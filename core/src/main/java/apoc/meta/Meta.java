package apoc.meta;

import apoc.export.util.NodesAndRelsSubGraph;
import apoc.result.GraphResult;
import apoc.result.MapResult;
import apoc.result.VirtualGraph;
import apoc.result.VirtualNode;
import apoc.result.VirtualRelationship;
import apoc.util.MapUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.neo4j.cypher.export.CypherResultSubGraph;
import org.neo4j.cypher.export.DatabaseSubGraph;
import org.neo4j.cypher.export.SubGraph;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterable;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.schema.ConstraintDefinition;
import org.neo4j.graphdb.schema.ConstraintType;
import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.graphdb.schema.Schema;
import org.neo4j.graphdb.spatial.Point;
import org.neo4j.internal.helpers.collection.Iterables;
import org.neo4j.internal.helpers.collection.Pair;
import org.neo4j.internal.kernel.api.Read;
import org.neo4j.internal.kernel.api.TokenRead;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.UserFunction;
import org.neo4j.values.storable.DurationValue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static apoc.util.MapUtil.map;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonMap;
import static org.neo4j.internal.kernel.api.TokenRead.ANY_LABEL;
import static org.neo4j.internal.kernel.api.TokenRead.ANY_RELATIONSHIP_TYPE;

public class    Meta {

    @Context
    public Transaction tx;

    @Context
    public GraphDatabaseService db;

    @Context
    public KernelTransaction kernelTx;

    @Context
    public Transaction transaction;

    @Context public Log log;

    public static class ConstraintTracker {
        // The following maps are (label|rel-type)/constraintdefinition entries

        public static final Map<String, List<String>> relConstraints = new HashMap<>(20);;
        public static final Map<String, List<String>> nodeConstraints = new HashMap<>(20);;
    }

    public enum Types {
        INTEGER,FLOAT,STRING,BOOLEAN,RELATIONSHIP,NODE,PATH,NULL,ANY,MAP,LIST,POINT,DATE,DATE_TIME,LOCAL_TIME,LOCAL_DATE_TIME,TIME,DURATION;

        private String typeOfList = "ANY";

        private static Map<Class<?>, Class<?>> primitivesMapping = new HashMap(){{
            put(double.class, Double.class);
            put(float.class, Float.class);
            put(int.class, Integer.class);
            put(long.class, Long.class);
            put(short.class, Short.class);
            put(boolean.class, Boolean.class);
        }};

        @Override
        public String toString() {
            switch (this){
                case LIST:
                    return "LIST OF " + typeOfList;
                default:
                    return super.toString();
            }
        }

        public static Types of(Object value) {
            Types type = of(value == null ? null : value.getClass());
            if (type == Types.LIST && !value.getClass().isArray()) {
                type.typeOfList = inferType((List<?>) value);
            }
            return type;
        }

        public static Types of(Class<?> type) {
            if (type==null) return NULL;
            if (type.isArray()) {
                Types innerType = Types.of(type.getComponentType());
                Types returnType = LIST;
                returnType.typeOfList = innerType.toString();
                return returnType;
            }
            if (type.isPrimitive()) { type = primitivesMapping.getOrDefault(type, type); }
            if (Number.class.isAssignableFrom(type)) { return Double.class.isAssignableFrom(type) || Float.class.isAssignableFrom(type) ? FLOAT : INTEGER; }
            if (Boolean.class.isAssignableFrom(type)) { return BOOLEAN; }
            if (String.class.isAssignableFrom(type)) { return STRING; }
            if (Map.class.isAssignableFrom(type)) { return MAP; }
            if (Node.class.isAssignableFrom(type)) { return NODE; }
            if (Relationship.class.isAssignableFrom(type)) { return RELATIONSHIP; }
            if (Path.class.isAssignableFrom(type)) { return PATH; }
            if (Point.class.isAssignableFrom(type)){ return POINT; }
            if (List.class.isAssignableFrom(type)) { return LIST; }
            if (LocalDate.class.isAssignableFrom(type)) { return DATE; }
            if (LocalTime.class.isAssignableFrom(type)) { return LOCAL_TIME; }
            if (LocalDateTime.class.isAssignableFrom(type)) { return LOCAL_DATE_TIME; }
            if (DurationValue.class.isAssignableFrom(type)) { return DURATION; }
            if (OffsetTime.class.isAssignableFrom(type)) { return TIME; }
            if (ZonedDateTime.class.isAssignableFrom(type)) { return DATE_TIME; }
            return ANY;
        }

        public static Types from(String typeName) {
            if (typeName == null) {
                return STRING;
            }
            typeName = typeName.toUpperCase();
            for (Types type : values()) {
                if (type.name().startsWith(typeName)) return type;
            }
            return STRING;
        }

        public static String inferType(List<?> list) {
            Set<String> set = list.stream().limit(10).map(e -> of(e).name()).collect(Collectors.toSet());
            return set.size() != 1 ? "ANY" : set.iterator().next();
        }
    }

    public static class MetaResult {
        public String label;
        public String property;
        public long count;
        public boolean unique;
        public boolean index;
        public boolean existence;
        public String type;
        public boolean array;
        public List<Object> sample;
        public long leftCount; // 0,1,
        public long rightCount; // 0,1,many
        public long left; // 0,1,
        public long right; // 0,1,many
        public List<String> other = new ArrayList<>();
        public List<String> otherLabels = new ArrayList<>();
        public String elementType;

        public MetaResult addLabel(String label) {
            this.otherLabels.add(label);
            return this;
        }

        public MetaResult(String label, String name) {
            this.label = label;
            this.property = name;
        }

        public MetaResult inc() {
            count ++;
            return this;
        }
        public MetaResult rel(long out, long in) {
            this.type = Types.RELATIONSHIP.name();
            if (out>1) array = true;
            leftCount += out;
            rightCount += in;
            left = leftCount / count;
            right = rightCount / count;
            return this;
        }

        public MetaResult other(List<String> labels) {
            for (String l : labels) {
                if (!this.other.contains(l)) this.other.add(l);
            }
            return this;
        }

