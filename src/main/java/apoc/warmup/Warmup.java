package apoc.warmup;

import apoc.Pools;
import apoc.util.Util;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.PagedFile;
import org.neo4j.kernel.impl.store.RecordStore;
import org.neo4j.kernel.impl.store.record.AbstractBaseRecord;
import org.neo4j.kernel.impl.store.record.RecordLoad;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.logging.Log;
import org.neo4j.procedure.*;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Sascha Peukert
 * @since 06.05.16
 */
public class Warmup {

    private static final int BATCH_SIZE = 100_000;
    private static final int PAGE_SIZE = 1 << 13;
    @Context
    public GraphDatabaseAPI db;
    @Context
    public TerminationGuard guard;
    @Context
    public Log log;

    static class PageResult {
        public final String file;
        public final boolean index;
        public final long fileSize;
        public final long pages;
        public final String error;
        public final long time;

        public PageResult(String file, boolean index, long fileSize, long pages, String error, long start) {
            this.file = file;
            this.index = index;
            this.fileSize = fileSize;
            this.pages = pages;
            this.error = error;
            this.time = System.currentTimeMillis() - start;
        }
    }

    private String subPath(File file, String fromParent) {
        StringBuilder sb = new StringBuilder(file.getAbsolutePath().length());
        while (true) {
            sb.insert(0,file.getName());
            file = file.getParentFile();
            if (file == null || file.getName().equals(fromParent)) break;
            sb.insert(0, File.separator);
        }
        return sb.toString();
    }

    @Procedure
    @Description("apoc.warmup.run(loadProperties=false,loadDynamicProperties=false,loadIndexes=false) - quickly loads all nodes and rels into memory by skipping one page at a time")
    public Stream<WarmupResult> run(@Name(value = "loadProperties", defaultValue = "false") boolean loadProperties, @Name(value = "loadDynamicProperties", defaultValue = "false") boolean loadDynamicProperties, @Name(value = "loadIndexes", defaultValue = "false") boolean loadIndexes) throws IOException {
        PageCache pageCache = db.getDependencyResolver().resolveDependency(PageCache.class);

        List<PagedFile> pagedFiles = pageCache.listExistingMappings();

        Map<String, PageResult> records = pagedFiles.parallelStream()
                .filter(pF -> {
                    String name = pF.file().getName();
                    if (isSchema(pF.file()) && !loadIndexes) return false;
                    if ((name.endsWith("propertystore.db.strings") || name.endsWith("propertystore.db.arrays")) && !loadDynamicProperties) return false;
                    if ((name.startsWith("propertystore.db")) && !loadProperties) return false;
                    return true;
                })
                .map((pagedFile -> {
                    File file = pagedFile.file();
                    boolean index = isSchema(file);
                    String fileName = index ? subPath(file, "schema") : file.getName();
                    long pages = 0;
                    long start = System.currentTimeMillis();
                    try {
                        if (pagedFile.fileSize() > 0) {
                            PageCursor cursor = pagedFile.io(0L, PagedFile.PF_READ_AHEAD | PagedFile.PF_SHARED_READ_LOCK);
                            while (cursor.next()) {
                                cursor.getByte();
                                pages++;
                                if (pages % 1000 == 0 && Util.transactionIsTerminated(guard)) {
                                    break;
                                }
                            }
                        }
                        return new PageResult(fileName, index, pagedFile.fileSize(), pages, null, start);
                    } catch (IOException e) {
                        e.printStackTrace();
                        return new PageResult(fileName, index, -1L, pages, e.getMessage(), start);
                    } finally {
                        pageCache.reportEvents();
                    }
                })).collect(Collectors.toMap(r -> r.file, r -> r));

        WarmupResult result = new WarmupResult(
                pageCache.pageSize(),
                Util.nodeCount(db),
                records.get("neostore.nodestore.db"),
                Util.relCount(db),
                records.get("neostore.relationshipstore.db"),
                records.get("neostore.relationshipgroupstore.db"),
                loadProperties,
                records.get("neostore.propertystore.db"),
                records.values().stream().mapToLong((r)->r.time).sum(),
                Util.transactionIsTerminated(guard),
                loadDynamicProperties,
                records.get("neostore.propertystore.db.strings"),
                records.get("neostore.propertystore.db.arrays"),
                loadIndexes,
                records.values().stream().filter(r -> r.index).collect(Collectors.toList())
                );
        return Stream.of(result);
    }

    public boolean isSchema(File file) {
        return file.getAbsolutePath().contains(File.separator+"schema"+File.separator);
    }

