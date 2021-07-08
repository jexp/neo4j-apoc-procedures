package apoc.export.graphml;

import apoc.ApocSettings;
import apoc.graph.Graphs;
import apoc.util.TestUtil;
import apoc.util.Util;
import junit.framework.TestCase;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.Relationship;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.diff.DefaultNodeMatcher;
import org.xmlunit.diff.Diff;
import org.xmlunit.diff.ElementSelector;
import org.xmlunit.util.Nodes;

import javax.xml.namespace.QName;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static apoc.ApocConfig.APOC_EXPORT_FILE_ENABLED;
import static apoc.ApocConfig.APOC_IMPORT_FILE_ENABLED;
import static apoc.ApocConfig.apocConfig;
import static apoc.util.MapUtil.map;
import static apoc.util.TestUtil.isRunningInCI;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.neo4j.configuration.GraphDatabaseSettings.TransactionStateMemoryAllocation.OFF_HEAP;
import static org.neo4j.configuration.SettingValueParsers.BYTES;
import static org.xmlunit.diff.ElementSelectors.byName;

/**
 * @author mh
 * @since 22.05.16
 */
public class ExportGraphMLTest {

    public static final List<String> ATTRIBUTES_CONTAINING_NODE_IDS = Arrays.asList("id", "source", "target");

    public static final String KEY_TYPES_EMPTY = "<key id=\"name\" for=\"node\" attr.name=\"name\" attr.type=\"string\"/>%n" +
            "<key id=\"limit\" for=\"node\" attr.name=\"limit\" attr.type=\"long\"/>%n" +
            "<key id=\"labels\" for=\"node\" attr.name=\"labels\" attr.type=\"string\"/>%n";
    public static final String GRAPH = "<graph id=\"G\" edgedefault=\"directed\">%n";
    public static final String HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>%n" +
            "<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://graphml.graphdrawing.org/xmlns http://graphml.graphdrawing.org/xmlns/1.0/graphml.xsd\">%n";
    public static final String KEY_TYPES_FALSE = "<key id=\"born\" for=\"node\" attr.name=\"born\"/>%n" +
            "<key id=\"values\" for=\"node\" attr.name=\"values\"/>%n" +
            "<key id=\"name\" for=\"node\" attr.name=\"name\"/>%n" +
            "<key id=\"labels\" for=\"node\" attr.name=\"labels\"/>%n"+
            "<key id=\"place\" for=\"node\" attr.name=\"place\"/>%n" +
            "<key id=\"age\" for=\"node\" attr.name=\"age\"/>%n" +
            "<key id=\"label\" for=\"edge\" attr.name=\"label\"/>%n";
    public static final String KEY_TYPES_DATA = "<key id=\"name\" for=\"node\" attr.name=\"name\"/>\n" +
            "<key id=\"labels\" for=\"node\" attr.name=\"labels\"/>";
    public static final String KEY_TYPES = "<key id=\"born\" for=\"node\" attr.name=\"born\" attr.type=\"string\"/>%n" +
            "<key id=\"values\" for=\"node\" attr.name=\"values\" attr.type=\"string\" attr.list=\"long\"/>%n" +
            "<key id=\"name\" for=\"node\" attr.name=\"name\" attr.type=\"string\"/>%n" +
            "<key id=\"labels\" for=\"node\" attr.name=\"labels\" attr.type=\"string\"/>%n"+
            "<key id=\"place\" for=\"node\" attr.name=\"place\" attr.type=\"string\"/>%n" +
            "<key id=\"age\" for=\"node\" attr.name=\"age\" attr.type=\"long\"/>%n" +
            "<key id=\"label\" for=\"edge\" attr.name=\"label\" attr.type=\"string\"/>%n";
    public static final String KEY_TYPES_PATH = "<key id=\"born\" for=\"node\" attr.name=\"born\" attr.type=\"string\"/>%n" +
            "<key id=\"name\" for=\"node\" attr.name=\"name\" attr.type=\"string\"/>%n" +
            "<key id=\"labels\" for=\"node\" attr.name=\"labels\" attr.type=\"string\"/>%n"+
            "<key id=\"place\" for=\"node\" attr.name=\"place\" attr.type=\"string\"/>%n" +
            "<key id=\"TYPE\" for=\"node\" attr.name=\"TYPE\" attr.type=\"string\"/>%n" +
            "<key id=\"age\" for=\"node\" attr.name=\"age\" attr.type=\"long\"/>%n" +
            "<key id=\"label\" for=\"edge\" attr.name=\"label\" attr.type=\"string\"/>%n" +
            "<key id=\"TYPE\" for=\"edge\" attr.name=\"TYPE\" attr.type=\"string\"/>%n";
    public static final String KEY_TYPES_CAMEL_CASE = "<key id=\"firstName\" for=\"node\" attr.name=\"firstName\" attr.type=\"string\"/>%n" +
            "<key id=\"ageNow\" for=\"node\" attr.name=\"ageNow\" attr.type=\"long\"/>%n" +
            "<key id=\"name\" for=\"node\" attr.name=\"name\" attr.type=\"string\"/>%n" +
            "<key id=\"labels\" for=\"node\" attr.name=\"labels\" attr.type=\"string\"/>%n" +
            "<key id=\"TYPE\" for=\"node\" attr.name=\"TYPE\" attr.type=\"string\"/>%n" +
            "<key id=\"label\" for=\"edge\" attr.name=\"label\" attr.type=\"string\"/>%n" +
            "<key id=\"TYPE\" for=\"edge\" attr.name=\"TYPE\" attr.type=\"string\"/>%n";
    public static final String KEY_TYPES_NO_DATA_KEY = "<key id=\"Node.Path\" for=\"node\" attr.name=\"Path\" attr.type=\"string\"/>\n" +
            "<key id=\"Edge.Path\" for=\"edge\" attr.name=\"Path\" attr.type=\"string\"/>";
    public static final String DATA = "<node id=\"n0\" labels=\":Foo:Foo0:Foo2\"><data key=\"labels\">:Foo:Foo0:Foo2</data><data key=\"place\">{\"crs\":\"wgs-84-3d\",\"latitude\":12.78,\"longitude\":56.7,\"height\":100.0}</data><data key=\"name\">foo</data><data key=\"born\">2018-10-10</data></node>%n" +
            "<node id=\"n1\" labels=\":Bar\"><data key=\"labels\">:Bar</data><data key=\"age\">42</data><data key=\"name\">bar</data><data key=\"place\">{\"crs\":\"wgs-84\",\"latitude\":12.78,\"longitude\":56.7,\"height\":null}</data></node>%n" +
            "<node id=\"n2\" labels=\":Bar\"><data key=\"labels\">:Bar</data><data key=\"age\">12</data><data key=\"values\">[1,2,3]</data></node>%n" +
            "<edge id=\"e0\" source=\"n0\" target=\"n1\" label=\"KNOWS\"><data key=\"label\">KNOWS</data></edge>%n";
    public static final String DATA_CAMEL_CASE =
            "<node id=\"n0\" labels=\":Foo:Foo0:Foo2\"><data key=\"TYPE\">:Foo:Foo0:Foo2</data><data key=\"label\">foo</data><data key=\"firstName\">foo</data></node>%n" +
            "<node id=\"n1\" labels=\":Bar\"><data key=\"TYPE\">:Bar</data><data key=\"label\">bar</data><data key=\"name\">bar</data><data key=\"ageNow\">42</data></node>%n" +
            "<edge id=\"e0\" source=\"n0\" target=\"n1\" label=\"KNOWS\"><data key=\"label\">KNOWS</data><data key=\"TYPE\">KNOWS</data></edge>%n";

