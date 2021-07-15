package apoc.util;

import apoc.Pools;
import apoc.convert.Convert;
import apoc.export.util.CountingInputStream;
import apoc.result.VirtualNode;
import apoc.result.VirtualRelationship;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.collections.api.iterator.LongIterator;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotInTransactionException;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.QueryExecutionType;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.TransactionGuardException;
import org.neo4j.graphdb.TransactionTerminatedException;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.internal.helpers.collection.Pair;
import org.neo4j.internal.kernel.api.security.SecurityContext;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.logging.Log;
import org.neo4j.procedure.TerminationGuard;

import javax.lang.model.SourceVersion;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PrimitiveIterator;
import java.util.Scanner;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.DeflaterInputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static apoc.ApocConfig.apocConfig;
import static apoc.util.DateFormatUtil.getOrCreate;
import static java.lang.String.format;
import static org.eclipse.jetty.util.URIUtil.encodePath;

/**
 * @author mh
 * @since 24.04.16
 */
public class Util {
    public static final Label[] NO_LABELS = new Label[0];
    public static final String NODE_COUNT = "MATCH (n) RETURN count(*) as result";
    public static final String REL_COUNT = "MATCH ()-->() RETURN count(*) as result";
    public static final String COMPILED = "interpreted"; // todo handle enterprise properly

    public static String labelString(List<String> labelNames) {
        return labelNames.stream().map(Util::quote).collect(Collectors.joining(":"));
    }

    public static String labelString(Node n) {
        return joinLabels(n.getLabels(), ":");
    }
    public static String joinLabels(Iterable<Label> labels, String s) {
        return StreamSupport.stream(labels.spliterator(), false).map(Label::name).collect(Collectors.joining(s));
    }
    public static List<String> labelStrings(Node n) {
        return StreamSupport.stream(n.getLabels().spliterator(),false).map(Label::name).sorted().collect(Collectors.toList());
    }
    public static Label[] labels(Object labelNames) {
        if (labelNames==null) return NO_LABELS;
        if (labelNames instanceof List) {
            Set names = new LinkedHashSet((List) labelNames); // Removing duplicates
            Label[] labels = new Label[names.size()];
            int i = 0;
            for (Object l : names) {
                if (l==null) continue;
                labels[i++] = Label.label(l.toString());
            }
            if (i <= labels.length) return Arrays.copyOf(labels,i);
            return labels;
        }
        return new Label[]{Label.label(labelNames.toString())};
    }

    public static RelationshipType type(Object type) {
        if (type == null) throw new RuntimeException("No relationship-type provided");
        return RelationshipType.withName(type.toString());
    }

    @SuppressWarnings("unchecked")
    public static LongStream ids(Object ids) {
        if (ids == null) return LongStream.empty();
        if (ids instanceof Number) return LongStream.of(((Number)ids).longValue());
        if (ids instanceof Node) return LongStream.of(((Node)ids).getId());
        if (ids instanceof Relationship) return LongStream.of(((Relationship)ids).getId());
        if (ids instanceof Collection) {
            Collection<Object> coll = (Collection<Object>) ids;
            return coll.stream().mapToLong( (o) -> ((Number)o).longValue());
        }
        if (ids instanceof Iterable) {
            Spliterator<Object> spliterator = ((Iterable) ids).spliterator();
            return StreamSupport.stream(spliterator,false).mapToLong( (o) -> ((Number)o).longValue());
        }
        throw new RuntimeException("Can't convert "+ids.getClass()+" to a stream of long ids");
    }

    public static Stream<Object> stream(Object values) {
        return Convert.convertToList(values).stream();
    }

    public static Stream<Node> nodeStream(Transaction tx, Object ids) {
        return stream(ids).map(id -> node(tx, id));
    }

    public static Node node(Transaction tx, Object id) {
        if (id instanceof Node) return rebind(tx, (Node)id);
        if (id instanceof Number) return tx.getNodeById(((Number)id).longValue());
        throw new RuntimeException("Can't convert "+id.getClass()+" to a Node");
    }

    public static Stream<Relationship> relsStream(Transaction tx, Object ids) {
        return stream(ids).map(id -> relationship(tx, id));
    }

    public static Relationship relationship(Transaction tx, Object id) {
        if (id instanceof Relationship) return rebind(tx, (Relationship)id);
        if (id instanceof Number) return tx.getRelationshipById(((Number)id).longValue());
        throw new RuntimeException("Can't convert "+id.getClass()+" to a Relationship");
    }

