import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.plan.*;
import io.github.luoyan.adventureworldgen.planner.RoadPlanner;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan;
import io.github.luoyan.adventureworldgen.persistence.PlanV2Codec;
import java.nio.file.*;
import java.util.*;

/** Replays roads from GameTest's natural.json and native instances.json, never invents footprints. */
public final class RoadReplayBenchmark {
    record Instance(PlannedStructurePlacement placement,StructurePlanningInfo info) {}
    public static void main(String[] args)throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("profile.json natural.json instances.json output-directory");
        Path out=Path.of(args[3]);Files.createDirectories(out);
        var config=new AdventureWorldConfigParser().parse(Files.readString(Path.of(args[0])));
        byte[] bytes=Files.readAllBytes(Path.of(args[1]));
        var root=com.google.gson.JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        var plan=GeneratedAdventurePlan.restore(config,new PlanV2Codec().decode(bytes,new ContentId(root.get("profile").getAsString()),root.get("input_sha256").getAsString()));
        var instances=List.of(new com.google.gson.Gson().fromJson(Files.readString(Path.of(args[2])),Instance[].class));
        var facts=new TreeMap<String,StructurePlanningInfo>();instances.forEach(i->facts.put(i.placement.instanceId(),i.info));
        var catalog=StructurePlanningCatalog.fromIds(instances.stream().map(i->i.placement.structureId()).distinct().toList()).withInstances(facts);
        long start=System.nanoTime();
        try(var execution=PlanningExecution.defaults(PlannerProfile.V2.maximumWorkingMemoryBytes())) {
            var planner=new RoadPlanner(plan.seed(),config,plan::terrainAt,request->{
                        if(request.kind()==RoadWorkControl.Kind.SEARCH_EXPANSIONS)System.out.println("ROAD_BATCH "+request.task()+" "+request.used());
                        RoadWorkControl.AUTOMATIC.pause(request);
                    },execution);
            RoadPlan roads;
            try {roads=planner.plan(plan.spawnPosition(),plan.biomePatches(),instances.stream().map(Instance::placement).toList(),catalog,(x,z)->plan.biomeAt(x,64,z));}
            finally {
                var gson=new com.google.gson.GsonBuilder().setPrettyPrinting().create();
                Files.writeString(out.resolve("work.json"),gson.toJson(planner.workReport()));
                Files.writeString(out.resolve("attempts.json"),gson.toJson(planner.attemptReport()));
            }
            Files.writeString(out.resolve("roads.json"),new com.google.gson.Gson().toJson(roads));
            System.out.printf("ROAD_REPLAY seconds=%.3f workers=%d nodes=%d routes=%d columns=%d supports=%d operations=%d%n",
                    (System.nanoTime()-start)/1e9,execution.workers(),roads.nodes().size(),roads.routes().size(),roads.columns().size(),roads.supports().size(),roads.operations());
        }
    }
}
