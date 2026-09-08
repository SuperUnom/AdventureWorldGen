package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.config.ContentId;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stable bounded placement of the minimum legal patch/structure solution before optional optimization. */
public final class JointPlanner {
    private final PlannerProfile profile;
    private PlacementIndex placementIndex;
    private ClimatePlan climate;

    public JointPlanner(PlannerProfile profile) { this.profile = profile; }

    public Result plan(long seed, AdventureWorldConfig config, MacroTerrain terrain, StructureFreezer freezer) {
        return plan(seed, config, terrain, freezer, (level, x, z) -> true, (biome, x, z) -> true);
    }

    public Result plan(long seed, AdventureWorldConfig config, MacroTerrain terrain, StructureFreezer freezer,
                       LevelConstraint levelConstraint) {
        return plan(seed, config, terrain, freezer, levelConstraint, (biome, x, z) -> true);
    }

    public Result plan(long seed, AdventureWorldConfig config, MacroTerrain terrain, StructureFreezer freezer,
                       LevelConstraint levelConstraint, BiomeConstraint adapterConstraint) {
        return plan(seed,config,terrain,freezer,levelConstraint,adapterConstraint,ignored -> {});
    }

    public Result plan(long seed, AdventureWorldConfig config, MacroTerrain terrain, StructureFreezer freezer,
                       LevelConstraint levelConstraint, BiomeConstraint adapterConstraint, java.util.function.DoubleConsumer progress) {
        return plan(seed,config,terrain,freezer,levelConstraint,adapterConstraint,progress,ignored -> {});
    }

