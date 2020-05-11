package apoc.periodic;

import apoc.ApocConfig;
import apoc.Pools;
import apoc.concurrent.Semaphores;
import apoc.util.Util;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.helpers.collection.Iterables;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.internal.helpers.collection.Pair;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.TerminationGuard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static apoc.ApocConfig.APOC_SEMAPHORE_DEFAULT_NAME;
import static apoc.util.Util.merge;

public class Periodic {

    public static final Pattern RUNTIME_PATTERN = Pattern.compile("\\bruntime\\s*=", Pattern.CASE_INSENSITIVE);
    public static final Pattern CYPHER_PREFIX_PATTERN = Pattern.compile("\\bcypher\\b", Pattern.CASE_INSENSITIVE);
    public static final String CYPHER_RUNTIME_SLOTTED = "cypher runtime=slotted ";
    final static Pattern LIMIT_PATTERN = Pattern.compile("\\slimit\\s", Pattern.CASE_INSENSITIVE);

    @Context public GraphDatabaseService db;
    @Context public TerminationGuard terminationGuard;
    @Context public Log log;
    @Context public Pools pools;
    @Context public Transaction tx;
    @Context public ApocConfig apocConfig;
    @Context public Semaphores semaphores;

    @Procedure
    @Description("apoc.periodic.list - list all jobs")
    public Stream<JobInfo> list() {
        return pools.getJobList().entrySet().stream().map( (e) -> e.getKey().update(e.getValue()));
    }

    @Procedure(mode = Mode.WRITE)
    @Description("apoc.periodic.commit(statement,params) - runs the given statement in separate transactions until it returns 0")
    public Stream<RundownResult> commit(@Name("statement") String statement, @Name(value = "params", defaultValue = "{}") Map<String,Object> parameters) throws ExecutionException, InterruptedException {
        validateQuery(statement);
        Map<String,Object> params = parameters == null ? Collections.emptyMap() : parameters;
        long total = 0, executions = 0, updates = 0;
        long start = System.nanoTime();

        if (!LIMIT_PATTERN.matcher(statement).find()) {
            throw new IllegalArgumentException("the statement sent to apoc.periodic.commit must contain a `limit`");
        }

        AtomicInteger batches = new AtomicInteger();
        AtomicInteger failedCommits = new AtomicInteger();
        Map<String,Long> commitErrors = new ConcurrentHashMap<>();
        AtomicInteger failedBatches = new AtomicInteger();
        Map<String,Long> batchErrors = new ConcurrentHashMap<>();

        do {
            Map<String, Object> window = Util.map("_count", updates, "_total", total);
            updates = Util.getFuture(pools.getScheduledExecutorService().submit(() -> {
                batches.incrementAndGet();
                try {
                    return executeNumericResultStatement(statement, merge(window, params));
                } catch(Exception e) {
                    failedBatches.incrementAndGet();
                    recordError(batchErrors, e);
                    return 0L;
                }
            }), commitErrors, failedCommits, 0L);
            total += updates;
            if (updates > 0) executions++;
        } while (updates > 0 && !Util.transactionIsTerminated(terminationGuard));
        long timeTaken = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        boolean wasTerminated = Util.transactionIsTerminated(terminationGuard);
        return Stream.of(new RundownResult(total,executions, timeTaken, batches.get(),failedBatches.get(),batchErrors, failedCommits.get(), commitErrors, wasTerminated));
    }

    private void recordError(Map<String, Long> executionErrors, Exception e) {
        String msg = ExceptionUtils.getRootCause(e).getMessage();
        // String msg = ExceptionUtils.getThrowableList(e).stream().map(Throwable::getMessage).collect(Collectors.joining(","))
        executionErrors.compute(msg, (s, i) -> i == null ? 1 : i + 1);
    }

    public static class RundownResult {
        public final long updates;
        public final long executions;
        public final long runtime;
        public final long batches;
        public final long failedBatches;
        public final Map<String, Long> batchErrors;
        public final long failedCommits;
        public final Map<String, Long> commitErrors;
        public final boolean wasTerminated;

