package apoc.path;

import apoc.util.TestUtil;
import apoc.util.Util;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.helpers.collection.Iterators;
import org.neo4j.test.TestGraphDatabaseFactory;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Test path expanders with node filters (where we already have the nodes that will be used for the whitelist, blacklist, endnodes, and terminator nodes
 */
public class NodeFilterTest {
    private static GraphDatabaseService db;

    public NodeFilterTest() throws Exception {
    }

    @BeforeClass
    public static void setUp() throws Exception {
        db = new TestGraphDatabaseFactory().newImpermanentDatabase();
        TestUtil.registerProcedure(db, PathExplorer.class);
        String movies = Util.readResourceFile("movies.cypher");
        String bigbrother = "MATCH (per:Person) MERGE (bb:BigBrother {name : 'Big Brother' })  MERGE (bb)-[:FOLLOWS]->(per)";
        try (Transaction tx = db.beginTx()) {
            db.execute(movies);
            db.execute(bigbrother);
            tx.success();
        }
    }

    @AfterClass
    public static void tearDown() {
        db.shutdown();
    }

    @After
    public void removeOtherLabels() {
        db.execute("OPTIONAL MATCH (c:Western) REMOVE c:Western WITH DISTINCT 1 as ignore OPTIONAL MATCH (c:Blacklist) REMOVE c:Blacklist");
    }

    @Test
    public void testTerminatorNodesPruneExpansion() {
        db.execute("MATCH (c:Person) WHERE c.name in ['Clint Eastwood', 'Gene Hackman'] SET c:Western");

        TestUtil.testResult(db,
                "MATCH (k:Person {name:'Keanu Reeves'}), (gene:Person {name:'Gene Hackman'}), (clint:Person {name:'Clint Eastwood'}) " +
                        "CALL apoc.path.subgraphNodes(k, {relationshipFilter:'ACTED_IN|PRODUCED|DIRECTED', terminatorNodes:[gene, clint]}) yield node " +
                        "return node",
                result -> {

                    List<Map<String, Object>> maps = Iterators.asList(result);
                    assertEquals(1, maps.size());
                    Node node = (Node) maps.get(0).get("node");
                    assertEquals("Gene Hackman", node.getProperty("name"));
                });
    }

    @Test
    public void testEndNodesContinueTraversal() {
        db.execute("MATCH (c:Person) WHERE c.name in ['Clint Eastwood', 'Gene Hackman'] SET c:Western WITH c WHERE c.name = 'Clint Eastwood' SET c:Blacklist");

        TestUtil.testResult(db,
                "MATCH (k:Person {name:'Keanu Reeves'}), (gene:Person {name:'Gene Hackman'}), (clint:Person {name:'Clint Eastwood'}) " +
                        "CALL apoc.path.subgraphNodes(k, {relationshipFilter:'ACTED_IN|PRODUCED|DIRECTED', endNodes:[gene, clint]}) yield node " +
                        "return node",
                result -> {

                    List<Map<String, Object>> maps = Iterators.asList(result);
                    assertEquals(2, maps.size());
                    Node node = (Node) maps.get(0).get("node");
                    assertEquals("Gene Hackman", node.getProperty("name"));
                    node = (Node) maps.get(1).get("node");
                    assertEquals("Clint Eastwood", node.getProperty("name"));
                });
    }

    @Test
    public void testEndNodesAndTerminatorNodesReturnExpectedResults() {
        db.execute("MATCH (c:Person) WHERE c.name in ['Clint Eastwood', 'Gene Hackman'] SET c:Western WITH c WHERE c.name = 'Clint Eastwood' SET c:Blacklist");

        TestUtil.testResult(db,
                "MATCH (k:Person {name:'Keanu Reeves'}), (gene:Person {name:'Gene Hackman'}), (clint:Person {name:'Clint Eastwood'}) " +
                        "CALL apoc.path.subgraphNodes(k, {relationshipFilter:'ACTED_IN|PRODUCED|DIRECTED', endNodes:[gene], terminatorNodes:[clint]}) yield node " +
                        "return node",
                result -> {

                    List<Map<String, Object>> maps = Iterators.asList(result);
                    assertEquals(2, maps.size());
                    Node node = (Node) maps.get(0).get("node");
                    assertEquals("Gene Hackman", node.getProperty("name"));
                    node = (Node) maps.get(1).get("node");
                    assertEquals("Clint Eastwood", node.getProperty("name"));
                });
    }

    @Test
    public void testEndNodesAndTerminatorNodesReturnExpectedResultsReversed() {
        db.execute("MATCH (c:Person) WHERE c.name in ['Clint Eastwood', 'Gene Hackman'] SET c:Western WITH c WHERE c.name = 'Clint Eastwood' SET c:Blacklist");

        TestUtil.testResult(db,
                "MATCH (k:Person {name:'Keanu Reeves'}), (gene:Person {name:'Gene Hackman'}), (clint:Person {name:'Clint Eastwood'}) " +
                        "CALL apoc.path.subgraphNodes(k, {relationshipFilter:'ACTED_IN|PRODUCED|DIRECTED', terminatorNodes:[gene], endNodes:[clint]}) yield node " +
                        "return node",
                result -> {

                    List<Map<String, Object>> maps = Iterators.asList(result);
                    assertEquals(1, maps.size());
                    Node node = (Node) maps.get(0).get("node");
                    assertEquals("Gene Hackman", node.getProperty("name"));
                });
    }

    @Test
    public void testTerminatorNodesOverruleEndNodes1() {
        db.execute("MATCH (c:Person) WHERE c.name in ['Clint Eastwood', 'Gene Hackman'] SET c:Western WITH c WHERE c.name = 'Clint Eastwood' SET c:Blacklist");

        TestUtil.testResult(db,
                "MATCH (k:Person {name:'Keanu Reeves'}), (gene:Person {name:'Gene Hackman'}), (clint:Person {name:'Clint Eastwood'}) " +
                        "CALL apoc.path.subgraphNodes(k, {relationshipFilter:'ACTED_IN|PRODUCED|DIRECTED', terminatorNodes:[gene], endNodes:[clint, gene]}) yield node " +
                        "return node",
                result -> {

                    List<Map<String, Object>> maps = Iterators.asList(result);
                    assertEquals(1, maps.size());
                    Node node = (Node) maps.get(0).get("node");
                    assertEquals("Gene Hackman", node.getProperty("name"));
                });
    }

    @Test
    public void testTerminatorNodesOverruleEndNodes2() {
        db.execute("MATCH (c:Person) WHERE c.name in ['Clint Eastwood', 'Gene Hackman'] SET c:Western WITH c WHERE c.name = 'Clint Eastwood' SET c:Blacklist");

        TestUtil.testResult(db,
                "MATCH (k:Person {name:'Keanu Reeves'}), (gene:Person {name:'Gene Hackman'}), (clint:Person {name:'Clint Eastwood'}) " +
                        "CALL apoc.path.subgraphNodes(k, {relationshipFilter:'ACTED_IN|PRODUCED|DIRECTED', terminatorNodes:[gene, clint], endNodes:[clint, gene]}) yield node " +
                        "return node",
                result -> {

                    List<Map<String, Object>> maps = Iterators.asList(result);
                    assertEquals(1, maps.size());
                    Node node = (Node) maps.get(0).get("node");
                    assertEquals("Gene Hackman", node.getProperty("name"));
                });
    }

    @Test
    public void testEndNodesWithTerminationFilterPrunesExpansion() {
        db.execute("MATCH (c:Person) WHERE c.name in ['Clint Eastwood', 'Gene Hackman'] SET c:Western");

        TestUtil.testResult(db,
                "MATCH (k:Person {name:'Keanu Reeves'}), (gene:Person {name:'Gene Hackman'}), (clint:Person {name:'Clint Eastwood'}) " +
                        "CALL apoc.path.subgraphNodes(k, {relationshipFilter:'ACTED_IN|PRODUCED|DIRECTED', labelFilter:'/Western', endNodes:[clint, gene]}) yield node " +
                        "return node",
                result -> {

                    List<Map<String, Object>> maps = Iterators.asList(result);
                    assertEquals(1, maps.size());
                    Node node = (Node) maps.get(0).get("node");
                    assertEquals("Gene Hackman", node.getProperty("name"));
                });
    }

    @Test
    public void testTerminatorNodesWithEndNodeFilterPrunesExpansion() {
        db.execute("MATCH (c:Person) WHERE c.name in ['Clint Eastwood', 'Gene Hackman'] SET c:Western");

        TestUtil.testResult(db,
                "MATCH (k:Person {name:'Keanu Reeves'}), (gene:Person {name:'Gene Hackman'}), (clint:Person {name:'Clint Eastwood'}) " +
                        "CALL apoc.path.subgraphNodes(k, {relationshipFilter:'ACTED_IN|PRODUCED|DIRECTED', labelFilter:'>Western', terminatorNodes" +
                        ":[clint, gene]}) yield node " +
                        "return node",
                result -> {

                    List<Map<String, Object>> maps = Iterators.asList(result);
                    assertEquals(1, maps.size());
                    Node node = (Node) maps.get(0).get("node");
                    assertEquals("Gene Hackman", node.getProperty("name"));
                });
    }

    @Test
    public void testBlacklistNodesInPathPrunesPath() {
        db.execute("MATCH (c:Person) WHERE c.name in ['Clint Eastwood', 'Gene Hackman'] SET c:Western");

        TestUtil.testResult(db,
                "MATCH (k:Person {name:'Keanu Reeves'}), (gene:Person {name:'Gene Hackman'}), (clint:Person {name:'Clint Eastwood'}), (unforgiven:Movie{title:'Unforgiven'}) " +
                        "CALL apoc.path.subgraphNodes(k, {relationshipFilter:'ACTED_IN|PRODUCED|DIRECTED', labelFilter:'>Western', blacklistNodes:[unforgiven]}) yield node " +
                        "return node",
                result -> {

                    List<Map<String, Object>> maps = Iterators.asList(result);
                    assertEquals(1, maps.size());
                    Node node = (Node) maps.get(0).get("node");
                    assertEquals("Gene Hackman", node.getProperty("name"));
                });
    }

    @Test
    public void testBlacklistNodesWithEndNodesPrunesPath() {
        db.execute("MATCH (c:Person) WHERE c.name in ['Clint Eastwood', 'Gene Hackman'] SET c:Western");

        TestUtil.testResult(db,
                "MATCH (k:Person {name:'Keanu Reeves'}), (gene:Person {name:'Gene Hackman'}), (clint:Person {name:'Clint Eastwood'}), (unforgiven:Movie{title:'Unforgiven'}) " +
                        "CALL apoc.path.subgraphNodes(k, {relationshipFilter:'ACTED_IN|PRODUCED|DIRECTED', endNodes:[clint, gene], blacklistNodes:[unforgiven]}) yield node " +
                        "return node",
                result -> {

                    List<Map<String, Object>> maps = Iterators.asList(result);
                    assertEquals(1, maps.size());
                    Node node = (Node) maps.get(0).get("node");
                    assertEquals("Gene Hackman", node.getProperty("name"));
                });
    }

    @Test
    public void testBlacklistNodesOverridesAllOtherNodeFilters() {
        db.execute("MATCH (c:Person) WHERE c.name in ['Clint Eastwood', 'Gene Hackman'] SET c:Western");

        TestUtil.testResult(db,
                "MATCH (k:Person {name:'Keanu Reeves'}), (gene:Person {name:'Gene Hackman'}), (clint:Person {name:'Clint Eastwood'}), (unforgiven:Movie{title:'Unforgiven'}),  (replacements:Movie{title:'The Replacements'})\n" +
                        "WITH k, clint, gene, [k, gene, clint, unforgiven, replacements] as whitelist\n" +
                        "CALL apoc.path.subgraphNodes(k, {relationshipFilter:'ACTED_IN|PRODUCED|DIRECTED', terminatorNodes:[clint], endNodes:[clint], whitelistNodes:whitelist, blacklistNodes:[clint]}) yield node return node",
                result -> {

                    List<Map<String, Object>> maps = Iterators.asList(result);
                    assertEquals(0, maps.size());
                });
    }

    @Test
    public void testWhitelistNodes() {
        db.execute("MATCH (c:Person) WHERE c.name in ['Clint Eastwood', 'Gene Hackman'] SET c:Western");

        TestUtil.testResult(db,
                "MATCH (k:Person {name:'Keanu Reeves'}), (gene:Person {name:'Gene Hackman'}), (clint:Person {name:'Clint Eastwood'}), (unforgiven:Movie{title:'Unforgiven'}),  (replacements:Movie{title:'The Replacements'})\n" +
                        "WITH k, clint, gene, [k, gene, clint, unforgiven, replacements] as whitelist\n" +
                        "CALL apoc.path.subgraphNodes(k, {relationshipFilter:'ACTED_IN|PRODUCED|DIRECTED', endNodes:[clint, gene], whitelistNodes:whitelist}) yield node return node",
                result -> {

                    List<Map<String, Object>> maps = Iterators.asList(result);
                    assertEquals(2, maps.size());
                    Node node = (Node) maps.get(0).get("node");
                    assertEquals("Gene Hackman", node.getProperty("name"));
                    node = (Node) maps.get(1).get("node");
                    assertEquals("Clint Eastwood", node.getProperty("name"));
                });
    }

    @Test
    public void testWhitelistNodesIncludesEndNodes() {
        db.execute("MATCH (c:Person) WHERE c.name in ['Clint Eastwood', 'Gene Hackman'] SET c:Western");

        TestUtil.testResult(db,
                "MATCH (k:Person {name:'Keanu Reeves'}), (gene:Person {name:'Gene Hackman'}), (clint:Person {name:'Clint Eastwood'}), (unforgiven:Movie{title:'Unforgiven'}),  (replacements:Movie{title:'The Replacements'})\n" +
                        "WITH k, clint, gene, [k, gene, unforgiven, replacements] as whitelist\n" +
                        "CALL apoc.path.subgraphNodes(k, {relationshipFilter:'ACTED_IN|PRODUCED|DIRECTED', endNodes:[clint, gene], whitelistNodes:whitelist}) yield node return node",
                result -> {

                    List<Map<String, Object>> maps = Iterators.asList(result);
                    assertEquals(2, maps.size());
                    Node node = (Node) maps.get(0).get("node");
                    assertEquals("Gene Hackman", node.getProperty("name"));
                    node = (Node) maps.get(1).get("node");
                    assertEquals("Clint Eastwood", node.getProperty("name"));
                });
    }

    @Test
    public void testWhitelistNodesIncludesTerminatorNodes() {
        db.execute("MATCH (c:Person) WHERE c.name in ['Clint Eastwood', 'Gene Hackman'] SET c:Western");

        TestUtil.testResult(db,
                "MATCH (k:Person {name:'Keanu Reeves'}), (gene:Person {name:'Gene Hackman'}), (clint:Person {name:'Clint Eastwood'}), (unforgiven:Movie{title:'Unforgiven'}),  (replacements:Movie{title:'The Replacements'}) \n" +
                        "WITH k, clint, gene, [k, gene, unforgiven, replacements] as whitelist \n" +
                        "CALL apoc.path.subgraphNodes(k, {relationshipFilter:'ACTED_IN|PRODUCED|DIRECTED', terminatorNodes:[clint], whitelistNodes:whitelist}) yield node return node",
                result -> {

                    List<Map<String, Object>> maps = Iterators.asList(result);
                    assertEquals(1, maps.size());
                    Node node = (Node) maps.get(0).get("node");
                    assertEquals("Clint Eastwood", node.getProperty("name"));
                });
    }

    @Test
    public void testWhitelistNodesAndLabelFiltersMustAgreeToInclude1() {
        db.execute("MATCH (c:Person) WHERE c.name in ['Clint Eastwood', 'Gene Hackman'] SET c:Western");

        TestUtil.testResult(db,
                "MATCH (k:Person {name:'Keanu Reeves'}), (replacements:Movie{title:'The Replacements'}) \n" +
                        "WITH k, [k, replacements] as whitelist \n" +
                        "CALL apoc.path.subgraphNodes(k, {relationshipFilter:'ACTED_IN|PRODUCED|DIRECTED', labelFilter:'+Person', whitelistNodes:whitelist}) yield node return node",
                result -> {

                    List<Map<String, Object>> maps = Iterators.asList(result);
                    assertEquals(1, maps.size());
                    Node node = (Node) maps.get(0).get("node");
                    assertEquals("Keanu Reeves", node.getProperty("name"));
                });
    }

    @Test
    public void testWhitelistNodesAndLabelFiltersMustAgreeToInclude2() {
        db.execute("MATCH (c:Person) WHERE c.name in ['Clint Eastwood', 'Gene Hackman'] SET c:Western");

        TestUtil.testResult(db,
                "MATCH (k:Person {name:'Keanu Reeves'}), (replacements:Movie{title:'The Replacements'}) \n" +
                        "WITH k, [k] as whitelist \n" +
                        "CALL apoc.path.subgraphNodes(k, {relationshipFilter:'ACTED_IN|PRODUCED|DIRECTED', labelFilter:'+Person|+Movie', whitelistNodes:whitelist}) yield node return node",
                result -> {

                    List<Map<String, Object>> maps = Iterators.asList(result);
                    assertEquals(1, maps.size());
                    Node node = (Node) maps.get(0).get("node");
                    assertEquals("Keanu Reeves", node.getProperty("name"));
                });
    }
}
