package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.compat.vanilla.VanillaWaterBiomes;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.BiomeLayout;
import io.github.luoyan.adventureworldgen.plan.PlannedBiomePatch;
import io.github.luoyan.adventureworldgen.plan.PlannedStructurePlacement;
import io.github.luoyan.adventureworldgen.plan.PlanningObserver;
import io.github.luoyan.adventureworldgen.noise.DeterministicRandom;
import io.github.luoyan.adventureworldgen.plan.PlanDiagnostics;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import io.github.luoyan.adventureworldgen.persistence.PlanSnapshot;
import io.github.luoyan.adventureworldgen.spatial.ColumnQueryCache;
import io.github.luoyan.adventureworldgen.hydrology.HydrologyTerrain;
import io.github.luoyan.adventureworldgen.hydrology.RiverNetwork;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import io.github.luoyan.adventureworldgen.terrain.IslandMacroTerrain;
import io.github.luoyan.adventureworldgen.terrain.RegionTerrain;
import io.github.luoyan.adventureworldgen.erosion.ErosionDeltaField;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import io.github.luoyan.adventureworldgen.biome.BiomeEnvironmentRules;
import io.github.luoyan.adventureworldgen.climate.ClimatePlan;
import io.github.luoyan.adventureworldgen.plan.PlanVersions;
import io.github.luoyan.adventureworldgen.plan.FailureStage;

/** Immutable executable snapshot produced by the first end-to-end planner pipeline. */
public final class GeneratedAdventurePlan implements AdventurePlanView {
    private static final ContentId OCEAN = new ContentId("minecraft:ocean");
    private final long seed;
    private final io.github.luoyan.adventureworldgen.plan.RoadPlan roads;
    private final RoadIndex roadIndex;
    @Override public io.github.luoyan.adventureworldgen.plan.RoadPlan roads() { return roads; }
    @Override public io.github.luoyan.adventureworldgen.plan.RoadPlan.Column roadAt(int x,int z) { return roadIndex.at(x,z); }
    @Override public List<io.github.luoyan.adventureworldgen.plan.RoadPlan.Column> roadsInChunk(int x,int z) { return roadIndex.chunk(x,z); }
    public io.github.luoyan.adventureworldgen.config.RoadSettings roadSettings() { return config.roads(); }
    private final io.github.luoyan.adventureworldgen.climate.ClimatePlan climate;
    private final io.github.luoyan.adventureworldgen.biome.BiomeEnvironmentRules environmentRules;
    private final io.github.luoyan.adventureworldgen.planner.FillerLayout filler;
    private final java.util.Set<String> blendProtectedPatches=new java.util.HashSet<>();
    private final AdventureWorldConfig config;
    private final MacroTerrain terrain;
    private final PlanTerrain terrainStack;
    private final HydrologyTerrain waterTerrain;
    private final RegionTerrain regions;
    private final MacroTerrain islandTerrain;
    private final SpawnPosition spawn;
    private final Coastline coastline;
    private final RiverNetwork riverNetwork;
    private final double seaSurface;
    private final double landBand;
    private final double seaBand;
    private final String terrainVersion;
    private final List<PlannedBiomePatch> biomePatches;
    private final List<PlannedStructurePlacement> structures;
    /**
     * The planning parameters this instance was built with. Every sub-stage that needs a budget or a
     * version string reads it from here, so the plan genuinely carries the profile that produced it.
     */
    private final PlannerProfile profile;
    private final PlanDiagnostics diagnostics;
    private final ErosionDeltaField erosion;
    private final io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan capacities;
    private final io.github.luoyan.adventureworldgen.biome.LocalBiomeBlend blockBlend;
    private final ColumnQueryCache<MacroSample> columnSamples = new ColumnQueryCache<>(16384);
    private final ColumnQueryCache<ContentId> columnBiomes;