        public RundownResult(long total, long executions, long timeTaken, long batches, long failedBatches, Map<String, Long> batchErrors, long failedCommits, Map<String, Long> commitErrors, boolean wasTerminated) {
            this.updates = total;
            this.executions = executions;
            this.runtime = timeTaken;
            this.batches = batches;
            this.failedBatches = failedBatches;
            this.batchErrors = batchErrors;
            this.failedCommits = failedCommits;
            this.commitErrors = commitErrors;
            this.wasTerminated = wasTerminated;
        }
    }

    private long executeNumericResultStatement(@Name("statement") String statement, @Name("params") Map<String, Object> parameters) {
        return db.executeTransactionally(statement, parameters, result -> {
            String column = Iterables.single(result.columns());
            return result.columnAs(column).stream().mapToLong( o -> (long)o).sum();
        });
    }

    @Procedure
    @Description("apoc.periodic.cancel(name) - cancel job with the given name")
    public Stream<JobInfo> cancel(@Name("name") String name) {
        JobInfo info = new JobInfo(name);
        Future future = pools.getJobList().remove(info);
        if (future != null) {
            future.cancel(false);
            return Stream.of(info.update(future));
        }
        return Stream.empty();
    }

    @Procedure(mode = Mode.WRITE)
    @Description("apoc.periodic.submit('name',statement) - submit a one-off background statement")
    public Stream<JobInfo> submit(@Name("name") String name, @Name("statement") String statement) {
        validateQuery(statement);
        JobInfo info = submit(name, () -> {
            try {
                db.executeTransactionally(statement);
            } catch(Exception e) {
                log.warn("in background task via submit", e);
                throw new RuntimeException(e);
            }
        }, log);
        return Stream.of(info);
    }

    @Procedure(mode = Mode.WRITE)
    @Description("apoc.periodic.repeat('name',statement,repeat-rate-in-seconds, config) submit a repeatedly-called background statement. Fourth parameter 'config' is optional and can contain 'params' entry for nested statement.")
    public Stream<JobInfo> repeat(@Name("name") String name, @Name("statement") String statement, @Name("rate") long rate, @Name(value = "config", defaultValue = "{}") Map<String,Object> config ) {
        validateQuery(statement);
        Map<String,Object> params = (Map)config.getOrDefault("params", Collections.emptyMap());
        JobInfo info = schedule(name, () -> {
            db.executeTransactionally(statement, params);
        },0,rate);
        return Stream.of(info);
    }

    private void validateQuery(String statement) {
        db.executeTransactionally("EXPLAIN " + statement);
    }

    @Procedure(mode = Mode.WRITE)
    @Description("apoc.periodic.countdown('name',statement,repeat-rate-in-seconds) submit a repeatedly-called background statement until it returns 0")
    public Stream<JobInfo> countdown(@Name("name") String name, @Name("statement") String statement, @Name("rate") long rate) {
        validateQuery(statement);
        JobInfo info = submit(name, new Countdown(name, statement, rate, log), log);
        info.rate = rate;
        return Stream.of(info);
    }

    /**
     * Call from a procedure that gets a <code>@Context GraphDatbaseAPI db;</code> injected and provide that db to the runnable.
     */
    public <T> JobInfo submit(String name, Runnable task, Log log) {
        JobInfo info = new JobInfo(name);
        Future<T> future = pools.getJobList().remove(info);
        if (future != null && !future.isDone()) future.cancel(false);

        Runnable wrappingTask = wrapTask(name, task, log);
        Future newFuture = pools.getScheduledExecutorService().submit(wrappingTask);
        pools.getJobList().put(info,newFuture);
        return info;
    }

    /**
     * Call from a procedure that gets a <code>@Context GraphDatbaseAPI db;</code> injected and provide that db to the runnable.
     */
    public JobInfo schedule(String name, Runnable task, long delay, long repeat) {
        JobInfo info = new JobInfo(name,delay,repeat);
        Future future = pools.getJobList().remove(info);
        if (future != null && !future.isDone()) future.cancel(false);

        Runnable wrappingTask = wrapTask(name, task, log);
        ScheduledFuture<?> newFuture = pools.getScheduledExecutorService().scheduleWithFixedDelay(wrappingTask, delay, repeat, TimeUnit.SECONDS);
        pools.getJobList().put(info,newFuture);
        return info;
    }

    private static Runnable wrapTask(String name, Runnable task, Log log) {
        return () -> {
            log.debug("Executing task " + name);
            try {
                task.run();
            } catch (Exception e) {
                log.error("Error while executing task " + name + " because of the following exception (the task will be killed):", e);
                throw e;
            }
            log.debug("Executed task " + name);
        };
    }