    public static final String DATA_NODE_EDGE = "<node id=\"n0\"> <data key=\"labels\">:FOO</data><data key=\"name\">foo</data> </node>%n" +
            "<node id=\"n1\"> <data key=\"labels\">:BAR</data><data key=\"name\">bar</data> <data key=\"kids\">[a,b,c]</data> </node>%n" +
            "<edge id=\"e0\" source=\"n0\" target=\"n1\"> <data key=\"label\">:EDGE_LABEL</data> <data key=\"name\">foo</data> </edge>%n" +
            "<edge id=\"e1\" source=\"n1\" target=\"n0\"><data key=\"label\">TEST</data> </edge>%n" +
            "<node id=\"n3\"> <data key=\"labels\">:QWERTY</data><data key=\"name\">qwerty</data> </node>%n" +
            "<edge id=\"e2\" source=\"n1\" target=\"n3\"> <data key=\"label\">KNOWS</data> </edge>%n";

    private static final String DATA_WITHOUT_CHAR_DATA_KEYS = "<node id=\"n0\" labels=\":Foo:Foo0:Foo2\"><data key=\"labels\">:Foo:Foo0:Foo2</data><data key=\"place\">{\"crs\":\"wgs-84-3d\",\"latitude\":12.78,\"longitude\":56.7,\"height\":100.0}</data><data key=\"name\">foo</data><data key=\"born\">2018-10-10</data></node>%n" +
            "<node id=\"n1\" labels=\":Bar\"><data key=\"labels\">:Bar</data><data key=\"age\">42</data><data key=\"name\">bar</data><data key=\"place\">{\"crs\":\"wgs-84\",\"latitude\":12.78,\"longitude\":56.7,\"height\":null}</data></node>%n" +
            "<node id=\"n2\" labels=\":Bar\"><data key=\"labels\">:Bar</data><data key=\"age\">12</data><data key=\"values\">[1,2,3]</data></node>%n" +
            "<edge id=\"e0\" source=\"n0\" target=\"n1\"><data key=\"d10\"/></edge>\n";

