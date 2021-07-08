package apoc.monitor;

import apoc.Extended;
import apoc.result.TransactionInfoResult;
import org.neo4j.kernel.impl.transaction.stats.DatabaseTransactionStats;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Procedure;
import org.neo4j.storageengine.api.TransactionIdStore;

import java.util.stream.Stream;

@Extended
public class Transaction {

    @Context
    public GraphDatabaseAPI db;

    @Procedure
    @Description("apoc.monitor.tx() returns informations about the neo4j transaction manager")
    public Stream<TransactionInfoResult> tx() throws Exception {
        DatabaseTransactionStats stats = db.getDependencyResolver().resolveDependency(DatabaseTransactionStats.class);
        TransactionIdStore transactionIdStore = db.getDependencyResolver().resolveDependency(TransactionIdStore.class);
        return Stream.of(new TransactionInfoResult(
                stats.getNumberOfRolledBackTransactions(),
                stats.getPeakConcurrentNumberOfTransactions(),
                transactionIdStore.getLastCommittedTransactionId(),
                stats.getNumberOfActiveTransactions(),
                stats.getNumberOfStartedTransactions(),
                stats.getNumberOfCommittedTransactions()
        ));
    }

}
