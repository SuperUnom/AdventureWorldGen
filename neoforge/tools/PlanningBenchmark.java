import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.runtime.RuntimePlanner;
import io.github.luoyan.adventureworldgen.api.*;
import java.util.List;
import java.nio.file.*;

/** Fresh default-profile planning, including validation and persistence, with a fixed test structure.
 * Run with the game runtime classpath (no game bootstrap needed).
 * Args: profile JSON, new output directory, seed. Refuses cache hits so timings remain comparable. */
public final class PlanningBenchmark {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("expected profile JSON, new output directory, seed");
        Path world = Path.of(args[1]);
        if (Files.exists(world)) throw new IllegalArgumentException("output directory must be new: " + world);
        var config = new AdventureWorldConfigParser().parse(Files.readString(Path.of(args[0])));
        String canonical = CanonicalConfigJson.write(config);
        var loaded = new ProfileManager.LoadedProfile(new ContentId("adventureworldgen:default"),
                config, canonical, "benchmark", "benchmark");
        long start = System.nanoTime();
        var adapters = AdapterRegistry.builder(new GenericBiomeAdapter()).add(new StructureAdapter() {
            public ContentId structureId() { return new ContentId("minecraft:desert_pyramid"); }
            public String adapterVersion() { return "benchmark-fixed-piece-v1"; }
            public Descriptor describe() { return new Descriptor(List.of("north"), 15, true, true); }
            public Prepared prepare(Candidate c, long seed) {
                var piece = new AdventurePlanView.PlannedPiece(c.instanceId()+"/0", c.originX()-10,
                        c.originY(), c.originZ()-10, c.originX()+10, c.originY()+14, c.originZ()+10, new byte[]{1});
                var box = new HorizontalBox(piece.minX(), piece.minZ(), piece.maxX(), piece.maxZ());
                return new Prepared(c, List.of(piece), List.of(box), List.of(box), c.originX(), c.originY(), c.originZ());
            }
            public List<String> validatePrepared(Prepared p, MacroTerrain t) { return List.of(); }
            public byte[] serializePieces(Prepared p) { return new byte[]{1}; }
            public void placeChunk(Prepared p, int x, int z, PlacementTarget t) { throw new UnsupportedOperationException(); }
        }).build();
        var plan = RuntimePlanner.plan(Long.parseLong(args[2]), loaded, world, adapters);
        System.out.printf("PLANNING seconds=%.3f patches=%d structures=%d%n",
                (System.nanoTime() - start) / 1e9, plan.biomePatches().size(), plan.structures().size());
    }
}
