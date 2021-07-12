package apoc.load.util;

import apoc.ApocConfiguration;
import org.apache.commons.lang.StringUtils;
import org.apache.directory.api.ldap.model.exception.LdapInvalidDnException;
import org.apache.directory.api.ldap.model.exception.LdapURLEncodingException;
import org.apache.directory.api.ldap.model.message.SearchScope;
import org.apache.directory.api.ldap.model.name.Dn;
import org.apache.directory.api.ldap.model.url.LdapUrl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LoadLdapConfig {
    private final Credentials credentials;
    private int pageSize;
    private LdapUrl ldapUrl;

    public LoadLdapConfig(Map<String, Object> config, String url) {
        config = (null != config) ? config : Collections.emptyMap();
        this.credentials = new Credentials(
                (String) config.getOrDefault("username", StringUtils.EMPTY),
                (String) config.getOrDefault("password", StringUtils.EMPTY)
        );
        try {
            this.ldapUrl = new LdapUrl(url);
        } catch (LdapURLEncodingException e) {
            throw new RuntimeException(e);
        }
        
        try {
            this.pageSize = (config.containsKey("pageSize")) ? Integer.parseInt((String) config.get("pageSize")) : 100;
        } catch (java.lang.NumberFormatException e) {
            this.pageSize = 100;
        }
    }

    public LoadLdapConfig(Map<String, Object> config) {
        this(config, (String) config.get("url"));
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getPageSize() {
        return this.pageSize;
    }

    public LdapUrl getLdapUrl() {
        return this.ldapUrl;
    }

    public void setLdapUrl(String url) {
        try {
            this.ldapUrl = new LdapUrl(url);
        } catch (LdapURLEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public Credentials getCredentials() {
        return this.credentials;
    }

    public static class Credentials {
        private final String bindDn;
        private final String bindPassword;

        public Credentials(String bindDn, String bindPassword) {
            this.bindDn = bindDn;
            this.bindPassword = bindPassword;
        }

        public String getBindDn() {
            return bindDn;
        }

        public String getBindPassword() {
            return bindPassword;
        }
    }

    public boolean hasCredentials() {
        return this.credentials != null;
    }

    public static LoadLdapConfig compatConfig(Map<String, Object> connMap, Map<String, Object> searchMap) throws LdapInvalidDnException {
        LdapUrl tempUrl = new LdapUrl();
        HashMap<String, Object> compatConfig = new HashMap<>();
        int port = 389;
        List<String> attributes = (List<String>) searchMap.get("attributes");

        // Simple parser for hostname string which maybe includes the port number
        String rawHost = (String) connMap.get("ldapHost");
        String[] splitHost = rawHost.split(":");

        if (!splitHost[1].equals(StringUtils.EMPTY)) {
            try {
                port = Integer.parseInt(splitHost[1]);
            } catch (java.lang.NumberFormatException e) {
                throw new RuntimeException(e);
            }
        }

        /*
         * Since the prior plugin doesn't provide a way to specify secure connections on non-standard
         * ports, naively assume that everything is unsecured unless 636 is given
         */
        boolean isSecure = (port == 636);
        tempUrl.setHost(splitHost[0]);
        tempUrl.setPort(port);
        tempUrl.setScheme(isSecure ? LdapUrl.LDAPS_SCHEME : LdapUrl.LDAP_SCHEME);
        tempUrl.setDn(new Dn((String) searchMap.get("searchBase")));
        tempUrl.setAttributes((attributes != null) ? attributes : new ArrayList<>());
        tempUrl.setFilter((String) searchMap.get("searchFilter"));
        String scope = (String) searchMap.get("searchScope");
        if (scope.equals("SCOPE_BASE")) {
            tempUrl.setScope(SearchScope.OBJECT);
        } else if (scope.equals("SCOPE_ONE")) {
            tempUrl.setScope(SearchScope.ONELEVEL);
        } else if (scope.equals("SCOPE_SUB")) {
            tempUrl.setScope(SearchScope.SUBTREE);
        } else {
            throw new RuntimeException("Invalid scope:" + scope + ". value scopes are SCOPE_BASE, SCOPE_ONE and SCOPE_SUB");
        }

        compatConfig.put("url", tempUrl.toString());
        compatConfig.put("username", connMap.get("loginDN"));
        compatConfig.put("password", connMap.get("loginPW"));
        return new LoadLdapConfig(compatConfig, tempUrl.toString());
    }

    public static LoadLdapConfig compatConfig(String conn, Map<String, Object> searchMap) throws LdapInvalidDnException {
        String value = (String) ApocConfiguration.get("loadldap").get(conn + ".config");
        // format <ldaphost:port> <logindn> <loginpw>
        if (value == null) {
            throw new RuntimeException("No apoc.loadldap." + conn +".config ldap access configuration specified");
        }
        String[] sConf = value.split(" ");
        HashMap<String, Object> connMap = new HashMap<>();
        connMap.put("ldapHost", sConf[0]);
        connMap.put("loginDN", sConf[1]);
        connMap.put("loginPW", sConf[2]);
        return compatConfig(connMap, searchMap);
    }
}
