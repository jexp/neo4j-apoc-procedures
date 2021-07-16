package apoc.meta.tablesforlabels;
import org.neo4j.graphdb.Label;

import java.util.*;

public class PropertyTracker {
    public String name;
    public Set<String> types;
    public boolean mandatory;
    public long observations;
    public long nulls;

    public PropertyTracker(String name) {
        this.name = name;
        types = new HashSet<>(3);
        mandatory = false;
        observations = 0L;
        nulls = 0L;
    }

    public void addObservation(Object value) {
        observations++;
        if (value == null) { nulls++; }
        types.add(assignTypeName(value));
    }

    private String assignTypeName(Object value) {
        String typeName = value.getClass().getCanonicalName();
        if (typeMappings.containsKey(typeName)) {
            return typeMappings.get(typeName);
        }

        return typeName.replace("java.lang.", "");
    }

    public List<String> propertyTypes() {
        List<String> ret = new ArrayList<>(types);
        Collections.sort(ret);
        return ret;
    }

    public static final Map<String,String> typeMappings = new HashMap<String,String>();
    static {
        typeMappings.put("java.lang.String", "String");
        typeMappings.put("java.lang.String[]", "StringArray");
        typeMappings.put("java.lang.Double", "Double");
        typeMappings.put("java.lang.Double[]", "DoubleArray");
        typeMappings.put("double[]", "DoubleArray");
        typeMappings.put("java.lang.Integer", "Integer");
        typeMappings.put("java.lang.Integer[]", "IntegerArray");
        typeMappings.put("int[]", "IntegerArray");
        typeMappings.put("java.lang.Long", "Long");
        typeMappings.put("java.lang.Long[]", "LongArray");
        typeMappings.put("long[]", "LongArray");
        typeMappings.put("org.neo4j.values.storable.PointValue", "Point");
        typeMappings.put("org.neo4j.values.storable.PointValue[]", "PointArray");
        typeMappings.put("java.time.ZonedDateTime", "DateTime");
        typeMappings.put("java.time.ZonedDateTime[]", "DateTimeArray");
        typeMappings.put("java.lang.Boolean", "Boolean");
        typeMappings.put("java.lang.Boolean[]", "BooleanArray");
        typeMappings.put("boolean", "Boolean");
        typeMappings.put("boolean[]", "BooleanArray");
        typeMappings.put("java.time.LocalDate", "Date");
        typeMappings.put("java.time.LocalDate[]", "DateArray");
        typeMappings.put("java.time.LocalDateTime", "LocalDateTime");
        typeMappings.put("java.time.LocalDateTime[]", "LocalDateTimeArray");
        typeMappings.put("java.time.LocalTime", "LocalTime");
        typeMappings.put("java.time.LocalTime[]", "LocalTimeArray");
        typeMappings.put("org.neo4j.values.storable.DurationValue", "Duration");
        typeMappings.put("org.neo4j.values.storable.DurationValue[]", "DurationArray");
        typeMappings.put("java.time.OffsetTime", "Time");
        typeMappings.put("java.time.OffsetTime[]", "TimeArray");
    }
}
