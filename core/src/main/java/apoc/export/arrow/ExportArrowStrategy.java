package apoc.export.arrow;

import apoc.meta.Meta;
import apoc.util.JsonUtil;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BaseVariableWidthVector;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.impl.UnionListWriter;
import org.apache.arrow.vector.ipc.ArrowWriter;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.logging.Log;
import org.neo4j.procedure.TerminationGuard;
import org.neo4j.values.storable.DurationValue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

public interface ExportArrowStrategy<IN, OUT> {

    OUT export(IN data, ArrowConfig config);

    Object convertValue(Object data);

    ArrowWriter newArrowWriter(VectorSchemaRoot root, OutputStream out);

    Schema schemaFor(List<Map<String, Object>> rows);

    TerminationGuard getTerminationGuard();

    BufferAllocator getBufferAllocator();

    GraphDatabaseService getGraphDatabaseApi();

    ExecutorService getExecutorService();

    Log getLogger();

    static String fromMetaType(Meta.Types type) {
        switch (type) {
            case INTEGER:
                return "Long";
            case FLOAT:
                return "Double";
            case LIST:
                String inner = type.toString().substring("LIST OF ".length()).trim();
                final Meta.Types innerType = Meta.Types.from(inner);
                if (innerType == Meta.Types.LIST) {
                    return "AnyArray";
                }
                return fromMetaType(innerType) + "Array";
            case BOOLEAN:
                return "Boolean";
            case MAP:
                return "Map";
            case RELATIONSHIP:
                return "Relationship";
            case NODE:
                return "Node";
            case PATH:
                return "Path";
            case POINT:
                return "Point";
            case DATE:
                return "Date";
            case LOCAL_TIME:
            case DATE_TIME:
            case LOCAL_DATE_TIME:
                return "DateTime";
            case TIME:
                return "Time";
            case DURATION:
                return "Duration";
            default:
                return "String";
        }
    }

    static Field toField(String fieldName, Set<String> propertyTypes) {
        if (propertyTypes.size() > 1) {
            // return string type
            return new Field(fieldName, FieldType.nullable(new ArrowType.Utf8()), null);
        } else {
            // convert to RelatedType
            final String type = propertyTypes.iterator().next();
            switch (type) {
                case "Boolean":
                    return new Field(fieldName, FieldType.nullable(Types.MinorType.BIT.getType()), null);
                case "Long":
                    return new Field(fieldName, FieldType.nullable(Types.MinorType.BIGINT.getType()), null);
                case "Double":
                    return new Field(fieldName, FieldType.nullable(Types.MinorType.FLOAT8.getType()), null);
                case "DateTime":
                case "LocalDateTime":
                case "Date":
                    return new Field(fieldName, FieldType.nullable(Types.MinorType.DATEMILLI.getType()), null);
                case "Duration":
                case "Node":
                case "Relationship":
//                    return new Field(fieldName, FieldType.nullable(Types.MinorType.STRUCT.getType()), null);
                case "Point":
                case "Map":
//                    return new Field(fieldName, FieldType.nullable(Types.MinorType.MAP.getType()), null);
                case "DateTimeArray":
                case "DateArray":
                case "BooleanArray":
                case "LongArray":
                case "DoubleArray":
                case "StringArray":
                case "PointArray":
                default:
                    return (type.endsWith("Array")) ? new Field(fieldName, FieldType.nullable(Types.MinorType.LIST.getType()),
                            List.of(toField("$data$", Set.of(type.replace("Array", "")))))
                            : new Field(fieldName, FieldType.nullable(Types.MinorType.VARCHAR.getType()), null);
            }
        }
    }

    default void write(int index, Object value, FieldVector fieldVector) {
        if (fieldVector instanceof BaseVariableWidthVector) {
            writeBaseVariableWidthVector(index, value, (BaseVariableWidthVector) fieldVector);
        } else if (fieldVector instanceof BigIntVector) {
            writeBigIntVector(index, value, (BigIntVector) fieldVector);
        } else if (fieldVector instanceof DateMilliVector) {
            writeDateMilliVector(index, value, (DateMilliVector) fieldVector);
        } else if (fieldVector instanceof Float8Vector) {
            writeFloat8Vector(index, value, (Float8Vector) fieldVector);
        } else if (fieldVector instanceof BitVector) {
            writeBitVector(index, value, (BitVector) fieldVector);
        } else if (fieldVector instanceof ListVector) {
            writeListVector(index, value, fieldVector);
        }
    }

