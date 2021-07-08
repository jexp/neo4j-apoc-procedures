import apoc.ApocSignatures;
import apoc.util.Neo4jContainerExtension;
import apoc.util.TestUtil;
import org.junit.Test;
import org.neo4j.driver.Session;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static apoc.util.TestContainerUtil.createEnterpriseDB;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/*
 This test is just to verify if the APOC procedures and functions are correctly deployed into a Neo4j instance without any startup issue.
 If you don't have docker installed it will fail, and you can simply ignore it.
 */
public class StartupTest {

    private static final File APOC_FULL = Paths.get("../full").toFile();

    @Test
    public void check_basic_deployment() {
        try (Neo4jContainerExtension neo4jContainer = createEnterpriseDB(APOC_FULL, !TestUtil.isRunningInCI())
                .withNeo4jConfig("dbms.transaction.timeout", "5s")) {

            neo4jContainer.start();
            assertTrue("Neo4j Instance should be up-and-running", neo4jContainer.isRunning());

            Session session = neo4jContainer.getSession();
            int procedureCount = session.run("CALL dbms.procedures() YIELD name WHERE name STARTS WITH 'apoc' RETURN count(*) AS count").peek().get("count").asInt();
            int functionCount = session.run("CALL dbms.functions() YIELD name WHERE name STARTS WITH 'apoc' RETURN count(*) AS count").peek().get("count").asInt();
            int coreCount = session.run("CALL apoc.help('') YIELD core WHERE core = true RETURN count(*) AS count").peek().get("count").asInt();

            assertTrue(procedureCount > 0);
            assertTrue(functionCount > 0);
            assertTrue(coreCount > 0);
        }
    }

    @Test
    public void compare_with_sources() {
        try (Neo4jContainerExtension neo4jContainer = createEnterpriseDB(APOC_FULL, !TestUtil.isRunningInCI())) {
            neo4jContainer.start();

            assertTrue("Neo4j Instance should be up-and-running", neo4jContainer.isRunning());

            try (Session session = neo4jContainer.getSession()) {
                final List<String> functionNames = session.run("CALL apoc.help('') YIELD core, type, name WHERE core = true and type = 'function' RETURN name")
                        .list(record -> record.get("name").asString());
                final List<String> procedureNames = session.run("CALL apoc.help('') YIELD core, type, name WHERE core = true and type = 'procedure' RETURN name")
                        .list(record -> record.get("name").asString());


                assertEquals(sorted(ApocSignatures.PROCEDURES), procedureNames);
                assertEquals(sorted(ApocSignatures.FUNCTIONS), functionNames);
            }
        }
    }

    private List<String> sorted(List<String> signatures) {
        return signatures.stream().sorted().collect(Collectors.toList());
    }
}
