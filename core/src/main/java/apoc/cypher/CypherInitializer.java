package apoc.cypher;

import apoc.ApocConfig;
import apoc.util.Util;
import org.apache.commons.configuration2.Configuration;
import org.neo4j.common.DependencyResolver;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.kernel.api.procedure.GlobalProcedures;
import org.neo4j.kernel.availability.AvailabilityListener;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.logging.Log;

import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class CypherInitializer implements AvailabilityListener {

    private final GraphDatabaseAPI db;
    private final Log userLog;
    private final GlobalProcedures procs;
    private final DependencyResolver dependencyResolver;

    /**
     * indicates the status of the initializer, to be used for tests to ensure initializer operations are already done
     */
    private boolean finished = false;

    public CypherInitializer(GraphDatabaseAPI db, Log userLog) {
        this.db = db;
        this.userLog = userLog;
        this.dependencyResolver = db.getDependencyResolver();
        this.procs = dependencyResolver.resolveDependency(GlobalProcedures.class);
    }

    public boolean isFinished() {
        return finished;
    }

    public GraphDatabaseAPI getDb() {
        return db;
    }

    @Override
    public void available() {

        // run initializers in a new thread
        // we need to wait until apoc procs are registered
        // unfortunately an AvailabilityListener is triggered before that
        Util.newDaemonThread(() -> {

            try {
                final boolean isSystemDatabase = db.databaseName().equals(GraphDatabaseSettings.SYSTEM_DATABASE_NAME);
                if (!isSystemDatabase) {
                    awaitApocProceduresRegistered();
                }
                Configuration config = dependencyResolver.resolveDependency(ApocConfig.class).getConfig();

                TreeMap<String, String> initializers = new TreeMap<>();

                config.getKeys(ApocConfig.APOC_CONFIG_INITIALIZER + "." + db.databaseName()).forEachRemaining(key -> initializers.put(key, config.getString(key)));

                if (!isSystemDatabase) {
                    config.getKeys(ApocConfig.APOC_CONFIG_INITIALIZER_CYPHER).forEachRemaining(key -> initializers.put(key, config.getString(key)));
                }

                for (Object initializer : initializers.values()) {
                    String query = initializer.toString();
                    if (!query.isEmpty()) {
                        try {
                            // we need to apply a retry strategy here since in systemdb we potentially conflict with
                            // creating contraints which could cause our query to fail with a transient error.
                            Util.retryInTx(userLog, db, tx -> Iterators.count(tx.execute(query)), 0, 5, retries -> {});
                            userLog.info("successfully initialized: " + query);
                        } catch (Exception e) {
                            userLog.error("error upon initialization, running: " + query, e);
                        }
                    }
                }
            } finally {
                finished = true;
            }
        }).start();
    }

    private void awaitApocProceduresRegistered() {
        while (!areApocProceduresRegistered()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private boolean areApocProceduresRegistered() {
        try {
            return procs.getAllProcedures().stream().anyMatch(signature -> signature.name().toString().startsWith("apoc"));
        } catch (ConcurrentModificationException e) {
            // if a CME happens (possible during procedure scanning)
            // we return false and the caller will try again
            return false;
        }
    }

    @Override
    public void unavailable() {
        // intentionally empty
    }
}
