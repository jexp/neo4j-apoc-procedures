package apoc.export.csv;

import apoc.ApocSettings;
import apoc.graph.Graphs;
import apoc.meta.Meta;
import apoc.util.TestUtil;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.graphdb.Result;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.function.Consumer;

import static apoc.util.MapUtil.map;
import static apoc.util.TestUtil.testResult;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.*;

/**
 * @author mh
 * @since 22.05.16
 */
public class ExportCsvTest {

    private static final String EXPECTED_QUERY_NODES = String.format("\"u\"%n" +
            "\"{\"\"id\"\":0,\"\"labels\"\":[\"\"User\"\",\"\"User1\"\"],\"\"properties\"\":{\"\"name\"\":\"\"foo\"\",\"\"age\"\":42,\"\"male\"\":true,\"\"kids\"\":[\"\"a\"\",\"\"b\"\",\"\"c\"\"]}}\"%n" +
            "\"{\"\"id\"\":1,\"\"labels\"\":[\"\"User\"\"],\"\"properties\"\":{\"\"name\"\":\"\"bar\"\",\"\"age\"\":42}}\"%n" +
            "\"{\"\"id\"\":2,\"\"labels\"\":[\"\"User\"\"],\"\"properties\"\":{\"\"age\"\":12}}\"%n");
    private static final String EXPECTED_QUERY = String.format("\"u.age\",\"u.name\",\"u.male\",\"u.kids\",\"labels(u)\"%n" +
            "\"42\",\"foo\",\"true\",\"[\"\"a\"\",\"\"b\"\",\"\"c\"\"]\",\"[\"\"User1\"\",\"\"User\"\"]\"%n" +
            "\"42\",\"bar\",\"\",\"\",\"[\"\"User\"\"]\"%n" +
            "\"12\",\"\",\"\",\"\",\"[\"\"User\"\"]\"%n");
    private static final String EXPECTED_QUERY_WITHOUT_QUOTES = String.format("u.age,u.name,u.male,u.kids,labels(u)%n" +
            "42,foo,true,[\"a\",\"b\",\"c\"],[\"User1\",\"User\"]%n" +
            "42,bar,,,[\"User\"]%n" +
            "12,,,,[\"User\"]%n");
    private static final String EXPECTED_QUERY_QUOTES_NONE = String.format("a.name,a.city,a.street,labels(a)%n" +
            "Andrea,Milano,Via Garibaldi, 7,[\"Address1\",\"Address\"]%n" +
            "Bar Sport,,,[\"Address\"]%n" +
            ",,via Benni,[\"Address\"]%n");
    private static final String EXPECTED_QUERY_QUOTES_ALWAYS = String.format("\"a.name\",\"a.city\",\"a.street\",\"labels(a)\"%n" +
            "\"Andrea\",\"Milano\",\"Via Garibaldi, 7\",\"[\"\"Address1\"\",\"\"Address\"\"]\"%n" +
            "\"Bar Sport\",\"\",\"\",\"[\"\"Address\"\"]\"%n" +
            "\"\",\"\",\"via Benni\",\"[\"\"Address\"\"]\"%n");
    private static final String EXPECTED_QUERY_QUOTES_NEEDED = String.format("a.name,a.city,a.street,labels(a)%n" +
            "Andrea,Milano,\"Via Garibaldi, 7\",\"[\"Address1\",\"Address\"]\"%n" +
            "Bar Sport,,,\"[\"Address\"]\"%n" +
            ",,via Benni,\"[\"Address\"]\"%n");
    private static final String EXPECTED = String.format("\"_id\",\"_labels\",\"age\",\"city\",\"kids\",\"male\",\"name\",\"street\",\"_start\",\"_end\",\"_type\"%n" +
            "\"0\",\":User:User1\",\"42\",\"\",\"[\"\"a\"\",\"\"b\"\",\"\"c\"\"]\",\"true\",\"foo\",\"\",,,%n" +
            "\"1\",\":User\",\"42\",\"\",\"\",\"\",\"bar\",\"\",,,%n" +
            "\"2\",\":User\",\"12\",\"\",\"\",\"\",\"\",\"\",,,%n" +
            "\"3\",\":Address:Address1\",\"\",\"Milano\",\"\",\"\",\"Andrea\",\"Via Garibaldi, 7\",,,%n" +
            "\"4\",\":Address\",\"\",\"\",\"\",\"\",\"Bar Sport\",\"\",,,%n" +
            "\"5\",\":Address\",\"\",\"\",\"\",\"\",\"\",\"via Benni\",,,%n" +
            ",,,,,,,,\"0\",\"1\",\"KNOWS\"%n" +
            ",,,,,,,,\"3\",\"4\",\"NEXT_DELIVERY\"%n");

