/*
 * Copyright (c) 2010-2018. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.eventhandling;

import org.axonframework.common.Assert;
import org.axonframework.common.AxonNonTransientException;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.eventhandling.tokenstore.TokenStore;
import org.axonframework.eventhandling.tokenstore.UnableToClaimTokenException;
import org.axonframework.eventsourcing.DomainEventMessage;
import org.axonframework.eventsourcing.GenericTrackedDomainEventMessage;
import org.axonframework.eventsourcing.eventstore.TrackingToken;
import org.axonframework.messaging.MessageStream;
import org.axonframework.messaging.StreamableMessageSource;
import org.axonframework.messaging.interceptors.TransactionManagingInterceptor;
import org.axonframework.messaging.unitofwork.BatchingUnitOfWork;
import org.axonframework.messaging.unitofwork.RollbackConfiguration;
import org.axonframework.messaging.unitofwork.RollbackConfigurationType;
import org.axonframework.messaging.unitofwork.UnitOfWork;
import org.axonframework.monitoring.MessageMonitor;
import org.axonframework.monitoring.NoOpMessageMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;
import static org.axonframework.common.io.IOUtils.closeQuietly;

/**
 * EventProcessor implementation that tracks events from a {@link StreamableMessageSource}.
 * <p>
 * A supplied {@link TokenStore} allows the EventProcessor to keep track of its position in the event log. After
 * processing an event batch the EventProcessor updates its tracking token in the TokenStore.
 * <p>
 * A TrackingEventProcessor is able to continue processing from the last stored token when it is restarted. It is also
 * capable of replaying events from any starting token. To replay the entire event log simply remove the tracking token
 * of this processor from the TokenStore. To replay from a given point first update the entry for this processor in the
 * TokenStore before starting this processor.
 * <p>
 * <p>
 * Note, the {@link #getName() name} of the EventProcessor is used to obtain the tracking token from the TokenStore, so
 * take care when renaming a TrackingEventProcessor.
 * <p/>
 *
 * @author Rene de Waele
 * @author Christophe Bouhier
 */
public class TrackingEventProcessor extends AbstractEventProcessor {
    private static final Logger logger = LoggerFactory.getLogger(TrackingEventProcessor.class);

    private final StreamableMessageSource<TrackedEventMessage<?>> messageSource;
    private final TokenStore tokenStore;
    private final TransactionManager transactionManager;
    private final int batchSize;
    private final int segmentsSize;

    private final ActivityCountingThreadFactory threadFactory;
    private final AtomicReference<State> state = new AtomicReference<>(State.NOT_STARTED);
    private final CopyOnWriteArraySet<Integer> activeSegments = new CopyOnWriteArraySet<>();
    private final int maxThreadCount;
    private final String segmentIdResourceKey;
    private final String lastTokenResourceKey;

    /**
     * Initializes an EventProcessor with given {@code name} that subscribes to the given {@code messageSource} for
     * events. Actual handling of event messages is deferred to the given {@code eventHandlerInvoker}.
     * <p>
     * The EventProcessor is initialized with a batch size of 1, a {@link PropagatingErrorHandler}, a {@link
     * RollbackConfigurationType#ANY_THROWABLE} and a {@link NoOpMessageMonitor}.
     *
     * @param name                The name of the event processor
     * @param eventHandlerInvoker The component that handles the individual events
     * @param messageSource       The message source (e.g. Event Bus) which this event processor will track
     * @param tokenStore          Used to store and fetch event tokens that enable the processor to track its progress
     * @param transactionManager  The transaction manager used when processing messages
     */
    public TrackingEventProcessor(String name, EventHandlerInvoker eventHandlerInvoker,
                                  StreamableMessageSource<TrackedEventMessage<?>> messageSource, TokenStore tokenStore,
                                  TransactionManager transactionManager) {
        this(name, eventHandlerInvoker, messageSource, tokenStore, transactionManager, NoOpMessageMonitor.INSTANCE);
    }