    public static double doubleValue(Entity pc, String prop, Number defaultValue) {
        return toDouble(pc.getProperty(prop, defaultValue));

    }

    public static double doubleValue(Entity pc, String prop) {
        return doubleValue(pc, prop, 0);
    }

    public static Direction parseDirection(String direction) {
        if (null == direction) {
            return Direction.BOTH;
        }
        try {
            return Direction.valueOf(direction.toUpperCase());
        } catch (Exception e) {
            throw new RuntimeException(format("Cannot convert value '%s' to Direction. Legal values are '%s'",
                    direction, Arrays.toString(Direction.values())));
        }
    }

    public static RelationshipType[] toRelTypes(List<String> relTypeStrings) {
        RelationshipType[] relTypes = new RelationshipType[relTypeStrings.size()];
        for (int i = 0; i < relTypes.length; i++) {
            relTypes[i] = RelationshipType.withName(relTypeStrings.get(i));
        }
        return relTypes;
    }

    public static <T> T retryInTx(Log log, GraphDatabaseService db, Function<Transaction, T> function, long retry, long maxRetries, Consumer<Long> callbackForRetry) {
        try (Transaction tx = db.beginTx()) {
            T result = function.apply(tx);
            tx.commit();
            return result;
        } catch (Exception e) {
            if (retry >= maxRetries) throw e;
            if (log!=null) {
                log.warn("Retrying operation %d of %d", retry, maxRetries);
            }
            callbackForRetry.accept(retry);
            Util.sleep(100);
            return retryInTx(log, db, function, retry + 1, maxRetries, callbackForRetry);
        }
    }

    public static <T> Future<T> inTxFuture(Log log,
                                           ExecutorService pool,
                                           GraphDatabaseService db,
                                           Function<Transaction, T> function,
                                           long maxRetries,
                                           Consumer<Long> callbackForRetry,
                                           Consumer<Void> callbackAction) {
        try {
            return pool.submit(() -> {
                try {
                    return retryInTx(log, db, function, 0, maxRetries, callbackForRetry);
                } finally {
                    callbackAction.accept(null);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("Error executing in separate transaction", e);
        }
    }

    public static <T> Future<T> inTxFuture(ExecutorService pool, GraphDatabaseService db, Function<Transaction, T> function) {
        return inTxFuture(null, pool, db, function, 0, _ignored -> {}, _ignored -> {});
    }

    public static <T> T inTx(GraphDatabaseService db, Pools pools, Function<Transaction, T> function) {
        try {
            return inTxFuture(pools.getDefaultExecutorService(), db, function).get();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // TODO: consider dropping the exception to logs
            throw new RuntimeException("Error executing in separate transaction: "+e.getMessage(), e);
        }
    }

    public static <T> T inThread(Pools pools, Callable<T> callable) {
        try {
            return inFuture(pools, callable).get();
        } catch (Exception e) {
            throw new RuntimeException("Error executing in separate thread: "+e.getMessage(), e);
        }
    }

    public static <T> Future<T> inFuture(Pools pools, Callable<T> callable) {
        return pools.getDefaultExecutorService().submit(callable);
    }

    public static Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
        	return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
        	return null;
        }
    }

    public static Map<String, Object> subMap(Map<String, ?> params, String prefix) {
        Map<String, Object> config = new HashMap<>(10);
        int len = prefix.length() + (prefix.isEmpty() || prefix.endsWith(".") ? 0 : 1);
        for (Map.Entry<String, ?> entry : params.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(prefix)) {
                config.put(key.substring(len), entry.getValue());
            }
        }
        return config;
    }

    public static Long toLong(Object value) {
    	if (value == null) return null;
        if (value instanceof Number) return ((Number)value).longValue();
        try {
            String s = value.toString();
            if (s.contains(".")) return (long)Double.parseDouble(s);
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
        	return null;
        }
    }
    public static Integer toInteger(Object value) {
    	if (value == null) return null;
        if (value instanceof Number) return ((Number)value).intValue();
        try {
            String s = value.toString();
            if (s.contains(".")) return (int)Double.parseDouble(s);
        	return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
        	return null;
        }
    }

