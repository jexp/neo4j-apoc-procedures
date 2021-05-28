package apoc.coll;

import apoc.convert.Json;
import apoc.util.TestUtil;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.neo4j.graphdb.Node;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import java.util.*;

import static apoc.util.TestUtil.testCall;
import static apoc.util.TestUtil.testResult;
import static apoc.util.Util.map;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.*;
import static org.neo4j.internal.helpers.collection.Iterables.asSet;

public class CollTest {

    @ClassRule
    public static DbmsRule db = new ImpermanentDbmsRule();


    @BeforeClass public static void setUp() throws Exception {
        TestUtil.registerProcedure(db, Coll.class, Json.class);
    }

    @Test
    public void testRunningTotal() throws Exception {
        testCall(db, "RETURN apoc.coll.runningTotal([1,2,3,4,5.5,1]) as value",
                (row) -> assertEquals(asList(1L, 3L, 6L, 10L, 15.5D, 16.5D), row.get("value")));
    }

    @Test
    public void testStandardDeviation() throws Exception {
        testCall(db, "RETURN apoc.coll.stdev([10, 12, 23, 23, 16, 23, 21, 16]) as value",
                (row) -> assertEquals(5.237229365663817D, row.get("value")));

        testCall(db, "RETURN apoc.coll.stdev([10, 12, 23, 23, 16, 23, 21, 16], false) as value",
                (row) -> assertEquals(4.898979485566356D, row.get("value")));
        
        // conversion from double to long
        testCall(db, "RETURN apoc.coll.stdev([10, 12, 23]) as value",
                (row) -> assertEquals(7L, row.get("value")));
    }

    @Test
    public void testZip() throws Exception {
        testCall(db, "RETURN apoc.coll.zip([1,2,3],[4,5]) as value",
                (row) -> {
                    Object value = row.get("value");
                    List<List<Long>> expected = asList(asList(1L, 4L), asList(2L, 5L), asList(3L, null));
                    assertEquals(expected, value);
                });
    }

    @Test
    public void testPairs() throws Exception {
        testCall(db, "RETURN apoc.coll.pairs([1,2,3]) as value",
                (row) -> assertEquals(asList(asList(1L, 2L), asList(2L, 3L), asList(3L, null)), row.get("value")));
    }

    @Test
    public void testPairsMin() throws Exception {
        testCall(db, "RETURN apoc.coll.pairsMin([1,2,3]) as value",
                (row) -> assertEquals(asList(asList(1L, 2L), asList(2L, 3L)), row.get("value")));
    }

    @Test
    public void testPairsMinListResult() throws Exception {
        testCall(db, "RETURN apoc.coll.pairsMin([1,2,3])[0][0] as result",
                (row) -> assertEquals(1L, row.get("result")));
    }

    @Test
    public void testToSet() throws Exception {
        testCall(db, "RETURN apoc.coll.toSet([1,2,1,3]) as value",
                (row) -> assertEquals(asList(1L, 2L, 3L), row.get("value")));
    }

    @Test
    public void testSum() throws Exception {
        testCall(db, "RETURN apoc.coll.sum([1,2,3]) as value",
                (row) -> assertEquals(6D, row.get("value")));
    }

    @Test
    public void testAvg() throws Exception {
        testCall(db, "RETURN apoc.coll.avg([1.4,2,3.2]) as value",
                (row) -> assertEquals(2.2D, (double) row.get("value"), 0.1));
    }

    @Test
    public void testMin() throws Exception {
        testCall(db, "RETURN apoc.coll.min([1,2]) as value",
                (row) -> assertEquals(1L, row.get("value")));
        testCall(db, "RETURN apoc.coll.min([1,2,3]) as value",
                (row) -> assertEquals(1L, row.get("value")));
        testCall(db, "RETURN apoc.coll.min([0.5,1,2.3]) as value",
                (row) -> assertEquals(0.5D, row.get("value")));
    }

    @Test
    public void testMax() throws Exception {
        testCall(db, "RETURN apoc.coll.max([1,2,3]) as value",
                (row) -> assertEquals(3L, row.get("value")));
        testCall(db, "RETURN apoc.coll.max([0.5,1,2.3]) as value",
                (row) -> assertEquals(2.3D, row.get("value")));
    }

    @Test
    public void testMaxDate() throws Exception {
        testCall(db, "RETURN apoc.coll.max([date('2020-04-01'), date('2020-03-01')]) as value",
                (row) -> assertEquals("2020-04-01", row.get("value").toString()));
        testCall(db, "RETURN apoc.coll.max([datetime('2020-03-30T12:17:43.175Z'), datetime('2020-03-30T12:17:39.982Z')]) as value",
                (row) -> assertEquals("2020-03-30T12:17:43.175Z", row.get("value").toString()));
        testCall(db, "RETURN apoc.coll.max([null, datetime('2020-03-30T11:17:39.982Z'), datetime('2020-03-30T12:17:39.982Z'), datetime('2020-03-30T11:17:39.982Z')]) as value",
                (row) -> assertEquals("2020-03-30T12:17:39.982Z", row.get("value").toString()));
    }

    @Test
    public void testPartitionProcedure() throws Exception {
        testResult(db, "CALL apoc.coll.partition([1,2,3,4,5],2)",
                (result) -> {
                    Map<String, Object> row = result.next();
                    assertEquals(asList(1L, 2L), row.get("value"));
                    row = result.next();
                    assertEquals(asList(3L, 4L), row.get("value"));
                    row = result.next();
                    assertEquals(asList(5L), row.get("value"));
                    assertFalse(result.hasNext());
                });
    }

