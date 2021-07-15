package apoc.export.arrow;

import apoc.ApocSettings;
import apoc.graph.Graphs;
import apoc.load.LoadArrow;
import apoc.meta.Meta;
import apoc.util.TestUtil;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.Assert.assertEquals;

public class ArrowTest {

    private static File directory = new File("target/import");
    static { //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
    }

    @ClassRule
    public static DbmsRule db = new ImpermanentDbmsRule()
            .withSetting(ApocSettings.apoc_import_file_enabled, true)
            .withSetting(GraphDatabaseSettings.load_csv_file_url_root, directory.toPath().toAbsolutePath())
            .withSetting(ApocSettings.apoc_export_file_enabled, true);

    public static final List<Map<String, Object>> EXPECTED = List.of(
            new HashMap<>() {{
                put("name", "Adam");
                put("bffSince", null);
                put("<source.id>", null);
                put("<id>", 0L);
                put("age", 42L);
                put("labels", List.of("User"));
                put("male", true);
                put("<type>", null);
                put("kids", List.of("Sam", "Anna", "Grace"));
                put("place", "{\"crs\":\"wgs-84-3d\",\"latitude\":33.46789,\"longitude\":13.1,\"height\":100.0}");
                put("<target.id>", null);
                put("since", null);
                put("born", LocalDateTime.parse("2015-05-18T19:32:24.000").atOffset(ZoneOffset.UTC).toZonedDateTime());
            }},
            new HashMap<>() {{
                put("name", "Jim");
                put("bffSince", null);
                put("<source.id>", null);
                put("<id>", 1L);
                put("age", 42L);
                put("labels", List.of("User"));
                put("male", null);
                put("<type>", null);
                put("kids", null);
                put("place", null);
                put("<target.id>", null);
                put("since", null);
                put("born", null);
            }},
            new HashMap<>() {{
                put("name", null);
                put("bffSince", "P5M1DT12H");
                put("<source.id>", 0L);
                put("<id>", 0L);
                put("age", null);
                put("labels", null);
                put("male", null);
                put("<type>", "KNOWS");
                put("kids", null);
                put("place", null);
                put("<target.id>", 1L);
                put("since", 1993L);
                put("born", null);
            }}
    );

    @BeforeClass
    public static void beforeClass() {
        db.executeTransactionally("CREATE (f:User {name:'Adam',age:42,male:true,kids:['Sam','Anna','Grace'], born:localdatetime('2015-05-18T19:32:24.000'), place:point({latitude: 13.1, longitude: 33.46789, height: 100.0})})-[:KNOWS {since: 1993, bffSince: duration('P5M1.5D')}]->(b:User {name:'Jim',age:42})");
        TestUtil.registerProcedure(db, ExportArrow.class, LoadArrow.class, Graphs.class, Meta.class);
    }

    @Test
    public void testStreamRoundtripArrowQuery() {
        // given - when
        final String returnQuery = "RETURN 1 AS intData," +
                "'a' AS stringData," +
                "true AS boolData," +
                "[1, 2, 3] AS intArray," +
                "[1.1, 2.2, 3.3] AS doubleArray," +
                "[true, false, true] AS boolArray," +
                "[1, '2', true, null] AS mixedArray," +
                "{foo: 'bar'} AS mapData," +
                "localdatetime('2015-05-18T19:32:24') as dateData," +
                "[[0]] AS arrayArray," +
                "1.1 AS doubleData";
        final String query = "CALL apoc.export.arrow.stream.query(\"" + returnQuery + "\") YIELD value AS byteArray " +
                "CALL apoc.load.arrow.stream(byteArray) YIELD value " +
                "RETURN value";

        // then
        db.executeTransactionally(query, Map.of(), result -> {
            final Map<String, Object> row = (Map<String, Object>) result.next().get("value");
            assertEquals(1L, row.get("intData"));
            assertEquals("a", row.get("stringData"));
            assertEquals(Arrays.asList(1L, 2L, 3L), row.get("intArray"));
            assertEquals(Arrays.asList(1.1D, 2.2D, 3.3), row.get("doubleArray"));
            assertEquals(Arrays.asList(true, false, true), row.get("boolArray"));
            assertEquals(Arrays.asList("1", "2", "true", null), row.get("mixedArray"));
            assertEquals("{\"foo\":\"bar\"}", row.get("mapData"));
            assertEquals(LocalDateTime.parse("2015-05-18T19:32:24.000")
                    .atOffset(ZoneOffset.UTC)
                    .toZonedDateTime(), row.get("dateData"));
            assertEquals(Arrays.asList("[0]"), row.get("arrayArray"));
            assertEquals(1.1D, row.get("doubleData"));
            return true;
        });
    }