    /**
     * as long as cypherLoop does not return 0, null, false, or the empty string as 'value' do:
     *
     * invoke cypherAction in batched transactions being feeded from cypherIteration running in main thread
     *
     * @param cypherLoop
     * @param cypherIterate
     * @param cypherAction
     * @param batchSize
     */
    @Procedure(mode = Mode.WRITE)
    @Deprecated
    @Description("apoc.periodic.rock_n_roll_while('some cypher for knowing when to stop', 'some cypher for iteration', 'some cypher as action on each iteration', 10000) YIELD batches, total - run the action statement in batches over the iterator statement's results in a separate thread. Returns number of batches and total processed rows")
    public Stream<LoopingBatchAndTotalResult> rock_n_roll_while(
            @Name("cypherLoop") String cypherLoop,
            @Name("cypherIterate") String cypherIterate,
            @Name("cypherAction") String cypherAction,
            @Name("batchSize") long batchSize) {
        Map<String, String> fieldStatement = Util.map(
                "cypherLoop", cypherLoop,
                "cypherIterate", cypherIterate);
        validateQueries(fieldStatement);
        Stream<LoopingBatchAndTotalResult> allResults = Stream.empty();

        Map<String,Object> loopParams = new HashMap<>(1);
        Object value = null;

        while (true) {
            loopParams.put("previous", value);

            try (Result result = tx.execute(cypherLoop, loopParams)) {
                value = result.next().get("loop");
                if (!Util.toBoolean(value)) return allResults;
            }

            log.info("starting batched operation using iteration `%s` in separate thread", cypherIterate);
            try (Result result = tx.execute(cypherIterate)) {
                Stream<BatchAndTotalResult> oneResult =
                    iterateAndExecuteBatchedInSeparateThread((int) batchSize, false, false,0, result, (tx, params) -> tx.execute(cypherAction, params), 50, -1);
                final Object loopParam = value;
                allResults = Stream.concat(allResults, oneResult.map(r -> r.inLoop(loopParam)));
            }
        }
    }

    private void validateQueries(Map<String, String> fieldStatement) {
        String error = fieldStatement.entrySet()
                .stream()
                .map(e -> {
                    try {
                        validateQuery(e.getValue());
                        return null;
                    } catch (Exception exception) {
                        return String.format("Exception for field `%s`, message: %s", e.getKey(), exception.getMessage());
                    }
                })
                .filter(e -> e != null)
                .collect(Collectors.joining("\n"));
        if (!error.isEmpty()) {
            throw new RuntimeException(error);
        }
    }

    /**
     * invoke cypherAction in batched transactions being feeded from cypherIteration running in main thread
     * @param cypherIterate
     * @param cypherAction
     */
    @Procedure(mode = Mode.WRITE)
    @Description("apoc.periodic.iterate('statement returning items', 'statement per item', {batchSize:1000,iterateList:true,parallel:false,params:{},concurrency:50,retries:0}) YIELD batches, total - run the second statement for each item returned by the first statement. Returns number of batches and total processed rows")
    public Stream<BatchAndTotalResult> iterate(
            @Name("cypherIterate") String cypherIterate,
            @Name("cypherAction") String cypherAction,
            @Name("config") Map<String,Object> config) {
        validateQuery(cypherIterate);

        String semaphoreName = (String) config.getOrDefault("semaphore", apocConfig.getString(APOC_SEMAPHORE_DEFAULT_NAME));

        return semaphores.withSemaphore(terminationGuard, semaphoreName, () -> {
            long batchSize = Util.toLong(config.getOrDefault("batchSize", 10000));
            int concurrency = Util.toInteger(config.getOrDefault("concurrency", 50));
            boolean parallel = Util.toBoolean(config.getOrDefault("parallel", false));
            boolean iterateList = Util.toBoolean(config.getOrDefault("iterateList", true));
            long retries = Util.toLong(config.getOrDefault("retries", 0)); // todo sleep/delay or push to end of batch to try again or immediate ?
            Map<String,Object> params = (Map<String, Object>) config.getOrDefault("params", Collections.emptyMap());
            int failedParams = Util.toInteger(config.getOrDefault("failedParams", -1));
            try (Result result = tx.execute(slottedRuntime(cypherIterate),params)) {
                Pair<String,Boolean> prepared = prepareInnerStatement(cypherAction, iterateList, result.columns(), "_batch");
                String innerStatement = prepared.first();
                iterateList=prepared.other();
                log.info("starting batching from `%s` operation using iteration `%s` in separate thread", cypherIterate, cypherAction);
                return iterateAndExecuteBatchedInSeparateThread((int)batchSize, parallel, iterateList, retries, result,
                        (tx, p) -> Iterators.count(tx.execute(innerStatement, merge(params, p))), concurrency, failedParams);
            }
        });

    }

