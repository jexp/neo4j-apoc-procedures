package apoc.custom;

import apoc.path.PathExplorer;
import apoc.util.FileUtils;
import apoc.util.TestUtil;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * @author mh
 * @since 18.08.18
 */
public class CypherProceduresStorageTest {

    @Rule
    public TemporaryFolder STORE_DIR = new TemporaryFolder();

    private GraphDatabaseService db;
    private DatabaseManagementService databaseManagementService;

    @Before
    public void setUp() throws Exception {
        databaseManagementService = new TestDatabaseManagementServiceBuilder(STORE_DIR.getRoot().toPath()).build();
        db = databaseManagementService.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME);
        TestUtil.registerProcedure(db, CypherProcedures.class, PathExplorer.class);
    }

    private void restartDb() throws IOException {
        databaseManagementService.shutdown();
        databaseManagementService = new TestDatabaseManagementServiceBuilder(STORE_DIR.getRoot().toPath()).build();
        db = databaseManagementService.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME);
        assertTrue(db.isAvailable(1000));
        TestUtil.registerProcedure(db, CypherProcedures.class, PathExplorer.class);
    }
    @Test
    public void registerSimpleStatement() throws Exception {
        db.executeTransactionally("call apoc.custom.asProcedure('answer','RETURN 42 as answer')");
        restartDb();
        TestUtil.testCall(db, "call custom.answer()", (row) -> assertEquals(42L, ((Map)row.get("row")).get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("answer", row.get("name"));
            assertEquals("procedure", row.get("type"));
        });
    }

    @Test
    public void registerSimpleFunctionWithDotInName() throws Exception {
        db.executeTransactionally("call apoc.custom.asFunction('foo.bar.baz','RETURN 42 as answer')");
        TestUtil.testCall(db, "return custom.foo.bar.baz() as row", (row) -> assertEquals(42L, ((Map)((List)row.get("row")).get(0)).get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("foo.bar.baz", row.get("name"));
            assertEquals("function", row.get("type"));
        });
        restartDb();
        TestUtil.testCall(db, "return custom.foo.bar.baz() as row", (row) -> assertEquals(42L, ((Map)((List)row.get("row")).get(0)).get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("foo.bar.baz", row.get("name"));
            assertEquals("function", row.get("type"));
        });
    }

    @Test
    public void registerSimpleProcedureWithDotInName() throws Exception {
        db.executeTransactionally("call apoc.custom.asProcedure('foo.bar.baz','RETURN 42 as answer')");
        TestUtil.testCall(db, "call custom.foo.bar.baz()", (row) -> assertEquals(42L, ((Map)row.get("row")).get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("foo.bar.baz", row.get("name"));
            assertEquals("procedure", row.get("type"));
        });
        restartDb();
        TestUtil.testCall(db, "call custom.foo.bar.baz()", (row) -> assertEquals(42L, ((Map)row.get("row")).get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("foo.bar.baz", row.get("name"));
            assertEquals("procedure", row.get("type"));
        });
    }

    @Test
    public void registerSimpleStatementConcreteResults() throws Exception {
        db.executeTransactionally("call apoc.custom.asProcedure('answer','RETURN 42 as answer','read',[['answer','long']])");
        restartDb();
        TestUtil.testCall(db, "call custom.answer()", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void registerParameterStatement() throws Exception {
        db.executeTransactionally("call apoc.custom.asProcedure('answer','RETURN $answer as answer')");
        restartDb();
        TestUtil.testCall(db, "call custom.answer({answer:42})", (row) -> assertEquals(42L, ((Map)row.get("row")).get("answer")));
    }

    @Test
    public void registerConcreteParameterStatement() throws Exception {
        db.executeTransactionally("call apoc.custom.asProcedure('answer','RETURN $input as answer','read',null,[['input','number']])");
        restartDb();
        TestUtil.testCall(db, "call custom.answer(42)", (row) -> assertEquals(42L, ((Map)row.get("row")).get("answer")));
    }

    @Test
    public void registerConcreteParameterAndReturnStatement() throws Exception {
        db.executeTransactionally("call apoc.custom.asProcedure('answer','RETURN $input as answer','read',[['answer','number']],[['input','int','42']])");
        restartDb();
        TestUtil.testCall(db, "call custom.answer()", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void testAllParameterTypes() throws Exception {
        db.executeTransactionally("call apoc.custom.asProcedure('answer','RETURN [$int,$float,$string,$map,$`list int`,$bool,$date,$datetime,$point] as data','read',null," +
                "[['int','int'],['float','float'],['string','string'],['map','map'],['list int','list int'],['bool','bool'],['date','date'],['datetime','datetime'],['point','point']])");
        restartDb();
        TestUtil.testCall(db, "call custom.answer(42,3.14,'foo',{a:1},[1],true,date(),datetime(),point({x:1,y:2}))", (row) -> assertEquals(9, ((List)((Map)row.get("row")).get("data")).size()));
    }

    @Test
    public void registerSimpleStatementFunction() throws Exception {
        db.executeTransactionally("call apoc.custom.asFunction('answer','RETURN 42 as answer')");
        TestUtil.testCall(db, "return custom.answer() as row", (row) -> assertEquals(42L, ((Map)((List)row.get("row")).get(0)).get("answer")));
        restartDb();
        TestUtil.testCall(db, "return custom.answer() as row", (row) -> assertEquals(42L, ((Map)((List)row.get("row")).get(0)).get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("answer", row.get("name"));
            assertEquals("function", row.get("type"));
        });
    }

    @Test
    public void registerSimpleStatementFunctionWithDotInName() throws Exception {
        db.executeTransactionally("call apoc.custom.asFunction('foo.bar.baz','RETURN 42 as answer')");
        TestUtil.testCall(db, "return custom.foo.bar.baz() as row", (row) -> assertEquals(42L, ((Map)((List)row.get("row")).get(0)).get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("foo.bar.baz", row.get("name"));
            assertEquals("function", row.get("type"));
        });
        restartDb();
        TestUtil.testCall(db, "return custom.foo.bar.baz() as row", (row) -> assertEquals(42L, ((Map)((List)row.get("row")).get(0)).get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("foo.bar.baz", row.get("name"));
            assertEquals("function", row.get("type"));
        });
    }

    @Test
    public void registerSimpleStatementConcreteResultsFunction() throws Exception {
        db.executeTransactionally("call apoc.custom.asFunction('answer','RETURN 42 as answer','long')");
        restartDb();
        TestUtil.testCall(db, "return custom.answer() as answer", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void registerSimpleStatementConcreteResultsFunctionUnnamedResultColumn() throws Exception {
        db.executeTransactionally("call apoc.custom.asFunction('answer','RETURN 42','long')");
        restartDb();
        TestUtil.testCall(db, "return custom.answer() as answer", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void registerParameterStatementFunction() throws Exception {
        db.executeTransactionally("call apoc.custom.asFunction('answer','RETURN $answer as answer','long')");
        restartDb();
        TestUtil.testCall(db, "return custom.answer({answer:42}) as answer", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void registerConcreteParameterAndReturnStatementFunction() throws Exception {
        db.executeTransactionally("call apoc.custom.asFunction('answer','RETURN $input as answer','long',[['input','number']])");
        restartDb();
        TestUtil.testCall(db, "return custom.answer(42) as answer", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void testAllParameterTypesFunction() throws Exception {
        db.executeTransactionally("call apoc.custom.asFunction('answer','RETURN [$int,$float,$string,$map,$`list int`,$bool,$date,$datetime,$point] as data','list of any'," +
                "[['int','int'],['float','float'],['string','string'],['map','map'],['list int','list int'],['bool','bool'],['date','date'],['datetime','datetime'],['point','point']], true)");
        restartDb();
        TestUtil.testCall(db, "return custom.answer(42,3.14,'foo',{a:1},[1],true,date(),datetime(),point({x:1,y:2})) as data", (row) -> assertEquals(9, ((List)row.get("data")).size()));
    }

    @Test
    public void testIssue1744() throws Exception {
        db.executeTransactionally("CREATE (:Area {name: 'foo'})-[:CURRENT]->(:VantagePoint {alpha: 'beta'})");
        db.executeTransactionally("CALL apoc.custom.asProcedure('vantagepoint_within_area',\n" +
            "  \"MATCH (start:Area {name: $areaName} )\n" +
            "    CALL apoc.path.expand(start,'CONTAINS>|<SEES|CURRENT','',0,100) YIELD path\n" +
            "    UNWIND nodes(path) as node\n" +
            "    WITH node\n" +
            "    WHERE node:VantagePoint\n" +
            "    RETURN DISTINCT node as resource\",\n" +
            "  'read',\n" +
            "  [['resource','NODE']],\n" +
            "  [['areaName', 'STRING']],\n" +
            "  \"Get vantage points within an area and all included areas\");");

        // function analogous to procedure
        db.executeTransactionally("CALL apoc.custom.asFunction('vantagepoint_within_area',\n" +
            "  \"MATCH (start:Area {name: $areaName} )\n" +
            "    CALL apoc.path.expand(start,'CONTAINS>|<SEES|CURRENT','',0,100) YIELD path\n" +
            "    UNWIND nodes(path) as node\n" +
            "    WITH node\n" +
            "    WHERE node:VantagePoint\n" +
            "    RETURN DISTINCT node as resource\",\n" +
            "  'read',\n" +
            "  [['areaName', 'STRING']]);");

        testCallIssue1744();
        restartDb();

        final String logFileContent = Files.readString(new File(FileUtils.getLogDirectory(), "debug.log").toPath());
        assertFalse(logFileContent.contains("Could not register function: custom.vantagepoint_within_area"));
        assertFalse(logFileContent.contains("Could not register procedure: custom.vantagepoint_within_area"));
        testCallIssue1744();
    }

    private void testCallIssue1744() {
        TestUtil.testCall(db, "CALL custom.vantagepoint_within_area('foo')", this::assertCallIssue1744);
        TestUtil.testCall(db, "RETURN custom.vantagepoint_within_area('foo') as resource", this::assertCallIssue1744);
    }

    private void assertCallIssue1744(Map<String, Object> row) {
        final Node resource = (Node) row.get("resource");
        assertEquals("VantagePoint", resource.getLabels().iterator().next().name());
        assertEquals("beta", resource.getProperty("alpha"));
    }
}
