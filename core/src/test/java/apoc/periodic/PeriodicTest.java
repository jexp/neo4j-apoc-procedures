package apoc.periodic;

import apoc.util.MapUtil;
import apoc.util.TestUtil;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.neo4j.common.DependencyResolver;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.TransientTransactionFailureException;
import org.neo4j.graphdb.schema.ConstraintDefinition;
import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.graphdb.schema.Schema;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.kernel.api.KernelTransactionHandle;
import org.neo4j.kernel.impl.api.KernelTransactions;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static apoc.util.TestUtil.testCall;
import static apoc.util.TestUtil.testResult;
import static apoc.util.Util.map;
import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.neo4j.driver.internal.util.Iterables.count;

public class PeriodicTest {

    public static final long RUNDOWN_COUNT = 1000;
    public static final int BATCH_SIZE = 399;

    @Rule
    public DbmsRule db = new ImpermanentDbmsRule();

    @Before
    public void initDb() throws Exception {
        TestUtil.registerProcedure(db, Periodic.class);
        db.executeTransactionally("call apoc.periodic.list() yield name call apoc.periodic.cancel(name) yield name as name2 return count(*)");
    }

    @Test
    public void testSubmitStatement() throws Exception {
        String callList = "CALL apoc.periodic.list()";
        // force pre-caching the queryplan
        assertFalse(db.executeTransactionally(callList, Collections.emptyMap(), Result::hasNext));

        testCall(db, "CALL apoc.periodic.submit('foo','create (:Foo)')",
                (row) -> {
                    assertEquals("foo", row.get("name"));
                    assertEquals(false, row.get("done"));
                    assertEquals(false, row.get("cancelled"));
                    assertEquals(0L, row.get("delay"));
                    assertEquals(0L, row.get("rate"));
                });

        long count = tryReadCount(50, "MATCH (:Foo) RETURN COUNT(*) AS count", 1L);

        assertThat(count, equalTo(1L));

        testCall(db, callList, (r) -> assertEquals(true, r.get("done")));
    }

    @Test
    public void testSubmitStatementWithParams() throws Exception {
        String callList = "CALL apoc.periodic.list()";
        // force pre-caching the queryplan
        assertFalse(db.executeTransactionally(callList, Collections.emptyMap(), Result::hasNext));

        testCall(db, "CALL apoc.periodic.submit('foo','create (:Foo { id: $id })', { id: '(╯°□°)╯︵ ┻━┻' })",
                (row) -> {
                    assertEquals("foo", row.get("name"));
                    assertEquals(false, row.get("done"));
                    assertEquals(false, row.get("cancelled"));
                    assertEquals(0L, row.get("delay"));
                    assertEquals(0L, row.get("rate"));
                });

        long count = tryReadCount(50, "MATCH (:Foo { id: '(╯°□°)╯︵ ┻━┻' }) RETURN COUNT(*) AS count", 1L);

        assertThat(count, equalTo(1L));

        testCall(db, callList, (r) -> assertEquals(true, r.get("done")));
    }

    @Test
    public void testSlottedRuntime() throws Exception {
        assertTrue(Periodic.slottedRuntime("MATCH (n) RETURN n").contains("cypher runtime=slotted "));
        assertFalse(Periodic.slottedRuntime("cypher runtime=compiled MATCH (n) RETURN n").contains("cypher runtime=slotted "));
        assertFalse(Periodic.slottedRuntime("cypher runtime=compiled MATCH (n) RETURN n").contains("cypher runtime=slotted cypher"));
        assertTrue(Periodic.slottedRuntime("cypher 3.1 MATCH (n) RETURN n").contains(" runtime=slotted "));
        assertFalse(Periodic.slottedRuntime("cypher 3.1 MATCH (n) RETURN n").contains(" runtime=slotted cypher "));
        assertTrue(Periodic.slottedRuntime("cypher expressionEngine=compiled MATCH (n) RETURN n").contains(" runtime=slotted "));
        assertFalse(Periodic.slottedRuntime("cypher expressionEngine=compiled MATCH (n) RETURN n").contains(" runtime=slotted cypher"));

    }
    @Test
    public void testTerminateCommit() throws Exception {
        PeriodicTestUtils.testTerminatePeriodicQuery(db, "CALL apoc.periodic.commit('UNWIND range(0,1000) as id WITH id CREATE (:Foo {id: id}) limit 1000', {})");
    }

