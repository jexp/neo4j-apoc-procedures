package apoc.date;

import apoc.util.DateFormatUtil;
import apoc.util.Util;
import org.apache.commons.lang3.StringUtils;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.procedure.*;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.time.temporal.TemporalQuery;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.time.temporal.ChronoField.*;


/**
 * @author tkroman
 * @since 9.04.2016
 */
public class Date {
	public static final String DEFAULT_FORMAT = "yyyy-MM-dd HH:mm:ss";
	private static final int MILLIS_IN_SECOND = 1000;
	private static final String UTC_ZONE_ID = "UTC";
	private static final List<TemporalQuery<Consumer<FieldResult>>> DT_FIELDS_SELECTORS = Arrays.asList(
			temporalQuery(YEAR),
			temporalQuery(MONTH_OF_YEAR),
			temporalQuery(DAY_OF_WEEK),
			temporalQuery(DAY_OF_MONTH),
			temporalQuery(HOUR_OF_DAY),
			temporalQuery(MINUTE_OF_HOUR),
			temporalQuery(SECOND_OF_MINUTE),
			(temporal) -> (FieldResult result) -> {
				Optional<ZoneId> zone = Optional.ofNullable(TemporalQueries.zone().queryFrom(temporal));
				zone.ifPresent(zoneId -> {
					String displayName = zoneId.getDisplayName(TextStyle.SHORT, Locale.ROOT);
					result.value.put("zoneid", displayName);
					result.zoneid = displayName;
				});
			}
	);

	@UserFunction
	@Description("toYears(timestamp) or toYears(date[,format]) - converts timestamp into floating point years")
	public double toYears(@Name("value") Object value, @Name(value = "format", defaultValue = DEFAULT_FORMAT) String format) {
		if (value instanceof Number) {
			long time = ((Number) value).longValue();
			return (time / (365d*24*3600*1000));
		} else {
			long time = parse(value.toString(),"ms",format,null);
			return 1970d + (time / (365d*24*3600*1000));
		}
	}

	@UserFunction
	@Description("apoc.date.fields('2012-12-23',('yyyy-MM-dd')) - return columns and a map representation of date parsed with the given format with entries for years,months,weekdays,days,hours,minutes,seconds,zoneid")
	public Map<String,Object> fields(final @Name("date") String date, final @Name(value = "pattern", defaultValue = DEFAULT_FORMAT) String pattern) {
		if (date == null) {
			return Util.map();
		}
		DateTimeFormatter fmt = getSafeDateTimeFormatter(pattern);
		TemporalAccessor temporal = fmt.parse(date);
		FieldResult result = new FieldResult();

		for (final TemporalQuery<Consumer<FieldResult>> query : DT_FIELDS_SELECTORS) {
			query.queryFrom(temporal).accept(result);
		}

		return result.asMap();
	}

	@UserFunction
	@Description("apoc.date.field(12345,('ms|s|m|h|d|month|year'),('TZ')")
	public Long field(final @Name("time") Long time,  @Name(value = "unit", defaultValue = "d") String unit, @Name(value = "timezone",defaultValue = "UTC") String timezone) {
		return (time == null)
				? null
				: (long) ZonedDateTime
				.ofInstant( Instant.ofEpochMilli( time ), ZoneId.of( timezone ) )
				.get( chronoField( unit ) );
	}

	@UserFunction
	@Description( "apoc.date.currentTimestamp() - returns System.currentTimeMillis() at the time it was called. The value is current throughout transaction execution, and is different from Cypher’s timestamp() function, which does not update within a transaction." )
	public long currentTimestamp()
	{
		return System.currentTimeMillis();
	}

	public static class FieldResult {
		public final Map<String,Object> value = new LinkedHashMap<>();
		public long years, months, days, weekdays, hours, minutes, seconds;
		public String zoneid;

		public Map<String, Object> asMap() {
			return value;
		}
	}

	public static TimeUnit unit(String unit) {
		if (unit == null) return TimeUnit.MILLISECONDS;

		switch (unit.toLowerCase()) {
			case "ms": case "milli":  case "millis": case "milliseconds": return TimeUnit.MILLISECONDS;
			case "s":  case "second": case "seconds": return TimeUnit.SECONDS;
			case "m":  case "minute": case "minutes": return TimeUnit.MINUTES;
			case "h":  case "hour":   case "hours":   return TimeUnit.HOURS;
			case "d":  case "day":    case "days":    return TimeUnit.DAYS;
//			case "month":case "months": return TimeUnit.MONTHS;
//			case "years":case "year": return TimeUnit.YEARS;
		}

		throw new IllegalArgumentException("The unit: "+ unit + " is not correct");

		//return TimeUnit.MILLISECONDS;
	}