    public GeneratedAdventurePlan(long seed, AdventureWorldConfig config, Coastline coastline,
                                  RiverNetwork riverNetwork, double seaSurface, double landBand,
                                  double seaBand, String terrainVersion, SpawnPosition frozenSpawn,
                                  List<PlannedBiomePatch> biomePatches, List<PlannedStructurePlacement> structures,
                                  PlanDiagnostics diagnostics, ErosionDeltaField erosion) {
        this(PlannerProfile.V2, seed,config,coastline,riverNetwork,seaSurface,landBand,seaBand,terrainVersion,frozenSpawn,
                biomePatches,structures,diagnostics,erosion,
                io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan.empty(), null, null, PlanningObserver.NONE, null);
    }
    public GeneratedAdventurePlan(long seed, AdventureWorldConfig config, Coastline coastline,
                                  RiverNetwork riverNetwork, double seaSurface, double landBand,
                                  double seaBand, String terrainVersion, SpawnPosition frozenSpawn,
                                  List<PlannedBiomePatch> biomePatches, List<PlannedStructurePlacement> structures,
                                  PlanDiagnostics diagnostics, ErosionDeltaField erosion,
                                  io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan capacities) {
        this(PlannerProfile.V2, seed,config,coastline,riverNetwork,seaSurface,landBand,seaBand,terrainVersion,frozenSpawn,
                biomePatches,structures,diagnostics,erosion,capacities,null,null,PlanningObserver.NONE,null);
    }
    public BiomeLayout biomeLayout(){return new BiomeLayout(climate.snapshot(),filler.snapshot(),blendProtectedPatches.stream().sorted().toList());}
    /** Frozen planning objects can be reused on first publication; decoded plans rebuild them. */
    record PlanningInputs(PlanTerrain terrain,io.github.luoyan.adventureworldgen.climate.ClimatePlan climate,
                          io.github.luoyan.adventureworldgen.plan.StructurePlanningCatalog structurePlanning) {
        PlanningInputs(PlanTerrain terrain,io.github.luoyan.adventureworldgen.climate.ClimatePlan climate) {
            this(terrain,climate,io.github.luoyan.adventureworldgen.plan.StructurePlanningCatalog.fromIds(List.of()));
        }
    }
    public GeneratedAdventurePlan(long seed, AdventureWorldConfig config, Coastline coastline,
                                  RiverNetwork riverNetwork, double seaSurface, double landBand,
                                  double seaBand, String terrainVersion, SpawnPosition frozenSpawn,
                                  List<PlannedBiomePatch> biomePatches, List<PlannedStructurePlacement> structures,
                                  PlanDiagnostics diagnostics, ErosionDeltaField erosion,
                                  io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan capacities,
                                  BiomeLayout frozenLayout) {
        this(PlannerProfile.V2, seed,config,coastline,riverNetwork,seaSurface,landBand,seaBand,terrainVersion,frozenSpawn,
                biomePatches,structures,diagnostics,erosion,capacities,frozenLayout,null,PlanningObserver.NONE,null);
    }
    /**
     * First planning entry: this run just computed the layout, so its objects are reused and nothing
     * is frozen. {@code prepared} is required — a first planning run always has the objects it built.
     * Use {@link #restore(AdventureWorldConfig, PlanSnapshot)} to rebuild a plan from a decoded
     * payload instead; that path takes the frozen layout and rebuilds the rest.
     */
    static GeneratedAdventurePlan fromPlanning(PlannerProfile profile, long seed, AdventureWorldConfig config,
                                              Coastline coastline,
                                              RiverNetwork riverNetwork, double seaSurface, double landBand,
                                              double seaBand, String terrainVersion, SpawnPosition spawn,
                                              List<PlannedBiomePatch> biomePatches, List<PlannedStructurePlacement> structures,
                                              PlanDiagnostics diagnostics, ErosionDeltaField erosion,
                                              io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan capacities,
                                              PlanningInputs prepared, PlanningObserver observer) {
        return fromPlanning(profile, seed, config, coastline, riverNetwork, seaSurface, landBand, seaBand, terrainVersion,
                spawn, biomePatches, structures, diagnostics, erosion, capacities, prepared, observer, null);
    }
    static GeneratedAdventurePlan fromPlanning(PlannerProfile profile, long seed, AdventureWorldConfig config,
            Coastline coastline, RiverNetwork riverNetwork, double seaSurface, double landBand, double seaBand,
            String terrainVersion, SpawnPosition spawn, List<PlannedBiomePatch> biomePatches,
            List<PlannedStructurePlacement> structures, PlanDiagnostics diagnostics, ErosionDeltaField erosion,
            io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan capacities, PlanningInputs prepared,
            PlanningObserver observer, io.github.luoyan.adventureworldgen.plan.RoadPlan frozenRoads) {
        return new GeneratedAdventurePlan(profile, seed, config, coastline, riverNetwork, seaSurface, landBand,
                seaBand, terrainVersion, spawn, biomePatches, structures, diagnostics, erosion, capacities, null,
                java.util.Objects.requireNonNull(prepared, "prepared"), observer, frozenRoads);
    }

