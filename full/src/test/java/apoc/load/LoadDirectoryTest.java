package apoc.load;

import apoc.util.TestUtil;
import junit.framework.TestCase;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static apoc.ApocConfig.APOC_IMPORT_FILE_ALLOW__READ__FROM__FILESYSTEM;
import static apoc.ApocConfig.APOC_IMPORT_FILE_ENABLED;
import static apoc.ApocConfig.apocConfig;
import static apoc.util.TestUtil.getUrlFileName;
import static apoc.load.LoadDirectoryItem.DEFAULT_EVENT_TYPES;
import static apoc.util.TestUtil.testCall;
import static apoc.util.TestUtil.testCallEmpty;
import static apoc.util.TestUtil.testResult;
import static java.util.Collections.emptyMap;
import static org.eclipse.jetty.util.URIUtil.encodePath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.isOneOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.neo4j.test.assertion.Assert.assertEventually;

public class LoadDirectoryTest {

    @ClassRule
    public static TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static GraphDatabaseService db;
    private static File importFolder;

    private static final String IMPORT_DIR = "import";
    private static final String SUBFOLDER_1 = "sub1";
    private static final String INNER_SUBFOLDER = "innerSub1";
    private static final String SUBFOLDER_2 = "sub2";
    private static final String SUBFOLDER_3 = "sub3";
    
    private static final String CSV_1 = "TestCsv1.csv";
    private static final String CSV_2_FILENAME_WITH_SPACES = "Test Csv 2.csv";
    private static final String CSV_3 = "TestCsv3.csv";
    private static final String XLS_1 = "TestXls1.xls";
    private static final String JSON_1 = "TestJson1.json";
    private static final String JSON_SUBFOLDER_1 = "TestSubfolder1.json";
    private static final String CSV_SUBFOLDER_1 = "TestSubfolder.csv";
    
    private static final String FILE_PROTOCOL = "file://";
    
    private static final ArrayList<String> eventTypes = new ArrayList<>(DEFAULT_EVENT_TYPES);

    @BeforeClass
    public static void setUp() throws Exception {
        importFolder = new File(temporaryFolder.getRoot() + File.separator + IMPORT_DIR);
        DatabaseManagementService databaseManagementService = new TestDatabaseManagementServiceBuilder(importFolder).build();
        db = databaseManagementService.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME);

        TestUtil.registerProcedure(db, LoadDirectory.class, LoadCsv.class, LoadJson.class);
        apocConfig().setProperty(APOC_IMPORT_FILE_ALLOW__READ__FROM__FILESYSTEM, true);

