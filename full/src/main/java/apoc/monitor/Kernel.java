package apoc.monitor;

import apoc.Extended;
import apoc.result.KernelInfoResult;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.kernel.database.Database;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.kernel.internal.Version;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Procedure;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.Date;
import java.util.stream.Stream;

@Extended
public class Kernel {

    private static final String JMX_OBJECT_NAME = "Kernel";
    private static final String READ_ONLY = "ReadOnly";
    private static final String KERNEL_VERSION = "KernelVersion";
    private static final String STORE_ID = "StoreId";
    private static final String START_TIME = "KernelStartTime";
    private static final String DB_NAME = "DatabaseName";
    private static final String STORE_LOG_VERSION = "StoreLogVersion";
    private static final String STORE_CREATION_DATE = "StoreCreationDate";


    @Context
    public GraphDatabaseService graphDatabaseService;

    @Procedure
    @Description("apoc.monitor.kernel() returns informations about the neo4j kernel")
    public Stream<KernelInfoResult> kernel() {
        GraphDatabaseAPI api = ((GraphDatabaseAPI) graphDatabaseService);
        Database database = api.getDependencyResolver().resolveDependency(Database.class);

        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        Date startDate = new Date(runtimeBean.getStartTime());

        return Stream.of(new KernelInfoResult(
                database.isReadOnly(),
                Version.getKernelVersion(),
                database.getStoreId().toString(),
                startDate,
                graphDatabaseService.databaseName(),
                database.getStoreId().getStoreVersion(),
                new Date(database.getStoreId().getCreationTime())
        ));
    }

}
