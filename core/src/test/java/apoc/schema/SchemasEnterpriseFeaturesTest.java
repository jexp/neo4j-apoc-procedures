package apoc.schema;

import apoc.util.Neo4jContainerExtension;
import apoc.util.TestUtil;
import org.apache.commons.lang3.StringUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static apoc.util.TestContainerUtil.*;
import static apoc.util.TestUtil.isRunningInCI;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeNotNull;

/**
 * @author as
 * @since 12.02.19
 */
public class SchemasEnterpriseFeaturesTest {

    private static Neo4jContainerExtension neo4jContainer;
    private static Session session;

    @BeforeClass
    public static void beforeAll() {
        assumeFalse(isRunningInCI());
        TestUtil.ignoreException(() -> {
            // We build the project, the artifact will be placed into ./build/libs
            neo4jContainer = createEnterpriseDB(true);
            neo4jContainer.start();
        }, Exception.class);
        assumeNotNull(neo4jContainer);
        session = neo4jContainer.getSession();
    }

    @AfterClass
    public static void afterAll() {
        if (neo4jContainer != null) {
            session.close();
            neo4jContainer.close();
        }
    }

    @After
    public void removeAllConstraints() {
        session.writeTransaction(tx -> {
            tx.run("CALL apoc.schema.assert({},{})");
            tx.commit();
            return null;
        });
    }

    @Test
    public void testKeptNodeKeyAndUniqueConstraintIfExists() {
        String query = "CALL apoc.schema.assert(null,{Foo:[['foo','bar']]}, false)";
        testResult(session, query, (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals(expectedKeys("foo", "bar"), r.get("keys"));
            assertEquals(true, r.get("unique"));
            assertEquals("CREATED", r.get("action"));
            assertFalse(result.hasNext());
        });

        testResult(session, query, (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals(expectedKeys("foo", "bar"), r.get("keys"));
            assertEquals(true, r.get("unique"));
            assertEquals("KEPT", r.get("action"));
            assertFalse(result.hasNext());
        });

        session.readTransaction(tx -> {
            List<Record> result = tx.run("CALL db.constraints").list();
            assertEquals(1, result.size());
            Map<String, Object> firstResult = result.get(0).asMap();
            assertEquals("CONSTRAINT ON ( foo:Foo ) ASSERT (foo.foo, foo.bar) IS NODE KEY", firstResult.get("description"));
            tx.commit();
            return null;
        });
    }

    @Test
    public void testKeptNodeKeyAndUniqueConstraintIfExistsAndDropExistingIsFalse() {
        String query = "CALL apoc.schema.assert(null,{Foo:[['foo','bar']]}, false)";
        testResult(session, query, (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals(expectedKeys("foo", "bar"), r.get("keys"));
            assertEquals(true, r.get("unique"));
            assertEquals("CREATED", r.get("action"));
            assertFalse(result.hasNext());
        });
        testResult(session, "CALL apoc.schema.assert(null,{Foo:[['bar','foo']]}, false)", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals(expectedKeys("foo", "bar"), r.get("keys"));
            assertEquals(true, r.get("unique"));
            assertEquals("KEPT", r.get("action"));
            r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals(expectedKeys("bar", "foo"), r.get("keys"));
            assertEquals(true, r.get("unique"));
            assertEquals("CREATED", r.get("action"));
            assertFalse(result.hasNext());
        });

