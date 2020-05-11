package apoc.concurrent;

import apoc.ApocConfig;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.Log;
import org.neo4j.logging.internal.LogService;
import org.neo4j.procedure.TerminationGuard;
import org.neo4j.procedure.impl.GlobalProceduresRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * holds a per-database level map of declared semaphores
 */
public class Semaphores extends LifecycleAdapter {

    private final Map<String, Semaphore> semaphoreMap = new ConcurrentHashMap<>();

    private final Log log;
    private final GlobalProceduresRegistry globalProceduresRegistry;
    private final ApocConfig apocConfig;

    public Semaphores(LogService log, GlobalProceduresRegistry globalProceduresRegistry, ApocConfig apocConfig) {

        this.log = log.getInternalLog(Semaphores.class);
        this.globalProceduresRegistry = globalProceduresRegistry;
        this.apocConfig = apocConfig;

        // expose this config instance via `@Context ApocConfig config`
        globalProceduresRegistry.registerComponent((Class<Semaphores>) getClass(), ctx -> this, true);
        this.log.info("successfully registered Pools for @Context");
    }

    @Override
    public void init() throws Exception {
        Iterators.stream(apocConfig.getKeys(ApocConfig.APOC_SEMAPHORE_PREFIX))
                .filter(key -> !key.equals(ApocConfig.APOC_SEMAPHORE_DEFAULT_NAME))
                .forEach(key -> {
                    int permits = apocConfig.getInt(key, -1);
                    String semaphoreName = key.substring(ApocConfig.APOC_SEMAPHORE_PREFIX.length() + 1);
                    semaphoreMap.put(semaphoreName, new Semaphore(permits));
                    log.debug("created semaphore %s with %d permits", key, permits);
                });
    }

    public <T> Stream<T> withSemaphore(TerminationGuard terminationGuard, String semaphoreName, Supplier<Stream<T>> supplier) {
        if (semaphoreName == null) {
            return supplier.get();
        } else {
            Semaphore semaphore = getSemaphoreOrThrow(semaphoreName);
            long now = System.currentTimeMillis();
            boolean permitAcquired = false;
            try {

                // we can't simply use semaphore.acquire() here since it blocks and doesn't give us a chance for checking termination status
                log.debug("about to acquire semaphore %s", semaphoreName);
                while (!semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                    terminationGuard.check();
                }
                permitAcquired = true;
                log.debug("acquired semaphore %s in %d millis", semaphoreName, System.currentTimeMillis()-now);

                return supplier.get().onClose(() -> {
                    semaphore.release();
                    log.debug("released semaphore %s after %d millis", semaphoreName, System.currentTimeMillis()-now);
                });
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (RuntimeException e) {
                if (permitAcquired) {
                    semaphore.release();
                }
                log.debug("released semaphore %s after %d millis due to %s", semaphoreName, System.currentTimeMillis()-now, e.getMessage());
                throw e;
            }
        }
    }

    public int availablePermits(String semaphoreName) {
        Semaphore semaphore = getSemaphoreOrThrow(semaphoreName);
        return semaphore.availablePermits();
    }

    public void add(String semaphoreName, int permits) {
        if (semaphoreMap.get(semaphoreName) != null) {
            throw new IllegalArgumentException("already existing semaphore :" + semaphoreName + ". Use remove first");
        }
        semaphoreMap.put(semaphoreName, new Semaphore(permits));
    }

    public void remove(String semaphoreName) {
        semaphoreMap.remove(semaphoreName);
    }

    public boolean hasSemaphore(String semaphoreName) {
        return semaphoreMap.containsKey(semaphoreName);
    }

    private Semaphore getSemaphoreOrThrow(String semaphoreName) {
        Semaphore semaphore = semaphoreMap.get(semaphoreName);
        if (semaphore == null) {
            throw new IllegalArgumentException("no declared semaphore: " + semaphoreName);
        }
        return semaphore;
    }

    public void removeAll() {
        semaphoreMap.clear();
    }
}
