package apoc.concurrent;

import apoc.result.MapResult;
import org.neo4j.graphdb.Transaction;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.TerminationGuard;
import org.neo4j.procedure.UserFunction;

import java.util.Map;
import java.util.stream.Stream;

public class SemaphoreProcedures {

    @Context
    public Transaction tx;

    @Context
    public TerminationGuard terminationGuard;

    @Context
    public Semaphores semaphores;

    @Procedure(mode = Mode.WRITE)
    @Description("apoc.concurrent.runInSemaphore('mySemaphoreName', '<cypher>', {somekey: 'someValue', ...}) - runs the given cypher statement inside a semaphore to control concurrent access")
    public Stream<MapResult> runInSemaphore(
            @Name("semaphoreName") String semaphoreName,
            @Name("cypher") String cypher,
            @Name(value="params", defaultValue="{}") Map<String, Object> params) {
        return semaphores.withSemaphore(terminationGuard, semaphoreName, () -> tx.execute(cypher, params).stream().map(MapResult::new));
    }

    @Procedure(mode = Mode.WRITE)
    @Description("apoc.concurrent.removeAllSemaphores() - removes all registered semaphores")
    public void removeAllSemaphores() {
        semaphores.removeAll();
    }

    @Procedure(mode = Mode.WRITE)
    @Description("apoc.concurrent.addSemaphores('myName', 1) - creates a semaphore with given number of permits")
    public void addSemaphore(@Name("semaphoreName") String semaphoreName, @Name("permits") long permits) {
        semaphores.add(semaphoreName, (int) permits);
    }

    @Procedure(mode = Mode.WRITE)
    @Description("apoc.concurrent.removeSemaphore() - removes given semaphore")
    public void removeSemaphore(@Name("semaphoreName") String semaphoreName) {
        semaphores.remove(semaphoreName);
    }

    @UserFunction()
    @Description("apoc.concurrent.availablePermits(semaphoreName) - function returning the available number of permits for given semaphore")
    public long availablePermits(@Name("semaphoreName") String semaphoreName) {
        return semaphores.availablePermits(semaphoreName);
    }
}
