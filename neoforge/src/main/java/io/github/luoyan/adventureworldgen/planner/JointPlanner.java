package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.PlannedBiomePatch;
import io.github.luoyan.adventureworldgen.plan.PlannedStructurePlacement;
import io.github.luoyan.adventureworldgen.plan.StructurePlanningCatalog;
import io.github.luoyan.adventureworldgen.plan.PlanningObserver;
import io.github.luoyan.adventureworldgen.plan.PlanningStage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.luoyan.adventureworldgen.spatial.CellMask;
import io.github.luoyan.adventureworldgen.noise.DeterministicRandom;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import io.github.luoyan.adventureworldgen.plan.StableIds;
import io.github.luoyan.adventureworldgen.biome.BiomeEnvironmentRules;
import io.github.luoyan.adventureworldgen.climate.ClimatePlan;
import io.github.luoyan.adventureworldgen.plan.FailureStage;

/** Stable bounded placement of the minimum legal patch/structure solution before optional optimization. */
public final class JointPlanner {
    private final PlannerProfile profile;
    private PlacementIndex placementIndex;
    private ClimatePlan climate;
    private BiomeEnvironmentRules rules;

    public JointPlanner(PlannerProfile profile) { this.profile = profile; }

    /** Frozen climate from the latest plan, reused immediately by runtime publication. */
    public ClimatePlan climate() { return java.util.Objects.requireNonNull(climate,"plan has not built climate"); }

    public Result plan(long seed, AdventureWorldConfig config, MacroTerrain terrain, StructurePlanningCatalog structures) {
        return plan(seed, config, terrain, structures, (level, x, z) -> true, (biome, x, z) -> true);
    }

    public Result plan(long seed, AdventureWorldConfig config, MacroTerrain terrain, StructurePlanningCatalog structures,
                       LevelConstraint levelConstraint) {
        return plan(seed, config, terrain, structures, levelConstraint, (biome, x, z) -> true);
    }

    public Result plan(long seed, AdventureWorldConfig config, MacroTerrain terrain, StructurePlanningCatalog structures,
                       LevelConstraint levelConstraint, BiomeConstraint adapterConstraint) {
        return plan(seed,config,terrain,structures,levelConstraint,adapterConstraint,ignored -> {});
    }

    public Result plan(long seed, AdventureWorldConfig config, MacroTerrain terrain, StructurePlanningCatalog structures,
                       LevelConstraint levelConstraint, BiomeConstraint adapterConstraint, java.util.function.DoubleConsumer progress) {
        return plan(seed,config,terrain,structures,levelConstraint,adapterConstraint,progress,ignored -> {});
    }

    public Result plan(long seed, AdventureWorldConfig config, MacroTerrain terrain, StructurePlanningCatalog structures,
                       LevelConstraint levelConstraint, BiomeConstraint adapterConstraint, java.util.function.DoubleConsumer progress,
                       java.util.function.Consumer<String> checkpoint) {
        return plan(seed,config,terrain,structures,levelConstraint,adapterConstraint,PlanningObserver.NONE,progress,checkpoint);
    }

