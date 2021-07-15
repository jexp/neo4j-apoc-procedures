package apoc.result;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AssertSchemaResult {
    public final Object label;
    public final String key;
    public final List<String> keys;
    public boolean unique = false;
    public String action = "KEPT";

    public AssertSchemaResult(Object label, List<String> keys) {
        this.label = label;
        if (keys.size() == 1) {
            this.key = keys.get(0);
            this.keys = keys;
        } else {
            this.keys = keys;
            this.key = null;
        }
    }

    public AssertSchemaResult(String label, String key){
        this.label = label;
        this.keys = Arrays.asList(key);
        this.key = key;
    }

    public AssertSchemaResult unique() {
        this.unique = true;
        return this;
    }

    public AssertSchemaResult dropped() {
        this.action = "DROPPED";
        return this;
    }

    public AssertSchemaResult created() {
        this.action = "CREATED";
        return this;
    }
}
