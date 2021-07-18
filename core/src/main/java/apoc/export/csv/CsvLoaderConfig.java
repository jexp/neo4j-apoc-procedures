package apoc.export.csv;

import java.util.Map;

/**
 * Config class to store the configuration for loading the CSV file. Names and defaults are based on the import tool's
 * <a href="http://neo4j.com/docs/operations-manual/current/tools/import/command-line-usage/">command line options</a>.
 */
public class CsvLoaderConfig {

    public static final String DELIMITER = "delimiter";
    private static final String ARRAY_DELIMITER = "arrayDelimiter";
    private static final String QUOTATION_CHARACTER = "quotationCharacter";
    private static final String STRING_IDS = "stringIds";
    private static final String SKIP_LINES = "skipLines";
    private static final String BATCH_SIZE = "batchSize";
    private static final String IGNORE_DUPLICATE_NODES = "ignoreDuplicateNodes";
    private static final String IGNORE_EMPTY_STRING = "ignoreEmptyString";

    private static char DELIMITER_DEFAULT = ',';
    private static char ARRAY_DELIMITER_DEFAULT = ';';
    private static char QUOTATION_CHARACTER_DEFAULT = '"';
    private static boolean STRING_IDS_DEFAULT = true;
    private static int SKIP_LINES_DEFAULT = 1;
    private static int BATCH_SIZE_DEFAULT = 2000;
    private static boolean IGNORE_DUPLICATE_NODES_DEFAULT = false;
    private static boolean IGNORE_EMPTY_STRING_DEFAULT = false;

    private final char delimiter;
    private final char arrayDelimiter;
    private final char quotationCharacter;
    private final boolean stringIds;
    private final int skipLines;
    private final int batchSize;
    private final boolean ignoreDuplicateNodes;
    private final boolean ignoreEmptyString;

    private CsvLoaderConfig(Builder builder) {
        this.delimiter = builder.delimiter;
        this.arrayDelimiter = builder.arrayDelimiter;
        this.quotationCharacter = builder.quotationCharacter;
        this.stringIds = builder.stringIds;
        this.skipLines = builder.skipLines;
        this.batchSize = builder.batchSize;
        this.ignoreDuplicateNodes = builder.ignoreDuplicateNodes;
        this.ignoreEmptyString = builder.ignoreEmptyString;
    }

    public char getDelimiter() {
        return delimiter;
    }

    public char getArrayDelimiter() {
        return arrayDelimiter;
    }

    public char getQuotationCharacter() {
        return quotationCharacter;
    }

    public boolean getStringIds() {
        return stringIds;
    }

    public int getSkipLines() {
        return skipLines;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public boolean getIgnoreDuplicateNodes() { return ignoreDuplicateNodes; }

    public boolean isIgnoreEmptyString() {
        return ignoreEmptyString;
    }

    /**
     * Creates builder to build {@link CsvLoaderConfig}.
     *
     * @return created builder
     */
    public static Builder builder() {
        return new Builder();
    }

    public static Character getCharacterOrString(Map<String, Object> config, String name) {
        Object o = config.get(name);
        if (o instanceof String) {
            String s = (String) o;
            if (s.length() != 1) {
                throw new IllegalStateException(name + " must have a length of one.");
            }
            return s.charAt(0);
        }
        if (o instanceof Character) {
            return (Character) o;
        }
        return null;
    }

    public static CsvLoaderConfig from(Map<String, Object> config) {
        Builder builder = builder();

        if (config.get(DELIMITER) != null) builder.delimiter(getCharacterOrString(config, DELIMITER));
        if (config.get(ARRAY_DELIMITER) != null) builder.arrayDelimiter(getCharacterOrString(config, ARRAY_DELIMITER));
        if (config.get(QUOTATION_CHARACTER) != null) builder.quotationCharacter(getCharacterOrString(config, QUOTATION_CHARACTER));
        if (config.get(STRING_IDS) != null) builder.stringIds((boolean) config.get(STRING_IDS));
        if (config.get(SKIP_LINES) != null) builder.skipLines((int) config.get(SKIP_LINES));
        if (config.get(BATCH_SIZE) != null) builder.batchSize((int) config.get(BATCH_SIZE));
        if (config.get(IGNORE_DUPLICATE_NODES) != null) builder.ignoreDuplicateNodes((boolean) config.get(IGNORE_DUPLICATE_NODES));
        if (config.get(IGNORE_EMPTY_STRING) != null) builder.ignoreEmptyString((boolean) config.get(IGNORE_EMPTY_STRING));
        return builder.build();
    }

    /**
     * Builder to build {@link CsvLoaderConfig}.
     */
    public static final class Builder {
        private char delimiter = DELIMITER_DEFAULT;
        private char arrayDelimiter = ARRAY_DELIMITER_DEFAULT;
        private char quotationCharacter = QUOTATION_CHARACTER_DEFAULT;
        private boolean stringIds = STRING_IDS_DEFAULT;
        private int skipLines = SKIP_LINES_DEFAULT;
        private int batchSize = BATCH_SIZE_DEFAULT;
        private boolean ignoreDuplicateNodes = IGNORE_DUPLICATE_NODES_DEFAULT;
        private boolean ignoreEmptyString = IGNORE_EMPTY_STRING_DEFAULT;

        private Builder() {
        }

        public Builder delimiter(char delimiter) {
            this.delimiter = delimiter;
            return this;
        }

        public Builder arrayDelimiter(char arrayDelimiter) {
            this.arrayDelimiter = arrayDelimiter;
            return this;
        }

        public Builder quotationCharacter(char quotationCharacter) {
            this.quotationCharacter = quotationCharacter;
            return this;
        }

        public Builder stringIds(boolean stringIds) {
            this.stringIds = stringIds;
            return this;
        }

        public Builder skipLines(int skipLines) {
            this.skipLines = skipLines;
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder ignoreDuplicateNodes(boolean ignoreDuplicateNodes) {
            this.ignoreDuplicateNodes = ignoreDuplicateNodes;
            return this;
        }

        public Builder ignoreEmptyString(boolean ignoreEmptyString) {
            this.ignoreEmptyString = ignoreEmptyString;
            return this;
        }

        public CsvLoaderConfig build() {
            return new CsvLoaderConfig(this);
        }
    }

}