    /**
     * The one constructor that assembles a plan. {@code frozenLayout} is the decoded layout of a
     * READY payload and {@code prepared} the objects of a first planning run; both are null only for
     * a plan built from explicit geometry (tests and read-only tools). Private so callers cannot mix
     * a frozen layout with first-planning objects: use {@link #fromPlanning} or {@link #restore}.
     */
    private GeneratedAdventurePlan(PlannerProfile profile, long seed, AdventureWorldConfig config,
                                  Coastline coastline,
                                  RiverNetwork riverNetwork, double seaSurface, double landBand,
                                  double seaBand, String terrainVersion, SpawnPosition frozenSpawn,
                                  List<PlannedBiomePatch> biomePatches, List<PlannedStructurePlacement> structures,
                                  PlanDiagnostics diagnostics, ErosionDeltaField erosion,
                                  io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan capacities,
                                  BiomeLayout frozenLayout, PlanningInputs prepared, PlanningObserver observer,
                                  io.github.luoyan.adventureworldgen.plan.RoadPlan frozenRoads) {
        java.util.Objects.requireNonNull(observer,"observer");
        this.profile=java.util.Objects.requireNonNull(profile,"profile");
        this.capacities=capacities;
        this.seed = seed;
        this.blockBlend=new io.github.luoyan.adventureworldgen.biome.LocalBiomeBlend(seed,config.biomes().blendRadius());
        this.config = config;
        this.coastline = coastline;
        this.riverNetwork = riverNetwork;
        this.seaSurface = seaSurface;
        this.landBand = landBand;
        this.seaBand = seaBand;
        this.terrainVersion = terrainVersion;
        this.biomePatches = List.copyOf(biomePatches);
        this.structures = List.copyOf(structures);
        this.diagnostics = diagnostics;
        this.erosion = erosion;
        // Terrain assembly lives in PlanTerrain: the first planning run hands over the objects it
        // already built, a decoded plan rebuilds the same stack from frozen data.
        terrainStack = prepared == null
                ? PlanTerrain.assemble(profile, seed, config, capacities, coastline, riverNetwork, seaSurface,
                        landBand, seaBand, terrainVersion, erosion)
                : prepared.terrain();
        this.regions = terrainStack.regions();
        this.islandTerrain = terrainStack.island();
        this.waterTerrain = terrainStack.water();
        this.terrain = terrainStack.terrain();
        if(frozenLayout!=null && (frozenLayout.climate()==null||frozenLayout.filler()==null||frozenLayout.protectedPatches()==null))
            throw new IllegalArgumentException("incomplete frozen biome layout");
        // Layout assembly lives in PlanAssembly; the plan keeps the steps that need its own queries.
        PlanAssembly.Layout layout = PlanAssembly.layout(profile, seed, config, terrain, this.biomePatches,
                frozenLayout, prepared == null ? null : prepared.climate(), observer);
        climate = layout.climate();
        environmentRules = layout.rules();
        filler = layout.filler();
        this.spawn = PlanAssembly.spawn(terrain, frozenSpawn);
        if(frozenLayout==null)protectMinimumAreas();
        else {
            var ids=this.biomePatches.stream().map(PlannedBiomePatch::patchId).collect(java.util.stream.Collectors.toSet());
            if(!ids.containsAll(frozenLayout.protectedPatches()))throw new IllegalArgumentException("unknown blend protection patch");
            blendProtectedPatches.addAll(frozenLayout.protectedPatches());
        }
        // Minimum-area protection can change biome selection during construction.
        // Publish the cache only after that layout is final, and keep it local to this plan.
        columnBiomes = new ColumnQueryCache<>(16384);
        if (frozenRoads == null && config.roads().enabled()) observer.stage(io.github.luoyan.adventureworldgen.plan.PlanningStage.ROADS);
        roads = frozenRoads != null ? frozenRoads : new io.github.luoyan.adventureworldgen.planner.RoadPlanner(
                seed, config, terrain).plan(spawn, this.biomePatches, this.structures,
                prepared == null ? io.github.luoyan.adventureworldgen.plan.StructurePlanningCatalog.fromIds(
                        this.structures.stream().map(PlannedStructurePlacement::structureId).toList()) : prepared.structurePlanning(),
                (x,z) -> biomeAt(x,64,z));
        roadIndex = new RoadIndex(roads);
    }