        public MetaResult type(String type) {
            this.type = type;
            return this;
        }

        public MetaResult array(boolean array) {
            this.array = array;
            return this;
        }

        public MetaResult elementType(String elementType) {
            switch(elementType){
                case "NODE" : this.elementType = "node"; break;
                case "RELATIONSHIP" : this.elementType = "relationship"; break;
            }
            return this;
        }
    }

    static final int SAMPLE = 100;

    @Deprecated
    @UserFunction
    @Description("apoc.meta.type(value) - type name of a value (INTEGER,FLOAT,STRING,BOOLEAN,RELATIONSHIP,NODE,PATH,NULL,UNKNOWN,MAP,LIST)")
    public String type(@Name("value") Object value) {
        return typeName(value);
    }
    @Deprecated
    @UserFunction
    @Description("apoc.meta.typeName(value) - type name of a value (INTEGER,FLOAT,STRING,BOOLEAN,RELATIONSHIP,NODE,PATH,NULL,UNKNOWN,MAP,LIST)")
    public String typeName(@Name("value") Object value) {
        Types type = Types.of(value);

        String typeName;

        // In order to keep the backwards compatibility
        switch (type) {
            case POINT: case DATE: case DATE_TIME: case LOCAL_TIME: case LOCAL_DATE_TIME: case TIME: case DURATION: case ANY:
                typeName = value.getClass().getSimpleName();
                break;
            case LIST:
                Class<?> clazz = value.getClass();
                if (value != null && clazz.isArray()) {
                    typeName = clazz.getComponentType().getSimpleName() + "[]";
                    break;
                }
            default:
                typeName = type.name();
        }

        return typeName;

    }

    @Deprecated
    @UserFunction
    @Description("apoc.meta.types(node-relationship-map)  - returns a map of keys to types")
    public Map<String,Object> types(@Name("properties") Object target) {
        Map<String,Object> properties = Collections.emptyMap();
        if (target instanceof Node) properties = ((Node)target).getAllProperties();
        if (target instanceof Relationship) properties = ((Relationship)target).getAllProperties();
        if (target instanceof Map) {
            //noinspection unchecked
            properties = (Map<String, Object>) target;
        }

        Map<String,Object> result = new LinkedHashMap<>(properties.size());
        properties.forEach((key,value) -> {
            result.put(key, typeName(value));
        });

        return result;
    }

    @Deprecated
    @UserFunction
    @Description("apoc.meta.isType(value,type) - returns a row if type name matches none if not (INTEGER,FLOAT,STRING,BOOLEAN,RELATIONSHIP,NODE,PATH,NULL,UNKNOWN,MAP,LIST)")
    public boolean isType(@Name("value") Object value, @Name("type") String type) {
        return type.equalsIgnoreCase(typeName(value));
    }

    @UserFunction("apoc.meta.cypher.isType")
    @Description("apoc.meta.cypher.isType(value,type) - returns a row if type name matches none if not (INTEGER,FLOAT,STRING,BOOLEAN,RELATIONSHIP,NODE,PATH,NULL,MAP,LIST OF <TYPE>,POINT,DATE,DATE_TIME,LOCAL_TIME,LOCAL_DATE_TIME,TIME,DURATION)")
    public boolean isTypeCypher(@Name("value") Object value, @Name("type") String type) {
        return type.equalsIgnoreCase(typeCypher(value));
    }

    @UserFunction("apoc.meta.cypher.type")
    @Description("apoc.meta.cypher.type(value) - type name of a value (INTEGER,FLOAT,STRING,BOOLEAN,RELATIONSHIP,NODE,PATH,NULL,MAP,LIST OF <TYPE>,POINT,DATE,DATE_TIME,LOCAL_TIME,LOCAL_DATE_TIME,TIME,DURATION)")
    public String typeCypher(@Name("value") Object value) {
        Types type = Types.of(value);

        switch (type) {
            case ANY: // TODO Check if it's necessary
                return value.getClass().getSimpleName();
            default:
                return type.toString();
        }
    }


    @UserFunction("apoc.meta.cypher.types")
    @Description("apoc.meta.cypher.types(node-relationship-map)  - returns a map of keys to types")
    public Map<String,Object> typesCypher(@Name("properties") Object target) {
        Map<String,Object> properties = Collections.emptyMap();
        if (target instanceof Node) properties = ((Node)target).getAllProperties();
        if (target instanceof Relationship) properties = ((Relationship)target).getAllProperties();
        if (target instanceof Map) {
            //noinspection unchecked
            properties = (Map<String, Object>) target;
        }

        Map<String,Object> result = new LinkedHashMap<>(properties.size());
        properties.forEach((key,value) -> {
            result.put(key, typeCypher(value));
        });

        return result;
    }


    public static class MetaStats {
        public final long labelCount;
        public final long relTypeCount;
        public final long propertyKeyCount;
        public final long nodeCount;
        public final long relCount;
        public final Map<String,Long> labels;
        public final Map<String,Long> relTypes;
        public final Map<String,Long> relTypesCount;
        public final Map<String,Object> stats;

        public MetaStats(long labelCount, long relTypeCount, long propertyKeyCount, long nodeCount, long relCount, Map<String, Long> labels, Map<String, Long> relTypes, Map<String, Long> relTypesCount) {
            this.labelCount = labelCount;
            this.relTypeCount = relTypeCount;
            this.propertyKeyCount = propertyKeyCount;
            this.nodeCount = nodeCount;
            this.relCount = relCount;
            this.labels = labels;
            this.relTypes = relTypes;
            this.relTypesCount = relTypesCount;
            this.stats = map("labelCount", labelCount, "relTypeCount", relTypeCount, "propertyKeyCount", propertyKeyCount,
                    "nodeCount", nodeCount, "relCount", relCount,
                    "labels", labels, "relTypes", relTypes);
        }
    }

    interface StatsCallback {
        void label(int labelId, String labelName, long count);
        void rel(int typeId, String typeName, long count);
        void rel(int typeId, String typeName, int labelId, String labelName, long out, long in);
    }

