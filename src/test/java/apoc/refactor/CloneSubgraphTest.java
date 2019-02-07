package apoc.refactor;

import apoc.algo.Cover;
import apoc.coll.Coll;
import apoc.map.Maps;
import apoc.meta.Meta;
import apoc.path.PathExplorer;
import apoc.util.TestUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.test.TestGraphDatabaseFactory;

import java.util.Collections;
import java.util.List;

import static apoc.util.MapUtil.map;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;

public class CloneSubgraphTest {
    private GraphDatabaseService db;

    @Rule
    public ExpectedException exceptionGrabber = ExpectedException.none();

    @Before
    public void setUp() throws Exception {
        db = new TestGraphDatabaseFactory().newImpermanentDatabase();
        TestUtil.registerProcedure(db, GraphRefactoring.class, Coll.class, PathExplorer.class, Cover.class, Meta.class, Maps.class); // helpful for matching to subgraphs

        // tree structure, testing clone of branches and reanchorings
        db.execute("CREATE (rA:Root{name:'A'}), \n" +
                "(rB:Root{name:'B'}),\n" +
                "(n1:Node{name:'node1', id:1}),\n" +
                "(n2:Node{name:'node2', id:2}),\n" +
                "(n3:Node{name:'node3', id:3}),\n" +
                "(n4:Node{name:'node4', id:4}),\n" +
                "(n5:Node:Oddball{name:'node5', id:5}),\n" +
                "(n6:Node{name:'node6', id:6}),\n" +
                "(n7:Node{name:'node7', id:7}),\n" +
                "(n8:Node{name:'node8', id:8}),\n" +
                "(n9:Node{name:'node9', id:9}),\n" +
                "(n10:Node{name:'node10', id:10}),\n" +
                "(n11:Node{name:'node11', id:11}),\n" +
                "(n12:Node{name:'node12', id:12})\n" + // 12 on its own
                "CREATE (rA)-[:LINK]->(n1)-[:LINK]->(n2)-[:LINK]->(n3)-[:LINK]->(n4)\n" +
                "CREATE               (n1)-[:LINK]->(n5)-[:LINK]->(n6)<-[:LINK]-(n7)\n" +
                "CREATE                             (n5)-[:LINK]->(n8)\n" +
                "CREATE                             (n5)-[:LINK]->(n9)-[:DIFFERENT_LINK]->(n10)\n" +
                "CREATE (rB)-[:LINK]->(n11)");
    }

    @After
    public void tearDown() {
        db.shutdown();
    }

    @Test
    public void testCloneSubgraph_From_RootA_Without_Rels_Should_Only_Clone_Nodes()  {

        TestUtil.testCall(db,
                "MATCH (root:Root{name:'A'})-[*]-(node) " +
                        "WITH collect(DISTINCT node) as nodes " +
                        "CALL apoc.refactor.cloneSubgraph(nodes, []) YIELD input, output, error " +
                        "WITH collect(output) as clones, collect(output.name) as cloneNames " +
                        "RETURN cloneNames, size(cloneNames) as cloneCount, none(clone in clones WHERE (clone)--()) as noRelationshipsOnClones, " +
                        " single(clone in clones WHERE clone.name = 'node5' AND clone:Oddball) as oddballNode5Exists, " +
                        " size([clone in clones WHERE clone:Node]) as nodesWithNodeLabel",
                (row) -> {
                    assertThat((List<String>) row.get("cloneNames"), containsInAnyOrder("node1", "node2", "node3", "node4", "node5", "node8", "node9", "node10", "node6", "node7"));
                    assertThat(row.get("cloneCount"), is(10L));
                    assertThat(row.get("noRelationshipsOnClones"), is(true));
                    assertThat(row.get("oddballNode5Exists"), is(true));
                    assertThat(row.get("nodesWithNodeLabel"), is(10L));
                }
        );

        TestUtil.testCall(db,
                "MATCH (node:Node) " +
                        "WHERE 0 < node.id < 15 " +
                        "RETURN count(node) as nodesWithId",
                (row) -> {
                    assertThat(row.get("nodesWithId"), is(22L)); // 12 original nodes + 10 clones
                }
        );
    }

