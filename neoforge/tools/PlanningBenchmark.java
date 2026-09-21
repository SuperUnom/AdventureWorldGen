import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.api.AdapterRegistry;
import io.github.luoyan.adventureworldgen.runtime.RuntimePlanner;
import io.github.luoyan.adventureworldgen.worldgen.GenericBiomeAdapter;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import java.nio.file.*;

/** Fresh default-profile planning, including validation and persistence.
 * Uses the structure planning declarations on the tool classpath (no active datapack overrides).
 * Run with the game runtime classpath (no game bootstrap needed).
 * Args: profile JSON, new output directory, seed. Refuses cache hits so timings remain comparable. */
public final class PlanningBenchmark {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("expected profile JSON, new output directory, seed");
        Path world = Path.of(args[1]);
        if (Files.exists(world)) throw new IllegalArgumentException("output directory must be new: " + world);
        var config = new AdventureWorldConfigParser().parse(Files.readString(Path.of(args[0])));
        String canonical = CanonicalConfigJson.write(config);
        var loaded = new LoadedProfile(new ContentId("adventureworldgen:default"),
                config, canonical, "benchmark", io.github.luoyan.adventureworldgen.worldgen.StructureRoadInformation.load(config, id -> {
                    String[] parts=id.value().split(":");
                    var input=PlanningBenchmark.class.getResourceAsStream("/data/"+parts[0]+"/adventureworldgen/structure_planning/"+parts[1]+".json");
                    return input==null?null:new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8);
                }));
        long start = System.nanoTime();
        var adapters = AdapterRegistry.builder(new GenericBiomeAdapter()).build();
        var plan = RuntimePlanner.plan(Long.parseLong(args[2]), loaded, world, adapters);
        System.out.printf("PLANNING seconds=%.3f patches=%d structures=%d%n",
                (System.nanoTime() - start) / 1e9, plan.biomePatches().size(), plan.structures().size());
    }
}