    GeneratedAdventurePlan completeStructures(StructurePreparation.Result prepared, PlanningObserver observer) {
        return new GeneratedAdventurePlan(profile, seed, config, coastline, riverNetwork, seaSurface, landBand,
                seaBand, terrainVersion, spawn, biomePatches, prepared.placements(), diagnostics, erosion,
                capacities, biomeLayout(), new PlanningInputs(terrainStack, climate, prepared.catalog()), observer, null);
    }

    public GeneratedAdventurePlan(long seed, AdventureWorldConfig config, Coastline coastline,
                                  RiverNetwork riverNetwork, double seaSurface, double landBand,
                                  double seaBand, String terrainVersion, SpawnPosition frozenSpawn) {
        this(seed, config, coastline, riverNetwork, seaSurface, landBand, seaBand, terrainVersion,
                frozenSpawn, List.of(), List.of(), coastAndRiverDiagnostics(coastline, riverNetwork), null);
    }

    /** Counts available before the expensive stages: coast vertices, channels and their points. */
    private static PlanDiagnostics coastAndRiverDiagnostics(Coastline coast, RiverNetwork rivers) {
        return PlanDiagnostics.basic(coast.vertices().size(), rivers.channels().size(),
                rivers.channels().stream().mapToLong(channel -> channel.points().size()).sum(),
                PlanVersions.TERRAIN + "+" + rivers.version());
    }

    /**
     * The frozen data of this plan: everything a plan-v3 document stores, as data.
     *
     * <p>The first planning run publishes exactly this, and a READY reload rebuilds a query object
     * from exactly this, so the two paths cannot drift apart.
     */
    public PlanSnapshot snapshot() {
        return new PlanSnapshot(seed, diagnostics, spawn, coastline, riverNetwork, seaSurface, landBand, seaBand,
                terrainVersion, config.world().terrain(), recipeRegions(), biomePatches, structures, erosion,
                capacities, biomeLayout(), roads);
    }