    @Test
    public void testPartitionFunction() throws Exception {
        testResult(db, "UNWIND apoc.coll.partition([1,2,3,4,5],2) AS value RETURN value",
                (result) -> {
                    Map<String, Object> row = result.next();
                    assertEquals(asList(1L, 2L), row.get("value"));
                    row = result.next();
                    assertEquals(asList(3L, 4L), row.get("value"));
                    row = result.next();
                    assertEquals(asList(5L), row.get("value"));
                    assertFalse(result.hasNext());
                });
    }

    @Test
    public void testSumLongs() throws Exception {
        testCall(db, "RETURN apoc.coll.sumLongs([1,2,3]) AS value",
                (row) -> assertEquals(6L, row.get("value")));
    }

    @Test
    public void testSort() throws Exception {
        testCall(db, "RETURN apoc.coll.sort([3,2,1]) as value",
                (row) -> assertEquals(asList(1L, 2L, 3L), row.get("value")));
    }

    @Test
    public void testIN() throws Exception {
        testCall(db, "RETURN apoc.coll.contains([1,2,3],1) AS value",
                (res) -> assertEquals(true, res.get("value")));
    }

    @Test
    public void testIndexOf() throws Exception {
        testCall(db, "RETURN apoc.coll.indexOf([1,2,3],1) AS value", r -> assertEquals(0L, r.get("value")));
        testCall(db, "RETURN apoc.coll.indexOf([1,2,3],2) AS value", r -> assertEquals(1L, r.get("value")));
        testCall(db, "RETURN apoc.coll.indexOf([1,2,3],3) AS value", r -> assertEquals(2L, r.get("value")));
        testCall(db, "RETURN apoc.coll.indexOf([1,2,3],4) AS value", r -> assertEquals(-1L, r.get("value")));
        testCall(db, "RETURN apoc.coll.indexOf([1,2,3],0) AS value", r -> assertEquals(-1L, r.get("value")));
        testCall(db, "RETURN apoc.coll.indexOf([1,2,3],null) AS value", r -> assertEquals(-1L, r.get("value")));
    }

    @Test
    public void testSplit() throws Exception {
        testResult(db, "CALL apoc.coll.split([1,2,3,2,4,5],2)", r -> {
            assertEquals(asList(1L), r.next().get("value"));
            assertEquals(asList(3L), r.next().get("value"));
            assertEquals(asList(4L, 5L), r.next().get("value"));
            assertFalse(r.hasNext());
        });
        testResult(db, "CALL apoc.coll.split([1,2,3],2)", r -> {
            assertEquals(asList(1L), r.next().get("value"));
            assertEquals(asList(3L), r.next().get("value"));
            assertFalse(r.hasNext());
        });
        testResult(db, "CALL apoc.coll.split([1,2,3],1)", r -> {
            assertEquals(asList(2L, 3L), r.next().get("value"));
            assertFalse(r.hasNext());
        });
        testResult(db, "CALL apoc.coll.split([1,2,3],3)", r -> {
            assertEquals(asList(1L, 2L), r.next().get("value"));
            assertFalse(r.hasNext());
        });
        testResult(db, "CALL apoc.coll.split([1,2,3],4)", r -> {
            assertEquals(asList(1L, 2L, 3L), r.next().get("value"));
            assertFalse(r.hasNext());
        });
    }

