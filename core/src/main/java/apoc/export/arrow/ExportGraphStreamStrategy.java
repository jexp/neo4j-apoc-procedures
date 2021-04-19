package apoc.export.arrow;

import apoc.Pools;
import apoc.result.ByteArrayResult;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.dictionary.DictionaryProvider;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.apache.arrow.vector.ipc.ArrowWriter;
import org.apache.arrow.vector.types.pojo.Schema;
import org.neo4j.cypher.export.SubGraph;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.internal.helpers.collection.Iterables;
import org.neo4j.logging.Log;
import org.neo4j.procedure.TerminationGuard;

import java.io.OutputStream;
import java.nio.channels.Channels;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

public class ExportGraphStreamStrategy implements ExportArrowStreamStrategy<SubGraph>, ExportGraphStrategy {

    private final GraphDatabaseService db;
    private final Pools pools;
    private final TerminationGuard terminationGuard;
    private final Log logger;

    private final RootAllocator bufferAllocator;

    private Schema schema;


    public ExportGraphStreamStrategy(GraphDatabaseService db, Pools pools, TerminationGuard terminationGuard, Log logger) {
        this.db = db;
        this.pools = pools;
        this.terminationGuard = terminationGuard;
        this.logger = logger;
        this.bufferAllocator = new RootAllocator();
    }

    @Override
    public Iterator<Map<String, Object>> toIterator(SubGraph subGraph) {
        return Stream.concat(Iterables.stream(subGraph.getNodes()), Iterables.stream(subGraph.getRelationships()))
                .map(this::entityToMap)
                .iterator();
    }

    @Override
    public Stream<ByteArrayResult> export(SubGraph subGraph, ArrowConfig config) {
        Map<String, Object> configMap = createConfigMap(subGraph, config);
        this.schemaFor(List.of(configMap));
        return ExportArrowStreamStrategy.super.export(subGraph, config);
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
    public ArrowWriter newArrowWriter(VectorSchemaRoot root, OutputStream out) {
        return new ArrowStreamWriter(root, new DictionaryProvider.MapDictionaryProvider(), Channels.newChannel(out));
    }

    @Override
    public synchronized Schema schemaFor(List<Map<String, Object>> records) {
        if (schema == null) {
            schema = schemaFor(getGraphDatabaseApi(), records);
        }
        return schema;
    }
}