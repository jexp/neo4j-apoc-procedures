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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public interface ExportArrowStreamStrategy<IN> extends ExportArrowStrategy<IN, Stream<ByteArrayResult>> {

    Iterator<Map<String, Object>> toIterator(IN data);

    default byte[] writeBatch(BufferAllocator bufferAllocator, List<Map<String, Object>> rows) {
        try (final VectorSchemaRoot root = VectorSchemaRoot.create(schemaFor(rows), bufferAllocator);
             final ByteArrayOutputStream out = new ByteArrayOutputStream();
             final ArrowWriter writer = newArrowWriter(root, out)) {
            AtomicInteger counter = new AtomicInteger();
            root.allocateNew();
            rows.forEach(row -> {
                final int index = counter.getAndIncrement();
                root.getFieldVectors()
                        .forEach(fe -> {
                            Object value = convertValue(row.get(fe.getName()));
                            write(index, value, fe);
                        });
            });
            root.setRowCount(counter.get());
            try {
                writer.writeBatch();
            } catch (IOException ignored) {}
            root.clear();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    default Stream<ByteArrayResult> export(IN data, ArrowConfig config) {
        final BlockingQueue<apoc.result.ByteArrayResult> queue = new ArrayBlockingQueue<>(100);
        Util.inTxFuture(getExecutorService(), getGraphDatabaseApi(), txInThread -> {
            int batchCount = 0;
            List<Map<String, Object>> rows = new ArrayList<>(config.getBatchSize());
            try {
                Iterator<Map<String, Object>> it = toIterator(data);
                while (!Util.transactionIsTerminated(getTerminationGuard()) && it.hasNext()) {
                    rows.add(it.next());
                    if (batchCount > 0 && batchCount % config.getBatchSize() == 0) {
                        final byte[] bytes = writeBatch(getBufferAllocator(), rows);
                        QueueUtil.put(queue, new ByteArrayResult(bytes), 10);
                        rows.clear();
                    }
                    ++batchCount;
                }
                if (!rows.isEmpty()) {
                    final byte[] bytes = writeBatch(getBufferAllocator(), rows);
                    QueueUtil.put(queue, new ByteArrayResult(bytes), 10);
                }
            } catch (Exception e) {
                getLogger().error("Exception while extracting Arrow data:", e);
            } finally {
                QueueUtil.put(queue, ByteArrayResult.NULL, 10);
            }
            return true;
        });
        QueueBasedSpliterator<apoc.result.ByteArrayResult> spliterator = new QueueBasedSpliterator<>(queue, apoc.result.ByteArrayResult.NULL, getTerminationGuard(), Integer.MAX_VALUE);
        return StreamSupport.stream(spliterator, false)
                .onClose(() -> Util.close(getBufferAllocator()));
    }

    default Object convertValue(Object data) {
        return data == null ? null : Json.writeJsonResult(data);
    }

    default ArrowWriter newArrowWriter(VectorSchemaRoot root, OutputStream out) {
        return new ArrowStreamWriter(root, new DictionaryProvider.MapDictionaryProvider(), Channels.newChannel(out));
    }

    Schema schemaFor(List<Map<String, Object>> rows);
}