    @Procedure
    @Description("apoc.meta.stats yield labelCount, relTypeCount, propertyKeyCount, nodeCount, relCount, labels, relTypes, stats | returns the information stored in the transactional database statistics")
    public Stream<MetaStats> stats() {
        return Stream.of(collectStats());
    }

    private MetaStats collectStats() {
        Map<String, Long> relStatsCount = new LinkedHashMap<>();
        TokenRead tokenRead = kernelTx.tokenRead();
        Read read = kernelTx.dataRead();

        int labelCount = tokenRead.labelCount();
        int relTypeCount = tokenRead.relationshipTypeCount();

        Map<String, Long> labelStats = new LinkedHashMap<>(labelCount);
        Map<String, Long> relStats = new LinkedHashMap<>(2 * relTypeCount);

        collectStats(new DatabaseSubGraph(transaction), null, null, new StatsCallback() {
            public void label(int labelId, String labelName, long count) {
                if (count > 0) labelStats.put(labelName, count);
            }

            public void rel(int typeId, String typeName, long count) {
                if (count > 0) relStats.put("()-[:" + typeName + "]->()", count);
            }

            public void rel(int typeId, String typeName, int labelId, String labelName, long out, long in) {
                if (out > 0) {
                    relStatsCount.put(typeName, relStatsCount.getOrDefault(typeName, 0L) + out);
                    relStats.put("(:" + labelName + ")-[:" + typeName + "]->()", out);
                }
                if (in > 0) {
                    relStats.put("()-[:" + typeName + "]->(:" + labelName + ")", in);
                }
            }
        });

        return new MetaStats(
                labelCount,
                relTypeCount,
                tokenRead.propertyKeyCount(),
                read.countsForNodeWithoutTxState(ANY_LABEL),
                read.countsForRelationshipWithoutTxState(ANY_LABEL, ANY_RELATIONSHIP_TYPE, ANY_LABEL),
                labelStats,
                relStats,
                relStatsCount);
    }

    private void collectStats(SubGraph subGraph, Collection<String> labelNames, Collection<String> relTypeNames, StatsCallback cb) {
        TokenRead tokenRead = kernelTx.tokenRead();

        Map<String, Integer> labelMap = subGraph.labelsInUse(tokenRead, labelNames);
        Map<String, Integer> typeMap = subGraph.relTypesInUse(tokenRead, relTypeNames);

        Iterable<Label> labels = CollectionUtils.isNotEmpty(labelNames)
                ? labelNames.stream().map(Label::label).collect(Collectors.toList()) : subGraph.getAllLabelsInUse();
        Iterable<RelationshipType> types = CollectionUtils.isNotEmpty(relTypeNames)
                ? relTypeNames.stream().map(RelationshipType::withName).collect(Collectors.toList()) : subGraph.getAllRelationshipTypesInUse();

        labels.forEach(label -> {
            long count = subGraph.countsForNode(label);
            if (count > 0) {
                String name = label.name();
                int id = labelMap.get(name);
                cb.label(id, name, count);
                types.forEach(type -> {
                    long relCountOut = subGraph.countsForRelationship(label, type);
                    long relCountIn = subGraph.countsForRelationship(type, label);
                    cb.rel(typeMap.get(type.name()), type.name(), id, name, relCountOut, relCountIn);
                });
            }
        });
        types.forEach(type -> {
            String name = type.name();
            int id = typeMap.get(name);
            cb.rel(id, name, subGraph.countsForRelationship(type));
        });
    }

    @Procedure("apoc.meta.data.of")
    @Description("apoc.meta.data.of({graph}, {config})  - examines a subset of the graph to provide a tabular meta information")
    public Stream<MetaResult> dataOf(@Name(value = "graph") Object graph, @Name(value = "config",defaultValue = "{}") Map<String,Object> config) {
        MetaConfig metaConfig = new MetaConfig(config);
        final SubGraph subGraph;
        if (graph instanceof String) {
            Result result = tx.execute((String) graph);
            subGraph = CypherResultSubGraph.from(tx, result, metaConfig.isAddRelationshipsBetweenNodes());
        } else if (graph instanceof Map) {
            Map<String, Object> mGraph = (Map<String, Object>) graph;
            if (!mGraph.containsKey("nodes")) {
                throw new IllegalArgumentException("Graph Map must contains `nodes` field and `relationships` optionally");
            }
            subGraph = new NodesAndRelsSubGraph(tx, (Collection<Node>) mGraph.get("nodes"),
                    (Collection<Relationship>) mGraph.get("relationships"));
        } else if (graph instanceof VirtualGraph) {
            VirtualGraph vGraph = (VirtualGraph) graph;
            subGraph = new NodesAndRelsSubGraph(tx, vGraph.nodes(), vGraph.relationships());
        } else {
            throw new IllegalArgumentException("Supported inputs are String, VirtualGraph, Map");
        }
        return collectMetaData(subGraph, metaConfig).values().stream().flatMap(x -> x.values().stream());
    }

    // todo ask index for distinct values if index size < 10 or so
    // todo put index sizes for indexed properties
    @Procedure
    @Description("apoc.meta.data({config})  - examines a subset of the graph to provide a tabular meta information")
    public Stream<MetaResult> data(@Name(value = "config",defaultValue = "{}") Map<String,Object> config) {
        MetaConfig metaConfig = new MetaConfig(config);
        return collectMetaData(new DatabaseSubGraph(transaction), metaConfig).values().stream().flatMap(x -> x.values().stream());
    }

    @Procedure
    @Description("apoc.meta.schema({config})  - examines a subset of the graph to provide a map-like meta information")
    public Stream<MapResult> schema(@Name(value = "config",defaultValue = "{}") Map<String,Object> config) {
        MetaStats metaStats = collectStats();
        MetaConfig metaConfig = new MetaConfig(config);
        Map<String, Map<String, MetaResult>> metaData = collectMetaData(new DatabaseSubGraph(transaction), metaConfig);

        Map<String, Object> relationships = collectRelationshipsMetaData(metaStats, metaData);
        Map<String, Object> nodes = collectNodesMetaData(metaStats, metaData, relationships);

        nodes.putAll(relationships);
        return Stream.of(new MapResult(nodes));
    }


