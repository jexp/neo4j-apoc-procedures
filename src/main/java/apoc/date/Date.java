package apoc.date;

import apoc.util.Util;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.procedure.*;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.time.temporal.TemporalQuery;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

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
	@Description("toYears(timestap) or toYears(date[,format]) converts timestamp into floating point years")
	public double toYears(@Name("value") Object value, @Name(value = "format", defaultValue = DEFAULT_FORMAT) String format) {
		if (value instanceof Number) {
			long time = ((Number) value).longValue();
			return (time / (365d*24*3600*1000));
		} else {
			long time = parse(value.toString(),"ms",format,null);
			return 1970d + (time / (365d*24*3600*1000));
		}
	}

	@Procedure(mode = Mode.WRITE)
	@Description("CALL apoc.date.expire(node,time,'time-unit') - expire node in given time by setting :TTL label and `ttl` property")
	public void expire(@Name("node") Node node, @Name("time") long time, @Name("timeUnit") String timeUnit) {
		node.addLabel(Label.label("TTL"));
		node.setProperty("ttl",unit(timeUnit).toMillis(time));
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

	public static class FieldResult {
		public final Map<String,Object> value = new LinkedHashMap<>();
		public long years, months, days, weekdays, hours, minutes, seconds;
		public String zoneid;

		public Map<String, Object> asMap() {
			return value;
		}
	}

	private TimeUnit unit(String unit) {
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
		return TimeUnit.MILLISECONDS;
	}

	@UserFunction
	@Description("apoc.date.format(12345,('ms|s|m|h|d'),('yyyy-MM-dd HH:mm:ss zzz'),('TZ')) get string representation of time value optionally using the specified unit (default ms) using specified format (default ISO) and specified time zone (default current TZ)")
	public String format(final @Name("time") long time, @Name(value = "unit", defaultValue = "ms") String unit, @Name(value = "format",defaultValue = DEFAULT_FORMAT) String format, @Name(value = "timezone",defaultValue = "") String timezone) {
		return parse(unit(unit).toMillis(time), format, timezone);
	}

	@UserFunction
	@Description("apoc.date.parse('2012-12-23','ms|s|m|h|d','yyyy-MM-dd') parse date string using the specified format into the specified time unit")
	public Long parse(@Name("time") String time, @Name(value = "unit", defaultValue = "ms") String unit, @Name(value = "format",defaultValue = DEFAULT_FORMAT) String format, final @Name(value = "timezone", defaultValue = "") String timezone) {
		Long value = parseOrThrow(time, getFormat(format, timezone));
		return value == null ? null : unit(unit).convert(value, TimeUnit.MILLISECONDS);
	}

	@UserFunction
	@Description("apoc.date.systemTimezone() returns the system timezone display name")
	public String systemTimezone() {
		return TimeZone.getDefault().getDisplayName();
	}

	public String parse(final @Name("millis") long millis, final @Name(value = "pattern", defaultValue = DEFAULT_FORMAT) String pattern, final @Name("timezone") String timezone) {
		if (millis < 0) {
			throw new IllegalArgumentException("The time argument should be >= 0, got: " + millis);
		}
		return getFormat(pattern, timezone).format(new java.util.Date(millis));
	}

	private static DateFormat getFormat(final String pattern, final String timezone) {
		String actualPattern = getPattern(pattern);
		SimpleDateFormat format = new SimpleDateFormat(actualPattern);
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

	private static Long parseOrThrow(final String date, final DateFormat format) {
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
