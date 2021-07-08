package apoc.atomic;

import apoc.util.TestUtil;
import org.apache.commons.lang.ArrayUtils;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static apoc.util.MapUtil.map;
import static apoc.util.TestUtil.testCall;
import static org.junit.Assert.*;

/**
 * @author AgileLARUS
 *
 * @since 26-06-17
 */
public class AtomicTest {

	@Rule
	public DbmsRule db = new ImpermanentDbmsRule();

	@Before public void setUp() throws Exception {
		TestUtil.registerProcedure(db, Atomic.class);
	}

	@Test
	public void testAddAndSubInteger(){
		db.executeTransactionally("CREATE (p:Person {name:'Tom'})");
		try(Transaction tx = db.beginTx()) {
			final Node node = tx.getAllNodes().stream().findFirst().orElse(null);
			node.setProperty("age", 1);
			tx.commit();
		}
		db.executeTransactionally("MATCH (n:Person {name:'Tom'}) CALL apoc.atomic.add(n,$property,$value) YIELD container RETURN count(*)",
				map("property","age","value",10));
		int age = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'}) RETURN n.age as age");
		assertEquals(11, age);
		db.executeTransactionally("MATCH (n:Person {name:'Tom'}) CALL apoc.atomic.subtract(n,$property,$value) YIELD container RETURN count(*)",
				map("property","age","value",5));
		int ageSub = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'}) RETURN n.age as age");
		assertEquals(6, ageSub);
	}

	@Test
	public void testAddAndSubFloat() {
		db.executeTransactionally("CREATE (p:Person {name:'Tom'})");
		try(Transaction tx = db.beginTx()) {
			final Node node = tx.getAllNodes().stream().findFirst().orElse(null);
			node.setProperty("age", 2.4F);
			tx.commit();
		}

		db.executeTransactionally("MATCH (n:Person {name:'Tom'}) CALL apoc.atomic.add(n,$property,$value) YIELD container RETURN count(*)",
				map("property","age","value",1.5F));
		float age = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'}) RETURN n.age as age");
		assertEquals(3.9F, age, 0);

		db.executeTransactionally("MATCH (n:Person {name:'Tom'}) CALL apoc.atomic.subtract(n,$property,$value) YIELD container RETURN count(*)",
				map("property","age","value",0.5F));
		float ageSub = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'}) RETURN n.age as age");
		assertEquals(3.4F, ageSub, 0);
	}

	@Test
	public void testAddLong(){
		db.executeTransactionally("CREATE (p:Person {name:'Tom',age: 40}) CREATE (c:Person {name:'John',age: 40}) CREATE (a:Person {name:'Anne',age: 22})");
		testCall(db, "MATCH (n:Person {name:'Tom'}) CALL apoc.atomic.add(n,$property,$value) YIELD container RETURN count(*)",map("property","age","value",10), (r) -> {});
		long age = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'}) RETURN n.age as age");
		assertEquals(50L, age);
	}

	@Test
	public void testAddLongRelationship(){
		db.executeTransactionally("CREATE (p:Person {name:'Tom',age: 40}) CREATE (c:Person {name:'John',age: 40}) CREATE (p)-[:KNOWS{since:1965}]->(c)");
		Relationship rel = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'})-[r:KNOWS]-(c) RETURN r;");
		testCall(db, "MATCH (n:Person {name:'Tom'})-[r:KNOWS]-(c) CALL apoc.atomic.add(r,$property,$value,$times) YIELD container RETURN count(*)",map("property","since","value",10,"times",5), (r) -> {});
		long since = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'})-[r:KNOWS]-(c) RETURN r.since as since;");
		assertEquals(1975L, since);
	}