    private static final String EXP_SAMPLE = "\"_id\",\"_labels\",\"address\",\"age\",\"baz\",\"city\",\"foo\",\"kids\",\"last:Name\",\"male\",\"name\",\"street\",\"_start\",\"_end\",\"_type\",\"one\",\"three\"\n" +
            "\"0\",\":User:User1\",\"\",\"42\",\"\",\"\",\"\",\"[\"\"a\"\",\"\"b\"\",\"\"c\"\"]\",\"\",\"true\",\"foo\",\"\",,,,,\n" +
            "\"1\",\":User\",\"\",\"42\",\"\",\"\",\"\",\"\",\"\",\"\",\"bar\",\"\",,,,,\n" +
            "\"2\",\":User\",\"\",\"12\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",,,,,\n" +
            "\"3\",\":Address:Address1\",\"\",\"\",\"\",\"Milano\",\"\",\"\",\"\",\"\",\"Andrea\",\"Via Garibaldi, 7\",,,,,\n" +
            "\"4\",\":Address\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"Bar Sport\",\"\",,,,,\n" +
            "\"5\",\":Address\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"via Benni\",,,,,\n" +
            "\"6\",\":Sample:User\",\"\",\"\",\"\",\"\",\"\",\"\",\"Galilei\",\"\",\"\",\"\",,,,,\n" +
            "\"7\",\":Sample:User\",\"Universe\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",,,,,\n" +
            "\"8\",\":Sample:User\",\"\",\"\",\"\",\"\",\"bar\",\"\",\"\",\"\",\"\",\"\",,,,,\n" +
            "\"9\",\":Sample:User\",\"\",\"\",\"baa\",\"\",\"true\",\"\",\"\",\"\",\"\",\"\",,,,,\n" +
            ",,,,,,,,,,,,\"0\",\"1\",\"KNOWS\",\"\",\"\"\n" +
            ",,,,,,,,,,,,\"3\",\"4\",\"NEXT_DELIVERY\",\"\",\"\"\n" +
            ",,,,,,,,,,,,\"8\",\"9\",\"KNOWS\",\"two\",\"four\"\n";

    private static final String EXPECTED_NONE_QUOTES = String.format("_id,_labels,age,city,kids,male,name,street,_start,_end,_type%n" +
            "0,:User:User1,42,,[\"a\",\"b\",\"c\"],true,foo,,,,%n" +
            "1,:User,42,,,,bar,,,,%n" +
            "2,:User,12,,,,,,,,%n" +
            "3,:Address:Address1,,Milano,,,Andrea,Via Garibaldi, 7,,,%n" +
            "4,:Address,,,,,Bar Sport,,,,%n" +
            "5,:Address,,,,,,via Benni,,,%n" +
            ",,,,,,,,0,1,KNOWS%n" +
            ",,,,,,,,3,4,NEXT_DELIVERY%n");
    private static final String EXPECTED_NEEDED_QUOTES = String.format("_id,_labels,age,city,kids,male,name,street,_start,_end,_type%n" +
            "0,:User:User1,42,,\"[\"a\",\"b\",\"c\"]\",true,foo,,,,%n" +
            "1,:User,42,,,,bar,,,,%n" +
            "2,:User,12,,,,,,,,%n" +
            "3,:Address:Address1,,Milano,,,Andrea,\"Via Garibaldi, 7\",,,%n" +
            "4,:Address,,,,,Bar Sport,,,,%n" +
            "5,:Address,,,,,,via Benni,,,%n" +
            ",,,,,,,,0,1,KNOWS%n" +
            ",,,,,,,,3,4,NEXT_DELIVERY%n");

    private static File directory = new File("target/import");
    static { //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
    }

    @ClassRule
    public static DbmsRule db = new ImpermanentDbmsRule()
            .withSetting(GraphDatabaseSettings.load_csv_file_url_root, directory.toPath().toAbsolutePath())
            .withSetting(ApocSettings.apoc_export_file_enabled, true);