    static String slottedRuntime(String cypherIterate) {
        if (RUNTIME_PATTERN.matcher(cypherIterate).find()) {
            return cypherIterate;
        }
        Matcher matcher = CYPHER_PREFIX_PATTERN.matcher(cypherIterate.substring(0, Math.min(15,cypherIterate.length())));
        return matcher.find() ? CYPHER_PREFIX_PATTERN.matcher(cypherIterate).replaceFirst(CYPHER_RUNTIME_SLOTTED) : CYPHER_RUNTIME_SLOTTED + cypherIterate;
    }

    public Pair<String,Boolean> prepareInnerStatement(String cypherAction, boolean iterateList, List<String> columns, String iterator) {
        String names = columns.stream().map(Util::quote).collect(Collectors.joining("|"));
        boolean withCheck = regNoCaseMultiLine("[{$](" + names + ")\\}?\\s+AS\\s+").matcher(cypherAction).find();
        if (withCheck) return Pair.of(cypherAction, false);
        if (iterateList) {
            if (regNoCaseMultiLine("UNWIND\\s+[{$]" + iterator+"\\}?\\s+AS\\s+").matcher(cypherAction).find()) return Pair.of(cypherAction, true);
            String with = Util.withMapping(columns.stream(), (c) -> Util.quote(iterator) + "." + Util.quote(c) + " AS " + Util.quote(c));
            return Pair.of("UNWIND "+ Util.param(iterator)+" AS "+ Util.quote(iterator) + with + " " + cypherAction,true);
        }
        return Pair.of(Util.withMapping(columns.stream(), (c) ->  Util.param(c) + " AS " + Util.quote(c)) + cypherAction,false);
    }

    public Pattern regNoCaseMultiLine(String pattern) {
        return Pattern.compile(pattern,Pattern.CASE_INSENSITIVE|Pattern.MULTILINE|Pattern.DOTALL);
    }

    @Deprecated
    @Procedure(mode = Mode.WRITE)
    @Description("apoc.periodic.rock_n_roll('some cypher for iteration', 'some cypher as action on each iteration', 10000) YIELD batches, total - run the action statement in batches over the iterator statement's results in a separate thread. Returns number of batches and total processed rows")
    public Stream<BatchAndTotalResult> rock_n_roll(
            @Name("cypherIterate") String cypherIterate,
            @Name("cypherAction") String cypherAction,
            @Name("batchSize") long batchSize) {
        Map<String, String> fieldStatement = Util.map(
                "cypherIterate", cypherIterate,
                "cypherAction", cypherAction);
        validateQueries(fieldStatement);

        log.info("starting batched operation using iteration `%s` in separate thread", cypherIterate);
        try (Result result = tx.execute(cypherIterate)) {
            return iterateAndExecuteBatchedInSeparateThread((int)batchSize, false, false, 0, result, (tx, p) -> tx.execute(cypherAction, p), 50, -1);
        }
    }

