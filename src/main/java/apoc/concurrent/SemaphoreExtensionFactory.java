package apoc.concurrent;

import apoc.ApocConfig;
import org.neo4j.kernel.extension.ExtensionFactory;
import org.neo4j.kernel.extension.ExtensionType;
import org.neo4j.kernel.extension.context.ExtensionContext;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.logging.internal.LogService;
import org.neo4j.procedure.impl.GlobalProceduresRegistry;

public class SemaphoreExtensionFactory extends ExtensionFactory<apoc.PoolExtensionFactory.Dependencies> {

    public SemaphoreExtensionFactory() {
        super(ExtensionType.GLOBAL, "APOC_SEMAPHORES");
    }

    public interface Dependencies {
        GlobalProceduresRegistry globalProceduresRegistry();

        LogService log();

        ApocConfig apocConfig();
    }

    @Override
    public Lifecycle newInstance(ExtensionContext context, apoc.PoolExtensionFactory.Dependencies dependencies) {
        return new Semaphores(dependencies.log(), dependencies.globalProceduresRegistry(), dependencies.apocConfig());
    }


}