    @BeforeClass
    public static void setUp() throws Exception {
        TestUtil.registerProcedure(db, ExportCSV.class, Graphs.class, Meta.class);
        db.executeTransactionally("CREATE (f:User1:User {name:'foo',age:42,male:true,kids:['a','b','c']})-[:KNOWS]->(b:User {name:'bar',age:42}),(c:User {age:12})");
        db.executeTransactionally("CREATE (f:Address1:Address {name:'Andrea', city: 'Milano', street:'Via Garibaldi, 7'})-[:NEXT_DELIVERY]->(a:Address {name: 'Bar Sport'}), (b:Address {street: 'via Benni'})");
    }

    private String readFile(String fileName) {
        return TestUtil.readFileToString(new File(directory, fileName));
    }

    @Test
    public void testExportInvalidQuoteValue() throws Exception {
        try {
            String fileName = "all.csv";
            TestUtil.testCall(db, "CALL apoc.export.csv.all($file,{quotes: 'Invalid'})",
                    map("file", fileName),
                    (r) -> assertResults(fileName, r, "database"));
            fail();
        } catch (RuntimeException e) {
            final String expectedMessage = "Failed to invoke procedure `apoc.export.csv.all`: Caused by: java.lang.RuntimeException: The string value of the field quote is not valid";
            assertEquals(expectedMessage, e.getMessage());
        }
    }

    @Test
    public void testExportAllCsv() throws Exception {
        String fileName = "all.csv";
        TestUtil.testCall(db, "CALL apoc.export.csv.all($file,null)", map("file", fileName),
                (r) -> assertResults(fileName, r, "database"));
        assertEquals(EXPECTED, readFile(fileName));
    }

    @Test
    public void testExportAllCsvWithSample() throws IOException {
        db.executeTransactionally("CREATE (:User:Sample {`last:Name`:'Galilei'}), (:User:Sample {address:'Universe'}),\n" +
                "(:User:Sample {foo:'bar'})-[:KNOWS {one: 'two', three: 'four'}]->(:User:Sample {baz:'baa', foo: true})");
        String fileName = "all.csv";
        final long totalNodes = 10L;
        final long totalRels = 3L;
        final long totalProps = 19L;
        TestUtil.testCall(db, "CALL apoc.export.csv.all($file, null)", map("file", fileName),
                (r) -> assertResults(fileName, r, "database", totalNodes, totalRels, totalProps, true));
        assertEquals(EXP_SAMPLE, readFile(fileName));

        // quotes: 'none' to simplify header testing
        TestUtil.testCall(db, "CALL apoc.export.csv.all($file, {sampling: true, samplingConfig: {sample: 1}, quotes: 'none'})", map("file", fileName),
                (r) -> assertResults(fileName, r, "database", totalNodes, totalRels, totalProps, false));
        
        final String[] s = Files.lines(new File(directory, fileName).toPath()).findFirst().get().split(",");
        assertTrue(s.length < 17);
        assertTrue(Arrays.asList(s).containsAll(List.of("_id", "_labels", "_start", "_end", "_type")));
        
        db.executeTransactionally("MATCH (n:Sample) DETACH DELETE n");
    }

    @Test
    public void testExportAllCsvWithQuotes() throws Exception {
        String fileName = "all.csv";
        TestUtil.testCall(db, "CALL apoc.export.csv.all($file,{quotes: true})",
                map("file", fileName),
                (r) -> assertResults(fileName, r, "database"));
        assertEquals(EXPECTED, readFile(fileName));
    }

    @Test
    public void testExportAllCsvWithoutQuotes() throws Exception {
        String fileName = "all.csv";
        TestUtil.testCall(db, "CALL apoc.export.csv.all($file,{quotes: 'none'})",
                map("file", fileName),
                (r) -> assertResults(fileName, r, "database"));
        assertEquals(EXPECTED_NONE_QUOTES, readFile(fileName));
    }

    @Test
    public void testExportAllCsvNeededQuotes() throws Exception {
        String fileName = "all.csv";
        TestUtil.testCall(db, "CALL apoc.export.csv.all($file,{quotes: 'ifNeeded'})",
                map("file", fileName),
                (r) -> assertResults(fileName, r, "database"));
        assertEquals(EXPECTED_NEEDED_QUOTES, readFile(fileName));
    }

    @Test
    public void testExportGraphCsv() throws Exception {
        String fileName = "graph.csv";
        TestUtil.testCall(db, "CALL apoc.graph.fromDB('test',{}) yield graph " +
                        "CALL apoc.export.csv.graph(graph, $file,{quotes: 'none'}) " +
                        "YIELD nodes, relationships, properties, file, source,format, time " +
                        "RETURN *", map("file", fileName),
                (r) -> assertResults(fileName, r, "graph"));
        assertEquals(EXPECTED_NONE_QUOTES, readFile(fileName));
    }

