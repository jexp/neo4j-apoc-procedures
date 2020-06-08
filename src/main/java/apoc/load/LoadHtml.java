package apoc.load;

import apoc.result.MapResult;
import apoc.util.Util;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.*;
import java.util.stream.Stream;

public class LoadHtml {

    @Context
    public GraphDatabaseService db;

    @Context
    public Log log;


    @Procedure
    @Description("apoc.load.html('url',{name: jquery, name2: jquery}, config) YIELD value - Load Html page and return the result as a Map")
    public Stream<MapResult> html(@Name("url") String url, @Name(value = "query",defaultValue = "{}") Map<String, String> query, @Name(value = "config",defaultValue = "{}") Map<String, Object> config) {
        return readHtmlPage(url, query, config);
    }

    private Stream<MapResult> readHtmlPage(String url, Map<String, String> query, Map<String, Object> config){
        try {
            String charset = config.getOrDefault("charset", "UTF-8").toString();
            // baseUri is used to resolve relative paths
            String baseUri = config.getOrDefault("baseUri", "").toString();

            Document document = Jsoup.parse(Util.openInputStream(url, null, null), charset, baseUri);

            Map<String, Object> output = new HashMap<>();

            query.keySet().stream().forEach(key -> {
                Elements elements = document.select(query.get(key));

                output.put(key, getElements(elements, config));
            });

            return Stream.of( new MapResult(output) );
        } catch(Exception e){
            throw new RuntimeException("Can't read the HTML from: "+ url);
        }
    }

    private List<Map<String, Object>> getElements(Elements elements, Map<String, Object> config) {
        List<Map<String, Object>> elementList = new ArrayList<>();

        for (Element element : elements) {
            Map<String, Object> result = new HashMap<>();
            if(element.attributes().size() > 0) result.put("attributes", getAttributes(element));
            if(!element.data().isEmpty()) result.put("data", element.data());
            if(!element.val().isEmpty()) result.put("value", element.val());
            if(!element.tagName().isEmpty()) result.put("tagName", element.tagName());

            if ( Util.toBoolean( config.get("children") ) ) {
                if(element.hasText())  result.put("text", element.ownText());

                result.put("children", getElements(element.children(), config));
            }
            else {
                if(element.hasText()) result.put("text", element.text());
            }

            elementList.add(result);
        }

        return elementList;
    }

    private Map<String, String> getAttributes(Element element) {
        Map<String, String> attributes = new HashMap<>();
        for (Attribute attribute : element.attributes()) {
            if(!attribute.getValue().isEmpty()) attributes.put(attribute.getKey(), attribute.getValue());
        }

        return attributes;
    }


}