	@Test
	public void testAddDouble(){
		db.executeTransactionally("CREATE (p:Person {name:'Tom',age: 40}) CREATE (c:Person {name:'John',age: "+ 35d +"}) CREATE (a:Person {name:'Anne',age: 22})");
		testCall(db, "MATCH (n:Person {name:'John'}) CALL apoc.atomic.add(n,$property,$value,$times) YIELD container RETURN count(*)",map("property","age","value",10,"times",5), (r) -> {});
		double age = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'John'}) RETURN n.age as age;");
		assertEquals(45d, age, 0.000001d);
	}

	@Test
	public void testSubLong(){
		db.executeTransactionally("CREATE (p:Person {name:'Tom',age: 40}) CREATE (c:Person {name:'John',age: 35}) CREATE (a:Person {name:'Anne',age: 22})");
		testCall(db, "MATCH (n:Person {name:'John'}) CALL apoc.atomic.subtract(n,$property,$value,$times) YIELD container RETURN count(*)",map("property","age","value",10,"times",5), (r) -> {});
		long age = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'John'}) RETURN n.age as age;");
		assertEquals(25L, age);
	}

	@Test
	public void testSubLongRelationship(){
		db.executeTransactionally("CREATE (p:Person {name:'Tom',age: 40}) CREATE (c:Person {name:'John',age: 40}) CREATE (p)-[:KNOWS{since:1965}]->(c)");
		testCall(db, "MATCH (n:Person {name:'Tom'})-[r:KNOWS]-(c) CALL apoc.atomic.subtract(r,$property,$value,$times) YIELD container RETURN count(*)",map("property","since","value",10,"times",5), (r) -> {});
		long since = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'})-[r:KNOWS]-(c) RETURN r.since as since;");
		assertEquals(1955L, since);
	}

	@Test
	public void testConcat(){
	    db.executeTransactionally("CREATE (p:Person {name:'Tom',age: 35})");
		testCall(db, "MATCH (n:Person {name:'Tom'}) CALL apoc.atomic.concat(n,$property,$value,$times) YIELD container RETURN count(*)",map("property","name","value","asson","times",5), (r) -> {});
		long age = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tomasson'}) RETURN n.age as age;");
		assertEquals(35L, age);
	}

	@Test
	public void testConcatRelationship(){
		db.executeTransactionally("CREATE (p:Person {name:'Angelo',age: 22}) CREATE (c:Company {name:'Larus'}) CREATE (p)-[:WORKS_FOR{role:\"software dev\"}]->(c)");
		testCall(db, "MATCH (n:Person {name:'Angelo'})-[r:WORKS_FOR]-(c) CALL apoc.atomic.concat(r,$property,$value,$times) YIELD container RETURN count(*)",map("property","role","value","eloper","times",5), (r) -> {});
		assertEquals("software developer", TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Angelo'})-[r:WORKS_FOR]-(c) RETURN r.role as role;"));
	}

	@Test
	public void testRemoveArrayValueLong(){
		db.executeTransactionally("CREATE (p:Person {name:'Tom',age: [40,50,60]})");
		testCall(db, "MATCH (n:Person {name:'Tom'})CALL apoc.atomic.remove(n,$property,$position,$times) YIELD container RETURN count(*)",map("property","age","position",1,"times",5), (r) -> {});

		long[] ages = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'}) RETURN n.age as age;");
        assertThat(ArrayUtils.toObject(ages), Matchers.arrayContaining(40l, 60l));
	}

    @Test
    public void testRemoveFirstElementArrayValueLong(){
        db.executeTransactionally("CREATE (p:Person {name:'Tom',age: [40,50,60]})");
        testCall(db, "MATCH (n:Person {name:'Tom'}) CALL apoc.atomic.remove(n,$property,$position,$times) YIELD container RETURN count(*)",map("property","age","position",0,"times",5), (r) -> {});
		long[] ages = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'}) RETURN n.age as age;");
		assertThat(ArrayUtils.toObject(ages), Matchers.arrayContaining(50L, 60L));
    }

    @Test
    public void testRemoveLastElementArrayValueLong(){
        db.executeTransactionally("CREATE (p:Person {name:'Tom',age: [40,50,60]})");
        testCall(db, "MATCH (n:Person {name:'Tom'}) CALL apoc.atomic.remove(n,$property,$position,$times) YIELD container RETURN count(*)",map("property","age","position",2,"times",5), (r) -> {});
		long[] ages = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'}) RETURN n.age as age;");
		assertThat(ArrayUtils.toObject(ages), Matchers.arrayContaining(40L, 50L));
    }

    @Test
    public void testRemoveLastItemArray(){
        db.executeTransactionally("CREATE (p:Person {name:'Tom',age: [40]})");
        testCall(db, "MATCH (n:Person {name:'Tom'}) CALL apoc.atomic.remove(n,$property,$position,$times) YIELD container RETURN count(*)",map("property","age","position",0,"times",5), (r) -> {});
		long[] ages = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'}) RETURN n.age as age;");
		assertTrue(ages.length == 0);
    }

    @Test(expected = RuntimeException.class)
    public void testRemoveOutOfArrayIndex(){
        db.executeTransactionally("CREATE (p:Person {name:'Tom',age: [40,50,60]})");
        testCall(db, "MATCH (n:Person {name:'Tom'}) CALL apoc.atomic.remove(n,$property,$position,$times) YIELD container RETURN count(*)",map("property","age","position",5,"times",5), (r) -> {});
		long[] ages = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'}) RETURN n.age as age;");
		assertThat(ArrayUtils.toObject(ages), Matchers.arrayContaining(40L, 50L, 60L));
    }

    @Test(expected = RuntimeException.class)
    public void testRemoveEmptyArray(){
        db.executeTransactionally("CREATE (p:Person {name:'Tom',age: []})");
        testCall(db, "MATCH (n:Person {name:'Tom'}) CALL apoc.atomic.remove($node,$property,$position,$times) YIELD container RETURN count(*)",map("property","age","position",5,"times",5), (r) -> {});
		long[] ages = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'}) RETURN n.age as age;");
    }

	@Test
	public void testInsertArrayValueLong(){
		db.executeTransactionally("CREATE (p:Person {name:'Tom',age: 40})");
		testCall(db, "MATCH (n:Person {name:'Tom'}) CALL apoc.atomic.insert(n,$property,$position,$value,$times) YIELD container RETURN count(*)",map("property","age","position",2,"value",55L,"times",5), (r) -> {});
		long[] ages = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'}) RETURN n.age as age;");
		assertThat(ArrayUtils.toObject(ages), Matchers.arrayContaining(40L, 55L));
	}

	@Test
	public void testInsertArrayValueLongRelationship() {
		db.executeTransactionally("CREATE (p:Person {name:'Tom',age: 40}) CREATE (c:Person {name:'John',age: 40}) CREATE (p)-[:KNOWS{since:[40,50,60]}]->(c)");
		testCall(db, "MATCH (n:Person {name:'Tom'})-[r:KNOWS]-(c) CALL apoc.atomic.insert(r,$property,$position,$value,$times) YIELD container RETURN count(*)", map("property", "since", "position", 2, "value", 55L, "times", 5), (r) -> {
		});
		long[] ages = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'})-[r:KNOWS]-(c) RETURN r.since as since;");
		assertThat(ArrayUtils.toObject(ages), Matchers.arrayContaining(40L, 50L, 55L, 60L));
	}

	@Test
	public void testUpdateNode(){
		db.executeTransactionally("CREATE (p:Person {name:'Tom',salary1: 1800, salary2:1500})");
		testCall(db, "MATCH (n:Person {name:'Tom'}) CALL apoc.atomic.update(n,$property,$operation,$times) YIELD container RETURN count(*)",map("property","salary1","operation","n.salary1 + n.salary2","times",5), (r) -> {});
		long salary = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'}) RETURN n.salary1 as salary;");
		assertEquals(3300L, salary);
	}

	@Test
	public void testUpdateRel(){
		db.executeTransactionally("CREATE (t:Person {name:'Tom'})-[:KNOWS {forYears:5}]->(m:Person {name:'Mary'})");
		testCall(db, "MATCH (t:Person {name:'Tom'})-[r:KNOWS]->(m:Person {name:'Mary'}) CALL apoc.atomic.update(r,$property,$operation,$times) YIELD container RETURN count(*)",
				map("property","forYears","operation","n.forYears *3 + n.forYears","times",5), (r) -> {});
		long forYears = TestUtil.singleResultFirstColumn(db, "MATCH (t:Person {name:'Tom'})-[r:KNOWS]->(m:Person {name:'Mary'}) RETURN r.forYears as forYears;");
		assertEquals(20L, forYears);
	}

	@Test
	public void testConcurrentAdd() throws Exception {
        db.executeTransactionally("CREATE (p:Person {name:'Tom',age: 40})");
        ExecutorService executorService = Executors.newFixedThreadPool(2);

		Runnable task = () -> {
			db.executeTransactionally("MATCH (p:Person {name:'Tom'}) WITH p CALL apoc.atomic.add(p,'age',10, 5) YIELD oldValue, newValue RETURN *");
		};

		Runnable task2 = () -> {
			db.executeTransactionally("MATCH (p:Person {name:'Tom'}) WITH p CALL apoc.atomic.add(p,'age',10, 5) YIELD oldValue, newValue RETURN *");
		};

		executorService.execute(task);
		executorService.execute(task2);
        executorService.shutdown();
		executorService.awaitTermination(2, TimeUnit.SECONDS);

		long age = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'}) RETURN n.age as age;");
		assertEquals(60L, age);
	}

	@Test
	public void testConcurrentSubtract() throws Exception {
        db.executeTransactionally("CREATE (p:Person {name:'Tom',age: 40})");
		ExecutorService executorService = Executors.newFixedThreadPool(2);

		Runnable task = () -> {
			db.executeTransactionally("MATCH (p:Person {name:'Tom'}) WITH p CALL apoc.atomic.subtract(p,'age',10, 5) YIELD oldValue, newValue RETURN *");
		};

		Runnable task2 = () -> {
			db.executeTransactionally("MATCH (p:Person {name:'Tom'}) WITH p CALL apoc.atomic.subtract(p,'age',10, 5) YIELD oldValue, newValue RETURN *");
		};

		executorService.execute(task);
		executorService.execute(task2);
        executorService.shutdown();
		executorService.awaitTermination(2, TimeUnit.SECONDS);

		long age = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'}) RETURN n.age as age;");
		assertEquals(20L, age);
	}

	@Test
	public void testConcurrentConcat() throws Exception {
		Long nodeId = TestUtil.singleResultFirstColumn(db, "CREATE (n:Person {name:'Tom', age: 40}) RETURN id(n) AS id;");
        ExecutorService executorService = Executors.newFixedThreadPool(2);

		Runnable task = () -> {
			db.executeTransactionally("MATCH (n) WHERE id(n) = $nodeId CALL apoc.atomic.concat(n,$property,$value,$times) YIELD container RETURN count(*)", map("nodeId", nodeId, "property","name","value","asson","times",5));
		};

		Runnable task2 = () -> {
			db.executeTransactionally("MATCH (n) WHERE id(n) = $nodeId CALL apoc.atomic.concat(n,$property,$value,$times) YIELD container RETURN count(*)", map("nodeId", nodeId, "property","name","value","s","times",5));
		};

		executorService.execute(task);
		executorService.execute(task2);
        executorService.shutdown();
		executorService.awaitTermination(2, TimeUnit.SECONDS);

		String name = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person) return n.name as name;");
		assertEquals(9, name.length());
	}

	@Test
	public void testConcurrentInsert() throws InterruptedException {
		db.executeTransactionally("CREATE (p:Person {name:'Tom',age: 40})");
		ExecutorService executorService = Executors.newFixedThreadPool(2);

        Runnable task = () -> {
			db.executeTransactionally("MATCH (n:Person {name:'Tom'}) CALL apoc.atomic.insert(n,$property,$position,$value,$times) YIELD container RETURN count(*)", map("property","age","position",2,"value",41L,"times",5));
		};

		Runnable task2 = () -> {
			db.executeTransactionally("MATCH (n:Person {name:'Tom'}) CALL apoc.atomic.insert(n,$property,$position,$value,$times) YIELD container RETURN count(*)", map("property","age","position",2,"value",42L,"times",5));
		};

		executorService.execute(task);
		executorService.execute(task2);
        executorService.shutdown();
        executorService.awaitTermination(2, TimeUnit.SECONDS);

		long[] age = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'}) RETURN n.age as age;");
		assertEquals(3, age.length);
	}

	@Test
	public void testConcurrentRemove() throws InterruptedException {
        db.executeTransactionally("CREATE (p:Person {name:'Tom',age: [40,50,60]}) CREATE (c:Person {name:'John',age: 40}) CREATE (a:Person {name:'Anne',age: 22})");
        Node node = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'}) return n;");
		ExecutorService executorService = Executors.newFixedThreadPool(2);

		Runnable task = () -> {
		    try (Transaction tx = db.beginTx()) {
                System.out.println("tx 1 " + System.identityHashCode(tx));
		        tx.execute("MATCH (n:Person {name:'Tom'}) CALL apoc.atomic.remove(n,$property,$position,$times) YIELD container RETURN count(*)",map("property","age","position",0,"times",5));
		        tx.commit();
            }
//            db.executeTransactionally("CALL apoc.atomic.remove($node,$property,$position,$times)",map("node",node,"property","age","position",0,"times",5));
        };

        Runnable task2 = () -> {
            try (Transaction tx = db.beginTx()) {
                System.out.println("tx 2 " + System.identityHashCode(tx));
                tx.execute("MATCH (n:Person {name:'Tom'}) CALL apoc.atomic.remove(n,$property,$position,$times) YIELD container RETURN count(*)",map("property","age","position",1,"times",5));
                tx.commit();
            }
//            db.executeTransactionally("CALL apoc.atomic.remove($node,$property,$position,$times)",map("node",node,"property","age","position",1,"times",5));
		};

		executorService.execute(task);
		executorService.execute(task2);
        executorService.shutdown();
		executorService.awaitTermination(10, TimeUnit.SECONDS);

		long[] age = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'}) RETURN n.age as age;");
        assertEquals(1 , age.length);
	}

    @Test
    public void testConcurrentUpdate() throws Exception {
        db.executeTransactionally("CREATE (p:Person {name:'Tom',salary1: 100, salary2: 100})");
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        Runnable task = () -> {
            db.executeTransactionally("MATCH (p:Person {name:'Tom'}) WITH p CALL apoc.atomic.update(p,'salary1','n.salary1 - n.salary2',5) YIELD oldValue, newValue RETURN *");
        };

        Runnable task2 = () -> {
            db.executeTransactionally("MATCH (p:Person {name:'Tom'}) WITH p CALL apoc.atomic.update(p,'salary1','n.salary1 + n.salary2',5) YIELD oldValue, newValue RETURN *");
        };

        executorService.execute(task);
        executorService.execute(task2);
        executorService.shutdown();
        executorService.awaitTermination(2, TimeUnit.SECONDS);

		long salary = TestUtil.singleResultFirstColumn(db, "MATCH (n:Person {name:'Tom'}) RETURN n.salary1 as salary;");
        assertEquals(100L, salary);
    }
}