    @Test
    public void testExportGraphCsvWithoutQuotes() throws Exception {
        String fileName = "graph.csv";
        TestUtil.testCall(db, "CALL apoc.graph.fromDB('test',{}) yield graph " +
                        "CALL apoc.export.csv.graph(graph, $file,null) " +
                        "YIELD nodes, relationships, properties, file, source,format, time " +
                        "RETURN *", map("file", fileName),
                (r) -> assertResults(fileName, r, "graph"));
        assertEquals(EXPECTED, readFile(fileName));
    }

    @Test
    public void testExportQueryCsv() throws Exception {
        String fileName = "query.csv";
        String query = "MATCH (u:User) return u.age, u.name, u.male, u.kids, labels(u)";
        TestUtil.testCall(db, "CALL apoc.export.csv.query($query,$file,null)",
                map("file", fileName, "query", query),
                (r) -> {
                    assertTrue("Should get statement",r.get("source").toString().contains("statement: cols(5)"));
                    assertEquals(fileName, r.get("file"));
                    assertEquals("csv", r.get("format"));

                });
        assertEquals(EXPECTED_QUERY, readFile(fileName));
    }

    @Test
    public void testExportQueryCsvWithoutQuotes() throws Exception {
        String fileName = "query.csv";
        String query = "MATCH (u:User) return u.age, u.name, u.male, u.kids, labels(u)";
        TestUtil.testCall(db, "CALL apoc.export.csv.query($query,$file,{quotes: false})",
                map("file", fileName, "query", query),
                (r) -> {
                    assertTrue("Should get statement",r.get("source").toString().contains("statement: cols(5)"));
                    assertEquals(fileName, r.get("file"));
                    assertEquals("csv", r.get("format"));

                });
        assertEquals(EXPECTED_QUERY_WITHOUT_QUOTES, readFile(fileName));
    }

    @Test
    public void testExportQueryNodesCsv() throws Exception {
        String fileName = "query_nodes.csv";
        String query = "MATCH (u:User) return u";
        TestUtil.testCall(db, "CALL apoc.export.csv.query($query,$file,null)",
                map("file", fileName, "query", query),
                (r) -> {
                    assertTrue("Should get statement",r.get("source").toString().contains("statement: cols(1)"));
                    assertEquals(fileName, r.get("file"));
                    assertEquals("csv", r.get("format"));

                });
        assertEquals(EXPECTED_QUERY_NODES, readFile(fileName));
    }

    @Test
    public void testExportQueryNodesCsvParams() throws Exception {
        String fileName = "query_nodes.csv";
        String query = "MATCH (u:User) WHERE u.age > $age return u";
        TestUtil.testCall(db, "CALL apoc.export.csv.query($query,$file,{params:{age:10}})", map("file", fileName,"query",query),
                (r) -> {
                    assertTrue("Should get statement",r.get("source").toString().contains("statement: cols(1)"));
                    assertEquals(fileName, r.get("file"));
                    assertEquals("csv", r.get("format"));

                });
        assertEquals(EXPECTED_QUERY_NODES, readFile(fileName));
    }

    private void assertResults(String fileName, Map<String, Object> r, final String source) {
        assertResults(fileName, r, source, 6L, 2L, 12L, true);
    }

    private void assertResults(String fileName, Map<String, Object> r, final String source, 
                               Long expectedNodes, Long expectedRelationships, Long expectedProperties, boolean assertPropEquality) {
        assertEquals(expectedNodes, r.get("nodes"));
        assertEquals(expectedRelationships, r.get("relationships"));
        if (assertPropEquality) {
            assertEquals(expectedProperties, r.get("properties"));
        } else {
            assertTrue((Long) r.get("properties") < expectedProperties);
        }
        final String expectedSource = source + ": nodes(" + expectedNodes + "), rels(" + expectedRelationships + ")";
        assertEquals(expectedSource, r.get("source"));
        assertCsvCommon(fileName, r);
    }

    private void assertCsvCommon(String fileName, Map<String, Object> r) {
        assertEquals(fileName, r.get("file"));
        assertEquals("csv", r.get("format"));
        assertTrue("Should get time greater than 0",((long) r.get("time")) >= 0);
    }

