package apoc.load.util;

import apoc.util.Util;
import org.apache.commons.lang.StringUtils;
import org.apache.directory.api.ldap.model.cursor.SearchCursor;
import org.apache.directory.api.ldap.model.entry.Attribute;
import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.directory.api.ldap.model.entry.Value;
import org.apache.directory.api.ldap.model.exception.LdapException;
import org.apache.directory.api.ldap.model.message.*;
import org.apache.directory.api.ldap.model.message.controls.PagedResults;
import org.apache.directory.api.ldap.model.message.controls.PagedResultsImpl;
import org.apache.directory.api.ldap.model.name.Dn;
import org.apache.directory.api.ldap.model.url.LdapUrl;
import org.apache.directory.ldap.client.api.*;
import org.neo4j.logging.Log;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LdapUtil {
    public static final String LOAD_TYPE = "ldap";
    private static final String KEY_NOT_FOUND_MESSAGE = "No apoc.ldap.%s.url url specified";

    /**
     * Build up an LdapConnectionPool from the configuration information received from the
     * proc constructor or config file. It will pre-authenticate connections retrieved from the
     * pool and will already be bound
     * @param ldapConfig Configuration when the procedure was called
     * @return a new LDAP connection pool
     */
    public static LdapConnectionPool getConnectionPool(LoadLdapConfig ldapConfig) {
        LdapConnectionConfig config = new LdapConnectionConfig();
        LdapUrl ldapUrl = ldapConfig.getLdapUrl();
        boolean isSecure = ldapUrl.getScheme().equals(LdapUrl.LDAPS_SCHEME);
        int port = ldapUrl.getPort();

        if (isSecure && (port == -1)) {
            port = LdapConnectionConfig.DEFAULT_LDAPS_PORT;
        } else if (isSecure && !(port == -1)) {
            port = ldapUrl.getPort();
        } else if (!isSecure && (port == -1)) {
            port = LdapConnectionConfig.DEFAULT_LDAP_PORT;
        } else {
            port = ldapUrl.getPort();
        }

        config.setLdapHost(ldapUrl.getHost());
        config.setLdapPort(port);
        config.setName(ldapConfig.getCredentials().getBindDn());
        config.setCredentials(ldapConfig.getCredentials().getBindPassword());
        config.setUseSsl(isSecure);

        DefaultLdapConnectionFactory factory = new DefaultLdapConnectionFactory(config);
        return new LdapConnectionPool(new DefaultPoolableLdapConnectionFactory(factory));
    }

    /**
     * Do an object-level search on the range retrieved attribute to get the next page of values
     *
     * @param object the attribute that was returned by the server
     * @param attrName the original attribute name
     * @param nextHigh the max value of the next page to retrieve
     * @param nextLow the min value of the next page to retrieve
     * @param connection an LdapConnection to retrieve the next page
     * @param log Neo4j logger
     * @return the results of the next page search
     * @throws LdapException any search/LDAP server errors returned
     */
    public static Entry getRangedValues(Dn object, String attrName, int nextHigh, int nextLow, LdapConnection connection, Log log) throws LdapException {
        Entry entry = null;
        SearchRequest rangeRetrieval = new SearchRequestImpl();
        String nextRange = String.format("%s;range=%d-%d", attrName, nextLow, nextHigh);
        if (log.isDebugEnabled())
            log.debug("Getting next range retrieval page " + nextRange);
        rangeRetrieval.addAttributes(nextRange);
        rangeRetrieval.setScope(SearchScope.OBJECT);
        rangeRetrieval.setBase(object);
        rangeRetrieval.setFilter("(objectClass=*)");
        try (SearchCursor subCursor = connection.search(rangeRetrieval)) {
            while (subCursor.next()) {
                Response subResp = subCursor.get();
                if (subResp instanceof SearchResultEntry) {
                    entry = ((SearchResultEntry) subResp).getEntry();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error retrieving object: " + e);
        }
        return entry;
    }

    /**
     * Process all of the possible attribute values. AD doesn't return values attached to the
     * queried attribute. It will return a new attribute in the form of:
     * <attribute name>;range=<low>-<high>
     * To return results that an other LDAP server would reply with, gather all of the values to be
     * attached to the original attribute
     *
     * @param object the attribute that was returned by the server
     * @param attribute the original attribute name
     * @param connection an LdapConnection to retrieve the next page
     * @param log Neo4j logger
     * @return the complete list of values from all pages
     */
    public static List<Value> rangedRetrievalHandler(Dn object, Attribute attribute, LdapConnection connection, Log log) {
        List<Value> combinedValues = new ArrayList<>();

        Pattern pattern = Pattern.compile("(\\S+);range=(\\d)-(\\d+)");
        Matcher matcher = pattern.matcher(attribute.getId());
        attribute.forEach(combinedValues::add);

        if (matcher.find()) {
            if (log.isDebugEnabled()) log.debug("Found match for ranged value " + matcher.group(0));
            String attrName = matcher.group(1);
            int low = Integer.parseInt(matcher.group(2));
            int high = Integer.parseInt(matcher.group(3));
            final int step = high - low;
            int nextLow = high + 1;
            int nextHigh = step + nextLow;
            boolean moreResults = true;

            while (moreResults) {
                try {
                    if (log.isDebugEnabled()) log.debug(String.format("Getting next page of values with step %d, next low %d, next high %d", step, nextLow, nextHigh));
                    Entry nextPage = getRangedValues(object, attrName, nextHigh, nextLow, connection, log);
                    for (Attribute page : nextPage) {
                        page.forEach(combinedValues::add);
                        Matcher nextPageMatcher = Pattern.compile("(\\S+);range=(\\d+)-(\\d+|\\*)").matcher(page.getId());
                        if (nextPageMatcher.find()) {
                            if (nextPageMatcher.group(3).equals("*")) {
                                if (log.isDebugEnabled()) log.debug("Found last page of values, we are done");
                                moreResults = false;
                            } else {
                                nextLow = Integer.parseInt(nextPageMatcher.group(3)) + 1;
                                nextHigh = step + nextLow;
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return combinedValues;
    }


    /**
     * Build the paged SearchRequest where the cookie is either null for the first run or the
     * cookie returned from the LDAP server. In the latter instance, the cookie serves as a
     * bookmark to inform the LDAP server which results should be returned next
     * @param ldapConfig the config received from the procedure
     * @param cookie the cookie returned by the LDAP server
     * @param log Neo4j logger
     * @return a new SearchRequest for the next page of results
     * @throws LdapException a problem with building the search was encountered
     */
    public static SearchRequest buildSearch(LoadLdapConfig ldapConfig, byte[] cookie, Log log) throws LdapException {
        if (log.isDebugEnabled())
            log.debug("Generating new SearchRequest");
        SearchRequest req = new SearchRequestImpl();
        req.setScope(ldapConfig.getLdapUrl().getScope());
        req.setSizeLimit(0);
        req.setFilter(ldapConfig.getLdapUrl().getFilter());
        for (String a : ldapConfig.getLdapUrl().getAttributes()) {
            req.addAttributes(a);
        }
        req.setBase(ldapConfig.getLdapUrl().getDn());
        req.followReferrals();

        PagedResults pr = new PagedResultsImpl();
        pr.setSize(ldapConfig.getPageSize());
        if (null != cookie) {
            pr.setCookie(cookie);
        }

        req.addControl(pr);
        if (log.isDebugEnabled())
            log.debug(String.format("Generated SearchRequest: %s", req.toString()));
        return req;
    }

    public static LoadLdapConfig getFromConfigFile(String key) {
        Map<String, Object> temp = new HashMap<>();
        temp.put("url", Util.getLoadUrlByConfigFile(LOAD_TYPE, key, "url"));
        temp.put("username", Util.getLoadUrlByConfigFile(LOAD_TYPE, key, "username"));
        temp.put("password", Util.getLoadUrlByConfigFile(LOAD_TYPE, key, "password"));
        temp.put("pageSize", Util.getLoadUrlByConfigFile(LOAD_TYPE, key, "pageSize"));
        return new LoadLdapConfig(temp);
    }

    public static String getUrlOrKey(String urlOrKey) {
        return urlOrKey.contains(":") ? urlOrKey : Util.getLoadUrlByConfigFile(LOAD_TYPE, urlOrKey, "url").orElseThrow(() -> new RuntimeException(String.format(KEY_NOT_FOUND_MESSAGE, urlOrKey)));
    }

    public static int getPageSize(String urlOrKey) {
        String pageSizeStr = Util.getLoadUrlByConfigFile(LOAD_TYPE, urlOrKey, "pageSize").orElse(StringUtils.EMPTY);
        return (pageSizeStr.equals(StringUtils.EMPTY)) ? 100 : Integer.parseInt(pageSizeStr);
    }

    public static String getUsernameOrKey(String usernameOrKey) {
        return (usernameOrKey.equals(StringUtils.EMPTY)) ? Util.getLoadUrlByConfigFile(LOAD_TYPE, usernameOrKey, "binddn").orElse(StringUtils.EMPTY) : usernameOrKey;
    }

    public static String getPasswordOrKey(String passwordOrKey) {
        return (passwordOrKey.equals(StringUtils.EMPTY)) ? Util.getLoadUrlByConfigFile(LOAD_TYPE, passwordOrKey, "password").orElse(StringUtils.EMPTY) : passwordOrKey;
    }

    /**
     * Use the upper and lower 16 bytes to generate the UUID that would match string representations
     * commonly found elsewhere (such as Apache Directory Studio)
     * @param entryUuid bytes returned from the server
     * @return string representation of the UUID
     */
    public static String getUuidFromEntryUuid(byte[] entryUuid) {
        ByteBuffer bb = ByteBuffer.wrap(entryUuid);
        long high = bb.getLong();
        long low = bb.getLong();
        return new UUID(high, low).toString();
    }

    /**
     * Rearrange the bytes before generating the UUID in order ot match the form shown in all of
     * the Active Directory tools
     * @param objectGuid bytes returned from the server for the attribute
     * @return a string representation of the objectGUID (which isn't a true UUID)
     */
    public static String getStringFromObjectGuid(byte[] objectGuid) {
        byte[] rearranged = new byte[16];
        rearranged[0] = objectGuid[3];
        rearranged[1] = objectGuid[2];
        rearranged[2] = objectGuid[1];
        rearranged[3] = objectGuid[0];
        rearranged[4] = objectGuid[5];
        rearranged[5] = objectGuid[4];
        rearranged[6] = objectGuid[7];
        rearranged[7] = objectGuid[6];
        System.arraycopy(objectGuid, 8, rearranged, 8, 8);
        return getUuidFromEntryUuid(rearranged);
    }

    /**
     * Generates a String representation of an objectSid as defined by
     * https://docs.microsoft.com/en-us/windows/win32/secauthz/sid-components
     * The code was adapted from https://ldapwiki.com/wiki/ObjectSID
     * @param objectSid byte result from the LDAP server
     * @return a String representation of the objectSid
     */
    public static String getStringFromObjectSid(byte[] objectSid) {
        StringBuilder strSid = new StringBuilder("S-");

        int revision = objectSid[0];
        strSid.append(revision);

        int countSubAuths = objectSid[1] & 0xFF;

        long authority = 0;
        for (int i=2; i<=7;i++) {
            authority |= ((long) objectSid[i]) << (8 * (5 - (i - 2)));
        }
        strSid.append("-");
        strSid.append(Long.toHexString(authority));

        int offset = 8;
        int size = 4;
        for (int j=0; j<countSubAuths; j++) {
            long subAuthority = 0;
            for (int k=0; k<size; k++) {
                subAuthority |= (long) (objectSid[offset+k] & 0xFF) << (8 * k);
            }
            strSid.append("-");
            strSid.append(subAuthority);
            offset += size;
        }
        return strSid.toString();
    }

    /**
     * Translate an LDAP server provided timestamp into a Neo4J DateTime. AD will return a float
     * as the milliseconds, but it's always .0. LDAP servers leave off the float
     * @param dateTime original timestamp from the LDAP server
     * @return a string matching what Neo4J would expect for a DateTime object
     */
    public static String formatDateTime(String dateTime) {
        Pattern pattern = Pattern.compile("(\\d\\d\\d\\d)(\\d\\d)(\\d\\d)(\\d\\d)(\\d\\d)(\\d\\d)");
        Matcher groupedDateTime = pattern.matcher(dateTime);
        String formattedDateTime = null;
        if (groupedDateTime.find()) {
            formattedDateTime = String.format("%s-%s-%sT%s:%s:%sZ",
                    groupedDateTime.group(1),
                    groupedDateTime.group(2),
                    groupedDateTime.group(3),
                    groupedDateTime.group(4),
                    groupedDateTime.group(5),
                    groupedDateTime.group(6));
        }
        return formattedDateTime;
    }
}