        // create temp files and subfolder
        temporaryFolder.newFile("Foo.csv");
        temporaryFolder.newFile("Bar.csv");
        temporaryFolder.newFile("Baz.xls");
        temporaryFolder.newFolder(IMPORT_DIR + File.separator + SUBFOLDER_1);
        temporaryFolder.newFolder(IMPORT_DIR + File.separator + SUBFOLDER_2);
        temporaryFolder.newFolder(IMPORT_DIR + File.separator + SUBFOLDER_3);
        temporaryFolder.newFolder(IMPORT_DIR + File.separator + SUBFOLDER_1 + File.separator + INNER_SUBFOLDER);
        temporaryFolder.newFile(IMPORT_DIR + File.separator + CSV_1);
        temporaryFolder.newFile(IMPORT_DIR + File.separator + CSV_2_FILENAME_WITH_SPACES);
        temporaryFolder.newFile(IMPORT_DIR + File.separator + CSV_3);
        temporaryFolder.newFile(IMPORT_DIR + File.separator + XLS_1);
        temporaryFolder.newFile(IMPORT_DIR + File.separator + JSON_1);
        temporaryFolder.newFile(IMPORT_DIR + File.separator + SUBFOLDER_1 + File.separator + JSON_SUBFOLDER_1);
        temporaryFolder.newFile(IMPORT_DIR + File.separator + SUBFOLDER_1 + File.separator + CSV_SUBFOLDER_1);
    }

    @Before
    public void before() throws Exception {
        apocConfig().setProperty(APOC_IMPORT_FILE_ENABLED, true);
        apocConfig().setProperty(APOC_IMPORT_FILE_USE_NEO4J_CONFIG, true);
    }

    @Test
    public void testWithNullUrlDir() {
        TestUtil.testFail(db, "CALL apoc.load.directory('*', null, {recursive: false}) YIELD value RETURN value", IllegalArgumentException.class);
    }

    @After
    public void clearDB() {
        db.executeTransactionally("CALL apoc.load.directory.async.removeAll()");
        db.executeTransactionally("MATCH (n) DETACH DELETE n");
    }

    @Test(expected = QueryExecutionException.class)
    public void testAddListenerWithApocLoadDirectoryNotEnabled() {
        apocConfig().setProperty(APOC_IMPORT_FILE_ENABLED, false);
        try {
            db.executeTransactionally("CALL apoc.load.directory.async.add('test','CREATE (n:Test)', '*.csv', '', {}) YIELD name RETURN name");
        } catch (QueryExecutionException e) {
            Throwable except = ExceptionUtils.getRootCause(e);
            TestCase.assertTrue(except instanceof RuntimeException);
            assertEquals("Import from files not enabled, please set apoc.import.file.enabled=true in your apoc.conf", except.getMessage());
            throw e;
        }
    }

    @Test(expected = QueryExecutionException.class)
    public void testNotAllowSchemaOperation() {
        try {
            db.executeTransactionally("CALL apoc.load.directory.async.add('test','CREATE INDEX FOR (a:Test) ON (a.name)', '*.csv', '', {}) YIELD name RETURN name");
        } catch (QueryExecutionException e) {
            Throwable except = ExceptionUtils.getRootCause(e);
            TestCase.assertTrue(except instanceof RuntimeException);
            assertEquals("Supported query types for the operation are [READ_WRITE, WRITE]", except.getMessage());
            throw e;
        }
    }

    @Test(expected = QueryExecutionException.class)
    public void testInvalidQuery() {
        try {
            db.executeTransactionally("CALL apoc.load.directory.async.add('test','MATCH (n:Test) return invalid', '*.csv', '', {}) YIELD name RETURN name");
        } catch (QueryExecutionException e) {
            Throwable except = ExceptionUtils.getRootCause(e);
            TestCase.assertTrue(except instanceof RuntimeException);
            assertTrue(except.getMessage().contains("Variable `invalid` not defined"));
            throw e;
        }
    }

    @Test
    public void testAddFolderListenerWithLoadCsv() throws IOException {
        final String innerQuery = "CALL apoc.load.csv($filePath) YIELD list WITH list " +
                "CREATE (n:CsvToNode {content: list, fileName: $fileName, fileDirectory: $fileDirectory, listenEventType: $listenEventType})";

        testCall(db, "CALL apoc.load.directory.async.add('testRelativePath','" + innerQuery + "', '*.csv', '" + SUBFOLDER_1 + "')",
                r -> assertEquals("testRelativePath", r.get("name"))
        );

        final String fileName = "fileToMove.csv";
        File csvFile = temporaryFolder.newFile(IMPORT_DIR + File.separator + fileName);
        try (FileWriter fileWriter = new FileWriter(csvFile)) {
            final String content = "name,age\n" +
                    "Selma,8\n" +
                    "Rana,11\n" +
                    "Selina,18";
            fileWriter.write(content);
        }

        final String queryCount = "MATCH (n:CsvToNode {fileName: '" + fileName + "', fileDirectory: '" + SUBFOLDER_1 + "', listenEventType: 'CREATE', content: ['Selma', '8']}), " +
                "(m:CsvToNode {fileName: '" + fileName + "', fileDirectory: '" + SUBFOLDER_1 + "', listenEventType: 'CREATE', content: ['Rana', '11']}), " +
                "(l:CsvToNode {fileName: '" + fileName + "', fileDirectory: '" + SUBFOLDER_1 + "', listenEventType: 'CREATE', content: ['Selina', '18']}) " +
                "RETURN count(n) + count(m) + count(l) AS count";
        // we check that the load csv is not triggered yet
        testResult(db, queryCount, result -> assertEquals(0L, result.columnAs("count").next()));

        final File csvDestination = temporaryFolder.newFile(IMPORT_DIR + File.separator + SUBFOLDER_1 + File.separator + fileName);
        csvFile.renameTo(csvDestination);

        assertEventually(() -> db.executeTransactionally(queryCount,
                emptyMap(), (r) -> r.<Long>columnAs("count").next()),
                value -> value == 3L, 20L, TimeUnit.SECONDS);

        testCallEmpty(db, "CALL apoc.load.directory.async.removeAll", emptyMap());
        testCallEmpty(db, "CALL apoc.load.directory.async.list", emptyMap());

        FileUtils.forceDelete(csvDestination);
    }

    @Test
    public void testAddFolderListenerWithAllEventTypes() throws IOException {
        db.executeTransactionally("CALL apoc.load.directory.async.add('testAllEvents','CREATE (n:TestEntry)','*.csv') YIELD name RETURN name");

        final String name = "testAllEvents";
        assertIsRunning(name);

        final String queryCount = "MATCH (n:TestEntry) RETURN count(n) AS count";
        testResult(db, queryCount, result -> assertEquals(0L, result.columnAs("count").next()));

        final File fileCsv = temporaryFolder.newFile(IMPORT_DIR + File.separator + "newFile.csv");

        assertEventually(() -> db.executeTransactionally(queryCount,
                emptyMap(), (r) -> r.<Long>columnAs("count").next()),
                value -> value == 1L, 30L, TimeUnit.SECONDS);

        FileUtils.forceDelete(fileCsv);

        assertEventually(() -> db.executeTransactionally(queryCount,
                emptyMap(), (r) -> r.<Long>columnAs("count").next()),
                value -> value == 2L, 30L, TimeUnit.SECONDS);

        final File fileCsv2 = temporaryFolder.newFile(IMPORT_DIR + File.separator + "newFileTwo.csv");

        assertEventually(() -> db.executeTransactionally(queryCount,
                emptyMap(), (r) -> r.<Long>columnAs("count").next()),
                value -> value == 3L, 30L, TimeUnit.SECONDS);

        testCallEmpty(db, "CALL apoc.load.directory.async.remove('testAllEvents')", emptyMap());

        FileUtils.forceDelete(fileCsv2);

    }

    private void assertIsRunning(String name) {
        assertEventually(() -> db.executeTransactionally("CALL apoc.load.directory.async.list() YIELD name, status " +
                        "WHERE name = $name " +
                        "RETURN *",
                Map.of("name", name), (r) -> {
                    Map<String, Object> result = r.next();
                    return LoadDirectoryItem.Status.RUNNING.name().equals(result.get("status"));
                }),
                value -> value, 30L, TimeUnit.SECONDS);
    }

    @Test
    public void testAddFolderListenerWithCreateEventType() throws InterruptedException, IOException {
        db.executeTransactionally("CALL apoc.load.directory.async.add('testSpecific','CREATE (n:TestEntry {fileName: $fileName})', '*.csv', '', {listenEventType: ['CREATE']}) YIELD name RETURN name");

        assertIsRunning("testSpecific");
        final String queryCount = "MATCH (n:TestEntry {fileName: $fileName}) RETURN count(n) AS count";
        final String fileOne = "newFile.csv";
        testResult(db, queryCount, Map.of("fileName", fileOne), result -> assertEquals(0L, result.columnAs("count").next()));

        final File file = temporaryFolder.newFile(IMPORT_DIR + File.separator + fileOne);

        assertEventually(() -> db.executeTransactionally(queryCount,
                Map.of("fileName", fileOne), (r) -> r.<Long>columnAs("count").next()),
                value -> value == 1L, 30L, TimeUnit.SECONDS);

        FileUtils.forceDelete(file);
        // the DELETE event is not triggered
        Thread.sleep(1000);
        testResult(db, queryCount, Map.of("fileName", fileOne), result -> assertEquals(1L, result.columnAs("count").next()));

        final String fileTwo = "newFileTwo.csv";
        final File file2 = temporaryFolder.newFile(IMPORT_DIR + File.separator + fileTwo);

        assertEventually(() -> db.executeTransactionally(queryCount,
                Map.of("fileName", fileTwo), (r) -> r.<Long>columnAs("count").next()),
                value -> value == 1L, 30L, TimeUnit.SECONDS);

        testCallEmpty(db, "CALL apoc.load.directory.async.remove('testSpecific')", emptyMap());
        FileUtils.forceDelete(file2);
    }

    @Test
    public void testRemoveListenerWithNotExistingName() {
        try {
            testResult(db, "CALL apoc.load.directory.async.remove('notExisting') YIELD name, pattern, cypher RETURN name, pattern, cypher", result -> {
            });
        } catch (RuntimeException e) {
            String expectedMessage = "Listener with name: notExisting doesn't exists";
            assertEquals(expectedMessage, e.getMessage());
            throw e;
        }
    }

    @Test
    public void testFolderListenerList() {
        db.executeTransactionally("CALL apoc.load.directory.async.add('testOne','CREATE (n:Test)','*.csv','', {}) YIELD name RETURN name");
        db.executeTransactionally("CALL apoc.load.directory.async.add('testTwo','CREATE (n:Test)', '*.json', '', {}) YIELD name RETURN name");

        assertIsRunning("testOne");
        assertIsRunning("testTwo");
        final Map<String, Object> defaultConfig = Map.of("listenEventType", eventTypes, "interval", 1000L);
        testResult(db, "CALL apoc.load.directory.async.list()", result -> {
            Map<String, Object> mapTestOne = result.next();
            assertThat(mapTestOne.get("name"), isOneOf("testOne", "testTwo"));
            assertThat(mapTestOne.get("pattern"), isOneOf("*.csv", "*.json"));
            assertEquals(encodePath(importFolder.getPath()), mapTestOne.get("urlDir"));
            assertEquals("CREATE (n:Test)", mapTestOne.get("cypher"));
            assertEquals(defaultConfig, mapTestOne.get("config"));
            assertEquals(LoadDirectoryItem.Status.RUNNING.name(), mapTestOne.get("status"));
            assertEquals(StringUtils.EMPTY, mapTestOne.get("error"));
            Map<String, Object> mapTestTwo = result.next();
            assertThat(mapTestTwo.get("name"), isOneOf("testOne", "testTwo"));
            assertThat(mapTestTwo.get("pattern"), isOneOf("*.csv", "*.json"));
            assertEquals(encodePath(importFolder.getPath()), mapTestTwo.get("urlDir"));
            assertEquals("CREATE (n:Test)", mapTestTwo.get("cypher"));
            assertEquals(defaultConfig, mapTestTwo.get("config"));
            assertEquals(LoadDirectoryItem.Status.RUNNING.name(), mapTestTwo.get("status"));
            assertEquals(StringUtils.EMPTY, mapTestTwo.get("error"));
            assertFalse(result.hasNext());
        });

        testCall(db, "CALL apoc.load.directory.async.remove('testOne')", result -> {
            // remains 2nd listener
            assertEquals("testTwo", result.get("name"));
            assertEquals("*.json", result.get("pattern"));
            assertEquals(encodePath(importFolder.getPath()), result.get("urlDir"));
            assertEquals("CREATE (n:Test)", result.get("cypher"));
            assertEquals(defaultConfig, result.get("config"));
            assertEquals(LoadDirectoryItem.Status.RUNNING.name(), result.get("status"));
            assertEquals(StringUtils.EMPTY, result.get("error"));
        });

        testCallEmpty(db, "CALL apoc.load.directory.async.remove('testTwo') YIELD name RETURN name", emptyMap());
    }

    @Test
    public void testRemoveFolderListener() {
        db.executeTransactionally("CALL apoc.load.directory.async.add('test','CREATE (n:Test)','*.csv', '', {}) YIELD name RETURN name");
        assertIsRunning("test");
        testResult(db, "CALL apoc.load.directory.async.list() YIELD name RETURN name", result -> {
                    List<Map<String, Object>> rows = Iterators.asList(result.columnAs("name"));
                    assertEquals(1, rows.size());
                }
        );

        testCallEmpty(db, "CALL apoc.load.directory.async.remove('test')", emptyMap());
        testCallEmpty(db, "CALL apoc.load.directory.async.list", emptyMap());
    }

    @Test
    public void testFolderListenerNotMatchingPattern() throws IOException, InterruptedException {
        final Map<String, Object> defaultConfig = Map.of("listenEventType", eventTypes, "interval", 1000L);
        testCall(db, "CALL apoc.load.directory.async.add('test','CREATE (n:Test {file: $fileName})','*.json')", result -> {
            assertEquals("test", result.get("name"));
            assertEquals("*.json", result.get("pattern"));
            assertEquals(encodePath(importFolder.getPath()), result.get("urlDir"));
            assertEquals("CREATE (n:Test {file: $fileName})", result.get("cypher"));
            assertEquals(defaultConfig, result.get("config"));
            assertEquals(LoadDirectoryItem.Status.CREATED.name(), result.get("status"));
            assertEquals(StringUtils.EMPTY, result.get("error"));
        });

        db.executeTransactionally("CALL apoc.load.directory.async.add('test','CREATE (n:Test {file: $fileName})','*.json') YIELD name RETURN name");
        Thread.sleep(1000);

        final String queryCount = "MATCH (n:Test {file: $file}) RETURN count(n) AS count";
        testResult(db, queryCount, Map.of("file", "newFile.csv"), result -> assertEquals(0L, result.columnAs("count").next()));

        final File fileCsv = temporaryFolder.newFile(IMPORT_DIR + File.separator + "newFile.csv");

        Thread.sleep(1000);
        testResult(db, queryCount, Map.of("file", "newFile.csv"), result -> assertEquals(0L, result.columnAs("count").next()));

        testCallEmpty(db, "CALL apoc.load.directory.async.remove('test')", emptyMap());

        FileUtils.forceDelete(fileCsv);
    }

    @Test
    public void testFolderListenerWithSameName() throws IOException, InterruptedException {
        testCall(db, "CALL apoc.load.directory.async.add('sameName','CREATE (n:Test {event: $listenEventType, url: $fileDirectory})','*csv','', {interval: 30000}) YIELD name RETURN name",
                r -> assertEquals("sameName", r.get("name"))
        );

        testCall(db, "CALL apoc.load.directory.async.add('sameName','CREATE (n:TestTwo {event: $listenEventType, url: $fileDirectory, file: $fileName })','*csv','', {}) YIELD name RETURN name",
                r -> assertEquals("sameName", r.get("name"))
        );
        Thread.sleep(1000);
        assertIsRunning("sameName");

        final String queryCount = "MATCH (n:TestTwo {event: $event, url: $url, file: $file }) RETURN count(n) AS count";
        final Map<String, Object> queryParams = Map.of("file", "newFile.csv", "url", temporaryFolder.getRoot().getPath() + File.separator + IMPORT_DIR, "event", "CREATE");

        testResult(db, queryCount, queryParams, result -> assertEquals(0L, result.columnAs("count").next()));

        final File file = temporaryFolder.newFile(IMPORT_DIR + File.separator + "newFile.csv");

        assertEventually(() -> db.executeTransactionally(queryCount, queryParams, (r) -> r.<Long>columnAs("count").next()),
                value -> value == 1L, 20L, TimeUnit.SECONDS);

        testResult(db, "MATCH (n:Test) RETURN count(n) AS count", result -> assertEquals(0L, result.columnAs("count").next()));

        testCallEmpty(db, "CALL apoc.load.directory.async.remove('sameName')", emptyMap());

        final File file2 = temporaryFolder.newFile(IMPORT_DIR + File.separator + "newFileTwo.csv");
        Thread.sleep(1000);

        testResult(db, "MATCH (n:TestTwo) RETURN count(n) AS count", result -> assertEquals(1L, result.columnAs("count").next()));
        testResult(db, "MATCH (n:Test) RETURN count(n) AS count", result -> assertEquals(0L, result.columnAs("count").next()));

        FileUtils.forceDelete(file);
        FileUtils.forceDelete(file2);
    }

    @Test
    public void testAddFolderWithOnlyNameAndCypher() throws InterruptedException, IOException {
        testResult(db, "CALL apoc.load.directory.async.add('testOnlyNameAndCypher','CREATE (n:TestOnly {prop: $filePath})')",
                result -> {
                    Map<String, Object> row = result.next();
                    assertEquals("testOnlyNameAndCypher", row.get("name"));
                    assertFalse(result.hasNext());
                });
        Thread.sleep(1000);
        assertIsRunning("testOnlyNameAndCypher");

        final String fileExe = "newFile.exe";
        final String relativePath = IMPORT_DIR + File.separator + fileExe;
        final String queryCount = "MATCH (n:TestOnly {prop: $file}) RETURN count(n) AS count";

        testResult(db, queryCount, Map.of("file", fileExe), result -> assertEquals(0L, result.columnAs("count").next()));

        final File file = temporaryFolder.newFile(relativePath);
        assertEventually(() -> db.executeTransactionally(queryCount,
                Map.of("file", fileExe), (r) -> r.<Long>columnAs("count").next()),
                value -> value == 1L,20L, TimeUnit.SECONDS);

        testCallEmpty(db, "CALL apoc.load.directory.async.removeAll", emptyMap());
        FileUtils.forceDelete(file);
    }

    @Test
    public void testAddFolderWithRelativePath() throws InterruptedException, IOException {
        testCall(db, "CALL apoc.load.directory.async.add('testRelativePath','CREATE (n:TestRelativePath)', '*', '" + SUBFOLDER_1 + "')",
                r -> assertEquals("testRelativePath", r.get("name"))
        );

        assertIsRunning("testRelativePath");

        final String queryCount = "MATCH (n:TestRelativePath) RETURN count(n) AS count";
        testResult(db, queryCount, result -> assertEquals(0L, result.columnAs("count").next()));


        final File file = temporaryFolder.newFile(IMPORT_DIR + File.separator + SUBFOLDER_1 + File.separator + "newFile.kt");

        assertEventually(() -> db.executeTransactionally(queryCount,
                emptyMap(), (r) -> r.<Long>columnAs("count").next()),
                value -> value == 1L, 20L, TimeUnit.SECONDS);

        testCallEmpty(db, "CALL apoc.load.directory.async.removeAll", emptyMap());
        FileUtils.forceDelete(file);
    }

    @Test
    public void testAddFolderMultipleListener() throws IOException {
        db.executeTransactionally("CALL apoc.load.directory.async.add('testOne','CREATE (n:TestOne)', '*.csv', '', {}) YIELD name RETURN name");
        assertIsRunning("testOne");
        db.executeTransactionally("CALL apoc.load.directory.async.add('testTwo','CREATE (n:TestTwo)', '*.json', '', {}) YIELD name RETURN name");
        assertIsRunning("testTwo");

        final String queryCountLabelOne = "MATCH (n:TestOne) RETURN count(n) AS count";
        final String queryCountLabelTwo = "MATCH (n:TestTwo) RETURN count(n) AS count";
        testResult(db, queryCountLabelOne, result -> assertEquals(0L, result.columnAs("count").next()));
        testResult(db, queryCountLabelTwo, result -> assertEquals(0L, result.columnAs("count").next()));

        final File file = temporaryFolder.newFile(IMPORT_DIR + File.separator + "newOtherFile.csv");

        assertEventually(() -> db.executeTransactionally(queryCountLabelOne,
                emptyMap(), (r) -> r.<Long>columnAs("count").next()),
                value -> value == 1L, 30L, TimeUnit.SECONDS);

        testCall(db, "CALL apoc.load.directory.async.remove('testOne')", result -> {
            // remain 1st listener
            assertEquals("testTwo", result.get("name"));
            assertEquals("*.json", result.get("pattern"));
            assertEquals(encodePath(importFolder.getPath()), result.get("urlDir"));
            assertEquals("CREATE (n:TestTwo)", result.get("cypher"));
            assertEquals("CREATE (n:TestTwo)", result.get("cypher"));
        });

        final File file2 = temporaryFolder.newFile(IMPORT_DIR + File.separator + "newOtherFile.json");

        assertEventually(() -> db.executeTransactionally(queryCountLabelTwo,
                emptyMap(), (r) -> r.<Long>columnAs("count").next()),
                value -> value == 1L, 30L, TimeUnit.SECONDS);

        testResult(db, queryCountLabelOne, result -> assertEquals(1L, result.columnAs("count").next()));

        testCallEmpty(db, "CALL apoc.load.directory.async.remove('testTwo')", emptyMap());

        FileUtils.forceDelete(file);
        FileUtils.forceDelete(file2);
    }

    @Test
    public void testAddLoadFolderWithFileProtocol() throws InterruptedException, IOException {
        apocConfig().setProperty(APOC_IMPORT_FILE_USE_NEO4J_CONFIG, false);
        File rootTempFolder = temporaryFolder.getRoot();
        String folderAsExternalUrl = FILE_PROTOCOL + rootTempFolder;

        testResult(db, "CALL apoc.load.directory.async.add('testExternalUrl','CREATE (n:TestExternalUrl {prop: $fileName})','*.csv','" + folderAsExternalUrl + "', {}) YIELD name RETURN name",
                result -> {
                    Map<String, Object> row = result.next();
                    assertEquals("testExternalUrl", row.get("name"));
                    assertFalse(result.hasNext());
                });
        Thread.sleep(1000);
        assertIsRunning("testExternalUrl");
        final String queryCount = "MATCH (n:TestExternalUrl {prop: 'newFile.csv'}) RETURN count(n) AS count";
        testResult(db, queryCount, result ->assertEquals(0L, result.columnAs("count").next()));

        final File file = temporaryFolder.newFile("newFile.csv");

        assertEventually(() -> db.executeTransactionally(queryCount,
                emptyMap(), (r) -> r.<Long>columnAs("count").next()),
                value -> value == 1L, 20L, TimeUnit.SECONDS);

        try (Transaction tx = db.beginTx()) {
            assertEquals(1, Iterators.count(tx.findNodes(Label.label("TestExternalUrl"))));
            tx.commit();
        }

        FileUtils.forceDelete(file);
        testCallEmpty(db, "CALL apoc.load.directory.async.removeAll", emptyMap());
    }

    @Test
    public void testListenerItemWithError() throws Exception, IOException {
        final Map<String, Object> defaultConfig = Map.of("listenEventType", eventTypes, "interval", 1000L);

        db.executeTransactionally("CALL apoc.load.directory.async.add('notExistent', 'CREATE (n:Node)', '*', 'pathNotExistent')");

        assertEventually(() -> db.executeTransactionally("CALL apoc.load.directory.async.list()",
                emptyMap(), (r) -> {
                    Map<String, Object> result = r.next();
                    return "notExistent".equals(result.get("name")) &&
                        "*".equals(result.get("pattern")) &&
                        "CREATE (n:Node)".equals(result.get("cypher")) &&
                        defaultConfig.equals(result.get("config")) &&
                        LoadDirectoryItem.Status.ERROR.name().equals(result.get("status")) &&
                        ((String) result.get("error")).contains("java.nio.file.NoSuchFileException");
                }),
                value -> value, 20L, TimeUnit.SECONDS);

        testCallEmpty(db, "CALL apoc.load.directory.async.remove('notExistent')", emptyMap());
        testCallEmpty(db, "CALL apoc.load.directory.async.list", emptyMap());
    }

    @Test
    public void testWithSubfolder() {
        testResult(db, "CALL apoc.load.directory('*', '" + SUBFOLDER_1 + "', {recursive: false}) YIELD value RETURN value", result -> {
                    List<Map<String, Object>> rows = Iterators.asList(result.columnAs("value"));
                    assertTrue(rows.contains(SUBFOLDER_1 + File.separator + JSON_SUBFOLDER_1));
                    assertTrue(rows.contains(SUBFOLDER_1 + File.separator + CSV_SUBFOLDER_1));
                    assertEquals(2, rows.size());
                }
        );
    }

    @Test
    public void testWithFileProtocolAndRecursiveFalse() {
        apocConfig().setProperty(APOC_IMPORT_FILE_USE_NEO4J_CONFIG, false);
        File rootTempFolder = temporaryFolder.getRoot();
        String folderAsExternalUrl = FILE_PROTOCOL + rootTempFolder;
        testResult(db, "CALL apoc.load.directory('*', '" + folderAsExternalUrl + "', {recursive: false}) YIELD value RETURN value", result -> {
                    List<Map<String, Object>> rows = Iterators.asList(result.columnAs("value"));
                    assertTrue(rows.contains(rootTempFolder + File.separator + "Foo.csv"));
                    assertTrue(rows.contains(rootTempFolder + File.separator + "Bar.csv"));
                    assertTrue(rows.contains(rootTempFolder + File.separator + "Baz.xls"));
                    assertEquals(3, rows.size());
                }
        );
    }

    @Test
    public void testWithFileProtocolFilterXlsAndRecursiveTrue() {
        apocConfig().setProperty(APOC_IMPORT_FILE_USE_NEO4J_CONFIG, false);
        File rootTempFolder = temporaryFolder.getRoot();
        String folderAsExternalUrl = FILE_PROTOCOL + rootTempFolder;
        testResult(db, "CALL apoc.load.directory('*.xls', '" + folderAsExternalUrl + "', {recursive: true}) YIELD value RETURN value", result -> {
                    List<Map<String, Object>> rows = Iterators.asList(result.columnAs("value"));
                    assertTrue(rows.contains(rootTempFolder + File.separator + "Baz.xls"));
                    assertTrue(rows.contains(rootTempFolder + File.separator + IMPORT_DIR + File.separator + XLS_1));
                    assertEquals(2, rows.size());
                }
        );
    }

    @Test
    public void testWithFilterAllRecursiveFalse() {
        testResult(db, "CALL apoc.load.directory('*', '', {recursive: false}) YIELD value RETURN value", result -> {
                    List<Map<String, Object>> rows = Iterators.asList(result.columnAs("value"));
                    assertTrue(rows.contains(CSV_1));
                    assertTrue(rows.contains(CSV_2_FILENAME_WITH_SPACES));
                    assertTrue(rows.contains(CSV_3));
                    assertTrue(rows.contains(JSON_1));
                    assertTrue(rows.contains(XLS_1));
                    assertEquals(5, rows.size());
                }
        );
    }

    @Test
    public void testWithFilterJsonRecursiveTrue() {
        apocConfig().setProperty(APOC_IMPORT_FILE_USE_NEO4J_CONFIG, true);
        testResult(db, "CALL apoc.load.directory('*.json', '', {recursive: true}) YIELD value RETURN value", result -> {
                    List<Map<String, Object>> rows = Iterators.asList(result.columnAs("value"));
                    assertTrue(rows.contains(JSON_1));
                    assertTrue(rows.contains(SUBFOLDER_1 + File.separator + JSON_SUBFOLDER_1));
                    assertEquals(2, rows.size());
                }
        );
    }

    @Test
    public void testWithFilterCsv() {
        apocConfig().setProperty(APOC_IMPORT_FILE_USE_NEO4J_CONFIG, true);
        testResult(db, "CALL apoc.load.directory('*.csv') YIELD value RETURN value", result -> {
                    List<Map<String, Object>> rows = Iterators.asList(result.columnAs("value"));
                    assertTrue(rows.contains(CSV_1));
                    assertTrue(rows.contains(CSV_2_FILENAME_WITH_SPACES));
                    assertTrue(rows.contains(CSV_3));
                    assertTrue(rows.contains(SUBFOLDER_1 + File.separator + CSV_SUBFOLDER_1));
                    assertEquals(4, rows.size());
                }
        );
    }

    @Test
    public void testLoadDirectoryConcatenatedWithLoadCsv() throws URISyntaxException {
        apocConfig().setProperty(APOC_IMPORT_FILE_USE_NEO4J_CONFIG, false);
        File rootTempFolder = Paths.get(getUrlFileName("test.csv").toURI()).getParent().toFile();
        String folderAsExternalUrl = "file://" + rootTempFolder;
        testResult(db, "CALL apoc.load.directory('*.csv', '" + folderAsExternalUrl + "') YIELD value " +
                "WITH value as url WHERE url ENDS WITH 'test.csv' OR url ENDS WITH 'test-pipe-column.csv' WITH url ORDER BY url DESC CALL apoc.load.csv(url, {results:['map']}) YIELD map RETURN map", result -> {
                    Map<String, Object> firstRowFirstFile = (Map<String, Object>) result.next().get("map");
                    assertEquals(Map.of("name", "Selma", "age", "8"), firstRowFirstFile);
                    Map<String, Object> secondRowFirstFile = (Map<String, Object>) result.next().get("map");
                    assertEquals(Map.of("name", "Rana", "age", "11"), secondRowFirstFile);
                    Map<String, Object> thirdRowFirstFile = (Map<String, Object>) result.next().get("map");
                    assertEquals(Map.of("name", "Selina", "age", "18"), thirdRowFirstFile);
                    Map<String, Object> firstRowSecondFile = (Map<String, Object>) result.next().get("map");
                    assertEquals(Map.of("name", "Selma", "beverage", "Soda"), firstRowSecondFile);
                    Map<String, Object> secondRowSecondFile = (Map<String, Object>) result.next().get("map");
                    assertEquals(Map.of("name", "Rana", "beverage", "Tea|Milk"), secondRowSecondFile);
                    Map<String, Object> thirdRowSecondFile = (Map<String, Object>) result.next().get("map");
                    assertEquals(Map.of("name", "Selina", "beverage", "Cola"), thirdRowSecondFile);
                    assertFalse(result.hasNext());
                }
        );
    }
}