    public Result plan(long seed, AdventureWorldConfig config, MacroTerrain terrain, StructureFreezer freezer,
                       LevelConstraint levelConstraint, BiomeConstraint adapterConstraint, java.util.function.DoubleConsumer progress,
                       java.util.function.Consumer<String> checkpoint) {
        var demands = new RequirementExpander().expandMinimum(config);
        List<GeneratedAdventurePlan.PlannedBiomePatch> patches = new ArrayList<>();
        List<AdventurePlanView.PlannedStructure> structures = new ArrayList<>();
        Set<String> occupied = new HashSet<>();
        long operations = 0;
        long indexStart = System.nanoTime();
        placementIndex = new PlacementIndex(config, terrain, levelConstraint, adapterConstraint, value -> progress.accept(value * 0.2));
        checkpoint.accept("index");
        io.github.luoyan.adventureworldgen.runtime.PlanningProgress.stageCurrent(io.github.luoyan.adventureworldgen.runtime.PlanningProgress.Stage.TEMPERATURE);
        climate=new ClimatePlan(seed,config,terrain,io.github.luoyan.adventureworldgen.runtime.PlanningProgress.withinCurrent(io.github.luoyan.adventureworldgen.runtime.PlanningProgress.Stage.TEMPERATURE));
        checkpoint.accept("climate");
        io.github.luoyan.adventureworldgen.runtime.PlanningProgress.stageCurrent(io.github.luoyan.adventureworldgen.runtime.PlanningProgress.Stage.SEEDS);
        BiomeConstraint biomeConstraint = placementIndex::allows;
        System.getLogger(JointPlanner.class.getName()).log(System.Logger.Level.INFO,
                "Placement index built in {0} ms", (System.nanoTime() - indexStart) / 1_000_000);

        // Reserve the actual spawn cell before preparing structural footprints. The full spawn
        // quota participates in the same matching as all other required biome quotas.
        if (config.spawn().hasBiome()) {
            patches.add(new GeneratedAdventurePlan.PlannedBiomePatch("reservation/spawn", config.spawn().biome(), 0,
                    0, 0, 4, 4, new CellMask(new long[]{CellMask.key(0,0)}), 0, 0));
        }
        for (int i=0; i<demands.structures().size(); i++) {
            placeStructure(seed,config,terrain,freezer,levelConstraint,biomeConstraint,patches,structures,occupied,
                    demands.structures().get(i),i+10_000,true);
            operations++;
        }
        patches.removeIf(p -> p.patchId().equals("reservation/spawn"));
        checkpoint.accept("structures");
        progress.accept(0.4);
        long assignmentStart = System.nanoTime();
        var assigned = new BiomeAllocationPlanner().allocate(seed,config,placementIndex,demands.patches(),patches,climate,value -> {
            var stage=value<.18?io.github.luoyan.adventureworldgen.runtime.PlanningProgress.Stage.SEEDS:io.github.luoyan.adventureworldgen.runtime.PlanningProgress.Stage.GROWTH;
            io.github.luoyan.adventureworldgen.runtime.PlanningProgress.withinCurrent(stage).accept(value<.18?value/.18:(value-.18)/.82);
        });
        patches.addAll(assigned.patches());
        operations += assigned.operations();
        progress.accept(0.95);
        System.getLogger(JointPlanner.class.getName()).log(System.Logger.Level.INFO,
                "Joint area assignment finished in {0} ms; {1} exact terrain samples, {2} operations",
                (System.nanoTime()-assignmentStart)/1_000_000,placementIndex.queries(),assigned.operations());

        // Optional instances are a soft optimization: each attempt commits atomically or leaves the legal minimum unchanged.
        long optionalAttempts = 0;
        for (var settings : config.structures()) {
            long minimum = settings.effectiveMinimum(config.spawn().hasStructure()
                    && config.spawn().structure().id().equals(settings.id()));
            for (long sequence = minimum; sequence < settings.count().max()
                    && optionalAttempts < profile.search().optionalCandidatePreparations(); sequence++, optionalAttempts++) {
                var demand = new RequirementExpander.StructureInstanceDemand(
                        StableIds.structureInstance(settings.id(), sequence), settings.id(), Math.toIntExact(sequence),
                        settings.adventureLevel(), settings.allowedBiomes().ids(), settings.allowedBiomes().area(),
                        settings.entrance(), false, false);
                if (!placeStructure(seed, config, terrain, freezer, levelConstraint, biomeConstraint, patches, structures, occupied,
                        demand, 100_000 + Math.toIntExact(sequence), false)) break;
                operations++;
            }
        }

        AdventurePlanView.SpawnPosition spawn = resolveSpawn(config, terrain, structures);
        validate(config, demands, patches, structures, spawn, terrain, levelConstraint, biomeConstraint);
        checkpoint.accept("area_assignment");
        return new Result(patches, structures, spawn, operations);
    }

