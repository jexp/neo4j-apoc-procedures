package apoc.index;

import apoc.util.TestUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.test.TestGraphDatabaseFactory;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.StreamSupport;

import static apoc.util.MapUtil.map;
import static apoc.util.TestUtil.testCall;
import static apoc.util.TestUtil.testResult;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author mh
 * @since 23.05.16
 */
public class SchemaIndexTest {

    private static GraphDatabaseService db;

    @BeforeClass
    public static void setUp() throws Exception {
        db = new TestGraphDatabaseFactory()
                .newImpermanentDatabaseBuilder()
                .newGraphDatabase();
        TestUtil.registerProcedure(db, SchemaIndex.class);
        db.execute("CREATE (city:City {name:'London'}) WITH city UNWIND range(1,200) as id CREATE (:Person {name:'name'+id, id:id, age:id % 100})-[:LIVES_IN]->(city)").close();
        db.execute("CREATE INDEX ON :Person(name)").close();
        db.execute("CREATE INDEX ON :Person(age)").close();
        db.execute("CREATE CONSTRAINT ON (p:Person) ASSERT p.id IS UNIQUE").close();
        db.execute("CREATE INDEX ON :Foo(bar)").close();
        db.execute("CREATE INDEX ON :Foo(baz)").close();
        db.execute("CREATE (f:Foo {bar:'three'}), (f2a:Foo {bar:'four'}), (f2b:Foo {bar:'four', baz:'five'})").close();

        try (Transaction tx=db.beginTx()) {
            db.schema().awaitIndexesOnline(2,TimeUnit.SECONDS);
            tx.success();
        }
    }

    @AfterClass
    public static void tearDown() {
        db.shutdown();
    }

    @Test
    public void testRelated() throws Exception {
        testResult(db, "MATCH (city:City) WITH city CALL apoc.index.related([city],'Person','age','LIVES_IN>',5) YIELD node RETURN *", r -> {
            Function<Result,Object> age = (res) -> ((Node)res.next().get("node")).getProperty("age");
            LongStream.of(1,2,3,4,5).forEach( (a) -> assertEquals(a,age.apply(r)));
            assertEquals(false, r.hasNext());
        });
    }

    @Test
    public void testOrderedRangeNumbers() throws Exception {
        testResult(db, "CALL apoc.index.orderedRange('Person','age',10,30,false,10)", r -> {
            for (long i=10;i<20;i++) {
                assertEquals(i,((Node)r.next().get("node")).getProperty("id"));
            }
            assertEquals(false, r.hasNext());
        });
    }

    @Test
    public void testOrderedRangeNumbersLowerBound() throws Exception {
        testResult(db, "CALL apoc.index.orderedRange('Person','age',10,null,false,5)", r -> {
            for (long i=10;i<15;i++) {
                assertEquals(i,((Node)r.next().get("node")).getProperty("id"));
            }
            assertEquals(false, r.hasNext());
        });
    }
    @Test
    public void testOrderedRangeNumbersUpperBound() throws Exception {
        testResult(db, "CALL apoc.index.orderedRange('Person','age',null,10,false,5)", r -> {
            for (long i=1;i<6;i++) {
                assertEquals(i,((Node)r.next().get("node")).getProperty("id"));
            }
            assertEquals(false, r.hasNext());
        });
    }

    @Test
    public void testOrderedRangeNumbersRangeSmallerSize() throws Exception {
        testResult(db, "CALL apoc.index.orderedRange('Person','age',10,15,false,10)", r -> {
            for (long i=10;i<16;i++) {
                assertEquals(i,((Node)r.next().get("node")).getProperty("id"));
            }
            for (long i=110;i<114;i++) {
                assertEquals(i,((Node)r.next().get("node")).getProperty("id"));
            }
            assertEquals(false, r.hasNext());
        });
    }