    /**
     * The READY reload entry point: rebuild a query object from frozen data.
     *
     * <p>Only the recipe sections are cross-checked against the live profile, because they are the
     * inputs terrain is regenerated from. Everything else comes from the snapshot, so a reload
     * never re-runs coast, erosion, hydrology, capacity, biome or structure solving.
     */
    public static GeneratedAdventurePlan restore(AdventureWorldConfig config, PlanSnapshot snapshot) {
        try {
            // A decoded payload has already been checked against the current plan contract by PlanV2Codec
            // (algorithm/format/hydrology all equal the contract values), so the restoring profile IS
            // the contract profile: V2 is not a shortcut here, it is what the format pins.
            GeneratedAdventurePlan plan = new GeneratedAdventurePlan(PlannerProfile.V2, snapshot.seed(), config,
                    snapshot.coastline(),
                    snapshot.riverNetwork(), snapshot.seaSurface(), snapshot.landBand(), snapshot.seaBand(),
                    snapshot.terrainVersion(), snapshot.spawn(), snapshot.biomePatches(), snapshot.structures(),
                    snapshot.diagnostics(), snapshot.erosion(), snapshot.capacities(), snapshot.biomeLayout(),
                    null, PlanningObserver.NONE, snapshot.roads());
            if (!snapshot.recipeSettings().equals(config.world().terrain()))
                throw new IllegalArgumentException("frozen recipe settings do not match the active profile");
            // Recipe assignments are explicit plan data. Reject drift rather than silently regenerate them.
            if (!plan.recipeRegions().equals(snapshot.recipeRegions()))
                throw new IllegalArgumentException("recipe region manifest does not match frozen terrain inputs");
            return plan;
        } catch (PlanningFailure failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw new PlanningFailure(PlanningFailure.Code.EXECUTION_FAILED, FailureStage.PLAN_LOAD,
                    "READY plan-v3 payload is invalid", java.util.Map.of("reason", String.valueOf(malformed.getMessage())));
        }
    }

    @Override public ContentId biomeAt(int blockX, int blockY, int blockZ) {
        // One unwarped quart-cell center for both runtime surfaces and the stored biome palette.
        // Rounding the warped coordinates instead creates uneven cells and comb-like aliases.
        blockX = Math.floorDiv(blockX, 4) * 4 + 2;
        blockZ = Math.floorDiv(blockZ, 4) * 4 + 2;
        if (columnBiomes != null) return columnBiomes.get(blockX, blockZ, this::uncachedBiomeAt);
        return uncachedBiomeAt(blockX, blockZ);
    }

    private ContentId uncachedBiomeAt(int blockX, int blockZ) {
        MacroSample sample = terrain.sample(blockX, blockZ);
        if (sample.waterKind() == WaterKind.OCEAN) return OCEAN;
        ContentId land = mixedLandBiomeAt(blockX, blockZ, sample);
        if (sample.waterKind() == WaterKind.RIVER || sample.waterKind() == WaterKind.LAKE)
            return VanillaWaterBiomes.inlandWaterFor(land);
        return land;
    }

    /** Surface generation uses exactly the returned Minecraft biome; no independent material blending. */
    public ContentId surfaceBiomeAt(int x,int z) {
        return biomeAt(x,64,z);
    }

    private ContentId mixedLandBiomeAt(int x,int z,MacroSample sample) {
        ContentId fallback=landBiomeAt(x,z,sample);
        for(var patch:biomePatches)if(patch.contains(x,z)) {
            if(blendProtectedPatches.contains(patch.patchId()) || Math.hypot(x-spawnPositionX(),z-spawnPositionZ())<=32)
                return fallback;
        }
        return blockBlend.sample(x,z,(nx,nz)-> {
            int qx=Math.floorDiv(nx,4)*4+2,qz=Math.floorDiv(nz,4)*4+2;
            var nearby=terrain.sample(qx,qz);
            return nearby.waterKind()==WaterKind.NONE?landBiomeAt(qx,qz,nearby):fallback;
        },id->config.biomes().allows(id,sample)&&environmentRules.allows(id,x,z,sample),fallback);
    }

    private double spawnPositionX(){return spawn==null?0:spawn.x();}
    private double spawnPositionZ(){return spawn==null?0:spawn.z();}

    /** Raw carrier query used by the independent boundary mixer. Water never counts as dry quota. */
    public ContentId landBiomeAt(int blockX, int blockZ) {
        blockX = Math.floorDiv(blockX, 4) * 4 + 2;
        blockZ = Math.floorDiv(blockZ, 4) * 4 + 2;
        return landBiomeAt(blockX, blockZ, null);
    }

    private ContentId landBiomeAt(int blockX, int blockZ, MacroSample sample) {
        for (PlannedBiomePatch patch : biomePatches)
            if (patch.contains(blockX, blockZ)) return patch.biomeId();
        return fillerAt(blockX, blockZ, sample == null ? terrain.sample(blockX, blockZ) : sample);
    }