    public static <R extends AbstractBaseRecord> long loadRecords(int recordsPerPage, long highestRecordId, RecordStore<R> recordStore, R record, GraphDatabaseAPI db, TerminationGuard guard) {
        long[] ids = new long[BATCH_SIZE];
        long pages = 0;
        int idx = 0;
        List<Future<Long>> futures = new ArrayList<>(100);
        for (long id = 0; id <= highestRecordId; id += recordsPerPage) {
            ids[idx++] = id;
            if (idx == BATCH_SIZE) {
                long[] submitted = ids.clone();
                idx = 0;
                futures.add(Util.inTxFuture(Pools.DEFAULT, db, () -> loadRecords(submitted, record, recordStore, guard)));
            }
            pages += removeDone(futures, false);
        }
        if (idx > 0) {
            long[] submitted = Arrays.copyOf(ids, idx);
            futures.add(Util.inTxFuture(Pools.DEFAULT, db, () -> loadRecords(submitted, record, recordStore, guard)));
        }
        pages += removeDone(futures, true);
        return pages;
    }

    public static <R extends AbstractBaseRecord> long loadRecords(long[] submitted, R record, RecordStore<R> recordStore, TerminationGuard guard) {
        if (Util.transactionIsTerminated(guard)) return 0;
        for (long recordId : submitted) {
            record.setId(recordId);
            record.clear();
            try {
                recordStore.getRecord(recordId, record, RecordLoad.NORMAL);
            } catch (Exception ignore) {
                // ignore
            }
        }
        return submitted.length;
    }

    public static long removeDone(List<Future<Long>> futures, boolean wait) {
        long pages = 0;
        if (wait || futures.size() > 25) {
            Iterator<Future<Long>> it = futures.iterator();
            while (it.hasNext()) {
                Future<Long> future = it.next();
                if (wait || future.isDone()) {
                    try {
                        pages += future.get();
                    } catch (InterruptedException | ExecutionException e) {
                        // log.warn("Error during task execution", e);
                    }
                    it.remove();
                }
            }
        }
        return pages;
    }

    public static class WarmupResult {
        public final long pageSize;
        public final long totalTime;
        public final boolean transactionWasTerminated;

        public long nodesPerPage;
        public final long nodesTotal;
        public final long nodePages;
        public final long nodesTime;

        public long relsPerPage;
        public final long relsTotal;
        public final long relPages;
        public final long relsTime;
        public long relGroupsPerPage;
        public long relGroupsTotal;
        public final long relGroupPages;
        public final long relGroupsTime;
        public final boolean propertiesLoaded;
        public final boolean dynamicPropertiesLoaded;
        public long propsPerPage;
        public long propRecordsTotal;
        public long propPages;
        public long propsTime;
        public long stringPropsPerPage;
        public long stringPropRecordsTotal;
        public long stringPropPages;
        public long stringPropsTime;
        public long arrayPropsPerPage;
        public long arrayPropRecordsTotal;
        public long arrayPropPages;
        public long arrayPropsTime;
        public final boolean indexesLoaded;
        public long indexPages;
        public long indexTime;

        public WarmupResult(long pageSize,
                            long nodesTotal,
                            PageResult nodes,
                            long relsTotal,
                            PageResult rels,
                            PageResult relGroups,
                            boolean propertiesLoaded,
                            PageResult props,
                            long totalTime, boolean transactionWasTerminated,
                            boolean dynamicPropertiesLoaded,
                            PageResult stringProps,
                            PageResult arrayProps,
                            boolean loadIndexes,
                            List<PageResult> indexes
                            ) {
            this.pageSize = pageSize;
            this.transactionWasTerminated = transactionWasTerminated;
            this.totalTime = totalTime;
            this.propertiesLoaded = propertiesLoaded;
            this.dynamicPropertiesLoaded = dynamicPropertiesLoaded;

            this.nodesTotal = nodesTotal;
            this.nodePages = nodes.pages;
            this.nodesTime = nodes.time;

            this.relsTotal = relsTotal;
            this.relPages = rels.pages;
            this.relsTime = rels.time;

            this.relGroupPages = relGroups.pages;
            this.relGroupsTime = relGroups.time;

            if (props!=null) {
                this.propPages = props.pages;
                this.propsTime = props.time;
            }
            if (stringProps != null) {
                this.stringPropPages = stringProps.pages;
                this.stringPropsTime = stringProps.time;
            }
            if (arrayProps != null) {
                this.arrayPropPages = arrayProps.pages;
                this.arrayPropsTime = arrayProps.time;
            }
            this.indexesLoaded = loadIndexes;
            if (!indexes.isEmpty()) {
                this.indexPages = indexes.stream().mapToLong(pr -> pr.pages).sum();
                this.indexTime = indexes.stream().mapToLong(pr -> pr.time).sum();
            }
        }
    }
}
