package io.github.luoyan.adventureworldgen.worldgen;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.plan.*;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan;
import io.github.luoyan.adventureworldgen.runtime.StructurePreparation;
import io.github.luoyan.adventureworldgen.worldgen.structure.TemplateStructure;
import io.github.luoyan.adventureworldgen.worldgen.structure.JigsawParameters;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import java.util.*;

/** Validates native starts before roads/READY, exporting no pieces or Minecraft objects to planning. */
public final class StructureInstancePreparation implements StructurePreparation {
    private static final int MAX_ATTEMPTS = 64, SEARCH_RADIUS = 64;
    private final RegistryAccess registries;
    private final StructureTemplateManager templates;
    private final StructureExecutionCatalog execution;
    private final AdventureWorldConfig config;
    private final ResourceLocation profile;

    public StructureInstancePreparation(RegistryAccess registries, StructureTemplateManager templates,
            StructureExecutionCatalog execution, AdventureWorldConfig config, ResourceLocation profile) {
        this.registries=registries; this.templates=templates; this.execution=execution;
        this.config=config; this.profile=profile;
    }

    @Override public Result prepare(GeneratedAdventurePlan view, StructurePlanningCatalog catalog) {
        var generator=AdventureChunkGenerator.naturalQueries(registries,profile,view);
        var random=RandomState.create(NoiseGeneratorSettings.dummy(),registries.lookupOrThrow(Registries.NOISE),view.seed());
        var height=LevelHeightAccessor.create(AdventureChunkGenerator.MIN_Y,AdventureChunkGenerator.DEPTH);
        var placements=new ArrayList<>(view.plannedStructures());
        placements.sort(Comparator.comparing(PlannedStructurePlacement::instanceId));
        var facts=new TreeMap<String,StructurePlanningInfo>();
        for(int index=0;index<placements.size();index++) {
            var original=placements.get(index);
            var id=ResourceLocation.parse(original.structureId().value());
            var structure=registries.registryOrThrow(Registries.STRUCTURE).get(id);
            if(structure==null)throw new IllegalStateException("missing structure "+id);
            var settings=config.structures().stream().filter(s->s.id().equals(original.structureId())).findFirst().orElseThrow();
            var allowed=settings.allowedBiomes().ids().isEmpty()?config.biomes().filler():settings.allowedBiomes().ids();
            var carriers=view.biomePatches().stream().filter(p->p.contains(original.anchorX(),original.anchorZ())).toList();
            var candidates=new ArrayList<PlannedStructurePlacement>(); candidates.add(original);
            for(int dz=-SEARCH_RADIUS;dz<=SEARCH_RADIUS;dz+=8)for(int dx=-SEARCH_RADIUS;dx<=SEARCH_RADIUS;dx+=8) {
                if(dx==0&&dz==0||dx*dx+dz*dz>SEARCH_RADIUS*SEARCH_RADIUS)continue;
                candidates.add(new PlannedStructurePlacement(original.instanceId(),original.structureId(),original.anchorX()+dx,original.anchorZ()+dz));
            }
            candidates.sort(Comparator.comparingDouble((PlannedStructurePlacement p)->Math.hypot(p.anchorX()-original.anchorX(),p.anchorZ()-original.anchorZ()))
                    .thenComparingInt(PlannedStructurePlacement::anchorX).thenComparingInt(PlannedStructurePlacement::anchorZ));
            StructureStart accepted=null; PlannedStructurePlacement selected=null; int attempts=0;
            // Java execution ignores the macro position within a chunk. Do not spend the
            // native-call budget rediscovering the same invalid start at four 8-block offsets.
            boolean chunkOrigin=!(structure instanceof TemplateStructure)&&!(structure instanceof JigsawParameters);
            var attemptedChunks=new HashSet<ChunkPos>();
            for(var candidate:candidates) {
                int x=candidate.anchorX(),z=candidate.anchorZ();
                if(Math.hypot(x,z)>config.world().radius()||carriers.stream().noneMatch(p->p.contains(x,z)))continue;
                var sample=view.terrainAt(x+.5,z+.5);
                if(sample.wet()||sample.hazardous()||!allowed.contains(view.biomeAt(x,64,z))||!spacing(candidate,placements,settings.spacing()))continue;
                if(placements.stream().anyMatch(p->!p.instanceId().equals(candidate.instanceId())&&p.structureId().equals(candidate.structureId())
                        &&PlannedStructureBridge.owner(p).equals(PlannedStructureBridge.owner(candidate))))continue;
                if(chunkOrigin&&!attemptedChunks.add(PlannedStructureBridge.owner(candidate)))continue;
                if(attempts>=MAX_ATTEMPTS)break;
                attempts++;
                var context=new Structure.GenerationContext(registries,generator,generator.getBiomeSource(),random,
                        templates,view.seed(),PlannedStructureBridge.owner(candidate),height,structure.biomes()::contains);
                var start=PlannedStructureBridge.generateStart(structure,context,candidate,execution.terrain(id));
                if(!start.isValid())continue;
                PlannedStructureBridge.validateEnvelope(start,PlannedStructureBridge.owner(candidate),height);
                accepted=start; selected=candidate; break;
            }
            if(accepted==null)throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,FailureStage.STRUCTURE_CANDIDATES,
                    "native structure has no valid start in bounded carrier search",Map.of("instance",original.instanceId(),"attempts",attempts,"radius",SEARCH_RADIUS));
            placements.set(index,selected);
            var declared=catalog.find(original.structureId()).orElse(new StructurePlanningInfo(original.structureId()));
            var box=accepted.getBoundingBox();
            var bounds=new BoundsXZ(box.minX()-selected.anchorX(),box.minZ()-selected.anchorZ(),box.maxX()-selected.anchorX(),box.maxZ()-selected.anchorZ());
            // Template resource contracts may intentionally reserve additional space. Dynamic native
            // structures instead replace the conservative type envelope with this validated instance.
            if(structure instanceof TemplateStructure) {
                var extra=declared.footprintAt(view.seed(),selected.anchorX(),selected.anchorZ());
                if(extra!=null)bounds=bounds.union(extra.translate(-selected.anchorX(),-selected.anchorZ()));
            }
            var access=declared.roadAccess();
            if(access!=null&&access.entrances().isEmpty()&&!(structure instanceof TemplateStructure)) {
                access=new StructurePlanningInfo.RoadAccess(access.margin(),access.connectorLength(),
                        entrances(accepted,bounds,selected,access.margin()+(int)Math.ceil(config.roads().width()/2.0+1)+1));
            }
            if(access!=null&&declared.templateFootprint()!=null) {
                int orientation=declared.templateFootprint().rotations().get(declared.templateFootprint().orientation(view.seed(),selected.anchorX(),selected.anchorZ()));
                access=new StructurePlanningInfo.RoadAccess(access.margin(),access.connectorLength(),access.entrances().stream().map(e->e.rotate(orientation)).toList());
            }
            facts.put(original.instanceId(),new StructurePlanningInfo(original.structureId(),bounds,access));
        }
        return new Result(placements,catalog.withInstances(facts));
    }

    /** Project actual outermost pieces onto the safe perimeter, avoiding empty bbox corners. */
    private static List<StructurePlanningInfo.AccessPoint> entrances(StructureStart start,BoundsXZ bounds,
            PlannedStructurePlacement placement,int offset) {
        var boxes=start.getPieces().stream().map(p->p.getBoundingBox()).toList();
        var result=new ArrayList<StructurePlanningInfo.AccessPoint>();
        for(var facing:StructurePlanningInfo.Facing.values()) {
            Comparator<BoundingBox> order=switch(facing) {
                case NORTH -> Comparator.comparingInt(BoundingBox::minZ);
                case EAST -> Comparator.comparingInt(BoundingBox::maxX).reversed();
                case SOUTH -> Comparator.comparingInt(BoundingBox::maxZ).reversed();
                case WEST -> Comparator.comparingInt(BoundingBox::minX);
            };
            var box=boxes.stream().min(order.thenComparingInt(BoundingBox::minX).thenComparingInt(BoundingBox::minZ)).orElseThrow();
            int x=(box.minX()+box.maxX())/2-placement.anchorX(),z=(box.minZ()+box.maxZ())/2-placement.anchorZ();
            switch(facing) {
                case NORTH -> z=bounds.minZ()-offset;
                case EAST -> x=bounds.maxX()+offset;
                case SOUTH -> z=bounds.maxZ()+offset;
                case WEST -> x=bounds.minX()-offset;
            }
            result.add(new StructurePlanningInfo.AccessPoint(x,z,facing));
        }
        return List.copyOf(result);
    }

    private static boolean spacing(PlannedStructurePlacement candidate,List<PlannedStructurePlacement> placements,AdventureWorldConfig.Spacing spacing) {
        for(var other:placements)if(!candidate.instanceId().equals(other.instanceId())&&candidate.structureId().equals(other.structureId())) {
            double distance=Math.hypot(candidate.anchorX()-other.anchorX(),candidate.anchorZ()-other.anchorZ());
            if(distance+1e-9<spacing.min()||spacing.hasMaximum()&&distance-1e-9>spacing.max())return false;
        }
        return true;
    }
}