    @Test
    public void testFileRoundtripArrowQuery() {
        // given - when
        final String returnQuery = "RETURN 1 AS intData," +
                "'a' AS stringData," +
                "true AS boolData," +
                "[1, 2, 3] AS intArray," +
                "[1.1, 2.2, 3.3] AS doubleArray," +
                "[true, false, true] AS boolArray," +
                "[1, '2', true, null] AS mixedArray," +
                "{foo: 'bar'} AS mapData," +
                "localdatetime('2015-05-18T19:32:24') as dateData," +
                "[[0]] AS arrayArray," +
                "1.1 AS doubleData";
        final String query = "CALL apoc.export.arrow.query('query_test.arrow', \"" + returnQuery + "\") YIELD file " +
                "CALL apoc.load.arrow(file) YIELD value " +
                "RETURN value";

        // then
        db.executeTransactionally(query, Map.of(), result -> {
            final Map<String, Object> row = (Map<String, Object>) result.next().get("value");
            assertEquals(1L, row.get("intData"));
            assertEquals("a", row.get("stringData"));
            assertEquals(Arrays.asList(1L, 2L, 3L), row.get("intArray"));
            assertEquals(Arrays.asList(1.1D, 2.2D, 3.3), row.get("doubleArray"));
            assertEquals(Arrays.asList(true, false, true), row.get("boolArray"));
            assertEquals(Arrays.asList("1", "2", "true", null), row.get("mixedArray"));
            assertEquals("{\"foo\":\"bar\"}", row.get("mapData"));
            assertEquals(LocalDateTime.parse("2015-05-18T19:32:24.000")
                    .atOffset(ZoneOffset.UTC)
                    .toZonedDateTime(), row.get("dateData"));
            assertEquals(Arrays.asList("[0]"), row.get("arrayArray"));
            assertEquals(1.1D, row.get("doubleData"));
            return true;
        });
    }

    @Test
    public void testStreamRoundtripArrowGraph() {
        // given - when
        final String query = "CALL apoc.graph.fromDB('neo4j',{}) yield graph " +
                "CALL apoc.export.arrow.stream.graph(graph) YIELD value AS byteArray " +
                "CALL apoc.load.arrow.stream(byteArray) YIELD value " +
                "RETURN value";

        // then
        db.executeTransactionally(query, Map.of(), result -> {
            final List<Map<String, Object>> actual = result.stream()
                    .map(m -> (Map<String, Object>) m.get("value"))
                    .collect(Collectors.toList());
            assertEquals(EXPECTED, actual);
            return null;
        });
    }

    @Test
    public void testFileRoundtripArrowGraph() {
        // given - when
        final String query = "CALL apoc.graph.fromDB('neo4j',{}) yield graph " +
                "CALL apoc.export.arrow.graph('graph_test.arrow', graph) YIELD file " +
                "CALL apoc.load.arrow(file) YIELD value " +
                "RETURN value";

        // then
        db.executeTransactionally(query, Map.of(), result -> {
            final List<Map<String, Object>> actual = result.stream()
                    .map(m -> (Map<String, Object>) m.get("value"))
                    .collect(Collectors.toList());
            assertEquals(EXPECTED, actual);
            return null;
        });
    }

    @Test
    public void testStreamRoundtripArrowAll() {
        // given - when
        final String query = "CALL apoc.export.arrow.stream.all() YIELD value AS byteArray " +
                "CALL apoc.load.arrow.stream(byteArray) YIELD value " +
                "RETURN value";

        // then
        db.executeTransactionally(query, Map.of(), result -> {
            final List<Map<String, Object>> actual = result.stream()
                    .map(m -> (Map<String, Object>) m.get("value"))
                    .collect(Collectors.toList());
            assertEquals(EXPECTED, actual);
            return null;
        });
    }

    @Test
    public void testFileRoundtripArrowAll() {
        // given - when
        final String query = "CALL apoc.export.arrow.all('all_test.arrow') YIELD file " +
                "CALL apoc.load.arrow(file) YIELD value " +
                "RETURN value";

        // then
        db.executeTransactionally(query, Map.of(), result -> {
            final List<Map<String, Object>> actual = result.stream()
                    .map(m -> (Map<String, Object>) m.get("value"))
                    .collect(Collectors.toList());
            assertEquals(EXPECTED, actual);
            return null;
        });
    }

    @Test
    public void testStreamVolumeArrowAll() {
        // given - when
        db.executeTransactionally("UNWIND range(0, 10000 - 1) AS id CREATE (n:ArrowNode{id:id})");

        final String query = "CALL apoc.export.arrow.stream.query('MATCH (n:ArrowNode) RETURN n.id AS id') YIELD value AS byteArray " +
                "CALL apoc.load.arrow.stream(byteArray) YIELD value " +
                "RETURN value.id AS id";

        final List<Long> expected = LongStream.range(0, 10000)
                .mapToObj(l -> l)
                .collect(Collectors.toList());

        // then
        db.executeTransactionally(query, Map.of(), result -> {
            final List<Long> actual = result.stream()
                    .map(m -> (Long) m.get("id"))
                    .collect(Collectors.toList());
            assertEquals(expected, actual);
            return null;
        });

        db.executeTransactionally("MATCH (n:ArrowNode) DELETE n");
    }

    @Test
    public void testFileVolumeArrowAll() {
        // given - when
        db.executeTransactionally("UNWIND range(0, 10000 - 1) AS id CREATE (:ArrowNode{id:id})");

        final String query = "CALL apoc.export.arrow.query('volume_test.arrow', 'MATCH (n:ArrowNode) RETURN n.id AS id') YIELD file " +
                "CALL apoc.load.arrow(file) YIELD value " +
                "RETURN value.id AS id";

        final List<Long> expected = LongStream.range(0, 10000)
                .mapToObj(l -> l)
                .collect(Collectors.toList());

        // then
        db.executeTransactionally(query, Map.of(), result -> {
            final List<Long> actual = result.stream()
                    .map(m -> (Long) m.get("id"))
                    .collect(Collectors.toList());
            assertEquals(expected, actual);
            return null;
        });

        db.executeTransactionally("MATCH (n:ArrowNode) DELETE n");
    }


}