    public static final String DATA_NO_DATA_KEY = "<node id=\"A\" labels=\":Unit\"><data key=\"Path\">C:\\bright\\itecembed\\obj\\ada\\a3_status.ads</data></node>\n" +
            "<node id=\"B\" labels=\":Unit\"><data key=\"Path\">C:\\bright\\itecembed\\obj\\ada\\b3_status.ads</data></node>\n" +
            "<edge source=\"A\" target=\"B\"><!-- <data key=\"Path\">C:\\bright\\itecembed\\obj\\ada\\b3_status.ads</data> --></edge>";

    public static final String FOOTER = "</graph>%n" +
            "</graphml>";

    public static final String DATA_PATH = "<node id=\"n0\" labels=\":Foo:Foo0:Foo2\"><data key=\"TYPE\">:Foo:Foo0:Foo2</data><data key=\"label\">foo</data><data key=\"place\">{\"crs\":\"wgs-84-3d\",\"latitude\":12.78,\"longitude\":56.7,\"height\":100.0}</data><data key=\"name\">foo</data><data key=\"born\">2018-10-10</data></node>%n" +
            "<node id=\"n1\" labels=\":Bar\"><data key=\"TYPE\">:Bar</data><data key=\"label\">bar</data><data key=\"age\">42</data><data key=\"name\">bar</data><data key=\"place\">{\"crs\":\"wgs-84\",\"latitude\":12.78,\"longitude\":56.7,\"height\":null}</data></node>%n" +
            "<edge id=\"e0\" source=\"n0\" target=\"n1\" label=\"KNOWS\"><data key=\"label\">KNOWS</data><data key=\"TYPE\">KNOWS</data></edge>%n";

    public static final String DATA_PATH_CAPTION = "<node id=\"n0\" labels=\":Foo:Foo0:Foo2\"><data key=\"TYPE\">:Foo:Foo0:Foo2</data><data key=\"label\">foo</data><data key=\"place\">{\"crs\":\"wgs-84-3d\",\"latitude\":12.78,\"longitude\":56.7,\"height\":100.0}</data><data key=\"name\">foo</data><data key=\"born\">2018-10-10</data></node>%n" +
            "<node id=\"n1\" labels=\":Bar\"><data key=\"TYPE\">:Bar</data><data key=\"label\">bar</data><data key=\"age\">42</data><data key=\"name\">bar</data><data key=\"place\">{\"crs\":\"wgs-84\",\"latitude\":12.78,\"longitude\":56.7,\"height\":null}</data></node>%n" +
            "<edge id=\"e0\" source=\"n0\" target=\"n1\" label=\"KNOWS\"><data key=\"label\">KNOWS</data><data key=\"TYPE\">KNOWS</data></edge>%n";

    public static final String DATA_PATH_CAPTION_DEFAULT = "<node id=\"n0\" labels=\":Foo:Foo0:Foo2\"><data key=\"TYPE\">:Foo:Foo0:Foo2</data><data key=\"label\">point({x: 56.7, y: 12.78, z: 100.0, crs: 'wgs-84-3d'})</data><data key=\"place\">{\"crs\":\"wgs-84-3d\",\"latitude\":12.78,\"longitude\":56.7,\"height\":100.0}</data><data key=\"name\">foo</data><data key=\"born\">2018-10-10</data></node>%n" +
            "<node id=\"n1\" labels=\":Bar\"><data key=\"TYPE\">:Bar</data><data key=\"label\">42</data><data key=\"age\">42</data><data key=\"name\">bar</data><data key=\"place\">{\"crs\":\"wgs-84\",\"latitude\":12.78,\"longitude\":56.7,\"height\":null}</data></node>%n" +
            "<edge id=\"e0\" source=\"n0\" target=\"n1\" label=\"KNOWS\"><data key=\"label\">KNOWS</data><data key=\"TYPE\">KNOWS</data></edge>%n";

    public static final String DATA_DATA = "<node id=\"n3\" labels=\":Person\"><data key=\"labels\">:Person</data><data key=\"name\">Foo</data></node>\n" +
            "<node id=\"n5\" labels=\":Person\"><data key=\"labels\">:Person</data><data key=\"name\">Foo0</data></node>\n";