    private void writeListVector(int index, Object value, FieldVector fieldVector) {
        ListVector listVector = (ListVector) fieldVector;
        if (value == null) {
            listVector.setNull(index);
            return;
        }
        UnionListWriter listWriter = listVector.getWriter();
        Object[] array;
        if (value instanceof Collection) {
            final Collection collection = (Collection) value;
            array = collection.toArray(new Object[collection.size()]);
        } else {
            array = (Object[]) value;
        }
        listWriter.setPosition(index);
        listWriter.startList();
        FieldVector inner = listVector.getChildrenFromFields().get(0);
        for (int i = 0; i < array.length; i++) {
            Object val = convertValue(array[i]);
            if (val == null) {
                listWriter.writeNull();
            } else if (inner instanceof ListVector) {
                write(i, val, inner);
            } else if (inner instanceof BaseVariableWidthVector) {
                final byte[] bytes;
                if (val instanceof String) {
                    bytes = val.toString().getBytes(StandardCharsets.UTF_8);
                } else {
                    bytes = JsonUtil.writeValueAsBytes(val);
                }
                ArrowBuf tempBuf = fieldVector.getAllocator().buffer(bytes.length);
                tempBuf.setBytes(0, bytes);
                listWriter.varChar().writeVarChar(0, bytes.length, tempBuf);
                tempBuf.close();
            } else if (inner instanceof BigIntVector) {
                long lng = (long) val;
                listWriter.bigInt().writeBigInt(lng);
            } else if (inner instanceof Float8Vector) {
                double dbl = (double) val;
                listWriter.float8().writeFloat8(dbl);
            } else if (inner instanceof BitVector) {
                boolean bool = (boolean) val;
                listWriter.bit().writeBit(bool ? 1 : 0);
            } // TODO datemilli
        }
        listWriter.endList();
    }

    private void writeBitVector(int index, Object value, BitVector fieldVector) {
        BitVector baseVector = fieldVector;
        if (value == null) {
            baseVector.setNull(index);
        } else {
            baseVector.setSafe(index, (boolean) value ? 1 : 0);
        }
    }

    private void writeFloat8Vector(int index, Object value, Float8Vector fieldVector) {
        Float8Vector baseVector = fieldVector;
        if (value == null) {
            baseVector.setNull(index);
        } else {
            baseVector.setSafe(index, (double) value);
        }
    }

    private void writeDateMilliVector(int index, Object value, DateMilliVector fieldVector) {
        DateMilliVector baseVector = fieldVector;
        final Long dateInMillis;
        if (value == null) {
            dateInMillis = null;
        } else if (value instanceof Date) {
            dateInMillis = ((Date) value).getTime();
        } else if (value instanceof LocalDateTime) {
            dateInMillis = ((LocalDateTime) value)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli();
        } else if (value instanceof ZonedDateTime) {
            dateInMillis = ((ZonedDateTime) value)
                    .toInstant()
                    .toEpochMilli();
        } else if (value instanceof OffsetDateTime) {
            dateInMillis = ((OffsetDateTime) value)
                    .toInstant()
                    .toEpochMilli();
        } else {
            dateInMillis = null;
        }
        if (dateInMillis == null) {
            baseVector.setNull(index);
        } else {
            baseVector.setSafe(index, dateInMillis);
        }
    }

    private void writeBigIntVector(int index, Object value, BigIntVector fieldVector) {
        BigIntVector baseVector = fieldVector;
        if (value == null) {
            baseVector.setNull(index);
        } else {
            baseVector.setSafe(index, (long) value);
        }
    }

    private void writeBaseVariableWidthVector(int index, Object value, BaseVariableWidthVector fieldVector) {
        final BaseVariableWidthVector baseVector = fieldVector;
        if (value == null) {
            baseVector.setNull(index);
            return;
        }
        if (value instanceof DurationValue) {
            value = ((DurationValue) value).toString();
        }
        if (value instanceof String) {
            baseVector.setSafe(index, value.toString().getBytes(StandardCharsets.UTF_8));
        } else {
            baseVector.setSafe(index, JsonUtil.writeValueAsBytes(value));
        }
    }
}