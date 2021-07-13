package apoc.refactor.rename;


import apoc.util.TestUtil;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static apoc.util.MapUtil.map;
import static apoc.util.TestUtil.testCall;
import static apoc.util.TestUtil.testResult;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * @author AgileLARUS
 *
 * @since 03-04-2017
 */
public class RenameTest {

	@Rule
	public DbmsRule db = new ImpermanentDbmsRule();

	@Before public void setUp() throws Exception {
		TestUtil.registerProcedure(db, Rename.class);
	}

	@Test
	public void testRenameLabelForSomeNodes() throws Exception {
		List<Node> nodes = TestUtil.firstColumn(db, "UNWIND range(0,9) as id CREATE (f:Foo {id: id, name: 'name'+id}) RETURN f");
		testCall(db, "CALL apoc.refactor.rename.label($oldName,$newName, $nodes)",
				map("oldName", "Foo", "newName", "Bar", "nodes", nodes.subList(0,3)), (r) -> {});

		assertEquals(3L, resultNodesMatches("Bar", null));
		assertEquals(7L, resultNodesMatches("Foo", null));
	}

	@Test
	public void testRenameLabel() throws Exception {
		db.executeTransactionally("UNWIND range(0,9) AS id CREATE (f:Foo {id: id})");
		testCall(db, "CALL apoc.refactor.rename.label($oldName,$newName)",
				map("oldName", "Foo", "newName", "Bar"), (r) -> {});

		assertEquals(10L, resultNodesMatches("Bar", null));
		assertEquals(0L, resultNodesMatches("Foo", null));
	}

	@Test
	public void testRenameRelationship() throws Exception {
		db.executeTransactionally("UNWIND range(0,9) AS id CREATE (f:Foo {id: id})-[:KNOWS {id: id}]->(l:Fii {id: id})");
		testCall(db, "CALL apoc.refactor.rename.type($oldType,$newType)",
				map("oldType", "KNOWS", "newType", "LOVES"), (r) -> {});

		assertEquals(10L, resultRelationshipsMatches("LOVES", null));
		assertEquals(0L, resultRelationshipsMatches("KNOWS", null));
	}

	@Test
	public void testRenameTypeForSomeRelationships() throws Exception {
		db.executeTransactionally("UNWIND range(0,9) AS id CREATE (f:Foo {id: id})-[:KNOWS {id: id}]->(l:Fii {id: id})");

		List<Relationship> rels = TestUtil.firstColumn(db, "MATCH (:Foo)-[r:KNOWS]->(:Fii) RETURN r LIMIT 2");
		testCall(db, "CALL apoc.refactor.rename.type($oldType,$newType, $rels)",
				map("oldType", "KNOWS", "newType", "LOVES", "rels", rels), (r) -> {
		});

		assertEquals(2L, resultRelationshipsMatches("LOVES", null));
		assertEquals(8L, resultRelationshipsMatches("KNOWS", null));
	}

	@Test
	public void testRenameNodesProperty() throws Exception {
		List<Node> nodes = TestUtil.firstColumn(db, "UNWIND range(0,9) as id CREATE (f:Foo {id: id, name: 'name'+id}) RETURN f");
		testCall(db, "CALL apoc.refactor.rename.nodeProperty($oldName,$newName)",
				map("oldName", "name", "newName", "surname"), (r) -> {});

		assertEquals(10L, resultNodesMatches(null, "surname"));
		assertEquals(0L, resultNodesMatches(null, "name"));
	}

	@Test
	public void testRenamePropertyForSomeNodes() throws Exception {
		List<Node> nodes = TestUtil.firstColumn(db, "UNWIND range(0,9) as id CREATE (f:Foo {id: id, name: 'name'+id}) RETURN f");
		db.executeTransactionally("Create constraint on (n:Foo) assert n.name is UNIQUE");
		testCall(db, "CALL apoc.refactor.rename.nodeProperty($oldName,$newName,$nodes)",
				map("oldName", "name", "newName", "surname","nodes",nodes.subList(0,3)), (r) -> {});

		assertEquals(3L, resultNodesMatches(null, "surname"));
		assertEquals(7L, resultNodesMatches(null, "name"));
	}

	@Test
	public void testRenameTypeProperty() throws Exception {
		db.executeTransactionally("UNWIND range(0,9) as id CREATE (f:Foo {id: id})-[:KNOWS {name: 'name' +id}]->(:Fii)");
		testCall(db, "CALL apoc.refactor.rename.typeProperty($oldName,$newName)",
				map("oldName", "name", "newName", "surname"), (r) -> {});

		assertEquals(10L, resultRelationshipsMatches(null, "surname"));
		assertEquals(0L, resultRelationshipsMatches(null, "name"));
	}

	@Test
	@Ignore("in 4.3 it should be hard to reproduce")
	public void testDeadlockException() {
		String query = "UNWIND RANGE(1, 100) AS ID \n" +
				"MERGE (account1:Account{ID: ID})\n" +
				"MERGE (account2:Account{ID: toInteger(rand() * 100)})\n" +
				"MERGE (account3:Account{ID: toInteger(rand() * 100)})\n" +
				"MERGE (account1)-[:SIMILAR_TO]->(account2)\n" +
				"MERGE (account1)-[:SIMILAR_TO]->(account3)\n" +
				"MERGE (account2)-[:SIMILAR_TO]->(account3)";
		db.executeTransactionally(query);

		String testQuery = "MATCH (:Account)-[r:SIMILAR_TO]->(:Account)\n" +
				"WITH COLLECT(r) as rs\n" +
				"CALL apoc.refactor.rename.type('SIMILAR_TO','SIMILAR_TO_'+rand(),rs, {batchSize:10}) YIELD committedOperations, batches, failedBatches, total, errorMessages, batch\n" +
				"RETURN committedOperations, batches, failedBatches, total, errorMessages, batch";
		testResult(db, testQuery, Collections.emptyMap(), (r) -> {
			final Map<String, Object> batch = r.<Map<String, Object>>columnAs("batch").next();
			final Map<String, Object> errors = (Map<String, Object>) batch.get("errors");
			assertFalse(errors.isEmpty());
		});

	}

	@Test
	public void testRenamePropertyForSomeRelationship() throws Exception {
		db.executeTransactionally("UNWIND range(0,9) AS id CREATE (f:Foo {id: id})-[:KNOWS {name: 'name' + id}]->(l:Fii {id: id})");
		List<Relationship> rels = TestUtil.firstColumn(db, "MATCH (:Foo)-[r:KNOWS]->(:Fii) RETURN r LIMIT 2");
		testCall(db, "CALL apoc.refactor.rename.typeProperty($oldName,$newName,$rels)",
				map("oldName", "name", "newName", "surname","rels",rels), (r) -> {});

		assertEquals(2L, resultRelationshipsMatches(null, "surname"));
		assertEquals(8L, resultRelationshipsMatches(null, "name"));
	}

	private long resultRelationshipsMatches(String type, String prop){
		String query = type != null ? "MATCH ()-[r:"+type+"]->() RETURN count(r) as countResult" : "match ()-[r]->() where exists (r."+prop+") return count(r) as countResult";
		return TestUtil.singleResultFirstColumn(db, query);
	}

	private long resultNodesMatches(String label, String prop) {
		String query = label != null ? "MATCH (b:"+label+") RETURN count(b) as countResult" : "match (n) where exists (n."+prop+") return count(n) as countResult";
		return TestUtil.singleResultFirstColumn(db, query);
	}

}