    private static final String EXPECTED_TYPES_PATH = String.format(HEADER + KEY_TYPES_PATH + GRAPH + DATA_PATH + FOOTER);
    private static final String EXPECTED_TYPES_PATH_CAPTION = String.format(HEADER + KEY_TYPES_PATH + GRAPH + DATA_PATH_CAPTION + FOOTER);
    private static final String EXPECTED_TYPES_PATH_WRONG_CAPTION = String.format(HEADER + KEY_TYPES_PATH + GRAPH + DATA_PATH_CAPTION_DEFAULT + FOOTER);
    private static final String EXPECTED_TYPES = String.format(HEADER + KEY_TYPES + GRAPH + DATA + FOOTER);
    private static final String EXPECTED_TYPES_WITHOUT_CHAR_DATA_KEYS = String.format(HEADER + KEY_TYPES  + GRAPH + DATA_WITHOUT_CHAR_DATA_KEYS + FOOTER);
    private static final String EXPECTED_FALSE = String.format(HEADER + KEY_TYPES_FALSE + GRAPH + DATA + FOOTER);
    private static final String EXPECTED_DATA = String.format(HEADER + KEY_TYPES_DATA + GRAPH + DATA_DATA + FOOTER);
    private static final String EXPECTED_READ_NODE_EDGE = String.format(HEADER + GRAPH + DATA_NODE_EDGE + FOOTER);
    private static final String EXPECTED_TYPES_PATH_CAMEL_CASE = String.format(HEADER + KEY_TYPES_CAMEL_CASE + GRAPH + DATA_CAMEL_CASE + FOOTER);
    private static final String DATA_EMPTY = "<node id=\"n0\" labels=\":Test\"><data key=\"labels\">:Test</data><data key=\"name\"></data><data key=\"limit\">3</data></node>%n";
    private static final String EXPECTED_TYPES_EMPTY = String.format(HEADER + KEY_TYPES_EMPTY + GRAPH + DATA_EMPTY + FOOTER);
    private static final String EXPECTED_TYPES_NO_DATA_KEY = String.format(HEADER + KEY_TYPES_NO_DATA_KEY + GRAPH + DATA_NO_DATA_KEY + FOOTER);

    @Rule
    public TestName testName = new TestName();

    private static final String TEST_WITH_NO_IMPORT = "WithNoImportConfig";
    private static final String TEST_WITH_NO_EXPORT = "WithNoExportConfig";

    @Rule
    public DbmsRule db = new ImpermanentDbmsRule()
                .withSetting(GraphDatabaseSettings.memory_tracking, true)
                .withSetting(GraphDatabaseSettings.tx_state_memory_allocation, OFF_HEAP)
                .withSetting(GraphDatabaseSettings.tx_state_max_off_heap_memory, BYTES.parse("200m"))
                .withSetting(ApocSettings.apoc_import_file_use__neo4j__config, false)
                .withSetting(GraphDatabaseSettings.load_csv_file_url_root, directory.toPath().toAbsolutePath());

    private static File directory = new File("target/import");

    static { //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
    }

    @Before
    public void setUp() throws Exception {
        TestUtil.registerProcedure(db, ExportGraphML.class, Graphs.class);

        apocConfig().setProperty(APOC_EXPORT_FILE_ENABLED, Boolean.toString(!testName.getMethodName().endsWith(TEST_WITH_NO_EXPORT)));
        apocConfig().setProperty(APOC_IMPORT_FILE_ENABLED, Boolean.toString(!testName.getMethodName().endsWith(TEST_WITH_NO_IMPORT)));

        db.executeTransactionally("CREATE (f:Foo:Foo2:Foo0 {name:'foo', born:Date('2018-10-10'), place:point({ longitude: 56.7, latitude: 12.78, height: 100 })})-[:KNOWS]->(b:Bar {name:'bar',age:42, place:point({ longitude: 56.7, latitude: 12.78})}),(c:Bar {age:12,values:[1,2,3]})");
    }

    @Test
    public void testImportGraphML() throws Exception {
        db.executeTransactionally("MATCH (n) DETACH DELETE n");

        File output = new File(directory, "import.graphml");
        FileWriter fw = new FileWriter(output);
        fw.write(EXPECTED_TYPES); fw.close();
        TestUtil.testCall(db, "CALL apoc.import.graphml($file,{readLabels:true})", map("file", output.getAbsolutePath()),
                (r) -> {
                    assertResults(output, r, "statement");
                });

        TestUtil.testCall(db, "MATCH  (c:Bar {age: 12, values: [1,2,3]}) RETURN COUNT(c) AS c", null, (r) -> assertEquals(1L, r.get("c")));
    }

    @Test
    public void testImportGraphMLLargeFile() {
        assumeFalse(isRunningInCI());
        db.executeTransactionally("MATCH (n) DETACH DELETE n");

        final String file = ClassLoader.getSystemResource("largeFile.graphml").toString();
        TestUtil.testCall(db, "CALL apoc.import.graphml($file,{readLabels:true})", map("file", file),
                (r) -> {
                    assertEquals(335160L, r.get("nodes"));
                    assertEquals(5666L, r.get("relationships"));
                    assertEquals(737297L, r.get("properties"));
                    assertEquals(file, r.get("file"));
                    assertEquals("graphml", r.get("format"));
                    assertEquals(true, r.get("done"));
                });
    }

    @Test
    public void testImportGraphMLWithEdgeWithoutDataKeys() throws Exception {
        db.executeTransactionally("MATCH (n) DETACH DELETE n");

        File output = new File(directory, "import.graphml");
        FileWriter fw = new FileWriter(output);
        fw.write(EXPECTED_TYPES_NO_DATA_KEY); fw.close();
        TestUtil.testCall(db, "CALL apoc.import.graphml($file,{readLabels:true})", map("file", output.getAbsolutePath()),
                (r) -> assertEquals(2L, r.get("nodes")));

        TestUtil.testCall(db, "MATCH  ()-[c:RELATED]->() RETURN COUNT(c) AS c", null, (r) -> assertEquals(1L, r.get("c")));
    }