    /**
     * Initializes an EventProcessor with given {@code name} that subscribes to the given {@code messageSource} for
     * events. Actual handling of event messages is deferred to the given {@code eventHandlerInvoker}.
     * <p>
     * The EventProcessor is initialized with a batch size of 1, a {@link PropagatingErrorHandler} and a {@link
     * RollbackConfigurationType#ANY_THROWABLE}.
     *
     * @param name                The name of the event processor
     * @param eventHandlerInvoker The component that handles the individual events
     * @param messageSource       The message source (e.g. Event Bus) which this event processor will track
     * @param tokenStore          Used to store and fetch event tokens that enable the processor to track its progress
     * @param transactionManager  The transaction manager used when processing messages
     * @param messageMonitor      Monitor to be invoked before and after event processing
     */
    public TrackingEventProcessor(String name, EventHandlerInvoker eventHandlerInvoker,
                                  StreamableMessageSource<TrackedEventMessage<?>> messageSource, TokenStore tokenStore,
                                  TransactionManager transactionManager,
                                  MessageMonitor<? super EventMessage<?>> messageMonitor) {
        this(name, eventHandlerInvoker, messageSource, tokenStore, transactionManager, messageMonitor,
             RollbackConfigurationType.ANY_THROWABLE, PropagatingErrorHandler.INSTANCE,
             TrackingEventProcessorConfiguration.forSingleThreadedProcessing());
    }

    /**
     * Initializes an EventProcessor with given {@code name} that subscribes to the given {@code messageSource} for
     * events. Actual handling of event messages is deferred to the given {@code eventHandlerInvoker}.
     *
     * @param name                  The name of the event processor
     * @param eventHandlerInvoker   The component that handles the individual events
     * @param messageSource         The message source (e.g. Event Bus) which this event processor will track
     * @param tokenStore            Used to store and fetch event tokens that enable the processor to track its
     *                              progress
     * @param transactionManager    The transaction manager used when processing messages
     * @param messageMonitor        Monitor to be invoked before and after event processing
     * @param rollbackConfiguration Determines rollback behavior of the UnitOfWork while processing a batch of events
     * @param errorHandler          Invoked when a UnitOfWork is rolled back during processing
     * @param config                The configuration for the event processor.
     */
    public TrackingEventProcessor(String name, EventHandlerInvoker eventHandlerInvoker,
                                  StreamableMessageSource<TrackedEventMessage<?>> messageSource, TokenStore tokenStore,
                                  TransactionManager transactionManager,
                                  MessageMonitor<? super EventMessage<?>> messageMonitor,
                                  RollbackConfiguration rollbackConfiguration, ErrorHandler errorHandler,
                                  TrackingEventProcessorConfiguration config) {
        super(name, eventHandlerInvoker, rollbackConfiguration, errorHandler, messageMonitor);

        this.batchSize = config.getBatchSize();

        this.messageSource = requireNonNull(messageSource);
        this.tokenStore = requireNonNull(tokenStore);

        this.segmentsSize = config.getInitialSegmentsCount();
        this.transactionManager = transactionManager;

        this.maxThreadCount = config.getMaxThreadCount();
        this.threadFactory = new ActivityCountingThreadFactory(config.getThreadFactory(name));
        this.segmentIdResourceKey = "Processor[" + name + "]/SegmentId";
        this.lastTokenResourceKey = "Processor[" + name + "]/Token";

        registerInterceptor(new TransactionManagingInterceptor<>(transactionManager));
        registerInterceptor((unitOfWork, interceptorChain) -> {
            if (!(unitOfWork instanceof BatchingUnitOfWork) || ((BatchingUnitOfWork) unitOfWork).isFirstMessage()) {
                tokenStore.extendClaim(getName(), unitOfWork.getResource(segmentIdResourceKey));
            }
            if (!(unitOfWork instanceof BatchingUnitOfWork) || ((BatchingUnitOfWork) unitOfWork).isLastMessage()) {
                unitOfWork.onPrepareCommit(uow -> tokenStore.storeToken(unitOfWork.getResource(lastTokenResourceKey),
                                                                        name,
                                                                        unitOfWork.getResource(segmentIdResourceKey)));
            }
            return interceptorChain.proceed();
        });
    }

    /**
     * Start this processor. The processor will open an event stream on its message source in a new thread using {@link
     * StreamableMessageSource#openStream(TrackingToken)}. The {@link TrackingToken} used to open the stream will be
     * fetched from the {@link TokenStore}.
     */
    @Override
    public void start() {
        State previousState = state.getAndSet(State.STARTED);
        if (!previousState.isRunning()) {
            startSegmentWorkers();
        }
    }