    private Stream<BatchAndTotalResult> iterateAndExecuteBatchedInSeparateThread(int batchsize, boolean parallel, boolean iterateList, long retries,
                  Iterator<Map<String, Object>> iterator, BiConsumer<Transaction, Map<String, Object>> consumer, int concurrency, int failedParams) {

        ExecutorService pool = parallel ? pools.getDefaultExecutorService() : Executors.newSingleThreadExecutor();
        try {
            List<Future<Long>> futures = new ArrayList<>(concurrency);
            BatchAndTotalCollector collector = new BatchAndTotalCollector(terminationGuard, failedParams);
            do {
                if (Util.transactionIsTerminated(terminationGuard)) break;
                if (log.isDebugEnabled()) log.debug("execute in batch no %d batch size ", batchsize);
                List<Map<String, Object>> batch = Util.take(iterator, batchsize);
                final long currentBatchSize = batch.size();
                Function<Transaction, Long> task;
                if (iterateList) {
                    task = txInThread -> {
                        if (Util.transactionIsTerminated(terminationGuard)) return 0L;
                        Map<String, Object> params = Util.map("_count", collector.getCount(), "_batch", batch);
                        long successes = executeAndReportErrors(txInThread, consumer, params, batch, batch.size(), null, collector);
                        return successes;
                    };
                } else {
                    task = txInThread -> {
                        if (Util.transactionIsTerminated(terminationGuard)) return 0L;
                        AtomicLong localCount = new AtomicLong(collector.getCount());
                        return batch.stream().map(
                                p -> {
                                    if (localCount.get() % 1000 == 0 && Util.transactionIsTerminated(terminationGuard)) {
                                        return 0;
                                    }
                                    Map<String, Object> params = merge(p, Util.map("_count", localCount.get(), "_batch", batch));
                                    return executeAndReportErrors(txInThread, consumer, params, batch, 1, localCount, collector);
                                }).mapToLong(n -> (Long) n).sum();
                    };
                }
                futures.add(Util.inTxFuture(log, pool, db, task, retries, aLong -> collector.incrementRetried(), _ignored -> collector.incrementBatches()));
            /*  TODO: not sure if the block below is required
            if (futures.size() > concurrency) {
                while (futures.stream().noneMatch(Future::isDone)) { // none done yet, block for a bit
                    LockSupport.parkNanos(1000);
                }
                Iterator<Future<Long>> it = futures.iterator();
                while (it.hasNext()) {
                    Future<Long> future = it.next();
                    if (future.isDone()) {
                        collector.incrementSuccesses(Util.getFuture(future, collector.getBatchErrors(), collector.getFailedBatches(), 0L));
                        it.remove();
                    }
                }
            }*/
                collector.incrementCount(currentBatchSize);
            } while (iterator.hasNext());

            boolean wasTerminated = Util.transactionIsTerminated(terminationGuard);
            ToLongFunction<Future<Long>> toLongFunction = wasTerminated ?
                    f -> Util.getFutureOrCancel(f, collector.getBatchErrors(), collector.getFailedBatches(), 0L) :
                    f -> Util.getFuture(f, collector.getBatchErrors(), collector.getFailedBatches(), 0L);
            collector.incrementSuccesses(futures.stream().mapToLong(toLongFunction).sum());

            Util.logErrors("Error during iterate.commit:", collector.getBatchErrors(), log);
            Util.logErrors("Error during iterate.execute:", collector.getOperationErrors(), log);
            return Stream.of(collector.getResult());
        } finally {
            if (parallel==false) { // we need to ensure destruction of the executor we've created above
                pool.shutdown();
                try {
                    pool.awaitTermination(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private long executeAndReportErrors(Transaction tx, BiConsumer<Transaction, Map<String, Object>> consumer, Map<String, Object> params,
                                        List<Map<String, Object>> batch, int returnValue, AtomicLong localCount, BatchAndTotalCollector collector) {
        try {
            consumer.accept(tx, params);
            if (localCount!=null) {
                localCount.getAndIncrement();
            }
            return returnValue;
        } catch (Exception e) {
            collector.incrementFailedOps(batch.size());
            collector.amendFailedParamsMap(batch);
            recordError(collector.getOperationErrors(), e);
            throw e;
        }
    }

    public static class BatchAndTotalCollector {
        private final int failedParams;
        private long start = System.nanoTime();
        private AtomicLong batches = new AtomicLong();
        private long successes = 0;
        private AtomicLong count = new AtomicLong();
        private AtomicLong failedOps = new AtomicLong();
        private AtomicLong retried = new AtomicLong();
        private Map<String, Long> operationErrors = new ConcurrentHashMap<>();
        private AtomicInteger failedBatches = new AtomicInteger();
        private Map<String, Long> batchErrors = new HashMap<>();
        private Map<String, List<Map<String, Object>>> failedParamsMap = new ConcurrentHashMap<>();
        private final boolean wasTerminated;

        public BatchAndTotalCollector(TerminationGuard terminationGuard, int failedParams) {
            this.failedParams = failedParams;
            wasTerminated = Util.transactionIsTerminated(terminationGuard);
        }

        public BatchAndTotalResult getResult() {
            long timeTaken = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
            return new BatchAndTotalResult(batches.get(), count.get(), timeTaken, successes, failedOps.get(), failedBatches.get(), retried.get(), operationErrors, batchErrors, wasTerminated, failedParamsMap);
        }

        public long getBatches() {
            return batches.get();
        }

        public long getCount() {
            return count.get();
        }

        public void incrementFailedOps(long size) {
            failedOps.addAndGet(size);
        }

        public void incrementBatches() {
            batches.incrementAndGet();
        }

        public void incrementSuccesses(long increment) {
            successes += increment;
        }

        public void incrementCount(long currentBatchSize) {
            count.addAndGet(currentBatchSize);
        }

        public Map<String, Long> getBatchErrors() {
            return batchErrors;
        }

        public Map<String, Long> getOperationErrors() {
            return operationErrors;
        }

        public Map<String, List<Map<String, Object>>> getFailedParamsMap() {
            return failedParamsMap;
        }

        public void amendFailedParamsMap(List<Map<String, Object>> batch) {
            if (failedParams >= 0) {
                failedParamsMap.put(
                        Long.toString(batches.get()),
                        new ArrayList<>(batch.subList(0, Math.min(failedParams + 1, batch.size())))
                );
            }
        }

        public AtomicInteger getFailedBatches() {
            return failedBatches;
        }

        public void incrementRetried() {
            retried.incrementAndGet();
        }
    }

    public static class BatchAndTotalResult {
        public final long batches;
        public final long total;
        public final long timeTaken;
        public final long committedOperations;
        public final long failedOperations;
        public final long failedBatches;
        public final long retries;
        public final Map<String,Long> errorMessages;
        public final Map<String,Object> batch;
        public final Map<String,Object> operations;
        public final boolean wasTerminated;
        public final Map<String, List<Map<String,Object>>> failedParams;

        public BatchAndTotalResult(long batches, long total, long timeTaken, long committedOperations,
                                   long failedOperations, long failedBatches, long retries,
                                   Map<String, Long> operationErrors, Map<String, Long> batchErrors, boolean wasTerminated, Map<String, List<Map<String, Object>>> failedParams) {
            this.batches = batches;
            this.total = total;
            this.timeTaken = timeTaken;
            this.committedOperations = committedOperations;
            this.failedOperations = failedOperations;
            this.failedBatches = failedBatches;
            this.retries = retries;
            this.errorMessages = operationErrors;
            this.wasTerminated = wasTerminated;
            this.failedParams = failedParams;
            this.batch = Util.map("total",batches,"failed",failedBatches,"committed",batches-failedBatches,"errors",batchErrors);
            this.operations = Util.map("total",total,"failed",failedOperations,"committed", committedOperations,"errors",operationErrors);
        }

        public LoopingBatchAndTotalResult inLoop(Object loop) {
            return new LoopingBatchAndTotalResult(loop, batches, total);
        }
    }

    public static class LoopingBatchAndTotalResult {
        public Object loop;
        public long batches;
        public long total;

        public LoopingBatchAndTotalResult(Object loop, long batches, long total) {
            this.loop = loop;
            this.batches = batches;
            this.total = total;
        }
    }

    public static class JobInfo {
        public final String name;
        public long delay;
        public long rate;
        public boolean done;
        public boolean cancelled;

        public JobInfo(String name) {
            this.name = name;
        }

        public JobInfo(String name, long delay, long rate) {
            this.name = name;
            this.delay = delay;
            this.rate = rate;
        }

        public JobInfo update(Future future) {
            this.done = future.isDone();
            this.cancelled = future.isCancelled();
            return this;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof JobInfo && name.equals(((JobInfo) o).name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

    private class Countdown implements Runnable {
        private final String name;
        private final String statement;
        private final long rate;
        private transient final Log log;

        public Countdown(String name, String statement, long rate, Log log) {
            this.name = name;
            this.statement = statement;
            this.rate = rate;
            this.log = log;
        }

        @Override
        public void run() {
            if (Periodic.this.executeNumericResultStatement(statement, Collections.emptyMap()) > 0) {
                pools.getScheduledExecutorService().schedule(() -> submit(name, this, log), rate, TimeUnit.SECONDS);
            }
        }
    }
}
