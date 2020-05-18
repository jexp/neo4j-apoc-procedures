package apoc.load;

import apoc.ApocConfig;
import apoc.load.util.LdapUtil;
import apoc.load.util.LoadLdapConfig;
import apoc.util.TestUtil;
import org.apache.directory.api.ldap.model.exception.LdapURLEncodingException;
import org.apache.directory.api.ldap.model.url.LdapUrl;
import org.apache.directory.server.annotations.CreateLdapServer;
import org.apache.directory.server.annotations.CreateTransport;
import org.apache.directory.server.core.annotations.ApplyLdifFiles;
import org.apache.directory.server.core.annotations.CreateDS;
import org.apache.directory.server.core.annotations.CreatePartition;
import org.apache.directory.server.core.integ.AbstractLdapTestUnit;
import org.apache.directory.server.core.integ.FrameworkRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.neo4j.common.DependencyResolver;
import org.neo4j.configuration.Config;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.Result;
import org.neo4j.logging.internal.LogService;
import org.neo4j.procedure.impl.GlobalProceduresRegistry;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static apoc.util.MapUtil.map;
import static apoc.util.TestUtil.testCall;
import static org.junit.Assert.assertEquals;

@RunWith(FrameworkRunner.class)
@CreateDS(name = "testDS",
        partitions = {
                @CreatePartition(name = "test", suffix = "dc=neo4j,dc=test")
        })
@CreateLdapServer(
        transports = {
                @CreateTransport(protocol = "LDAP", address = "localhost")
        },
        allowAnonymousAccess = true
)
@ApplyLdifFiles({"users.ldif"})
public class LoadLdapTest extends AbstractLdapTestUnit {

    @Rule
    public DbmsRule db = new ImpermanentDbmsRule();

    private int ldapServerPort = ldapServer.getPort();
    private ApocConfig storage;

    private List<Map<String, Object>> consumeResults(Result rows) {
        List<Map<String, Object>> results = new ArrayList<>();
        while (rows.hasNext()) {
            Map<String, Object> row = rows.next();
            results.add(row);
        }
        return results;
    }

    @Before
    public void setUp() throws Exception {

        DependencyResolver dependencyResolver = db.getDependencyResolver();
        storage = new ApocConfig(dependencyResolver.resolveDependency(Config.class),
                dependencyResolver.resolveDependency(LogService.class),
                dependencyResolver.resolveDependency(GlobalProceduresRegistry.class),
                dependencyResolver.resolveDependency(DatabaseManagementService.class));
//        ApocConfiguration.initialize((GraphDatabaseAPI) db);
        storage.init();

        String unsecuredUrl = String.format("ldap://localhost:%d/dc=neo4j,dc=test?cn?sub?(&(objectClass=person)(cn=Agent Smith))", ldapServerPort);
        storage.setProperty("apoc.ldap.localhost_noauth.url", unsecuredUrl);
        storage.setProperty("apoc.ldap.localhost_auth.url", unsecuredUrl);
        storage.setProperty("apoc.ldap.localhost_auth.username", "uid=admin,ou=system");
        storage.setProperty("apoc.ldap.localhost_auth.password", "secret");


//        ApocConfiguration.addToConfig(map("ldap.localhost_noauth.url", unsecuredUrl));
//        ApocConfiguration.addToConfig(map("ldap.localhost_auth.url", unsecuredUrl));
//        ApocConfiguration.addToConfig(map("ldap.localhost_auth.username", "uid=admin,ou=system"));
//        ApocConfiguration.addToConfig(map("ldap.localhost_auth.password", "secret"));
//
        TestUtil.registerProcedure(db, LoadLdap.class);
    }