    /**
     * Fetch and process event batches continuously for as long as the processor is not shutting down. The processor
     * will process events in batches. The maximum size of size of each event batch is configurable.
     * <p>
     * Events with the same tracking token (which is possible as result of upcasting) should always be processed in
     * the same batch. In those cases the batch size may be larger than the one configured.
     *
     * @param segment The {@link Segment} of the Stream that should be processed.
     */
    protected void processingLoop(Segment segment) {
        MessageStream<TrackedEventMessage<?>> eventStream = null;
        long errorWaitTime = 1;
        try {
            while (state.get().isRunning()) {
                try {
                    eventStream = ensureEventStreamOpened(eventStream, segment);
                    processBatch(segment, eventStream);
                    errorWaitTime = 1;
                } catch (UnableToClaimTokenException e) {
                    if (errorWaitTime == 1) {
                        logger.info("Token is owned by another node. Waiting for it to become available...");
                    }
                    errorWaitTime = 5;
                    waitFor(errorWaitTime);
                } catch (Exception e) {
                    // make sure to start with a clean event stream. The exception may have caused an illegal state
                    if (errorWaitTime == 1) {
                        logger.warn("Error occurred. Starting retry mode.", e);
                    }
                    logger.warn("Releasing claim on token and preparing for retry in {}s", errorWaitTime);
                    releaseToken(segment);
                    closeQuietly(eventStream);
                    eventStream = null;
                    waitFor(errorWaitTime);
                    errorWaitTime = Math.min(errorWaitTime * 2, 60);
                }
            }
        } finally {
            closeQuietly(eventStream);
            releaseToken(segment);
        }
    }

    private void waitFor(long errorWaitTime) {
        try {
            Thread.sleep(errorWaitTime * 1000);
        } catch (InterruptedException e1) {
            shutDown();
            Thread.currentThread().interrupt();
            logger.warn("Thread interrupted. Preparing to shut down event processor");
        }
    }

    private void releaseToken(Segment segment) {
        try {
            transactionManager.executeInTransaction(() -> tokenStore.releaseClaim(getName(), segment.getSegmentId()));
        } catch (Exception e) {
            // whatever.
        }
    }

    private void processBatch(Segment segment, MessageStream<TrackedEventMessage<?>> eventStream) throws Exception {
        List<TrackedEventMessage<?>> batch = new ArrayList<>();
        try {
            TrackingToken lastToken = null;
            if (eventStream.hasNextAvailable(1, TimeUnit.SECONDS)) {
                for (int i = 0; i < batchSize * 10 && batch.size() < batchSize && eventStream.hasNextAvailable(); i++) {
                    final TrackedEventMessage<?> trackedEventMessage = eventStream.nextAvailable();
                    lastToken = trackedEventMessage.trackingToken();
                    if (canHandle(trackedEventMessage, segment)) {
                        batch.add(trackedEventMessage);
                    } else {
                        reportIgnored(trackedEventMessage);
                    }
                }
                if (batch.isEmpty()) {
                    TrackingToken finalLastToken = lastToken;
                    transactionManager.executeInTransaction(() -> tokenStore.storeToken(finalLastToken, getName(), segment.getSegmentId()));
                    return;
                }
            } else {
                // refresh claim on token
                transactionManager.executeInTransaction(() -> tokenStore.extendClaim(getName(), segment.getSegmentId()));
                return;
            }

            TrackingToken finalLastToken = lastToken;
            // make sure all subsequent events with the same token (if non-null) as the last are added as well.
            // These are the result of upcasting and should always be processed in the same batch.
            while (lastToken != null && eventStream.peek().filter(event -> finalLastToken.equals(event.trackingToken())).isPresent()) {
                final TrackedEventMessage<?> trackedEventMessage = eventStream.nextAvailable();
                if (canHandle(trackedEventMessage, segment)) {
                    batch.add(trackedEventMessage);
                }
            }

            UnitOfWork<? extends EventMessage<?>> unitOfWork = new BatchingUnitOfWork<>(batch);
            unitOfWork.resources().put(segmentIdResourceKey, segment.getSegmentId());
            unitOfWork.resources().put(lastTokenResourceKey, finalLastToken);
            processInUnitOfWork(batch, unitOfWork, segment);

        } catch (InterruptedException e) {
            logger.error(String.format("Event processor [%s] was interrupted. Shutting down.", getName()), e);
            this.shutDown();
            Thread.currentThread().interrupt();
        }
    }