    // Start new code

    /**
     * This procedure is intended to replicate what's in the core Neo4j product, but with the crucial difference that it
     * supports flexible sampling options, and does not scan the entire database.  The result is producing a table of
     * metadata that is useful for generating "Tables 4 Labels" schema designs for RDBMSs, but in a more performant way.
     */
    @Procedure
    @Description("apoc.meta.nodeTypeProperties()")
    public Stream<Tables4LabelsProfile.NodeTypePropertiesEntry> nodeTypeProperties(@Name(value = "config",defaultValue = "{}") Map<String,Object> config) {
        MetaConfig metaConfig = new MetaConfig(config);
        try {
            return collectTables4LabelsProfile(metaConfig).asNodeStream();
        } catch (Exception e) {
            log.debug("meta.nodeTypeProperties(): Failed to return stream", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * This procedure is intended to replicate what's in the core Neo4j product, but with the crucial difference that it
     * supports flexible sampling options, and does not scan the entire database.  The result is producing a table of
     * metadata that is useful for generating "Tables 4 Labels" schema designs for RDBMSs, but in a more performant way.
     */
    @Procedure
    @Description("apoc.meta.relTypeProperties()")
    public Stream<Tables4LabelsProfile.RelTypePropertiesEntry> relTypeProperties(@Name(value = "config",defaultValue = "{}") Map<String,Object> config) {
        MetaConfig metaConfig = new MetaConfig(config);
        try {
            return collectTables4LabelsProfile(metaConfig).asRelStream();
        } catch (Exception e) {
            log.debug("meta.relTypeProperties(): Failed to return stream", e);
            throw new RuntimeException(e);
        }
    }

    private Tables4LabelsProfile collectTables4LabelsProfile (MetaConfig config) {
        Tables4LabelsProfile profile = new Tables4LabelsProfile();

        Schema schema = tx.schema();

        for (ConstraintDefinition cd : schema.getConstraints()) {
            if (cd.isConstraintType(ConstraintType.NODE_PROPERTY_EXISTENCE)) {
                List<String> props = new ArrayList<String>(10);
                if (ConstraintTracker.nodeConstraints.containsKey(cd.getLabel().name())) {
                    props = ConstraintTracker.nodeConstraints.get(cd.getLabel().name());
                }
                cd.getPropertyKeys().forEach(props::add);
                ConstraintTracker.nodeConstraints.put(cd.getLabel().name(),props);

            } else if (cd.isConstraintType(ConstraintType.RELATIONSHIP_PROPERTY_EXISTENCE)) {
                List<ConstraintDefinition> tcd = new ArrayList<ConstraintDefinition>(10);
                List<String> props = new ArrayList<String>(10);
                if (ConstraintTracker.relConstraints.containsKey(cd.getRelationshipType().name())) {
                    props = ConstraintTracker.relConstraints.get(cd.getRelationshipType().name());
                }
                cd.getPropertyKeys().forEach(props::add);
                ConstraintTracker.relConstraints.put(cd.getRelationshipType().name(), props);
            }
        }

        Map<String, Long> countStore = getLabelCountStore();

        Set<String> includeLabels = config.getIncludesLabels();
        Set<String> excludes = config.getExcludes();

        Set<String> includeRels = config.getIncludesRels();
        Set<String> excludeRels = config.getExcludeRels();

        for (Label label : tx.getAllLabelsInUse()) {
            String labelName = label.name();

            if (!excludes.contains(labelName) && (includeLabels.isEmpty() || includeLabels.contains(labelName))) {
                // Skip if explicitly excluded or at least 1 include specified and not included

                for (ConstraintDefinition cd : schema.getConstraints(label)) { profile.noteConstraint(label, cd); }
                for (IndexDefinition index : schema.getIndexes(label)) { profile.noteIndex(label, index); }

                long labelCount = countStore.get(labelName);
                long sample = getSampleForLabelCount(labelCount, config.getSample());

                //System.out.println("Sampling " + sample + " for " + labelName);

                try (ResourceIterator<Node> nodes = tx.findNodes(label)) {
                    int count = 1;
                    while (nodes.hasNext()) {
                        Node node = nodes.next();
                        if(count++ % sample == 0) {
                            boolean skipNode = false;
                            for (RelationshipType rel : node.getRelationshipTypes()) {
                                String relName = rel.name();
                                if (excludeRels.contains(relName)) {
                                    // Skip if explicitly excluded
                                    skipNode = true;
                                } else if (!includeRels.isEmpty() && !includeRels.contains(relName)) {
                                    // Skip if included set is specified and this is not in it.
                                    skipNode = true;
                                }
                            }
                            if (skipNode != true) {
                                profile.observe(node, config);
                            }
                        }
                    }
                }
            }
        }

        return profile.finished();
    }

    // End new code

    private Map<String, Map<String, MetaResult>> collectMetaData(SubGraph graph, MetaConfig config) {
        Map<String,Map<String,MetaResult>> metaData = new LinkedHashMap<>(100);

        Set<RelationshipType> types = Iterables.asSet(graph.getAllRelationshipTypesInUse());
        Map<String, Iterable<ConstraintDefinition>> relConstraints = new HashMap<>(20);
        for (RelationshipType type : graph.getAllRelationshipTypesInUse()) {
            metaData.put(type.name(), new LinkedHashMap<>(10));
            relConstraints.put(type.name(),graph.getConstraints(type));
        }
        for (Label label : graph.getAllLabelsInUse()) {
            Map<String,MetaResult> nodeMeta = new LinkedHashMap<>(50);
            String labelName = label.name();
            metaData.put(labelName, nodeMeta);
            Iterable<ConstraintDefinition> constraints = graph.getConstraints(label);
            Set<String> indexed = new LinkedHashSet<>();
            for (IndexDefinition index : graph.getIndexes(label)) {
                for (String prop : index.getPropertyKeys()) {
                    indexed.add(prop);
                }
            }
            long labelCount = graph.countsForNode(label);
            long sample = getSampleForLabelCount(labelCount, config.getSample());
            Iterator<Node> nodes = graph.findNodes(label);
            int count = 1;
            while (nodes.hasNext()) {
                Node node = nodes.next();
                if(count++ % sample == 0) {
                    addRelationships(metaData, nodeMeta, labelName, node, relConstraints, types);
                    addProperties(nodeMeta, labelName, constraints, indexed, node, node);
                }
            }
        }
        return metaData;
    }

    private Map<String, Long> getLabelCountStore() {
        List<String> labels = Iterables.stream(tx.getAllLabelsInUse()).map(label -> label.name()).collect(Collectors.toList());
        TokenRead tokenRead = kernelTx.tokenRead();
        return labels
                .stream()
                .collect(Collectors.toMap(e -> e, e -> kernelTx.dataRead().countsForNodeWithoutTxState(tokenRead.nodeLabel(e))));
    }

    public long getSampleForLabelCount(long labelCount, long sample) {
        if(sample != -1L) {
            long skipCount = labelCount / sample;
            long min = (long) Math.floor(skipCount - (skipCount * 0.1D));
            long max = (long) Math.ceil(skipCount + (skipCount * 0.1D));
            if (min >= max) {
                return -1L;
            }
            long randomValue = ThreadLocalRandom.current().nextLong(min, max);
            return randomValue == 0L ? -1L : randomValue; // it can't return zero as it's used in % ops
        } else {
            return sample;
        }
    }

    private Map<String, Object> collectNodesMetaData(MetaStats metaStats, Map<String, Map<String, MetaResult>> metaData, Map<String, Object> relationships) {
        Map<String, Object> nodes = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> startNodeNameToRelationshipsMap = new HashMap<>();
        for (String entityName : metaData.keySet()) {
            Map<String, MetaResult> entityData = metaData.get(entityName);
            Map<String, Object> entityProperties = new LinkedHashMap<>();
            Map<String, Object> entityRelationships = new LinkedHashMap<>();
            List<String> labels = new LinkedList<>();
            boolean isNode = true;
            for (String entityDataKey : entityData.keySet()) {
                MetaResult metaResult = entityData.get(entityDataKey);
                if (metaResult.elementType.equals("relationship")) {
                    isNode = false;
                    break;
                } else {
                    if (metaResult.unique)
                        labels = metaResult.otherLabels;
                    if (!metaResult.type.equals("RELATIONSHIP")) { // NODE PROPERTY
                        entityProperties.put(entityDataKey,
                                MapUtil.map("type", metaResult.type, "indexed", metaResult.index, "unique", metaResult.unique, "existence", metaResult.existence));
                    } else {
                        entityRelationships.put(metaResult.property,
                                MapUtil.map("direction", "out", "count", metaResult.rightCount, "labels", metaResult.other,
                                        "properties", ((Map<String, Object>) relationships.get(metaResult.property)).get("properties")));
                        metaResult.other.forEach(o -> {
                            Map<String, Object> mirroredRelationship = new LinkedHashMap<>();
                            mirroredRelationship.put(metaResult.property, MapUtil.map("direction", "in", "count", metaResult.leftCount, "labels", new LinkedList<>(Arrays.asList(metaResult.label)) ,
                                    "properties", ((Map<String, Object>) relationships.get(metaResult.property)).get("properties")));

                            if (startNodeNameToRelationshipsMap.containsKey(o))
                                startNodeNameToRelationshipsMap.get(o).add(mirroredRelationship);
                            else {
                                List<Map<String, Object>> relList = new LinkedList<>();
                                relList.add(mirroredRelationship);
                                startNodeNameToRelationshipsMap.put(o, relList);
                            }
                        });
                    }
                }
            }
            if (isNode) {
                nodes.put(entityName, MapUtil.map(
                        "type", "node",
                        "count", metaStats.labels.get(entityName),
                        "labels", labels,
                        "properties", entityProperties,
                        "relationships", entityRelationships
                ));
            }
        }
        setIncomingRelationships(nodes, startNodeNameToRelationshipsMap);
        return nodes;
    }

    private void setIncomingRelationships(Map<String, Object> nodes, Map<String, List<Map<String, Object>>> nodeNameToRelationshipsMap) {
        nodes.keySet().forEach(k-> {
            if (nodeNameToRelationshipsMap.containsKey(k)) {
                Map<String, Object> node = (Map<String, Object>) nodes.get(k);
                List<Map<String, Object>> relationshipsToAddList = nodeNameToRelationshipsMap.get(k);
                relationshipsToAddList.forEach(relationshipNameToRelationshipMap -> {
                    Map<String,Object> actualRelationshipsList = (Map<String, Object>) node.get("relationships");
                    relationshipNameToRelationshipMap.keySet().forEach(relationshipName -> {
                        if(actualRelationshipsList.containsKey(relationshipName)) {
                            Map<String, Object> relToAdd = (Map<String, Object>) relationshipNameToRelationshipMap.get(relationshipName);
                            Map<String, Object> existingRel = (Map<String, Object>) actualRelationshipsList.get(relationshipName);
                            List<String> labels = (List<String>) existingRel.get("labels");
                            labels.addAll((List<String>) relToAdd.get("labels"));
                        }
                        else  actualRelationshipsList.put(relationshipName, relationshipNameToRelationshipMap.get(relationshipName));
                    });
                });
            }
        });
    }

    private Map<String, Object> collectRelationshipsMetaData(MetaStats metaStats, Map<String, Map<String, MetaResult>> metaData) {
        Map<String, Object> relationships = new LinkedHashMap<>();
        for(String entityName : metaData.keySet()) {
            Map<String, MetaResult> entityData = metaData.get(entityName);
            Map<String, Object> entityProperties = new LinkedHashMap<>();
            boolean isRelationship = metaStats.relTypesCount.containsKey(entityName);
            for (String entityDataKey : entityData.keySet()) {
                MetaResult metaResult = entityData.get(entityDataKey);
                if (!metaResult.elementType.equals("relationship")) {
                    isRelationship = false;
                    break;
                }
                if (!metaResult.type.equals("RELATIONSHIP")) { // RELATIONSHIP PROPERTY
                    entityProperties.put(entityDataKey, MapUtil.map(
                            "type", metaResult.type,
                            "array", metaResult.array,
                            "existence", metaResult.existence));
                }
            }
            if (isRelationship) {
                relationships.put(entityName, MapUtil.map(
                        "type", "relationship",
                        "count", metaStats.relTypesCount.get(entityName),
                        "properties", entityProperties));
            }
        }
        return relationships;
    }

    private void addProperties(Map<String, MetaResult> properties, String labelName, Iterable<ConstraintDefinition> constraints, Set<String> indexed, Entity pc, Node node) {
        for (String prop : pc.getPropertyKeys()) {
            if (properties.containsKey(prop)) continue;
            MetaResult res = metaResultForProp(pc, labelName, prop);
            res.elementType(Types.of(pc).name());
            addSchemaInfo(res, prop, constraints, indexed, node);
            properties.put(prop,res);
        }
    }

    private void addRelationships(Map<String, Map<String, MetaResult>> metaData,
                                  Map<String, MetaResult> nodeMeta,
                                  String labelName,
                                  Node node,
                                  Map<String, Iterable<ConstraintDefinition>> relConstraints,
                                  Set<RelationshipType> types) {
        StreamSupport.stream(node.getRelationshipTypes().spliterator(), false)
                .filter(type -> types.contains(type))
                .forEach(type -> {
                    int out = node.getDegree(type, Direction.OUTGOING);
                    if (out == 0) return;

                    String typeName = type.name();

                    Iterable<ConstraintDefinition> constraints = relConstraints.get(typeName);
                    if (!nodeMeta.containsKey(typeName)) nodeMeta.put(typeName, new MetaResult(labelName,typeName));
//            int in = node.getDegree(type, Direction.INCOMING);

                    Map<String, MetaResult> typeMeta = metaData.get(typeName);
                    if (!typeMeta.containsKey(labelName)) typeMeta.put(labelName,new MetaResult(typeName,labelName));
                    MetaResult relMeta = nodeMeta.get(typeName);
                    addOtherNodeInfo(node, labelName, out, type, relMeta , typeMeta, constraints);
                });
    }

    private void addOtherNodeInfo(Node node, String labelName, int out, RelationshipType type, MetaResult relMeta, Map<String, MetaResult> typeMeta, Iterable<ConstraintDefinition> relConstraints) {
        MetaResult relNodeMeta = typeMeta.get(labelName);
        relMeta.elementType(Types.of(node).name());
        for (Relationship rel : node.getRelationships(Direction.OUTGOING, type)) {
            Node endNode = rel.getEndNode();
            List<String> labels = toStrings(endNode.getLabels());
            int in = endNode.getDegree(type, Direction.INCOMING);
            relMeta.inc().other(labels).rel(out , in);
            relNodeMeta.inc().other(labels).rel(out,in);
            addProperties(typeMeta, type.name(), relConstraints, Collections.emptySet(), rel, node);
            relNodeMeta.elementType(Types.RELATIONSHIP.name());
        }
    }

    private void addSchemaInfo(MetaResult res, String prop, Iterable<ConstraintDefinition> constraints, Set<String> indexed, Node node) {

        if (indexed.contains(prop)) {
            res.index = true;
        }
        if (constraints == null) return;
        for (ConstraintDefinition constraint : constraints) {
            for (String key : constraint.getPropertyKeys()) {
                if (key.equals(prop)) {
                    switch (constraint.getConstraintType()) {
                        case UNIQUENESS: res.unique = true;
                            node.getLabels().forEach(l -> {
                                if(res.label != l.name())
                                    res.addLabel(l.name());
                            });
                            break;
                        case NODE_PROPERTY_EXISTENCE:res.existence = true; break;
                        case RELATIONSHIP_PROPERTY_EXISTENCE: res.existence = true; break;
                    }
                }
            }
        }
    }

    private MetaResult metaResultForProp(Entity pc, String labelName, String prop) {
        MetaResult res = new MetaResult(labelName, prop);
        Object value = pc.getProperty(prop);
        res.type(Types.of(value).name());
        res.elementType(Types.of(pc).name());
        if (value.getClass().isArray()) {
            res.array = true;
        }
        return res;
    }

    private List<String> toStrings(Iterable<Label> labels) {
        List<String> res=new ArrayList<>(10);
        for (Label label : labels) {
            String name = label.name();
            res.add(name);
        }
        return res;
    }

    interface Sampler {
        void sample(Label label, int count, Node node);
        void sample(Label label, int count, Node node, RelationshipType type, Direction direction, int degree, Relationship rel);
    }
    public void sample(GraphDatabaseService db, Sampler sampler, int sampleSize) {
        for (Label label : tx.getAllLabelsInUse()) {
            ResourceIterator<Node> nodes = tx.findNodes(label);
            int count = 0;
            while (nodes.hasNext() && count++ < sampleSize) {
                Node node = nodes.next();
                sampler.sample(label,count,node);
                for (RelationshipType type : node.getRelationshipTypes()) {
                    sampleRels(sampleSize, sampler, label, count, node, type);
                }
            }
            nodes.close();
        }
    }

    private void sampleRels(int sampleSize, Sampler sampler, Label label, int count, Node node, RelationshipType type) {
        Direction direction = Direction.OUTGOING;
        int degree = node.getDegree(type, direction);
        sampler.sample(label,count,node,type,direction,degree,null);
        if (degree==0) return;
        ResourceIterator<Relationship> relIt = ((ResourceIterable<Relationship>)node.getRelationships(direction, type)).iterator();
        int relCount = 0;
        while (relIt.hasNext() && relCount++ < sampleSize) {
            sampler.sample(label,count,node,type,direction,degree,relIt.next());
        }
        relIt.close();
    }

    static class Pattern {
        private final String from;
        private final String type;
        private final String to;

        private Pattern(String from, String type, String to) {
            this.from = from;
            this.type = type;
            this.to = to;
        }
        public static Pattern of(String labelFrom, String type, String labelTo) {
            return new Pattern(labelFrom,type,labelTo);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o instanceof Pattern) {
                Pattern pattern = (Pattern) o;
                return from.equals(pattern.from) && type.equals(pattern.type) && to.equals(pattern.to);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * from.hashCode() + type.hashCode()) + to.hashCode();
        }

        public Label labelTo() {
            return Label.label(to);
        }
        public Label labelFrom() {
            return Label.label(from);
        }
        public RelationshipType relationshipType() {
            return RelationshipType.withName(type);
        }
    }
    @Procedure
    @Description("apoc.meta.graph - examines the full graph to create the meta-graph")
    public Stream<GraphResult> graph(@Name(value = "config",defaultValue = "{}") Map<String,Object> config) {
        MetaConfig metaConfig = new MetaConfig(config);
        return metaGraph(new DatabaseSubGraph(transaction), null, null, true, metaConfig);
    }

    @Procedure("apoc.meta.graph.of")
    @Description("apoc.meta.graph.of({graph}, {config})  - examines a subset of the graph to provide a graph meta information")
    public Stream<GraphResult> graphOf(@Name(value = "graph",defaultValue = "{}") Object graph, @Name(value = "config",defaultValue = "{}") Map<String,Object> config) {
        MetaConfig metaConfig = new MetaConfig(config);
        final SubGraph subGraph;
        if (graph instanceof String) {
            Result result = tx.execute((String) graph);
            subGraph = CypherResultSubGraph.from(tx, result, metaConfig.isAddRelationshipsBetweenNodes());
        } else if (graph instanceof Map) {
            Map<String, Object> mGraph = (Map<String, Object>) graph;
            if (!mGraph.containsKey("nodes")) {
                throw new IllegalArgumentException("Graph Map must contains `nodes` field and `relationships` optionally");
            }
            subGraph = new NodesAndRelsSubGraph(tx, (Collection<Node>) mGraph.get("nodes"),
                    (Collection<Relationship>) mGraph.get("relationships"));
        } else if (graph instanceof VirtualGraph) {
            VirtualGraph vGraph = (VirtualGraph) graph;
            subGraph = new NodesAndRelsSubGraph(tx, vGraph.nodes(), vGraph.relationships());
        } else {
            throw new IllegalArgumentException("Supported inputs are String, VirtualGraph, Map");
        }
        return metaGraph(subGraph,null, null, true, metaConfig);
    }

    private Stream<GraphResult> metaGraph(SubGraph subGraph, Collection<String> labelNames, Collection<String> relTypeNames, boolean removeMissing, MetaConfig metaConfig) {
        TokenRead tokenRead = kernelTx.tokenRead();

        Map<String, Integer> typeMap = subGraph.relTypesInUse(tokenRead, relTypeNames);
        Iterable<Label> labels = CollectionUtils.isNotEmpty(labelNames)
                ? labelNames.stream().map(Label::label).collect(Collectors.toList()) : subGraph.getAllLabelsInUse();
        Iterable<RelationshipType> types = CollectionUtils.isNotEmpty(relTypeNames)
                ? relTypeNames.stream().map(RelationshipType::withName).collect(Collectors.toList()) : subGraph.getAllRelationshipTypesInUse();


        Map<String, Node> vNodes = new TreeMap<>();
        Map<Pattern, Relationship> vRels = new HashMap<>(typeMap.size() * 2);

        labels.forEach(label -> {
            long count = subGraph.countsForNode(label);
            if (count > 0) {
                mergeMetaNode(label, vNodes, count);
            }
        });
        types.forEach(type -> {

            labels.forEach(start -> {
                labels.forEach(end -> {
                    String startLabel = start.name();
                    String endLabel = end.name();
                    String relType = type.name();
                    if (vRels.containsKey(Pattern.of(startLabel, relType, endLabel))) return;
                    long relCountOut = subGraph.countsForRelationship(start, type);
                    if (relCountOut == 0) return;
                    long relCountIn = subGraph.countsForRelationship(type, end);
                    if (relCountIn > 0) {
                        Node startNode = vNodes.get(startLabel);
                        Node endNode = vNodes.get(endLabel);
                        long global = subGraph.countsForRelationship(type);
                        Relationship vRel = new VirtualRelationship(startNode, endNode, type)
                                .withProperties(map("type", relType, "out", relCountOut, "in", relCountIn, "count", global));
                        vRels.put(Pattern.of(startLabel, relType, endLabel), vRel);
                    }
                });
            });
        });

        if (removeMissing) filterNonExistingRelationships(vRels, metaConfig);
        GraphResult graphResult = new GraphResult(new ArrayList<>(vNodes.values()), new ArrayList<>(vRels.values()));
        return Stream.of(graphResult);
    }

    private void filterNonExistingRelationships(Map<Pattern, Relationship> vRels, MetaConfig metaConfig) {
        Set<Pattern> rels = vRels.keySet();
        Map<Pair<String,String>,Set<Pattern>> aggregated = new HashMap<>();
        for (Pattern rel : rels) {
            combine(aggregated, Pair.of(rel.from, rel.type), rel);
            combine(aggregated, Pair.of(rel.type, rel.to), rel);
        }
        aggregated.values().stream()
                .filter( c -> c.size() > 1)
                .flatMap(Collection::stream)
                .filter( p -> !relationshipExists(p, vRels.get(p), metaConfig))
                .forEach(vRels::remove);
    }

    private boolean relationshipExists(Pattern p, Relationship relationship, MetaConfig metaConfig) {
        if (relationship==null) return false;
        double degreeFrom = (double)(long)relationship.getProperty("out")  / (long)relationship.getStartNode().getProperty("count");
        double degreeTo = (double)(long)relationship.getProperty("in")  / (long)relationship.getEndNode().getProperty("count");

        if (degreeFrom < degreeTo) {
            if (relationshipExists(p.labelFrom(), p.labelTo(), p.relationshipType(), Direction.OUTGOING, metaConfig)) return true;
        } else {
            if (relationshipExists(p.labelTo(), p.labelFrom(), p.relationshipType(), Direction.INCOMING, metaConfig)) return true;
        }
        return false;
    }

    private boolean relationshipExists(Label labelFromLabel, Label labelToLabel, RelationshipType relationshipType, Direction direction, MetaConfig metaConfig) {
        Map<String, Long> countStore = getLabelCountStore();
        try (ResourceIterator<Node> nodes = tx.findNodes(labelFromLabel)) {
            long count = 1L;
            String labelName = labelFromLabel.name();
            long labelCount = countStore.get(labelName);
            long sample = getSampleForLabelCount(labelCount, metaConfig.getSample());
            while (nodes.hasNext()) {
                count++;
                if(count % sample == 0) {
                    Node node = nodes.next();
                    long maxRels = metaConfig.getMaxRels();
                    for (Relationship rel : node.getRelationships(direction, relationshipType)) {
                        Node otherNode = direction == Direction.OUTGOING ? rel.getEndNode() : rel.getStartNode();
                        if (otherNode.hasLabel(labelToLabel)) return true;
                        if (maxRels != -1 && maxRels-- == 0) break;
                    }
                }
            }
        }
        return false;
    }

    private void combine(Map<Pair<String, String>, Set<Pattern>> aggregated, Pair<String, String> p, Pattern rel) {
        if (!aggregated.containsKey(p)) aggregated.put(p,new HashSet<>());
        aggregated.get(p).add(rel);
    }


    @Procedure
    @Description("apoc.meta.graphSample() - examines the database statistics to build the meta graph, very fast, might report extra relationships")
    public Stream<GraphResult> graphSample(@Name(value = "config",defaultValue = "{}") Map<String,Object> config) {
        MetaConfig metaConfig = new MetaConfig(config);
        return metaGraph(new DatabaseSubGraph(transaction), null, null, false, metaConfig);
    }

    @Procedure
    @Description("apoc.meta.subGraph({labels:[labels],rels:[rel-types], excludes:[labels,rel-types]}) - examines a sample sub graph to create the meta-graph")
    public Stream<GraphResult> subGraph(@Name("config") Map<String,Object> config ) {

        MetaConfig metaConfig = new MetaConfig(config);

        return filterResultStream(metaConfig.getExcludes(), metaGraph(new DatabaseSubGraph(transaction), metaConfig.getIncludesLabels(), metaConfig.getIncludesRels(),true, metaConfig));
    }

    private Stream<GraphResult> filterResultStream(Set<String> excludes, Stream<GraphResult> graphResultStream) {
        if (excludes == null || excludes.isEmpty()) return graphResultStream;
        return graphResultStream.map(gr -> {
            Iterator<Node> it = gr.nodes.iterator();
            while (it.hasNext()) {
                Node node = it.next();
                if (containsLabelName(excludes,node)) it.remove();
            }

            Iterator<Relationship> it2 = gr.relationships.iterator();
            while (it2.hasNext()) {
                Relationship relationship = it2.next();
                if (excludes.contains(relationship.getType().name()) ||
                        containsLabelName(excludes, relationship.getStartNode()) ||
                        containsLabelName(excludes, relationship.getEndNode())) {
                    it2.remove();
                }
            }

            return gr;
        });
    }

    private boolean containsLabelName(Set<String> excludes, Node node) {
        for (Label label : node.getLabels()) {
            if (excludes.contains(label.name())) return true;
        }
        return false;
    }

    private Node mergeMetaNode(Label label, Map<String, Node> labels, long increment) {
        String name = label.name();
        Node vNode = labels.get(name);
        if (vNode == null) {
            vNode = new VirtualNode(new Label[] {label}, Collections.singletonMap("name", name));
            labels.put(name, vNode);
        }
        if (increment > 0 ) vNode.setProperty("count",(((Number)vNode.getProperty("count",0L)).longValue())+increment);
        return vNode;
    }

    private void addRel(Map<List<String>, Relationship> rels, Map<String, Node> labels, Relationship rel, boolean strict) {
        String typeName = rel.getType().name();
        Node startNode = rel.getStartNode();
        Node endNode = rel.getEndNode();
        for (Label labelA : startNode.getLabels()) {
            Node nodeA = strict ? labels.get(labelA.name()) : mergeMetaNode(labelA,labels,0);
            if (nodeA == null) continue;
            for (Label labelB : endNode.getLabels()) {
                List<String> key = asList(labelA.name(), labelB.name(), typeName);
                Relationship vRel = rels.get(key);
                if (vRel==null) {
                    Node nodeB = strict ? labels.get(labelB.name()) : mergeMetaNode(labelB,labels,0);
                    if (nodeB == null) continue;
                    vRel = new VirtualRelationship(nodeA,nodeB,rel.getType()).withProperties(singletonMap("type",typeName));
                    rels.put(key,vRel);
                }
                vRel.setProperty("count",((long)vRel.getProperty("count",0L))+1);
            }
        }
    }

    static class RelInfo {
        final Set<String> properties = new HashSet<>();
        final NodeInfo from,to;
        final String type;
        int count;

        public RelInfo(NodeInfo from, NodeInfo to, String type) {
            this.from = from;
            this.to = to;
            this.type = type;
        }
        public void add(Relationship relationship) {
            for (String key : relationship.getPropertyKeys()) properties.add(key);
            count++;
        }
    }
    static class NodeInfo {
        final Set<String> labels=new HashSet<>();
        final Set<String> properties = new HashSet<>();
        long count, minDegree, maxDegree, sumDegree;

        private void add(Node node) {
            count++;
            int degree = node.getDegree();
            sumDegree += degree;
            if (degree > maxDegree) maxDegree = degree;
            if (degree < minDegree) minDegree = degree;

            for (Label label : node.getLabels()) labels.add(label.name());
            for (String key : node.getPropertyKeys()) properties.add(key);
        }
        Map<String,Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("labels",labels.toArray());
            map.put("properties",properties.toArray());
            map.put("count",count);
            map.put("minDegree",minDegree);
            map.put("maxDegree",maxDegree);
            map.put("avgDegree",sumDegree/count);
            return map;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof NodeInfo && labels.equals(((NodeInfo) o).labels);

        }

        @Override
        public int hashCode() {
            return labels.hashCode();
        }
    }
}
