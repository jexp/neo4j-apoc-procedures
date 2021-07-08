package apoc.load.util;

import apoc.util.Util;
import org.apache.commons.lang3.StringUtils;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;

/**
 * @author ab-Larus
 * @since 03-10-18
 */
public class LoadJdbcConfig {

    private ZoneId zoneId = null;

    private Credentials credentials;

    private final Long fetchSize;

    private final boolean autoCommit;

    public LoadJdbcConfig(Map<String,Object> config) {
        config = config != null ? config : Collections.emptyMap();
        try {
            this.zoneId = config.containsKey("timezone") ?
                    ZoneId.of(config.get("timezone").toString()) : null;
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(String.format("The timezone field contains an error: %s", e.getMessage()));
        }
        this.credentials = config.containsKey("credentials") ? createCredentials((Map<String, String>) config.get("credentials")) : null;
        this.fetchSize = Util.toLong(config.getOrDefault("fetchSize", 5000L));
        this.autoCommit = Util.toBoolean(config.getOrDefault("autoCommit", false));
    }

    public ZoneId getZoneId(){
        return this.zoneId;
    }

    public Credentials getCredentials() {
        return this.credentials;
    }

    public static Credentials createCredentials(Map<String,String> credentials) {
        if (!credentials.getOrDefault("user", StringUtils.EMPTY).equals(StringUtils.EMPTY) && !credentials.getOrDefault("password", StringUtils.EMPTY).equals(StringUtils.EMPTY)) {
            return new Credentials(credentials.get("user"), credentials.get("password"));
        } else {
            throw new IllegalArgumentException("In config param credentials must be passed both user and password.");
        }
    }

    public static class Credentials {
        private String user;

        private String password;

        public Credentials(String user, String password){
            this.user = user;

            this.password = password;
        }

        public String getUser() {
            return user;
        }

        public String getPassword() {
            return password;
        }
    }

    public boolean hasCredentials() {
        return this.credentials != null;
    }

    public Long getFetchSize() {
        return fetchSize;
    }

    public boolean isAutoCommit() {
        return autoCommit;
    }
}