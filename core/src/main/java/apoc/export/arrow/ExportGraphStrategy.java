package apoc.export.arrow;

import apoc.util.Util;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.neo4j.cypher.export.SubGraph;
import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.internal.helpers.collection.Iterables;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static apoc.export.arrow.ArrowUtils.FIELD_ID;
import static apoc.export.arrow.ArrowUtils.FIELD_LABELS;
import static apoc.export.arrow.ArrowUtils.FIELD_SOURCE_ID;
import static apoc.export.arrow.ArrowUtils.FIELD_TARGET_ID;
import static apoc.export.arrow.ArrowUtils.FIELD_TYPE;
import static apoc.export.arrow.ExportArrowStrategy.toField;

public interface ExportGraphStrategy {

    default Schema schemaFor(GraphDatabaseService db, List<Map<String, Object>> records) {
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
        return new Schema(allFields);
    }

    default Map<String, Object> entityToMap(Entity entity) {
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

    default Map<String, Object> createConfigMap(SubGraph subGraph, ArrowConfig config) {
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
}