    @Test public void testExportAllCsvStreaming() throws Exception {
        String statement = "CALL apoc.export.csv.all(null,{stream:true,batchSize:2,useOptimizations:{unwindBatchSize:2}})";
        StringBuilder sb=new StringBuilder();
        testResult(db, statement, (res) -> {
            Map<String, Object> r = res.next();
            assertEquals(2L, r.get("batchSize"));
            assertEquals(1L, r.get("batches"));
            assertEquals(2L, r.get("nodes"));
            assertEquals(2L, r.get("rows"));
            assertEquals(0L, r.get("relationships"));
            assertEquals(6L, r.get("properties"));
            assertNull("Should get file",r.get("file"));
            assertEquals("csv", r.get("format"));
            assertTrue("Should get time greater than 0", ((long) r.get("time")) >= 0);
            sb.append(r.get("data"));
            r = res.next();
            assertEquals(2L, r.get("batchSize"));
            assertEquals(2L, r.get("batches"));
            assertEquals(4L, r.get("nodes"));
            assertEquals(4L, r.get("rows"));
            assertEquals(0L, r.get("relationships"));
            assertEquals(10L, r.get("properties"));
            assertTrue("Should get time greater than 0",((long) r.get("time")) >= 0);
            sb.append(r.get("data"));
            r = res.next();
            assertEquals(2L, r.get("batchSize"));
            assertEquals(3L, r.get("batches"));
            assertEquals(6L, r.get("nodes"));
            assertEquals(6L, r.get("rows"));
            assertEquals(0L, r.get("relationships"));
            assertEquals(12L, r.get("properties"));
            assertTrue("Should get time greater than 0",((long) r.get("time")) >= 0);
            sb.append(r.get("data"));
            r = res.next();
            assertEquals(2L, r.get("batchSize"));
            assertEquals(4L, r.get("batches"));
            assertEquals(6L, r.get("nodes"));
            assertEquals(8L, r.get("rows"));
            assertEquals(2L, r.get("relationships"));
            assertEquals(12L, r.get("properties"));
            assertTrue("Should get time greater than 0",((long) r.get("time")) >= 0);
            sb.append(r.get("data"));
        });
        assertEquals(EXPECTED, sb.toString());
    }

    @Test public void testCypherCsvStreaming() throws Exception {
        String query = "MATCH (u:User) return u.age, u.name, u.male, u.kids, labels(u)";
        StringBuilder sb = new StringBuilder();
        testResult(db, "CALL apoc.export.csv.query($query,null,{stream:true,batchSize:2, useOptimizations:{unwindBatchSize:2}})", map("query",query),
                getAndCheckStreamingMetadataQueryMatchUsers(sb));
        assertEquals(EXPECTED_QUERY, sb.toString());
    }

    @Test public void testCypherCsvStreamingWithoutQuotes() throws Exception {
        String query = "MATCH (u:User) return u.age, u.name, u.male, u.kids, labels(u)";
        StringBuilder sb = new StringBuilder();
        testResult(db, "CALL apoc.export.csv.query($query,null,{quotes: false, stream:true,batchSize:2, useOptimizations:{unwindBatchSize:2}})", map("query",query),
                getAndCheckStreamingMetadataQueryMatchUsers(sb));

        assertEquals(EXPECTED_QUERY_WITHOUT_QUOTES, sb.toString());
    }

    private Consumer<Result> getAndCheckStreamingMetadataQueryMatchUsers(StringBuilder sb)
    {
        return (res) -> {
            Map<String, Object> r = res.next();
            assertEquals(2L, r.get("batchSize"));
            assertEquals(1L, r.get("batches"));
            assertEquals(0L, r.get("nodes"));
            assertEquals(2L, r.get("rows"));
            assertEquals(0L, r.get("relationships"));
            assertEquals(10L, r.get("properties"));
            assertNull("Should get file", r.get("file"));
            assertEquals("csv", r.get("format"));
            assertTrue("Should get time greater than 0",
                    ((long) r.get("time")) >= 0);
            sb.append(r.get("data")); r = res.next();
            assertEquals(2L, r.get("batchSize"));
            assertEquals(2L, r.get("batches"));
            assertEquals(0L, r.get("nodes"));
            assertEquals(3L, r.get("rows"));
            assertEquals(0L, r.get("relationships"));
            assertEquals(15L, r.get("properties"));
            assertTrue("Should get time greater than 0",
                    ((long) r.get("time")) >= 0);
            sb.append(r.get("data"));
        };
    }

