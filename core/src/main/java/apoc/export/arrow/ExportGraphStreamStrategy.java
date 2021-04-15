package apoc.export.arrow;

import apoc.Pools;
import apoc.result.ByteArrayResult;
import apoc.util.Util;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.dictionary.DictionaryProvider;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.apache.arrow.vector.ipc.ArrowWriter;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.neo4j.cypher.export.SubGraph;
import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.internal.helpers.collection.Iterables;
import org.neo4j.logging.Log;
import org.neo4j.procedure.TerminationGuard;

import java.io.OutputStream;
import java.nio.channels.Channels;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static apoc.export.arrow.ExportArrowStrategy.toField;

public class ExportGraphStreamStrategy implements ExportArrowStreamStrategy<SubGraph> {

    private final GraphDatabaseService db;
    private final Pools pools;
    private final TerminationGuard terminationGuard;
    private final Log logger;

    private final AtomicInteger counter;

    private final RootAllocator bufferAllocator;

    private Schema schema;

    private static Field FIELD_ID = new Field("<id>", FieldType.nullable(Types.MinorType.BIGINT.getType()), null);
    private static Field FIELD_LABELS = new Field("labels", FieldType.nullable(Types.MinorType.LIST.getType()),
            List.of(new Field("$data$", FieldType.nullable(Types.MinorType.VARCHAR.getType()), null)));
    private static Field FIELD_SOURCE_ID = new Field("<source.id>", FieldType.nullable(Types.MinorType.BIGINT.getType()), null);
    private static Field FIELD_TARGET_ID = new Field("<target.id>", FieldType.nullable(Types.MinorType.BIGINT.getType()), null);
    private static Field FIELD_TYPE = new Field("<type>", FieldType.nullable(Types.MinorType.VARCHAR.getType()), null);

    public ExportGraphStreamStrategy(GraphDatabaseService db, Pools pools, TerminationGuard terminationGuard, Log logger) {
        this.db = db;
        this.pools = pools;
        this.terminationGuard = terminationGuard;
        this.logger = logger;
        this.counter = new AtomicInteger();
        this.bufferAllocator = new RootAllocator();
    }

    @Override
    public Iterator<Map<String, Object>> toIterator(SubGraph subGraph) {
        return Stream.concat(Iterables.stream(subGraph.getNodes()), Iterables.stream(subGraph.getRelationships()))
                .map(this::entityToMap)
                .iterator();
    }

    @Override
    public Stream<ByteArrayResult> export(SubGraph subGraph, ArrowConfig config) {
        Map<String, Object> configMap = createConfigMap(subGraph, config);
        this.schemaFor(List.of(configMap));
        return ExportArrowStreamStrategy.super.export(subGraph, config);
    }

    @Override
    public TerminationGuard getTerminationGuard() {
        return terminationGuard;
    }

    @Override
    public BufferAllocator getBufferAllocator() {
        return bufferAllocator;
    }

    @Override
    public AtomicInteger getCounter() {
        return counter;
    }

    @Override
    public GraphDatabaseService getGraphDatabaseApi() {
        return db;
    }

    @Override
    public ExecutorService getExecutorService() {
        return pools.getDefaultExecutorService();
    }

    @Override
    public Log getLogger() {
        return logger;
    }

    private Map<String, Object> createConfigMap(SubGraph subGraph, ArrowConfig config) {
        final List<String> allLabelsInUse = Iterables.stream(subGraph.getAllLabelsInUse())
                .map(Label::name)
                .collect(Collectors.toList());
        final List<String> allRelationshipTypesInUse = Iterables.stream(subGraph.getAllRelationshipTypesInUse())
                .map(RelationshipType::name)
                .collect(Collectors.toList());
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("includeLabels", allLabelsInUse);
        if (!allRelationshipTypesInUse.isEmpty()) {
            configMap.put("includeRels", allRelationshipTypesInUse);
        }
        configMap.putAll(config.getConfig());
        return configMap;
    }

    private Map<String, Object> entityToMap(Entity entity) {
        Map<String, Object> flattened = new HashMap<>();
        flattened.put(FIELD_ID.getName(), entity.getId());
        if (entity instanceof Node) {
            flattened.put(FIELD_LABELS.getName(), Util.labelStrings((Node) entity));
        } else {
            Relationship rel = (Relationship) entity;
            flattened.put(FIELD_TYPE.getName(), rel.getType().name());
            flattened.put(FIELD_SOURCE_ID.getName(), rel.getStartNodeId());
            flattened.put(FIELD_TARGET_ID.getName(), rel.getEndNodeId());
        }
        flattened.putAll(entity.getAllProperties());
        return flattened;
    }

    @Override
    public ArrowWriter newArrowWriter(VectorSchemaRoot root, OutputStream out) {
        return new ArrowStreamWriter(root, new DictionaryProvider.MapDictionaryProvider(), Channels.newChannel(out));
    }

    @Override
    public synchronized Schema schemaFor(List<Map<String, Object>> records) {
        if (schema == null) {
            final Map<String, Object> cfg = records.get(0);
            final Map<String, Object> parameters = Map.of("config", cfg);
            final Set<Field> allFields = new HashSet<>();
            Set<Field> nodeFields = db.executeTransactionally("CALL apoc.meta.nodeTypeProperties($config)",
                    parameters, result -> result.stream()
                            .flatMap(m -> {
                                String propertyName = (String) m.get("propertyName");
                                List<String> propertyTypes = (List<String>) m.get("propertyTypes");
                                return propertyTypes.stream()
                                        .map(propertyType -> toField(propertyName, new HashSet<>(propertyTypes)));
                            })
                            .collect(Collectors.toSet()));
            allFields.add(FIELD_ID);
            allFields.add(FIELD_LABELS);
            allFields.addAll(nodeFields);

            if (cfg.containsKey("includeRels")) {
                final Set<Field> relFields = db.executeTransactionally("CALL apoc.meta.relTypeProperties($config)",
                        parameters, result -> result.stream()
                                .flatMap(m -> {
                                    String propertyName = (String) m.get("propertyName");
                                    List<String> propertyTypes = (List<String>) m.get("propertyTypes");
                                    return propertyTypes.stream()
                                            .map(propertyType -> toField(propertyName, new HashSet<>(propertyTypes)));
                                })
                                .collect(Collectors.toSet()));
                allFields.add(FIELD_SOURCE_ID);
                allFields.add(FIELD_TARGET_ID);
                allFields.add(FIELD_TYPE);
                allFields.addAll(relFields);
            }

            schema = new Schema(allFields);
        }
        return schema;
    }


}