    @Test
    public void testCloneSubgraph_From_RootA_Should_Only_Include_Rels_Between_Clones()  {
        TestUtil.testCall(db,
                "MATCH (rootA:Root{name:'A'})" +
                        "CALL apoc.path.subgraphAll(rootA, {}) YIELD nodes, relationships " +
                        "WITH nodes[1..] as nodes, relationships, [rel in relationships | startNode(rel).name + ' ' + type(rel) + ' ' + endNode(rel).name] as relNames " +
                        "CALL apoc.refactor.cloneSubgraph(nodes, relationships) YIELD input, output, error " +
                        "WITH relNames, collect(output) as clones, collect(output.name) as cloneNames " +
                        "CALL apoc.algo.cover(clones) YIELD rel " +
                        "WITH relNames, cloneNames, [rel in collect(rel) | startNode(rel).name + ' ' + type(rel) + ' ' + endNode(rel).name] as cloneRelNames " + // was seeing odd incorrect behavior with yielded relTypesCount from apoc.meta.stats()
                        "WITH cloneNames, cloneRelNames, apoc.coll.containsAll(relNames, cloneRelNames) as clonedRelsVerified " +
                        "RETURN cloneNames, cloneRelNames, clonedRelsVerified",
                (row) -> {
                    assertThat((List<String>) row.get("cloneNames"), containsInAnyOrder("node1", "node2", "node3", "node4", "node5", "node8", "node9", "node10", "node6", "node7"));
                    assertThat((List<String>) row.get("cloneRelNames"), containsInAnyOrder("node1 LINK node5", "node1 LINK node2", "node2 LINK node3", "node3 LINK node4", "node5 LINK node6", "node7 LINK node6", "node5 LINK node8", "node5 LINK node9", "node9 DIFFERENT_LINK node10"));
                    assertThat(row.get("clonedRelsVerified"), is(true));
                }
        );

        TestUtil.testCall(db,
                "CALL apoc.meta.stats() YIELD nodeCount, relCount, labels, relTypes as relTypesMap " +
                        "CALL db.relationshipTypes() YIELD relationshipType " +
                        "WITH nodeCount, relCount, labels, collect([relationshipType, relTypesMap['()-[:' + relationshipType + ']->()']]) as relationshipTypesColl " +
                        "RETURN nodeCount, relCount, labels, apoc.map.fromPairs(relationshipTypesColl) as relTypesCount ",
                (row) -> {
                    assertThat(row.get("nodeCount"), is(24L)); // original was 14, 10 nodes cloned
                    assertThat(row.get("relCount"), is(20L)); // original was 11, 9 relationships cloned
                    assertThat(row.get("labels"), equalTo(map("Root", 2L, "Oddball", 2L, "Node", 22L)));
                    assertThat(row.get("relTypesCount"), equalTo(map("LINK", 18L, "DIFFERENT_LINK", 2L)));
                }
        );
    }

