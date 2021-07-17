package apoc.date;

import apoc.util.TestUtil;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Stream;

import static apoc.util.MapUtil.map;
import static apoc.util.TestUtil.testCall;
import static java.util.Objects.isNull;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.junit.Assert.*;

public class DateTest {

	@Rule public ExpectedException expected = ExpectedException.none();

	@ClassRule
	public static DbmsRule db = new ImpermanentDbmsRule();

	private DateFormat defaultFormat = formatInUtcZone("yyyy-MM-dd HH:mm:ss");
	private String epochAsString = defaultFormat.format(new java.util.Date(0L));
	private java.util.Date testDate = new java.util.Date(1464739200000L);
	private String testDateAsString = defaultFormat.format( testDate );
	private static final long SECONDS_PER_MINUTE = 60;
	private static final long SECONDS_PER_HOUR = SECONDS_PER_MINUTE * 60;
	private static final long SECONDS_PER_DAY = SECONDS_PER_HOUR * 24;

	@BeforeClass
	public static void sUp() throws Exception {
		TestUtil.registerProcedure(db, Date.class);
	}

	@Test public void testToDays() throws Exception {
		testCall(db,
				"RETURN apoc.date.parse($date,'d') AS value",
				map("date",testDateAsString),
				row -> assertEquals(testDate.toInstant(), Instant.ofEpochSecond (SECONDS_PER_DAY * (long) row.get("value"))));
	}

	@Test public void testToHours() throws Exception {
		testCall(db,
				"RETURN apoc.date.parse($date,'h') AS value",
				map("date",testDateAsString),
				row -> assertEquals(testDate.toInstant(), Instant.ofEpochSecond (SECONDS_PER_HOUR * (long) row.get("value"))));
	}

	@Test public void testToMinutes() throws Exception {
		testCall(db,
				"RETURN apoc.date.parse($date,'m') AS value",
				map("date",testDateAsString),
				row -> assertEquals(testDate.toInstant(), Instant.ofEpochSecond (SECONDS_PER_MINUTE * (long) row.get("value"))));
	}

	@Test public void testToUnixtime() throws Exception {
		testCall(db,
				"RETURN apoc.date.parse($date,'s') AS value",
				map("date",epochAsString),
				row -> assertEquals(Instant.EPOCH, Instant.ofEpochSecond((long) row.get("value"))));
	}

	@Test public void testToMillis() throws Exception {
		testCall(db,
				"RETURN apoc.date.parse($date,'ms') AS value",
				map("date",epochAsString),
				row -> assertEquals(Instant.EPOCH, Instant.ofEpochMilli((long) row.get("value"))));
	}

	@Test public void testToUnixtimeWithCorrectFormat() throws Exception {
		String pattern = "MM/dd/yyyy HH:mm:ss";
		SimpleDateFormat customFormat = formatInUtcZone(pattern);
		String reference = customFormat.format(new java.util.Date(0L));
		testCall(db,
				"RETURN apoc.date.parse($date,'s',$pattern) AS value",
				map("date",reference,"pattern",pattern),
				row -> assertEquals(Instant.EPOCH, Instant.ofEpochSecond((long) row.get("value"))));
	}

	@Test public void testToUnixtimeWithIncorrectPatternFormat() throws Exception {
		expected.expect(instanceOf(QueryExecutionException.class));
		testCall(db,
				"RETURN apoc.date.parse('12/12/1945 12:12:12','s','MM/dd/yyyy HH:mm:ss/neo4j') AS value",
				row -> assertEquals(Instant.EPOCH, Instant.ofEpochSecond((long) row.get("value"))));
	}

	@Test public void testToUnixtimeWithNullInput() throws Exception {
		testCall(db,
				"RETURN apoc.date.parse(NULL,'s') AS value",
				row -> assertNull(row.get("value")));
	}

	@Test 
	public void testToUnixtimeWithEmptyInput() throws Exception {
		testCall(db, "RETURN apoc.date.parse(' ','s') AS value", row -> assertNull(row.get("value")));
	}

