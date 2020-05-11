package apoc.periodic;

import apoc.ApocConfig;
import apoc.load.Jdbc;
import apoc.util.MapUtil;
import apoc.util.TestUtil;
import apoc.util.Utils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ProvideSystemProperty;
import org.neo4j.common.DependencyResolver;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.TransientTransactionFailureException;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.kernel.api.KernelTransactionHandle;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.impl.api.KernelTransactions;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PeriodicTest {

    public static final long RUNDOWN_COUNT = 1000;
    public static final int BATCH_SIZE = 399;

    @Rule
    public DbmsRule db = new ImpermanentDbmsRule();

    @Rule
    public final ProvideSystemProperty systemPropertyRule = new ProvideSystemProperty("apoc.semaphore.one", "1")
            .and("apoc.semaphore.two", "2");

    @Before
    public void initDb() throws Exception {
        TestUtil.registerProcedure(db, Periodic.class, Jdbc.class, Utils.class);
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
        testTerminatePeriodicQuery("CALL apoc.periodic.commit('UNWIND range(0,1000) as id WITH id CREATE (:Foo {id: id}) limit 1000', {})");
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

    @Test
    public void testRock_n_roll() throws Exception {
        // setup
        db.executeTransactionally("UNWIND range(1,100) AS x CREATE (:Person{name:'Person_'+x})");

        // when&then

        testResult(db, "CALL apoc.periodic.rock_n_roll('match (p:Person) return p', 'WITH $p as p SET p.lastname =p.name REMOVE p.name', 10)", result -> {
            Map<String, Object> row = Iterators.single(result);
            assertEquals(10L, row.get("batches"));
            assertEquals(100L, row.get("total"));
        });
        // then
        testCall(db,
                "MATCH (p:Person) where p.lastname is not null return count(p) as count",
                row -> assertEquals(100L, row.get("count"))
        );
    }

    @Test
    public void testTerminateRockNRoll() throws Exception {
        testTerminatePeriodicQuery("CALL apoc.periodic.rock_n_roll('UNWIND range(0,1000) as id RETURN id', 'CREATE (:Foo {id: $id})', 10)");
    }

    public void testTerminatePeriodicQuery(String periodicQuery) {
        killPeriodicQueryAsync();
        try {
            testResult(db, periodicQuery, result -> {
                Map<String, Object> row = Iterators.single(result);
                assertEquals( periodicQuery + " result: " + row.toString(), true, row.get("wasTerminated"));
            });
            fail("Should have terminated");
        } catch(Exception tfe) {
            assertEquals(tfe.getMessage(),true, tfe.getMessage().contains("terminated"));
        }
    }

    private final static String KILL_PERIODIC_QUERY = "call dbms.listQueries() yield queryId, query, status\n" +
            "with * where query contains ('apoc.' + 'periodic')\n" +
            "call dbms.killQuery(queryId) yield queryId as killedId\n" +
            "return killedId";

    public void killPeriodicQueryAsync() {
        new Thread(() -> {
            int retries = 10;
            try {
                while (retries-- > 0 && !terminateQuery("apoc.periodic")) {
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                // ignore
            }
        }).start();
    }

    boolean terminateQuery(String pattern) {
        DependencyResolver dependencyResolver = ((GraphDatabaseAPI) db).getDependencyResolver();
        KernelTransactions kernelTransactions = dependencyResolver.resolveDependency(KernelTransactions.class);
        long numberOfKilledTransactions = kernelTransactions.activeTransactions().stream()
                .filter(kernelTransactionHandle ->
                        kernelTransactionHandle.executingQuery().map(query -> query.queryText().contains(pattern))
                                .orElse(false)
                )
                .map(kernelTransactionHandle -> kernelTransactionHandle.markForTermination(Status.Transaction.Terminated))
                .count();
        return numberOfKilledTransactions > 0;
    }

    @Test
    public void testIterateErrors() throws Exception {
        testResult(db, "CALL apoc.periodic.rock_n_roll('UNWIND range(0,99) as id RETURN id', 'CREATE (:Foo {id: 1 / ($id % 10)})', 10)", result -> {
            Map<String, Object> row = Iterators.single(result);
            assertEquals(10L, row.get("batches"));
            assertEquals(100L, row.get("total"));
            assertEquals(0L, row.get("committedOperations"));
            assertEquals(100L, row.get("failedOperations"));
            assertEquals(10L, row.get("failedBatches"));
            Map<String, Object> batchErrors = map("org.neo4j.graphdb.QueryExecutionException: / by zero", 10L);
            assertEquals(batchErrors, ((Map) row.get("batch")).get("errors"));
            Map<String, Object> operationsErrors = map("/ by zero", 10L);
            assertEquals(operationsErrors, ((Map) row.get("operations")).get("errors"));
        });
    }

    @Test
    public void testPeriodicIterateErrors() throws Exception {
        testResult(db, "CALL apoc.periodic.iterate('UNWIND range(0,99) as id RETURN id', 'CREATE null', {batchSize:10,iterateList:true})", result -> {
            Map<String, Object> row = Iterators.single(result);
            assertEquals(10L, row.get("batches"));
            assertEquals(100L, row.get("total"));
            assertEquals(0L, row.get("committedOperations"));
            assertEquals(100L, row.get("failedOperations"));
            assertEquals(10L, row.get("failedBatches"));
            Map<String, Object> batchErrors = map("org.neo4j.graphdb.QueryExecutionException: Parentheses are required to identify nodes in patterns, i.e. (null) (line 1, column 55 (offset: 54))\n" +
                    "\"UNWIND $_batch AS _batch WITH _batch.id AS id  CREATE null\"\n" +
                    "                                                       ^", 10L);

            assertEquals(batchErrors, ((Map) row.get("batch")).get("errors"));
            Map<String, Object> operationsErrors = map("Parentheses are required to identify nodes in patterns, i.e. (null) (line 1, column 55 (offset: 54))\n" +
                    "\"UNWIND $_batch AS _batch WITH _batch.id AS id  CREATE null\"\n" +
                    "                                                       ^", 10L);
            assertEquals(operationsErrors, ((Map) row.get("operations")).get("errors"));
        });
    }

    @Test
    public void testTerminateIterate() throws Exception {
        testTerminatePeriodicQuery("CALL apoc.periodic.iterate('UNWIND range(0,1000) as id RETURN id', 'WITH $id as id CREATE (:Foo {id: $id})', {batchSize:1,parallel:true})");
        testTerminatePeriodicQuery("CALL apoc.periodic.iterate('UNWIND range(0,1000) as id RETURN id', 'WITH $id as id CREATE (:Foo {id: $id})', {batchSize:10,iterateList:true})");
        testTerminatePeriodicQuery("CALL apoc.periodic.iterate('UNWIND range(0,1000) as id RETURN id', 'WITH $id as id CREATE (:Foo {id: $id})', {batchSize:10,iterateList:false})");
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

        killPeriodicQueryAsync();
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
    public void testIterateJDBC() throws Exception {
        TestUtil.ignoreException(() -> {
            testResult(db, "CALL apoc.periodic.iterate('call apoc.load.jdbc(\"jdbc:mysql://localhost:3306/northwind?user=root\",\"customers\")', 'create (c:Customer) SET c += $row', {batchSize:10,parallel:true})", result -> {
                Map<String, Object> row = Iterators.single(result);
                assertEquals(3L, row.get("batches"));
                assertEquals(29L, row.get("total"));
            });

            testCall(db,
                    "MATCH (p:Customer) return count(p) as count",
                    row -> assertEquals(29L, row.get("count"))
            );
        }, SQLException.class);
    }

    @Test
    public void testRock_n_roll_while() throws Exception {
        // setup
        db.executeTransactionally("UNWIND range(1,100) AS x CREATE (:Person{name:'Person_'+x})");

        // when&then
        testResult(db, "CALL apoc.periodic.rock_n_roll_while('return coalesce($previous,3)-1 as loop', 'match (p:Person) return p', 'MATCH (p) where p=$p SET p.lastname =p.name', 10)", result -> {
            long l = 0;
            while (result.hasNext()) {
                Map<String, Object> row = result.next();
                assertEquals(2L - l, row.get("loop"));
                assertEquals(10L, row.get("batches"));
                assertEquals(100L, row.get("total"));
                l += 1;
            }
            assertEquals(2L, l);
        });
        // then
        testCall(db,
                "MATCH (p:Person) where p.lastname is not null return count(p) as count",
                row -> assertEquals(100L, row.get("count"))
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
    public void testRockNRollWhileLoopFail() {
        final String query = "CALL apoc.periodic.rock_n_roll_while('return coalescence($previous, 3) - 1 as loop', " +
                "'match (p:Person) return p', " +
                "'MATCH (p) where p = {p} SET p.lastname = p.name', " +
                "10)";
        testFail(query);
    }

    @Test(expected = QueryExecutionException.class)
    public void testRockNRollWhileIterateFail() {
        final String query = "CALL apoc.periodic.rock_n_roll_while('return coalesce($previous, 3) - 1 as loop', " +
                "'match (p:Person) return pp', " +
                "'MATCH (p) where p = {p} SET p.lastname = p.name', " +
                "10)";
        testFail(query);
    }

    @Test(expected = QueryExecutionException.class)
    public void testIterateQueryFail() {
        final String query = "CALL apoc.periodic.iterate('UNWIND range(0, 1000) as id RETURN ids', " +
                "'WITH $id as id CREATE (:Foo {id: $id})', " +
                "{batchSize:1,parallel:true})";
        testFail(query);
    }

    @Test(expected = QueryExecutionException.class)
    public void testRockNRollIterateFail() {
        final String query = "CALL apoc.periodic.rock_n_roll('match (pp:Person) return p', " +
                "'WITH $p as p SET p.lastname = p.name REMOVE p.name', " +
                "10)";
        testFail(query);
    }

    @Test(expected = QueryExecutionException.class)
    public void testRockNRollActionFail() {
        final String query = "CALL apoc.periodic.rock_n_roll('match (p:Person) return p', " +
                "'WITH $p as p SET pp.lastname = p.name REMOVE p.name', " +
                "10)";
        testFail(query);
    }

    @Test(expected = QueryExecutionException.class)
    public void testRockNRollWhileFail() {
        final String query = "CALL apoc.periodic.rock_n_roll_while('return coalescence($previous, 3) - 1 as loop', " +
                "'match (p:Person) return pp', " +
                "'MATCH (p) where p = $p SET p.lastname = p.name', " +
                "10)";
        try {
            testFail(query);
        } catch (QueryExecutionException e) {
            String expected = "Failed to invoke procedure `apoc.periodic.rock_n_roll_while`: Caused by: java.lang.RuntimeException: Exception for field `cypherLoop`, message: Unknown function 'coalescence' (line 1, column 16 (offset: 15))\n" +
                    "\"EXPLAIN return coalescence($previous, 3) - 1 as loop\"\n" +
                    "                ^\n" +
                    "Exception for field `cypherIterate`, message: Variable `pp` not defined (line 1, column 33 (offset: 32))\n" +
                    "\"EXPLAIN match (p:Person) return pp\"\n" +
                    "                                 ^";
            assertEquals(expected, e.getMessage());
            throw e;
        }
    }

    @Test
    public void testPeriodicSemaphore() throws InterruptedException {
        long duration = 100;
        String cypher = "call apoc.periodic.iterate('return 1', 'call apoc.util.sleep($duration) return 1',{semaphore: $semaphore, params: {duration: $duration}}) ";
        db.executeTransactionally(cypher, map("duration", 1, "semaphore", "one")); // for heating up cypher query plan

        long millis = runMultipleTimes(2, cypher, duration, "one");
        assertTrue( "took " + millis, millis > 2 * duration );

        millis = runMultipleTimes(2, cypher, duration, "two");
        assertTrue( "took " + millis, millis < 2 * duration );
    }

    private long runMultipleTimes(int times, String cypher, long duration, String semaphore) {
        long now = System.currentTimeMillis();
        AtomicBoolean failureInThread = new AtomicBoolean(false);

        Map<String, Object> params = map("duration", duration);
        if (semaphore!=null) {
            params.put("semaphore", semaphore);
        }

        Set<Thread> threads = new HashSet<>();
        for (int i=0; i < times; i++) {
            threads.add(new Thread(() -> {
                try {
                    Map<String, Object> r = db.executeTransactionally(cypher, params, Iterators::single);
                    Map<String, Object> errorMessages = (Map<String, Object>) r.get("errorMessages");
                    if (!errorMessages.isEmpty()) {
                        failureInThread.set(true);
//                        System.out.println("failed: " + errorMessages);
                    }
                } catch (Exception e) {
                    failureInThread.set(true);
//                    System.out.println(e.getMessage());
                }
            }));
        }

        if (failureInThread.get()) {
            throw new RuntimeException("execution in child thread failed.");  // safety net
        }

        threads.forEach(Thread::start);
        threads.forEach(thread -> {
            try {
                thread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        return System.currentTimeMillis() - now;
    }

    @Test
    public void testDefaultSemaphore() {
        ApocConfig.apocConfig().setProperty(ApocConfig.APOC_SEMAPHORE_DEFAULT_NAME, "one");

        long duration = 100;
        String cypher = "call apoc.periodic.iterate('return 1', 'call apoc.util.sleep($duration) return 1',{params: {duration: $duration}}) "; // NOTE: we don't provide a semaphore name
        db.executeTransactionally(cypher, map("duration", 1)); // for heating up cypher query plan

        long millis = runMultipleTimes(2, cypher, duration, null);
        assertTrue( "took " + millis, millis > 2 * duration );

        ApocConfig.apocConfig().setProperty(ApocConfig.APOC_SEMAPHORE_DEFAULT_NAME, null);
    }

    private void testFail(String query) {
        testCall(db, query, row -> fail("The test should fail but it didn't"));
    }
}
