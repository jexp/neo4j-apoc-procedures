package apoc.bolt;

import apoc.result.RowResult;
import apoc.result.VirtualNode;
import apoc.result.VirtualRelationship;
import apoc.util.UriResolver;
import apoc.util.Util;
import org.apache.commons.lang3.StringUtils;
import org.neo4j.driver.AuthToken;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.Value;
import org.neo4j.driver.internal.InternalEntity;
import org.neo4j.driver.internal.InternalPath;
import org.neo4j.driver.summary.SummaryCounters;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static apoc.util.MapUtil.map;

/**
 * @author AgileLARUS
 * @since 29.08.17
 */
public class Bolt {

    @Context
    public GraphDatabaseService db;

    @Procedure()
    @Description("apoc.bolt.load(url-or-key, kernelTransaction, params, config) - access to other databases via bolt for read")
    public Stream<RowResult> load(@Name("url") String url, @Name("kernelTransaction") String statement, @Name(value = "params", defaultValue = "{}") Map<String, Object> params, @Name(value = "config", defaultValue = "{}") Map<String, Object> config) throws URISyntaxException {
        BoltConfig boltConfig = new BoltConfig(config);
        UriResolver uri = new UriResolver(url, "bolt");
        uri.initialize();
        return withDriver(uri.getConfiguredUri(), uri.getToken(), boltConfig.getDriverConfig(), driver ->
                withSession(driver, boltConfig.getSessionConfig(), session -> {
                    if (boltConfig.isAddStatistics()) {
                        Result statementResult = session.run(statement, params);
                        SummaryCounters counters = statementResult.consume().counters();
                        return Stream.of(new RowResult(toMap(counters)));
                    } else
                        return getRowResultStream(boltConfig.isVirtual(), session, params, statement);
                }));
    }

    private <T> Stream<T> withDriver(URI uri, AuthToken token, Config config, Function<Driver, Stream<T>> function) {
        Driver driver = GraphDatabase.driver(uri, token, config);
        return function.apply(driver).onClose(() -> driver.close());
    }

    private <T> Stream<T> withSession(Driver driver, SessionConfig sessionConfig, Function<Session, Stream<T>> function) {
        Session session = driver.session(sessionConfig);
        return function.apply(session).onClose(() -> session.close());
    }

    private <T> Stream<T> withSession(Driver driver, Function<Session, Stream<T>> function) {
        Session session = driver.session();
        return function.apply(session).onClose(() -> session.close());
    }

    private <T> Stream<T> withTransaction(Session session, Function<Transaction, Stream<T>> function) {
        Transaction transaction = session.beginTransaction();
        return function.apply(transaction).onClose(() -> transaction.commit()).onClose(() -> transaction.close());
    }

    @Procedure(value = "apoc.bolt.load.fromLocal", mode = Mode.WRITE)
    public Stream<RowResult> fromLocal(@Name("url") String url,
                                       @Name("localStatement") String localStatement,
                                       @Name("remoteStatement") String remoteStatement,
                                       @Name(value = "config", defaultValue = "{}") Map<String, Object> config)  throws URISyntaxException {
        BoltConfig boltConfig = new BoltConfig(config);
        UriResolver uri = new UriResolver(url, "bolt");
        uri.initialize();
        return withDriver(uri.getConfiguredUri(), uri.getToken(), boltConfig.getDriverConfig(), driver ->
                withSession(driver, boltConfig.getSessionConfig(), session -> {
                    try (org.neo4j.graphdb.Transaction tx = db.beginTx()) {
                        final org.neo4j.graphdb.Result localResult = tx.execute(localStatement, boltConfig.getLocalParams());
                        String withColumns = "WITH " + localResult.columns().stream()
                                .map(c -> "$" + c + " AS " + c)
                                .collect(Collectors.joining(", ")) + "\n";
                        Map<Long, Object> nodesCache = new HashMap<>();
                        List<RowResult> response = new ArrayList<>();
                        while (localResult.hasNext()) {
                            final Result statementResult;
                            Map<String, Object> row = localResult.next();
                            if (boltConfig.isStreamStatements()) {
                                final String statement = (String) row.get("statement");
                                if (StringUtils.isBlank(statement)) continue;
                                final Map<String, Object> params = Collections.singletonMap("params", row.getOrDefault("params", Collections.emptyMap()));
                                statementResult = session.run(statement, params);
                            } else {
                                statementResult = session.run(withColumns + remoteStatement, row);
                            }
                            if (boltConfig.isStreamStatements()) {
                                response.add(new RowResult(toMap(statementResult.consume().counters())));
                            } else {
                                response.addAll(
                                        statementResult.stream()
                                                .map(record -> buildRowResult(record, nodesCache, boltConfig.isVirtual()))
                                                .collect(Collectors.toList())
                                );
                            }
                        }
                        return response.stream();
                    }
                }));
    }