    @Test
    public void testConfigLoadingMap() throws LdapURLEncodingException {
        HashMap<String, Object> config = new HashMap<>();
        config.put("username", "TheArchitect");
        config.put("password", "TheOracle");
        config.put("url", String.format("ldap://localhost:%d/dc=neo4j,dc=test?cn?sub?(&(objectClass=person)(cn=Agent Smith))", ldapServerPort));
        LoadLdapConfig ldapConfig = new LoadLdapConfig(storage, config);
        assertEquals(ldapConfig.getCredentials().getBindDn(), "TheArchitect");
        assertEquals(ldapConfig.getCredentials().getBindPassword(), "TheOracle");
        assertEquals(ldapConfig.getPageSize(), 100);
        assertEquals(ldapConfig.getLdapUrl(), new LdapUrl(String.format("ldap://localhost:%d/dc=neo4j,dc=test?cn?sub?(&(objectClass=person)(cn=Agent Smith))", ldapServerPort)));
    }

    @Test
    public void testConfigLoadingMapAndURL() throws LdapURLEncodingException {
        HashMap<String, Object> config = new HashMap<>();
        config.put("username", "TheArchitect");
        config.put("password", "TheOracle");
        String url = String.format("ldap://localhost:%d/dc=neo4j,dc=test?cn?sub?(&(objectClass=person)(cn=Agent Smith))", ldapServerPort);
        LoadLdapConfig ldapConfig = new LoadLdapConfig(storage, config, url);
        assertEquals(ldapConfig.getCredentials().getBindDn(), "TheArchitect");
        assertEquals(ldapConfig.getCredentials().getBindPassword(), "TheOracle");
        assertEquals(ldapConfig.getPageSize(), 100);
        assertEquals(ldapConfig.getLdapUrl(), new LdapUrl(String.format("ldap://localhost:%d/dc=neo4j,dc=test?cn?sub?(&(objectClass=person)(cn=Agent Smith))", ldapServerPort)));
    }

    @Test
    public void testLoadFromApocConfig() throws LdapURLEncodingException {
        LoadLdapConfig config = LdapUtil.getFromConfigFile(storage, "localhost_auth");
        assertEquals(config.getLdapUrl(), new LdapUrl(String.format("ldap://localhost:%d/dc=neo4j,dc=test?cn?sub?(&(objectClass=person)(cn=Agent Smith))", ldapServerPort)));
        assertEquals(config.getCredentials().getBindDn(), "uid=admin,ou=system");
        assertEquals(config.getCredentials().getBindPassword(), "secret");
    }

    @Test
    public void testBadPageSize() {
        storage.setProperty("apoc.ldap.localhost_auth.pageSize", "lah");
        LoadLdapConfig config = LdapUtil.getFromConfigFile(storage, "localhost_auth");
        assertEquals(config.getPageSize(), 100);

    }

    @Test
    public void testValidPageSize() {
        storage.setProperty("apoc.ldap.localhost_auth.pageSize", "771");
        LoadLdapConfig config = LdapUtil.getFromConfigFile(storage, "localhost_auth");
        assertEquals(config.getPageSize(), 771);

    }

    @Test
    public void testCompatSignatureMapMap() {
        Map<String, Object> agentSmith = new HashMap<>();
        agentSmith.put("dn", "cn=Agent Smith,ou=Users,dc=neo4j,dc=test");
        agentSmith.put("cn", "Agent Smith");
        testCall(db,
                String.format("CALL apoc.load.ldap({ldapHost: 'localhost:%d', loginDN: 'uid=admin,ou=system', loginPW: 'secret'}, ", ldapServerPort) +
                        "{searchBase: 'dc=neo4j,dc=test', searchFilter: '(&(objectClass=person)(cn=Agent Smith))', searchScope: 'SCOPE_SUB', attributes: ['cn']})",
                (row) -> assertEquals(row, map("entry", agentSmith)));
    }

