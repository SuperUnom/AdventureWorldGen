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

    @Test void anUnavailableSharedIntersectionSplitsBackToOriginalCarrierChoices() {
        var c=new AdventureWorldConfigParser().parse("""
            {"world":{"radius":128},"spawn":{"biome":"test:plain"},"biomes":{"filler":["test:plain"],
             "required":[{"id":"test:blocked","adventure_level":4,"area":{"min":1024,"max":4096}}]},
             "structures":[{"id":"test:keep","adventure_level":4,"count":{"min":1,"max":1},
              "allowed_biomes":{"id":["test:blocked","test:alternative"],"area":{"min":1024,"max":4096}}}]}
            """);
        var result=new JointPlanner(PlannerProfile.V2).plan(7331,c,FLAT,catalog(c),(level,x,z)->true,
                (id,x,z)->!id.value().equals("test:blocked"));
        var structure=result.structures().getFirst();
        assertTrue(result.patches().stream().anyMatch(p->p.biomeId().value().equals("test:alternative")&&p.contains(structure.anchorX(),structure.anchorZ())));
        var relaxed=new ArrayList<MinimumAreaPolicy.Relaxation>();
        MinimumAreaPolicy.checkAchievedAreas(c,result.patches(),p->p.area(),relaxed::add);
        assertEquals(1,relaxed.size());assertEquals("patch/required/0",relaxed.getFirst().patchId());
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
    void requiredCarrierStillNeedsOneLegalOwnershipSeed() {
        var config=config();
        var error=assertThrows(PlanningFailure.class,()->new JointPlanner(PlannerProfile.V2)
                .plan(7331,config,FLAT,catalog(config),(level,x,z)->true,
                        (biome,x,z)->!biome.value().equals("test:forbidden")
                                &&!biome.value().equals("test:desert")));
        assertEquals("required structure carrier",error.diagnostics().get("role"));
    }

    @Test
    void steepTerrainStillProducesOnlyAMacroAnchorAndCarrier() {
        var config=config();
        MacroTerrain uneven=(x,z)->new MacroSample(80+0.5*x,
                Double.NaN,WaterKind.NONE,false,"r","hills","test");
        var result=new JointPlanner(PlannerProfile.V2).plan(8844,config,uneven,catalog(config));
        var placement=result.structures().getFirst();
        var carrier=result.patches().stream().filter(p->p.patchId().equals(
                StableIds.carrierPatch(placement.instanceId()))).findFirst().orElseThrow();
        assertTrue(carrier.contains(placement.anchorX(),placement.anchorZ()));
        assertEquals(WaterKind.NONE,uneven.sample(placement.anchorX()+.5,placement.anchorZ()+.5).waterKind());
        double west=uneven.sample(placement.anchorX()-15.5,placement.anchorZ()+.5).groundSurface();
        double east=uneven.sample(placement.anchorX()+16.5,placement.anchorZ()+.5).groundSurface();
        assertTrue(east-west>8,"regression terrain must reject the anchor under the removed ±16 flatness check");
    }

    private AdventureWorldConfig sharedConfig(int maximum, int spacing) {
        return new AdventureWorldConfigParser().parse("""
          {"world":{"radius":256},"spawn":{"biome":"test:plains"},"biomes":{
           "required":[{"id":"test:plains","adventure_level":0,"area":{"min":1024,"max":4096}},
                       {"id":"test:forest","adventure_level":4,"area":{"min":4096,"max":16384,"target":8192}}],
           "filler":["test:plains"]},
           "structures":[{"id":"test:keep","adventure_level":6,"count":{"min":2,"max":%d},
           "spacing":{"min":%d},"allowed_biomes":{"id":["test:forest"],
           "area":{"min":4096,"max":65536,"target":8192}}}]}
          """.formatted(maximum, spacing));
    }

    @Test
    void requiredAndOptionalInstancesShareOneBiomeWhileKeepingSpacingAndOriginalLevels() {
        var config = sharedConfig(3, 24);
        var levelsSeen = new java.util.HashSet<Integer>();
        var levels = JointPlanner.preferenceOnly((level, x, z) -> {
            levelsSeen.add(level);
            return 0;
        });
        var first = new JointPlanner(PlannerProfile.V2).plan(7331, config, FLAT, catalog(config), levels);
        var second = new JointPlanner(PlannerProfile.V2).plan(7331, config, FLAT, catalog(config));
        assertEquals(first, second);
        assertEquals(3, first.structures().size());
        assertEquals(2, first.patches().stream().filter(p -> !p.patchId().startsWith("filler/")).count());
        var carrier = first.patches().stream().filter(p -> p.biomeId().value().equals("test:forest")).findFirst().orElseThrow();
        assertEquals(5, carrier.adventureLevel());
        assertTrue(levelsSeen.contains(5), "shared biome uses the rounded member average");
        assertTrue(levelsSeen.contains(6), "structure anchors keep their own author level");
        for (var structure : first.structures()) assertTrue(carrier.contains(structure.anchorX(), structure.anchorZ()));
        for (int i = 0; i < first.structures().size(); i++) for (int j = 0; j < i; j++) {
            var a = first.structures().get(i); var b = first.structures().get(j);
            assertTrue(Math.hypot(a.anchorX() - b.anchorX(), a.anchorZ() - b.anchorZ()) >= 24);
        }
        assertTrue(first.patches().getFirst().contains(0, 0));
        var relaxations = new ArrayList<MinimumAreaPolicy.Relaxation>();
        MinimumAreaPolicy.checkAchievedAreas(config, first.patches(), p -> p.area(), relaxations::add);
        assertTrue(relaxations.isEmpty(), "merged minimum must be counted only once");
    }

    @Test
    void mergedRequiredCarrierCannotDisappearWithAnOrdinaryBiomeIdentity() {
        var config = sharedConfig(2, 24);
        var failure = assertThrows(PlanningFailure.class, () -> new JointPlanner(PlannerProfile.V2)
                .plan(7331, config, FLAT, catalog(config), (level,x,z) -> true,
                        (biome,x,z) -> !biome.value().equals("test:forest")));
        assertEquals("required structure carrier", failure.diagnostics().get("role"));
    }

    @Test
    void sharedAlternativeListFallsBackToALegalCommonBiome() {
        var config = new AdventureWorldConfigParser().parse("""
          {"world":{"radius":256},"spawn":{"biome":"test:plains"},"biomes":{
           "required":[{"id":"test:plains","adventure_level":0,"area":{"min":1024,"max":4096}}],
           "filler":["test:plains"]},"structures":[{"id":"test:keep","adventure_level":4,
           "count":{"min":2,"max":2},"allowed_biomes":{"id":["test:forbidden","test:desert"],
           "area":{"min":1024,"target":4096,"max":8192}}}]}
          """);
        var plan = new JointPlanner(PlannerProfile.V2).plan(7331, config, FLAT, catalog(config),
                (level,x,z) -> true, (biome,x,z) -> !biome.value().equals("test:forbidden"));
        var carriers = plan.patches().stream().filter(p -> p.biomeId().value().equals("test:desert")).toList();
        assertEquals(1, carriers.size());
        assertEquals(2, plan.structures().size());
        for (var structure : plan.structures()) assertTrue(carriers.getFirst().contains(structure.anchorX(), structure.anchorZ()));
    }

    @Test
    void failedOptionalSharingDoesNotChangeTheMinimumLayout() {
        String json = """
          {"world":{"radius":128},"spawn":{"biome":"test:plains"},"biomes":{
           "required":[{"id":"test:plains","adventure_level":0,"area":{"min":4096,"max":16384}}],
           "filler":["test:plains"]},"structures":[{"id":"test:keep","adventure_level":2,
           "spacing":{"min":1000},"count":{"min":1,"max":%d},"allowed_biomes":{"id":["test:plains"],
           "area":{"min":1024,"max":16384}}}]}
          """;
        var one = new AdventureWorldConfigParser().parse(json.formatted(1));
        var two = new AdventureWorldConfigParser().parse(json.formatted(2));
        var baseline = new JointPlanner(PlannerProfile.V2).plan(7331, one, FLAT, catalog(one));
        var attempted = new JointPlanner(PlannerProfile.V2).plan(7331, two, FLAT, catalog(two));
        assertEquals(baseline, attempted);
        assertEquals(1, attempted.structures().size());
    }
}