    @Procedure()
    @Description("apoc.bolt.execute(url-or-key, kernelTransaction, params, config) - access to other databases via bolt for reads and writes")
    public Stream<RowResult> execute(@Name("url") String url, @Name("kernelTransaction") String statement, @Name(value = "params", defaultValue = "{}") Map<String, Object> params, @Name(value = "config", defaultValue = "{}") Map<String, Object> config) throws URISyntaxException {
        Map<String, Object> configuration = new HashMap<>(config);
        configuration.put("readOnly", false);
        return load(url, statement, params, configuration);
    }

    private RowResult buildRowResult(Record record, Map<Long,Object> nodesCache, boolean virtual) {
        return new RowResult(record.asMap(value -> {
            Object entity = value.asObject();
            if (entity instanceof Node) return toNode(entity, virtual, nodesCache);
            if (entity instanceof Relationship) return toRelationship(entity, virtual, nodesCache);
            if (entity instanceof Path) return toPath(entity, virtual, nodesCache);
            return entity;
        }));
    }

    private Stream<RowResult> getRowResultStream(boolean virtual, Session session, Map<String, Object> params, String statement) {
        Map<Long, Object> nodesCache = new HashMap<>();

        return withTransaction(session, tx -> {
            ClosedAwareDelegatingIterator<Record> iterator = new ClosedAwareDelegatingIterator(tx.run(statement, params));
            return Iterators.stream(iterator).map(record -> buildRowResult(record, nodesCache, virtual));
        });
    }

    private Object toNode(Object value, boolean virtual, Map<Long, Object> nodesCache) {
        Value internalValue = ((InternalEntity) value).asValue();
        Node node = internalValue.asNode();
        if (virtual) {
            List<Label> labels = new ArrayList<>();
            node.labels().forEach(l -> labels.add(Label.label(l)));
            VirtualNode virtualNode = new VirtualNode(node.id(), labels.toArray(new Label[0]), node.asMap());
            nodesCache.put(node.id(), virtualNode);
            return virtualNode;
        } else
            return Util.map("entityType", internalValue.type().name(), "labels", node.labels(), "id", node.id(), "properties", node.asMap());
    }

    private Object toRelationship(Object value, boolean virtual, Map<Long, Object> nodesCache) {
        Value internalValue = ((InternalEntity) value).asValue();
        Relationship relationship = internalValue.asRelationship();
        if (virtual) {
            VirtualNode start = (VirtualNode) nodesCache.getOrDefault(relationship.startNodeId(), new VirtualNode(relationship.startNodeId()));
            VirtualNode end = (VirtualNode) nodesCache.getOrDefault(relationship.endNodeId(), new VirtualNode(relationship.endNodeId()));
            VirtualRelationship virtualRelationship = new VirtualRelationship(relationship.id(), start, end, RelationshipType.withName(relationship.type()), relationship.asMap());
            return virtualRelationship;
        } else
            return Util.map("entityType", internalValue.type().name(), "type", relationship.type(), "id", relationship.id(), "start", relationship.startNodeId(), "end", relationship.endNodeId(), "properties", relationship.asMap());
    }

    private Object toPath(Object value, boolean virtual, Map<Long, Object> nodesCache) {
        List<Object> entityList = new LinkedList<>();
        Value internalValue = ((InternalPath) value).asValue();
        internalValue.asPath().forEach(p -> {
            entityList.add(toNode(p.start(), virtual, nodesCache));
            entityList.add(toRelationship(p.relationship(), virtual, nodesCache));
            entityList.add(toNode(p.end(), virtual, nodesCache));
        });
        return entityList;
    }

    private Map<String, Object> toMap(SummaryCounters resultSummary) {
        return map(
                "nodesCreated", resultSummary.nodesCreated(),
                "nodesDeleted", resultSummary.nodesDeleted(),
                "labelsAdded", resultSummary.labelsAdded(),
                "labelsRemoved", resultSummary.labelsRemoved(),
                "relationshipsCreated", resultSummary.relationshipsCreated(),
                "relationshipsDeleted", resultSummary.relationshipsDeleted(),
                "propertiesSet", resultSummary.propertiesSet(),
                "constraintsAdded", resultSummary.constraintsAdded(),
                "constraintsRemoved", resultSummary.constraintsRemoved(),
                "indexesAdded", resultSummary.indexesAdded(),
                "indexesRemoved", resultSummary.indexesRemoved()
        );
    }



}