package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.config.ContentId;
import io.github.luoyan.adventureworldgen.planner.DeterministicRandom;
import io.github.luoyan.adventureworldgen.planner.PlannerProfile;
import io.github.luoyan.adventureworldgen.hydrology.HydrologyTerrain;
import io.github.luoyan.adventureworldgen.hydrology.RiverNetwork;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import io.github.luoyan.adventureworldgen.terrain.IslandMacroTerrain;
import io.github.luoyan.adventureworldgen.terrain.RegionTerrain;
import io.github.luoyan.adventureworldgen.erosion.ErodedTerrain;
import io.github.luoyan.adventureworldgen.erosion.ErosionDeltaField;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;

/** Immutable executable snapshot produced by the first end-to-end planner pipeline. */
public final class GeneratedAdventurePlan implements AdventurePlanView {
    private static final ContentId OCEAN = new ContentId("minecraft:ocean");
    private final long seed;
    private final io.github.luoyan.adventureworldgen.planner.ClimatePlan climate;
    private final io.github.luoyan.adventureworldgen.planner.FillerLayout filler;
    private final java.util.Set<String> blendProtectedPatches=new java.util.HashSet<>();
    private final AdventureWorldConfig config;
    private final MacroTerrain terrain;
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
    private final List<PlannedStructure> structures;
    private final PlanDiagnostics diagnostics;
    private final ErosionDeltaField erosion;
    private final io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan capacities;
    private final io.github.luoyan.adventureworldgen.terrain.LocalBiomeBlend blockBlend;
    private final ColumnQueryCache<MacroSample> columnSamples = new ColumnQueryCache<>(16384);
    private final ColumnQueryCache<ContentId> columnBiomes;