    private boolean placeStructure(long seed, AdventureWorldConfig config, MacroTerrain terrain,
                                   StructureFreezer freezer, LevelConstraint levels,
                                   BiomeConstraint biomes,
                                   List<GeneratedAdventurePlan.PlannedBiomePatch> patches,
                                   List<AdventurePlanView.PlannedStructure> structures, Set<String> occupied,
                                   RequirementExpander.StructureInstanceDemand demand, int proposalSequence,
                                   boolean required) {
        AdventureWorldConfig.StructureSettings settings = config.structures().stream()
                .filter(item -> item.id().equals(demand.structureId())).findFirst().orElseThrow();
        ContentId biome = demand.allowedBiomes().isEmpty() ? config.biomes().filler().getFirst()
                : demand.allowedBiomes().getFirst();
        for (int fallback = 0; fallback < profile.search().spawnCandidateAttempts(); fallback++) {
            Center center;
            try {
                center = demand.spawnInstance()
                        ? findSpawnStructureCenter(seed, config.world().radius(), proposalSequence, fallback, terrain, levels)
                        : findCenter(seed, config.world().radius(), demand.adventureLevel(),
                        proposalSequence + fallback * 1_000_000, terrain, Set.of(), levels, (x, z) -> {
                            if (!biomes.accepts(biome, x, z)) return false;
                            var possible = rectangle(StableIds.carrierPatch(demand.instanceId()), biome,
                                    demand.adventureLevel(), new Center(x, z), demand.carrierArea());
                            return patches.stream().noneMatch(existing -> !existing.biomeId().equals(biome) && overlaps(existing, possible))
                                    && patchCompatible(possible, biomes);
                        }, true);
            } catch (PlanningFailure failure) {
                if (demand.spawnInstance() && fallback + 1 < profile.search().spawnCandidateAttempts()) continue;
                if (required) throw failure;
                return false;
            }
            if (!spacingAllows(center, demand.structureId(), settings.spacing(), structures)) continue;
            if (!biomes.accepts(biome, center.x, center.z)) continue;
            var patch = rectangle(StableIds.carrierPatch(demand.instanceId()), biome, demand.adventureLevel(), center,
                    demand.carrierArea());
            if (patches.stream().anyMatch(existing -> overlaps(existing, patch)
                    && !existing.biomeId().equals(patch.biomeId()))) continue;
            long structureSeed = DeterministicRandom.seed(seed, profile.algorithmVersion(), "structure", demand.instanceId(), 0);
            int y = (int) StrictMath.ceil(terrain.sample(center.x, center.z).groundSurface());
            try {
                AdventurePlanView.PlannedStructure frozen = freezer.freeze(demand, center.x, y, center.z, structureSeed);
                if (!frozen.instanceId().equals(demand.instanceId()) || !frozen.structureId().equals(demand.structureId())
                        || frozen.originX() != center.x || frozen.originZ() != center.z)
                    throw new IllegalArgumentException("structure adapter changed frozen identity or origin");
                var carrier = fitCarrier(patch, frozen.biomeProtection(), demand.carrierArea().max());
                if (carrier == null || !patchCompatible(carrier, biomes) || frozen.footprint().isEmpty() || frozen.biomeProtection().isEmpty()
                        || !levels.accepts(demand.adventureLevel(), frozen.entranceX(), frozen.entranceZ())
                        || patches.stream().anyMatch(existing -> overlaps(existing, carrier)
                        && !existing.biomeId().equals(carrier.biomeId()))
                        || structures.stream().anyMatch(existing -> reservationsConflict(existing, frozen))) continue;
                patches.add(carrier); structures.add(frozen); occupied.add(center.key());
                return true;
            } catch (PlanningFailure | IllegalArgumentException rejected) {
                // No state was mutated: retry position/rotation/carrier relation from the last legal snapshot.
            }
        }
        if (required) throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN, "joint-placement",
                "required structure exhausted bounded spatial fallbacks: " + demand.instanceId(), Map.of("instance_id", demand.instanceId()));
        return false;
    }

    private static boolean patchCompatible(GeneratedAdventurePlan.PlannedBiomePatch patch, BiomeConstraint biomes) {
        for (int z = patch.minZ() + 2; z < patch.maxZExclusive(); z += 4)
            for (int x = patch.minX() + 2; x < patch.maxXExclusive(); x += 4)
                if (patch.contains(x, z) && !biomes.accepts(patch.biomeId(), x, z)) return false;
        return true;
    }

    private static GeneratedAdventurePlan.PlannedBiomePatch fitCarrier(
            GeneratedAdventurePlan.PlannedBiomePatch patch,
            List<io.github.luoyan.adventureworldgen.api.StructureAdapter.HorizontalBox> protection, long maximumArea) {
        if (protection.isEmpty()) return null;
        int minX = protection.stream().mapToInt(box -> box.minX()).min().orElseThrow();
        int minZ = protection.stream().mapToInt(box -> box.minZ()).min().orElseThrow();
        int maxX = protection.stream().mapToInt(box -> box.maxX()).max().orElseThrow();
        int maxZ = protection.stream().mapToInt(box -> box.maxZ()).max().orElseThrow();
        int centerX = align4((minX + maxX) / 2), centerZ = align4((minZ + maxZ) / 2);
        for (int size = patch.maxXExclusive() - patch.minX(); ; size += 4) {
            int x = align4(centerX - size / 2), z = align4(centerZ - size / 2);
            var candidate = new GeneratedAdventurePlan.PlannedBiomePatch(patch.patchId(), patch.biomeId(),
                    patch.adventureLevel(), x, z, x + size, z + size);
            if (candidate.area() > maximumArea) return null;
            if (protection.stream().allMatch(box -> contains(candidate, box))) return candidate;
        }
    }

    private Center findCenter(long seed, double radius, int level, int sequence, MacroTerrain terrain,
                              Set<String> occupied, LevelConstraint levelConstraint) {
        return findCenter(seed, radius, level, sequence, terrain, occupied, levelConstraint, (x, z) -> true, true);
    }

    private Center findCenter(long seed, double radius, int level, int sequence, MacroTerrain terrain,
                              Set<String> occupied, LevelConstraint levelConstraint,
                              java.util.function.BiPredicate<Integer, Integer> compatible) {
        return findCenter(seed, radius, level, sequence, terrain, occupied, levelConstraint, compatible, false);
    }

    private Center findCenter(long seed, double radius, int level, int sequence, MacroTerrain terrain,
                              Set<String> occupied, LevelConstraint levelConstraint,
                              java.util.function.BiPredicate<Integer, Integer> compatible, boolean requireFlat) {
        long salt = DeterministicRandom.seed(seed,profile.algorithmVersion(),"indexed-structure","sequence/"+sequence,level);
        int separation = requireFlat ? (int) StrictMath.max(64.0,StrictMath.min(512.0,radius/8.0)) : 64;
        long visited=0;
        for(int step:new int[]{16,8,4}) {
            var candidates = new ArrayList<>(placementIndex.candidates(level,step));
            candidates.sort(java.util.Comparator.comparingDouble((PlacementIndex.Point p) ->
                    4 * levelConstraint.penalty(level,p.x(),p.z())
                            + (PlacementIndex.mix(p.cell() ^ salt) >>> 11) * 0x1.0p-53));
            for (var point : candidates) {
                if(++visited>profile.search().requiredCandidatePreparations())throw new PlanningFailure(PlanningFailure.Code.SEARCH_BUDGET_EXHAUSTED,
                        "structure-candidates","indexed structure candidate budget exhausted",Map.of("visits",visited,"spacing",step));
                Center candidate = new Center(point.x(),point.z());
                if (occupied.stream().anyMatch(key -> near(key,candidate,separation))) continue;
                if (!levelConstraint.accepts(level,point.x(),point.z())) continue;
                if (requireFlat && !sufficientlyFlat(terrain,point.x(),point.z())) continue;
                if (compatible.test(point.x(),point.z())) return candidate;
            }
        }
        throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,"joint-placement",
                "no compatible structure center in indexed domain",Map.of("level",level,"sequence",sequence,"candidates",visited));
    }

    private Center findSpawnStructureCenter(long seed, double radius, int sequence, int attempt,
                                            MacroTerrain terrain, LevelConstraint levels) {
        double maximum = StrictMath.min(256.0, radius / 10.0) * 0.80;
        double angle = DeterministicRandom.sample(seed, profile.algorithmVersion(), "spawn-structure",
                "sequence/" + sequence, attempt * 2L) * StrictMath.PI * 2.0;
        double distance = attempt == 0 ? 0.0 : maximum * StrictMath.sqrt(DeterministicRandom.sample(seed,
                profile.algorithmVersion(), "spawn-structure", "sequence/" + sequence, attempt * 2L + 1));
        int x = align4((int) StrictMath.rint(StrictMath.cos(angle) * distance));
        int z = align4((int) StrictMath.rint(StrictMath.sin(angle) * distance));
        var sample = terrain.sample(x + 0.5, z + 0.5);
        if (sample.waterKind() == WaterKind.NONE && !sample.hazardous() && sufficientlyFlat(terrain, x, z)
                && levels.accepts(0, x, z)) return new Center(x, z);
        throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN, "spawn-candidate",
                "spawn structure candidate is not safe at this bounded attempt", Map.of("attempt", attempt));
    }

    private static GeneratedAdventurePlan.PlannedBiomePatch rectangle(String id, ContentId biome, int level,
                                                                        Center center, AdventureWorldConfig.AreaRange area) {
        long minimumCells = area.inCells(4).min();
        int width = StrictMath.max(1, (int) StrictMath.floor(StrictMath.sqrt(minimumCells * 1.8)));
        for (; (long) width * width <= PlannerProfile.V2.maximumAreaCells(); width++) {
            int minX = align4(center.x - width * 2), minZ = align4(center.z - width * 2);
            var patch = new GeneratedAdventurePlan.PlannedBiomePatch(id, biome, level, minX, minZ,
                    minX + width * 4, minZ + width * 4);
            long actualArea = patch.area();
            if (actualArea < area.min()) continue;
            if (actualArea > area.max()) throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,
                    "area-assignment", "irregular 4-block mask exceeds maximum area", Map.of("patch_id", id, "area", actualArea));
            return patch;
        }
        throw new PlanningFailure(PlanningFailure.Code.RESOURCE_LIMIT, "area-assignment",
                "irregular patch exceeds cell budget", Map.of("patch_id", id));
    }

    private void validate(AdventureWorldConfig config, RequirementExpander.ExpandedRequirements demands,
                                 List<GeneratedAdventurePlan.PlannedBiomePatch> patches,
                                 List<AdventurePlanView.PlannedStructure> structures,
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
                if (!patch.contains(owner.originX(), owner.originZ())) fail("carrier does not contain structure origin", patch.patchId());
            }
            if (patch.area() < range.min() || patch.area() > range.max()) fail("patch area outside configured range", patch.patchId());
            int centerX = (patch.minX() + patch.maxXExclusive()) / 2;
            int centerZ = (patch.minZ() + patch.maxZExclusive()) / 2;
            if (!patchCompatible(patch, biomes)) fail("biome terrain rule rejected a patch cell", patch.patchId());
        }
        Map<ContentId, Long> counts = new HashMap<>();
        for (var structure : structures) {
            visits += 2L + structure.pieces().size();
            if (!instanceIds.add(structure.instanceId())) fail("duplicate structure instance ID", structure.instanceId());
            var settings = settingsById.get(structure.structureId());
            if (settings == null) fail("unconfigured structure ID", structure.instanceId());
            if (structure.pieces().isEmpty()) fail("structure has no frozen pieces", structure.instanceId());
            if (structure.footprint().isEmpty() || structure.biomeProtection().isEmpty())
                fail("structure has no frozen footprint or biome protection", structure.instanceId());
            Set<String> pieceIds = new HashSet<>();
            for (var piece : structure.pieces()) {
                if (!pieceIds.add(piece.pieceId())) fail("duplicate piece ID", piece.pieceId());
                if (piece.canonicalNbt().length == 0) fail("piece has no canonical NBT", piece.pieceId());
            }
            if (StrictMath.hypot(structure.originX(), structure.originZ()) > config.world().radius())
                fail("structure origin is outside the planning domain", structure.instanceId());
            var sample = terrain.sample(structure.originX() + 0.5, structure.originZ() + 0.5);
            if (sample.wet() || sample.hazardous()) fail("structure origin is not safe dry terrain", structure.instanceId());
            if (!levels.accepts(settings.adventureLevel(), structure.entranceX(), structure.entranceZ()))
                fail("structure violates the shared adventure level", structure.instanceId());
            counts.merge(structure.structureId(), 1L, Long::sum);
        }
        for (var settings : config.structures()) {
            long count = counts.getOrDefault(settings.id(), 0L);
            long minimum = settings.effectiveMinimum(config.spawn().hasStructure()
                    && config.spawn().structure().id().equals(settings.id()));
            if (count < minimum || count > settings.count().max())
                fail("structure count outside configured range", settings.id().toString());
            var matching = structures.stream().filter(item -> item.structureId().equals(settings.id())).toList();
            for (int i = 0; i < matching.size(); i++) for (int j = i + 1; j < matching.size(); j++) {
                visits++;
                double distance = StrictMath.hypot(matching.get(i).originX() - matching.get(j).originX(),
                        matching.get(i).originZ() - matching.get(j).originZ());
                if (distance + 1e-9 < settings.spacing().min()
                        || (settings.spacing().hasMaximum() && distance - 1e-9 > settings.spacing().max()))
                    fail("structure spacing outside configured range", settings.id().toString());
            }
        }
        for (int i = 0; i < structures.size(); i++) for (int j = i + 1; j < structures.size(); j++) {
            visits++;
            if (reservationsConflict(structures.get(i), structures.get(j)))
                fail("structure footprint/protection conflict", structures.get(i).instanceId() + "/" + structures.get(j).instanceId());
        }
        if (config.spawn().hasBiome() && patches.stream().noneMatch(patch -> patch.contains(0, 0)
                && patch.biomeId().equals(config.spawn().biome())) && !config.spawn().hasStructure())
            fail("spawn biome is not assigned at origin", "spawn");
        if (StrictMath.hypot(spawn.x(), spawn.z()) > StrictMath.min(256.0, config.world().radius() / 10.0) + 1e-9)
            fail("spawn is outside the reserved center domain", "spawn");
        if (!config.spawn().hasStructure()) {
            var spawnTerrain = terrain.sample(spawn.x(), spawn.z());
            if (spawnTerrain.wet() || spawnTerrain.hazardous()) fail("spawn is not safe dry terrain", "spawn");
        }
        if (visits > profile.search().finalValidationVisits()) throw new PlanningFailure(
                PlanningFailure.Code.SEARCH_BUDGET_EXHAUSTED, "final-validation", "independent validation budget exhausted",
                Map.of("visits", visits, "budget", profile.search().finalValidationVisits()));
    }

    private static void fail(String message, String id) {
        throw new PlanningFailure(PlanningFailure.Code.EXECUTION_FAILED, "final-validation", message, Map.of("id", id));
    }

    private static AdventurePlanView.SpawnPosition resolveSpawn(AdventureWorldConfig config, MacroTerrain terrain,
                                                                 List<AdventurePlanView.PlannedStructure> structures) {
        if (!config.spawn().hasStructure()) {
            double y = StrictMath.ceil(terrain.sample(0.5, 0.5).groundSurface()) + 1.0;
            return new AdventurePlanView.SpawnPosition(0.5, y, 0.5, 0);
        }
        String instanceId = StableIds.structureInstance(config.spawn().structure().id(), 0);
        var structure = structures.stream().filter(item -> item.instanceId().equals(instanceId)).findFirst()
                .orElseThrow(() -> new PlanningFailure(PlanningFailure.Code.EXECUTION_FAILED, "spawn-resolution",
                        "spawn structure instance is absent", Map.of("instance_id", instanceId)));
        var relative = config.spawn().structure().spawnPoint();
        double rx, rz;
        float yaw;
        switch (structure.rotation()) {
            case "north" -> { rx = relative.x(); rz = relative.z(); yaw = 0; }
            case "east" -> { rx = -relative.z(); rz = relative.x(); yaw = 90; }
            case "south" -> { rx = -relative.x(); rz = -relative.z(); yaw = 180; }
            case "west" -> { rx = relative.z(); rz = -relative.x(); yaw = 270; }
            default -> throw new PlanningFailure(PlanningFailure.Code.EXECUTION_FAILED, "spawn-resolution",
                    "adapter returned unsupported spawn rotation", Map.of("rotation", structure.rotation()));
        }
        return new AdventurePlanView.SpawnPosition(structure.originX() + rx,
                structure.originY() + relative.y(), structure.originZ() + rz, yaw);
    }
    private static int align4(int value) { return Math.floorDiv(value, 4) * 4; }
    private static boolean overlaps(GeneratedAdventurePlan.PlannedBiomePatch a,
                                    GeneratedAdventurePlan.PlannedBiomePatch b) {
        int minX = StrictMath.max(a.minX(), b.minX()), maxX = StrictMath.min(a.maxXExclusive(), b.maxXExclusive());
        int minZ = StrictMath.max(a.minZ(), b.minZ()), maxZ = StrictMath.min(a.maxZExclusive(), b.maxZExclusive());
        for (int x = minX; x < maxX; x += 4) for (int z = minZ; z < maxZ; z += 4)
            if (a.contains(x + 2, z + 2) && b.contains(x + 2, z + 2)) return true;
        return false;
    }
    private static boolean contains(GeneratedAdventurePlan.PlannedBiomePatch patch,
                                    io.github.luoyan.adventureworldgen.api.StructureAdapter.HorizontalBox box) {
        for (int x = box.minX(); x <= box.maxX(); x++) for (int z = box.minZ(); z <= box.maxZ(); z++)
            if (!patch.contains(x, z)) return false;
        return true;
    }
    private static boolean reservationsConflict(AdventurePlanView.PlannedStructure a,
                                                AdventurePlanView.PlannedStructure b) {
        return boxesIntersect(a.footprint(), b.biomeProtection()) || boxesIntersect(b.footprint(), a.biomeProtection());
    }
    private static boolean boxesIntersect(List<io.github.luoyan.adventureworldgen.api.StructureAdapter.HorizontalBox> a,
                                          List<io.github.luoyan.adventureworldgen.api.StructureAdapter.HorizontalBox> b) {
        for (var left : a) for (var right : b)
            if (left.minX() <= right.maxX() && left.maxX() >= right.minX()
                    && left.minZ() <= right.maxZ() && left.maxZ() >= right.minZ()) return true;
        return false;
    }
    private static boolean spacingAllows(Center center, ContentId id, AdventureWorldConfig.Spacing spacing,
                                         List<AdventurePlanView.PlannedStructure> structures) {
        for (var existing : structures) if (existing.structureId().equals(id)) {
            double distance = StrictMath.hypot(center.x - existing.originX(), center.z - existing.originZ());
            if (distance + 1e-9 < spacing.min() || (spacing.max() != null && distance - 1e-9 > spacing.max())) return false;
        }
        return true;
    }
    private static boolean sufficientlyFlat(MacroTerrain terrain, int x, int z) {
        double minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
        for (int dx : new int[]{-16, 16}) for (int dz : new int[]{-16, 16}) {
            var sample = terrain.sample(x + dx + 0.5, z + dz + 0.5);
            if (sample.wet() || sample.hazardous()) return false;
            minimum = StrictMath.min(minimum, sample.groundSurface());
            maximum = StrictMath.max(maximum, sample.groundSurface());
        }
        return maximum - minimum <= 8.0;
    }
    private static boolean near(String key, Center candidate, int distance) {
        int split = key.indexOf('/');
        int x = Integer.parseInt(key.substring(0, split)), z = Integer.parseInt(key.substring(split + 1));
        return (long) (x - candidate.x) * (x - candidate.x) + (long) (z - candidate.z) * (z - candidate.z) < (long) distance * distance;
    }

    public interface StructureFreezer {
        AdventurePlanView.PlannedStructure freeze(RequirementExpander.StructureInstanceDemand demand,
                                                   int x, int y, int z, long structureSeed);
    }
    @FunctionalInterface public interface LevelConstraint {
        boolean accepts(int level, int x, int z);
        default double penalty(int level,int x,int z) { return 0; }
        /** A broad catalog filter; the exact predicate remains authoritative at selected anchors. */
        default boolean mightAccept(int level, int x, int z) { return true; }
    }
    @FunctionalInterface public interface BiomeConstraint { boolean accepts(ContentId biome, int x, int z); }
    public record Result(List<GeneratedAdventurePlan.PlannedBiomePatch> patches,
                         List<AdventurePlanView.PlannedStructure> structures,
                         AdventurePlanView.SpawnPosition spawn, long operationCount) {
        public Result { patches = List.copyOf(patches); structures = List.copyOf(structures); }
    }
    private record Center(int x, int z) { String key() { return x + "/" + z; } }
}
