package apoc.refactor.rename;

import apoc.ApocConfig;
import apoc.Pools;
import apoc.concurrent.Semaphores;
import apoc.periodic.Periodic;
import apoc.periodic.Periodic.BatchAndTotalResult;
import apoc.util.MapUtil;
import apoc.util.Util;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.schema.ConstraintDefinition;
import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.TerminationGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author AgileLARUS
 *
 * @since 03-04-2017
 */
public class Rename {

	@Context
	public GraphDatabaseService db;

	@Context
	public Log log;

    @Context
	public TerminationGuard terminationGuard;

    @Context
	public Transaction transaction;

    @Context
	public Pools pools;

    @Context
	public Transaction tx;

    @Context
	public ApocConfig apocConfig;

    @Context
	public Semaphores semaphores;

    /**
	 * Rename the Label of a node by creating a new one and deleting the old.
	 */
	@Procedure(mode = Mode.WRITE)
	@Description("apoc.refactor.rename.label(oldLabel, newLabel, [nodes]) | rename a label from 'oldLabel' to 'newLabel' for all nodes. If 'nodes' is provided renaming is applied to this set only")
	public Stream<BatchAndTotalResultWithInfo> label(@Name("oldLabel") String oldLabel, @Name("newLabel") String newLabel, @Name(value = "nodes", defaultValue = "[]") List<Node> nodes) {
		nodes = nodes.stream().map(n -> Util.rebind(tx, n)).collect(Collectors.toList());
		String cypherIterate = nodes != null && !nodes.isEmpty() ? "UNWIND $nodes AS n WITH n WHERE n:`"+oldLabel+"` RETURN n" : "MATCH (n:`"+oldLabel+"`) RETURN n";
        String cypherAction = "SET n:`"+newLabel+"` REMOVE n:`"+oldLabel+"`";
        Map<String, Object> parameters = MapUtil.map("batchSize", 100000, "parallel", true, "iterateList", true, "params", MapUtil.map("nodes", nodes));
		return getResultOfBatchAndTotalWithInfo( newPeriodic().iterate(cypherIterate, cypherAction, parameters), db, oldLabel, null, null);
	}

    /**
	 * Rename the Relationship Type by creating a new one and deleting the old.
	 */
	@Procedure(mode = Mode.WRITE)
	@Description("apoc.refactor.rename.type(oldType, newType, [rels]) | rename all relationships with type 'oldType' to 'newType'. If 'rels' is provided renaming is applied to this set only")
	public Stream<BatchAndTotalResultWithInfo> type(@Name("oldType") String oldType, @Name("newType") String newType, @Name(value = "rels", defaultValue = "[]") List<Relationship> rels) {
		rels = rels.stream().map(r -> Util.rebind(tx, r)).collect(Collectors.toList());
		List<Long> relIds = rels.stream().map(r -> r.getId()).collect(Collectors.toList());
		String cypherIterate = rels != null && ! rels.isEmpty() ?
				"UNWIND $rels AS oldRel WITH oldRel WHERE type(oldRel)=\""+oldType+"\" RETURN oldRel,startNode(oldRel) as a,endNode(oldRel) as b" :
				"MATCH (a)-[oldRel:`"+oldType+"`]->(b) RETURN oldRel,a,b";
		String cypherAction = "CREATE(a)-[newRel:`"+newType+"`]->(b)"+ "SET newRel+=oldRel DELETE oldRel";
		Map<String, Object> parameters = MapUtil.map("batchSize", 100000, "parallel", true, "iterateList", true, "params", MapUtil.map("rels", rels));
		return getResultOfBatchAndTotalWithInfo(newPeriodic().iterate(cypherIterate, cypherAction, parameters), db, null, oldType, null);
	}

	/**
	 * Rename property of a node by creating a new one and deleting the old.
	 */
	@Procedure(mode = Mode.WRITE)
	@Description("apoc.refactor.rename.nodeProperty(oldName, newName, [nodes]) | rename all node's property from 'oldName' to 'newName'. If 'nodes' is provided renaming is applied to this set only")
	public Stream<BatchAndTotalResultWithInfo> nodeProperty(@Name("oldName") String oldName, @Name("newName") String newName, @Name(value="nodes", defaultValue = "[]") List<Node> nodes) {
		nodes = nodes.stream().map(n -> Util.rebind(tx, n)).collect(Collectors.toList());;
		String cypherIterate = nodes != null && ! nodes.isEmpty() ? "UNWIND $nodes AS n WITH n WHERE exists (n."+oldName+") return n" : "match (n) where exists (n."+oldName+") return n";
		String cypherAction = "set n."+newName+"= n."+oldName+" remove n."+oldName;
		Map<String, Object> parameters = MapUtil.map("batchSize", 100000, "parallel", true, "iterateList", true, "params", MapUtil.map("nodes", nodes));
		return getResultOfBatchAndTotalWithInfo(newPeriodic().iterate(cypherIterate, cypherAction, parameters), db, null, null, oldName);
	}