    @Test(expected = QueryExecutionException.class)
    public void testPeriodicCommitWithoutLimitShouldFail() {
        db.executeTransactionally("CALL apoc.periodic.commit('return 0')");
    }

    @Test
    public void testRunDown() throws Exception {
        db.executeTransactionally("UNWIND range(1,$count) AS id CREATE (n:Person {id:id})", MapUtil.map("count", RUNDOWN_COUNT));

        String query = "MATCH (p:Person) WHERE NOT p:Processed WITH p LIMIT $limit SET p:Processed RETURN count(*)";

        testCall(db, "CALL apoc.periodic.commit($query,$params)", MapUtil.map("query", query, "params", MapUtil.map("limit", BATCH_SIZE)), r -> {
            assertEquals((long) Math.ceil((double) RUNDOWN_COUNT / BATCH_SIZE), r.get("executions"));
            assertEquals(RUNDOWN_COUNT, r.get("updates"));
        });
        assertEquals(RUNDOWN_COUNT, (long)db.executeTransactionally("MATCH (p:Processed) RETURN COUNT(*) AS c", Collections.emptyMap(), result -> Iterators.single(result.columnAs("c"))));
    }



    private final static String KILL_PERIODIC_QUERY = "call dbms.listQueries() yield queryId, query, status\n" +
            "with * where query contains ('apoc.' + 'periodic')\n" +
            "call dbms.killQuery(queryId) yield queryId as killedId\n" +
            "return killedId";


    @Test
    public void testPeriodicIterateErrors() {
        final String newline = System.lineSeparator();
        testResult(db, "CALL apoc.periodic.iterate('UNWIND range(0,99) as id RETURN id', 'CREATE null', {batchSize:10,iterateList:true})", result -> {
            Map<String, Object> row = Iterators.single(result);
            assertEquals(10L, row.get("batches"));
            assertEquals(100L, row.get("total"));
            assertEquals(0L, row.get("committedOperations"));
            assertEquals(100L, row.get("failedOperations"));
            assertEquals(10L, row.get("failedBatches"));
            Map<String, Object> batchErrors = map("org.neo4j.graphdb.QueryExecutionException: Parentheses are required to identify nodes in patterns, i.e. (null) (line 1, column 55 (offset: 54))\n" +
                    "\"UNWIND $_batch AS _batch WITH _batch.id AS id  CREATE null\"" + newline +
                    "                                                       ^", 10L);

            assertEquals(batchErrors, ((Map) row.get("batch")).get("errors"));
            Map<String, Object> operationsErrors = map("Parentheses are required to identify nodes in patterns, i.e. (null) (line 1, column 55 (offset: 54))\n" +
                    "\"UNWIND $_batch AS _batch WITH _batch.id AS id  CREATE null\"" + newline +
                    "                                                       ^", 10L);
            assertEquals(operationsErrors, ((Map) row.get("operations")).get("errors"));
        });
    }

    @Test
    public void testTerminateIterate() throws Exception {
        PeriodicTestUtils.testTerminatePeriodicQuery(db, "CALL apoc.periodic.iterate('UNWIND range(0,1000) as id RETURN id', 'WITH $id as id CREATE (:Foo {id: $id})', {batchSize:1,parallel:true})");
        PeriodicTestUtils.testTerminatePeriodicQuery(db, "CALL apoc.periodic.iterate('UNWIND range(0,1000) as id RETURN id', 'WITH $id as id CREATE (:Foo {id: $id})', {batchSize:10,iterateList:true})");
        PeriodicTestUtils.testTerminatePeriodicQuery(db, "CALL apoc.periodic.iterate('UNWIND range(0,1000) as id RETURN id', 'WITH $id as id CREATE (:Foo {id: $id})', {batchSize:10,iterateList:false})");
    }