    @Test
    public void testOrderedRangeText() throws Exception {
        testResult(db, "CALL apoc.index.orderedRange('Person','name','name10','name30',false,10)", r -> {
            assertEquals(2L, ((Node) r.next().get("node")).getProperty("id"));
            assertEquals(3L, ((Node) r.next().get("node")).getProperty("id"));
            for (long i=10;i<18;i++) {
                assertEquals(i, ((Node) r.next().get("node")).getProperty("id"));
            }
            assertEquals(false, r.hasNext());
        });
    }
    @Test
    public void testOrderByText() throws Exception {
        testResult(db, "CALL apoc.index.orderedByText('Person','name','STARTS WITH','name1',false,10)", r -> {
            assertEquals(1L,((Node)r.next().get("node")).getProperty("id"));
            for (long i=10;i<19;i++) {
                assertEquals(i, ((Node) r.next().get("node")).getProperty("id"));
            }
            assertEquals(false, r.hasNext());
        });
    }

    @Test
    public void testDistinctPropertiesOnFirstIndex() throws Exception {
        testCall(db,"CALL apoc.schema.properties.distinct({label}, {key})",
                map("label", "Foo","key", "bar"),
                (row) -> assertEquals(new HashSet<>(asList("three","four")), new HashSet<>((Collection<String>) row.get("value")))
        );
    }

    @Test
    public void testDistinctPropertiesOnSecondIndex() throws Exception {
        testCall(db,"CALL apoc.schema.properties.distinct({label}, {key})",
                map("label", "Foo","key", "baz"),
                (row) -> assertEquals(new HashSet<>(asList("five")), new HashSet<>((Collection<String>) row.get("value")))
        );
    }

    @Test
    public void testDistinctCountPropertiesOnFirstIndex() throws Exception {
        String label = "Foo";
        String key = "bar";
        testResult(db,"CALL apoc.schema.properties.distinctCount({label}, {key}) YIELD label,key,value,count RETURN * ORDER BY value",
                map("label",label,"key",key),
                (result) -> {
                    assertTrue(result.hasNext());
                    assertEquals(map("label",label,"key",key,"value","four","count",2L),result.next());
                    assertEquals(map("label",label,"key",key,"value","three","count",1L),result.next());
                    assertFalse(result.hasNext());
        });
    }

    @Test
    public void testDistinctCountPropertiesOnSecondIndex() throws Exception {
        String label = "Foo";
        String key = "baz";
        testResult(db,"CALL apoc.schema.properties.distinctCount({label}, {key}) YIELD label,key,value,count RETURN * ORDER BY value",
                map("label",label,"key",key),
                (result) -> {
                    assertTrue(result.hasNext());
                    assertEquals(map("label",label,"key",key,"value","five","count",1L),result.next());
                    assertFalse(result.hasNext());
                });
    }

    @Test
    public void testDistinctPropertiesOnEmptyLabel() throws Exception {
        String key = "bar";
        testResult(db,"CALL apoc.schema.properties.distinctCount({label}, {key}) YIELD label,key,value,count RETURN * ORDER BY value",
                map("label","","key",key),
                (result) -> {
                    assertTrue(result.hasNext());
                    assertEquals(map("label","Foo","key",key,"value","four","count",2L),result.next());
                    assertEquals(map("label","Foo","key",key,"value","three","count",1L),result.next());
                    assertFalse(result.hasNext());
                });
    }

    @Test
    public void testDistinctPropertiesOnEmptyKey() throws Exception {
        String label = "Foo";
        testResult(db,"CALL apoc.schema.properties.distinctCount({label}, {key}) YIELD label,key,value,count RETURN * ORDER BY value",
                map("label",label,"key",""),
                (result) -> {
                    assertTrue(result.hasNext());
                    assertEquals(map("label",label,"key","baz","value","five","count",1L),result.next());
                    assertEquals(map("label",label,"key","bar","value","four","count",2L),result.next());
                    assertEquals(map("label",label,"key","bar","value","three","count",1L),result.next());
                    assertFalse(result.hasNext());
                });
    }

}