    @Test
    public void testSet() throws Exception {
        testCall(db, "RETURN apoc.coll.set(null,0,4) AS value", r -> assertNull(r.get("value")));
        testCall(db, "RETURN apoc.coll.set([1,2,3],-1,4) AS value", r -> assertEquals(asList(1L,2L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.set([1,2,3],0,null) AS value", r -> assertEquals(asList(1L,2L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.set([1,2,3],0,4) AS value", r -> assertEquals(asList(4L,2L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.set([1,2,3],1,4) AS value", r -> assertEquals(asList(1L,4L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.set([1,2,3],2,4) AS value", r -> assertEquals(asList(1L,2L,4L), r.get("value")));
        testCall(db, "RETURN apoc.coll.set([1,2,3],3,4) AS value", r -> assertEquals(asList(1L,2L,3L), r.get("value")));
    }

    @Test
    public void testInsert() throws Exception {
        testCall(db, "RETURN apoc.coll.insert(null,0,4) AS value", r -> assertNull(r.get("value")));
        testCall(db, "RETURN apoc.coll.insert([1,2,3],-1,4) AS value", r -> assertEquals(asList(1L,2L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.insert([1,2,3],0,null) AS value", r -> assertEquals(asList(1L,2L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.insert([1,2,3],0,4) AS value", r -> assertEquals(asList(4L,1L,2L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.insert([1,2,3],1,4) AS value", r -> assertEquals(asList(1L,4L,2L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.insert([1,2,3],2,4) AS value", r -> assertEquals(asList(1L,2L,4L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.insert([1,2,3],3,4) AS value", r -> assertEquals(asList(1L,2L,3L,4L), r.get("value")));
    }

    @Test
    public void testInsertList() throws Exception {
        testCall(db, "RETURN apoc.coll.insertAll(null,0,[4,5,6]) AS value", r -> assertNull(r.get("value")));
        testCall(db, "RETURN apoc.coll.insertAll([1,2,3],-1,[4,5,6]) AS value", r -> assertEquals(asList(1L,2L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.insertAll([1,2,3],0,null) AS value", r -> assertEquals(asList(1L,2L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.insertAll([1,2,3],0,[4,5,6]) AS value", r -> assertEquals(asList(4L,5L,6L,1L,2L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.insertAll([1,2,3],1,[4,5,6]) AS value", r -> assertEquals(asList(1L,4L,5L,6L,2L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.insertAll([1,2,3],2,[4,5,6]) AS value", r -> assertEquals(asList(1L,2L,4L,5L,6L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.insertAll([1,2,3],3,[4,5,6]) AS value", r -> assertEquals(asList(1L,2L,3L,4L,5L,6L), r.get("value")));
    }

    @Test
    public void testRemove() throws Exception {
        testCall(db, "RETURN apoc.coll.remove(null,0,0) AS value", r -> assertNull(r.get("value")));
        testCall(db, "RETURN apoc.coll.remove([1,2,3],-1,0) AS value", r -> assertEquals(asList(1L,2L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.remove([1,2,3],0,-1) AS value", r -> assertEquals(asList(1L,2L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.remove([1,2,3],0,0) AS value", r -> assertEquals(asList(1L,2L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.remove([1,2,3],0,1) AS value", r -> assertEquals(asList(2L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.remove([1,2,3],0) AS value", r -> assertEquals(asList(2L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.remove([1,2,3],1,1) AS value", r -> assertEquals(asList(1L,3L), r.get("value")));
        testCall(db, "RETURN apoc.coll.remove([1,2,3],1,2) AS value", r -> assertEquals(asList(1L), r.get("value")));
    }


    @Test
    public void testContainsAll() throws Exception {
        testCall(db, "RETURN apoc.coll.containsAll([1,2,3],[1,2]) AS value", (res) -> assertEquals(true, res.get("value")));
        testCall(db, "RETURN apoc.coll.containsAll([1,2,3],[2,1]) AS value", (res) -> assertEquals(true, res.get("value")));
        testCall(db, "RETURN apoc.coll.containsAll([1,2,3],[1,4]) AS value", (res) -> assertEquals(false, res.get("value")));
        testCall(db, "RETURN apoc.coll.containsAll([1,2,3],[]) AS value", (res) -> assertEquals(true, res.get("value")));
        testCall(db, "RETURN apoc.coll.containsAll([1,2,3],[1]) AS value", (res) -> assertEquals(true, res.get("value")));
        testCall(db, "RETURN apoc.coll.containsAll([1,2,3],[1,2,3,4]) AS value", (res) -> assertEquals(false, res.get("value")));
        testCall(db, "RETURN apoc.coll.containsAll([1,1,2,3],[1,2,2,3]) AS value", (res) -> assertEquals(true, res.get("value")));
        testCall(db, "RETURN apoc.coll.containsAll(null,[1,2,3]) AS value", (res) -> assertEquals(false, res.get("value")));
        testCall(db, "RETURN apoc.coll.containsAll(null,null) AS value", (res) -> assertEquals(false, res.get("value")));
        testCall(db, "RETURN apoc.coll.containsAll([1,2,3],null) AS value", (res) -> assertEquals(false, res.get("value")));
    }

    @Test
    public void testContainsAllSorted() throws Exception {
        testCall(db, "RETURN apoc.coll.containsAllSorted([1,2,3],[1,2]) AS value", (res) -> assertEquals(true, res.get("value")));
        testCall(db, "RETURN apoc.coll.containsAllSorted([1,2,3],[1,4]) AS value", (res) -> assertEquals(false, res.get("value")));
        testCall(db, "RETURN apoc.coll.containsAllSorted([1,2,3],[]) AS value", (res) -> assertEquals(true, res.get("value")));
        testCall(db, "RETURN apoc.coll.containsAllSorted([1,2,3],[1]) AS value", (res) -> assertEquals(true, res.get("value")));
        testCall(db, "RETURN apoc.coll.containsAllSorted([1,2,3],[1,2,3,4]) AS value", (res) -> assertEquals(false, res.get("value")));
    }

    @Test
    public void testIsEqualCollection() throws Exception {
        testCall(db, "RETURN apoc.coll.isEqualCollection([1,2,3],[1,2,3]) AS value", (res) -> assertEquals(true, res.get("value")));
        testCall(db, "RETURN apoc.coll.isEqualCollection([1,2,3],[3,2,1]) AS value", (res) -> assertEquals(true, res.get("value")));
        testCall(db, "RETURN apoc.coll.isEqualCollection([1,1,2,2,3],[1,1,2,2,3]) AS value", (res) -> assertEquals(true, res.get("value")));
        testCall(db, "RETURN apoc.coll.isEqualCollection([1,1,2,3],[1,2,2,3]) AS value", (res) -> assertEquals(false, res.get("value")));
        testCall(db, "RETURN apoc.coll.isEqualCollection([1,2,3],[1,2]) AS value", (res) -> assertEquals(false, res.get("value")));
        testCall(db, "RETURN apoc.coll.isEqualCollection([1,2,3],[1,4]) AS value", (res) -> assertEquals(false, res.get("value")));
        testCall(db, "RETURN apoc.coll.isEqualCollection([1,2,3],[]) AS value", (res) -> assertEquals(false, res.get("value")));
        testCall(db, "RETURN apoc.coll.isEqualCollection([1,2,3],[1]) AS value", (res) -> assertEquals(false, res.get("value")));
        testCall(db, "RETURN apoc.coll.isEqualCollection([1,2,3],[1,2,3,4]) AS value", (res) -> assertEquals(false, res.get("value")));
        testCall(db, "RETURN apoc.coll.isEqualCollection([1,2,3],null) AS value", (res) -> assertEquals(false, res.get("value")));
        testCall(db, "RETURN apoc.coll.isEqualCollection([1,2,3],[]) AS value", (res) -> assertEquals(false, res.get("value")));
        testCall(db, "RETURN apoc.coll.isEqualCollection([],null) AS value", (res) -> assertEquals(false, res.get("value")));
        testCall(db, "RETURN apoc.coll.isEqualCollection([],[]) AS value", (res) -> assertEquals(true, res.get("value")));
        testCall(db, "RETURN apoc.coll.isEqualCollection(null,null) AS value", (res) -> assertEquals(true, res.get("value")));
        testCall(db, "RETURN apoc.coll.isEqualCollection(null,[]) AS value", (res) -> assertEquals(false, res.get("value")));
    }

    @Test
    public void testIN2() throws Exception {
        int elements = 1_000_000;
        ArrayList<Long> list = new ArrayList<>(elements);
        for (long i = 0; i < elements; i++) {
            list.add(i);
        }
        Map<String, Object> params = new HashMap<>();
        params.put("list", list);
        params.put("value", list.get(list.size() - 1));
        long start = System.currentTimeMillis();
        testCall(db, "RETURN apoc.coll.contains($list,$value) AS value", params,
                (res) -> assertEquals(true, res.get("value")));
    }

    @Test
    public void testContainsSorted() throws Exception {
        int elements = 1_000_000;
        ArrayList<Long> list = new ArrayList<>(elements);
        for (long i = 0; i < elements; i++) {
            list.add(i);
        }
        Map<String, Object> params = new HashMap<>();
        params.put("list", list);
        params.put("value", list.get(list.size() / 2));
        long start = System.currentTimeMillis();
        testCall(db, "RETURN apoc.coll.containsSorted($list,$value) AS value", params,
                (res) -> assertEquals(true, res.get("value")));
    }

    @Test
    public void testSortNodes() throws Exception {
        testCall(db,
                "CREATE (n {name:'foo'}),(m {name:'bar'}) WITH n,m RETURN apoc.coll.sortNodes([n,m], 'name') AS nodes",
                (row) -> {
                    List<Node> nodes = (List<Node>) row.get("nodes");
                    assertEquals("foo", nodes.get(0).getProperty("name"));
                    assertEquals("bar", nodes.get(1).getProperty("name"));
                });
    }

    @Test
    public void testSortNodesReverse() throws Exception {
        testCall(db,
                "CREATE (n {name:'foo'}),(m {name:'bar'}) WITH n,m RETURN apoc.coll.sortNodes([n,m], '^name') AS nodes",
                (row) -> {
                    List<Node> nodes = (List<Node>) row.get("nodes");
                    assertEquals("bar", nodes.get(0).getProperty("name"));
                    assertEquals("foo", nodes.get(1).getProperty("name"));
                });
    }

    @Test
    public void testElements() throws Exception {
        testCall(db,
                "CREATE p=(n {name:'foo'})-[r:R]->(n) WITH n,r,p CALL apoc.coll.elements([0,null,n,r,p,42,3.14,true,[42],{a:42},13], 9,1) YIELD elements,_1,_7,_10,_2n,_3r,_4p,_5i,_5f,_6i,_6f,_7b,_8l,_9m RETURN *",
                (row) -> {
                    assertEquals(9L, row.get("elements"));
                    assertEquals(null, row.get("_1"));
                    assertEquals(row.get("n"), row.get("_2n"));
                    assertEquals(row.get("r"), row.get("_3r"));
                    assertEquals(row.get("p"), row.get("_4p"));
                    assertEquals(42L, row.get("_5i"));
                    assertEquals(42D, row.get("_5f"));
                    assertEquals(3.14D, row.get("_6f"));
                    assertEquals(true, row.get("_7"));
                    assertEquals(true, row.get("_7b"));
                    assertEquals(singletonList(42L), row.get("_8l"));
                    assertEquals(map("a",42L), row.get("_9m"));
                    assertEquals(null, row.get("_10"));
                });
    }

    @Test
    public void testSortMaps() throws Exception {
        testCall(db,
                "RETURN apoc.coll.sortMaps([{name:'foo'},{name:'bar'}], 'name') as maps",
                (row) -> {
                    List<Map> nodes = (List<Map>) row.get("maps");
                    assertEquals("foo", nodes.get(0).get("name"));
                    assertEquals("bar", nodes.get(1).get("name"));
                });
    }

    @Test
    public void testSortMapsMulti() throws Exception {
        testCall(db,
                "RETURN apoc.coll.sortMulti([{name:'foo'},{name:'bar',age:32},{name:'bar',age:42}], ['^name','age'],1,1) as maps",
                (row) -> {
                    List<Map> maps = (List<Map>) row.get("maps");
                    assertEquals(1, maps.size());
                    assertEquals("bar", maps.get(0).get("name"));
                    assertEquals(32L, maps.get(0).get("age")); // 2nd element
                });
    }

    @Test
    public void testSortMapsCount() throws Exception {

        testCall(db,
                "WITH ['a','b','c','c','c','b','a','d'] AS l RETURN apoc.coll.sortMaps(apoc.coll.frequencies(l),'count') as maps",
                (row) -> {
                    List<Map> maps = (List<Map>) row.get("maps");
                    assertEquals(4, maps.size());
                    assertEquals("c", maps.get(0).get("item"));
                    assertEquals("a", maps.get(1).get("item"));
                    assertEquals("b", maps.get(2).get("item"));
                    assertEquals("d", maps.get(3).get("item"));
                });
    }

    @Test
    public void testSortMapsCountReverse() throws Exception {

        testCall(db,
                "WITH ['b','a','c','c','c','b','a','d'] AS l RETURN apoc.coll.sortMaps(apoc.coll.frequencies(l),'^count') as maps",
                (row) -> {
                    List<Map> maps = (List<Map>) row.get("maps");
                    assertEquals(4, maps.size());
                    assertEquals("d", maps.get(0).get("item"));
                    assertEquals("b", maps.get(1).get("item"));
                    assertEquals("a", maps.get(2).get("item"));
                    assertEquals("c", maps.get(3).get("item"));
                });
    }

    @Test
    public void testSetOperations() throws Exception {
        testCall(db, "RETURN apoc.coll.union([1,2],[3,2]) AS value", r -> assertEquals(asSet(asList(1L, 2L, 3L)), asSet((Iterable) r.get("value"))));
        testCall(db, "RETURN apoc.coll.intersection([1,2],[3,2]) AS value", r -> assertEquals(asSet(asList(2L)), asSet((Iterable) r.get("value"))));
        testCall(db, "RETURN apoc.coll.intersection([1,2],[2,3]) AS value", r -> assertEquals(asSet(asList(2L)), asSet((Iterable) r.get("value"))));
        testCall(db, "RETURN apoc.coll.intersection([1.2,2.3],[2.3,3.4]) AS value", r -> assertEquals(asSet(asList(2.3D)), asSet((Iterable) r.get("value"))));
        testCall(db, "RETURN apoc.coll.disjunction([1,2],[3,2]) AS value", r -> assertEquals(asSet(asList(1L, 3L)), asSet((Iterable) r.get("value"))));
        testCall(db, "RETURN apoc.coll.subtract([1,2],[3,2]) AS value", r -> assertEquals(asSet(asList(1L)), asSet((Iterable) r.get("value"))));
        testCall(db, "RETURN apoc.coll.unionAll([1,2],[3,2]) AS value", r -> assertEquals(asList(1L, 2L, 3L, 2L), r.get("value")));
        testCall(db, "RETURN apoc.coll.removeAll([1,2],[3,2]) AS value", r -> assertEquals(asList(1L), r.get("value")));
    }

    @Test
    public void testIntersectionWithJsonMap(){
        testCall(db, "WITH apoc.convert.fromJsonMap('{\"numbers\":[1,2]}') as set1, [2,3] as set2\n" +
                "WITH apoc.coll.intersection(set1.numbers, set2) as value\n" +
                "RETURN value", r -> assertEquals(asSet(asList(2L)), asSet((Iterable) r.get("value"))));
    }

    @Test
    public void testIntersectionWithJsonMapDouble(){
        testCall(db, "WITH apoc.convert.fromJsonMap('{\"numbers\":[1.2,2.3]}') as set1, [2.3,3.4] as set2\n" +
                "WITH apoc.coll.intersection(set1.numbers, set2) as value\n" +
                "RETURN value", r -> assertEquals(asSet(asList(2.3D)), asSet((Iterable) r.get("value"))));
    }

    @Test
    public void testShuffleOnNullAndEmptyList() throws Exception {
        testCall(db, "RETURN apoc.coll.shuffle([]) as value",
                (row) -> assertEquals(Collections.EMPTY_LIST, row.get("value")));

        testCall(db, "RETURN apoc.coll.shuffle(null) as value",
                (row) -> assertEquals(Collections.EMPTY_LIST, row.get("value")));
    }

    @Test
    public void testShuffle() throws Exception {
        // with 10k elements, very remote chance of randomly getting same order
        int elements = 10_000;
        ArrayList<Long> original = new ArrayList<>(elements);
        for (long i = 0; i < elements; i++) {
            original.add(i);
        }

        Map<String, Object> params = new HashMap<>();
        params.put("list", original);

        testCall(db, "RETURN apoc.coll.shuffle($list) as value", params,
                (row) -> {
                    List<Object> result = (List<Object>) row.get("value");
                    assertEquals(original.size(), result.size());
                    assertTrue(original.containsAll(result));
                    assertFalse(original.equals(result));
                });
    }

    @Test
    public void testRandomItemOnNullAndEmptyList() throws Exception {
        testCall(db, "RETURN apoc.coll.randomItem([]) as value",
                (row) -> {
                    Object result = row.get("value");
                    assertEquals(null, result);
                });

        testCall(db, "RETURN apoc.coll.randomItem(null) as value",
                (row) -> {
                    Object result = row.get("value");
                    assertEquals(null, result);
                });
    }

    @Test
    public void testRandomItem() throws Exception {
        testCall(db, "RETURN apoc.coll.randomItem([1,2,3,4,5]) as value",
                (row) -> {
                    Long result = (Long) row.get("value");
                    assertTrue(result >= 1 && result <= 5);
                });
    }

    @Test
    public void testRandomItemsOnNullAndEmptyList() throws Exception {
        testCall(db, "RETURN apoc.coll.randomItems([], 5) as value",
                (row) -> {
                    List<Object> result = (List<Object>) row.get("value");
                    assertTrue(result.isEmpty());
                });

        testCall(db, "RETURN apoc.coll.randomItems(null, 5) as value",
                (row) -> {
                    List<Object> result = (List<Object>) row.get("value");
                    assertTrue(result.isEmpty());
                });

        testCall(db, "RETURN apoc.coll.randomItems([], 5, true) as value",
                (row) -> {
                    List<Object> result = (List<Object>) row.get("value");
                    assertTrue(result.isEmpty());
                });

        testCall(db, "RETURN apoc.coll.randomItems(null, 5, true) as value",
                (row) -> {
                    List<Object> result = (List<Object>) row.get("value");
                    assertTrue(result.isEmpty());
                });
    }

    @Test
    public void testRandomItems() throws Exception {
        // with 100k elements, very remote chance of randomly getting same order
        int elements = 100_000;
        ArrayList<Long> original = new ArrayList<>(elements);
        for (long i = 0; i < elements; i++) {
            original.add(i);
        }

        Map<String, Object> params = new HashMap<>();
        params.put("list", original);

        testCall(db, "RETURN apoc.coll.randomItems($list, 5000) as value", params,
                (row) -> {
                    List<Object> result = (List<Object>) row.get("value");
                    assertEquals(result.size(), 5000);
                    assertTrue(original.containsAll(result));
                    assertFalse(result.equals(original.subList(0, 5000)));
                });
    }

    @Test
    public void testRandomItemsLargerThanOriginal() throws Exception {
        // with 10k elements, very remote chance of randomly getting same order
        int elements = 10_000;
        ArrayList<Long> original = new ArrayList<>(elements);
        for (long i = 0; i < elements; i++) {
            original.add(i);
        }

        Map<String, Object> params = new HashMap<>();
        params.put("list", original);

        testCall(db, "RETURN apoc.coll.randomItems($list, 20000) as value", params,
                (row) -> {
                    List<Object> result = (List<Object>) row.get("value");
                    assertEquals(result.size(), 10000);
                    assertTrue(original.containsAll(result));
                    assertFalse(result.equals(original));
                });
    }

    @Test
    public void testRandomItemsLargerThanOriginalAllowingRepick() throws Exception {
        // with 100k elements, very remote chance of randomly getting same order
        int elements = 100_000;
        ArrayList<Long> original = new ArrayList<>(elements);
        for (long i = 0; i < elements; i++) {
            original.add(i);
        }

        Map<String, Object> params = new HashMap<>();
        params.put("list", original);

        testCall(db, "RETURN apoc.coll.randomItems($list, 11000, true) as value", params,
                (row) -> {
                    List<Object> result = (List<Object>) row.get("value");
                    assertEquals(result.size(), 11000);
                    assertTrue(original.containsAll(result));
                });
    }

    @Test
    public void testContainsDuplicatesOnNullAndEmptyList() throws Exception {
        testCall(db, "RETURN apoc.coll.containsDuplicates([]) AS value", r -> assertEquals(false, r.get("value")));
        testCall(db, "RETURN apoc.coll.containsDuplicates(null) AS value", r -> assertEquals(false, r.get("value")));
    }

    @Test
    public void testContainsDuplicates() throws Exception {
        testCall(db, "RETURN apoc.coll.containsDuplicates([1,2,3,9,7,5]) AS value", r -> assertEquals(false, r.get("value")));
        testCall(db, "RETURN apoc.coll.containsDuplicates([1,2,1,5,4]) AS value", r -> assertEquals(true, r.get("value")));
    }

    @Test
    public void testDuplicatesOnNullAndEmptyList() throws Exception {
        testCall(db, "RETURN apoc.coll.duplicates([]) as value",
                (row) -> assertEquals(Collections.EMPTY_LIST, row.get("value")));
        testCall(db, "RETURN apoc.coll.duplicates(null) as value",
                (row) -> assertEquals(Collections.EMPTY_LIST, row.get("value")));
    }

    @Test
    public void testDuplicates() throws Exception {
        testCall(db, "RETURN apoc.coll.duplicates([1,2,1,3,2,5,2,3,1,2]) as value",
                (row) -> assertEquals(asList(1L, 2L, 3L), row.get("value")));
    }

    @Test
    public void testDuplicatesWithCountOnNullAndEmptyList() throws Exception {
        testCall(db, "RETURN apoc.coll.duplicatesWithCount([]) as value",
                (row) -> assertEquals(Collections.EMPTY_LIST, row.get("value")));
        testCall(db, "RETURN apoc.coll.duplicatesWithCount(null) as value",
                (row) -> assertEquals(Collections.EMPTY_LIST, row.get("value")));
    }

    @Test
    public void testDuplicatesWithCount() throws Exception {
        testCall(db, "RETURN apoc.coll.duplicatesWithCount([1,2,1,3,2,5,2,3,1,2]) as value",
                (row) -> {
                    Map<Long, Long> expectedMap = new HashMap<>(3);
                    expectedMap.put(1l, 3l);
                    expectedMap.put(2l, 4l);
                    expectedMap.put(3l, 2l);

                    List<Map<String, Object>> result = (List<Map<String, Object>>) row.get("value");
                    assertEquals(3, result.size());

                    Set<Long> keys = new HashSet<>(3);

                    for (Map<String, Object> map : result) {
                        Object item = map.get("item");
                        Long count = (Long) map.get("count");
                        keys.add((Long) item);
                        assertTrue(expectedMap.containsKey(item));
                        assertEquals(expectedMap.get(item), count);
                    }

                    assertEquals(expectedMap.keySet(), keys);
                });
    }

    @Test
    public void testFrequenciesOnNullAndEmptyList() throws Exception {
        testCall(db, "RETURN apoc.coll.frequencies([]) as value",
                (row) -> assertEquals(Collections.EMPTY_LIST, row.get("value")));
        testCall(db, "RETURN apoc.coll.frequencies(null) as value",
                (row) -> assertEquals(Collections.EMPTY_LIST, row.get("value")));
    }

    @Test
    public void testFrequencies() throws Exception {
        testCall(db, "RETURN apoc.coll.frequencies([1,2,1,3,2,5,2,3,1,2]) as value",
                (row) -> {
                    Map<Long, Long> expectedMap = new HashMap<>(4);
                    expectedMap.put(1l, 3l);
                    expectedMap.put(2l, 4l);
                    expectedMap.put(3l, 2l);
                    expectedMap.put(5l, 1l);

                    List<Map<String, Object>> result = (List<Map<String, Object>>) row.get("value");
                    assertEquals(4, result.size());

                    Set<Long> keys = new HashSet<>(4);

                    for (Map<String, Object> map : result) {
                        Object item = map.get("item");
                        Long count = (Long) map.get("count");
                        keys.add((Long) item);
                        assertTrue(expectedMap.containsKey(item));
                        assertEquals(expectedMap.get(item), count);
                    }

                    assertEquals(expectedMap.keySet(), keys);
                });
    }

    @Test
    public void testFrequenciesAsMapAsMap() throws Exception {
        testCall(db, "RETURN apoc.coll.frequenciesAsMap([]) as value",
                (row) -> assertEquals(Collections.emptyMap(), row.get("value")));
        testCall(db, "RETURN apoc.coll.frequenciesAsMap(null) as value",
                (row) -> assertEquals(Collections.emptyMap(), row.get("value")));
        testCall(db, "RETURN apoc.coll.frequenciesAsMap([1,1]) as value",
                (row) -> {
                    Map<String, Object> maps= (Map<String, Object>) row.get("value");
                    assertEquals(1, maps.size());
                    assertEquals(2L, maps.get("1"));
                });
        testCall(db, "RETURN apoc.coll.frequenciesAsMap([1,2,1]) as value",
                (row) -> {
                    Map<String, Object> maps= (Map<String, Object>) row.get("value");
                    assertEquals(2, maps.size());
                    assertEquals(2L, maps.get("1"));
                    assertEquals(1L, maps.get("2"));
                });
        testCall(db, "RETURN apoc.coll.frequenciesAsMap([1,2,1,3,2,5,2,3,1,2]) as value",
                (row) -> {
                    Map<String, Object> maps= (Map<String, Object>) row.get("value");
                    assertEquals(4, maps.size());
                    assertEquals(3L, maps.get("1"));
                    assertEquals(4L, maps.get("2"));
                    assertEquals(2L, maps.get("3"));
                    assertEquals(1L, maps.get("5"));
                });
        testCall(db, "WITH ['a','b','c','c','c','b','a','d'] AS l RETURN apoc.coll.frequenciesAsMap(l) as value",
                (row) -> {
                    Map<String, Object> maps= (Map<String, Object>) row.get("value");
                    assertEquals(4, maps.size());
                    assertEquals(2L, maps.get("a"));
                    assertEquals(2L, maps.get("b"));
                    assertEquals(3L, maps.get("c"));
                    assertEquals(1L, maps.get("d"));
                });
    }

    @Test
    public void testOccurrencesOnNullAndEmptyList() throws Exception {
        testCall(db, "RETURN apoc.coll.occurrences([], 5) as value",
                (row) -> assertEquals(0l, row.get("value")));
        testCall(db, "RETURN apoc.coll.occurrences(null, 5) as value",
                (row) -> assertEquals(0l, row.get("value")));
    }

    @Test
    public void testOccurrences() throws Exception {
        testCall(db, "RETURN apoc.coll.occurrences([1,2,1,3,2,5,2,3,1,2], 1) as value",
                (row) -> assertEquals(3l, row.get("value")));
        testCall(db, "RETURN apoc.coll.occurrences([1,2,1,3,2,5,2,3,1,2], 2) as value",
                (row) -> assertEquals(4l, row.get("value")));
        testCall(db, "RETURN apoc.coll.occurrences([1,2,1,3,2,5,2,3,1,2], 3) as value",
                (row) -> assertEquals(2l, row.get("value")));
        testCall(db, "RETURN apoc.coll.occurrences([1,2,1,3,2,5,2,3,1,2], 5) as value",
                (row) -> assertEquals(1l, row.get("value")));
        testCall(db, "RETURN apoc.coll.occurrences([1,2,1,3,2,5,2,3,1,2], -5) as value",
                (row) -> assertEquals(0l, row.get("value")));
    }

    @Test
    public void testReverseOnNullAndEmptyList() throws Exception {
        testCall(db, "RETURN apoc.coll.reverse([]) as value",
                (row) -> assertEquals(Collections.EMPTY_LIST, row.get("value")));
        testCall(db, "RETURN apoc.coll.reverse(null) as value",
                (row) -> assertEquals(Collections.EMPTY_LIST, row.get("value")));
    }

    @Test
    public void testReverse() throws Exception {
        testCall(db, "RETURN apoc.coll.reverse([1,2,1,3,2,5,2,3,1,2]) as value",
                (row) -> assertEquals(asList(2l, 1l, 3l, 2l, 5l, 2l, 3l, 1l, 2l, 1l), row.get("value")));
    }
    @Test
    public void testFlatten() throws Exception {
        testCall(db, "RETURN apoc.coll.flatten([[1,2],[3,4],[4],[5,6,7]]) as value",
                (row) -> assertEquals(asList(1L,2L,3L,4L,4L,5L,6L,7L), row.get("value")));
    }

    @Test
    public void testCombinationsWith0() throws Exception {
        testCall(db, "RETURN apoc.coll.combinations([1,2,3,4,5], 0) as value",
                (row) -> assertEquals(Collections.emptyList(), row.get("value")));
    }

    @Test
    public void testCombinationsWithNegative() throws Exception {
        testCall(db, "RETURN apoc.coll.combinations([1,2,3,4,5], -1) as value",
                (row) -> assertEquals(Collections.emptyList(), row.get("value")));
    }

    @Test
    public void testCombinationsWithEmptyCollection() throws Exception {
        testCall(db, "RETURN apoc.coll.combinations([], 0) as value",
                (row) -> assertEquals(Collections.emptyList(), row.get("value")));
    }

    @Test
    public void testCombinationsWithNullCollection() throws Exception {
        testCall(db, "RETURN apoc.coll.combinations(null, 0) as value",
                (row) -> assertEquals(Collections.emptyList(), row.get("value")));
    }

    @Test
    public void testCombinationsWithTooLargeSelect() throws Exception {
        testCall(db, "RETURN apoc.coll.combinations([1,2,3,4,5], 6) as value",
                (row) -> assertEquals(Collections.emptyList(), row.get("value")));
    }

    @Test
    public void testCombinationsWithListSizeSelect() throws Exception {
        testCall(db, "RETURN apoc.coll.combinations([1,2,3,4,5], 5) as value",
                (row) -> {
                    List<List<Object>> result = new ArrayList<>();
                    result.add(asList(1l,2l,3l,4l,5l));
                    assertEquals(result, row.get("value"));
                });
    }

    @Test
    public void testCombinationsWithSingleSelect() throws Exception {
        testCall(db, "RETURN apoc.coll.combinations([1,2,3,4,5], 3) as value",
                (row) -> {
                    List<List<Object>> result = new ArrayList<>();
                    result.add(asList(1l,2l,3l));
                    result.add(asList(1l,2l,4l));
                    result.add(asList(1l,3l,4l));
                    result.add(asList(2l,3l,4l));
                    result.add(asList(1l,2l,5l));
                    result.add(asList(1l,3l,5l));
                    result.add(asList(2l,3l,5l));
                    result.add(asList(1l,4l,5l));
                    result.add(asList(2l,4l,5l));
                    result.add(asList(3l,4l,5l));
                    assertEquals(result, row.get("value"));
                });
    }

    @Test
    public void testCombinationsWithMinSelectGreaterThanMax() throws Exception {
        testCall(db, "RETURN apoc.coll.combinations([1,2,3,4], 3, 2) as value",
                (row) -> {
                    assertEquals(Collections.emptyList(), row.get("value"));
                });
    }

    @Test
    public void testCombinationsWithMinAndMaxSelect() throws Exception {
        testCall(db, "RETURN apoc.coll.combinations([1,2,3,4], 2, 3) as value",
                (row) -> {
                    List<List<Object>> result = new ArrayList<>();
                    result.add(asList(1l,2l));
                    result.add(asList(1l,3l));
                    result.add(asList(2l,3l));
                    result.add(asList(1l,4l));
                    result.add(asList(2l,4l));
                    result.add(asList(3l,4l));
                    result.add(asList(1l,2l,3l));
                    result.add(asList(1l,2l,4l));
                    result.add(asList(1l,3l,4l));
                    result.add(asList(2l,3l,4l));

                    assertEquals(result, row.get("value"));
                });
    }

    @Test
    public void testVerifyAllValuesAreDifferent() throws Exception {
        testCall(db, "RETURN apoc.coll.different([1, 2, 3]) as value",
                (row) -> {
                    assertEquals(true, row.get("value"));
                });
        testCall(db, "RETURN apoc.coll.different([1, 1, 1]) as value",
                (row) -> {
                    assertEquals(false, row.get("value"));
                });
        testCall(db, "RETURN apoc.coll.different([3, 3, 1]) as value",
                (row) -> {
                    assertEquals(false, row.get("value"));
                });
    }

    @Test
    public void testDropNeighboursNodes() throws Exception {
        db.executeTransactionally("CREATE (n:Person {name:'Foo'}) " +
                "CREATE (b:Person {name:'Bar'}) " +
                "CREATE (n)-[:KNOWS]->(n)-[:LIVES_WITH]->(n)");
        testResult(db, "MATCH p=(n)-[:KNOWS]->(m)-[:LIVES_WITH]->(h) RETURN apoc.coll.dropDuplicateNeighbors(nodes(p)) as value",
                (row) -> {
                    assertEquals(true, row.hasNext());
                    assertEquals(1, row.next().size());
                });
    }

    @Test
    public void testDropNeighboursNumbers() throws Exception {
        testResult(db, "WITH [1,2,3,4,4,5,6,6,4,7] AS values RETURN apoc.coll.dropDuplicateNeighbors(values) as value",
                (row) -> {
                    assertEquals(asList(1L,2L,3L,4L,5L,6L,4L,7L), row.next().get("value"));
                });
    }

    @Test
    public void testDropNeighboursStrings() throws Exception {
        testResult(db, "WITH ['a','a','hello','hello','hello','foo','bar','apoc','apoc!','hello'] AS values RETURN apoc.coll.dropDuplicateNeighbors(values) as value",
                (row) -> {
                    assertEquals(asList("a","hello","foo","bar","apoc","apoc!","hello"), row.next().get("value"));
                });
    }

    @Test
    public void testDropNeighboursDifferentTypes() throws Exception {
        testResult(db, "WITH ['a','a',1,1,'hello','foo','bar','apoc','apoc!',1] AS values RETURN apoc.coll.dropDuplicateNeighbors(values) as value",
                (row) -> {
                    assertEquals(asList("a",1L,"hello","foo","bar","apoc","apoc!",1L), row.next().get("value"));
                });
    }

    @Test
    public void testFill() throws Exception {
        testResult(db, "RETURN apoc.coll.fill('abc',2) as value",
                (row) -> {
                    assertEquals(asList("abc","abc"), row.next().get("value"));
                });
    }

    @Test
    public void testSortText() throws Exception {
        testCall(db, "RETURN apoc.coll.sortText(['b', 'a']) as value",
                (row) -> assertEquals(asList("a", "b"), row.get("value")));
        testCall(db, "RETURN apoc.coll.sortText(['Єльська', 'Гусак'], {locale: 'ru'}) as value",
                (row) -> assertEquals(asList("Гусак", "Єльська"), row.get("value")));
    }

    @Test
    public void testPairWithOffsetFn() throws Exception {
        testCall(db, "RETURN apoc.coll.pairWithOffset([1,2,3,4], 2) AS value",
                (row) -> assertEquals(asList(asList(1L, 3L), asList(2L, 4L), asList(3L, null), asList(4L, null)), row.get("value")));
        testCall(db, "RETURN apoc.coll.pairWithOffset([1,2,3,4], -2) AS value",
                (row) -> assertEquals(asList(asList(1L, null), asList(2L, null), asList(3L, 1L), asList(4L, 2L)), row.get("value")));
    }

    @Test
    public void testPairWithOffset() throws Exception {
        testResult(db, "CALL apoc.coll.pairWithOffset([1,2,3,4], 2)",
                (result) -> {
                    assertEquals(asList(1L, 3L), result.next().get("value"));
                    assertEquals(asList(2L, 4L), result.next().get("value"));
                    assertEquals(asList(3L, null), result.next().get("value"));
                    assertEquals(asList(4L, null), result.next().get("value"));
                    assertFalse(result.hasNext());
                });
        testResult(db, "CALL apoc.coll.pairWithOffset([1,2,3,4], -2)",
                (result) -> {
                    assertEquals(asList(1L, null), result.next().get("value"));
                    assertEquals(asList(2L, null), result.next().get("value"));
                    assertEquals(asList(3L, 1L), result.next().get("value"));
                    assertEquals(asList(4L, 2L), result.next().get("value"));
                    assertFalse(result.hasNext());
                });
    }

}

