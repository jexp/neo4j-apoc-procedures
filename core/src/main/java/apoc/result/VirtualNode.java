package apoc.result;

import apoc.util.Util;
import org.neo4j.graphdb.*;
import org.neo4j.internal.helpers.collection.FilteringIterable;
import org.neo4j.internal.helpers.collection.Iterables;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;

/**
 * @author mh
 * @since 16.03.16
 */
public class VirtualNode implements Node {
    private static AtomicLong MIN_ID = new AtomicLong(-1);
    private final Set<String> labels = new LinkedHashSet<>();
    private final Map<String, Object> props = new HashMap<>();
    private final List<Relationship> rels = new ArrayList<>();
    private final long id;

    public VirtualNode(Label[] labels, Map<String, Object> props) {
        this.id = MIN_ID.getAndDecrement();
        addLabels(asList(labels));
        this.props.putAll(props);
    }

    public VirtualNode(long nodeId, Label[] labels, Map<String, Object> props) {
        this.id = nodeId;
        addLabels(asList(labels));
        this.props.putAll(props);
    }

    public VirtualNode(long nodeId) {
        this.id = nodeId;
    }

    public VirtualNode(Node node, List<String> propertyNames) {
        this.id = node.getId();
        this.labels.addAll(Util.labelStrings(node));
        String[] keys = propertyNames.toArray(new String[propertyNames.size()]);
        this.props.putAll(node.getProperties(keys));
    }

    public static VirtualNode from(Node node) {
        return new VirtualNode(node, Iterables.asList(node.getPropertyKeys()));
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public void delete() {
        for (Relationship rel : rels) {
            rel.delete();
        }
    }

    @Override
    public Iterable<Relationship> getRelationships() {
        return rels;
    }

    @Override
    public boolean hasRelationship() {
        return !rels.isEmpty();
    }

    @Override
    public Iterable<Relationship> getRelationships(RelationshipType... relationshipTypes) {
        return new FilteringIterable<>(rels, (r) -> isType(r, relationshipTypes));
    }

    private boolean isType(Relationship r, RelationshipType... relationshipTypes) {
        for (RelationshipType type : relationshipTypes) {
            if (r.isType(type)) return true;
        }
        return false;
    }

    @Override
    public Iterable<Relationship> getRelationships(Direction direction, RelationshipType... relationshipTypes) {
        return new FilteringIterable<>(rels, (r) -> isType(r, relationshipTypes) && isDirection(r, direction));
    }

    private boolean isDirection(Relationship r, Direction direction) {
        return direction == Direction.BOTH || direction == Direction.OUTGOING && r.getStartNode().equals(this) || direction == Direction.INCOMING && r.getEndNode().equals(this);
    }

    @Override
    public boolean hasRelationship(RelationshipType... relationshipTypes) {
        return getRelationships(relationshipTypes).iterator().hasNext();
    }

    @Override
    public boolean hasRelationship(Direction direction, RelationshipType... relationshipTypes) {
        return getRelationships(direction, relationshipTypes).iterator().hasNext();
    }

    @Override
    public Iterable<Relationship> getRelationships(Direction direction) {
        return new FilteringIterable<>(rels, (r) -> isDirection(r, direction));
    }

    @Override
    public boolean hasRelationship(Direction direction) {
        return getRelationships(direction).iterator().hasNext();
    }

    @Override
    public Relationship getSingleRelationship(RelationshipType relationshipType, Direction direction) {
        return Iterables.single(getRelationships(direction, relationshipType));
    }

    @Override
    public VirtualRelationship createRelationshipTo(Node node, RelationshipType relationshipType) {
        VirtualRelationship rel = new VirtualRelationship(this, node, relationshipType);
        rels.add(rel);
        if (node instanceof VirtualNode) { // register the inverse relationship into the target virtual node only if it is not a self relationship
            VirtualNode target = (VirtualNode) node;
            if (!target.rels.contains(rel)) {
                target.rels.add(rel);
            }
        }
        return rel;
    }

    public VirtualRelationship createRelationshipFrom(Node start, RelationshipType relationshipType) {
        VirtualRelationship rel = new VirtualRelationship(start, this, relationshipType);
        rels.add(rel);
        if (start instanceof VirtualNode) { // register the inverse relationship into the start virtual node only if it is not a self relationship
            VirtualNode startVirtual = (VirtualNode) start;
            if (!startVirtual.rels.contains(rel)) {
                startVirtual.rels.add(rel);
            }
        }
        return rel;
    }

    @Override
    public Iterable<RelationshipType> getRelationshipTypes() {
        return rels.stream().map(Relationship::getType).collect(Collectors.toList());
    }

    @Override
    public int getDegree() {
        return rels.size();
    }

    @Override
    public int getDegree(RelationshipType relationshipType) {
        return (int) Iterables.count(getRelationships(relationshipType));
    }

    @Override
    public int getDegree(Direction direction) {
        return (int) Iterables.count(getRelationships(direction));
    }

    @Override
    public int getDegree(RelationshipType relationshipType, Direction direction) {
        return (int) Iterables.count(getRelationships(direction, relationshipType));
    }

    @Override
    public void addLabel(Label label) {
        labels.add(label.name());
    }

    public void addLabels(Iterable<Label> labels) {
        for (Label label : labels) {
            addLabel(label);
        }
    }

    @Override
    public void removeLabel(Label label) {
        labels.remove(label.name());
    }

    @Override
    public boolean hasLabel(Label label) {
        return labels.contains(label.name());
    }

    @Override
    public Iterable<Label> getLabels() {
        return labels.stream().map(Label::label).collect(Collectors.toList());
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
        Object value = props.get(s);
        return value == null ? o : value;
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
        HashMap<String, Object> res = new HashMap<>(props);
        res.keySet().retainAll(asList(strings));
        return res;
    }

    @Override
    public Map<String, Object> getAllProperties() {
        return props;
    }

    void delete(Relationship rel) {
        rels.remove(rel);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof Node && id == ((Node) o).getId();

    }

    @Override
    public int hashCode() {
        return (int) (id ^ (id >>> 32));
    }

    @Override
    public String toString() {
        return "VirtualNode{" + "id=" + id  + ", labels=" + labels + ", props=" + props + ", rels=" + rels + '}';
    }
}
