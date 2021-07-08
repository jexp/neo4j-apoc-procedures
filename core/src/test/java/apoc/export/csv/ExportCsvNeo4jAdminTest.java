package apoc.export.csv;

import apoc.ApocSettings;
import apoc.graph.Graphs;
import apoc.util.TestUtil;
import apoc.util.Util;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Map;

import static apoc.util.MapUtil.map;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;

public class ExportCsvNeo4jAdminTest {

    private static final String EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_TYPES_NODE = String
            .format(":ID;born_3D:point;localtime:localtime;time:time;localDateTime:localdatetime;duration:duration;dateTime:datetime;born_2D:point;date:date;:LABEL%n");

    private static final String EXPECTED_NEO4J_ADMIN_IMPORT_TYPES_NODE = String
            .format("6;{crs:wgs-84-3d,latitude:12.78,longitude:56.7,height:100.0};12:50:35.556;12:50:35.556+01:00;2018-10-30T19:32:24;P5M1DT12H;2018-10-30T12:50:35.556+01:00;{crs:cartesian,x:2.3,y:4.5};2018-10-30;Types%n");

    private static final String EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_NODE_ADDRESS = String
            .format(":ID;name;street;:LABEL%n");

    private static final String EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_NODE_ADDRESS1 = String
            .format(":ID;street;name;city;:LABEL%n");

    private static final String EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_NODE_USER = String
            .format(":ID;name;age:long;:LABEL%n");

    private static final String EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_NODE_USER1 = String
            .format(":ID;name;age:long;male:boolean;kids;:LABEL%n");

    private static final String EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_RELATIONSHIP_KNOWS = String
            .format(":START_ID;:END_ID;:TYPE%n");

    private static final String EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_RELATIONSHIP_NEXT_DELIVERY = String
            .format(":START_ID;:END_ID;:TYPE%n");

    private static final String EXPECTED_NEO4J_ADMIN_IMPORT_NODE_ADDRESS = String
            .format("4;Bar Sport;;Address%n" +
                    "5;;via Benni;Address%n");

    private static final String EXPECTED_NEO4J_ADMIN_IMPORT_NODE_ADDRESS1 = String
            .format("3;Via Garibaldi, 7;Andrea;Milano;\"Address1;Address\"%n");

    private static final String EXPECTED_NEO4J_ADMIN_IMPORT_NODE_USER = String
            .format("1;bar;42;User%n" +
                    "2;;12;User%n");

    private static final String EXPECTED_NEO4J_ADMIN_IMPORT_NODE_USER1 = String
            .format("0;foo;42;true;[a,b,c];\"User1;User\"%n");

    private static final String EXPECTED_NEO4J_ADMIN_IMPORT_RELATIONSHIP_KNOWS = String
            .format("0;1;KNOWS%n");

