package apoc.concurrent;

import apoc.util.TestUtil;
import apoc.util.Utils;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.neo4j.common.DependencyResolver;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.internal.helpers.collection.MapUtil;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.impl.api.KernelTransactions;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SemaphoreProceduresTest {

    private final static String SEMAPHORE_SINGLE = "single";
    private final static String SEMAPHORE_ZERO = "zero";

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @ClassRule
    public static DbmsRule db = new ImpermanentDbmsRule();

    @BeforeClass
    public static void setUp() throws Exception {
        TestUtil.registerProcedure(db, SemaphoreProcedures.class, Utils.class);
        db.executeTransactionally("call apoc.concurrent.addSemaphore($semaphoreName, 1)", Collections.singletonMap("semaphoreName", SEMAPHORE_SINGLE));
        db.executeTransactionally("call apoc.concurrent.addSemaphore($semaphoreName, 0)", Collections.singletonMap("semaphoreName", SEMAPHORE_ZERO));
    }

    @Test
    public void failOnUndeclaredSemaphore() {
        thrown.expect(QueryExecutionException.class);
        thrown.expectMessage("java.lang.IllegalArgumentException: no declared semaphore: undeclaredSemaphore");
        String cypher = "call apoc.concurrent.runInSemaphore($name, 'return 1')";

        Map<String, Object> params = MapUtil.map("name", "undeclaredSemaphore");
        db.executeTransactionally(cypher, params, Iterators::single);
    }

    @Test
    public void testThatSemaphoreSerializesOperations() throws InterruptedException {
        long duration = 200;
        String cypher = "call apoc.concurrent.runInSemaphore($semaphoreName, 'call apoc.util.sleep($duration) return 1', {duration: $duration})";
        Map<String, Object> params = MapUtil.map(
                "duration", duration,
                "semaphoreName", "single"
        );
        db.executeTransactionally(cypher, params, Iterators::single); // ensure query plan cache gets populated
        long now = System.currentTimeMillis();

        Set<Thread> threads = Iterators.asSet(
                new Thread(() -> db.executeTransactionally(cypher, params, Iterators::single)),
                new Thread(() -> db.executeTransactionally(cypher, params, Iterators::single))
        );

        threads.forEach(Thread::start);
        threads.forEach(thread -> {
            try {
                thread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue( (System.currentTimeMillis()- now) >= 2*duration);
        long availablePermits = TestUtil.singleResultFirstColumn(db, "return apoc.concurrent.availablePermits($semaphoreName)", params);
        assertEquals(1l, availablePermits);
    }

    @Test
    public void testSemaphoreReleasedOnFailure() throws InterruptedException {
        String cypher = "call apoc.concurrent.runInSemaphore($semaphoreName, 'call does.not.exist() return 1')";
        Map<String, Object> params = MapUtil.map("semaphoreName", "single");
        Set<Thread> threads = Iterators.asSet(
                new Thread(() -> db.executeTransactionally(cypher, params, Iterators::single)),
                new Thread(() -> db.executeTransactionally(cypher, params, Iterators::single))
        );

        threads.forEach(Thread::start);
        threads.forEach(thread -> {
            try {
                thread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        long availablePermits = TestUtil.singleResultFirstColumn(db, "return apoc.concurrent.availablePermits($semaphoreName)", params);
        assertEquals("Ensure 'release' has been called.", 1l, availablePermits);
    }

    @Test(timeout = 10000)
    public void testRunInSemaphoreIsKillable() throws InterruptedException {
        Thread thread = new Thread(() -> {
            db.executeTransactionally("call apoc.concurrent.runInSemaphore($semaphoreName, 'return 1')", Collections.singletonMap("semaphoreName", SEMAPHORE_ZERO), Iterators::single);
        });
        thread.start();
        Thread.sleep(100);  // await planning

        // kill all active queries
        DependencyResolver dependencyResolver = ((GraphDatabaseAPI) db).getDependencyResolver();
        KernelTransactions kernelTransactions = dependencyResolver.resolveDependency(KernelTransactions.class);
        long numberOfKilledTransaction = kernelTransactions.activeTransactions().stream()
                .filter(kernelTransactionHandle -> kernelTransactionHandle.markForTermination(Status.Transaction.Terminated))
                .count();
        assertEquals(1l, numberOfKilledTransaction);
        thread.join();
    }

}