	@Test public void testFromUnixtime() throws Exception {
		testCall(db,
				"RETURN apoc.date.format(0,'s') AS value",
				row -> {
					try {
						assertEquals(new java.util.Date(0L), defaultFormat.parse((String) row.get("value")));
					} catch (ParseException e) {
						throw new RuntimeException(e);
					}
				});
	}

	@Test public void testFromUnixtimeWithNullInputReturnsNull() throws Exception {
		testCall(db,
				"RETURN apoc.date.format(null,'s') AS value",
				row -> assertEquals(null, row.get("value")));
	}

	@Test public void testParseAsZonedDateTimeWithCorrectFormat() throws Exception {
		testCall(db,
				"RETURN apoc.date.parseAsZonedDateTime('03/23/1965 00:00:00','MM/dd/yyyy HH:mm:ss','America/New_York') AS value",
				row -> assertEquals(ZonedDateTime.of(LocalDateTime.of(1965, 3, 23, 0, 0), ZoneId.of("America/New_York")),
						row.get("value")));
	}

	@Test public void testParseAsZonedDateTimeWithDefaultTimezone() throws Exception {
		testCall(db,
				"RETURN apoc.date.parseAsZonedDateTime('03/23/1965 00:00:00','MM/dd/yyyy HH:mm:ss') AS value",
				row -> assertEquals(ZonedDateTime.of(LocalDateTime.of(1965, 3, 23, 0, 0), ZoneId.of("UTC")),
						row.get("value")));
	}

	@Test public void testParseAsZonedDateTimeWithDefaultFormatAndTimezone() throws Exception {
		testCall(db,
				"RETURN apoc.date.parseAsZonedDateTime('1965-03-23 00:00:00') AS value",
				row -> assertEquals(ZonedDateTime.of(LocalDateTime.of(1965, 3, 23, 0, 0), ZoneId.of("UTC")),
						row.get("value")));
	}

	@Test public void testParseAsZonedDateTimeWithIncorrectPatternFormat() throws Exception {
		expected.expect(instanceOf(QueryExecutionException.class));
		testCall(db,
				"RETURN apoc.date.parseAsZonedDateTime('03/23/1965 00:00:00','MM/dd/yyyy HH:mm:ss/neo4j','America/New_York') AS value",
				row -> assertEquals(ZonedDateTime.of(LocalDateTime.of(1965, 3, 23, 0, 0), ZoneId.of("America/New_York")),
						row.get("value")));
	}

	@Test public void testToZonedDateTimeWithNullInput() throws Exception {
		testCall(db,
				"RETURN apoc.date.parseAsZonedDateTime(NULL) AS value",
				row -> assertNull(row.get("value")));
	}

	@Test public void testToISO8601() throws Exception {
		testCall(db,
				"RETURN apoc.date.toISO8601(0) AS value",
				row -> assertEquals("1970-01-01T00:00:00.000Z", row.get("value")));
	}

	@Test public void testFromISO8601() throws Exception {
		testCall(db,
				"RETURN apoc.date.fromISO8601('1970-01-01T00:00:00.000Z') AS value",
				row -> assertEquals(0L, row.get("value")));
	}

	@Test public void testFromUnixtimeWithCorrectFormat() throws Exception {
		String pattern = "MM/dd/yyyy HH:mm:ss";
		SimpleDateFormat customFormat = formatInUtcZone(pattern);
		testCall(db,
				"RETURN apoc.date.format(0,'s',$pattern) AS value",
				map("pattern",pattern),
				row -> {
					try {
						assertEquals(new java.util.Date(0L), customFormat.parse((String) row.get("value")));
					} catch (ParseException e) {
						throw new RuntimeException(e);
					}
				});
	}

	@Test public void testFromUnixtimeWithCorrectFormatAndTimeZone() throws Exception {
		String pattern = "MM/dd/yyyy HH:mm:ss";
		String timezone = "America/New_York";
		SimpleDateFormat customFormat = formatInCustomTimeZone(pattern, timezone);
		testCall(db,
				"RETURN apoc.date.format(0,'s',$pattern,$timezone) AS value",
				map("pattern",pattern,"timezone",timezone),
				row -> {
					try {
						assertEquals(new java.util.Date(0L), customFormat.parse((String) row.get("value")));
					} catch (ParseException e) {
						throw new RuntimeException(e);
					}
				});
	}

