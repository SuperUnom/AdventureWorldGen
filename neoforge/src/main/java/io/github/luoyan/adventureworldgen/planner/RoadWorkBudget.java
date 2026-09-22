package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.plan.PlanningPolicy;
import io.github.luoyan.adventureworldgen.plan.RoadWorkControl;
import java.util.*;

/** Scheduling batches never replenish the deterministic, per-destination work ledger. */
final class RoadWorkBudget {
    private final long batchSize;
    private final RoadWorkControl control;
    private final PlanningPolicy.RoadPolicy policy;
    private final Map<String,Ledger> tasks=new HashMap<>();
    private String task="endpoints";
    private long batchUsed;
    private Ledger ledger;
    private static final class Ledger {
        long samples,visits; double length;
        int candidates,validations,coarse,repair,repairs;
    }
    static final class Limit extends RuntimeException {
        Limit(String reason) {super("RESOURCE_LIMIT_"+reason,null,false,false);}
    }
    RoadWorkBudget(long batchSize,RoadWorkControl control) {this(batchSize,control,PlanningPolicy.CURRENT.roads());}
    RoadWorkBudget(long batchSize,RoadWorkControl control,PlanningPolicy.RoadPolicy policy) {
        this.batchSize=batchSize;this.control=Objects.requireNonNull(control);this.policy=policy;task("endpoints");
    }
    PlanningPolicy.RoadPolicy policy(){return policy;}
    void task(String id) {task=id;batchUsed=0;ledger=tasks.computeIfAbsent(id,ignored->new Ledger());}
    void task(String id,boolean optional) {task(id);}
    void operation() {
        control.checkCancelled();
        if(ledger.samples>=policy.samples())throw new Limit("TERRAIN_SAMPLES");
        if(batchUsed>=batchSize) {
            control.pause(new RoadWorkControl.Pause(task,RoadWorkControl.Kind.OPERATIONS,batchUsed,batchSize));
            batchUsed=0;
        }
        ledger.samples++;batchUsed++;
    }
    void visit() {
        control.checkCancelled();
        if(ledger.visits>=policy.visits())throw new Limit("COLUMN_OR_GEOMETRY_VISITS");
        ledger.visits++;
    }
    boolean candidate(double length) {
        if(!Double.isFinite(length)||length>policy.routeLength()||ledger.length+length>policy.candidateLength()
                ||ledger.candidates>=policy.candidates())return false;
        ledger.candidates++;ledger.length+=length;return true;
    }
    boolean validation() {if(ledger.validations>=policy.validations())return false;ledger.validations++;return true;}
    boolean repair() {if(ledger.repairs>=policy.repairs())return false;ledger.repairs++;return true;}
    boolean expansion(boolean local) {
        control.checkCancelled();
        if(local){if(ledger.repair>=policy.repairExpansions())return false;ledger.repair++;}
        else {if(ledger.coarse>=policy.coarseExpansions())return false;ledger.coarse++;}
        return true;
    }
    Map<String,Map<String,Long>> report() {
        var report=new TreeMap<String,Map<String,Long>>();
        tasks.forEach((id,l)->report.put(id,Map.of("samples",l.samples,"visits",l.visits,"candidates",(long)l.candidates,
                "validations",(long)l.validations,"coarse_expansions",(long)l.coarse,"repair_expansions",(long)l.repair,"repairs",(long)l.repairs)));
        return Collections.unmodifiableMap(report);
    }
    int candidates(){return ledger.candidates;}
    int validations(){return ledger.validations;}
    long samples(){return ledger.samples;}
    long visits(){return ledger.visits;}
    void capacity(RoadWorkControl.Kind kind,long used,long limit) {
        control.pause(new RoadWorkControl.Pause(task,kind,used,limit));
        throw new IllegalStateException("cannot resume road planning beyond a hard capacity: "+kind);
    }
    Scope phase(String suffix) {var scope=new Scope(task,batchUsed);task+="/"+suffix;batchUsed=0;return scope;}
    final class Scope implements AutoCloseable {
        private final String savedTask;private final long savedBatch;
        Scope(String task,long batch){savedTask=task;savedBatch=batch;}
        public void close(){task=savedTask;batchUsed=savedBatch;}
    }
}
