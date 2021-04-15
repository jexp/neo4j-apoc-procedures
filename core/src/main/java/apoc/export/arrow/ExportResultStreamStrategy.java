package apoc.export.arrow;

import apoc.Pools;
import apoc.meta.Meta;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.logging.Log;
import org.neo4j.procedure.TerminationGuard;

import java.util.AbstractMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static apoc.export.arrow.ExportArrowStrategy.fromMetaType;
import static apoc.export.arrow.ExportArrowStrategy.toField;

public class ExportResultStreamStrategy implements ExportArrowStreamStrategy<Result> {

    private final GraphDatabaseService db;
    private final Pools pools;
    private final TerminationGuard terminationGuard;
    private final Log logger;

    private final AtomicInteger counter;

    private final RootAllocator bufferAllocator;

    private Schema schema;

    public ExportResultStreamStrategy(GraphDatabaseService db, Pools pools, TerminationGuard terminationGuard, Log logger) {
        this.db = db;
        this.pools = pools;
        this.terminationGuard = terminationGuard;
        this.logger = logger;
        this.counter = new AtomicInteger();
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
    public AtomicInteger getCounter() {
        return counter;
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
            final List<Field> fields = records.stream()
                    .flatMap(m -> m.entrySet().stream())
                    .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), fromMetaType(Meta.Types.of(e.getValue()))))
                    .collect(Collectors.groupingBy(e -> e.getKey(), Collectors.mapping(e -> e.getValue(), Collectors.toSet())))
                    .entrySet()
                    .stream()
                    .map(e -> toField(e.getKey(), e.getValue()))
                    .collect(Collectors.toList());
            schema = new Schema(fields);
        }
        return schema;
    }


}