    @Test
    public void testCloneSubgraph_Reanchoring_From_RootA_Should_Have_RootB()  {
        TestUtil.testCall(db,
                "MATCH (rootA:Root{name:'A'}), (rootB:Root{name:'B'}) " +
                        "CALL apoc.path.subgraphAll(rootA, {}) YIELD nodes, relationships " +
                        "WITH rootA, rootB, nodes, relationships, [rel in relationships | startNode(rel).name + ' ' + type(rel) + ' ' + endNode(rel).name] as relNames " +
                        "CALL apoc.refactor.cloneSubgraph(nodes, relationships, [[rootA, rootB]]) YIELD input, output, error " +
                        "WITH relNames, collect(output) as clones, collect(output.name) as cloneNames " +
                        "CALL apoc.algo.cover(clones) YIELD rel " +
                        "WITH relNames, cloneNames, [rel in collect(rel) | startNode(rel).name + ' ' + type(rel) + ' ' + endNode(rel).name] as cloneRelNames " +
                        "WITH cloneNames, cloneRelNames, apoc.coll.containsAll(relNames, cloneRelNames) as clonedRelsVerified " +
                        "RETURN cloneNames, cloneRelNames, clonedRelsVerified",
                (row) -> {
                    assertThat((List<String>) row.get("cloneNames"), containsInAnyOrder("node1", "node2", "node3", "node4", "node5", "node8", "node9", "node10", "node6", "node7"));
                    assertThat((List<String>) row.get("cloneRelNames"), containsInAnyOrder("node1 LINK node5", "node1 LINK node2", "node2 LINK node3", "node3 LINK node4", "node5 LINK node6", "node7 LINK node6", "node5 LINK node8", "node5 LINK node9", "node9 DIFFERENT_LINK node10"));
                    assertThat(row.get("clonedRelsVerified"), is(true));
                }
        );

        TestUtil.testCall(db,
                "MATCH (:Root{name:'B'})-[:LINK]->(node:Node) " +
                        "RETURN collect(node.name) as bLinkedNodeNames",
                (row) -> {
                    assertThat((List<String>) row.get("bLinkedNodeNames"), containsInAnyOrder("node1", "node11"));
                    assertThat(((List<String>) row.get("bLinkedNodeNames")).size(), is(2));
                }
        );

        TestUtil.testCall(db,
                "CALL apoc.meta.stats() YIELD nodeCount, relCount, labels, relTypes as relTypesMap " +
                        "CALL db.relationshipTypes() YIELD relationshipType " +
                        "WITH nodeCount, relCount, labels, collect([relationshipType, relTypesMap['()-[:' + relationshipType + ']->()']]) as relationshipTypesColl " +
                        "RETURN nodeCount, relCount, labels, apoc.map.fromPairs(relationshipTypesColl) as relTypesCount ",
                (row) -> {
                    assertThat(row.get("nodeCount"), is(24L)); // original was 14, 10 nodes cloned
                    assertThat(row.get("relCount"), is(21L)); // original was 11, 10 relationships cloned
                    assertThat(row.get("labels"), equalTo(map("Root", 2L, "Oddball", 2L, "Node", 22L)));
                    assertThat(row.get("relTypesCount"), equalTo(map("LINK", 19L, "DIFFERENT_LINK", 2L)));
                }
        );
    }

    @Test
    public void testCloneSubgraph_Reanchoring_From_Node1_Should_Have_RootB()  {
        TestUtil.testCall(db,
                "MATCH (rootA:Root{name:'A'})--(node1), (rootB:Root{name:'B'}) " +
                        "CALL apoc.path.subgraphAll(node1, {blacklistNodes:[rootA]}) YIELD nodes, relationships " +
                        "WITH node1, rootB, nodes, relationships, [rel in relationships | startNode(rel).name + ' ' + type(rel) + ' ' + endNode(rel).name] as relNames " +
                        "CALL apoc.refactor.cloneSubgraph(nodes, relationships, [[node1, rootB]]) YIELD input, output, error " +
                        "WITH relNames, collect(output) as clones, collect(output.name) as cloneNames " +
                        "CALL apoc.algo.cover(clones) YIELD rel " +
                        "WITH relNames, cloneNames, [rel in collect(rel) | startNode(rel).name + ' ' + type(rel) + ' ' + endNode(rel).name] as cloneRelNames " +
                        "WITH cloneNames, cloneRelNames, apoc.coll.containsAll(relNames, cloneRelNames) as clonedRelsVerified " +
                        "RETURN cloneNames, cloneRelNames, clonedRelsVerified",
                (row) -> {
                    assertThat((List<String>) row.get("cloneNames"), containsInAnyOrder("node2", "node3", "node4", "node5", "node8", "node9", "node10", "node6", "node7"));
                    assertThat((List<String>) row.get("cloneRelNames"), containsInAnyOrder("node2 LINK node3", "node3 LINK node4", "node5 LINK node6", "node7 LINK node6", "node5 LINK node8", "node5 LINK node9", "node9 DIFFERENT_LINK node10"));
                    assertThat(row.get("clonedRelsVerified"), is(true));
                }
        );

        TestUtil.testCall(db,
                "MATCH (:Root{name:'B'})-[:LINK]->(node:Node) " +
                        "RETURN collect(node.name) as bLinkedNodeNames",
                (row) -> {
                    assertThat((List<String>) row.get("bLinkedNodeNames"), containsInAnyOrder("node5", "node2", "node11"));
                    assertThat(((List<String>) row.get("bLinkedNodeNames")).size(), is(3));
                }
        );

        TestUtil.testCall(db,
                "CALL apoc.meta.stats() YIELD nodeCount, relCount, labels, relTypes as relTypesMap " +
                        "CALL db.relationshipTypes() YIELD relationshipType " +
                        "WITH nodeCount, relCount, labels, collect([relationshipType, relTypesMap['()-[:' + relationshipType + ']->()']]) as relationshipTypesColl " +
                        "RETURN nodeCount, relCount, labels, apoc.map.fromPairs(relationshipTypesColl) as relTypesCount ",
                (row) -> {
                    assertThat(row.get("nodeCount"), is(23L)); // original was 14, 9 nodes cloned
                    assertThat(row.get("relCount"), is(20L)); // original was 11, 9 relationships cloned
                    assertThat(row.get("labels"), equalTo(map("Root", 2L, "Oddball", 2L, "Node", 21L)));
                    assertThat(row.get("relTypesCount"), equalTo(map("LINK", 18L, "DIFFERENT_LINK", 2L)));
                }
        );
    }