    public static URLConnection openUrlConnection(String url, Map<String, Object> headers) throws IOException {
        URL src = new URL(url);
        URLConnection con = src.openConnection();
        con.setRequestProperty("User-Agent", "APOC Procedures for Neo4j");
        if (headers != null) {
            Object method = headers.get("method");
            if (method != null && con instanceof HttpURLConnection) {
                HttpURLConnection http = (HttpURLConnection) con;
                http.setRequestMethod(method.toString());
                http.setChunkedStreamingMode(1024*1024);
                http.setInstanceFollowRedirects(true);
            }
            headers.forEach((k,v) -> con.setRequestProperty(k, v == null ? "" : v.toString()));
        }
//        con.setDoInput(true);
        con.setConnectTimeout(apocConfig().getInt("apoc.http.timeout.connect",10_000));
        con.setReadTimeout(apocConfig().getInt("apoc.http.timeout.read",60_000));
        return con;
    }

    public static boolean isRedirect(HttpURLConnection con) throws IOException {
        int code = con.getResponseCode();
        boolean isRedirectCode = code >= 300 && code < 400;
        if (isRedirectCode) {
            URL location = new URL(con.getHeaderField("Location"));
            String oldProtocol = con.getURL().getProtocol();
            String protocol = location.getProtocol();
            if (!protocol.equals(oldProtocol) && !protocol.startsWith(oldProtocol)) { // we allow http -> https redirect and similar
                throw new RuntimeException("The redirect URI has a different protocol: " + location.toString());
            }
        }
        return isRedirectCode;
    }

