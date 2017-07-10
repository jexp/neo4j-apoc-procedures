package apoc;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.helpers.collection.Iterables;
import org.neo4j.kernel.api.exceptions.KernelException;
import org.neo4j.test.TestGraphDatabaseFactory;
import org.openjdk.jmh.annotations.*;

import java.util.Collections;
import java.util.Map;

@State(Scope.Benchmark)
public class GraphDatabaseState {


    private GraphDatabaseService graphDatabaseService;

    public GraphDatabaseService getGraphDatabaseService() {
        return graphDatabaseService;
    }

    @Setup(Level.Invocation)
    public final void setup() {
        graphDatabaseService = new TestGraphDatabaseFactory().newImpermanentDatabaseBuilder()
                .setConfig(getGraphDatabaseConfig())
                .newGraphDatabase();
        setupGraphDatabase(graphDatabaseService);
    }

    @TearDown(Level.Invocation)
    public final void tearDown() {

        try (Transaction tx = graphDatabaseService.beginTx()) {
            long numberOfNodes = Iterables.count(graphDatabaseService.getAllNodes());
            System.out.println(" we have " + numberOfNodes + " nodes");
            tx.success();
        }
        graphDatabaseService.shutdown();
//            System.out.println("db stopped.");
    }

    public Map<String, String> getGraphDatabaseConfig() {
        return Collections.EMPTY_MAP;
    }

    void setupGraphDatabase(GraphDatabaseService graphDatabaseService) {
    }
}