    @Test
    public void testCloneSubgraph_Reanchoringing_From_RootA_With_Skipped_Properties_Should_Not_Include_Skipped_Properties()  {
        TestUtil.testCall(db,
                "MATCH (rootA:Root{name:'A'}), (rootB:Root{name:'B'}) " +
                        "CALL apoc.path.subgraphAll(rootA, {}) YIELD nodes, relationships " +
                        "WITH rootA, rootB, nodes, relationships, [rel in relationships | startNode(rel).name + ' ' + type(rel) + ' ' + endNode(rel).name] as relNames " +
                        "CALL apoc.refactor.cloneSubgraph(nodes, relationships, [[rootA, rootB]], ['id']) YIELD input, output, error " +
                        "WITH relNames, collect(output) as clones, collect(output.name) as cloneNames " +
                        "CALL apoc.algo.cover(clones) YIELD rel " +
                        "WITH relNames, cloneNames, [rel in collect(rel) | startNode(rel).name + ' ' + type(rel) + ' ' + endNode(rel).name] as cloneRelNames " +
                        "WITH cloneNames, cloneRelNames, apoc.coll.containsAll(relNames, cloneRelNames) as clonedRelsVerified " +
                        "RETURN cloneNames, cloneRelNames, clonedRelsVerified",
                (row) -> {
                    assertThat((List<String>) row.get("cloneNames"), containsInAnyOrder("node1", "node2", "node3", "node4", "node5", "node8", "node9", "node10", "node6", "node7"));
                    assertThat((List<String>) row.get("cloneRelNames"), containsInAnyOrder("node1 LINK node5", "node1 LINK node2", "node2 LINK node3", "node3 LINK node4", "node5 LINK node6", "node7 LINK node6", "node5 LINK node8", "node5 LINK node9", "node9 DIFFERENT_LINK node10"));
                    assertThat(row.get("clonedRelsVerified"), is(true));
                }
        );

        TestUtil.testCall(db,
                "MATCH (:Root{name:'B'})-[:LINK]->(node:Node) " +
                        "RETURN collect(node.name) as bLinkedNodeNames",
                (row) -> {
                    assertThat((List<String>) row.get("bLinkedNodeNames"), containsInAnyOrder("node1", "node11"));
                    assertThat(((List<String>) row.get("bLinkedNodeNames")).size(), is(2));
                }
        );

        TestUtil.testCall(db,
                "CALL apoc.meta.stats() YIELD nodeCount, relCount, labels, relTypes as relTypesMap " +
                        "CALL db.relationshipTypes() YIELD relationshipType " +
                        "WITH nodeCount, relCount, labels, collect([relationshipType, relTypesMap['()-[:' + relationshipType + ']->()']]) as relationshipTypesColl " +
                        "RETURN nodeCount, relCount, labels, apoc.map.fromPairs(relationshipTypesColl) as relTypesCount ",
                (row) -> {
                    assertThat(row.get("nodeCount"), is(24L)); // original was 14, 10 nodes cloned
                    assertThat(row.get("relCount"), is(21L)); // original was 11, 10 relationships cloned
                    assertThat(row.get("labels"), equalTo(map("Root", 2L, "Oddball", 2L, "Node", 22L)));
                    assertThat(row.get("relTypesCount"), equalTo(map("LINK", 19L, "DIFFERENT_LINK", 2L)));
                }
        );

        TestUtil.testCall(db,
                "MATCH (node:Node) " +
                        "WHERE 0 < node.id < 15 " +
                        "RETURN count(node) as nodesWithId",
                (row) -> {
                    assertThat(row.get("nodesWithId"), is(12L)); // 12 original nodes + 10 clones
                }
        );
    }