    public Result plan(long seed, AdventureWorldConfig config, MacroTerrain terrain, StructurePlanningCatalog planningCatalog,
                       LevelConstraint levelConstraint, BiomeConstraint adapterConstraint, PlanningObserver observer,
                       java.util.function.DoubleConsumer progress, java.util.function.Consumer<String> checkpoint) {
        var demands = new RequirementExpander().expandMinimum(config);
        for (var settings : config.structures()) requirePlanningInfo(planningCatalog, settings.id());
        List<PlannedBiomePatch> patches = new ArrayList<>();
        List<PlannedStructurePlacement> structures = new ArrayList<>();
        long operations = 0;
        long indexStart = System.nanoTime();
        placementIndex = new PlacementIndex(config, terrain, levelConstraint, adapterConstraint, profile,
                value -> progress.accept(value * 0.2));
        checkpoint.accept("index");
        observer.stage(PlanningStage.TEMPERATURE);
        climate=new ClimatePlan(seed,config,placementIndex::sampleAt,observer.within(PlanningStage.TEMPERATURE),observer,null,
                new ClimateDiagnostics(config,ClimatePlan.STEP));
        rules=new BiomeEnvironmentRules(config,climate);
        checkpoint.accept("climate");
        observer.stage(PlanningStage.SEEDS);
        BiomeConstraint biomeConstraint = (biome,x,z)->placementIndex.allows(biome,x,z)&&rules.allows(biome,x,z,placementIndex.sample(x,z));
        System.getLogger(JointPlanner.class.getName()).log(System.Logger.Level.INFO,
                "Placement index built in {0} ms", (System.nanoTime() - indexStart) / 1_000_000);

        progress.accept(0.4);
        long assignmentStart = System.nanoTime();
        var assigned = new BiomeAllocationPlanner(profile).allocate(seed,config,placementIndex,demands.patches(),List.of(),rules,observer,value -> {
            var stage=value<.18?PlanningStage.SEEDS:PlanningStage.GROWTH;
            observer.within(stage).accept(value<.18?value/.18:(value-.18)/.82);
        });
        patches.addAll(assigned.patches());
        operations += assigned.operations();
        progress.accept(0.95);
        System.getLogger(JointPlanner.class.getName()).log(System.Logger.Level.INFO,
                "Joint area assignment finished in {0} ms; {1} exact terrain samples, {2} operations",
                (System.nanoTime()-assignmentStart)/1_000_000,placementIndex.queries(),assigned.operations());

        checkpoint.accept("biomes");
        observer.stage(PlanningStage.STRUCTURES);
        // The entire required biome layout is frozen before required structure anchor selection.
        var structureOrder=new ArrayList<>(demands.structures());
        structureOrder.sort(java.util.Comparator.comparingInt(RequirementExpander.StructureDemand::adventureLevel)
                .thenComparing(RequirementExpander.StructureDemand::instanceId));
        for(var demand:structureOrder) {
            var carrier=patches.stream().filter(p->p.patchId().equals(StableIds.carrierPatch(demand.instanceId()))).findFirst().orElseThrow();
            placeRequiredStructure(seed,terrain,planningCatalog,levelConstraint,carrier,structures,demand);
            operations++;
            observer.within(PlanningStage.STRUCTURES)
                    .accept(structures.size()/(double)structureOrder.size());
        }
        checkpoint.accept("structures");

        // Optional instances are a soft optimization: each attempt commits atomically or leaves the legal minimum unchanged.
        long optionalAttempts = 0;
        for (var settings : config.structures()) {
            long minimum = settings.effectiveMinimum();
            for (long sequence = minimum; sequence < settings.count().max()
                    && optionalAttempts < profile.search().optionalCandidatePreparations(); sequence++, optionalAttempts++) {
                var demand = new RequirementExpander.StructureDemand(
                        StableIds.structureInstance(settings.id(), sequence), settings.id(), Math.toIntExact(sequence),
                        settings.adventureLevel(), settings.allowedBiomes().ids(), settings.allowedBiomes().area(),
                        settings.spacing(), false);
                if (!placeStructure(seed, config, terrain, planningCatalog, levelConstraint, biomeConstraint, patches, structures,
                        demand, 100_000 + Math.toIntExact(sequence), false)) break;
                operations++;
            }
        }

        AdventurePlanView.SpawnPosition spawn = resolveSpawn(terrain);
        validate(config, demands, patches, structures, spawn, terrain, levelConstraint, biomeConstraint);
        checkpoint.accept("area_assignment");
        return new Result(patches, structures, spawn, operations);
    }