    private MessageStream<TrackedEventMessage<?>> ensureEventStreamOpened(
            MessageStream<TrackedEventMessage<?>> eventStreamIn, Segment segment) {
        MessageStream<TrackedEventMessage<?>> eventStream = eventStreamIn;
        if (eventStream == null && state.get().isRunning()) {
            final TrackingToken trackingToken = transactionManager.fetchInTransaction(() -> tokenStore.fetchToken(getName(), segment.getSegmentId()));
            logger.info("Fetched token: {} for segment: {}", trackingToken, segment);
            eventStream = transactionManager.fetchInTransaction(
                    () -> doOpenStream(trackingToken));
        }
        return eventStream;
    }

    private MessageStream<TrackedEventMessage<?>> doOpenStream(TrackingToken trackingToken) {
        if (trackingToken instanceof ReplayToken) {
            return new ReplayingMessageStream((ReplayToken) trackingToken,
                                              messageSource.openStream(((ReplayToken) trackingToken).unwrap()));
        }
        return messageSource.openStream(trackingToken);
    }

    /**
     * Sets the paused flag, causing processor threads to shut down.
     *
     * @deprecated in favor of {@link #shutDown()}.
     */
    @Deprecated
    public void pause() {
        this.state.updateAndGet(s -> s.isRunning() ? State.PAUSED : s);
    }

    /**
     * Resets tokens to the initial state. This effectively causes a replay.
     * <p>
     * Before attempting to reset the tokens, the caller must stop this processor, as well as any instances of the
     * same logical processor that may be running in the cluster. Failure to do so will cause the reset to fail,
     * as a processor can only reset the tokens if it is able to claim them all.
     */
    public void resetTokens() {
        Assert.state(supportsReset(), () -> "The handlers assigned to this Processor do not support a reset");
        Assert.state(!isRunning() && activeProcessorThreads() == 0,
                     () -> "TrackingProcessor must be shut down before triggering a reset");
        transactionManager.executeInTransaction(() -> {
            int[] segments = tokenStore.fetchSegments(getName());
            TrackingToken[] tokens = new TrackingToken[segments.length];
            for (int i = 0; i < segments.length; i++) {
                tokens[i] = tokenStore.fetchToken(getName(), segments[i]);
            }
            // we now have all tokens, hurray
            eventHandlerInvoker().performReset();

            for (int i = 0; i < tokens.length; i++) {
                tokenStore.storeToken(new ReplayToken(tokens[i]), getName(), segments[i]);
            }
        });
    }

    /**
     * Indicates whether this tracking processor supports a "reset". Generally, a reset is supported if at least one
     * of the event handlers assigned to this processor supports it, and no handlers explicitly prevent the resets.
     *
     * @return {@code true} if resets are supported, {@code false} otherwise
     */
    public boolean supportsReset() {
        return eventHandlerInvoker().supportsReset();
    }

    /**
     * Indicates whether this processor is currently running (i.e. consuming events from a stream).
     *
     * @return {@code true} when running, otherwise {@code false}
     */
    public boolean isRunning() {
        return state.get().isRunning();
    }

    /**
     * Indicates whether the processor has been paused due to an error. In such case, the processor has forcefully
     * paused, as it wasn't able to automatically recover.
     * <p>
     * Note that this method also returns {@code false} when the processor was stooped using {@link #shutDown()}.
     *
     * @return {@code true} when paused due to an error, otherwise {@code false}
     */
    public boolean isError() {
        return state.get() == State.PAUSED_ERROR;
    }