	private ChronoField chronoField(String unit) {
		switch (unit.toLowerCase()) {
			case "ms": case "milli":  case "millis": case "milliseconds": return ChronoField.MILLI_OF_SECOND;
			case "s":  case "second": case "seconds": return ChronoField.SECOND_OF_MINUTE;
			case "m":  case "minute": case "minutes": return ChronoField.MINUTE_OF_HOUR;
			case "h":  case "hour":   case "hours":   return ChronoField.HOUR_OF_DAY;
			case "d":  case "day":    case "days":    return ChronoField.DAY_OF_MONTH;
			case "w":  case "weekday": case "weekdays": return ChronoField.DAY_OF_WEEK;
			case "month":case "months": return ChronoField.MONTH_OF_YEAR;
			case "year":case "years": return ChronoField.YEAR;
//			default: return ChronoField.YEAR;
		}

		throw new IllegalArgumentException("The unit: "+ unit + " is not correct");
	}

	@UserFunction
	@Description("apoc.date.format(12345,('ms|s|m|h|d'),('yyyy-MM-dd HH:mm:ss zzz'),('TZ')) - get string representation of time value optionally using the specified unit (default ms) using specified format (default ISO) and specified time zone (default current TZ)")
	public String format(final @Name("time") Long time, @Name(value = "unit", defaultValue = "ms") String unit, @Name(value = "format",defaultValue = DEFAULT_FORMAT) String format, @Name(value = "timezone",defaultValue = "") String timezone) {
		return time == null ? null : parse(unit(unit).toMillis(time), format, timezone);
	}

	@UserFunction
	@Description("apoc.date.toISO8601(12345,('ms|s|m|h|d') - return string representation of time in ISO8601 format")
	public String toISO8601(final @Name("time") Long time, @Name(value = "unit", defaultValue = "ms") String unit) {
		return time == null ? null : parse(unit(unit).toMillis(time), "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", null);
	}

	@UserFunction
	@Description("apoc.date.fromISO8601('yyyy-MM-ddTHH:mm:ss.SSSZ') - return number representation of time in EPOCH format")
	public Long fromISO8601(final @Name("time") String time) {
		return time == null ? null : Instant.parse(time).toEpochMilli();
	}

	@UserFunction
	@Description("apoc.date.parse('2012-12-23','ms|s|m|h|d','yyyy-MM-dd') - parse date string using the specified format into the specified time unit")
	public Long parse(@Name("time") String time, @Name(value = "unit", defaultValue = "ms") String unit, @Name(value = "format",defaultValue = DEFAULT_FORMAT) String format, final @Name(value = "timezone", defaultValue = "") String timezone) {
		Long value = StringUtils.isBlank(time) ? null : parseOrThrow(time, getFormat(format, timezone));
		return value == null ? null : unit(unit).convert(value, TimeUnit.MILLISECONDS);
	}

	@UserFunction(deprecatedBy = "apoc.temporal.toZonedTemporal")
	@Description("apoc.date.parseAsZonedDateTime('2012-12-23 23:59:59','yyyy-MM-dd HH:mm:ss', 'UTC-hour-offset') - parse date string using the specified format to specified timezone")
	@Deprecated
	public ZonedDateTime parseAsZonedDateTime(@Name("time") String time, @Name(value = "format", defaultValue = DEFAULT_FORMAT) String format, final @Name(value = "timezone", defaultValue = "UTC") String timezone) {
		Long value = parseOrThrow(time, getFormat(format, timezone));
		return value == null ? null : Instant.ofEpochMilli(value).atZone(ZoneId.of(timezone));
	}

	@UserFunction
	@Description("apoc.date.systemTimezone() - returns the system timezone display name")
	public String systemTimezone() {
		return TimeZone.getDefault().getID();
	}

	@UserFunction
	@Description("apoc.date.convert(12345, 'ms', 'd') - convert a timestamp in one time unit into one of a different time unit")
	public Long convert(@Name("time") long time, @Name(value = "unit") String unit, @Name(value = "toUnit") String toUnit) {
		return unit(toUnit).convert(time, unit(unit));
	}

