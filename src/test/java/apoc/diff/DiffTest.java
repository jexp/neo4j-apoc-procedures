package apoc.diff;

import apoc.util.TestUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.test.TestGraphDatabaseFactory;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * @author Benjamin Clauss
 * @since 15.06.2018
 */
public class DiffTest {

    private static Node node1;
    private static Node node2;
    private static Node node3;

    private static GraphDatabaseService db;

    @BeforeClass
    public static void setup() throws Exception {
        db = new TestGraphDatabaseFactory().newImpermanentDatabase();
        TestUtil.registerProcedure(db, Diff.class);

        try (Transaction tx = db.beginTx()) {
            node1 = db.createNode();
            node1.setProperty("prop1", "val1");
            node1.setProperty("prop2", 2L);

            node2 = db.createNode();
            node2.setProperty("prop1", "val1");
            node2.setProperty("prop2", 2L);
            node2.setProperty("prop4", "four");

            node3 = db.createNode();
            node3.setProperty("prop1", "val1");
            node3.setProperty("prop3", "3");
            node3.setProperty("prop4", "for");
            tx.success();
        }
    }

    @Test
    public void nodesSame() {
        Map<String, Object> params = new HashMap<>();
        params.put("leftNode", node1);
        params.put("rightNode", node1);

        Map<String, Object> result =
                (Map<String, Object>) db.execute(
                        "RETURN apoc.diff.nodes($leftNode, $rightNode) as diff", params).next().get("diff");

        assertNotNull(result);

        HashMap<String, Object> leftOnly = (HashMap<String, Object>) result.get("leftOnly");
        assertTrue(leftOnly.isEmpty());

        HashMap<String, Object> rightOnly = (HashMap<String, Object>) result.get("rightOnly");
        assertTrue(rightOnly.isEmpty());

        HashMap<String, Object> different = (HashMap<String, Object>) result.get("different");
        assertTrue(different.isEmpty());

        HashMap<String, Object> inCommon = (HashMap<String, Object>) result.get("inCommon");
        assertEquals(2, inCommon.size());
        assertEquals("val1", inCommon.get("prop1"));
        assertEquals(2L, inCommon.get("prop2"));
    }

    @Test
    public void nodesDiffering() {
        Map<String, Object> params = new HashMap<>();
        params.put("leftNode", node2);
        params.put("rightNode", node3);
        Map<String, Object> result =
                (Map<String, Object>) db.execute(
                        "RETURN apoc.diff.nodes($leftNode, $rightNode) as diff", params).next().get("diff");

        assertNotNull(result);

        HashMap<String, Object> leftOnly = (HashMap<String, Object>) result.get("leftOnly");
        assertEquals(1, leftOnly.size());
        assertEquals(2L, leftOnly.get("prop2"));

        HashMap<String, Object> rightOnly = (HashMap<String, Object>) result.get("rightOnly");
        assertEquals(1, rightOnly.size());
        assertEquals("3", rightOnly.get("prop3"));

        HashMap<String, HashMap<String, Object>> different = (HashMap<String, HashMap<String, Object>>) result.get("different");
        assertEquals(1, different.size());
        HashMap<String, Object> pairs = different.get("prop4");
        assertEquals("four", pairs.get("left"));
        assertEquals("for", pairs.get("right"));

        HashMap<String, Object> inCommon = (HashMap<String, Object>) result.get("inCommon");
        assertEquals(1, inCommon.size());
        assertEquals("val1", inCommon.get("prop1"));
    }

    @AfterClass
    public static void tearDown() {
        db.shutdown();
    }
}