    /**
     * Shut down the processor.
     */
    @Override
    public void shutDown() {
        if (state.getAndUpdate(s -> State.SHUT_DOWN) != State.SHUT_DOWN) {
            logger.info("Shutdown state set for Processor '{}'. Awaiting termination...", getName());
            try {
                while (threadFactory.activeThreads() > 0) {
                    Thread.sleep(1);
                }
            } catch (InterruptedException e) {
                logger.info("Thread was interrupted while waiting for TrackingProcessor '{}' shutdown.", getName());
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Returns an approximation of the number of threads currently processing events.
     *
     * @return an approximation of the number of threads currently processing events
     */
    public int activeProcessorThreads() {
        return this.activeSegments.size();
    }

    /**
     * Get the state of the event processor. This will indicate whether or not the processor has started or is shutting
     * down.
     *
     * @return the processor state
     */
    protected State getState() {
        return state.get();
    }

    /**
     * Starts the {@link TrackingSegmentWorker workers} for a number of segments. When only the
     * {@link Segment#ROOT_SEGMENT root } segment {@link TokenStore#fetchSegments(String) exists} in the  TokenStore,
     * it will be split in multiple segments as configured by the
     * {@link TrackingEventProcessorConfiguration#andInitialSegmentsCount(int)}, otherwise the existing segments in
     * the TokenStore will be used.
     * <p/>
     * An attempt will be made to instantiate a {@link TrackingSegmentWorker} for each segment. This will succeed when
     * the number of threads matches the requested segments. The number of active threads can be configured with
     * {@link TrackingEventProcessorConfiguration#forParallelProcessing(int)}. When insufficient threads are available
     * to serve the number of segments, it will result in some segments not being processed.
     */
    protected void startSegmentWorkers() {
        threadFactory.newThread(new WorkerLauncher()).start();
    }

    /**
     * Instructs the current Thread to sleep until the given deadline. This method may be overridden to check for
     * flags that have been set to return earlier than the given deadline.
     * <p>
     * The default implementation will sleep in blocks of 100ms, intermittently checking for the processor's state. Once
     * the processor stops running, this method will return immediately (after detecting the state change).
     *
     * @param millisToSleep The number of milliseconds to sleep
     * @throws InterruptedException whn the Thread is interrupted
     */
    protected void doSleepFor(long millisToSleep) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millisToSleep;
        while (getState().isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }

    protected enum State {

        NOT_STARTED(false), STARTED(true), PAUSED(false), SHUT_DOWN(false), PAUSED_ERROR(false);

        private final boolean allowProcessing;

        State(boolean allowProcessing) {
            this.allowProcessing = allowProcessing;
        }

        boolean isRunning() {
            return allowProcessing;
        }
    }

    private static class ActivityCountingThreadFactory implements ThreadFactory {

        private final AtomicInteger threadCount = new AtomicInteger(0);
        private final ThreadFactory delegate;

        public ActivityCountingThreadFactory(ThreadFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public Thread newThread(Runnable r) {
            return delegate.newThread(new CountingRunnable(r, threadCount));
        }

        public int activeThreads() {
            return threadCount.get();
        }

    }

    private static class CountingRunnable implements Runnable {
        private final Runnable delegate;
        private final AtomicInteger counter;

        public CountingRunnable(Runnable delegate, AtomicInteger counter) {
            this.delegate = delegate;
            this.counter = counter;
        }

        @Override
        public void run() {
            counter.incrementAndGet();
            try {
                delegate.run();
            } finally {
                counter.decrementAndGet();
            }
        }
    }

    private class TrackingSegmentWorker implements Runnable {

        private final Segment segment;

        public TrackingSegmentWorker(Segment segment) {
            this.segment = segment;
        }

        @Override
        public void run() {
            try {
                processingLoop(segment);
            } catch (Throwable e) {
                logger.error("Processing loop ended due to uncaught exception. Processor pausing.", e);
                state.set(State.PAUSED_ERROR);
            } finally {
                activeSegments.remove(segment.getSegmentId());
            }
        }

        @Override
        public String toString() {
            return "TrackingSegmentWorker{" +
                    "processor=" + getName() +
                    ", segment=" + segment +
                    '}';
        }
    }

    private class WorkerLauncher implements Runnable {
        @Override
        public void run() {
            while (getState().isRunning()) {
                String processorName = TrackingEventProcessor.this.getName();
                int[] tokenStoreCurrentSegments = tokenStore.fetchSegments(processorName);

                // When in an initial stage, split segments to the requested number.
                if (tokenStoreCurrentSegments.length == 0 && segmentsSize > 0) {
                    tokenStoreCurrentSegments = transactionManager.fetchInTransaction(
                            () -> {
                                tokenStore.initializeTokenSegments(processorName, segmentsSize);
                                return tokenStore.fetchSegments(processorName);
                            });
                }
                Segment[] segments = Segment.computeSegments(tokenStoreCurrentSegments);

                // Submit segmentation workers matching the size of our thread pool (-1 for the current dispatcher).
                // Keep track of the last processed segments...
                TrackingSegmentWorker workingInCurrentThread = null;
                boolean attemptImmediateRetry = false;
                for (int i = 0; i < segments.length
                        && activeSegments.size() < maxThreadCount; i++) {
                    Segment segment = segments[i];

                    if (activeSegments.add(segment.getSegmentId())) {
                        try {
                            transactionManager.executeInTransaction(() -> tokenStore.fetchToken(processorName, segment.getSegmentId()));
                        } catch (UnableToClaimTokenException ucte) {
                            // When not able to claim a token for a given segment, we skip the
                            logger.debug("Unable to claim the token for segment: {}. It is owned by another process", segment.getSegmentId());
                            activeSegments.remove(segment.getSegmentId());
                            attemptImmediateRetry = true;
                            continue;
                        } catch (Exception e) {
                            activeSegments.remove(segment.getSegmentId());
                            if (AxonNonTransientException.isCauseOf(e)) {
                                logger.error("An unrecoverable error has occurred wile attempting to claim a token for segment: {}. Shutting down processor [{}].", segment.getSegmentId(), getName(), e);
                                state.set(State.PAUSED_ERROR);
                                break;
                            }
                            logger.info("An error occurred while attempting to claim a token for segment: {}. Will retry later...", segment.getSegmentId(), e);
                            continue;
                        }

                        TrackingSegmentWorker trackingSegmentWorker = new TrackingSegmentWorker(segment);
                        if (threadFactory.activeThreads() < maxThreadCount) {
                            logger.info("Dispatching new tracking segment worker: {}", trackingSegmentWorker);
                            threadFactory.newThread(trackingSegmentWorker).start();
                        } else {
                            workingInCurrentThread = trackingSegmentWorker;
                            break;
                        }
                    }
                }

                // We're not able to spawn new threads, so this thread should also start processing.
                if (nonNull(workingInCurrentThread)) {
                    logger.info("Using current Thread for last segment segment worker: {}", workingInCurrentThread);
                    workingInCurrentThread.run();
                    break;
                }

                try {
                    if (!attemptImmediateRetry) {
                        doSleepFor(5000);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private class ReplayingMessageStream implements MessageStream<TrackedEventMessage<?>> {

        private final MessageStream<TrackedEventMessage<?>> delegate;
        private ReplayToken lastToken;

        public ReplayingMessageStream(ReplayToken token, MessageStream<TrackedEventMessage<?>> delegate) {
            this.delegate = delegate;
            this.lastToken = token;
        }

        @Override
        public Optional<TrackedEventMessage<?>> peek() {
            return delegate.peek();
        }

        @Override
        public boolean hasNextAvailable(int timeout, TimeUnit unit) throws InterruptedException {
            return delegate.hasNextAvailable(timeout, unit);
        }

        @Override
        public TrackedEventMessage<?> nextAvailable() throws InterruptedException {
            TrackedEventMessage<?> trackedEventMessage = alterToken(delegate.nextAvailable());
            this.lastToken = trackedEventMessage.trackingToken() instanceof ReplayToken
                    ? (ReplayToken) trackedEventMessage.trackingToken()
                    : null;
            return trackedEventMessage;
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public boolean hasNextAvailable() {
            return delegate.hasNextAvailable();
        }

        @SuppressWarnings("unchecked")
        public <T> TrackedEventMessage<T> alterToken(TrackedEventMessage<T> message) {
            if (lastToken == null) {
                return message;
            }
            if (message instanceof DomainEventMessage) {
                return new GenericTrackedDomainEventMessage<>(lastToken.advancedTo(message.trackingToken()), (DomainEventMessage<T>) message);
            } else {
                return new GenericTrackedEventMessage<>(lastToken.advancedTo(message.trackingToken()), message);
            }
        }
    }
}