    @Test public void testCypherCsvStreamingWithAlwaysQuotes() throws Exception {
        String query = "MATCH (a:Address) return a.name, a.city, a.street, labels(a)";
        StringBuilder sb = new StringBuilder();
        testResult(db, "CALL apoc.export.csv.query($query,null,{quotes: 'always', stream:true,batchSize:2, useOptimizations:{unwindBatchSize:2}})", map("query",query),
                getAndCheckStreamingMetadataQueryMatchAddress(sb));

        assertEquals(EXPECTED_QUERY_QUOTES_ALWAYS, sb.toString());
    }

    @Test public void testCypherCsvStreamingWithNeededQuotes() throws Exception {
        String query = "MATCH (a:Address) return a.name, a.city, a.street, labels(a)";
        StringBuilder sb = new StringBuilder();
        testResult(db, "CALL apoc.export.csv.query($query,null,{quotes: 'ifNeeded', stream:true,batchSize:2, useOptimizations:{unwindBatchSize:2}})", map("query",query),
                getAndCheckStreamingMetadataQueryMatchAddress(sb));

        assertEquals(EXPECTED_QUERY_QUOTES_NEEDED, sb.toString());
    }

    @Test public void testCypherCsvStreamingWithNoneQuotes() throws Exception {
        String query = "MATCH (a:Address) return a.name, a.city, a.street, labels(a)";
        StringBuilder sb = new StringBuilder();
        testResult(db, "CALL apoc.export.csv.query($query,null,{quotes: 'none', stream:true,batchSize:2, useOptimizations:{unwindBatchSize:2}})", map("query",query),
                getAndCheckStreamingMetadataQueryMatchAddress(sb));

        assertEquals(EXPECTED_QUERY_QUOTES_NONE, sb.toString());
    }

    @Test
    public void testExportQueryCsvIssue1188() throws Exception {
        String copyright = "\n" +
                "(c) 2018 Hovsepian, Albanese, et al. \"\"ASCB(r),\"\" \"\"The American Society for Cell Biology(r),\"\" and \"\"Molecular Biology of the Cell(r)\"\" are registered trademarks of The American Society for Cell Biology.\n" +
                "2018\n" +
                "\n" +
                "This article is distributed by The American Society for Cell Biology under license from the author(s). Two months after publication it is available to the public under an Attribution-Noncommercial-Share Alike 3.0 Unported Creative Commons License.\n" +
                "\n";
        String pk = "5921569";
        db.executeTransactionally("CREATE (n:Document{pk:$pk, copyright: $copyright})", map("copyright", copyright, "pk", pk));
        String query = "MATCH (n:Document{pk:'5921569'}) return n.pk as pk, n.copyright as copyright";
        TestUtil.testCall(db, "CALL apoc.export.csv.query($query, null, $config)", map("query", query,
                "config", map("stream", true)),
                (r) -> {
                    List<String[]> csv = CsvTestUtil.toCollection(r.get("data").toString());
                    assertEquals(2, csv.size());
                    assertArrayEquals(new String[]{"pk","copyright"}, csv.get(0));
                    assertArrayEquals(new String[]{"5921569",copyright}, csv.get(1));
                });
        db.executeTransactionally("MATCH (d:Document) DETACH DELETE d");

    }

    private Consumer<Result> getAndCheckStreamingMetadataQueryMatchAddress(StringBuilder sb)
    {
        return (res) -> {
            Map<String, Object> r = res.next();
            assertEquals(2L, r.get("batchSize"));
            assertEquals(1L, r.get("batches"));
            assertEquals(0L, r.get("nodes"));
            assertEquals(2L, r.get("rows"));
            assertEquals(0L, r.get("relationships"));
            assertEquals(8L, r.get("properties"));
            assertNull("Should get file", r.get("file"));
            assertEquals("csv", r.get("format"));
            assertTrue("Should get time greater than 0",
                    ((long) r.get("time")) >= 0);
            sb.append(r.get("data"));
            r = res.next();
            assertEquals(2L, r.get("batchSize"));
            assertEquals(2L, r.get("batches"));
            assertEquals(0L, r.get("nodes"));
            assertEquals(3L, r.get("rows"));
            assertEquals(0L, r.get("relationships"));
            assertEquals(12L, r.get("properties"));
            assertTrue("Should get time greater than 0",
                    ((long) r.get("time")) >= 0);
            sb.append(r.get("data"));
        };
    }

}
