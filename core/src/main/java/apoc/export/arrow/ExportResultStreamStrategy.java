package apoc.export.arrow;

import apoc.Pools;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.types.pojo.Schema;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.logging.Log;
import org.neo4j.procedure.TerminationGuard;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

public class ExportResultStreamStrategy implements ExportArrowStreamStrategy<Result>, ExportResultStrategy {

    private final GraphDatabaseService db;
    private final Pools pools;
    private final TerminationGuard terminationGuard;
    private final Log logger;

    private final RootAllocator bufferAllocator;

    private Schema schema;

    public ExportResultStreamStrategy(GraphDatabaseService db, Pools pools, TerminationGuard terminationGuard, Log logger) {
        this.db = db;
        this.pools = pools;
        this.terminationGuard = terminationGuard;
        this.logger = logger;
        this.bufferAllocator = new RootAllocator();
    }

    @Override
    public Iterator<Map<String, Object>> toIterator(Result data) {
        return data;
    }

    @Override
    public TerminationGuard getTerminationGuard() {
        return terminationGuard;
    }

    @Override
    public BufferAllocator getBufferAllocator() {
        return bufferAllocator;
    }

    @Override
    public GraphDatabaseService getGraphDatabaseApi() {
        return db;
    }

    @Override
    public ExecutorService getExecutorService() {
        return pools.getDefaultExecutorService();
    }

    @Override
    public Log getLogger() {
        return logger;
    }

    @Override
    public synchronized Schema schemaFor(List<Map<String, Object>> records) {
        if (schema == null) {
            schema = schemaFor(getGraphDatabaseApi(), records);
        }
        return schema;
    }


}