    /**
     * test for https://github.com/neo4j-contrib/neo4j-apoc-procedures/issues/1314
     * note that this test might depend on timings on your machine
     * prior to fixing #1314 this test fails sporadic (~ in 4 out of 5 attempts) with a
     * java.nio.channels.ClosedChannelException upon db.shutdown
     */
    @Test
    public void terminateIterateShouldNotFailonShutdown() throws Exception {

        long totalNumberOfNodes = 100000;
        int batchSizeCreate = 10000;

        db.executeTransactionally("call apoc.periodic.iterate( " +
                "'unwind range(0,$totalNumberOfNodes) as i return i', " +
                "'create (p:Person{name:\"person_\" + i})', " +
                "{batchSize:$batchSizeCreate, parallel:true, params: {totalNumberOfNodes: $totalNumberOfNodes}})",
                org.neo4j.internal.helpers.collection.MapUtil.map(
                        "totalNumberOfNodes", totalNumberOfNodes,
                        "batchSizeCreate", batchSizeCreate
                ));

        Thread thread = new Thread( () -> {
            try {
                db.executeTransactionally("call apoc.periodic.iterate( " +
                        "'match (p:Person) return p', " +
                        "'set p.name = p.name + \"ABCDEF\"', " +
                        "{batchSize:100, parallel:true, concurrency:20})");

            } catch (TransientTransactionFailureException e) {
                 //this exception is expected due to killPeriodicQueryAsync
            }
        });
        thread.start();

        DependencyResolver dependencyResolver = ((GraphDatabaseAPI) db).getDependencyResolver();
        KernelTransactions kernelTransactions = dependencyResolver.resolveDependency(KernelTransactions.class);

        // wait until we've started processing by checking queryIds incrementing
        while (maxQueryId(kernelTransactions) < (totalNumberOfNodes / batchSizeCreate) + 20 ) {
            Thread.sleep(200);
        }

        PeriodicTestUtils.killPeriodicQueryAsync(db);
        thread.join();
    }

    private Long maxQueryId(KernelTransactions kernelTransactions) {
        LongStream longStream = kernelTransactions.activeTransactions().stream()
                .map(KernelTransactionHandle::executingQuery)
                .filter(Optional::isPresent)
                .mapToLong(executingQuery ->  executingQuery.get().internalQueryId()
                );
        return longStream.max().orElse(0l);
    }

    @Test
    public void testIteratePrefixGiven() throws Exception {
        db.executeTransactionally("UNWIND range(1,100) AS x CREATE (:Person{name:'Person_'+x})");

        testResult(db, "CALL apoc.periodic.iterate('match (p:Person) return p', 'WITH $p as p SET p.lastname =p.name REMOVE p.name', {batchSize:10,parallel:true})", result -> {
            Map<String, Object> row = Iterators.single(result);
            assertEquals(10L, row.get("batches"));
            assertEquals(100L, row.get("total"));
        });

        testCall(db,
                "MATCH (p:Person) where p.lastname is not null return count(p) as count",
                row -> assertEquals(100L, row.get("count"))
        );
    }

    @Test
    public void testIterate() throws Exception {
        db.executeTransactionally("UNWIND range(1,100) AS x CREATE (:Person{name:'Person_'+x})");

        testResult(db, "CALL apoc.periodic.iterate('match (p:Person) return p', 'SET p.lastname =p.name REMOVE p.name', {batchSize:10,parallel:true})", result -> {
            Map<String, Object> row = Iterators.single(result);
            assertEquals(10L, row.get("batches"));
            assertEquals(100L, row.get("total"));
        });

        testCall(db,
                "MATCH (p:Person) where p.lastname is not null return count(p) as count",
                row -> assertEquals(100L, row.get("count"))
        );
    }