    @Test
    public void testImportGraphMLWithoutCharactersDataKeys() throws Exception {
        db.executeTransactionally("MATCH (n) DETACH DELETE n");

        File output = new File(directory, "import.graphml");
        FileWriter fw = new FileWriter(output);
        fw.write(EXPECTED_TYPES_WITHOUT_CHAR_DATA_KEYS); fw.close();
        TestUtil.testCall(db, "CALL apoc.import.graphml($file,{readLabels:true})", map("file", output.getAbsolutePath()),
                (r) -> {
                    assertResults(output, r, "statement");
                });

        TestUtil.testCall(db, "MATCH  ()-[c:RELATED]->() RETURN COUNT(c) AS c", null, (r) -> assertEquals(1L, r.get("c")));
    }

    private String NO_REL_TYPES = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\"\n" +
            "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
            "         xsi:schemaLocation=\"http://graphml.graphdrawing.org/xmlns\n" +
            "         http://graphml.graphdrawing.org/xmlns/1.0/graphml.xsd\">\n" +
            "<!-- Created by igraph -->\n" +
            "  <key id=\"username\" for=\"node\" attr.name=\"username\" attr.type=\"string\"/>\n" +
            "  <key id=\"weight\" for=\"edge\" attr.name=\"weight\" attr.type=\"double\"/>\n" +
            "  <graph id=\"G\" edgedefault=\"directed\">\n" +
            "    <node id=\"n0\">\n" +
            "      <data key=\"username\">Dodo von den Bergen</data>\n" +
            "    </node>\n" +
            "    <node id=\"n1\">\n" +
            "      <data key=\"username\">Semolo75</data>\n" +
            "    </node>  " +
            "<edge source=\"n1\" target=\"n0\">\n" +
            "      <data key=\"weight\">1</data>\n" +
            "    </edge>  </graph>\n" +
            "</graphml>";
    
    @Test
    public void testImportDefaultRelationship() throws Exception {
        db.executeTransactionally("MATCH (n) DETACH DELETE n");

        File output = new File(directory, "import_no_rel_types.graphml");
        FileWriter fw = new FileWriter(output);
        fw.write(NO_REL_TYPES); fw.close();
        db.executeTransactionally("CALL apoc.import.graphml( $file, { batchSize: 5000, readLabels: true, defaultRelationshipType:'DEFAULT_TYPE' })", map("file", output.getAbsolutePath()));
        TestUtil.testCall(db, "MATCH ()-[r]-() RETURN Distinct type(r) as type",
                (r) -> {
                    String label = (String) r.get("type");
                    assertEquals("DEFAULT_TYPE", label);
                }
        );
    }

    @Test(expected = QueryExecutionException.class)
    public void testImportGraphMLWithNoImportConfig() throws Exception {
        File output = new File(directory, "all.graphml");
        try {
            TestUtil.testCall(db, "CALL apoc.import.graphml($file,{readLabels:true})", map("file", output.getAbsolutePath()), (r) -> assertResults(output, r, "database"));
        } catch (QueryExecutionException e) {
            Throwable except = ExceptionUtils.getRootCause(e);
            TestCase.assertTrue(except instanceof RuntimeException);
            assertEquals("Import from files not enabled, please set apoc.import.file.enabled=true in your apoc.conf", except.getMessage());
            throw e;
        }
    }

    @Test
    public void testImportGraphMLNodeEdge() throws Exception {
        db.executeTransactionally("MATCH (n) DETACH DELETE n");

        File output = new File(directory, "importNodeEdges.graphml");
        FileWriter fw = new FileWriter(output);
        fw.write(EXPECTED_READ_NODE_EDGE); fw.close();
        TestUtil.testCall(db, "CALL apoc.import.graphml($file,{readLabels:true})", map("file", output.getAbsolutePath()),
                (r) -> {
                    assertEquals(3L, r.get("nodes"));
                    assertEquals(3L, r.get("relationships"));
                    assertEquals(5L, r.get("properties"));
                    assertEquals(output.getAbsolutePath(), r.get("file"));
                    if (r.get("source").toString().contains(":"))
                        assertEquals("database: nodes(3), rels(3)", r.get("source"));
                    else
                        assertEquals("file", r.get("source"));
                    assertEquals("graphml", r.get("format"));
                    assertTrue("Should get time greater than 0",((long) r.get("time")) > 0);
                });

        TestUtil.testCall(db, "MATCH (foo:FOO)-[rel:EDGE_LABEL]->(bar:BAR) RETURN foo, rel, bar", null, (r) -> {
            assertFoo(((Node)r.get("foo")));
            assertBar(((Node)r.get("bar")));
            assertEquals("EDGE_LABEL", ((Relationship)r.get("rel")).getType().name());
            assertEquals(Util.map("name", "foo"), ((Relationship)r.get("rel")).getAllProperties());
        });
        TestUtil.testCall(db, "MATCH (bar:BAR)-[test:TEST]->(foo:FOO) RETURN bar, test, foo", null, (r) -> {
            assertFoo(((Node)r.get("foo")));
            assertBar(((Node)r.get("bar")));
            assertEquals("TEST", ((Relationship)r.get("test")).getType().name());
        });
        TestUtil.testCall(db, "MATCH (bar:BAR)-[knows:KNOWS]->(qwerty:QWERTY) RETURN bar, knows, qwerty", null, (r) -> {
            assertBar(((Node)r.get("bar")));
            assertEquals(Arrays.asList(Label.label("QWERTY")), ((Node)r.get("qwerty")).getLabels());
            assertEquals(Util.map("name", "qwerty"), ((Node)r.get("qwerty")).getAllProperties());
            assertEquals("KNOWS", ((Relationship)r.get("knows")).getType().name());
        });

    }

