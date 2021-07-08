package apoc.load;

import apoc.util.JsonUtil;
import apoc.util.TestUtil;
import apoc.util.Util;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.junit.*;
import org.mockserver.client.server.MockServerClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.Header;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static apoc.ApocConfig.*;
import static apoc.convert.ConvertJsonTest.EXPECTED_AS_PATH_LIST;
import static apoc.convert.ConvertJsonTest.EXPECTED_PATH;
import static apoc.convert.ConvertJsonTest.EXPECTED_PATH_WITH_NULLS;
import static apoc.util.MapUtil.map;
import static apoc.util.TestUtil.testCall;
import static apoc.util.TestUtil.testResult;
import static java.util.Arrays.asList;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.matchers.Times.exactly;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class LoadJsonTest {

    private static ClientAndServer mockServer;

    @BeforeClass
    public static void startServer() {
        mockServer = startClientAndServer(1080);
    }

    @AfterClass
    public static void stopServer() {
        mockServer.stop();
    }

    @Rule
    public DbmsRule db = new ImpermanentDbmsRule();
//            .withSetting(ApocSettings.apoc_import_file_enabled, true)
//            .withSetting(ApocSettings.apoc_import_file_use__neo4j__config, false);

	@Before public void setUp() throws Exception {
	    apocConfig().setProperty(APOC_IMPORT_FILE_ENABLED, true);
	    apocConfig().setProperty(APOC_IMPORT_FILE_USE_NEO4J_CONFIG, false);
	    apocConfig().setProperty("apoc.json.zip.url", "https://github.com/neo4j-contrib/neo4j-apoc-procedures/blob/3.4/src/test/resources/testload.zip?raw=true!person.json");
	    apocConfig().setProperty("apoc.json.simpleJson.url", ClassLoader.getSystemResource("map.json").toString());
        TestUtil.registerProcedure(db, LoadJson.class);
    }

    @Test public void testLoadJson() throws Exception {
		URL url = ClassLoader.getSystemResource("map.json");
		testCall(db, "CALL apoc.load.json($url)",map("url",url.toString()), // 'file:map.json' YIELD value RETURN value
                (row) -> {
                    assertEquals(map("foo",asList(1L,2L,3L)), row.get("value"));
                });
    }

    @Test public void testLoadMultiJson() throws Exception {
		URL url = ClassLoader.getSystemResource("multi.json");
		testResult(db, "CALL apoc.load.json($url)",map("url",url.toString()), // 'file:map.json' YIELD value RETURN value
                (result) -> {
                    Map<String, Object> row = result.next();
                    assertEquals(map("foo",asList(1L,2L,3L)), row.get("value"));
                    row = result.next();
                    assertEquals(map("bar",asList(4L,5L,6L)), row.get("value"));
                    assertFalse(result.hasNext());
                });
    }
    @Test public void testLoadMultiJsonPaths() throws Exception {
		URL url = ClassLoader.getSystemResource("multi.json");
		testResult(db, "CALL apoc.load.json($url,'$')",map("url",url.toString()), // 'file:map.json' YIELD value RETURN value
                (result) -> {
                    Map<String, Object> row = result.next();
                    assertEquals(map("foo",asList(1L,2L,3L)), row.get("value"));
                    row = result.next();
                    assertEquals(map("bar",asList(4L,5L,6L)), row.get("value"));
                    assertFalse(result.hasNext());
                });
    }
    @Test public void testLoadJsonPath() throws Exception {
		URL url = ClassLoader.getSystemResource("map.json");
		testCall(db, "CALL apoc.load.json($url,'$.foo')",map("url",url.toString()), // 'file:map.json' YIELD value RETURN value
                (row) -> {
                    assertEquals(map("result",asList(1L,2L,3L)), row.get("value"));
                });
    }
    @Test public void testLoadJsonPathRoot() throws Exception {
		URL url = ClassLoader.getSystemResource("map.json");
		testCall(db, "CALL apoc.load.json($url,'$')",map("url",url.toString()), // 'file:map.json' YIELD value RETURN value
                (row) -> {
                    assertEquals(map("foo",asList(1L,2L,3L)), row.get("value"));
                });
    }
    
    @Test
    public void testLoadJsonWithPathOptions() throws Exception {
        URL url = ClassLoader.getSystemResource("columns.json");

        // -- load.json
        testResult(db, "CALL apoc.load.json($url, '$..columns')", map("url", url.toString()),
                (res) -> assertEquals(EXPECTED_PATH_WITH_NULLS, Iterators.asList(res.columnAs("value"))));

        testResult(db, "CALL apoc.load.json($url, '$..columns', $config)", 
                map("url", url.toString(), "config", map("pathOptions", Collections.emptyList())),
                (res) -> assertEquals(EXPECTED_PATH, Iterators.asList(res.columnAs("value"))));

        testResult(db, "CALL apoc.load.json($url, '$..columns', $config)", 
                map("url", url.toString(), "config", map("pathOptions", List.of("AS_PATH_LIST"))),
                (res) -> assertEquals(List.of(Map.of("result", EXPECTED_AS_PATH_LIST)), Iterators.asList(res.columnAs("value"))));

        // -- load.jsonArray
        testResult(db, "CALL apoc.load.jsonArray($url, '$..columns')", map("url", url.toString()),
                (res) -> assertEquals(EXPECTED_PATH_WITH_NULLS, Iterators.asList(res.columnAs("value"))));
        
        testResult(db, "CALL apoc.load.jsonArray($url, '$..columns', $config)",
                map("url", url.toString(), "config", map("pathOptions", Collections.emptyList())),
                (res) -> assertEquals(EXPECTED_PATH, Iterators.asList(res.columnAs("value"))));
        
        testResult(db, "CALL apoc.load.jsonArray($url, '$..columns', $config)",
                map("url", url.toString(), "config", map("pathOptions", List.of("AS_PATH_LIST"))),
                (res) -> assertEquals(List.of(EXPECTED_AS_PATH_LIST), Iterators.asList(res.columnAs("value"))));
    }
    
    @Test public void testLoadJsonArrayPath() throws Exception {
		URL url = ClassLoader.getSystemResource("map.json");
		testCall(db, "CALL apoc.load.jsonArray($url,'$.foo')",map("url",url.toString()), // 'file:map.json' YIELD value RETURN value
                (row) -> {
                    assertEquals(asList(1L,2L,3L), row.get("value"));
                });
    }
    @Test public void testLoadJsonArrayPathRoot() throws Exception {
		URL url = ClassLoader.getSystemResource("map.json");
		testCall(db, "CALL apoc.load.jsonArray($url,'$')",map("url",url.toString()), // 'file:map.json' YIELD value RETURN value
                (row) -> {
                    assertEquals(map("foo",asList(1L,2L,3L)), row.get("value"));
                });
    }
    @Test @Ignore public void testLoadJsonGraphCommons() throws Exception {
		String url = "https://graphcommons.com/graphs/8da5327d-7829-4dfe-b60b-4c0bda956b2a.json";
		testCall(db, "CALL apoc.load.json($url)",map("url", url), // 'file:map.json' YIELD value RETURN value
                (row) -> {
                    Map value = (Map)row.get("value");
                    assertEquals(true, value.containsKey("users"));
                    assertEquals(true, value.containsKey("nodes"));
                });
    }

    @Test public void testLoadJsonStackOverflow() throws Exception {
        String url = "https://api.stackexchange.com/2.2/questions?pagesize=10&order=desc&sort=creation&tagged=neo4j&site=stackoverflow&filter=!5-i6Zw8Y)4W7vpy91PMYsKM-k9yzEsSC1_Uxlf";
        testCall(db, "CALL apoc.load.json($url)",map("url", url), // 'file:map.json' YIELD value RETURN value
                (row) -> {
                    Map<String, Object> value = (Map<String, Object>) row.get("value");
                    assertFalse(value.isEmpty());
                    List<Map<String, Object>> items = (List<Map<String, Object>>) value.get("items");
                    assertEquals(10, items.size());
                });
    }


    @Test public void testLoadJsonNoFailOnError() throws Exception {
        String url = "file.json";
        testResult(db, "CALL apoc.load.json($url,null, {failOnError:false})",map("url", url), // 'file:map.json' YIELD value RETURN value
                (row) -> {
                    assertFalse(row.hasNext());
                });
    }

    @Test public void testLoadJsonZip() throws Exception {
        URL url = ClassLoader.getSystemResource("testload.zip");
        testCall(db, "CALL apoc.load.json($url)",map("url",url.toString()+"!person.json"),
                (row) -> {
                    Map<String,Object> r = (Map<String, Object>) row.get("value");
                    assertEquals("Michael", r.get("name"));
                    assertEquals(41L, r.get("age"));
                    assertEquals(asList("Selina", "Rana", "Selma"), r.get("children"));
                });
    }

    @Test public void testLoadJsonTar() throws Exception {
        URL url = ClassLoader.getSystemResource("testload.tar");
        testCall(db, "CALL apoc.load.json($url)",map("url",url.toString()+"!person.json"),
                (row) -> {
                    Map<String,Object> r = (Map<String, Object>) row.get("value");
                    assertEquals("Michael", r.get("name"));
                    assertEquals(41L, r.get("age"));
                    assertEquals(asList("Selina", "Rana", "Selma"), r.get("children"));
                });
    }

    @Test public void testLoadJsonTarGz() throws Exception {
        URL url = ClassLoader.getSystemResource("testload.tar.gz");
        testCall(db, "CALL apoc.load.json($url)",map("url",url.toString()+"!person.json"),
                (row) -> {
                    Map<String,Object> r = (Map<String, Object>) row.get("value");
                    assertEquals("Michael", r.get("name"));
                    assertEquals(41L, r.get("age"));
                    assertEquals(asList("Selina", "Rana", "Selma"), r.get("children"));
                });
    }

    @Test public void testLoadJsonTgz() throws Exception {
        URL url = ClassLoader.getSystemResource("testload.tgz");
        testCall(db, "CALL apoc.load.json($url)",map("url",url.toString()+"!person.json"),
                (row) -> {
                    Map<String,Object> r = (Map<String, Object>) row.get("value");
                    assertEquals("Michael", r.get("name"));
                    assertEquals(41L, r.get("age"));
                    assertEquals(asList("Selina", "Rana", "Selma"), r.get("children"));
                });
    }

    @Test public void testLoadJsonZipByUrl() throws Exception {
        URL url = new URL("https://github.com/neo4j-contrib/neo4j-apoc-procedures/blob/3.4/src/test/resources/testload.zip?raw=true");
        testCall(db, "CALL apoc.load.json($url)",map("url",url.toString()+"!person.json"),
                (row) -> {
                    Map<String,Object> r = (Map<String, Object>) row.get("value");
                    assertEquals("Michael", r.get("name"));
                    assertEquals(41L, r.get("age"));
                    assertEquals(asList("Selina", "Rana", "Selma"), r.get("children"));
                });
    }

    @Test public void testLoadJsonTarByUrl() throws Exception {
        URL url = new URL("https://github.com/neo4j-contrib/neo4j-apoc-procedures/blob/3.4/src/test/resources/testload.tar?raw=true");
        testCall(db, "CALL apoc.load.json($url)",map("url",url.toString()+"!person.json"),
                (row) -> {
                    Map<String,Object> r = (Map<String, Object>) row.get("value");
                    assertEquals("Michael", r.get("name"));
                    assertEquals(41L, r.get("age"));
                    assertEquals(asList("Selina", "Rana", "Selma"), r.get("children"));
                });
    }

    @Test public void testLoadJsonTarGzByUrl() throws Exception {
        URL url = new URL("https://github.com/neo4j-contrib/neo4j-apoc-procedures/blob/3.4/src/test/resources/testload.tar.gz?raw=true");
        testCall(db, "CALL apoc.load.json($url)",map("url",url.toString()+"!person.json"),
                (row) -> {
                    Map<String,Object> r = (Map<String, Object>) row.get("value");
                    assertEquals("Michael", r.get("name"));
                    assertEquals(41L, r.get("age"));
                    assertEquals(asList("Selina", "Rana", "Selma"), r.get("children"));
                });
    }

    @Test public void testLoadJsonTgzByUrl() throws Exception {
        URL url = new URL("https://github.com/neo4j-contrib/neo4j-apoc-procedures/blob/3.4/src/test/resources/testload.tgz?raw=true");
        testCall(db, "CALL apoc.load.json($url)",map("url",url.toString()+"!person.json"),
                (row) -> {
                    Map<String,Object> r = (Map<String, Object>) row.get("value");
                    assertEquals("Michael", r.get("name"));
                    assertEquals(41L, r.get("age"));
                    assertEquals(asList("Selina", "Rana", "Selma"), r.get("children"));
                });
    }

    @Test public void testLoadJsonZipByUrlInConfigFile() throws Exception {
        testCall(db, "CALL apoc.load.json($key)",map("key","zip"),
                (row) -> {
                    Map<String,Object> r = (Map<String, Object>) row.get("value");
                    assertEquals("Michael", r.get("name"));
                    assertEquals(41L, r.get("age"));
                    assertEquals(asList("Selina", "Rana", "Selma"), r.get("children"));
                });
    }

    @Test public void testLoadJsonByUrlInConfigFile() throws Exception {

        testCall(db, "CALL apoc.load.json($key)",map("key","simpleJson"), // 'file:map.json' YIELD value RETURN value
                (row) -> {
                    assertEquals(map("foo",asList(1L,2L,3L)), row.get("value"));
                });
    }

    @Test(expected = QueryExecutionException.class)
    public void testLoadJsonByUrlInConfigFileWrongKey() throws Exception {

        try {
            testResult(db, "CALL apoc.load.json($key)",map("key","foo"), (r) -> r.hasNext());
        } catch (QueryExecutionException e) {
            Throwable except = ExceptionUtils.getRootCause(e);
            assertTrue(except instanceof RuntimeException);
            assertEquals("Can't read url or key invalid URL (foo) as json: no protocol: foo", except.getMessage());
            throw e;
        }
    }

    @Test
    public void testLoadJsonWithAuth() throws Exception {
        String userPass = "user:password";
        String token = Util.encodeUserColonPassToBase64(userPass);
        Map<String, String> responseBody = Map.of("result", "message");

        new MockServerClient("localhost", 1080)
                .when(
                        request()
                                .withPath("/docs/search")
                                .withHeader("Authorization", "Basic " + token)
                                .withHeader("\"Content-type\", \"application/json\""),
                        exactly(1))
                .respond(
                        response()
                                .withStatusCode(200)
                                .withHeaders(
                                        new Header("Content-Type", "application/json; charset=utf-8"),
                                        new Header("Cache-Control", "private, max-age=1000"))
                                .withBody(JsonUtil.OBJECT_MAPPER.writeValueAsString(responseBody))
                                .withDelay(TimeUnit.SECONDS, 1)
                );

        testCall(db, "call apoc.load.json($url)",
                    map( "url", "http://" + userPass + "@localhost:1080/docs/search"),
                    (row) -> assertEquals(responseBody, row.get("value"))
                );
    }

    @Test
    public void testLoadJsonParamsWithAuth() throws Exception {
	    String userPass = "user:password";
        String token = Util.encodeUserColonPassToBase64(userPass);
        Map<String, String> responseBody = Map.of("result", "message");

        new MockServerClient("localhost", 1080)
                .when(
                        request()
                                .withMethod("POST")
                                .withPath("/docs/search")
                                .withHeader("Authorization", "Basic " + token)
                                .withHeader("\"Content-type\", \"application/json\""),
                        exactly(1))
                .respond(
                        response()
                                .withStatusCode(200)
                                .withHeaders(
                                        new Header("Content-Type", "application/json; charset=utf-8"),
                                        new Header("Cache-Control", "public, max-age=86400"))
                                .withBody(JsonUtil.OBJECT_MAPPER.writeValueAsString(responseBody))
                                .withDelay(TimeUnit.SECONDS, 1)
                );

        testCall(db, "call apoc.load.jsonParams($url, $config, $payload)",
                    map("payload", "{\"query\":\"pagecache\",\"version\":\"3.5\"}",
                        "url", "http://" + userPass + "@localhost:1080/docs/search",
                        "config", map("method", "POST")),
                    (row) -> assertEquals(responseBody, row.get("value"))
                );
    }

    @Test
    public void testLoadJsonParams() throws Exception {
        new MockServerClient("localhost", 1080)
                .when(
                        request()
                                .withMethod("POST")
                                .withPath("/docs/search")
                                .withHeader("\"Content-type\", \"application/json\""),
                        exactly(1))
                .respond(
                        response()
                                .withStatusCode(200)
                                .withHeaders(
                                        new Header("Content-Type", "application/json; charset=utf-8"),
                                        new Header("Cache-Control", "public, max-age=86400"))
                                .withBody("{ result: 'message' }")
                                .withDelay(TimeUnit.SECONDS,1)
                );


        testCall(db, "call apoc.load.jsonParams($url, $config, $json)",
                map("json", "{\"query\":\"pagecache\",\"version\":\"3.5\"}",
                        "url", "http://localhost:1080/docs/search",
                        "config", map("method", "POST")),
                (row) -> {
                    Map<String, Object> value = (Map<String, Object>) row.get("value");
                    assertFalse("value should be not empty", value.isEmpty());
                });
    }
}