	@Test public void testFromUnixtimeWithIncorrectPatternFormat() throws Exception {
		expected.expect(instanceOf(QueryExecutionException.class));
		testCall(db,
				"RETURN apoc.date.format(0,'s','MM/dd/yyyy HH:mm:ss/neo4j') AS value",
				row -> {});
	}

	@Test public void testFromUnixtimeWithIncorrectPatternFormatAndTimeZone() throws Exception {
		expected.expect(instanceOf(QueryExecutionException.class));
		testCall(db,
				"RETURN apoc.date.formatTimeZone(0,'s','MM/dd/yyyy HH:mm:ss/neo4j','Neo4j/Apoc') AS value",
				row -> {});
	}

	@Test public void testFromUnixtimeWithNegativeInputDoesNotThrowException() throws Exception {
		testCall(db, "RETURN apoc.date.format(-1,'s') AS value", row -> {});
	}

	@Test public void testWrongUnitDoesThrowException() throws Exception {
		expected.expect(instanceOf(RuntimeException.class));
		testCall(db, "RETURN apoc.date.format(-1,'wrong') AS value", row -> {});
	}

	@Test public void testWrongPatternDoesThrowException() throws Exception {
		expected.expect(instanceOf(RuntimeException.class));
		testCall(db, "RETURN apoc.date.format(-1,'s','aaaa-bb-cc') AS value", row -> {});
	}

	@Test public void testOrderByDate() throws Exception {
		SimpleDateFormat format = formatInUtcZone("yyyy-MM-dd HH:mm:ss");
		try (Transaction tx = db.beginTx()) {
			for (int i = 0 ; i < 8; i++) {
				Node datedNode = tx.createNode(() -> "Person");
				datedNode.setProperty("born", format.format(java.util.Date.from(Instant.EPOCH.plus(i, ChronoUnit.DAYS))));
			}
			for (int i = 15 ; i >= 8; i--) {
				Node datedNode = tx.createNode(() -> "Person");
				datedNode.setProperty("born", format.format(java.util.Date.from(Instant.EPOCH.plus(i, ChronoUnit.DAYS))));
			}
			tx.commit();
		}

		List<java.util.Date> expected = Stream.iterate(Instant.EPOCH, prev -> prev.plus(1, ChronoUnit.DAYS))
				.limit(16)
				.map(java.util.Date::from)
				.sorted()
				.collect(toList());

		List<java.util.Date> actual;
		try (Transaction tx = db.beginTx()) {
			String query = "MATCH (p:Person) RETURN p, apoc.date.parse(p.born,'s') as dob ORDER BY dob ";
			actual = Iterators.asList(tx.execute(query).<Long>columnAs("dob")).stream()
					.map(dob -> java.util.Date.from(Instant.ofEpochSecond((long) dob)))
					.collect(toList());
			tx.commit();
		}

		assertEquals(expected, actual);
	}

	@Test
	public void testfields() throws Exception {
		testCall(db,
				"RETURN apoc.date.fields('2015-01-02 03:04:05') AS value",
				row -> {
					Map<String, Object> map = (Map<String, Object>) row.get("value");
					assertEquals(2015L, map.get("years"));
					assertEquals(1L, map.get("months"));
					assertEquals(2L, map.get("days"));
					assertEquals(5L, map.get("weekdays"));
					assertEquals(3L, map.get("hours"));
					assertEquals(4L, map.get("minutes"));
					assertEquals(5L, map.get("seconds"));
				});
	}

