package apoc.load;

import apoc.Pools;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.Log;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static apoc.util.FileUtils.getDirImport;
import static apoc.util.FileUtils.getPathFromUrlString;
import static apoc.util.FileUtils.isImportUsingNeo4jConfig;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.WatchEvent.Kind;
import static org.apache.commons.lang3.StringUtils.replaceOnce;

public class LoadDirectoryHandler extends LifecycleAdapter {

    public final Map<LoadDirectoryItem, Future> storage = new ConcurrentHashMap<>();

    private final Log log;
    private final GraphDatabaseService db;
    private final Pools pools;

    public LoadDirectoryHandler(GraphDatabaseService db, Log log, Pools pools) {
        this.db = db;
        this.log = log;
        this.pools = pools;
    }

    private static Kind[] fromListStringToKindArray(List<String> listenEventType) {
        Kind[] kinds = listenEventType.stream().map(item -> {
            switch (item) {
                case "CREATE":
                    return ENTRY_CREATE;
                case "MODIFY":
                    return ENTRY_MODIFY;
                case "DELETE":
                    return ENTRY_DELETE;
                default:
                    throw new UnsupportedOperationException("Event Type not supported: " + item);
            }
        }).toArray(Kind[]::new);

        return kinds;
    }

    @Override
    public void start() {}

    @Override
    public void stop() {
        removeAll();
    }

    public void remove(String name) {
        final LoadDirectoryItem loadDirectoryItem = new LoadDirectoryItem(name);
        remove(loadDirectoryItem);
    }

    private void remove(LoadDirectoryItem loadDirectoryItem) {
        Future removed = storage.remove(loadDirectoryItem);
        if (removed == null) {
            String name = loadDirectoryItem.getName();
            throw new RuntimeException("Listener with name: " + name + " doesn't exist");
        }
        removed.cancel(true);
    }

    public void add(LoadDirectoryItem loadDirectoryItem) {
        storage.compute(loadDirectoryItem, (k, v) -> {
            if (v != null) {
                try {
                    v.cancel(true);
                } catch (Exception ignored) {}
            }
            return pools.getDefaultExecutorService().submit(createListener(loadDirectoryItem));
        });
    }

    public Stream<LoadDirectoryItem.LoadDirectoryResult> list() {
        return Collections.unmodifiableMap(storage).keySet().stream().map(LoadDirectoryItem::toResult);
    }

    public void removeAll() {
        Set<LoadDirectoryItem> keys = new HashSet<>(storage.keySet());
        keys.forEach(this::remove);
    }

    private Runnable createListener(LoadDirectoryItem item) {
        return () -> {
            try (WatchService watcher = FileSystems.getDefault().newWatchService()) {

                final LoadDirectoryItem.LoadDirectoryConfig config = item.getConfig();

                getPathFromUrlString(item.getUrlDir()).register(watcher, fromListStringToKindArray(config.getListenEventType()));

                item.setStatusRunning();
                while (true) {
                    WatchKey watchKey = watcher.take();
                    if (watchKey != null) {
                        watchKey.reset();
                        Path dir = (Path) watchKey.watchable();
                        for (WatchEvent event : watchKey.pollEvents()) {
                            Path filePath = dir.resolve((Path) event.context());
                                WildcardFileFilter fileFilter = new WildcardFileFilter(item.getPattern());
                                final String fileName = filePath.getFileName().toString();
                                boolean matchFilePattern = fileFilter.accept(dir.toFile(), fileName);
                                if (matchFilePattern) {
                                    try (Transaction tx = db.beginTx()) {
                                        final String stringFileDirectory = getPathDependingOnUseNeo4jConfig(dir.toString());
                                        final String stringFilePath = getPathDependingOnUseNeo4jConfig(filePath.toString());

                                        tx.execute(item.getCypher(),
                                                Map.of("fileName", fileName,
                                                        "filePath", stringFilePath,
                                                        "fileDirectory", stringFileDirectory,
                                                        "listenEventType", event.kind().name().replace("ENTRY_", ""))
                                        );
                                        tx.commit();
                                    }
                                }
                        }
                    }
                    Thread.sleep(config.getInterval());
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    return;
                }
                log.warn(String.format("Error while executing procedure with name %s . " +
                        "The status of the directory listener is changed to ERROR. " +
                        "Type `call apoc.load.directory.async.list` to more details.", item.getName()));
                item.setError(ExceptionUtils.getStackTrace(e));
            }
        };
    }

    public static String getPathDependingOnUseNeo4jConfig(String urlFile) {
        return isImportUsingNeo4jConfig()
                ? replaceOnce(urlFile, getDirImport() + File.separator, "")
                : urlFile;
    }
}
