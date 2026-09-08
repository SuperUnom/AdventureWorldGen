package io.github.luoyan.adventureworldgen.testcompanion;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Full default-profile planning with real vanilla structure preparation and READY reload. */
@GameTestHolder("testcompanion_planning")
@PrefixGameTestTemplate(false)
public final class PlanningPerformanceGameTests {
    @GameTest(templateNamespace="testcompanion_planning", template="empty", timeoutTicks=2400)
    public static void productionPlanningAndReload(GameTestHelper helper) {
        AdventureWorldGameTests.productionProfilePlansIrregularContinentAtRadius3000(helper);
    }
}
