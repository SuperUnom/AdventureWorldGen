package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import io.github.luoyan.adventureworldgen.plan.StableIds;
import io.github.luoyan.adventureworldgen.plan.StructurePlanningCatalog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureBiomePlanningTest {
    private static final MacroTerrain FLAT=(x,z)->new MacroSample(80,Double.NaN,WaterKind.NONE,false,"r","plains","test");

    private AdventureWorldConfig config() {
        return new AdventureWorldConfigParser().parse("""
          {"world":{"radius":512},"spawn":{"biome":"test:plains"},"biomes":{
           "required":[{"id":"test:forest","adventure_level":2,"area":{"min":4096,"target":65536}}],
           "filler":["test:plains"],"terrain_rules":{"test:forbidden":{"allowed_terrain":["mountains"]}}},
           "structures":[{"id":"test:keep","adventure_level":4,"count":{"min":1,"max":1},
           "allowed_biomes":{"id":["test:forbidden","test:desert"],"area":{"min":4096,"target":65536}}}]}
          """);
    }

    private static StructurePlanningCatalog catalog(AdventureWorldConfig config) {
        return StructurePlanningCatalog.fromIds(config.structures().stream()
                .map(AdventureWorldConfig.StructureSettings::id).toList());
    }

    @Test
    void freezesBiomeLayoutBeforeChoosingAStableAnchorInsideTheCarrier() {
        var config=config();
        var events=new ArrayList<String>();
        var first=new JointPlanner(PlannerProfile.V2).plan(7331,config,FLAT,catalog(config),
                (level,x,z)->true,(biome,x,z)->true,ignored->{},events::add);
        var second=new JointPlanner(PlannerProfile.V2).plan(7331,config,FLAT,catalog(config));

        assertTrue(events.indexOf("biomes")<events.indexOf("structures"));
        assertEquals(first.structures(),second.structures());
        var placement=first.structures().getFirst();
        var carrier=first.patches().stream().filter(p->p.patchId().equals(
                StableIds.carrierPatch(placement.instanceId()))).findFirst().orElseThrow();
        assertTrue(carrier.contains(placement.anchorX(),placement.anchorZ()));
        assertTrue(carrier.area()>=4096);
    }

    @Test
    void missingPlanningInfoRejectsTheStructureWithoutRecognizingItsIdInPlannerCode() {
        var config=config();
        var error=assertThrows(PlanningFailure.class,()->new JointPlanner(PlannerProfile.V2)
                .plan(7331,config,FLAT,StructurePlanningCatalog.fromIds(java.util.List.of())));
        assertTrue(error.getMessage().contains("no planning information"));
        assertEquals("test:keep", error.diagnostics().get("structure_id"));
    }

    @Test
    void unevenTerrainStillProducesOnlyAMacroAnchorAndCarrier() {
        var config=config();
        MacroTerrain uneven=(x,z)->new MacroSample(90+6*Math.sin(x/120)+4*Math.cos(z/80),
                Double.NaN,WaterKind.NONE,false,"r","hills","test");
        var result=new JointPlanner(PlannerProfile.V2).plan(8844,config,uneven,catalog(config));
        var placement=result.structures().getFirst();
        var carrier=result.patches().stream().filter(p->p.patchId().equals(
                StableIds.carrierPatch(placement.instanceId()))).findFirst().orElseThrow();
        assertTrue(carrier.contains(placement.anchorX(),placement.anchorZ()));
        assertEquals(WaterKind.NONE,uneven.sample(placement.anchorX()+.5,placement.anchorZ()+.5).waterKind());
    }
}
