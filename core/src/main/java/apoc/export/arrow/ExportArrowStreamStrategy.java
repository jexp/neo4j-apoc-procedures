package apoc.export.arrow;

import apoc.convert.Json;
import apoc.result.ByteArrayResult;
import apoc.util.QueueBasedSpliterator;
import apoc.util.QueueUtil;
import apoc.util.Util;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.dictionary.DictionaryProvider;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.apache.arrow.vector.ipc.ArrowWriter;
import org.apache.arrow.vector.types.pojo.Schema;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.logging.Log;
import org.neo4j.procedure.TerminationGuard;

import java.io.OutputStream;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public interface ExportArrowStreamStrategy<IN> extends ExportArrowStrategy<IN, Stream<ByteArrayResult>> {

    Iterator<Map<String, Object>> toIterator(IN data);

    default Stream<ByteArrayResult> export(IN data, ArrowConfig config) {
        final BlockingQueue<apoc.result.ByteArrayResult> queue = new ArrayBlockingQueue<>(1000);
        Util.inTxFuture(getExecutorService(), getGraphDatabaseApi(), txInThread -> {
            int batchCount = 0;
            List<Map<String, Object>> rows = new ArrayList<>(config.getBatchSize());
            try {
                Iterator<Map<String, Object>> it = toIterator(data);
                while (!Util.transactionIsTerminated(getTerminationGuard()) && it.hasNext()) {
                    rows.add(it.next());
                    if (batchCount > 0 && batchCount % config.getBatchSize() == 0) {
                        final byte[] bytes = writeBatch(getCounter(), getBufferAllocator(), rows);
                        QueueUtil.put(queue, new apoc.result.ByteArrayResult(bytes), 10);
                        rows.clear();
                    }
                    ++batchCount;
                }
                if (!rows.isEmpty()) {
                    final byte[] bytes = writeBatch(getCounter(), getBufferAllocator(), rows);
                    QueueUtil.put(queue, new apoc.result.ByteArrayResult(bytes), 10);
                }
            } catch (Exception e) {
                getLogger().error("Exception while extracting Arrow data:", e);
            } finally {
                QueueUtil.put(queue, apoc.result.ByteArrayResult.NULL, 10);
            }
            return true;
        });
        QueueBasedSpliterator<apoc.result.ByteArrayResult> spliterator = new QueueBasedSpliterator<>(queue, apoc.result.ByteArrayResult.NULL, getTerminationGuard(), Integer.MAX_VALUE);
        return StreamSupport.stream(spliterator, false)
                .onClose(() -> Util.close(getBufferAllocator()));
    }

    TerminationGuard getTerminationGuard();

    BufferAllocator getBufferAllocator();

    AtomicInteger getCounter();

    GraphDatabaseService getGraphDatabaseApi();

    ExecutorService getExecutorService();

    Log getLogger();

    default Object convertValue(Object data) {
        return Json.writeJsonResult(data);
    }

    default ArrowWriter newArrowWriter(VectorSchemaRoot root, OutputStream out) {
        return new ArrowStreamWriter(root, new DictionaryProvider.MapDictionaryProvider(), Channels.newChannel(out));
    }

    Schema schemaFor(List<Map<String, Object>> rows);
}