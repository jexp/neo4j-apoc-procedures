package apoc.export.arrow;

import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;

import java.util.List;

public class ArrowUtils {

    private ArrowUtils() {
    }

    public static Field FIELD_ID = new Field("<id>", FieldType.nullable(Types.MinorType.BIGINT.getType()), null);
    public static Field FIELD_LABELS = new Field("labels", FieldType.nullable(Types.MinorType.LIST.getType()),
            List.of(new Field("$data$", FieldType.nullable(Types.MinorType.VARCHAR.getType()), null)));
    public static Field FIELD_SOURCE_ID = new Field("<source.id>", FieldType.nullable(Types.MinorType.BIGINT.getType()), null);
    public static Field FIELD_TARGET_ID = new Field("<target.id>", FieldType.nullable(Types.MinorType.BIGINT.getType()), null);
    public static Field FIELD_TYPE = new Field("<type>", FieldType.nullable(Types.MinorType.VARCHAR.getType()), null);

}
