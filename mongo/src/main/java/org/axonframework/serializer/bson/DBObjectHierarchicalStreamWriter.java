package org.axonframework.serializer.bson;

import com.mongodb.DBObject;
import com.thoughtworks.xstream.io.ExtendedHierarchicalStreamWriter;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import org.axonframework.common.Assert;

import java.util.Stack;

/**
 * HierarchicalStreamWriter implementation that writes objects into a MongoDB DBObject structure. Use the {@link
 * DBObjectHierarchicalStreamReader} to read the object back.
 *
 * @author Allard Buijze
 * @since 2.0
 */
public class DBObjectHierarchicalStreamWriter implements ExtendedHierarchicalStreamWriter {

    private final Stack<BSONNode> itemStack = new Stack<BSONNode>();
    private final DBObject root;

    /**
     * Initialize the writer to write the object structure to the given <code>root</code> DBObject.
     * <p/>
     * Note that the given <code>root</code> DBObject must not contain any data yet.
     *
     * @param root The root DBObject to which the serialized structure will be added. Must not contain any data.
     */
    public DBObjectHierarchicalStreamWriter(DBObject root) {
        Assert.isTrue(root.keySet().isEmpty(), "The given root object must be empty.");
        this.root = root;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void startNode(String name) {
        if (itemStack.empty()) {
            itemStack.push(new BSONNode(name));
        } else {
            itemStack.push(itemStack.peek().addChildNode(name));
        }
    }

    @Override
    public void addAttribute(String name, String value) {
        itemStack.peek().setAttribute(name, value);
    }

    @Override
    public void setValue(String text) {
        itemStack.peek().setValue(text);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void endNode() {
        BSONNode closingElement = itemStack.pop();
        if (itemStack.isEmpty()) {
            // we've popped the last one, so we're done
            root.putAll(closingElement.asDBObject());
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    @Override
    public HierarchicalStreamWriter underlyingWriter() {
        return this;
    }

    @Override
    public void startNode(String name, Class clazz) {
        startNode(name);
    }
}