	/**
	 * Rename property of a relationship by creating a new one and deleting the old.
	 */
	@Procedure(mode = Mode.WRITE)
	@Description("apoc.refactor.rename.typeProperty(oldName, newName, [rels]) | rename all relationship's property from 'oldName' to 'newName'. If 'rels' is provided renaming is applied to this set only")
	public Stream<BatchAndTotalResultWithInfo> typeProperty(@Name("oldName") String oldName, @Name("newName") String newName, @Name(value="rels", defaultValue = "[]") List<Relationship> rels) {
		rels = rels.stream().map(r -> Util.rebind(tx, r)).collect(Collectors.toList());
		String cypherIterate = rels != null && ! rels.isEmpty() ? "UNWIND $rels AS r WITH r WHERE exists (r."+oldName+") return r" : "match ()-[r]->() where exists (r."+oldName+") return r";
		String cypherAction = "set r."+newName+"= r."+oldName+" remove r."+oldName;
		Map<String, Object> parameters = MapUtil.map("batchSize", 100000, "parallel", true, "iterateList", true, "params", MapUtil.map("rels", rels));
		return getResultOfBatchAndTotalWithInfo(newPeriodic().iterate(cypherIterate, cypherAction, parameters), db, null, null, oldName);
	}

    /*
     * create a properly initialized Periodic instance by setting all the required @Context attributes
     */
    private Periodic newPeriodic() {
        Periodic periodic = new Periodic();
        periodic.db = this.db;
        periodic.log = this.log;
        periodic.terminationGuard = this.terminationGuard;
        periodic.pools = this.pools;
        periodic.tx = this.tx;
        periodic.apocConfig = this.apocConfig;
        periodic.semaphores = this.semaphores;
        return periodic;
    }

	/*
	 * Create the response for rename apoc with impacted constraints and indexes
	 */
	private Stream<BatchAndTotalResultWithInfo> getResultOfBatchAndTotalWithInfo(Stream<BatchAndTotalResult> iterate, GraphDatabaseService db, String label, String rel, String prop) {
		List<String> constraints = new ArrayList<>();
		List<String> indexes = new ArrayList<>();

		if(label != null){
			Iterable<ConstraintDefinition> constraintsForLabel = transaction.schema().getConstraints(Label.label(label));
			constraintsForLabel.forEach((c) -> {
				constraints.add(c.toString());
			});
			Iterable<IndexDefinition> idxs = transaction.schema().getIndexes(Label.label(label));
			idxs.forEach((i) -> {
				indexes.add(i.toString());
			});
		}
		if(rel != null){
			Iterable<ConstraintDefinition> constraintsForRel = transaction.schema().getConstraints(RelationshipType.withName(rel));
			constraintsForRel.forEach((c) -> {
				constraints.add(c.toString());
			});
		}
		if (prop != null) {
			Iterable<ConstraintDefinition> constraintsForProps = transaction.schema().getConstraints();
			constraintsForProps.forEach((c)-> {
				c.getPropertyKeys().forEach((p) -> {
					if (p.equals(prop)){
						constraints.add(c.toString());
					}
				});
			});
            Iterable<IndexDefinition> idxs = transaction.schema().getIndexes();
            idxs.forEach((i) -> {
                i.getPropertyKeys().forEach((p) -> {
                    if(p.equals(prop)){
                        indexes.add(i.toString());
                    }
                });
            });
		}
        Optional<BatchAndTotalResult> targetLongList = Optional.of(iterate.findFirst().orElse(null));
        BatchAndTotalResultWithInfo result = new BatchAndTotalResultWithInfo(targetLongList,constraints,indexes);
        return Stream.of(result);
    }

    public class  BatchAndTotalResultWithInfo  {
        public long batches;
        public long total;
        public long timeTaken;
        public long committedOperations;
        public long failedOperations;
        public long failedBatches;
        public long retries;
        public Map<String,Long> errorMessages;
        public Map<String,Object> batch;
        public Map<String,Object> operations;
        public List<String> constraints;
        public List<String> indexes;

        public BatchAndTotalResultWithInfo(Optional<BatchAndTotalResult> batchAndTotalResult, List<String> constraints, List<String> indexes) {
            batchAndTotalResult.ifPresent(a -> {
                this.batches = a.batches;
                this.total = a.total;
                this.timeTaken = a.timeTaken;
                this.committedOperations = a.committedOperations;
                this.failedOperations = a.failedOperations;
                this.failedBatches = a.failedBatches;
                this.retries = a.retries;
                this.errorMessages = (Map<String, Long>) a.operations.get("errors");
                this.batch = (Map<String, Object>) a.operations.get("errors");
                this.operations = Util.map("total", total, "failed", failedOperations, "committed", committedOperations, "errors", a.operations.get("errors"));
            });
            this.constraints = constraints;
            this.indexes = indexes;
        }
    }
}
