package apoc.export.arrow;

import apoc.meta.Meta;
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

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static apoc.export.arrow.ArrowUtils.FIELD_ID;
import static apoc.export.arrow.ArrowUtils.FIELD_LABELS;
import static apoc.export.arrow.ArrowUtils.FIELD_SOURCE_ID;
import static apoc.export.arrow.ArrowUtils.FIELD_TARGET_ID;
import static apoc.export.arrow.ArrowUtils.FIELD_TYPE;
import static apoc.export.arrow.ExportArrowStrategy.fromMetaType;
import static apoc.export.arrow.ExportArrowStrategy.toField;

public interface ExportResultStrategy {

    default Schema schemaFor(GraphDatabaseService db, List<Map<String, Object>> records) {
        final List<Field> fields = records.stream()
                .flatMap(m -> m.entrySet().stream())
                .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), fromMetaType(Meta.Types.of(e.getValue()))))
                .collect(Collectors.groupingBy(e -> e.getKey(), Collectors.mapping(e -> e.getValue(), Collectors.toSet())))
                .entrySet()
                .stream()
                .map(e -> toField(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
        return new Schema(fields);
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