    @Test
    public void testCloneSubgraph_Reanchoring_From_RootA_And_Oddball_Should_Have_RootB_And_Use_Node_12_In_Place_Of_Oddball()  {
        TestUtil.testCall(db,
                "MATCH (rootA:Root{name:'A'}), (rootB:Root{name:'B'}), (node12:Node{name:'node12'}), (oddball:Oddball) " +
                        "CALL apoc.path.subgraphAll(rootA, {}) YIELD nodes, relationships " +
                        "WITH rootA, rootB, node12, oddball, nodes, relationships, [rel in relationships | startNode(rel).name + ' ' + type(rel) + ' ' + endNode(rel).name] as relNames " +
                        "CALL apoc.refactor.cloneSubgraph(nodes, relationships, [[rootA, rootB], [oddball, node12]]) YIELD input, output, error " +
                        "WITH relNames, collect(output) as clones, collect(output.name) as cloneNames " +
                        "CALL apoc.algo.cover(clones) YIELD rel " +
                        "WITH relNames, cloneNames, [rel in collect(rel) | startNode(rel).name + ' ' + type(rel) + ' ' + endNode(rel).name] as cloneRelNames " +
                        "WITH cloneNames, cloneRelNames, apoc.coll.containsAll(relNames, cloneRelNames) as clonedRelsVerified " +
                        "RETURN cloneNames, cloneRelNames, clonedRelsVerified",
                (row) -> {
                    assertThat((List<String>) row.get("cloneNames"), containsInAnyOrder("node1", "node2", "node3", "node4", "node8", "node9", "node10", "node6", "node7"));
                    assertThat((List<String>) row.get("cloneRelNames"), containsInAnyOrder("node1 LINK node2", "node2 LINK node3", "node3 LINK node4", "node7 LINK node6", "node9 DIFFERENT_LINK node10"));
                    assertThat(row.get("clonedRelsVerified"), is(true));
                }
        );

        TestUtil.testCall(db,
                "MATCH (:Root{name:'B'})-[:LINK]->(node:Node) " +
                        "RETURN collect(node.name) as bLinkedNodeNames",
                (row) -> {
                    assertThat((List<String>) row.get("bLinkedNodeNames"), containsInAnyOrder("node1", "node11"));
                    assertThat(((List<String>) row.get("bLinkedNodeNames")).size(), is(2));
                }
        );



        TestUtil.testCall(db,
                "MATCH (node12:Node{name:'node12'})-[:LINK]-(node:Node) " +
                        "RETURN collect(node.name) as node12LinkedNodeNames",
                (row) -> {
                    assertThat((List<String>) row.get("node12LinkedNodeNames"), containsInAnyOrder("node1", "node9", "node8", "node6"));
                    assertThat(((List<String>) row.get("node12LinkedNodeNames")).size(), is(4));
                }
        );

        TestUtil.testCall(db,
                "CALL apoc.meta.stats() YIELD nodeCount, relCount, labels, relTypes as relTypesMap " +
                        "CALL db.relationshipTypes() YIELD relationshipType " +
                        "WITH nodeCount, relCount, labels, collect([relationshipType, relTypesMap['()-[:' + relationshipType + ']->()']]) as relationshipTypesColl " +
                        "RETURN nodeCount, relCount, labels, apoc.map.fromPairs(relationshipTypesColl) as relTypesCount ",
                (row) -> {
                    assertThat(row.get("nodeCount"), is(23L)); // original was 14, 9 nodes cloned
                    assertThat(row.get("relCount"), is(21L)); // original was 11, 10 relationships cloned
                    assertThat(row.get("labels"), equalTo(map("Root", 2L, "Oddball", 1L, "Node", 21L)));
                    assertThat(row.get("relTypesCount"), equalTo(map("LINK", 19L, "DIFFERENT_LINK", 2L)));
                }
        );
    }

