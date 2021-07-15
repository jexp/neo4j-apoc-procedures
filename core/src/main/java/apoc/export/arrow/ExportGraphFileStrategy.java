package apoc.export.arrow;

import apoc.Pools;
import apoc.export.util.ProgressReporter;
import apoc.result.ProgressInfo;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.types.pojo.Schema;
import org.neo4j.cypher.export.SubGraph;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.internal.helpers.collection.Iterables;
import org.neo4j.logging.Log;
import org.neo4j.procedure.TerminationGuard;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

public class ExportGraphFileStrategy implements ExportArrowFileStrategy<SubGraph>, ExportGraphStrategy {

    private final String fileName;
    private final GraphDatabaseService db;
    private final Pools pools;
    private final TerminationGuard terminationGuard;
    private final Log logger;

    private final RootAllocator bufferAllocator;

    private Schema schema;

    public ExportGraphFileStrategy(String fileName, GraphDatabaseService db, Pools pools, TerminationGuard terminationGuard, Log logger) {
        this.fileName = fileName;
        this.db = db;
        this.pools = pools;
        this.terminationGuard = terminationGuard;
        this.logger = logger;
        this.bufferAllocator = new RootAllocator();
    }

    @Override
    public Iterator<Map<String, Object>> toIterator(ProgressReporter reporter, SubGraph subGraph) {
        return Stream.concat(Iterables.stream(subGraph.getNodes()), Iterables.stream(subGraph.getRelationships()))
                .map(entity -> {
                    reporter.update(entity instanceof Node ? 1 : 0,
                            entity instanceof Relationship ? 1 : 0, 0);
                    return this.entityToMap(entity);
                })
                .iterator();
    }

    @Override
    public String getSource(SubGraph subGraph) {
        return String.format("graph: nodes(%d), rels(%d)", Iterables.count(subGraph.getNodes()), Iterables.count(subGraph.getRelationships()));
    }

    @Override
    public Stream<ProgressInfo> export(SubGraph data, ArrowConfig config) {
        schemaFor(List.of(createConfigMap(data, config)));
        return ExportArrowFileStrategy.super.export(data, config);
    }

    @Override
    public String getFileName() {
        return fileName;
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