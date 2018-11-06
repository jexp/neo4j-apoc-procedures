package apoc;

import apoc.custom.CypherProcedures;
import apoc.cypher.CypherInitializer;
import apoc.index.IndexUpdateTransactionEventHandler;
import apoc.trigger.Trigger;
import apoc.ttl.TTLLifeCycle;
import apoc.util.ApocUrlStreamHandlerFactory;
import org.neo4j.kernel.AvailabilityGuard;
import org.neo4j.kernel.extension.KernelExtensionFactory;
import org.neo4j.kernel.impl.logging.LogService;
import org.neo4j.kernel.impl.proc.Procedures;
import org.neo4j.kernel.impl.spi.KernelContext;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.Log;

import java.net.URL;

/**
 * @author mh
 * @since 14.05.16
 */
public class ApocKernelExtensionFactory extends KernelExtensionFactory<ApocKernelExtensionFactory.Dependencies>{

    static {
        try {
            URL.setURLStreamHandlerFactory(new ApocUrlStreamHandlerFactory());
        } catch (Error e) {
            System.err.println("APOC couln't set a URLStreamHandlerFactory since some other tool already did this (e.g. tomcat). This means you cannot use s3:// or hdfs:// style URLs in APOC. This is a known issue tracked at https://github.com/neo4j-contrib/neo4j-apoc-procedures/issues/778. Full stacktrace below: ");
            e.printStackTrace();
        }
    }
    public ApocKernelExtensionFactory() {
        super("APOC");
    }

    public interface Dependencies {
        GraphDatabaseAPI graphdatabaseAPI();
        JobScheduler scheduler();
        Procedures procedures();
        LogService log();
        AvailabilityGuard availabilityGuard();
    }

    @Override
    public Lifecycle newInstance(KernelContext context, Dependencies dependencies) {
        GraphDatabaseAPI db = dependencies.graphdatabaseAPI();
        LogService log = dependencies.log();
        return new ApocLifecycle(log, db, dependencies);
    }

    public static class ApocLifecycle extends LifecycleAdapter {

        private final LogService log;
        private final GraphDatabaseAPI db;
        private final Dependencies dependencies;
        private Trigger.LifeCycle triggerLifeCycle;
        private Log userLog;
        private TTLLifeCycle ttlLifeCycle;

        private IndexUpdateTransactionEventHandler.LifeCycle indexUpdateLifeCycle;
        private CypherProcedures.CustomProcedureStorage customProcedureStorage;

        public ApocLifecycle(LogService log, GraphDatabaseAPI db, Dependencies dependencies) {
            this.log = log;
            this.db = db;
            this.dependencies = dependencies;
            userLog = log.getUserLog(ApocKernelExtensionFactory.class);
        }

        public IndexUpdateTransactionEventHandler.LifeCycle getIndexUpdateLifeCycle() {
            return indexUpdateLifeCycle;
        }

        @Override
        public void start() throws Throwable {
            ApocConfiguration.initialize(db);
            Pools.NEO4J_SCHEDULER = dependencies.scheduler();
            registerCustomProcedures();
            ttlLifeCycle = new TTLLifeCycle(Pools.NEO4J_SCHEDULER, db, log.getUserLog(TTLLifeCycle.class));
            ttlLifeCycle.start();

            triggerLifeCycle = new Trigger.LifeCycle(db, log.getUserLog(Trigger.class));
            triggerLifeCycle.start();
            indexUpdateLifeCycle = new IndexUpdateTransactionEventHandler.LifeCycle(db, log.getUserLog(Procedures.class));
            indexUpdateLifeCycle.start();

            customProcedureStorage = new CypherProcedures.CustomProcedureStorage(db, log.getUserLog(CypherProcedures.class));
            AvailabilityGuard availabilityGuard = dependencies.availabilityGuard();
            availabilityGuard.addListener(customProcedureStorage);
            availabilityGuard.addListener(new CypherInitializer(db, log.getUserLog(CypherInitializer.class)));
        }

        public void registerCustomProcedures() {

        }

        @Override
        public void stop() throws Throwable {
            if (ttlLifeCycle !=null)
                try {
                    ttlLifeCycle.stop();
                } catch(Exception e) {
                    userLog.warn("Error stopping ttl service",e);
                }
            if (triggerLifeCycle !=null)
                try {
                    triggerLifeCycle.stop();
                } catch(Exception e) {
                    userLog.warn("Error stopping trigger service",e);
                }
            if (indexUpdateLifeCycle !=null)
                try {
                    indexUpdateLifeCycle.stop();
                } catch(Exception e) {
                    userLog.warn("Error stopping index update service",e);
                }
        }

    }
}
