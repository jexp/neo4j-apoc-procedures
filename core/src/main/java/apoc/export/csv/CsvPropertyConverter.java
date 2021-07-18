package apoc.export.csv;

import org.apache.commons.lang.StringUtils;
import org.neo4j.graphdb.Entity;

import java.util.List;
import java.util.Objects;

public class CsvPropertyConverter {

    public static boolean addPropertyToGraphEntity(Entity entity, CsvHeaderField field, Object value, CsvLoaderConfig config) {
        if (field.isIgnore() || value == null) {
            return false;
        }
        if (field.isArray()) {
            final List list = (List) value;
            final boolean listContainingNull = list.stream().anyMatch(Objects::isNull);
            if (listContainingNull) {
                return false;
            }
            final Object[] prototype = getPrototypeFor(field.getType());
            final Object[] array = list.toArray(prototype);
            entity.setProperty(field.getName(), array);
        } else {
            if (config.isIgnoreEmptyString() && value instanceof String && StringUtils.isBlank((String) value)) {
                return false;
            }
            entity.setProperty(field.getName(), value);
        }
        return true;
    }

    static Object[] getPrototypeFor(String type) {
        switch (type) {
            case "INT":
            case "LONG":    return new Long     [] {};
            case "FLOAT":
            case "DOUBLE":  return new Double   [] {};
            case "BOOLEAN": return new Boolean  [] {};
            case "BYTE":    return new Byte     [] {};
            case "SHORT":   return new Short    [] {};
            case "CHAR":    return new Character[] {};
            case "STRING":  return new String   [] {};
        }
        throw new IllegalStateException("Type " + type + " not supported.");
    }

}
