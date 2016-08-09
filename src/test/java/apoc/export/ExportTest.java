package apoc.export;

import apoc.graph.Graphs;
import apoc.util.TestUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.test.TestGraphDatabaseFactory;

import java.io.File;
import java.util.Map;
import java.util.Scanner;

import static apoc.util.MapUtil.map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author mh
 * @since 22.05.16
 */
public class ExportTest {

    private static final String line_sep = System.lineSeparator();
    private static final String EXPECTED = "begin" + line_sep +
            "CREATE (:`Foo`:`UNIQUE IMPORT LABEL` {`name`:\"foo\", `UNIQUE IMPORT ID`:0});" + line_sep +
            "CREATE (:`Bar` {`name`:\"bar\", `age`:42});" + line_sep +
            "CREATE (:`Bar`:`UNIQUE IMPORT LABEL` {`age`:12, `UNIQUE IMPORT ID`:2});" + line_sep +
            "commit" + line_sep +
            "begin" + line_sep +
            "CREATE INDEX ON :`Foo`(`name`);" + line_sep +
            "CREATE CONSTRAINT ON (node:`Bar`) ASSERT node.`name` IS UNIQUE;" + line_sep +
            "CREATE CONSTRAINT ON (node:`UNIQUE IMPORT LABEL`) ASSERT node.`UNIQUE IMPORT ID` IS UNIQUE;" + line_sep +
            "commit" + line_sep +
            "schema await" + line_sep +
            "begin" + line_sep +
            "MATCH (n1:`UNIQUE IMPORT LABEL`{`UNIQUE IMPORT ID`:0}), (n2:`Bar`{`name`:\"bar\"}) CREATE (n1)-[:`KNOWS`]->(n2);" + line_sep +
            "commit" + line_sep +
            "begin" + line_sep +
            "MATCH (n:`UNIQUE IMPORT LABEL`)  WITH n LIMIT 20000 REMOVE n:`UNIQUE IMPORT LABEL` REMOVE n.`UNIQUE IMPORT ID`;" + line_sep +
            "commit" + line_sep +
            "begin" + line_sep +
            "DROP CONSTRAINT ON (node:`UNIQUE IMPORT LABEL`) ASSERT node.`UNIQUE IMPORT ID` IS UNIQUE;" + line_sep +
            "commit";

    private static GraphDatabaseService db;
    private static File directory = new File("target/import");

    static { //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
    }

    @BeforeClass
    public static void setUp() throws Exception {
        db = new TestGraphDatabaseFactory()
                .newImpermanentDatabaseBuilder()
                .setConfig(GraphDatabaseSettings.load_csv_file_url_root, directory.getAbsolutePath())
                .newGraphDatabase();
        TestUtil.registerProcedure(db, Export.class, Graphs.class);
        db.execute("CREATE INDEX ON :Foo(name)").close();
        db.execute("CREATE CONSTRAINT ON (b:Bar) ASSERT b.name is unique").close();
        db.execute("CREATE (f:Foo {name:'foo'})-[:KNOWS]->(b:Bar {name:'bar',age:42}),(c:Bar {age:12})").close();
    }

    @AfterClass
    public static void tearDown() {
        db.shutdown();
    }

    @Test
    public void testExportAllCypher() throws Exception {
        File output = new File(directory, "all.cypher");
        TestUtil.testCall(db, "CALL apoc.export.cypherAll({file},null)", map("file", output.getAbsolutePath()),
                (r) -> assertResults(output, r, "database"));
        assertEquals(EXPECTED, new Scanner(output).useDelimiter("\\Z").next());
    }

    @Test
    public void testExportGraphCypher() throws Exception {
        File output = new File(directory, "graph.cypher");
        TestUtil.testCall(db, "CALL apoc.graph.fromDB('test',{}) yield graph " +
                        "CALL apoc.export.cypherGraph(graph, {file},null) " +
                        "YIELD nodes, relationships, properties, file, source,format, time " +
                        "RETURN *", map("file", output.getAbsolutePath()),
                (r) -> assertResults(output, r, "graph"));
        assertEquals(EXPECTED, new Scanner(output).useDelimiter("\\Z").next());
    }

    private void assertResults(File output, Map<String, Object> r, final String source) {
        assertEquals(3L, r.get("nodes"));
        assertEquals(1L, r.get("relationships"));
        assertEquals(4L, r.get("properties"));
        assertEquals(output.getAbsolutePath(), r.get("file"));
        assertEquals(source + ": nodes(3), rels(1)", r.get("source"));
        assertEquals("cypher", r.get("format"));
        assertEquals(true, ((long) r.get("time")) > 0);
    }
}