    public GeneratedAdventurePlan(long seed, AdventureWorldConfig config, Coastline coastline,
                                  RiverNetwork riverNetwork, double seaSurface, double landBand,
                                  double seaBand, String terrainVersion, SpawnPosition frozenSpawn,
                                  List<PlannedBiomePatch> biomePatches, List<PlannedStructure> structures,
                                  PlanDiagnostics diagnostics, ErosionDeltaField erosion) {
        this(seed,config,coastline,riverNetwork,seaSurface,landBand,seaBand,terrainVersion,frozenSpawn,
                biomePatches,structures,diagnostics,erosion,io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan.empty());
    }
    public GeneratedAdventurePlan(long seed, AdventureWorldConfig config, Coastline coastline,
                                  RiverNetwork riverNetwork, double seaSurface, double landBand,
                                  double seaBand, String terrainVersion, SpawnPosition frozenSpawn,
                                  List<PlannedBiomePatch> biomePatches, List<PlannedStructure> structures,
                                  PlanDiagnostics diagnostics, ErosionDeltaField erosion,
                                  io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan capacities) {
        this(seed,config,coastline,riverNetwork,seaSurface,landBand,seaBand,terrainVersion,frozenSpawn,
                biomePatches,structures,diagnostics,erosion,capacities,null);
    }
    public record BiomeLayout(io.github.luoyan.adventureworldgen.planner.ClimatePlan.State climate,
                              io.github.luoyan.adventureworldgen.planner.FillerLayout.State filler,
                              List<String> protectedPatches) {}
    public BiomeLayout biomeLayout(){return new BiomeLayout(climate.snapshot(),filler.snapshot(),blendProtectedPatches.stream().sorted().toList());}
    /** Frozen planning objects can be reused on first publication; decoded plans rebuild them. */
    record PlanningInputs(RegionTerrain regions,MacroTerrain island,HydrologyTerrain water,
                          MacroTerrain terrain,io.github.luoyan.adventureworldgen.planner.ClimatePlan climate) {}
    public GeneratedAdventurePlan(long seed, AdventureWorldConfig config, Coastline coastline,
                                  RiverNetwork riverNetwork, double seaSurface, double landBand,
                                  double seaBand, String terrainVersion, SpawnPosition frozenSpawn,
                                  List<PlannedBiomePatch> biomePatches, List<PlannedStructure> structures,
                                  PlanDiagnostics diagnostics, ErosionDeltaField erosion,
                                  io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan capacities,
                                  BiomeLayout frozenLayout) {
        this(seed,config,coastline,riverNetwork,seaSurface,landBand,seaBand,terrainVersion,frozenSpawn,
                biomePatches,structures,diagnostics,erosion,capacities,frozenLayout,null);
    }
    GeneratedAdventurePlan(long seed, AdventureWorldConfig config, Coastline coastline,
                                  RiverNetwork riverNetwork, double seaSurface, double landBand,
                                  double seaBand, String terrainVersion, SpawnPosition frozenSpawn,
                                  List<PlannedBiomePatch> biomePatches, List<PlannedStructure> structures,
                                  PlanDiagnostics diagnostics, ErosionDeltaField erosion,
                                  io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan capacities,
                                  BiomeLayout frozenLayout, PlanningInputs prepared) {
        this.capacities=capacities;
        this.seed = seed;
        this.blockBlend=new io.github.luoyan.adventureworldgen.terrain.LocalBiomeBlend(seed,config.biomes().blendRadius());
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
        if(prepared==null) {
            this.regions=new RegionTerrain(seed, PlannerProfile.V2,capacities,config.world().terrain(),config);
            MacroTerrain island = new IslandMacroTerrain(coastline, regions, seed,
                    seaSurface, landBand, seaBand, terrainVersion);
            this.islandTerrain = island;
            MacroTerrain eroded = erosion == null ? island : new ErodedTerrain(island, erosion, "erosion-v2");
            this.waterTerrain = new HydrologyTerrain(eroded, riverNetwork);
            this.terrain = new io.github.luoyan.adventureworldgen.terrain.TerrainMorphology(waterTerrain);
        } else {
            this.regions=prepared.regions();this.islandTerrain=prepared.island();
            this.waterTerrain=prepared.water();this.terrain=prepared.terrain();
        }
        if(frozenLayout!=null && (frozenLayout.climate()==null||frozenLayout.filler()==null||frozenLayout.protectedPatches()==null))
            throw new IllegalArgumentException("incomplete frozen biome layout");
        climate=prepared!=null?prepared.climate():new io.github.luoyan.adventureworldgen.planner.ClimatePlan(seed,config,terrain,ignored->{},frozenLayout==null?null:frozenLayout.climate());
        if(frozenLayout==null)PlanningProgress.stageCurrent(PlanningProgress.Stage.FILLER);
        filler=new io.github.luoyan.adventureworldgen.planner.FillerLayout(seed,config,terrain,this.biomePatches,climate,frozenLayout==null?null:frozenLayout.filler());
        if(frozenLayout==null)PlanningProgress.stageCurrent(PlanningProgress.Stage.TRANSITION);
        double y = terrain.sample(0, 0).groundSurface() + 1.0;
        this.spawn = frozenSpawn == null ? new SpawnPosition(0.5, StrictMath.ceil(y), 0.5, 0) : frozenSpawn;
        if(frozenLayout==null)protectMinimumAreas();
        else {
            var ids=this.biomePatches.stream().map(PlannedBiomePatch::patchId).collect(java.util.stream.Collectors.toSet());
            if(!ids.containsAll(frozenLayout.protectedPatches()))throw new IllegalArgumentException("unknown blend protection patch");
            blendProtectedPatches.addAll(frozenLayout.protectedPatches());
        }
        // Minimum-area protection can change biome selection during construction.
        // Publish the cache only after that layout is final, and keep it local to this plan.
        columnBiomes = new ColumnQueryCache<>(16384);
    }