    @Test
    public void testCloneSubgraph_With_Rels_Not_Between_Provided_Nodes_Or_Reanchors_Should_Be_Ignored()  {
        TestUtil.testCall(db,
                "MATCH (rootA:Root{name:'A'}), (rootB:Root{name:'B'}) " +
                        "CALL apoc.path.subgraphAll(rootA, {relationshipFilter:'LINK>'}) YIELD nodes " +
                        "WITH rootA, rootB, nodes, [(:Node{name:'node7'})-[r]->() | r] + [(:Node{name:'node9'})-[r:DIFFERENT_LINK]->() | r] as relationships " + // just an opposite-direction :LINK and the :DIFFERENT_LINK rels
                        "CALL apoc.refactor.cloneSubgraph(nodes, relationships, [[rootA, rootB]]) YIELD input, output, error " +
                        "RETURN collect(output.name) as cloneNames",
                (row) -> {
                    assertThat((List<String>) row.get("cloneNames"), containsInAnyOrder("node1", "node2", "node3", "node4", "node5", "node8", "node9", "node6"));
                }
        );

        TestUtil.testCall(db,
                "MATCH (:Root{name:'B'})-[:LINK]->(node:Node) " +
                        "RETURN collect(node.name) as bLinkedNodeNames",
                (row) -> {
                    assertThat((List<String>) row.get("bLinkedNodeNames"), containsInAnyOrder("node11"));
                    assertThat(((List<String>) row.get("bLinkedNodeNames")).size(), is(1));
                }
        );

        TestUtil.testCall(db,
                "CALL apoc.meta.stats() YIELD nodeCount, relCount, labels, relTypes as relTypesMap " +
                        "CALL db.relationshipTypes() YIELD relationshipType " +
                        "WITH nodeCount, relCount, labels, collect([relationshipType, relTypesMap['()-[:' + relationshipType + ']->()']]) as relationshipTypesColl " +
                        "RETURN nodeCount, relCount, labels, apoc.map.fromPairs(relationshipTypesColl) as relTypesCount ",
                (row) -> {
                    assertThat(row.get("nodeCount"), is(22L)); // original was 14, 8 nodes cloned
                    assertThat(row.get("relCount"), is(11L)); // original was 11, 0 relationships cloned
                    assertThat(row.get("labels"), equalTo(map("Root", 2L, "Oddball", 2L, "Node", 20L)));
                    assertThat(row.get("relTypesCount"), equalTo(map("LINK", 10L, "DIFFERENT_LINK", 1L)));
                }
        );
    }

    @Test
    public void testCloneSubgraph_With_No_Nodes_But_With_Rels_And_Reanchors_Should_Do_Nothing()  {
        TestUtil.testCallEmpty(db,
                "MATCH (rootA:Root{name:'A'}), (rootB:Root{name:'B'}) " +
                        "CALL apoc.path.subgraphAll(rootA, {}) YIELD relationships " +
                        "WITH rootA, rootB, relationships, [rel in relationships | startNode(rel).name + ' ' + type(rel) + ' ' + endNode(rel).name] as relNames " +
                        "CALL apoc.refactor.cloneSubgraph([], relationships, [[rootA, rootB]]) YIELD input, output, error " +
                        "WITH relNames, collect(output) as clones, collect(output.name) as cloneNames " +
                        "CALL apoc.algo.cover(clones) YIELD rel " +
                        "WITH relNames, cloneNames, [rel in collect(rel) | startNode(rel).name + ' ' + type(rel) + ' ' + endNode(rel).name] as cloneRelNames " +
                        "WITH cloneNames, cloneRelNames, apoc.coll.containsAll(relNames, cloneRelNames) as clonedRelsVerified " +
                        "RETURN cloneNames, cloneRelNames, clonedRelsVerified", Collections.emptyMap()
        );
    }

