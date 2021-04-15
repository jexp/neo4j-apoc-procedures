package apoc.load;

import apoc.Pools;
import apoc.result.MapResult;
import apoc.util.JsonUtil;
import apoc.util.QueueBasedSpliterator;
import apoc.util.Util;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowStreamReader;
import org.apache.arrow.vector.util.Text;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.TerminationGuard;
import org.neo4j.values.storable.Values;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class LoadArrow {

    @Context
    public GraphDatabaseService db;

    @Context
    public Pools pools;

    @Context
    public Log log;

    @Context
    public TerminationGuard terminationGuard;

    @Procedure(name = "apoc.load.arrow.stream")
    @Description("apoc.load.arrow.stream(source, config) - imports nodes and relationships from the provided byte[] source with given labels and types")
    public Stream<MapResult> stream(
            @Name("source") byte[] source,
            @Name(value = "config", defaultValue = "{}") Map<String, Object> config) {
        final BlockingQueue<MapResult> queue = new ArrayBlockingQueue<>(1000);
        RootAllocator allocator = new RootAllocator();

        Util.inThread(pools, () -> {
            try (ArrowStreamReader streamReader = new ArrowStreamReader(new ByteArrayInputStream(source), allocator);
                 VectorSchemaRoot schemaRoot = streamReader.getVectorSchemaRoot()) {
                AtomicInteger counter = new AtomicInteger();
                while (!Util.transactionIsTerminated(terminationGuard) && streamReader.loadNextBatch()) {
                    while (counter.get() < schemaRoot.getRowCount()) {
                        final Map<String, Object> row = schemaRoot.getFieldVectors()
                                .stream()
                                .map(fieldVector -> new AbstractMap.SimpleEntry<>(fieldVector.getName(), read(fieldVector, counter.get())))
                                .collect(HashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), HashMap::putAll); // please look at https://bugs.openjdk.java.net/browse/JDK-8148463
                        queue.add(new MapResult(row));
                        counter.incrementAndGet();
                    }
                }
                return true;
            } catch (Exception e) {
                log.error("Exception while reading Arrow data", e);
            } finally {
                queue.add(MapResult.EMPTY);
            }
            return null;
        });
        QueueBasedSpliterator<MapResult> spliterator = new QueueBasedSpliterator<>(queue, MapResult.EMPTY, terminationGuard, Integer.MAX_VALUE);
        return StreamSupport.stream(spliterator, false)
                .onClose(() -> Util.close(allocator));
    }

    private Object read(FieldVector fieldVector, int index) {
        if (fieldVector.isNull(index)) {
            return null;
        } else if (fieldVector instanceof DateMilliVector) {
            DateMilliVector fe = (DateMilliVector) fieldVector;
            return Instant.ofEpochMilli(fe.get(index)).atOffset(ZoneOffset.UTC);
        } else if (fieldVector instanceof BitVector) {
            BitVector fe = (BitVector) fieldVector;
            return fe.get(index) == 1;
        } else {
            Object object = fieldVector.getObject(index);
            return getObject(object);
        }
    }

    private Object getObject(Object object) {
        if (object instanceof Collection) {
            return ((Collection<?>) object).stream()
                    .map(this::getObject)
                    .collect(Collectors.toList());
        }
        if (object instanceof Map) {
            return ((Map<String, Object>) object).entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey(), e -> getObject(e.getValue())));
        }
        if (object instanceof Text) {
            return object.toString();
        }
        try {
            // we test if is a valid Neo4j type
            Values.of(object);
            return object;
        } catch (Exception e) {
            log.error("Coercing to a String as we cannot coerce the Arrow Value to a Neo4j type", e);
            // otherwise we try coerce it
            return valueToString(object);
        }
    }

    private String valueToString(Object value) {
        return JsonUtil.writeValueAsString(value);
    }

}