package apoc.uuid;
import apoc.util.*;
import org.junit.*;
import org.neo4j.driver.*;

import java.util.Map;

import static apoc.ApocConfig.APOC_UUID_ENABLED;
import static apoc.ApocConfig.APOC_UUID_ENABLED_DB;
import static apoc.util.TestContainerUtil.createEnterpriseDB;
import static apoc.util.TestContainerUtil.testResult;
import static apoc.util.TestUtil.isTravis;
import static apoc.uuid.UuidHandler.NOT_ENABLED_ERROR;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeNotNull;

public class UUIDMultiDbTest {

    private static Neo4jContainerExtension neo4jContainer;
    private static Driver driver;
    private static String dbTest = "dbtest";

    @BeforeClass
    public static void setupContainer() {
        assumeFalse(isTravis());
        TestUtil.ignoreException(() -> {
            neo4jContainer = createEnterpriseDB(!TestUtil.isTravis())
                    .withEnv(Map.of(String.format(APOC_UUID_ENABLED_DB, dbTest), "false",
                            APOC_UUID_ENABLED, "true"));
            neo4jContainer.start();
        }, Exception.class);
        assumeNotNull(neo4jContainer);

        driver = GraphDatabase.driver(neo4jContainer.getBoltUrl(), AuthTokens.basic("neo4j", "apoc"));

        try (Session session = driver.session()) {
            session.writeTransaction(tx -> tx.run(String.format("CREATE DATABASE %s;", dbTest)));
        }
    }

    @AfterClass
    public static void bringDownContainer() {
        if (neo4jContainer != null) {
            neo4jContainer.close();
        }
    }

    @Test(expected = RuntimeException.class)
    public void testWithSpecificDatabaseWithUUIDDisabled() throws Exception {

        Session session = driver.session(SessionConfig.forDatabase(dbTest));
        try{
            session.writeTransaction(tx -> tx.run(
                    "CREATE (d:Foo {name:'Test'})-[:WORK]->(l:Bar {name:'Baz'})")
            );

            session.writeTransaction(tx -> tx.run(
                    "CREATE CONSTRAINT ON (foo:Foo) ASSERT foo.uuid IS UNIQUE")
            );

            session.writeTransaction(tx -> tx.run(
                    "CALL apoc.uuid.install('Foo') YIELD label RETURN label")
            );

        } catch (RuntimeException e) {
            String expectedMessage = "Failed to invoke procedure `apoc.uuid.install`: " +
                    "Caused by: java.lang.RuntimeException: " + String.format(NOT_ENABLED_ERROR, dbTest);
            assertEquals(expectedMessage, e.getMessage());
            throw e;
        }
    }

    @Test
    public void testWithDefaultDatabaseWithUUIDEnabled() {

        try (Session session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            session.writeTransaction(tx -> tx.run(
                    "CREATE (d:Foo {name:'Test'})-[:WORK]->(l:Bar {name:'Baz'})")
            );

            session.writeTransaction(tx -> tx.run(
                    "CREATE CONSTRAINT ON (foo:Foo) ASSERT foo.uuid IS UNIQUE")
            );

            session.writeTransaction(tx -> tx.run(
                    "CALL apoc.uuid.install('Foo') YIELD label RETURN label")
            );

            testResult(session, "MATCH (n:Foo) RETURN n.uuid as uuid", (result) -> {
                Map<String, Object> r = result.next();
                assertNotNull(r.get("uuid")  );
            });

        }
    }
}