    @Override public MacroSample terrainAt(double blockX, double blockZ) {
        int x = (int) StrictMath.floor(blockX), z = (int) StrictMath.floor(blockZ);
        // Cache exact block centers only. Arbitrary planning/debug coordinates keep their precision.
        if (blockX == x + 0.5 && blockZ == z + 0.5)
            return columnSamples.get(x, z, (cx, cz) -> terrain.sample(cx + 0.5, cz + 0.5));
        return terrain.sample(blockX, blockZ);
    }
    public int solidSurfaceAt(int x, int z, MacroSample sample) { return waterTerrain.solidSurfaceAt(x, z, sample); }
    @Override public List<PlannedStructurePlacement> plannedStructures() { return structures; }
    @Override public SpawnPosition spawnPosition() { return spawn; }

    public long seed() { return seed; }
    public double oceanCarvingAt(double x, double z) {
        return StrictMath.max(0, islandTerrain.sample(x, z).groundSurface() - terrain.sample(x, z).groundSurface());
    }
    public Coastline coastline() { return coastline; }
    public RiverNetwork riverNetwork() { return riverNetwork; }
    public double seaSurface() { return seaSurface; }
    public double landBand() { return landBand; }
    public double seaBand() { return seaBand; }
    public io.github.luoyan.adventureworldgen.terrain.TerrainSettings terrainSettings() {return config.world().terrain();}
    public java.util.List<RegionTerrain.Region> recipeRegions() {
        // The spacing comes from the RegionTerrain this plan was actually built with, not from a
        // fixed V2 lookup: a differently spaced profile must describe its own regions.
        int extent=(int)Math.ceil(config.world().radius()/regions.regionSpacing())+3;
        var list=new java.util.ArrayList<RegionTerrain.Region>();
        for(int x=-extent;x<=extent;x++)for(int z=-extent;z<=extent;z++)list.add(regions.region(x,z));
        return java.util.List.copyOf(list);
    }
    public String terrainVersion() { return terrainVersion; }
    public List<PlannedBiomePatch> biomePatches() { return biomePatches; }
    public List<PlannedStructurePlacement> structures() { return structures; }
    public PlanDiagnostics diagnostics() { return diagnostics; }
    public ErosionDeltaField erosion() { return erosion; }
    public io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan capacities() { return capacities; }
    public io.github.luoyan.adventureworldgen.climate.ClimatePlan climate(){return climate;}
    public int fillerSeedCount(){return filler.seedCount();}
    public long effectiveArea(PlannedBiomePatch patch) {
        long cells=0;
        if(patch.mask()!=null) {
            // Disconnected regions may span the continent; visit ownership, not empty bounds.
            for(long cell:patch.mask().cells()) {
                int x=io.github.luoyan.adventureworldgen.spatial.CellMask.x(cell)+2;
                int z=io.github.luoyan.adventureworldgen.spatial.CellMask.z(cell)+2;
                if(terrain.sample(x,z).waterKind()==WaterKind.NONE&&biomeAt(x,64,z).equals(patch.biomeId()))cells++;
            }
            return cells*16;
        }
        for(int z=patch.minZ()+2;z<patch.maxZExclusive();z+=4)for(int x=patch.minX()+2;x<patch.maxXExclusive();x+=4)
            if(patch.contains(x,z)&&terrain.sample(x,z).waterKind()==WaterKind.NONE&&biomeAt(x,64,z).equals(patch.biomeId()))cells++;
        return cells*16;
    }
    private void protectMinimumAreas() {
        // The decision itself lives in the planner; the measurement is this plan's own ownership.
        blendProtectedPatches.addAll(io.github.luoyan.adventureworldgen.planner.MinimumAreaPolicy
                .protectedPatchIds(config, biomePatches, this::effectiveArea));
    }

    private ContentId fillerAt(int x,int z,MacroSample sample) { return filler.biomeAt(x,z,sample); }
}