    private void assertBar(Node node){
        assertEquals(Arrays.asList(Label.label("BAR")), node.getLabels());
        assertEquals(Util.map("name", "bar", "kids", "[a,b,c]"), node.getAllProperties());
    }

    private void assertFoo(Node node){
        assertEquals(Arrays.asList(Label.label("FOO")), node.getLabels());
        assertEquals(Util.map("name", "foo"), node.getAllProperties());
    }

    @Test
    public void testExportDataGraphML() throws Exception {
        db.executeTransactionally("MATCH (n) DETACH DELETE n");
        db.executeTransactionally("CREATE (p:Person {name: 'Foo'})");
        db.executeTransactionally("CREATE (p:Person {name: 'Bar'})");
        db.executeTransactionally("CREATE (p:Person {name: 'Baz'})");
        db.executeTransactionally("CREATE (p:Person {name: 'Foo0'})");

        File output = new File(directory, "data.graphml");
        TestUtil.testCall(db, "MATCH (person:Person) \n" +
                        "WHERE person.name STARTS WITH \"F\"\n" +
                        "WITH collect(person) as people\n" +
                        "CALL apoc.export.graphml.data(people, [], $file, {})\n" +
                        "YIELD file, source, format, nodes, relationships, properties, time, rows, batchSize, batches, done, data\n" +
                        "RETURN *",
                map("file", output.getAbsolutePath()),
                (r) -> assertEquals(2L, r.get("nodes")));
        assertXMLEquals(output, EXPECTED_DATA);
    }

    @Test
    public void testExportAllGraphML() throws Exception {
        File output = new File(directory, "all.graphml");
        TestUtil.testCall(db, "CALL apoc.export.graphml.all($file,null)", map("file", output.getAbsolutePath()),
                (r) -> assertResults(output, r, "database"));
        assertXMLEquals(output, EXPECTED_FALSE);
    }

    @Test
    public void testExportGraphGraphML() throws Exception {
        File output = new File(directory, "graph.graphml");
        TestUtil.testCall(db, "CALL apoc.graph.fromDB('test',{}) yield graph " +
                        "CALL apoc.export.graphml.graph(graph, $file,null) " +
                        "YIELD nodes, relationships, properties, file, source,format, time " +
                        "RETURN *", map("file", output.getAbsolutePath()),
                (r) -> assertResults(output, r, "graph"));
        assertXMLEquals(output, EXPECTED_FALSE);
    }

    private void assertXMLEquals(Object output, String xmlString) {
        Diff myDiff = DiffBuilder.compare(xmlString)
                .withTest(output)
                .checkForSimilar()
                .ignoreWhitespace()
                .withAttributeFilter(attr -> !ATTRIBUTES_CONTAINING_NODE_IDS.contains(attr.getLocalName())) // ignore id properties
//                .withNodeMatcher(new DefaultNodeMatcher(ElementSelectors.byNameAndAllAttributes))
                // similar to ElementSelectors.byNameAndAllAttributes, but ignore blacklistes attributes
                .withNodeMatcher(new DefaultNodeMatcher((ElementSelector) (controlElement, testElement) -> {
                    if (!byName.canBeCompared(controlElement, testElement)) {
                        return false;
                    }
                    Map<QName, String> cAttrs = Nodes.getAttributes(controlElement);
                    Map<QName, String> tAttrs = Nodes.getAttributes(testElement);
                    if (cAttrs.size() != tAttrs.size()) {
                        return false;
                    }
                    for (Map.Entry<QName, String> e: cAttrs.entrySet()) {
                        if ((!ATTRIBUTES_CONTAINING_NODE_IDS.contains(e.getKey().getLocalPart()))
                            && (!e.getValue().equals(tAttrs.get(e.getKey())))) {
                            return false;
                        }
                    }
                    return true;
                }))
                .build();

        assertFalse(myDiff.toString(), myDiff.hasDifferences());
    }