	@Test
	public void testfieldsCustomFormat() throws Exception {
		testCall(db,
				"RETURN apoc.date.fields('2015-01-02 03:04:05 EET', 'yyyy-MM-dd HH:mm:ss zzz') AS m",
				row -> {
					Map<String, Object> split = (Map<String, Object>) row.get("m");
					assertEquals(2015L, split.get("years"));
					assertEquals(1L, split.get("months"));
					assertEquals(2L, split.get("days"));
					assertEquals(3L, split.get("hours"));
					assertEquals(4L, split.get("minutes"));
					assertEquals(5L, split.get("seconds"));
					assertEquals(
							TimeZone.getTimeZone("EET").getRawOffset(),
							TimeZone.getTimeZone((String)split.get("zoneid")).getRawOffset()
					);
				});

		testCall(db,
				"RETURN apoc.date.fields('2015/01/02/03/04/05/EET', 'yyyy/MM/dd/HH/mm/ss/z') AS value",
				row -> {
					Map<String, Object> split = (Map<String, Object>) row.get("value");
					assertEquals(2015L, split.get("years"));
					assertEquals(1L, split.get("months"));
					assertEquals(2L, split.get("days"));
					assertEquals(3L, split.get("hours"));
					assertEquals(4L, split.get("minutes"));
					assertEquals(5L, split.get("seconds"));
					assertEquals(
							TimeZone.getTimeZone("EET").getRawOffset(),
							TimeZone.getTimeZone((String)split.get("zoneid")).getRawOffset()
					);
				});

		testCall(db,
				"RETURN apoc.date.fields('2015/01/02_EET', 'yyyy/MM/dd_z') AS value",
				row -> {
					Map<String, Object> split = (Map<String, Object>) row.get("value");
					assertEquals(2015L, split.get("years"));
					assertEquals(1L, split.get("months"));
					assertEquals(2L, split.get("days"));
					assertEquals(
							TimeZone.getTimeZone("EET").getRawOffset(),
							TimeZone.getTimeZone((String)split.get("zoneid")).getRawOffset()
					);
				});
	}

	@Test
	public void testfieldsNullInput() throws Exception {
		testCall(db,
				"RETURN apoc.date.fields(NULL, 'yyyy-MM-dd HH:mm:ss zzz') AS value",
				row -> {
					Map<String, Object> split = (Map<String, Object>) row.get("value");
					assertTrue(split.isEmpty());
				});
	}

	@Test
	public void testfield() throws Exception {
		long epoch = LocalDateTime.of( 1982, 1, 23, 22, 30, 42 )
				.atZone( ZoneId.of( "UTC" ) )
				.toInstant()
				.toEpochMilli();
		testCall(db,
				"RETURN apoc.date.field(" + epoch + ") AS value",
				row -> assertEquals(23L, (long) row.get("value")));
	}

	@Test
	public void testfieldCustomField() throws Exception {
		long epoch = LocalDateTime.of( 1982, 1, 23, 22, 30 )
				.atZone( ZoneId.of( "UTC" ) )
				.toInstant()
				.toEpochMilli();
		testCall(db,
				"RETURN apoc.date.field(" + epoch + ", 'year','UTC') AS value",
				row -> assertEquals(1982L, (long) row.get("value")));
	}

	@Test
	public void testfieldAll() throws Exception {
		long epoch = LocalDateTime.of(2015, 1, 2, 3, 4, 5)
				.atZone(ZoneId.of("UTC"))
				.toInstant()
				.toEpochMilli();
		testCall(db,
				"RETURN apoc.date.field(" + epoch + ", 'year') AS years, apoc.date.field(" + epoch
						+ ", 'month') AS months, apoc.date.field(" + epoch + ", 'd') AS days, apoc.date.field(" + epoch
						+ ", 'w') AS weekdays, apoc.date.field(" + epoch + ", 'h') AS hours, apoc.date.field(" + epoch
						+ ", 'm') AS minutes, apoc.date.field(" + epoch + ", 's') AS seconds, apoc.date.field(" + epoch
						+ ", 'ms') AS millis",
				row -> {
					assertEquals(2015L, (long) row.get("years"));
					assertEquals(1L, (long) row.get("months"));
					assertEquals(2L, (long) row.get("days"));
					assertEquals(5L, (long) row.get("weekdays"));
					assertEquals(3L, (long) row.get("hours"));
					assertEquals(4L, (long) row.get("minutes"));
					assertEquals(5L, (long) row.get("seconds"));
					assertEquals(0L, (long) row.get("millis"));
				});
	}