    @Test
    public void testCloneSubgraph_With_A_1_Element_Reanchor_Pair_Should_Throw_Exception()  {
        exceptionGrabber.expectMessage("\'reanchors\' must be a list of node pairs");

        TestUtil.testCall(db,
                "MATCH (root:Root{name:'A'})-[*]-(node) " +
                        "WITH root, collect(DISTINCT node) as nodes " +
                        "CALL apoc.refactor.cloneSubgraph(nodes, [], [[root]]) YIELD input, output, error " +
                        "WITH collect(output) as clones, collect(output.name) as cloneNames " +
                        "RETURN cloneNames, size(cloneNames) as cloneCount, none(clone in clones WHERE (clone)--()) as noRelationshipsOnClones, " +
                        " single(clone in clones WHERE clone.name = 'node5' AND clone:Oddball) as oddballNode5Exists, " +
                        " size([clone in clones WHERE clone:Node]) as nodesWithNodeLabel",
                (row) -> {
                    assertThat((List<String>) row.get("cloneNames"), containsInAnyOrder("node1", "node2", "node3", "node4", "node5", "node8", "node9", "node10", "node6", "node7"));
                    assertThat(row.get("cloneCount"), is(10L));
                    assertThat(row.get("noRelationshipsOnClones"), is(true));
                    assertThat(row.get("oddballNode5Exists"), is(true));
                    assertThat(row.get("nodesWithNodeLabel"), is(10L));
                }
        );
    }

    @Test
    public void testCloneSubgraph_With_A_3_Element_Reanchor_Pair_Should_Throw_Exception()  {
        exceptionGrabber.expectMessage("\'reanchors\' must be a list of node pairs");

        TestUtil.testCall(db,
                "MATCH (root:Root{name:'A'})-[*]-(node) " +
                        "WITH root, collect(DISTINCT node) as nodes " +
                        "CALL apoc.refactor.cloneSubgraph(nodes, [], [[root, root, root]]) YIELD input, output, error " +
                        "WITH collect(output) as clones, collect(output.name) as cloneNames " +
                        "RETURN cloneNames, size(cloneNames) as cloneCount, none(clone in clones WHERE (clone)--()) as noRelationshipsOnClones, " +
                        " single(clone in clones WHERE clone.name = 'node5' AND clone:Oddball) as oddballNode5Exists, " +
                        " size([clone in clones WHERE clone:Node]) as nodesWithNodeLabel",
                (row) -> {
                    assertThat((List<String>) row.get("cloneNames"), containsInAnyOrder("node1", "node2", "node3", "node4", "node5", "node8", "node9", "node10", "node6", "node7"));
                    assertThat(row.get("cloneCount"), is(10L));
                    assertThat(row.get("noRelationshipsOnClones"), is(true));
                    assertThat(row.get("oddballNode5Exists"), is(true));
                    assertThat(row.get("nodesWithNodeLabel"), is(10L));
                }
        );
    }

    @Test
    public void testCloneSubgraph_With_A_Null_Element_In_Reanchor_Pair_Should_Throw_Exception()  {
        exceptionGrabber.expectMessage("\'reanchors\' must be a list of node pairs");

        TestUtil.testCall(db,
                "MATCH (root:Root{name:'A'})-[*]-(node) " +
                        "WITH root, collect(DISTINCT node) as nodes " +
                        "CALL apoc.refactor.cloneSubgraph(nodes, [], [[root, null]]) YIELD input, output, error " +
                        "WITH collect(output) as clones, collect(output.name) as cloneNames " +
                        "RETURN cloneNames, size(cloneNames) as cloneCount, none(clone in clones WHERE (clone)--()) as noRelationshipsOnClones, " +
                        " single(clone in clones WHERE clone.name = 'node5' AND clone:Oddball) as oddballNode5Exists, " +
                        " size([clone in clones WHERE clone:Node]) as nodesWithNodeLabel",
                (row) -> {
                    assertThat((List<String>) row.get("cloneNames"), containsInAnyOrder("node1", "node2", "node3", "node4", "node5", "node8", "node9", "node10", "node6", "node7"));
                    assertThat(row.get("cloneCount"), is(10L));
                    assertThat(row.get("noRelationshipsOnClones"), is(true));
                    assertThat(row.get("oddballNode5Exists"), is(true));
                    assertThat(row.get("nodesWithNodeLabel"), is(10L));
                }
        );    }
}
