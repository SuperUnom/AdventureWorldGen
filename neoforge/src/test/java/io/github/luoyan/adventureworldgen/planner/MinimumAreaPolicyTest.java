package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.plan.PlannedBiomePatch;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The minimum-area policy answers two questions with the same comparison: which patches must be
 * protected during assembly, and whether the final layout still satisfies the author's minimum.
 * These checks pin that comparison, the relaxation it accepts, and the order in which a failing
 * layout reports what came before the failure.
 */
class MinimumAreaPolicyTest {
    private static final long MINIMUM = 4096;
    /** The spawn biome is the required biome, so the expander produces exactly one demand. */
    private static final String SINGLE_DEMAND = """
            {"world":{"radius":512},"spawn":{"biome":"test:forest"},
             "biomes":{"required":[{"id":"test:forest","adventure_level":0,"area":{"min":4096,"target":8192}}],
                       "filler":["test:plains"]}}
            """;
    private static final String TWO_DEMANDS = """
            {"world":{"radius":512},"spawn":{"biome":"test:forest"},
             "biomes":{"required":[{"id":"test:forest","adventure_level":0,"area":{"min":4096,"target":8192}},
                                   {"id":"test:taiga","adventure_level":5,"area":{"min":4096,"target":8192}}],
                       "filler":["test:plains"]}}
            """;

    @Test
    void mixingThatShrankAPatchProtectsItAndMixingThatDidNotDoesNot() {
        var config = parse(TWO_DEMANDS);
        var shrunken = patch(config, 0, -32, -32, 32, 32);
        var kept = patch(config, 1, -32, -32, 32, 32);
        assertTrue(shrunken.area() < MINIMUM, "the fixture must sit below the requested minimum");

        Set<String> protectedIds = MinimumAreaPolicy.protectedPatchIds(config, List.of(shrunken, kept),
                candidate -> candidate == shrunken ? shrunken.area() - 16 : kept.area());

        assertEquals(Set.of(shrunken.patchId()), protectedIds);
        assertFalse(protectedIds.contains(kept.patchId()),
                "a patch that still owns everything it planned needs no protection");
    }

    @Test
    void aRequestWithoutAnyPatchIsReportedAsNoLegalArea() {
        var config = parse(TWO_DEMANDS);
        var planned = patch(config, 0, -32, -32, 32, 32);
        var missingId = new RequirementExpander().expandMinimum(config).patches().get(1).patchId();
        List<MinimumAreaPolicy.Relaxation> relaxations = new ArrayList<>();

        // The measured area is above the request, so the present patch reports nothing at all.
        MinimumAreaPolicy.checkAchievedAreas(config, List.of(planned), candidate -> MINIMUM, relaxations::add);

        assertEquals(List.of(new MinimumAreaPolicy.Relaxation(missingId, MINIMUM, 0, true)), relaxations);
    }

    @Test
    void keepingTheAchievedAreaIsRelaxedAndLosingItFails() {
        var config = parse(SINGLE_DEMAND);
        var planned = patch(config, 0, -32, -32, 32, 32);
        long achieved = planned.area();
        List<MinimumAreaPolicy.Relaxation> relaxations = new ArrayList<>();

        // Mixing shrank nothing: the plan keeps what it achieved, which is below the request.
        MinimumAreaPolicy.checkAchievedAreas(config, List.of(planned), candidate -> achieved, relaxations::add);
        assertEquals(List.of(new MinimumAreaPolicy.Relaxation(planned.patchId(), MINIMUM, achieved, false)), relaxations);

        // Mixing shrank the achieved area: that is the failure the policy must not accept.
        var failure = assertThrows(PlanningFailure.class, () -> MinimumAreaPolicy.checkAchievedAreas(
                config, List.of(planned), candidate -> achieved - 16, ignored -> { }));
        assertEquals(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN, failure.code());
        assertEquals("effective-area", failure.stage());
        assertEquals(achieved - 16, Long.parseLong(failure.diagnostics().get("effective_area")));
        assertEquals(achieved, Long.parseLong(failure.diagnostics().get("achieved_area")));
    }

    @Test
    void aFailureReportsOnlyTheRelaxationsThatCameBeforeIt() {
        var config = parse(TWO_DEMANDS);
        var first = patch(config, 0, -32, -32, 32, 32);
        var second = patch(config, 1, -32, -32, 32, 32);
        List<MinimumAreaPolicy.Relaxation> reported = new ArrayList<>();

        assertThrows(PlanningFailure.class, () -> MinimumAreaPolicy.checkAchievedAreas(config, List.of(first, second),
                candidate -> candidate == first ? first.area() : second.area() - 16, reported::add));

        assertEquals(List.of(new MinimumAreaPolicy.Relaxation(first.patchId(), MINIMUM, first.area(), false)), reported,
                "the sink sees the accepted relaxation and nothing after the failure");
    }

    /** Demand patch ids are generated by the expander, so the fixture asks for them instead of guessing. */
    private static PlannedBiomePatch patch(AdventureWorldConfig config, int demandIndex, int minX, int minZ,
                                           int maxX, int maxZ) {
        var demand = new RequirementExpander().expandMinimum(config).patches().get(demandIndex);
        return new PlannedBiomePatch(demand.patchId(), demand.allowedBiomes().getFirst(), demand.adventureLevel(),
                minX, minZ, maxX, maxZ);
    }

    private static AdventureWorldConfig parse(String json) {
        return new AdventureWorldConfigParser().parse(json);
    }
}