    @Test
    public void testIterateUpdateStats() {
        testResult(db, "CALL apoc.periodic.iterate(" +
                "'UNWIND range(1, 100) AS x RETURN x', " +
                "'CREATE (n:Node {x:x})" +
                "   SET n.y = 1 " +
                " CREATE (n)-[:SELF]->(n)'," +
                "{ batchSize:10, parallel:true })", result -> {
            Map<String, Object> row = Iterators.single(result);
            assertNotNull(row.get("updateStatistics"));
            Map<String, Long> updateStats = (Map<String, Long>) row.get("updateStatistics");
            assertNotNull(updateStats);
            assertEquals(100, (long) updateStats.get("nodesCreated"));
            assertEquals(0, (long) updateStats.get("nodesDeleted"));
            assertEquals(100, (long) updateStats.get("relationshipsCreated"));
            assertEquals(0, (long) updateStats.get("relationshipsDeleted"));
            assertEquals(200, (long) updateStats.get("propertiesSet"));
            assertEquals(100, (long) updateStats.get("labelsAdded"));
            assertEquals(0, (long) updateStats.get("labelsRemoved"));
        });

        testResult(db, "CALL apoc.periodic.iterate(" +
                "'MATCH (n:Node) RETURN n', " +
                "'REMOVE n:Node', " +
                "{ batchSize:10, parallel:true })", result -> {
            Map<String, Object> row = Iterators.single(result);
            assertNotNull(row.get("updateStatistics"));
            Map<String, Long> updateStats = (Map<String, Long>) row.get("updateStatistics");
            assertNotNull(updateStats);
            assertEquals(0, (long) updateStats.get("nodesCreated"));
            assertEquals(0, (long) updateStats.get("nodesDeleted"));
            assertEquals(0, (long) updateStats.get("relationshipsCreated"));
            assertEquals(0, (long) updateStats.get("relationshipsDeleted"));
            assertEquals(0, (long) updateStats.get("propertiesSet"));
            assertEquals(0, (long) updateStats.get("labelsAdded"));
            assertEquals(100, (long) updateStats.get("labelsRemoved"));
        });

        testResult(db, "CALL apoc.periodic.iterate(" +
                "'MATCH (n) RETURN n', " +
                "'DETACH DELETE n', " +
                "{ batchSize:10, parallel:true })", result -> {
            Map<String, Object> row = Iterators.single(result);
            assertNotNull(row.get("updateStatistics"));
            Map<String, Long> updateStats = (Map<String, Long>) row.get("updateStatistics");
            assertNotNull(updateStats);
            assertEquals(0, (long) updateStats.get("nodesCreated"));
            assertEquals(100, (long) updateStats.get("nodesDeleted"));
            assertEquals(0, (long) updateStats.get("relationshipsCreated"));
            assertEquals(100, (long) updateStats.get("relationshipsDeleted"));
            assertEquals(0, (long) updateStats.get("propertiesSet"));
            assertEquals(0, (long) updateStats.get("labelsAdded"));
            assertEquals(0, (long) updateStats.get("labelsRemoved"));
        });

    }

    @Test
    public void testIteratePrefix() throws Exception {
        db.executeTransactionally("UNWIND range(1,100) AS x CREATE (:Person{name:'Person_'+x})");

        testResult(db, "CALL apoc.periodic.iterate('match (p:Person) return p', 'SET p.lastname =p.name REMOVE p.name', {batchSize:10,parallel:true})", result -> {
            Map<String, Object> row = Iterators.single(result);
            assertEquals(10L, row.get("batches"));
            assertEquals(100L, row.get("total"));
        });

        testCall(db,
                "MATCH (p:Person) where p.lastname is not null return count(p) as count",
                row -> assertEquals(100L, row.get("count"))
        );
    }

    @Test
    public void testIteratePassThroughBatch() throws Exception {
        db.executeTransactionally("UNWIND range(1,100) AS x CREATE (:Person{name:'Person_'+x})");

        testResult(db, "CALL apoc.periodic.iterate('match (p:Person) return p', 'UNWIND $_batch AS batch WITH batch.p AS p  SET p.lastname =p.name REMOVE p.name', {batchSize:10,parallel:true, batchMode: 'BATCH_SINGLE'})", result -> {
            Map<String, Object> row = Iterators.single(result);
            assertEquals(10L, row.get("batches"));
            assertEquals(100L, row.get("total"));
        });

        testCall(db,
                "MATCH (p:Person) where p.lastname is not null return count(p) as count",
                row -> assertEquals(100L, row.get("count"))
        );
    }

    @Test
    public void testIterateBatch() throws Exception {
        db.executeTransactionally("UNWIND range(1,100) AS x CREATE (:Person{name:'Person_'+x})");

        testResult(db, "CALL apoc.periodic.iterate('match (p:Person) return p', 'SET p.lastname = p.name REMOVE p.name', {batchSize:10, iterateList:true, parallel:true})", result -> {
            Map<String, Object> row = Iterators.single(result);
            assertEquals(10L, row.get("batches"));
            assertEquals(100L, row.get("total"));
        });

        testCall(db,
                "MATCH (p:Person) where p.lastname is not null return count(p) as count",
                row -> assertEquals(100L, row.get("count"))
        );
    }

    @Test
    public void testIterateBatchPrefix() throws Exception {
        db.executeTransactionally("UNWIND range(1,100) AS x CREATE (:Person{name:'Person_'+x})");

        testResult(db, "CALL apoc.periodic.iterate('match (p:Person) return p', 'SET p.lastname = p.name REMOVE p.name', {batchSize:10, iterateList:true, parallel:true})", result -> {
            Map<String, Object> row = Iterators.single(result);
            assertEquals(10L, row.get("batches"));
            assertEquals(100L, row.get("total"));
        });

        testCall(db,
                "MATCH (p:Person) where p.lastname is not null return count(p) as count",
                row -> assertEquals(100L, row.get("count"))
        );
    }

