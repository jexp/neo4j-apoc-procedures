package apoc;

import apoc.periodic.Periodic;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.Log;
import org.neo4j.logging.internal.LogService;
import org.neo4j.procedure.impl.GlobalProceduresRegistry;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

public class Pools extends LifecycleAdapter {

    public final static int DEFAULT_SCHEDULED_THREADS = Runtime.getRuntime().availableProcessors() / 4;
    public final static int DEFAULT_POOL_THREADS = Runtime.getRuntime().availableProcessors() * 2;
    private final Log log;
    private final GlobalProceduresRegistry globalProceduresRegistry;
    private final ApocConfig apocConfig;

    private ScheduledExecutorService scheduledExecutorService;
    private ExecutorService defaultExecutorService;

    private final Map<Periodic.JobInfo,Future> jobList = new ConcurrentHashMap<>();

    public Pools(LogService log, GlobalProceduresRegistry globalProceduresRegistry, ApocConfig apocConfig) {

        this.log = log.getInternalLog(Pools.class);
        this.globalProceduresRegistry = globalProceduresRegistry;
        this.apocConfig = apocConfig;

        // expose this config instance via `@Context ApocConfig config`
        globalProceduresRegistry.registerComponent((Class<Pools>) getClass(), ctx -> this, true);
        this.log.info("successfully registered Pools for @Context");
    }

    @Override
    public void init() {

        int threads = Math.max(1, apocConfig.getInt(ApocConfig.APOC_CONFIG_JOBS_POOL_NUM_THREADS, DEFAULT_POOL_THREADS));

        int queueSize = threads * 25;
        this.defaultExecutorService = new ThreadPoolExecutor(threads / 2, threads, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queueSize),
                new CallerBlocksPolicy());

        this.scheduledExecutorService = Executors.newScheduledThreadPool(
                Math.max(1, apocConfig.getInt(ApocConfig.APOC_CONFIG_JOBS_SCHEDULED_NUM_THREADS, DEFAULT_SCHEDULED_THREADS))
        );

        scheduledExecutorService.scheduleAtFixedRate(() -> {
            for (Iterator<Map.Entry<Periodic.JobInfo, Future>> it = jobList.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Periodic.JobInfo, Future> entry = it.next();
                if (entry.getValue().isDone() || entry.getValue().isCancelled()) it.remove();
            }
        },10,10,TimeUnit.SECONDS);
    }

    @Override
    public void shutdown() throws Exception {
        Stream.of(defaultExecutorService, scheduledExecutorService).forEach( service -> {
            try {
                service.shutdown();
                service.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignore) {

            }
        });
    }

    public ScheduledExecutorService getScheduledExecutorService() {
        return scheduledExecutorService;
    }

    public ExecutorService getDefaultExecutorService() {
        return defaultExecutorService;
    }

    public Map<Periodic.JobInfo, Future> getJobList() {
        return jobList;
    }

    static class CallerBlocksPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            // Submit again by directly injecting the task into the work queue, waiting if necessary, but also periodically checking if the pool has been
            // shut down.
            FutureTask<Void> task = new FutureTask<>( r, null );
            BlockingQueue<Runnable> queue = executor.getQueue();
            while (!executor.isShutdown()) {
                try {
                    if ( queue.offer( task, 250, TimeUnit.MILLISECONDS ) )
                    {
                        while ( !executor.isShutdown() )
                        {
                            try
                            {
                                task.get( 250, TimeUnit.MILLISECONDS );
                                return; // Success!
                            }
                            catch ( TimeoutException ignore )
                            {
                                // This is fine an expected. We just want to check that the executor hasn't been shut down.
                            }
                        }
                    }
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public <T> Future<Void> processBatch(List<T> batch, GraphDatabaseService db, BiConsumer<Transaction, T> action) {
        return defaultExecutorService.submit(() -> {
                try (Transaction tx = db.beginTx()) {
                    batch.forEach(t -> action.accept(tx, t));
                    tx.commit();
                }
                return null;
            }
        );
    }

    public static <T> T force(Future<T> future) throws ExecutionException {
        while (true) {
            try {
                return future.get();
            } catch (InterruptedException e) {
                Thread.interrupted();
            }
        }
    }
}