    private void placeRequiredStructure(long seed, MacroTerrain terrain, StructurePlanningCatalog planningCatalog,
            LevelConstraint levels, PlannedBiomePatch carrier, List<PlannedStructurePlacement> structures,
            RequirementExpander.StructureDemand demand) {
        requirePlanningInfo(planningCatalog, demand);
        long salt=DeterministicRandom.seed(seed,profile.algorithmVersion(),"structure-in-biome",demand.instanceId(),0);
        long visited=0, budget=Math.min(4096,profile.search().requiredCandidatePreparations());
        var attempted=new HashSet<Long>();
        for(int step:new int[]{16,8,4}) {
            var candidates=new ArrayList<PlacementIndex.Point>();
            for(long cell:carrier.mask().cells()) {
                int x=CellMask.x(cell),z=CellMask.z(cell);
                if(Math.floorMod(x,step)!=0||Math.floorMod(z,step)!=0||attempted.contains(cell))continue;
                if(!levels.accepts(demand.adventureLevel(),x,z))continue;
                candidates.add(new PlacementIndex.Point(x,z));
            }
            candidates.sort(java.util.Comparator.comparingDouble((PlacementIndex.Point p)->
                    levels.penalty(demand.adventureLevel(),p.x(),p.z())*4
                    +rules.cost(carrier.biomeId(),p.x()+2,p.z()+2,placementIndex.sample(p.x(),p.z()))*3
                    +Math.hypot(p.x()-carrier.anchorX(),p.z()-carrier.anchorZ())/Math.max(32,Math.sqrt(carrier.area()))
                    +(DeterministicRandom.mix(salt^p.cell())>>>11)*0x1.0p-53*.35).thenComparingLong(PlacementIndex.Point::cell));
            for(var point:candidates) {
                attempted.add(point.cell());int x=point.x(),z=point.z();
                if(!spacingAllows(new Center(x,z),demand.structureId(),demand.spacing(),structures))continue;
                if(++visited>budget)break;
                var sample=terrain.sample(x+.5,z+.5);
                if(sample.wet()||sample.hazardous()||!carrier.contains(x,z))continue;
                structures.add(new PlannedStructurePlacement(demand.instanceId(),demand.structureId(),x,z));
                return;
            }
            if(visited>budget)break;
        }
        throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN, FailureStage.STRUCTURE_IN_BIOME,
                "no legal structure anchor in its planned required biome",
                Map.of("instance_id",demand.instanceId(),"biome",carrier.biomeId(),"patch_id",carrier.patchId(),"area",carrier.area(),"visited",visited,"budget",budget));
    }

    private boolean placeStructure(long seed, AdventureWorldConfig config, MacroTerrain terrain,
                                   StructurePlanningCatalog planningCatalog, LevelConstraint levels,
                                   BiomeConstraint biomes,
                                   List<PlannedBiomePatch> patches,
                                   List<PlannedStructurePlacement> structures,
                                   RequirementExpander.StructureDemand demand, int proposalSequence,
                                   boolean required) {
        requirePlanningInfo(planningCatalog, demand);
        ContentId biome = demand.allowedBiomes().isEmpty() ? config.biomes().filler().getFirst()
                : demand.allowedBiomes().getFirst();
        for (int fallback = 0; fallback < profile.search().spawnCandidateAttempts(); fallback++) {
            Center center;
            try {
                center = findCenter(seed, demand.adventureLevel(),
                        proposalSequence + fallback * 1_000_000, levels, (x, z) -> {
                            if (!biomes.accepts(biome, x, z)) return false;
                            var possible = rectangle(StableIds.carrierPatch(demand.instanceId()), biome,
                                    demand.adventureLevel(), new Center(x, z), demand.carrierArea());
                            return patches.stream().noneMatch(existing -> !existing.biomeId().equals(biome) && overlaps(existing, possible))
                                    && patchCompatible(possible, biomes);
                        });
            } catch (PlanningFailure failure) {
                if (required) throw failure;
                return false;
            }
            if (!spacingAllows(center, demand.structureId(), demand.spacing(), structures)) continue;
            if (!biomes.accepts(biome, center.x, center.z)) continue;
            var patch = rectangle(StableIds.carrierPatch(demand.instanceId()), biome, demand.adventureLevel(), center,
                    demand.carrierArea());
            if (patches.stream().anyMatch(existing -> overlaps(existing, patch)
                    && !existing.biomeId().equals(patch.biomeId()))) continue;
            var sample=terrain.sample(center.x+.5,center.z+.5);
            if(sample.wet()||sample.hazardous()||!levels.accepts(demand.adventureLevel(),center.x,center.z))continue;
            patches.add(patch);
            structures.add(new PlannedStructurePlacement(demand.instanceId(),demand.structureId(),center.x,center.z));
            return true;
        }
        if (required) throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN, FailureStage.JOINT_PLACEMENT,
                "required structure exhausted bounded spatial fallbacks: " + demand.instanceId(), Map.of("instance_id", demand.instanceId()));
        return false;
    }

    private static boolean patchCompatible(PlannedBiomePatch patch, BiomeConstraint biomes) {
        for (int z = patch.minZ() + 2; z < patch.maxZExclusive(); z += 4)
            for (int x = patch.minX() + 2; x < patch.maxXExclusive(); x += 4)
                if (patch.contains(x, z) && !biomes.accepts(patch.biomeId(), x, z)) return false;
        return true;
    }

    private Center findCenter(long seed, int level, int sequence, LevelConstraint levelConstraint,
                              java.util.function.BiPredicate<Integer, Integer> compatible) {
        long salt = DeterministicRandom.seed(seed,profile.algorithmVersion(),"indexed-structure","sequence/"+sequence,level);
        long visited=0;
        for(int step:new int[]{16,8,4}) {
            var candidates = new ArrayList<>(placementIndex.candidates(level,step));
            candidates.sort(java.util.Comparator.comparingDouble((PlacementIndex.Point p) ->
                    4 * levelConstraint.penalty(level,p.x(),p.z())
                            + (DeterministicRandom.mix(p.cell() ^ salt) >>> 11) * 0x1.0p-53));
            for (var point : candidates) {
                if(++visited>profile.search().requiredCandidatePreparations())throw new PlanningFailure(PlanningFailure.Code.SEARCH_BUDGET_EXHAUSTED, FailureStage.STRUCTURE_CANDIDATES,"indexed structure candidate budget exhausted",Map.of("visits",visited,"spacing",step));
                Center candidate = new Center(point.x(),point.z());
                if (!levelConstraint.accepts(level,point.x(),point.z())) continue;
                if (compatible.test(point.x(),point.z())) return candidate;
            }
        }
        throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN, FailureStage.JOINT_PLACEMENT,
                "no compatible structure center in indexed domain",Map.of("level",level,"sequence",sequence,"candidates",visited));
    }

    private PlannedBiomePatch rectangle(String id, ContentId biome, int level,
                                                                        Center center, AdventureWorldConfig.AreaRange area) {
        long minimumCells = area.inCells(4).min();
        int width = StrictMath.max(1, (int) StrictMath.floor(StrictMath.sqrt(minimumCells * 1.8)));
        for (; (long) width * width <= profile.maximumAreaCells(); width++) {
            int minX = align4(center.x - width * 2), minZ = align4(center.z - width * 2);
            var patch = new PlannedBiomePatch(id, biome, level, minX, minZ,
                    minX + width * 4, minZ + width * 4);
            long actualArea = patch.area();
            if (actualArea < area.min()) continue;
            if (actualArea > area.max()) throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN, FailureStage.AREA_ASSIGNMENT, "irregular 4-block mask exceeds maximum area", Map.of("patch_id", id, "area", actualArea));
            return patch;
        }
        throw new PlanningFailure(PlanningFailure.Code.RESOURCE_LIMIT, FailureStage.AREA_ASSIGNMENT,
                "irregular patch exceeds cell budget", Map.of("patch_id", id));
    }

    private void validate(AdventureWorldConfig config, RequirementExpander.ExpandedRequirements demands,
                                 List<PlannedBiomePatch> patches,
                                 List<PlannedStructurePlacement> structures,
                                 AdventurePlanView.SpawnPosition spawn,
                                 MacroTerrain terrain, LevelConstraint levels, BiomeConstraint biomes) {
        long visits = 0;
        Set<String> patchIds = new HashSet<>(), instanceIds = new HashSet<>();
        Map<String, RequirementExpander.PatchDemand> patchDemands = new HashMap<>();
        demands.patches().forEach(demand -> patchDemands.put(demand.patchId(), demand));
        Map<ContentId, AdventureWorldConfig.StructureSettings> settingsById = new HashMap<>();
        config.structures().forEach(settings -> settingsById.put(settings.id(), settings));
        for (var patch : patches) {
            visits++;
            if (!patchIds.add(patch.patchId())) fail("duplicate patch ID", patch.patchId());
            if ((patch.minX() & 3) != 0 || (patch.minZ() & 3) != 0 || (patch.maxXExclusive() & 3) != 0
                    || (patch.maxZExclusive() & 3) != 0) fail("patch is not aligned to the final 4-grid", patch.patchId());
            if(patch.patchId().startsWith("filler/")) {
                if(!config.biomes().filler().contains(patch.biomeId())||!patchCompatible(patch,biomes))fail("illegal frozen filler",patch.patchId());
                continue;
            }
            AdventureWorldConfig.AreaRange range;
            var direct = patchDemands.get(patch.patchId());
            if (direct != null) {
                range = direct.area();
                if (!direct.allowedBiomes().contains(patch.biomeId())) fail("patch biome is not allowed", patch.patchId());
                if (direct.adventureLevel() != patch.adventureLevel()) fail("patch level changed", patch.patchId());
                if (patch.mask() != null && (!patch.contains(patch.anchorX(),patch.anchorZ())
                        || !levels.accepts(patch.adventureLevel(),patch.anchorX(),patch.anchorZ())))
                    fail("patch anchor violates hard adventure level or ownership",patch.patchId());
            } else {
                var owner = structures.stream().filter(structure ->
                        StableIds.carrierPatch(structure.instanceId()).equals(patch.patchId())).findFirst().orElse(null);
                if (owner == null) fail("unknown patch ID", patch.patchId());
                var settings = settingsById.get(owner.structureId());
                range = settings.allowedBiomes().area();
                if (!settings.allowedBiomes().acceptsAnySupportedBiome()
                        && !settings.allowedBiomes().ids().contains(patch.biomeId()))
                    fail("carrier biome is not allowed", patch.patchId());
                if (!patch.contains(owner.anchorX(), owner.anchorZ())) fail("carrier does not contain structure anchor", patch.patchId());
            }
            // Minimum area is best effort after bounded multi-region recovery. The configured
            // maximum and every per-cell environmental / structure constraint remain hard.
            if (patch.area() > range.max()) fail("patch area exceeds configured maximum", patch.patchId());
            int centerX = (patch.minX() + patch.maxXExclusive()) / 2;
            int centerZ = (patch.minZ() + patch.maxZExclusive()) / 2;
            if (!patchCompatible(patch, biomes)) fail("biome terrain rule rejected a patch cell", patch.patchId());
        }
        Map<ContentId, Long> counts = new HashMap<>();
        for (var structure : structures) {
            visits++;
            if (!instanceIds.add(structure.instanceId())) fail("duplicate structure instance ID", structure.instanceId());
            var settings = settingsById.get(structure.structureId());
            if (settings == null) fail("unconfigured structure ID", structure.instanceId());
            if (StrictMath.hypot(structure.anchorX(), structure.anchorZ()) > config.world().radius())
                fail("structure anchor is outside the planning domain", structure.instanceId());
            var sample = terrain.sample(structure.anchorX() + 0.5, structure.anchorZ() + 0.5);
            if (sample.wet() || sample.hazardous()) fail("structure anchor is not safe dry terrain", structure.instanceId());
            if (!levels.accepts(settings.adventureLevel(), structure.anchorX(), structure.anchorZ()))
                fail("structure violates the shared adventure level", structure.instanceId());
            var carrier=patches.stream().filter(p->p.patchId().equals(StableIds.carrierPatch(structure.instanceId()))).findFirst().orElseThrow();
            if(!carrier.contains(structure.anchorX(),structure.anchorZ()))
                fail("structure anchor lost carrier ownership",structure.instanceId());
            counts.merge(structure.structureId(), 1L, Long::sum);
        }
        for (var settings : config.structures()) {
            long count = counts.getOrDefault(settings.id(), 0L);
            long minimum = settings.effectiveMinimum();
            if (count < minimum || count > settings.count().max())
                fail("structure count outside configured range", settings.id().toString());
            var matching = structures.stream().filter(item -> item.structureId().equals(settings.id())).toList();
            for (int i = 0; i < matching.size(); i++) for (int j = i + 1; j < matching.size(); j++) {
                visits++;
                double distance = StrictMath.hypot(matching.get(i).anchorX() - matching.get(j).anchorX(),
                        matching.get(i).anchorZ() - matching.get(j).anchorZ());
                if (distance + 1e-9 < settings.spacing().min()
                        || (settings.spacing().hasMaximum() && distance - 1e-9 > settings.spacing().max()))
                    fail("structure spacing outside configured range", settings.id().toString());
            }
        }
        if (patches.stream().noneMatch(patch -> patch.contains(0, 0)
                && patch.biomeId().equals(config.spawn().biome())))
            fail("spawn biome is not assigned at origin", "spawn");
        if (StrictMath.hypot(spawn.x(), spawn.z()) > StrictMath.min(256.0, config.world().radius() / 10.0) + 1e-9)
            fail("spawn is outside the reserved center domain", "spawn");
        var spawnTerrain = terrain.sample(spawn.x(), spawn.z());
        if (spawnTerrain.wet() || spawnTerrain.hazardous()) fail("spawn is not safe dry terrain", "spawn");
        if (visits > profile.search().finalValidationVisits()) throw new PlanningFailure(PlanningFailure.Code.SEARCH_BUDGET_EXHAUSTED, FailureStage.FINAL_VALIDATION, "independent validation budget exhausted",
                Map.of("visits", visits, "budget", profile.search().finalValidationVisits()));
    }

    private static void fail(String message, String id) {
        throw new PlanningFailure(PlanningFailure.Code.EXECUTION_FAILED, FailureStage.FINAL_VALIDATION, message, Map.of("id", id));
    }

    private static AdventurePlanView.SpawnPosition resolveSpawn(MacroTerrain terrain) {
        double y = StrictMath.ceil(terrain.sample(0.5, 0.5).groundSurface()) + 1.0;
        return new AdventurePlanView.SpawnPosition(0.5, y, 0.5, 0);
    }
    private static int align4(int value) { return Math.floorDiv(value, 4) * 4; }
    private static boolean overlaps(PlannedBiomePatch a,
                                    PlannedBiomePatch b) {
        int minX = StrictMath.max(a.minX(), b.minX()), maxX = StrictMath.min(a.maxXExclusive(), b.maxXExclusive());
        int minZ = StrictMath.max(a.minZ(), b.minZ()), maxZ = StrictMath.min(a.maxZExclusive(), b.maxZExclusive());
        for (int x = minX; x < maxX; x += 4) for (int z = minZ; z < maxZ; z += 4)
            if (a.contains(x + 2, z + 2) && b.contains(x + 2, z + 2)) return true;
        return false;
    }
    private static boolean spacingAllows(Center center, ContentId id, AdventureWorldConfig.Spacing spacing,
                                         List<PlannedStructurePlacement> structures) {
        for (var existing : structures) if (existing.structureId().equals(id)) {
            double distance = StrictMath.hypot(center.x - existing.anchorX(), center.z - existing.anchorZ());
            if (distance + 1e-9 < spacing.min() || (spacing.max() != null && distance - 1e-9 > spacing.max())) return false;
        }
        return true;
    }
    private static void requirePlanningInfo(StructurePlanningCatalog catalog,
                                            RequirementExpander.StructureDemand demand) {
        requirePlanningInfo(catalog, demand.structureId());
    }

    private static void requirePlanningInfo(StructurePlanningCatalog catalog, ContentId structureId) {
        var info = catalog.find(structureId).orElseThrow(() -> new PlanningFailure(
                PlanningFailure.Code.UNSUPPORTED_CONTENT, FailureStage.REQUIREMENTS,
                "structure has no planning information", Map.of("structure_id", structureId)));
        if (!info.structureId().equals(structureId))
            throw new IllegalArgumentException("structure planning catalog returned the wrong structure ID");
    }
    /**
     * How the adventure level reaches placement. Level is a <em>soft preference</em> in this product
     * (see {@code docs/systems/planning.md}): the author model uses it to order candidates, not to admit or
     * reject a position. The interface keeps a boolean because the candidate catalog and the anchor
     * checks still ask the question, but {@link #accepts} is the compatibility no-filter answer here
     * - production supplies {@link #preferenceOnly}, which accepts everywhere and ranks through
     * {@link #penalty}.
     */
    @FunctionalInterface public interface LevelConstraint {
        /**
         * Whether this level admits the position at all. Production answers {@code true} for every
         * level and position: the coast-cost interval in {@code AdventureLevels.contains} describes
         * what a level means, it is not an admission gate, and re-enabling it as one would reject
         * positions that are legal under every other hard constraint. Use {@link #preferenceOnly}
         * instead of writing {@code return true} inline, so the intent stays visible.
         */
        boolean accepts(int level, int x, int z);
        /** Ranking signal: larger means a worse fit for this level. Zero disables level ordering. */
        default double penalty(int level,int x,int z) { return 0; }
        /** A broad catalog filter; the exact predicate remains authoritative at selected anchors. */
        default boolean mightAccept(int level, int x, int z) { return true; }
    }

    /** The ranking signal of {@link LevelConstraint}, without the boolean admission question. */
    @FunctionalInterface public interface LevelPenalty { double penalty(int level, int x, int z); }

    /**
     * The production level policy: rank by {@code penalty}, never reject. Named here so the
     * "level cannot reject a legal position" decision is one reviewable place instead of an inline
     * anonymous {@code return true} at the assembly site.
     */
    public static LevelConstraint preferenceOnly(LevelPenalty penalty) {
        return new LevelConstraint() {
            @Override public boolean accepts(int level, int x, int z) { return true; }
            @Override public double penalty(int level, int x, int z) { return penalty.penalty(level, x, z); }
        };
    }

    @FunctionalInterface public interface BiomeConstraint { boolean accepts(ContentId biome, int x, int z); }
    public record Result(List<PlannedBiomePatch> patches,
                         List<PlannedStructurePlacement> structures,
                         AdventurePlanView.SpawnPosition spawn, long operationCount) {
        public Result { patches = List.copyOf(patches); structures = List.copyOf(structures); }
    }
    private record Center(int x, int z) { String key() { return x + "/" + z; } }
}
