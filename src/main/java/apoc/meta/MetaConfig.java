package apoc.meta;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;

import java.util.*;

public class MetaConfig {

    private Set<String> includesLabels;
    private Set<String> includesRels;
    private Set<String> excludes;
	private Set<String> excludeRels;
    private long maxRels;
    private long sample;

    /**
     * A map of values, with the following keys and meanings.
     * - labels: a list of strings, which are whitelisted node labels. If this list
     * is specified **only these labels** will be examined.
     * - rels: a list of strings, which are whitelisted rel types.  If this list is
     * specified, **only these reltypes** will be examined.
     * - excludes: a list of strings, which are node labels.  This
     * works like a blacklist: if listed here, the thing won't be considered.  Everything
     * else (subject to the whitelist) will be.
     * - sample: a long number, i.e. "1 in (SAMPLE)".  If set to 1000 this means that
     * every 1000th node will be examined.  It does **not** mean that a total of 1000 nodes
     * will be sampled.
     * - maxRels: the maximum number of relationships of a given type to look at.
     * @param config
     */
    public MetaConfig(Map<String,Object> config) {
        config = config != null ? config : Collections.emptyMap();
        this.includesLabels = new HashSet<>((Collection<String>)config.getOrDefault("includeLabels",Collections.EMPTY_SET));
        this.includesRels = new HashSet<>((Collection<String>)config.getOrDefault("includeRels",Collections.EMPTY_SET));
        this.excludes = new HashSet<>((Collection<String>)config.getOrDefault("excludeLabels",Collections.EMPTY_SET));
		this.excludeRels = new HashSet<>((Collection<String>)config.getOrDefault("excludeRels",Collections.EMPTY_SET));
        this.sample = (long) config.getOrDefault("sample", 1000L);
        this.maxRels = (long) config.getOrDefault("maxRels", 100L);
    }

    public Set<String> getIncludesLabels() {
        return includesLabels;
    }

    public Set<String> getIncludesRels() {
        return includesRels;
    }

    public Set<String> getExcludes() {
        return excludes;
    }

    public void setExcludes(Set<String> excludes) {
        this.excludes = excludes;
    }

    public Set<String> getExcludeRels() {
        return excludeRels;
    }

    /**
     * @param l
     * @return true if the label matches the mask expressed by this object, false otherwise.
     */
    public boolean matches(Label l) {
        if (getExcludes().contains(l.name())) { return false; }
        if (getIncludesLabels().isEmpty()) { return true; }
        return getIncludesLabels().contains(l.name());
    }

    /**
     * @param labels
     * @return true if any of the labels matches the mask expressed by this object, false otherwise.
     */
    public boolean matches(Iterable<Label> labels) {
        // If it matches any label, it gets looked at, because labels can co-occur.
        boolean match = true;

        for(Label l : labels) {
            match = match || matches(l);
        }

        return match;
    }

    /**
     * @param r
     * @return true if the relationship matches the mask expressed by this object, false otherwise.
     */
    public boolean matches(Relationship r) {
        return matches(r.getType());
    }

    /**
     * @param rt
     * @return true if the relationship type matches the mask expressed by this object, false otherwise.
     */
    public boolean matches(RelationshipType rt) {
        String name = rt.name();

        if (getExcludeRels().contains(name)) { return false; }
        if (getIncludesRels().isEmpty()) { return true; }
        return getIncludesRels().contains(name);
    }

    public long getSample() {
        return sample;
    }

    public long getMaxRels() {
        return maxRels;
    }
}