    @Test
    public void testIterateWithReportingFailed() throws Exception {
        testResult(db, "CALL apoc.periodic.iterate('UNWIND range(-5, 5) AS x RETURN x', 'return sum(1000/x)', {batchSize:3, failedParams:9999})", result -> {
            Map<String, Object> row = Iterators.single(result);
            assertEquals(4L, row.get("batches"));
            assertEquals(1L, row.get("failedBatches"));
            assertEquals(11L, row.get("total"));
            Map<String, List<Map<String, Object>>> failedParams = (Map<String, List<Map<String, Object>>>) row.get("failedParams");
            assertEquals(1, failedParams.size());
            List<Map<String, Object>> failedParamsForBatch = failedParams.get("1");
            assertEquals( 3, failedParamsForBatch.size());

            List<Object> values = stream(failedParamsForBatch.spliterator(),false).map(map -> map.get("x")).collect(toList());
            assertEquals(values, Stream.of(-2l, -1l, 0l).collect(toList()));
        });
    }

    @Test
    public void testIterateRetries() throws Exception {
        testResult(db, "CALL apoc.periodic.iterate('return 1', 'CREATE (n {prop: 1/$_retry})', {retries:1})", result -> {
            Map<String, Object> row = Iterators.single(result);
            assertEquals(1L, row.get("batches"));
            assertEquals(1L, row.get("total"));
            assertEquals(1L, row.get("retries"));
        });
    }

    @Test
    public void testIterateFail() throws Exception {
        db.executeTransactionally("UNWIND range(1,100) AS x CREATE (:Person{name:'Person_'+x})");
        testResult(db, "CALL apoc.periodic.iterate('match (p:Person) return p', 'WITH $p as p SET p.lastname = p.name REMOVE x.name', {batchSize:10,parallel:true})", result -> {
            Map<String, Object> row = Iterators.single(result);
            assertEquals(10L, row.get("batches"));
            assertEquals(100L, row.get("total"));
            assertEquals(100L, row.get("failedOperations"));
            assertEquals(0L, row.get("committedOperations"));
            Map<String,Object> failedParams = (Map<String, Object>) row.get("failedParams");
            assertTrue(failedParams.isEmpty());
        });

        testCall(db,
                "MATCH (p:Person) where p.lastname is not null return count(p) as count",
                row -> assertEquals(0L, row.get("count"))
        );
    }