    @Test
    public void testExportGraphGraphMLTypes() throws Exception {
        File output = new File(directory, "graph.graphml");
        TestUtil.testCall(db, "CALL apoc.graph.fromDB('test',{}) yield graph " +
                        "CALL apoc.export.graphml.graph(graph, $file,{useTypes:true}) " +
                        "YIELD nodes, relationships, properties, file, source,format, time " +
                        "RETURN *", map("file", output.getAbsolutePath()),
                (r) -> assertResults(output, r, "graph"));
        assertXMLEquals(output, EXPECTED_TYPES);
    }

    @Test(expected = QueryExecutionException.class)
    public void testExportGraphGraphMLTypesWithNoExportConfig() throws Exception {
        File output = new File(directory, "all.graphml");
        try {
            TestUtil.testCall(db, "CALL apoc.export.graphml.all($file,null)", map("file", output.getAbsolutePath()), (r) -> assertResults(output, r, "database"));
        } catch (QueryExecutionException e) {
            Throwable except = ExceptionUtils.getRootCause(e);
            TestCase.assertTrue(except instanceof RuntimeException);
            assertEquals("Export to files not enabled, please set apoc.export.file.enabled=true in your apoc.conf", except.getMessage());
            throw e;
        }
    }

    @Test
    public void testImportAndExport() throws Exception {
        db.executeTransactionally("MATCH (n) detach delete (n)");
        File output = new File(directory, "graphEmpty.graphml");
        db.executeTransactionally("CREATE (n:Test {name : '', limit : 3}) RETURN n");
        TestUtil.testCall(db, "call apoc.export.graphml.all($file,{storeNodeIds:true, readLabels:true, useTypes:true, defaultRelationshipType:'RELATED'})",
                map("file", output.getAbsolutePath()),
                (r) -> assertResultEmpty(output, r));
        assertXMLEquals(output, EXPECTED_TYPES_EMPTY);

        db.executeTransactionally("MATCH (n) DETACH DELETE n");

        File input = new File(directory, "importEmpty.graphml");
        FileWriter fw = new FileWriter(input);
        fw.write(EXPECTED_TYPES_EMPTY); fw.close();
        TestUtil.testCall(db, "CALL apoc.import.graphml($file,{readLabels:true})", map("file", input.getAbsolutePath()),
                (r) -> assertResultEmpty(input, r));

        TestUtil.testCall(db, "MATCH (n:Test) RETURN n.name as name, n.limit as limit", null, (r) -> {
                assertEquals(StringUtils.EMPTY, r.get("name"));
                assertEquals(3L, r.get("limit"));
            }
        );
        db.executeTransactionally("MATCH (n) detach delete (n)");
        db.executeTransactionally("CREATE (f:Foo:Foo2:Foo0 {name:'foo'})-[:KNOWS]->(b:Bar {name:'bar',age:42}),(c:Bar {age:12,values:[1,2,3]})");

    }

    private void assertResultEmpty(File output, Map<String, Object> r) {
        assertEquals(1L, r.get("nodes"));
        assertEquals(0L, r.get("relationships"));
        assertEquals(2L, r.get("properties"));
        assertEquals(output.getAbsolutePath(), r.get("file"));
        if (r.get("source").toString().contains(":"))
            assertEquals("database: nodes(1), rels(0)", r.get("source"));
        else
            assertEquals("file", r.get("source"));
        assertEquals("graphml", r.get("format"));
        assertTrue("Should get time greater than 0", ((long) r.get("time")) > 0);
    }

    public void testExportGraphGraphMLQueryGephi() throws Exception {
        File output = new File(directory, "query.graphml");
        TestUtil.testCall(db, "call apoc.export.graphml.query('MATCH p=()-[r]->() RETURN p limit 1000',$file,{useTypes:true, format: 'gephi'}) ", map("file", output.getAbsolutePath()),
                (r) -> {
                    assertEquals(2L, r.get("nodes"));
                    assertEquals(1L, r.get("relationships"));
                    assertEquals(6L, r.get("properties"));
                    assertEquals(output.getAbsolutePath(), r.get("file"));
                    if (r.get("source").toString().contains(":"))
                        assertEquals("statement" + ": nodes(2), rels(1)", r.get("source"));
                    else
                        assertEquals("file", r.get("source"));
                    assertEquals("graphml", r.get("format"));
                    assertTrue("Should get time greater than 0",((long) r.get("time")) > 0);
                });
        assertXMLEquals(output, EXPECTED_TYPES_PATH);
    }

    @Test
    public void testExportGraphGraphMLQueryGephiWithArrayCaption() throws Exception {
        File output = new File(directory, "query.graphml");
        TestUtil.testCall(db, "call apoc.export.graphml.query('MATCH p=()-[r]->() RETURN p limit 1000',$file,{useTypes:true, format: 'gephi', caption: ['bar','name','foo']}) ", map("file", output.getAbsolutePath()),
                (r) -> {
                    assertEquals(2L, r.get("nodes"));
                    assertEquals(1L, r.get("relationships"));
                    assertEquals(6L, r.get("properties"));
                    assertEquals(output.getAbsolutePath(), r.get("file"));
                    if (r.get("source").toString().contains(":"))
                        assertEquals("statement" + ": nodes(2), rels(1)", r.get("source"));
                    else
                        assertEquals("file", r.get("source"));
                    assertEquals("graphml", r.get("format"));
                    assertTrue("Should get time greater than 0",((long) r.get("time")) > 0);
                });
        assertXMLEquals(output, EXPECTED_TYPES_PATH_CAPTION);
    }

