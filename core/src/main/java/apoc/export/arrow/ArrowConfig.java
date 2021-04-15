package apoc.export.arrow;

import apoc.util.Util;

import java.util.Collections;
import java.util.Map;

public class ArrowConfig {

    private final int batchSize;

    private final Map<String, Object> config;

    public ArrowConfig(Map<String, Object> config) {
        this.config = config == null ? Collections.emptyMap() : config;
        this.batchSize = Util.toInteger(this.config.getOrDefault("batchSize", 2000));
    }

    public int getBatchSize() {
        return batchSize;
    }

    public Map<String, Object> getConfig() {
        return config;
    }
}