    @Test
    public void testCompatSignatureStringMap() {
        storage.setProperty("loadldap.myldap.config", String.format("localhost:%d uid=admin,ou=system secret", ldapServerPort));
        Map<String, Object> agentSmith = new HashMap<>();
        agentSmith.put("dn", "cn=Agent Smith,ou=Users,dc=neo4j,dc=test");
        agentSmith.put("cn", "Agent Smith");
        testCall(db,
                "CALL apoc.load.ldap('myldap', " +
                        "{searchBase: 'dc=neo4j,dc=test', searchFilter: '(&(objectClass=person)(cn=Agent Smith))', searchScope: 'SCOPE_SUB', attributes: ['cn']})",
                (row) -> assertEquals(row, map("entry", agentSmith)));
    }

    @Test
    public void testLoadNoAuth() {
        Map<String, Object> agentSmith = new HashMap<>();
        agentSmith.put("dn", "cn=Agent Smith,ou=Users,dc=neo4j,dc=test");
        agentSmith.put("cn", "Agent Smith");
        testCall(db, "CALL apoc.load.ldapurl('localhost_noauth')", (row) -> assertEquals(row, map("entry", agentSmith)));
    }

    @Test
    public void testLoadAuth() {
        Map<String, Object> agentSmith = new HashMap<>();
        agentSmith.put("dn", "cn=Agent Smith,ou=Users,dc=neo4j,dc=test");
        agentSmith.put("cn", "Agent Smith");
        testCall(db, "CALL apoc.load.ldapurl('localhost_auth')", (row) -> assertEquals(row, map("entry", agentSmith)));
    }

    @Test
    public void testInlineConfig() {
        Map<String, Object> agentSmith = new HashMap<>();
        agentSmith.put("dn", "cn=Agent Smith,ou=Users,dc=neo4j,dc=test");
        agentSmith.put("cn", "Agent Smith");
        testCall(db, String.format("CALL apoc.load.ldapurl('ldap://localhost:%d/dc=neo4j,dc=test?cn?sub?(&(objectClass=person)(cn=Agent Smith))', ", ldapServerPort) +
                        "{username: 'uid=admin,ou=system', password: 'secret'})",
                (row) -> assertEquals(row, map("entry", agentSmith)));
    }

    @Test
    public void testLoadCredsFromFileUrlFromCall() {
        Map<String, Object> neo = new HashMap<>();
        neo.put("dn", "cn=Neo,ou=Users,dc=neo4j,dc=test");
        neo.put("cn", "Neo");
        testCall(db,
                String.format("CALL apoc.load.ldapurl('localhost_noauth', {url: 'ldap://localhost:%d/dc=neo4j,dc=test?cn?sub?(&(objectClass=person)(cn=Neo))'})", ldapServerPort),
                (row) -> assertEquals(row, map("entry", neo)));
    }

    @Test
    public void testInlineConfigAnonymous() {
        Map<String, Object> neo = new HashMap<>();
        neo.put("dn", "cn=Neo,ou=Users,dc=neo4j,dc=test");
        neo.put("cn", "Neo");
        testCall(db,
                String.format("CALL apoc.load.ldapurl('ldap://localhost:%d/dc=neo4j,dc=test?cn?sub?(&(objectClass=person)(cn=Neo))')", ldapServerPort),
                (row) -> assertEquals(row, map("entry", neo)));
    }

    @Test
    public void testNoAttributesRequestedGotCn() {
        Map<String, Object> neo = new HashMap<>();
        neo.put("dn", "cn=Neo,ou=Users,dc=neo4j,dc=test");
        neo.put("cn", "Neo");
        testCall(db,
                String.format("CALL apoc.load.ldapurl('ldap://localhost:%d/dc=neo4j,dc=test??sub?(&(objectClass=person)(cn=Neo))')", ldapServerPort),
                (row) -> assertEquals(((Map) row.get("entry")).get("uid"), "neo1"));
    }

    @After
    public void tearDown() {
        db.shutdown();
    }

    /*
    @Test
    public void testPagingSupport() {
        testResult(db, "CALL apoc.load.ldap('ldaps://db.debian.org/dc=debian,dc=org?uid?sub?(objectclass=inetorgperson)')", (row)->assertTrue(consumeResults(row).size()>1000));
    }*/
}