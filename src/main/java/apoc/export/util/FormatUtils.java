package apoc.export.util;

import apoc.util.JsonUtil;
import apoc.util.Util;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.spatial.Point;
import org.neo4j.values.storable.DurationValue;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static apoc.util.Util.labelStrings;
import static apoc.util.Util.map;

/**
 * @author mh
 * @since 23.02.16
 */
public class FormatUtils {

    public static DecimalFormat decimalFormat = new DecimalFormat() {
        {
            setMaximumFractionDigits(340);
            setGroupingUsed(false);
            setDecimalSeparatorAlwaysShown(false);
            DecimalFormatSymbols symbols = getDecimalFormatSymbols();
            symbols.setDecimalSeparator('.');
            setDecimalFormatSymbols(symbols);
        }
    };

    public static String formatNumber(Number value) {
        if (value == null) return null;
        return decimalFormat.format(value);
    }

    public static String formatString(Object value) {
        return "\"" + String.valueOf(value).replaceAll("\\\\", "\\\\\\\\")
                .replaceAll("\n","\\\\n")
                .replaceAll("\r","\\\\r")
                .replaceAll("\t","\\\\t")
                .replaceAll("\"","\\\\\"") + "\"";
    }

    public static String joinLabels(Node node, String delimiter) {
        return getLabelsAsStream(node).collect(Collectors.joining(delimiter));
    }

    public static Map<String,Object> toMap(PropertyContainer pc) {
        if (pc == null) return null;
        if (pc instanceof Node) {
            Node node = (Node) pc;
            return map("id", node.getId(), "labels",labelStrings(node),
                    "properties",pc.getAllProperties());
        }
        if (pc instanceof Relationship) {
            Relationship rel = (Relationship) pc;
            return map("id", rel.getId(), "type", rel.getType().name(),
                    "start", rel.getStartNode().getId(),"end", rel.getEndNode().getId(),
                    "properties",pc.getAllProperties());
        }
        throw new RuntimeException("Invalid graph element "+pc);
    }
    public static String toString(Object value) {
        if (value == null) return "";
        if (value instanceof Path) {
            return toString(StreamSupport.stream(((Path)value).spliterator(),false).map(FormatUtils::toMap).collect(Collectors.toList()));
        }
        if (value instanceof PropertyContainer) {
            return Util.toJson(toMap((PropertyContainer) value)); // todo id, label, type ?
        }
        if (value.getClass().isArray() || value instanceof Iterable || value instanceof Map) {
            return Util.toJson(value);
        }
        if (value instanceof Number) {
            return formatNumber((Number)value);
        }
        if (value instanceof Point) {
            return formatPoint((Point) value);
        }
        if (value instanceof DurationValue) {
            return formatDurationValue((DurationValue) value);
        }
        return value.toString();
    }

    public static String formatDurationValue(DurationValue value) {
        StringBuilder str = new StringBuilder().append("P");
        str.append(value.get(ChronoUnit.MONTHS)).append('M');
        str.append(value.get(ChronoUnit.DAYS)).append('D');
        str.append('T');
        str.append(value.get(ChronoUnit.SECONDS));
        str.append(".");
        str.append(value.get(ChronoUnit.NANOS)).append('S');
        return str.toString();
    }

    public static String formatPoint(Point value) {
        try {
            return JsonUtil.OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<String> getLabelsSorted(Node node) {
        return getLabelsAsStream(node).collect(Collectors.toList());
    }

    private static Stream<String> getLabelsAsStream(Node node) {
        return StreamSupport.stream(node.getLabels().spliterator(),false).map(Label::name).sorted();
    }
}
