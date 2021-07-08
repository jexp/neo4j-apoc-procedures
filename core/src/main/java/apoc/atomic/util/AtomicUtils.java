package apoc.atomic.util;

/**
 * @author AgileLARUS
 *
 * @since 3.0.0
 */
public class AtomicUtils {

	public static Number sum(Number oldValue, Number number){
		if(oldValue instanceof Long)
			return oldValue.longValue() + number.longValue();
		if(oldValue instanceof Integer)
			return oldValue.intValue() + number.intValue();
		if(oldValue instanceof Double)
			return oldValue.doubleValue() + number.doubleValue();
		if(oldValue instanceof Float)
			return oldValue.floatValue() + number.floatValue();
		if(oldValue instanceof Short)
			return oldValue.shortValue() + number.shortValue();
		if(oldValue instanceof Byte)
			return oldValue.byteValue() + number.byteValue();
		return null;
	}

	public static Number sub(Number oldValue, Number number){
		if(oldValue instanceof Long)
			return oldValue.longValue() - number.longValue();
		if(oldValue instanceof Integer)
			return oldValue.intValue() - number.intValue();
		if(oldValue instanceof Double)
			return oldValue.doubleValue() - number.doubleValue();
		if(oldValue instanceof Float)
			return oldValue.floatValue() - number.floatValue();
		if(oldValue instanceof Short)
			return oldValue.shortValue() - number.shortValue();
		if(oldValue instanceof Byte)
			return oldValue.byteValue() - number.byteValue();
		return null;
	}
}