    public GeneratedAdventurePlan(long seed, AdventureWorldConfig config, Coastline coastline,
                                  RiverNetwork riverNetwork, double seaSurface, double landBand,
                                  double seaBand, String terrainVersion, SpawnPosition frozenSpawn) {
        this(seed, config, coastline, riverNetwork, seaSurface, landBand, seaBand, terrainVersion,
                frozenSpawn, List.of(), List.of(), PlanDiagnostics.basic(coastline, riverNetwork), null);
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
            return inlandWaterBiome(land);
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
        for(var structure:structures)for(var box:structure.biomeProtection())
            if(x>=box.minX()&&x<=box.maxX()&&z>=box.minZ()&&z<=box.maxZ())return fallback;
        return blockBlend.sample(x,z,(nx,nz)-> {
            int qx=Math.floorDiv(nx,4)*4+2,qz=Math.floorDiv(nz,4)*4+2;
            var nearby=terrain.sample(qx,qz);
            return nearby.waterKind()==WaterKind.NONE?landBiomeAt(qx,qz,nearby):fallback;
        },id->config.biomes().allows(id,sample)&&climate.allowsEnvironment(id,x,z,sample),fallback);
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

    public static ContentId inlandWaterBiome(ContentId land) {
        boolean frozen = switch (land.value()) {
            case "minecraft:snowy_plains", "minecraft:snowy_taiga", "minecraft:ice_spikes",
                 "minecraft:grove", "minecraft:snowy_slopes", "minecraft:jagged_peaks",
                 "minecraft:frozen_peaks", "minecraft:snowy_beach" -> true;
            default -> false;
        };
        return new ContentId(frozen ? "minecraft:frozen_river" : "minecraft:river");
    }

    @Override public MacroSample terrainAt(double blockX, double blockZ) {
        int x = (int) StrictMath.floor(blockX), z = (int) StrictMath.floor(blockZ);
        // Cache exact block centers only. Arbitrary planning/debug coordinates keep their precision.
        if (blockX == x + 0.5 && blockZ == z + 0.5)
            return columnSamples.get(x, z, (cx, cz) -> terrain.sample(cx + 0.5, cz + 0.5));
        return terrain.sample(blockX, blockZ);
    }
    public int solidSurfaceAt(int x, int z, MacroSample sample) { return waterTerrain.solidSurfaceAt(x, z, sample); }
    @Override public List<PlannedStructure> structuresIntersecting(int chunkX, int chunkZ) {
        int minX = chunkX << 4, minZ = chunkZ << 4, maxX = minX + 15, maxZ = minZ + 15;
        List<PlannedStructure> result = new ArrayList<>();
        for (PlannedStructure structure : structures) {
            boolean intersects = structure.pieces().stream().anyMatch(piece -> piece.minX() <= maxX && piece.maxX() >= minX
                    && piece.minZ() <= maxZ && piece.maxZ() >= minZ);
            if (intersects) result.add(structure);
        }
        result.sort(Comparator.comparing(PlannedStructure::instanceId));
        return List.copyOf(result);
    }
    @Override public SpawnPosition spawnPosition() { return spawn; }
    @Override public List<ContentId> controlledStructureIds() {
        return config.structures().stream().map(AdventureWorldConfig.StructureSettings::id).distinct().sorted().toList();
    }

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
        int extent=(int)Math.ceil(config.world().radius()/PlannerProfile.V2.terrain().regionSpacing())+3;
        var list=new java.util.ArrayList<RegionTerrain.Region>();
        for(int x=-extent;x<=extent;x++)for(int z=-extent;z<=extent;z++)list.add(regions.region(x,z));
        return java.util.List.copyOf(list);
    }
    public String terrainVersion() { return terrainVersion; }
    public List<PlannedBiomePatch> biomePatches() { return biomePatches; }
    public List<PlannedStructure> structures() { return structures; }
    public PlanDiagnostics diagnostics() { return diagnostics; }
    public ErosionDeltaField erosion() { return erosion; }
    public io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan capacities() { return capacities; }
    public io.github.luoyan.adventureworldgen.planner.ClimatePlan climate(){return climate;}
    public int fillerSeedCount(){return filler.seedCount();}
    public long effectiveArea(PlannedBiomePatch patch) {
        long cells=0;
        if(patch.mask()!=null) {
            // Disconnected regions may span the continent; visit ownership, not empty bounds.
            for(long cell:patch.mask().cells()) {
                int x=io.github.luoyan.adventureworldgen.planner.CellMask.x(cell)+2;
                int z=io.github.luoyan.adventureworldgen.planner.CellMask.z(cell)+2;
                if(terrain.sample(x,z).waterKind()==WaterKind.NONE&&biomeAt(x,64,z).equals(patch.biomeId()))cells++;
            }
            return cells*16;
        }
        for(int z=patch.minZ()+2;z<patch.maxZExclusive();z+=4)for(int x=patch.minX()+2;x<patch.maxXExclusive();x+=4)
            if(patch.contains(x,z)&&terrain.sample(x,z).waterKind()==WaterKind.NONE&&biomeAt(x,64,z).equals(patch.biomeId()))cells++;
        return cells*16;
    }
    private void protectMinimumAreas() {
        // Protect the achieved quota when legal supply cannot reach the configured minimum.
        var demands=new io.github.luoyan.adventureworldgen.planner.RequirementExpander().expandMinimum(config);
        for(var d:demands.patches())for(var p:biomePatches)if(p.patchId().equals(d.patchId())&&effectiveArea(p)<Math.min(d.area().min(),p.area()))
            blendProtectedPatches.add(p.patchId());
    }