    @Test
    public void testCountdown() {
        int startValue = 3;
        int rate = 1;

        db.executeTransactionally("CREATE (counter:Counter {c: $startValue})", Collections.singletonMap("startValue", startValue));
        String statementToRepeat = "MATCH (counter:Counter) SET counter.c = counter.c - 1 RETURN counter.c as count";

        Map<String, Object> params = map("statement", statementToRepeat, "rate", rate);
        testResult(db, "CALL apoc.periodic.countdown('decrement', $statement, $rate)", params, r -> {
            try {
                // Number of iterations per rate (in seconds)
                Thread.sleep(startValue * rate * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            long count = TestUtil.singleResultFirstColumn(db, "MATCH (counter:Counter) RETURN counter.c as c");
            assertEquals(0L, count);
        });
    }

    @Test
    public void testRepeatParams() {
        db.executeTransactionally(
                "CALL apoc.periodic.repeat('repeat-params', 'MERGE (person:Person {name: $nameValue})', 2, {params: {nameValue: 'John Doe'}} ) YIELD name RETURN name" );
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {

        }

        testCall(db,
                "MATCH (p:Person {name: 'John Doe'}) RETURN p.name AS name",
                row -> assertEquals( row.get( "name" ), "John Doe" )
        );
    }

    private long tryReadCount(int maxAttempts, String statement, long expected) throws InterruptedException {
        int attempts = 0;
        long count;
        do {
            Thread.sleep(100);
            attempts++;
            count = TestUtil.singleResultFirstColumn(db, statement);
            System.out.println("for " + statement + " we have "+ count + " results");
        } while (attempts < maxAttempts && count != expected);
        return count;
    }

    @Test(expected = QueryExecutionException.class)
    public void testCommitFail() {
        final String query = "CALL apoc.periodic.commit('UNWIND range(0,1000) as id WITH id CREATE (::Foo {id: id}) limit 1000', {})";
        testFail(query);
    }

    @Test(expected = QueryExecutionException.class)
    public void testSubmitFail() {
        final String query = "CALL apoc.periodic.submit('foo','create (::Foo)')";
        testFail(query);
    }

    @Test(expected = QueryExecutionException.class)
    public void testRepeatFail() {
        final String query = "CALL apoc.periodic.repeat('repeat-params', 'MERGE (person:Person {name: $nameValue})', 2, {params: {nameValue: 'John Doe'}}) YIELD name RETURN nam";
        testFail(query);
    }

    @Test(expected = QueryExecutionException.class)
    public void testCountdownFail() {
        final String query = "CALL apoc.periodic.countdown('decrement', 'MATCH (counter:Counter) SET counter.c == counter.c - 1 RETURN counter.c as count', 1)";
        testFail(query);
    }


    @Test(expected = QueryExecutionException.class)
    public void testIterateQueryFail() {
        final String query = "CALL apoc.periodic.iterate('UNWIND range(0, 1000) as id RETURN ids', " +
                "'WITH $id as id CREATE (:Foo {id: $id})', " +
                "{batchSize:1,parallel:true})";
        testFail(query);
    }

    @Test
    public void testTruncate() {
        createDatasetForTruncate();

        TestUtil.testCallEmpty(db, "CALL apoc.periodic.truncate", Collections.emptyMap());
        assertCountEntitiesAndIndexes(0, 0, 4,2);

        try(Transaction tx = db.beginTx()) {
            Schema schema = tx.schema();
            schema.getConstraints().forEach(ConstraintDefinition::drop);
            schema.getIndexes().forEach(IndexDefinition::drop);
            tx.commit();
        }

        assertCountEntitiesAndIndexes(0, 0, 0,0);
    }

    @Test
    public void testTruncateWithDropSchema() {
        createDatasetForTruncate();

        TestUtil.testCallEmpty(db, "CALL apoc.periodic.truncate({dropSchema: true})", Collections.emptyMap());
        assertCountEntitiesAndIndexes(0, 0, 0,0);
    }

    private void createDatasetForTruncate() {
        int iterations = 999;
        Map<String, Object> parameters = new HashMap<>(1);
        parameters.put("iterations", iterations);
        db.executeTransactionally("UNWIND range(1,$iterations) AS x CREATE (:One{name:'Person_'+x})-[:FOO {id: x}]->(:Two {surname: 'Two'+x})<-[:BAR {idBar: x}]-(:Three {other: x+'Three'})",
                parameters);

        db.executeTransactionally("CREATE INDEX ON :One(name)");
        db.executeTransactionally("CREATE CONSTRAINT ON (a:Two) ASSERT a.surname IS UNIQUE");
        db.executeTransactionally("CREATE INDEX ON :Three(other)");
        db.executeTransactionally("CREATE CONSTRAINT ON (a:Actor) ASSERT a.name IS UNIQUE");

        final int expectedNodes = iterations * 3;
        final int expectedRels = iterations * 2;
        assertCountEntitiesAndIndexes(expectedNodes, expectedRels, 4, 2);
    }
    
    @Test(expected = QueryExecutionException.class, timeout = 1000)
    public void testIterateQueryFailInvalidConcurrency() {
        final String query = "CALL apoc.periodic.iterate('UNWIND range(0, 10) AS x RETURN x', " +
                "'RETURN x', " +
                "{concurrency:0 ,parallel:true})";
        testFail(query);
    }

    private void assertCountEntitiesAndIndexes(long expectedNodes, long expectedRels, long expectedIndexes, long expectedContraints) {
        try (Transaction tx = db.beginTx()) {
            assertEquals(expectedNodes, count(tx.getAllNodes()));
            assertEquals(expectedRels, count(tx.getAllRelationships()));
            Schema schema = tx.schema();
            assertEquals(expectedIndexes, count(schema.getIndexes()));
            assertEquals(expectedContraints, count(schema.getConstraints()));
        }
    }

    @Test(expected = QueryExecutionException.class, timeout = 1000)
    public void testIterateQueryFailInvalidBatchSize() {
        final String query = "CALL apoc.periodic.iterate('UNWIND range(0, 10) AS x RETURN x', " +
                "'RETURN x', " +
                "{batchSize:0 ,parallel:true})";
        testFail(query);
    }

    private void testFail(String query) {
        testCall(db, query, row -> fail("The test should fail but it didn't"));
    }
}