    private static final String EXPECTED_NEO4J_ADMIN_IMPORT_RELATIONSHIP_NEXT_DELIVERY = String
            .format("3;4;NEXT_DELIVERY%n");

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
        TestUtil.registerProcedure(db, ExportCSV.class, Graphs.class);
        db.executeTransactionally("CREATE (f:User1:User {name:'foo',age:42,male:true,kids:['a','b','c']})-[:KNOWS]->(b:User {name:'bar',age:42}),(c:User {age:12})");
        db.executeTransactionally("CREATE (f:Address1:Address {name:'Andrea', city: 'Milano', street:'Via Garibaldi, 7'})-[:NEXT_DELIVERY]->(a:Address {name: 'Bar Sport'}), (b:Address {street: 'via Benni'})");
        db.executeTransactionally("CREATE (a:Types {date: date('2018-10-30'), localDateTime: localdatetime('20181030T19:32:24'), dateTime: datetime('2018-10-30T12:50:35.556+0100'), localtime: localtime('12:50:35.556'), duration: duration('P5M1DT12H'), time: time('125035.556+0100'), born_2D: point({ x: 2.3, y: 4.5 }), born_3D:point({ longitude: 56.7, latitude: 12.78, height: 100 })})");
    }

    @Test
    public void testCypherExportCsvForAdminNeo4jImportWithConfig() throws Exception {

        String fileName = "query_nodes.csv";
        File dir = new File(directory, fileName);

        TestUtil.testCall(db, "CALL apoc.export.csv.all($fileName,{bulkImport: true, separateHeader: true, delim: ';'})",
                map("fileName", fileName), r -> {
                    assertEquals(20000L, r.get("batchSize"));
                    assertEquals(1L, r.get("batches"));
                    assertEquals(7L, r.get("nodes"));
                    assertEquals(9L, r.get("rows"));
                    assertEquals(2L, r.get("relationships"));
                    assertEquals(20L, r.get("properties"));
                    assertTrue("Should get time greater than 0",
                            ((long) r.get("time")) >= 0);
                }
        );

        String file = dir.getParent() + File.separator;
        assertFileEquals(file, EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_NODE_ADDRESS, "query_nodes.header.nodes.Address.csv");
        assertFileEquals(file, EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_NODE_ADDRESS1, "query_nodes.header.nodes.Address1.Address.csv");
        assertFileEquals(file, EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_NODE_USER, "query_nodes.header.nodes.User.csv");
        assertFileEquals(file, EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_NODE_USER1, "query_nodes.header.nodes.User1.User.csv");
        assertFileEquals(file, EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_TYPES_NODE, "query_nodes.header.nodes.Types.csv");
        assertFileEquals(file, EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_RELATIONSHIP_KNOWS, "query_nodes.header.relationships.KNOWS.csv");
        assertFileEquals(file, EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_RELATIONSHIP_NEXT_DELIVERY, "query_nodes.header.relationships.NEXT_DELIVERY.csv");
        assertFileEquals(file, EXPECTED_NEO4J_ADMIN_IMPORT_NODE_ADDRESS, "query_nodes.nodes.Address.csv");
        assertFileEquals(file, EXPECTED_NEO4J_ADMIN_IMPORT_NODE_ADDRESS1, "query_nodes.nodes.Address1.Address.csv");
        assertFileEquals(file, EXPECTED_NEO4J_ADMIN_IMPORT_NODE_USER, "query_nodes.nodes.User.csv");
        assertFileEquals(file, EXPECTED_NEO4J_ADMIN_IMPORT_NODE_USER1, "query_nodes.nodes.User1.User.csv");
        assertFileEquals(file, EXPECTED_NEO4J_ADMIN_IMPORT_TYPES_NODE, "query_nodes.nodes.Types.csv");
        assertFileEquals(file, EXPECTED_NEO4J_ADMIN_IMPORT_RELATIONSHIP_KNOWS, "query_nodes.relationships.KNOWS.csv");
        assertFileEquals(file, EXPECTED_NEO4J_ADMIN_IMPORT_RELATIONSHIP_NEXT_DELIVERY, "query_nodes.relationships.NEXT_DELIVERY.csv");
    }

    @Test
    public void testExportGraphNeo4jAdminCsv() throws Exception {
        String fileName = "graph.csv";
        File output = new File(directory, fileName);
        TestUtil.testCall(db, "CALL apoc.graph.fromDB('test',{}) yield graph " +
                        "CALL apoc.export.csv.graph(graph, $fileName,{bulkImport: true, delim: ';'}) " +
                        "YIELD nodes, relationships, properties, file, source,format, time " +
                        "RETURN *", map("fileName", fileName),
                (r) -> assertResults(fileName, r, "graph"));

        String file = output.getParent() + File.separator;
        assertFileEquals(file,EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_NODE_ADDRESS + EXPECTED_NEO4J_ADMIN_IMPORT_NODE_ADDRESS, "graph.nodes.Address.csv");
        assertFileEquals(file,EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_NODE_ADDRESS1 + EXPECTED_NEO4J_ADMIN_IMPORT_NODE_ADDRESS1, "graph.nodes.Address1.Address.csv");
        assertFileEquals(file,EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_NODE_USER + EXPECTED_NEO4J_ADMIN_IMPORT_NODE_USER, "graph.nodes.User.csv");
        assertFileEquals(file,EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_NODE_USER1 + EXPECTED_NEO4J_ADMIN_IMPORT_NODE_USER1, "graph.nodes.User1.User.csv");
        assertFileEquals(file,EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_TYPES_NODE + EXPECTED_NEO4J_ADMIN_IMPORT_TYPES_NODE, "graph.nodes.Types.csv");
        assertFileEquals(file,EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_RELATIONSHIP_KNOWS + EXPECTED_NEO4J_ADMIN_IMPORT_RELATIONSHIP_KNOWS, "graph.relationships.KNOWS.csv");
        assertFileEquals(file,EXPECTED_NEO4J_ADMIN_IMPORT_HEADER_RELATIONSHIP_NEXT_DELIVERY + EXPECTED_NEO4J_ADMIN_IMPORT_RELATIONSHIP_NEXT_DELIVERY, "graph.relationships.NEXT_DELIVERY.csv");
    }

    private void assertFileEquals(String base, String expected, String file)  {
        try {
            assertEquals(expected, FileUtils.readFileToString(new File(base + file), Charset.forName("UTF-8")));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test(expected = RuntimeException.class)
    public void testCypherExportCsvForAdminNeo4jImportExceptionBulk() throws Exception {
        String fileName = "query_nodes.csv";
        try {
            TestUtil.testCall(db, "CALL apoc.export.csv.query('MATCH (n) return (n)',$fileName,{bulkImport: true})",
                    Util.map("fileName", fileName), (r) -> {});
        } catch (Exception e) {
            Throwable except = ExceptionUtils.getRootCause(e);
            assertTrue(except instanceof RuntimeException);
            assertEquals("You can use the `bulkImport` only with apoc.export.all and apoc.export.csv.graph", except.getMessage());
            throw e;
        }
    }

    private void assertResults(String fileName, Map<String, Object> r, final String source) {
        assertEquals(7L, r.get("nodes"));
        assertEquals(2L, r.get("relationships"));
        assertEquals(20L, r.get("properties"));
        assertEquals(source + ": nodes(7), rels(2)", r.get("source"));
        assertEquals(fileName, r.get("file"));
        assertEquals("csv", r.get("format"));
        assertTrue("Should get time greater than 0",((long) r.get("time")) >= 0);
    }

    @Test
    public void testExportCypherWithIdField() throws Exception {
        // given
        db.executeTransactionally("MATCH (n) DETACH DELETE n");
        final Map<String, Object> map = db.executeTransactionally("CREATE (source:User:Larus{id: 1, name: 'Andrea'})-[:KNOWS{id: 10}]->(target:User:Neo4j{id: 2, name: 'Michael'})\n" +
                "RETURN id(source) as sourceId, id(target) as targetId", Collections.emptyMap(), result ->  Iterators.single(result) );
        final String fileName = "export_id_field";
        String fileNameWithExtension = fileName + ".csv";
        File dir = new File(directory, fileNameWithExtension);

        // when
        TestUtil.testCall(db, "CALL apoc.export.csv.all($fileNameWithExtension,{bulkImport: true})",
                map("fileNameWithExtension", fileNameWithExtension), r -> {
                    // then
                    assertEquals(20000L, r.get("batchSize"));
                    assertEquals(1L, r.get("batches"));
                    assertEquals(2L, r.get("nodes"));
                    assertEquals(3L, r.get("rows"));
                    assertEquals(1L, r.get("relationships"));
                    assertEquals(5L, r.get("properties"));
                    assertTrue("Should get time greater than 0",
                            ((long) r.get("time")) >= 0);

                    String file = dir.getParent() + File.separator;
                    String expectedNodesLarus = String.format(":ID,name,id:long,:LABEL%n"
                            + "%s,Andrea,1,User;Larus%n", map.get("sourceId"));
                    String expectedNodesNeo4j = String.format(":ID,name,id:long,:LABEL%n"
                            +"%s,Michael,2,User;Neo4j%n", map.get("targetId"));
                    String expectedRelsNeo4j = String.format(":START_ID,:END_ID,:TYPE,id:long%n"
                            + "%s,%s,KNOWS,10%n", map.get("sourceId"), map.get("targetId"));

                    assertFileEquals(file, expectedNodesLarus, fileName + ".nodes.User.Larus.csv");
                    assertFileEquals(file, expectedNodesNeo4j, fileName + ".nodes.User.Neo4j.csv");
                    assertFileEquals(file, expectedRelsNeo4j, fileName + ".relationships.KNOWS.csv");
                }
        );
    }
}