	@UserFunction
	@Description("apoc.date.convertFormat('Tue, 14 May 2019 14:52:06 -0400', 'rfc_1123_date_time', 'iso_date_time') - convert a String of one date format into a String of another date format.")
	public String convertFormat( @Name( "temporal" ) String input, @Name( value = "currentFormat" ) String currentFormat, @Name( value = "convertTo" , defaultValue = "yyyy-MM-dd" ) String convertTo )
	{
		if (input == null || input.isEmpty())
		{
			return null;
		}

		DateTimeFormatter currentFormatter = DateFormatUtil.getOrCreate( currentFormat );
		DateTimeFormatter convertToFormatter = DateFormatUtil.getOrCreate( convertTo );

		return convertToFormatter.format(  currentFormatter.parse( input ) );
	}

	@UserFunction
	@Description("apoc.date.add(12345, 'ms', -365, 'd') - given a timestamp in one time unit, adds a value of the specified time unit")
	public Long add(@Name("time") long time, @Name(value = "unit") String unit, @Name(value = "addValue") long addValue, @Name(value = "addUnit") String addUnit) {
		long valueToAdd = unit(unit).convert(addValue, unit(addUnit));
		return time + valueToAdd;
	}

	public String parse(final @Name("millis") long millis, final @Name(value = "pattern", defaultValue = DEFAULT_FORMAT) String pattern, final @Name("timezone") String timezone) {
		return getFormat(pattern, timezone).format(new java.util.Date(millis));
	}

	public static DateFormat getFormat(final String pattern, final String timezone) {
		String actualPattern = getPattern(pattern);
		SimpleDateFormat format = null;
		try {
			format = new SimpleDateFormat(actualPattern);
		} catch(Exception e){
			throw new IllegalArgumentException("The pattern: "+pattern+" is not correct");
		}
		if (timezone != null && !"".equals(timezone)) {
			format.setTimeZone(TimeZone.getTimeZone(timezone));
		} else if (!(containsTimeZonePattern(actualPattern))) {
			format.setTimeZone(TimeZone.getTimeZone(UTC_ZONE_ID));
		}
		return format;
	}

	//work around https://bugs.openjdk.java.net/browse/JDK-8139107
	private static DateTimeFormatter getSafeDateTimeFormatter(final String pattern) {
		DateTimeFormatter safeFormatter = getDateTimeFormatter(pattern);

		if (Locale.UK.equals(safeFormatter.getLocale())) {
			return safeFormatter.withLocale(Locale.ENGLISH);
		}

		return safeFormatter;
	}

	private static DateTimeFormatter getDateTimeFormatter(final String pattern) {
		String actualPattern = getPattern(pattern);
		DateTimeFormatter fmt = DateTimeFormatter.ofPattern(actualPattern);
		if (containsTimeZonePattern(actualPattern)) {
			return fmt;
		} else {
			return fmt.withZone(ZoneId.of(UTC_ZONE_ID));
		}
	}

	public  static Long parseOrThrow(final String date, final DateFormat format) {
		if (date == null) return null;
		try {
			return format.parse(date).getTime();
		} catch (ParseException e) {
			throw new IllegalArgumentException(e);
		}
	}

	private static boolean containsTimeZonePattern(final String pattern) {
		return pattern.matches("[XxZzVO]{1,3}");	// doesn't account for strings escaped with "'" (TODO?)
	}

	private static String getPattern(final String pattern) {
		return pattern == null || "".equals(pattern) ? DEFAULT_FORMAT : pattern;
	}

	private static TemporalQuery<Consumer<FieldResult>> temporalQuery(final ChronoField field) {
		return temporal -> result -> {
			if (field.isSupportedBy(temporal)) {
				String key = field.getBaseUnit().toString().toLowerCase();
				long value = field.getFrom(temporal);
				switch (field) {
					case YEAR:             result.years = value;break;
					case MONTH_OF_YEAR:    result.months = value;break;
					case DAY_OF_WEEK:      result.weekdays = value; key = "weekdays"; break;
					case DAY_OF_MONTH:     result.days = value;break;
					case HOUR_OF_DAY:      result.hours = value;break;
					case MINUTE_OF_HOUR:   result.minutes = value;break;
					case SECOND_OF_MINUTE: result.seconds = value;break;
				}
				result.value.put(key, value);
			}
		};
	}
}
