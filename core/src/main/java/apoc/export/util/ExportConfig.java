package apoc.export.util;

import apoc.export.cypher.formatter.CypherFormat;
import apoc.util.Util;

import java.util.*;

import static apoc.util.Util.toBoolean;
import static java.util.Arrays.asList;

/**
 * @author mh
 * @since 19.01.14
 */
public class ExportConfig {
    public static final char QUOTECHAR = '"';
    public static final String NONE_QUOTES = "none";
    public static final String ALWAYS_QUOTES = "always";
    public static final String IF_NEEDED_QUUOTES = "ifNeeded";

    public static final int DEFAULT_BATCH_SIZE = 20000;
    private static final int DEFAULT_UNWIND_BATCH_SIZE = 20;
    public static final String DEFAULT_DELIM = ",";
    public static final String DEFAULT_ARRAY_DELIM = ";";
    public static final String DEFAULT_QUOTES = ALWAYS_QUOTES;
    private final boolean streamStatements;

    private int batchSize;
    private boolean silent;
    private boolean bulkImport = false;
    private boolean sampling;
    private String delim;
    private String quotes;
    private boolean useTypes;
    private Set<String> caption;
    private boolean writeNodeProperties;
    private boolean nodesOfRelationships;
    private ExportFormat format;
    private CypherFormat cypherFormat;
    private final Map<String, Object> config;
    private boolean separateHeader;
    private String arrayDelim;
    private Map<String, Object> optimizations;

    public enum OptimizationType {NONE, UNWIND_BATCH, UNWIND_BATCH_PARAMS}
    private OptimizationType optimizationType;
    private int unwindBatchSize;
    private long awaitForIndexes;
    private final Map<String, Object> samplingConfig;

    public int getBatchSize() {
        return batchSize;
    }

    public boolean isSilent() {
        return silent;
    }

    public boolean isBulkImport() {
        return bulkImport;
    }

    public char getDelimChar() {
        return delim.charAt(0);
    }

    public String getDelim() {
        return delim;
    }

    public String isQuotes() {
        return quotes;
    }

    public boolean useTypes() {
        return useTypes;
    }

    public ExportFormat getFormat() { return format; }

    public Set<String> getCaption() { return caption; }

    public CypherFormat getCypherFormat() { return cypherFormat; }

    public ExportConfig(Map<String,Object> config) {
        config = config != null ? config : Collections.emptyMap();
        this.silent = toBoolean(config.getOrDefault("silent",false));
        this.delim = delim(config.getOrDefault("delim", DEFAULT_DELIM).toString());
        this.arrayDelim = delim(config.getOrDefault("arrayDelim", DEFAULT_ARRAY_DELIM).toString());
        this.useTypes = toBoolean(config.get("useTypes"));
        this.caption = convertCaption(config.getOrDefault("caption", asList("name", "title", "label", "id")));
        this.nodesOfRelationships = toBoolean(config.get("nodesOfRelationships"));
        this.bulkImport = toBoolean(config.get("bulkImport"));
        this.separateHeader = toBoolean(config.get("separateHeader"));
        this.format = ExportFormat.fromString((String) config.getOrDefault("format", "cypher-shell"));
        this.cypherFormat = CypherFormat.fromString((String) config.getOrDefault("cypherFormat", "create"));
        this.config = config;
        this.streamStatements = toBoolean(config.get("streamStatements")) || toBoolean(config.get("stream"));
        this.writeNodeProperties = toBoolean(config.get("writeNodeProperties"));
        exportQuotes(config);
        this.optimizations = (Map<String, Object>) config.getOrDefault("useOptimizations", Collections.emptyMap());
        this.optimizationType = OptimizationType.valueOf(optimizations.getOrDefault("type", OptimizationType.UNWIND_BATCH.toString()).toString().toUpperCase());
        this.batchSize = ((Number)config.getOrDefault("batchSize", DEFAULT_BATCH_SIZE)).intValue();
        this.sampling = toBoolean(config.getOrDefault("sampling", false));
        this.samplingConfig = (Map<String, Object>) config.getOrDefault("samplingConfig", new HashMap<>());
        this.unwindBatchSize = ((Number)getOptimizations().getOrDefault("unwindBatchSize", DEFAULT_UNWIND_BATCH_SIZE)).intValue();
        this.awaitForIndexes = ((Number)config.getOrDefault("awaitForIndexes", 300)).longValue();
        validate();
    }

    private void validate() {
        if (OptimizationType.UNWIND_BATCH_PARAMS.equals(this.optimizationType) && !ExportFormat.CYPHER_SHELL.equals(this.format)) {
            throw new RuntimeException("`useOptimizations: 'UNWIND_BATCH_PARAMS'` can be used only in combination with `format: 'CYPHER_SHELL' but got [format:`" + this.format + "]");
        }
        if (!OptimizationType.NONE.equals(this.optimizationType) && this.unwindBatchSize > this.batchSize) {
            throw new RuntimeException("`unwindBatchSize` must be <= `batchSize`, but got [unwindBatchSize:" + unwindBatchSize + ", batchSize:" + batchSize + "]");
        }
    }

    private void exportQuotes(Map<String, Object> config)
    {
        try {
            this.quotes = (String) config.getOrDefault("quotes", DEFAULT_QUOTES);

            if ( !quotes.equals(ALWAYS_QUOTES) && !quotes.equals(NONE_QUOTES) && !quotes.equals(IF_NEEDED_QUUOTES) ) {
                throw new RuntimeException("The string value of the field quote is not valid");
            }

        } catch (ClassCastException e) { // backward compatibility
            this.quotes = toBoolean(config.get("quotes")) ? ALWAYS_QUOTES : NONE_QUOTES;
        }
    }

    public boolean getRelsInBetween() {
        return nodesOfRelationships;
    }

    private static String delim(String value) {
        if (value.length()==1) return value;
        if (value.contains("\\t")) return String.valueOf('\t');
        if (value.contains(" ")) return " ";
        throw new RuntimeException("Illegal delimiter '"+value+"'");
    }

    public String defaultRelationshipType() {
        return config.getOrDefault("defaultRelationshipType","RELATED").toString();
    }

    public boolean readLabels() {
        return toBoolean(config.getOrDefault("readLabels",false));
    }

    public boolean storeNodeIds() {
        return toBoolean(config.getOrDefault("storeNodeIds", false));
    }

    public boolean separateFiles() {
        return toBoolean(config.getOrDefault("separateFiles", false));
    }

    private static Set<String> convertCaption(Object value) {
        if (value == null) return null;
        if (!(value instanceof List)) throw new RuntimeException("Only array of Strings are allowed!");
        List<String> strings = (List<String>) value;
        return new HashSet<>(strings);
    }

    public boolean streamStatements() {
        return streamStatements;
    }

    public boolean writeNodeProperties() {
        return writeNodeProperties;
    }

    public long getTimeoutSeconds() {
        return Util.toLong(config.getOrDefault("timeoutSeconds",100));
    }

    public int getUnwindBatchSize() { return unwindBatchSize; }

    public Map<String, Object> getOptimizations() {
        return optimizations;
    }

    public boolean isSeparateHeader() {
        return this.separateHeader;
    }

    public String getArrayDelim() {
        return arrayDelim;
    }

    public OptimizationType getOptimizationType() {
        return optimizationType;
    }

    public long getAwaitForIndexes() {
        return awaitForIndexes;
    }

    public Map<String, Object> getSamplingConfig() {
        return samplingConfig;
    }

    public boolean isSampling() {
        return sampling;
    }
    
}