    private ContentId fillerAt(int x,int z,MacroSample sample) { return filler.biomeAt(x,z,sample); }

    public record PlannedBiomePatch(String patchId, ContentId biomeId, int adventureLevel,
                                    int minX, int minZ, int maxXExclusive, int maxZExclusive,
                                    io.github.luoyan.adventureworldgen.planner.CellMask mask, int anchorX, int anchorZ) {
        public PlannedBiomePatch(String id, ContentId biome, int level, int minX, int minZ, int maxX, int maxZ) {
            this(id, biome, level, minX, minZ, maxX, maxZ, null, (minX + maxX) / 2, (minZ + maxZ) / 2);
        }
        public PlannedBiomePatch {
            if (minX >= maxXExclusive || minZ >= maxZExclusive) throw new IllegalArgumentException("empty biome patch");
            if (mask != null && !mask.contains(anchorX,anchorZ)) throw new IllegalArgumentException("anchor outside ownership mask");
            if (mask != null) for (long cell : mask.cells()) {
                int x = io.github.luoyan.adventureworldgen.planner.CellMask.x(cell);
                int z = io.github.luoyan.adventureworldgen.planner.CellMask.z(cell);
                if (x < minX || z < minZ || x >= maxXExclusive || z >= maxZExclusive)
                    throw new IllegalArgumentException("ownership outside patch bounds");
            }
        }
        public boolean contains(int x, int z) {
            if (x < minX || z < minZ || x >= maxXExclusive || z >= maxZExclusive) return false;
            if (mask != null) return mask.contains(x,z);
            x = Math.floorDiv(x, 4) * 4 + 2;
            z = Math.floorDiv(z, 4) * 4 + 2;
            double hx = (maxXExclusive - minX) * 0.5, hz = (maxZExclusive - minZ) * 0.5;
            double dx = (x + 0.5 - (minX + hx)) / hx, dz = (z + 0.5 - (minZ + hz)) / hz;
            double angle = StrictMath.atan2(dz, dx);
            double phase = (patchId.hashCode() & 0xffff) * (StrictMath.PI * 2.0 / 65536.0);
            // An irregular closed contour, bounded by the persisted box. The planner counts
            // its actual 4-block cells and validates structure protection against this same mask.
            double radius = 0.74 + 0.06 * StrictMath.sin(3 * angle + phase)
                    + 0.035 * StrictMath.sin(7 * angle - phase) + 0.025 * StrictMath.sin(13 * angle + phase);
            return StrictMath.hypot(dx,dz)<radius;
        }
        public long area() {
            if (mask != null) return mask.size() * 16L;
            long cells = 0;
            for (int x = minX; x < maxXExclusive; x += 4)
                for (int z = minZ; z < maxZExclusive; z += 4)
                    if (contains(x + 2, z + 2)) cells++;
            return cells * 16;
        }
    }

    public record PlanDiagnostics(long coastVertices, long riverChannels, long riverPoints,
                                  long erosionSamples, long erosionOperations,
                                  long costNodes, long costEdges, long jointOperations,
                                  String terrainVersion) {
        public static PlanDiagnostics basic(Coastline coast, RiverNetwork rivers) {
            return new PlanDiagnostics(coast.vertices().size(), rivers.channels().size(),
                    rivers.channels().stream().mapToLong(channel -> channel.points().size()).sum(),
                    0, 0, 0, 0, 0, "terrain-r22+" + rivers.version());
        }
    }
}