        session.readTransaction(tx -> {
            List<Record> result = tx.run("CALL db.constraints").list();
            assertEquals(2, result.size());
            List<String> actualDescriptions = result.stream()
                    .map(record -> (String) record.asMap().get("description"))
                    .collect(Collectors.toList());
            List<String> expectedDescriptions = List.of(
                    "CONSTRAINT ON ( foo:Foo ) ASSERT (foo.foo, foo.bar) IS NODE KEY",
                    "CONSTRAINT ON ( foo:Foo ) ASSERT (foo.bar, foo.foo) IS NODE KEY");
            assertEquals(expectedDescriptions, actualDescriptions);
            tx.commit();
            return null;
        });
    }

    @Test
    public void testCreateUniqueAndIsNodeKeyConstraintInSameLabel() {
        testResult(session, "CALL apoc.schema.assert(null,{Galileo: [['newton', 'tesla'], 'curie']}, false)", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("Galileo", r.get("label"));
            assertEquals(expectedKeys("newton", "tesla"), r.get("keys"));
            assertEquals(true, r.get("unique"));
            assertEquals("CREATED", r.get("action"));
            r = result.next();
            assertEquals("Galileo", r.get("label"));
            assertEquals(expectedKeys("curie"), r.get("keys"));
            assertEquals(true, r.get("unique"));
            assertEquals("CREATED", r.get("action"));
            assertFalse(result.hasNext());
        });

        session.readTransaction(tx -> {
            List<Record> result = tx.run("CALL db.constraints").list();
            assertEquals(2, result.size());
            List<String> actualDescriptions = result.stream()
                    .map(record -> (String) record.asMap().get("description"))
                    .collect(Collectors.toList());
            List<String> expectedDescriptions = List.of(
                    "CONSTRAINT ON ( galileo:Galileo ) ASSERT (galileo.newton, galileo.tesla) IS NODE KEY",
                    "CONSTRAINT ON ( galileo:Galileo ) ASSERT (galileo.curie) IS UNIQUE");
            assertEquals(expectedDescriptions, actualDescriptions);
            tx.commit();
            return null;
        });
    }

    @Test
    public void testDropNodeKeyConstraintAndCreateNodeKeyConstraintWhenUsingDropExistingOnlyIfNotExist() {
        session.writeTransaction(tx -> {
            tx.run("CREATE CONSTRAINT ON (f:Foo) ASSERT (f.bar,f.foo) IS NODE KEY");
            tx.commit();
            return null;
        });
        testResult(session, "CALL apoc.schema.assert(null,{Foo:[['bar','foo']]})", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals(expectedKeys("bar","foo"), r.get("keys"));
            assertEquals(true, r.get("unique"));
            assertEquals("KEPT", r.get("action"));
        });

        testResult(session, "CALL apoc.schema.assert(null,{Foo:[['baa','baz']]})", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals(expectedKeys("bar","foo"), r.get("keys"));
            assertEquals(true, r.get("unique"));
            assertEquals("DROPPED", r.get("action"));

            r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals(expectedKeys("baa","baz"), r.get("keys"));
            assertEquals(true, r.get("unique"));
            assertEquals("CREATED", r.get("action"));

            assertFalse(result.hasNext());
        });

        session.readTransaction(tx -> {
            List<Record> result = tx.run("CALL db.constraints").list();
            assertEquals(1, result.size());
            Map<String, Object> firstResult = result.get(0).asMap();
            assertEquals("CONSTRAINT ON ( foo:Foo ) ASSERT (foo.baa, foo.baz) IS NODE KEY", firstResult.get("description"));
            tx.commit();
            return null;
        });
    }

    @Test
    public void testDropSchemaWithNodeKeyConstraintWhenUsingDropExisting() {
        session.writeTransaction(tx -> {
            tx.run("CREATE CONSTRAINT ON (f:Foo) ASSERT (f.foo, f.bar) IS NODE KEY");
            tx.commit();
            return null;
        });
        testCall(session, "CALL apoc.schema.assert(null,null)", (r) -> {
            assertEquals("Foo", r.get("label"));
            assertEquals(expectedKeys("foo", "bar"), r.get("keys"));
            assertEquals(true, r.get("unique"));
            assertEquals("DROPPED", r.get("action"));
        });

        session.readTransaction(tx -> {
            List<Record> result = tx.run("CALL db.constraints").list();
            assertEquals(0, result.size());
            tx.commit();
            return null;
        });
    }

    @Test
    public void testDropConstraintExistsPropertyNode() {
        session.writeTransaction(tx -> {
            tx.run("CREATE CONSTRAINT ON (m:Movie) ASSERT exists(m.title)");
            tx.commit();
            return null;
        });
        testCall(session, "CALL apoc.schema.assert({},{})", (r) -> {
            assertEquals("Movie", r.get("label"));
            assertEquals(expectedKeys("title"), r.get("keys"));
            assertTrue("should be unique", (boolean) r.get("unique"));
            assertEquals("DROPPED", r.get("action"));
        });

        session.readTransaction(tx -> {
            List<Record> result = tx.run("CALL db.constraints").list();
            assertEquals(0, result.size());
            tx.commit();
            return null;
        });
    }

    @Test
    public void testDropConstraintExistsPropertyRelationship() {
        session.writeTransaction(tx -> {
            tx.run("CREATE CONSTRAINT ON ()-[acted:Acted]->() ASSERT exists(acted.since)");
            tx.commit();
            return null;
        });

        testCall(session, "CALL apoc.schema.assert({},{})", (r) -> {
            assertEquals("Acted", r.get("label"));
            assertEquals(expectedKeys("since"), r.get("keys"));
            assertTrue("should be unique", (boolean) r.get("unique"));
            assertEquals("DROPPED", r.get("action"));
        });

        session.readTransaction(tx -> {
            List<Record> result = tx.run("CALL db.constraints").list();
            assertEquals(0, result.size());
            tx.commit();
            return null;
        });
    }

    @Test
    public void testIndexOnMultipleProperties() {
        session.writeTransaction(tx -> {
            tx.run("CREATE INDEX ON :Foo(bar, foo)");
            tx.commit();
            return null;
        });

        String indexName = session.readTransaction(tx -> {
            String name = tx.run("CALL db.indexes() YIELD name RETURN name").single().get("name").asString();
            tx.commit();
            return name;
        });

        session.writeTransaction(tx -> {
            tx.run("CALL db.awaitIndex($indexName)", Collections.singletonMap("indexName", indexName));
            tx.commit();
            return null;
        });
        testResult(session, "CALL apoc.schema.nodes()", (result) -> {
            // Get the index info
            Map<String, Object> r = result.next();

            assertEquals(":Foo(bar,foo)", r.get("name"));
            assertEquals("ONLINE", r.get("status"));
            assertEquals("Foo", r.get("label"));
            assertEquals("INDEX", r.get("type"));
            assertTrue(((List<String>) r.get("properties")).contains("bar"));
            assertTrue(((List<String>) r.get("properties")).contains("foo"));

            assertTrue(!result.hasNext());
        });
        session.writeTransaction(tx -> {
            tx.run("DROP INDEX ON :Foo(bar, foo)");
            tx.commit();
            return null;
        });
    }

    @Test
    public void testPropertyExistenceConstraintOnNode() {
        session.writeTransaction(tx -> {
            tx.run("CREATE CONSTRAINT ON (bar:Bar) ASSERT exists(bar.foobar)");
            return null;
        });

        testResult(session, "CALL apoc.schema.nodes()", (result) -> {
            Map<String, Object> r = result.next();

            assertEquals("Bar", r.get("label"));
            assertEquals("NODE_PROPERTY_EXISTENCE", r.get("type"));
            assertEquals(asList("foobar"), r.get("properties"));

            assertTrue(!result.hasNext());
        });
        session.writeTransaction(tx -> {
            tx.run("DROP CONSTRAINT ON (bar:Bar) ASSERT exists(bar.foobar)");
            tx.commit();
            return null;
        });
    }

    @Test
    public void testConstraintExistsOnRelationship() {
        session.writeTransaction(tx -> {
            tx.run("CREATE CONSTRAINT ON ()-[like:LIKED]-() ASSERT exists(like.day)");
            tx.commit();
            return null;
        });
        testResult(session, "RETURN apoc.schema.relationship.constraintExists('LIKED', ['day'])", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals(true, r.entrySet().iterator().next().getValue());
        });
        session.writeTransaction(tx -> {
            tx.run("DROP CONSTRAINT ON ()-[like:LIKED]-() ASSERT exists(like.day)");
            tx.commit();
            return null;
        });
    }

    @Test
    public void testSchemaRelationships() {
        session.writeTransaction(tx -> {
            tx.run("CREATE CONSTRAINT ON ()-[like:LIKED]-() ASSERT exists(like.day)");
            tx.commit();
            return null;
        });
        testResult(session, "CALL apoc.schema.relationships()", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("CONSTRAINT ON ()-[liked:LIKED]-() ASSERT exists(liked.day)", r.get("name"));
            assertEquals("RELATIONSHIP_PROPERTY_EXISTENCE", r.get("type"));
            assertEquals(asList("day"), r.get("properties"));
            assertEquals(StringUtils.EMPTY, r.get("status"));
            assertFalse(result.hasNext());
        });
        session.writeTransaction(tx -> {
            tx.run("DROP CONSTRAINT ON ()-[like:LIKED]-() ASSERT exists(like.day)");
            tx.commit();
            return null;
        });
    }
    
    @Test
    public void testSchemaNodeWithRelationshipsConstraintsAndViceVersa() {
        session.writeTransaction(tx -> {
            tx.run("CREATE CONSTRAINT ON ()-[like:LIKED]-() ASSERT exists(like.day)");
            tx.run("CREATE CONSTRAINT ON (bar:Bar) ASSERT exists(bar.foobar)");
            tx.commit();
            return null;
        });
        
        testResult(session, "CALL apoc.schema.relationships()", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("CONSTRAINT ON ()-[liked:LIKED]-() ASSERT exists(liked.day)", r.get("name"));
            assertEquals("RELATIONSHIP_PROPERTY_EXISTENCE", r.get("type"));
            assertEquals(asList("day"), r.get("properties"));
            assertEquals(StringUtils.EMPTY, r.get("status"));
            assertFalse(result.hasNext());
        });
        testResult(session, "CALL apoc.schema.nodes()", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("Bar", r.get("label"));
            assertEquals("NODE_PROPERTY_EXISTENCE", r.get("type"));
            assertEquals(asList("foobar"), r.get("properties"));
            assertFalse(result.hasNext());
        });
        
        session.writeTransaction(tx -> {
            tx.run("DROP CONSTRAINT ON ()-[like:LIKED]-() ASSERT exists(like.day)");
            tx.run("DROP CONSTRAINT ON (bar:Bar) ASSERT exists(bar.foobar)");
            tx.commit();
            return null;
        });
    }

    private List<String> expectedKeys(String... keys){
        return asList(keys);
    }
}
