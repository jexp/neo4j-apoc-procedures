package apoc.result;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Arrays.asList;

/**
 * @author mh
 * @since 16.03.16
 */
public class VirtualRelationship implements Relationship {
    private static AtomicLong MIN_ID = new AtomicLong(-1);
    private final Node startNode;
    private final Node endNode;
    private final RelationshipType type;
    private final long id;
    private final Map<String, Object> props = new HashMap<>();

    public VirtualRelationship(Node startNode, Node endNode, RelationshipType type) {
        this.id = MIN_ID.getAndDecrement();
        this.startNode = startNode;
        this.endNode = endNode;
        this.type = type;
    }

    public VirtualRelationship(long id, Node startNode, Node endNode, RelationshipType type, Map<String, Object> props) {
        this.id = id;
        this.startNode = startNode;
        this.endNode = endNode;
        this.type = type;
        this.props.putAll(props);
    }
    
    public static Relationship from(VirtualNode start, VirtualNode end, Relationship rel) {
        return new VirtualRelationship(start, end, rel.getType()).withProperties(rel.getAllProperties());
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public void delete() {
        if (getStartNode() instanceof VirtualNode) ((VirtualNode) getStartNode()).delete(this);
        if (getEndNode() instanceof VirtualNode) ((VirtualNode) getEndNode()).delete(this);
    }

    @Override
    public Node getStartNode() {
        return startNode;
    }

    @Override
    public Node getEndNode() {
        return endNode;
    }

    @Override
    public Node getOtherNode(Node node) {
        return node.equals(startNode) ? endNode : node.equals(endNode) ? startNode : null;
    }

    @Override
    public Node[] getNodes() {
        return new Node[] {
            startNode, endNode
        } ;
    }

    @Override
    public RelationshipType getType() {
        return type;
    }

    @Override
    public boolean isType(RelationshipType relationshipType) {
        return relationshipType.name().equals(type.name());
    }

    @Override
    public boolean hasProperty(String s) {
        return props.containsKey(s);
    }

    @Override
    public Object getProperty(String s) {
        return props.get(s);
    }

    @Override
    public Object getProperty(String s, Object o) {
        Object res = props.get(s);
        return res == null ? o : res;
    }

    @Override
    public void setProperty(String s, Object o) {
        props.put(s, o);
    }

    @Override
    public Object removeProperty(String s) {
        return props.remove(s);
    }

    @Override
    public Iterable<String> getPropertyKeys() {
        return props.keySet();
    }

    @Override
    public Map<String, Object> getProperties(String... strings) {
        Map<String, Object> res = new LinkedHashMap<>(props);
        res.keySet().retainAll(asList(strings));
        return res;
    }

    @Override
    public Map<String, Object> getAllProperties() {
        return props;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof Relationship && id == ((Relationship) o).getId();

    }

    @Override
    public int hashCode() {
        return (int) (id ^ (id >>> 32));
    }

    public Relationship withProperties(Map<String, Object> props) {
        this.props.putAll(props);
        return this;
    }

    @Override
    public String toString()
    {
        return "VirtualRelationship{" + "startNode=" + startNode.getLabels() + ", endNode=" + endNode.getLabels() + ", " +
                "type=" + type + '}';
    }
}
