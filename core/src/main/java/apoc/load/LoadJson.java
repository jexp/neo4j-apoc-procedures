package apoc.load;

import apoc.result.MapResult;
import apoc.result.ObjectResult;
import apoc.util.JsonUtil;
import apoc.util.Util;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class LoadJson {

    private static final String AUTH_HEADER_KEY = "Authorization";
    private static final String LOAD_TYPE = "json";

    @Context
    public GraphDatabaseService db;

    @SuppressWarnings("unchecked")
    @Procedure
    @Description("apoc.load.jsonArray('url') YIELD value - load array from JSON URL (e.g. web-api) to import JSON as stream of values")
    public Stream<ObjectResult> jsonArray(@Name("url") String url, @Name(value = "path",defaultValue = "") String path, @Name(value = "config",defaultValue = "{}") Map<String, Object> config) {
        return JsonUtil.loadJson(url, null, null, path, true, (List<String>) config.get("pathOptions"))
                .flatMap((value) -> {
                    if (value instanceof List) {
                        List list = (List) value;
                        if (list.isEmpty()) return Stream.empty();
                        if (list.get(0) instanceof Map) return list.stream().map(ObjectResult::new);
                    }
                    return Stream.of(new ObjectResult(value));
                });
        // throw new RuntimeException("Incompatible Type " + (value == null ? "null" : value.getClass()));
    }

    @Procedure
    @Description("apoc.load.json('url',path, config) YIELD value -  import JSON as stream of values if the JSON was an array or a single value if it was a map")
    public Stream<MapResult> json(@Name("url") String url, @Name(value = "path",defaultValue = "") String path, @Name(value = "config",defaultValue = "{}") Map<String, Object> config) {
        return jsonParams(url,null,null, path, config);
    }

    @SuppressWarnings("unchecked")
    @Procedure
    @Description("apoc.load.jsonParams('url',{header:value},payload, config) YIELD value - load from JSON URL (e.g. web-api) while sending headers / payload to import JSON as stream of values if the JSON was an array or a single value if it was a map")
    public Stream<MapResult> jsonParams(@Name("urlOrKey") String urlOrKey, @Name("headers") Map<String,Object> headers, @Name("payload") String payload, @Name(value = "path",defaultValue = "") String path, @Name(value = "config",defaultValue = "{}") Map<String, Object> config) {
        if (config == null) config = Collections.emptyMap();
        boolean failOnError = (boolean) config.getOrDefault("failOnError", true);
        List<String> pathOptions = (List<String>) config.get("pathOptions");
        return loadJsonStream(urlOrKey, headers, payload, path, failOnError, pathOptions);
    }

    public static Stream<MapResult> loadJsonStream(@Name("url") String url, @Name("headers") Map<String, Object> headers, @Name("payload") String payload) {
        return loadJsonStream(url, headers, payload, "", true, null);
    }
    public static Stream<MapResult> loadJsonStream(@Name("url") String url, @Name("headers") Map<String, Object> headers, @Name("payload") String payload, String path, boolean failOnError, List<String> pathOptions) {
        headers = null != headers ? headers : new HashMap<>();
        headers.putAll(Util.extractCredentialsIfNeeded(url, failOnError));
        Stream<Object> stream = JsonUtil.loadJson(url,headers,payload, path, failOnError, pathOptions);
        return stream.flatMap((value) -> {
            if (value instanceof Map) {
                return Stream.of(new MapResult((Map) value));
            }
            if (value instanceof List) {
                if (((List)value).isEmpty()) return Stream.empty();
                if (((List) value).get(0) instanceof Map)
                    return ((List) value).stream().map((v) -> new MapResult((Map) v));
                return Stream.of(new MapResult(Collections.singletonMap("result",value)));
            }
            if(!failOnError)
                throw new RuntimeException("Incompatible Type " + (value == null ? "null" : value.getClass()));
            else
                return Stream.of(new MapResult(Collections.emptyMap()));
        });
    }

}