    private static void writePayload(URLConnection con, String payload) throws IOException {
        if (payload == null) return;
        con.setDoOutput(true);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(con.getOutputStream(),"UTF-8"));
        writer.write(payload);
        writer.close();
    }

    private static String handleRedirect(URLConnection con, String url) throws IOException {
       if (!(con instanceof HttpURLConnection)) return url;
       if (!isRedirect(((HttpURLConnection)con))) return url;
       return con.getHeaderField("Location");
    }

    public static CountingInputStream openInputStream(String urlAddress, Map<String, Object> headers, String payload) throws IOException {
        StreamConnection sc;
        InputStream stream;
        if (urlAddress.contains("!") && (urlAddress.contains(".zip") || urlAddress.contains(".tar") || urlAddress.contains(".tgz"))) {
            return getStreamCompressedFile(urlAddress, headers, payload);
        }

        sc = getStreamConnection(urlAddress, headers, payload);
        stream = getInputStream(sc, urlAddress);

        return new CountingInputStream(stream, sc.getLength());
    }

    private static CountingInputStream getStreamCompressedFile(String urlAddress, Map<String, Object> headers, String payload) throws IOException {
        StreamConnection sc;
        InputStream stream;
        String[] tokens = urlAddress.split("!");
        urlAddress = tokens[0];
        String zipFileName;
        if(tokens.length == 2) {
            zipFileName = tokens[1];
            sc = getStreamConnection(urlAddress, headers, payload);
            stream = getFileStreamIntoCompressedFile(sc.getInputStream(), zipFileName);
        }else
            throw new IllegalArgumentException("filename can't be null or empty");

        return new CountingInputStream(stream, sc.getLength());
    }

    private static StreamConnection getStreamConnection(String urlAddress, Map<String, Object> headers, String payload) throws IOException {
        URL url = new URL(urlAddress);
        String protocol = url.getProtocol();
        if (FileUtils.S3_PROTOCOL.equalsIgnoreCase(protocol)) {
            return FileUtils.openS3InputStream(url);
        } else if (FileUtils.HDFS_PROTOCOL.equalsIgnoreCase(protocol)) {
            return FileUtils.openHdfsInputStream(url);
        } else {
            return readHttpInputStream(urlAddress, headers, payload);
        }
    }

    private static InputStream getInputStream(StreamConnection sc, String urlAddress) throws IOException {
        InputStream stream = sc.getInputStream();
        String encoding = sc.getEncoding();

        if ("gzip".equals(encoding) || urlAddress.endsWith(".gz")) {
             return new GZIPInputStream(stream);
        }
        if ("deflate".equals(encoding)) {
            return new DeflaterInputStream(stream);
        }

        return stream;
    }

    private static InputStream getFileStreamIntoCompressedFile(InputStream is, String fileName) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(is)) {
            ZipEntry zipEntry;

            while ((zipEntry = zip.getNextEntry()) != null) {
                if (!zipEntry.isDirectory() && zipEntry.getName().equals(fileName)) {
                    return new ByteArrayInputStream(IOUtils.toByteArray(zip));
                }
            }
        }

        return null;
    }

    private static StreamConnection readHttpInputStream(String urlAddress, Map<String, Object> headers, String payload) throws IOException {
        URLConnection con = openUrlConnection(urlAddress, headers);
        writePayload(con, payload);
        String newUrl = handleRedirect(con, urlAddress);
        if (newUrl != null && !urlAddress.equals(newUrl)) {
            con.getInputStream().close();
            return readHttpInputStream(newUrl, headers, payload);
        }

        return new StreamConnection.UrlStreamConnection(con);
    }

    public static boolean toBoolean(Object value) {
        if ((value == null || value instanceof Number && (((Number) value).longValue()) == 0L || value instanceof String && (value.equals("") || ((String) value).equalsIgnoreCase("false") || ((String) value).equalsIgnoreCase("no")|| ((String) value).equalsIgnoreCase("0"))|| value instanceof Boolean && value.equals(false))) {
            return false;
        }
        return true;
    }

    public static String encodeUrlComponent(String value) {
        try {
            return URLEncoder.encode(value,"UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Unsupported character set utf-8");
        }
    }

    public static String toJson(Object value) {
        try {
            return JsonUtil.OBJECT_MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new RuntimeException("Can't convert "+value+" to JSON");
        }
    }
    public static <T> T fromJson(String value, Class<T> type) {
        try {
            return JsonUtil.OBJECT_MAPPER.readValue(value,type);
        } catch (IOException e) {
            throw new RuntimeException("Can't convert "+value+" from JSON");
        }
    }

    public static Stream<List<Object>> partitionSubList(List<Object> data, int partitions) {
        return partitionSubList(data,partitions,null);
    }
    public static Stream<List<Object>> partitionSubList(List<Object> data, int partitions, List<Object> tombstone) {
        if (partitions==0) partitions=1;
        List<Object> list = new ArrayList<>(data);
        int total = list.size();
        int batchSize = Math.max((int)Math.ceil((double)total / partitions),1);
        Stream<List<Object>> stream = IntStream.range(0, partitions).parallel()
                .mapToObj((part) -> list.subList(Math.min(part * batchSize, total), Math.min((part + 1) * batchSize, total)))
                .filter(partition -> !partition.isEmpty());
        return tombstone == null ? stream : Stream.concat(stream,Stream.of(tombstone));
    }

    public static Long runNumericQuery(Transaction tx, String query, Map<String, Object> params) {
        if (params == null) params = Collections.emptyMap();
        try (ResourceIterator<Long> it = tx.execute(query,params).<Long>columnAs("result")) {
            return it.next();
        }
    }

    public static long nodeCount(Transaction tx) {
        return runNumericQuery(tx,NODE_COUNT,null);
    }
    public static long relCount(Transaction tx) {
        return runNumericQuery(tx,REL_COUNT,null);
    }

    public static LongStream toLongStream(LongIterator it) {
        PrimitiveIterator.OfLong iterator = new PrimitiveIterator.OfLong() {

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public long nextLong() {
                return it.next();
            }
        };
        return StreamSupport.longStream(Spliterators.spliteratorUnknownSize(
                iterator,
                Spliterator.ORDERED | Spliterator.IMMUTABLE | Spliterator.NONNULL), false);
    }

    public static String readResourceFile(String name) {
        InputStream is = Util.class.getClassLoader().getResourceAsStream(name);
        return new Scanner(is).useDelimiter("\\Z").next();
    }

    @SuppressWarnings("unchecked")
    public static Map<String,Object> readMap(String value) {
        try {
            return JsonUtil.OBJECT_MAPPER.readValue(value, Map.class);
        } catch (IOException e) {
            throw new RuntimeException("Couldn't read as JSON "+value);
        }
    }

    public static <T> List<T> take(Iterator<T> iterator, int batchsize) {
        List<T> result = new ArrayList<>(batchsize);
        while (iterator.hasNext() && batchsize-- > 0) {
            result.add(iterator.next());
        }
        return result;
    }

    public static Map<String, Object> merge(Map<String, Object> first, Map<String, Object> second) {
        if (second == null || second.isEmpty()) return first == null ? Collections.EMPTY_MAP : first;
        if (first == null || first.isEmpty()) return second == null ? Collections.EMPTY_MAP : second;
        Map<String,Object> combined = new HashMap<>(first);
        combined.putAll(second);
        return combined;
    }

    public static <T> Map<String, T> map(T ... values) {
        Map<String, T> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i+=2) {
            if (values[i] == null) continue;
            map.put(values[i].toString(),values[i+1]);
        }
        return map;
    }

    public static <T> Map<String, T> map(List<T> pairs) {
        Map<String, T> res = new LinkedHashMap<>(pairs.size() / 2);
        Iterator<T> it = pairs.iterator();
        while (it.hasNext()) {
            Object key = it.next();
            T value = it.next();
            if (key != null) res.put(key.toString(), value);
        }
        return res;
    }

    public static <T,R> List<R> map(Stream<T> stream, Function<T,R> mapper) {
        return stream.map(mapper).collect(Collectors.toList());
    }

    public static <T,R> List<R> map(Collection<T> collection, Function<T,R> mapper) {
        return map(collection.stream(), mapper);
    }

    public static Map<String, Object> mapFromLists(List<String> keys, List<Object> values) {
        if (keys == null || values == null || keys.size() != values.size())
            throw new RuntimeException("keys and values lists have to be not null and of same size");
        if (keys.isEmpty()) return Collections.emptyMap();
        if (keys.size()==1) return Collections.singletonMap(keys.get(0),values.get(0));
        ListIterator<Object> it = values.listIterator();
        Map<String, Object> res = new LinkedHashMap<>(keys.size());
        for (String key : keys) {
            res.put(key,it.next());
        }
        return res;
    }

    public static Map<String, Object> mapFromPairs(List<List<Object>> pairs) {
        if (pairs.isEmpty()) return Collections.emptyMap();
        Map<String,Object> map = new LinkedHashMap<>(pairs.size());
        for (List<Object> pair : pairs) {
            if (pair.isEmpty()) continue;
            Object key = pair.get(0);
            if (key==null) continue;
            Object value = pair.size() >= 2 ? pair.get(1) : null;
            map.put(key.toString(),value);
        }
        return map;
    }

    public static String cleanUrl(String url) {
        try {
            URL source = new URL(url);
            String file = source.getFile();
            if (source.getRef() != null) file += "#"+source.getRef();
            return new URL(source.getProtocol(),source.getHost(),source.getPort(),file).toString();
        } catch (MalformedURLException mfu) {
            return String.format("invalid URL (%s)", url);
        }
    }

    public static <T> T getFuture(Future<T> f, Map<String, Long> errorMessages, AtomicInteger errors, T errorValue) {
        try {
            T t = f.get();
            return t;
        } catch (Exception e) {
            errors.incrementAndGet();
            errorMessages.compute(e.getMessage(),(s, i) -> i == null ? 1 : i + 1);
            return errorValue;
        }
    }
    public static <T> T getFutureOrCancel(Future<T> f, Map<String, Long> errorMessages, AtomicInteger errors, T errorValue) {
        try {
            if (f.isDone()) return f.get();
            else {
                f.cancel(false);
                errors.incrementAndGet();
            }
        } catch (Exception e) {
            errors.incrementAndGet();
            errorMessages.compute(e.getMessage(),(s, i) -> i == null ? 1 : i + 1);
        }
        return errorValue;
    }

    public static boolean isSumOutOfRange(long... numbers) {
        try {
            sumLongs(numbers).longValueExact();
            return false;
        } catch (ArithmeticException ae) {
            return true;
        }
    }

    public static BigInteger sumLongs(long... numbers) {
        return LongStream.of(numbers)
                .mapToObj(BigInteger::valueOf)
                .reduce(BigInteger.ZERO, (x, y) -> x.add(y));
    }

    public static void logErrors(String message, Map<String, Long> errors, Log log) {
        if (!errors.isEmpty()) {
            log.bulk(l -> {
                l.warn(message);
                errors.forEach((k, v) -> l.warn("%d times: %s",v,k));
            });
        }
    }

    public static void checkAdmin(SecurityContext securityContext, String procedureName) {
        if (!securityContext.allowExecuteAdminProcedure()) throw new RuntimeException("This procedure "+ procedureName +" is only available to admin users");
    }

    public static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            /* ignore */
        }
    }

    public static String quote(String var) {
        return SourceVersion.isIdentifier(var) && !var.startsWith("$") ? var : '`' + var + '`';
    }

    public static String sanitizeAndQuote(String var) {
        return quote(var.replaceAll("`", ""));
    }

    public static String param(String var) {
        return var.charAt(0) == '$' ? var : '$'+quote(var);
    }

    public static String withMapping(Stream<String> columns, Function<String, String> withMapping) {
        String with = columns.map(withMapping).collect(Collectors.joining(","));
        return with.isEmpty() ? with : " WITH "+with+" ";
    }

    public static boolean isWriteableInstance(GraphDatabaseAPI db) {
        try {
            try {
                Class hadb = Class.forName("org.neo4j.kernel.ha.HighlyAvailableGraphDatabase");
                boolean isSlave = hadb.isInstance(db) && !((Boolean)hadb.getMethod("isMaster").invoke(db));
                if (isSlave) return false;
            } catch (ClassNotFoundException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                /* ignore */
            }

            String role = db.executeTransactionally("CALL dbms.cluster.role($databaseName)",
                    Collections.singletonMap("databaseName", db.databaseName()),
                    result -> Iterators.single(result.columnAs("role")));
            return role.equalsIgnoreCase("LEADER");
        } catch(QueryExecutionException e) {
            if (e.getStatusCode().equalsIgnoreCase("Neo.ClientError.Procedure.ProcedureNotFound")) return true;
            throw e;
        }
    }

    /**
     * Given a context related to the procedure invocation this method checks if the transaction is terminated in some way
     *
     * @param db
     * @return
     *
     */
    public static boolean transactionIsTerminated(TerminationGuard db) {
        try {
            db.check();
            return false;
        } catch (TransactionGuardException | TransactionTerminatedException | NotInTransactionException tge) {
            return true;
        }
    }

    public static void waitForFutures(List<Future> futures) {
        for (Future future : futures) {
            try {
                if (future != null) future.get();
            } catch (InterruptedException | ExecutionException e) {
                // ignore
            }
        }
    }

    public static void removeFinished(List<Future> futures) {
        if (futures.size() > 25) {
            futures.removeIf(Future::isDone);
        }
    }

    public static void close(AutoCloseable closeable, Consumer<Exception> onErrror) {
        try {
            if (closeable!=null) closeable.close();
        } catch (Exception e) {
            // ignore
            if (onErrror != null) {
                onErrror.accept(e);
            }
        }
    }

    public static void close(AutoCloseable closeable) {
        close(closeable, null);
    }

    public static boolean isNotNullOrEmpty(String s) {
        return s!=null && s.trim().length()!=0;
    }
    public static boolean isNullOrEmpty(String s) {
        return s==null || s.trim().length()==0;
    }

    public static Map<String, String> getRequestParameter(String parameters){
        Map<String, String> params = null;

        if(Objects.nonNull(parameters)){
            params = new HashMap<>();
            String[] queryStrings = parameters.split("&");
            for (String query : queryStrings) {
                String[] parts = query.split("=");
                if (parts.length == 2) {
                    params.put(parts[0], parts[1]);
                }
            }
        }
        return params;
    }

    public static boolean classExists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch(ClassNotFoundException cnfe) {
            return false;
        }
    }

    public static <T> T createInstanceOrNull(String className) {
        try {
            return (T)Class.forName(className).getDeclaredConstructor().newInstance();
        } catch (IllegalAccessException | InstantiationException | ClassNotFoundException | NoSuchMethodException | InvocationTargetException e) {
            return null;
        }
    }

    public static Optional<String> getLoadUrlByConfigFile(String loadType, String key, String suffix){
        key = Optional.ofNullable(key)
                .map(s ->
                        Stream.of("apoc", loadType, s, suffix).collect(Collectors.joining("."))
                )
                .orElse(StringUtils.EMPTY);
        String value = apocConfig().getString(key);
        return Optional.ofNullable(value);
    }

    public static String dateFormat(TemporalAccessor value, String format){
        return getFormat(format).format(value);
    }

    public static Duration durationParse(String value) {
        return Duration.parse(value);
    }

    public static DateTimeFormatter getFormat(String format) {
        return getOrCreate(format);
    }

    public static char parseCharFromConfig(Map<String, Object> config, String key, char defaultValue) {
        String separator = (String) config.getOrDefault(key, "");
        if (separator == null || separator.isEmpty()) {
            return defaultValue;
        }
        if ("TAB".equals(separator)) {
            return '\t';
        }
        return separator.charAt(0);
    }

    public static Map<String, Object> flattenMap(Map<String, Object> map) {
        return flattenMap(map, null);
    }

    public static Map<String, Object> flattenMap(Map<String, Object> map, String prefix) {
        return map.entrySet().stream()
                .flatMap(entry -> {
                    String key;
                    if (prefix != null && !prefix.isEmpty()) {
                        key = prefix + "." + entry.getKey();
                    } else {
                        key = entry.getKey();
                    }
                    Object value = entry.getValue();
                    if (value instanceof Map) {
                        return flattenMap((Map<String, Object>) value, key).entrySet().stream();
                    } else {
                        Map.Entry<String, Object> newEntry = new AbstractMap.SimpleEntry(key, entry.getValue());
                        return Stream.of(newEntry);
                    }
                })
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
    }

    public static Node rebind(Transaction tx, Node node) {
         return node instanceof VirtualNode ? node : tx.getNodeById(node.getId());
    }

    public static Relationship rebind(Transaction tx, Relationship rel) {
         return rel instanceof VirtualRelationship ? rel : tx.getRelationshipById(rel.getId());
    }

    public static <T extends Entity> T rebind(Transaction tx, T e) {
        if (e instanceof Node) {
            return (T) rebind(tx, (Node) e);
        } else {
            return (T) rebind(tx, (Relationship) e);
        }
    }

    public static <T extends Entity> List<T> rebind(List<T> entities, Transaction tx) {
        return entities.stream()
                .map(n -> Util.rebind(tx, n))
                .collect(Collectors.toList());
    }

    public static Node mergeNode(Transaction tx, Label primaryLabel, Label addtionalLabel,
                                 Pair<String, Object>... pairs ) {
        Node node = Iterators.singleOrNull(tx.findNodes(primaryLabel, pairs[0].first(), pairs[0].other()).stream()
                .filter(n -> addtionalLabel!=null && n.hasLabel(addtionalLabel))
                .filter( n -> {
                    for (int i=1; i<pairs.length; i++) {
                        if (!Objects.deepEquals(pairs[i].other(), n.getProperty(pairs[i].first(), null))) {
                            return false;
                        }
                    }
                    return true;
                })
                .iterator());
        if (node==null) {
            Label[] labels = addtionalLabel == null ?
                    new Label[]{primaryLabel} :
                    new Label[]{primaryLabel, addtionalLabel};
            node = tx.createNode(labels);
            for (int i=0; i<pairs.length; i++) {
                node.setProperty(pairs[i].first(), pairs[i].other());
            }
        }
        return node;
    }

    public static <T> Set<T> intersection(Collection<T> a, Collection<T> b) {
        if (a == null || b == null) {
            return Collections.emptySet();
        }
        Set<T> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return intersection;
    }

    public static void validateQuery(GraphDatabaseService db, String statement, QueryExecutionType.QueryType... supportedQueryTypes) {
        final boolean isValid = db.executeTransactionally("EXPLAIN " + statement, Collections.emptyMap(), result ->
                supportedQueryTypes == null || supportedQueryTypes.length == 0 || Stream.of(supportedQueryTypes)
                        .anyMatch(sqt -> sqt.equals(result.getQueryExecutionType().queryType())));

        if (!isValid) {
            throw new RuntimeException("Supported query types for the operation are " + Arrays.toString(supportedQueryTypes));
        }
    }

    /**
     * all new threads being created within apoc should be daemon threads to allow graceful termination, so use this whenever you need a thread
     * @param target
     * @return
     */
    public static Thread newDaemonThread(Runnable target) {
        Thread thread = new Thread(target);
        thread.setDaemon(true);
        return thread;
    }
    
    public static String encodeUserColonPassToBase64(String userPass) {
        return new String(Base64.getEncoder().encode((userPass).getBytes()));
    }

    public static Map<String, Object> extractCredentialsIfNeeded(String url, boolean failOnError) {
        try {
            URI uri = new URI(encodePath(url));
            String authInfo = uri.getUserInfo();
            if (null != authInfo) {
                String[] parts = authInfo.split(":");
                if (2 == parts.length) {
                    String token = encodeUserColonPassToBase64(authInfo);
                    return MapUtil.map("Authorization", "Basic " + token);
                }
            }
        } catch (Exception e) {
            if(!failOnError)
                return Collections.emptyMap();
            else
                throw new RuntimeException(e);
        }

        return Collections.emptyMap();
    }

    public static boolean isSelfRel(Relationship rel) {
        return rel.getStartNodeId() == rel.getEndNodeId();
    }
}