    @Test
    public void testExportGraphGraphMLQueryGephiWithArrayCaptionWrong() throws Exception {
        File output = new File(directory, "query.graphml");
        TestUtil.testCall(db, "call apoc.export.graphml.query('MATCH p=()-[r]->() RETURN p limit 1000',$file,{useTypes:true, format: 'gephi', caption: ['a','b','c']}) ", map("file", output.getAbsolutePath()),
                (r) -> {
                    assertEquals(2L, r.get("nodes"));
                    assertEquals(1L, r.get("relationships"));
                    assertEquals(6L, r.get("properties"));
                    assertEquals(output.getAbsolutePath(), r.get("file"));
                    if (r.get("source").toString().contains(":"))
                        assertEquals("statement" + ": nodes(2), rels(1)", r.get("source"));
                    else
                        assertEquals("file", r.get("source"));
                    assertEquals("graphml", r.get("format"));
                    assertTrue("Should get time greater than 0",((long) r.get("time")) > 0);
                });
        assertXMLEquals(output, EXPECTED_TYPES_PATH_WRONG_CAPTION);
    }

    @Test(expected = QueryExecutionException.class)
    public void testExportGraphGraphMLQueryGephiWithStringCaption() throws Exception {
        File output = new File(directory, "query.graphml");
        try {
        TestUtil.testCall(db, "call apoc.export.graphml.query('MATCH p=()-[r]->() RETURN p limit 1000',$file,{useTypes:true, format: 'gephi', caption: 'name'}) ", map("file", output.getAbsolutePath()),
                (r) -> {});
        } catch (QueryExecutionException e) {
            Throwable except = ExceptionUtils.getRootCause(e);
            TestCase.assertTrue(except instanceof RuntimeException);
            assertEquals("Only array of Strings are allowed!", except.getMessage());
            throw e;
        }
    }

    @Test
    public void testExportGraphmlQueryWithStringCaptionCamelCase() throws FileNotFoundException {
        db.executeTransactionally("MATCH (n) detach delete (n)");
        db.executeTransactionally("CREATE (f:Foo:Foo2:Foo0 {firstName:'foo'})-[:KNOWS]->(b:Bar {name:'bar',ageNow:42}),(c:Bar {age:12,values:[1,2,3]})");
        File output = new File(directory, "query.graphml");
        TestUtil.testCall(db, "call apoc.export.graphml.query('MATCH p=()-[r]->() RETURN p limit 1000',$file,{useTypes:true, format: 'gephi'}) ", map("file", output.getAbsolutePath()),
                (r) -> {
                    assertEquals(2L, r.get("nodes"));
                    assertEquals(1L, r.get("relationships"));
                    assertEquals(3L, r.get("properties"));
                    assertEquals(output.getAbsolutePath(), r.get("file"));
                    if (r.get("source").toString().contains(":"))
                        assertEquals("statement" + ": nodes(2), rels(1)", r.get("source"));
                    else
                        assertEquals("file", r.get("source"));
                    assertEquals("graphml", r.get("format"));
                    assertTrue("Should get time greater than 0",((long) r.get("time")) > 0);
                });
        assertXMLEquals(output, EXPECTED_TYPES_PATH_CAMEL_CASE);
    }

    private void assertResults(File output, Map<String, Object> r, final String source) {
        assertCommons(r);
        assertEquals(output.getAbsolutePath(), r.get("file"));
        if (r.get("source").toString().contains(":"))
            assertEquals(source + ": nodes(3), rels(1)", r.get("source"));
        else
            assertEquals("file", r.get("source"));
        assertNull("data should be null", r.get("data"));
    }

    private void assertCommons(Map<String, Object> r) {
        assertEquals(3L, r.get("nodes"));
        assertEquals(1L, r.get("relationships"));
        assertEquals(8L, r.get("properties"));
        assertEquals("graphml", r.get("format"));
        assertTrue("Should get time greater than 0",((long) r.get("time")) > 0);
    }

    private void assertStreamResults(Map<String, Object> r, final String source) {
        assertCommons(r);
        assertEquals(source + ": nodes(3), rels(1)", r.get("source"));
        assertNull("file should be null", r.get("file"));
        assertNotNull("data should be not null", r.get("data"));
    }

    @Test
    public void testExportAllGraphMLStream() throws Exception {
        TestUtil.testCall(db, "CALL apoc.export.graphml.all(null, {stream: true})",
                (r) -> {
                    assertStreamResults(r, "database");
                    assertXMLEquals(r.get("data"), EXPECTED_FALSE);
                });
    }
}