	@Test
	public void testfieldNullInput() throws Exception {
		testCall(db,
				"RETURN apoc.date.field(NULL) AS value",
				row -> assertTrue(isNull(row.get("value"))));
	}

	@Test
	public void testDateParserDifference() throws Exception {
		String dateDelta = "RETURN apoc.date.parse('2012-10-04','ms','yyyy-MM-dd') - apoc.date.parse('2012-10-04 00:00:00') as delta";
		testCall(db, dateDelta, row -> assertTrue(3600*1000*24 > (long)row.get("delta")));
	}

	@Test
	public void toYears() throws Exception {
		testCall(db, "RETURN apoc.date.toYears('2012-10-04','YYYY-MM-dd') as years", row -> assertEquals(2012d, (double)row.get("years"),0.5d));
		testCall(db, "RETURN apoc.date.toYears(apoc.date.parse('2012','ms','YYYY') - apoc.date.parse('2008','ms','YYYY')) as years", row -> assertEquals(4d, (double)row.get("years"),0.5d));
	}

	@Test
	public void testGetTimezone() throws Exception {
		testCall(db, "RETURN apoc.date.systemTimezone() as tz", row -> assertEquals(TimeZone.getDefault().getID(), row.get("tz").toString()));
	}

	@Test
	public void testConvert() throws Exception {
		Long firstOf2017ms = 1483228800000l;
		Long firstOf2017d = 17167l;
		Map<String, Object> params = new HashMap<>();
		params.put("firstOf2017ms", firstOf2017ms);
		testCall(db, "RETURN apoc.date.convert($firstOf2017ms, 'ms', 'd') as firstOf2017days", params, row -> assertEquals(firstOf2017d, row.get("firstOf2017days")));
	}

	@Test
	public void testAdd() throws Exception {
		Long firstOf2017ms = 1483228800000l;
		Long firstOf2017Plus5Daysms = 1483660800000l;
		Map<String, Object> params = new HashMap<>();
		params.put("firstOf2017ms", firstOf2017ms);
		testCall(db, "RETURN apoc.date.add($firstOf2017ms, 'ms', 5, 'd') as firstOf2017Plus5days", params, row -> assertEquals(firstOf2017Plus5Daysms, row.get("firstOf2017Plus5days")));
	}

	@Test
	public void testAddNegative() throws Exception {
		Long firstOf2017ms = 1483228800000l;
		Long firstOf2017Minus5Daysms = 1482796800000l;
		Map<String, Object> params = new HashMap<>();
		params.put("firstOf2017ms", firstOf2017ms);
		testCall(db, "RETURN apoc.date.add($firstOf2017ms, 'ms', -5, 'd') as firstOf2017Minus5days", params, row -> assertEquals(firstOf2017Minus5Daysms, row.get("firstOf2017Minus5days")));
	}

	@Test
	public void testConvertFormats() throws Exception {
		String rfcDateTime = "Tue, 14 May 2019 14:52:06 -0400";
		String isoDateTime = "2019-05-14T14:52:06-04:00";
		Map<String, Object> params = new HashMap<>();
		params.put("rfcDateTime", rfcDateTime);
		testCall(db, "RETURN apoc.date.convertFormat($rfcDateTime, 'rfc_1123_date_time', 'iso_date_time') as convertedTime", params, row -> assertEquals(isoDateTime, row.get("convertedTime")));
	}

	private SimpleDateFormat formatInUtcZone(final String pattern) {
		SimpleDateFormat customFormat = new SimpleDateFormat(pattern);
		customFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
		return customFormat;
	}

	private SimpleDateFormat formatInCustomTimeZone(final String pattern, final String timezone) {
		SimpleDateFormat customFormat = new SimpleDateFormat(pattern);
		customFormat.setTimeZone(TimeZone.getTimeZone(timezone));
		return customFormat;
	}
}
