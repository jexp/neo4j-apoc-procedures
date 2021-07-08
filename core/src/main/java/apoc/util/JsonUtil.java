package apoc.util;

import apoc.export.util.DurationValueSerializer;
import apoc.export.util.PointSerializer;
import apoc.export.util.TemporalSerializer;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.neo4j.graphdb.spatial.Point;
import org.neo4j.values.storable.DurationValue;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.temporal.Temporal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static apoc.ApocConfig.apocConfig;

/**
 * @author mh
 * @since 04.05.16
 */
public class JsonUtil {
    private final static Option[] defaultJsonPathOptions = { Option.DEFAULT_PATH_LEAF_TO_NULL, Option.SUPPRESS_EXCEPTIONS };
    
    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final String PATH_OPTIONS_ERROR_MESSAGE = "Invalid pathOptions. The allowed values are: " + EnumSet.allOf(Option.class);
    static {
        OBJECT_MAPPER.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
        OBJECT_MAPPER.configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, false);
        OBJECT_MAPPER.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
        OBJECT_MAPPER.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
        OBJECT_MAPPER.configure(JsonParser.Feature.ALLOW_COMMENTS, true);
        OBJECT_MAPPER.configure(JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true);
        OBJECT_MAPPER.configure(JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS, true);
        OBJECT_MAPPER.enable(DeserializationFeature.USE_LONG_FOR_INTS);
        SimpleModule module = new SimpleModule("Neo4jApocSerializer");
        module.addSerializer(Point.class, new PointSerializer());
        module.addSerializer(Temporal.class, new TemporalSerializer());
        module.addSerializer(DurationValue.class, new DurationValueSerializer());
        OBJECT_MAPPER.registerModule(module);
    }

    static class NonClosingStream extends FilterInputStream {

        protected NonClosingStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() throws IOException {
        }
    }

    private static Configuration getJsonPathConfig(List<String> options) {
        try {
            Option[] opts = options == null ? defaultJsonPathOptions : options.stream().map(Option::valueOf).toArray(Option[]::new);
            return Configuration.builder()
                    .options(opts)
                    .jsonProvider(new JacksonJsonProvider(OBJECT_MAPPER))
                    .mappingProvider(new JacksonMappingProvider(OBJECT_MAPPER))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(PATH_OPTIONS_ERROR_MESSAGE, e);
        }
    }
    
    public static Stream<Object> loadJson(String url, Map<String,Object> headers, String payload) {
        return loadJson(url,headers,payload,"", true);
    }
    
    private static Stream<Object> loadJson(String url, Map<String,Object> headers, String payload, String path, boolean failOnError) {
        return loadJson(url, headers, payload, path, failOnError, null);
    }

    public static Stream<Object> loadJson(String url, Map<String,Object> headers, String payload, String path, boolean failOnError, List<String> options) {
        try {
            url = Util.getLoadUrlByConfigFile("json",url, "url").orElse(url);
            apocConfig().checkReadAllowed(url);
            url = FileUtils.changeFileUrlIfImportDirectoryConstrained(url);
            InputStream input = Util.openInputStream(url, headers, payload);
            JsonParser parser = OBJECT_MAPPER.getFactory().createParser(input);
            MappingIterator<Object> it = OBJECT_MAPPER.readValues(parser, Object.class);
            Stream<Object> stream = StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, 0), false);
            return StringUtils.isBlank(path) ? stream  : stream.map((value) -> JsonPath.parse(value, getJsonPathConfig(options)).read(path));
        } catch (IOException e) {
            String u = Util.cleanUrl(url);
            if(!failOnError)
                return Stream.of();
            else
                throw new RuntimeException("Can't read url or key " + u + " as json: "+e.getMessage());
        }
    }

    public static Stream<Object> loadJson(String url) {
        return loadJson(url,null,null,"", true);
    }

    public static <T> T parse(String json, String path, Class<T> type) {
        return parse(json, path, type, null);
    }
    
    public static <T> T parse(String json, String path, Class<T> type, List<String> options) {
        if (json==null || json.isEmpty()) return null;
        try {
            if (path == null || path.isEmpty()) {
                return OBJECT_MAPPER.readValue(json, type);
            }
            return JsonPath.parse(json, getJsonPathConfig(options)).read(path, type);
        } catch (IOException e) {
            throw new RuntimeException("Can't convert " + json + " to "+type.getSimpleName()+" with path "+path, e);
        }
    }

    public static String writeValueAsString(Object json) {
        try {
            return OBJECT_MAPPER.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public static byte[] writeValueAsBytes(Object json) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
