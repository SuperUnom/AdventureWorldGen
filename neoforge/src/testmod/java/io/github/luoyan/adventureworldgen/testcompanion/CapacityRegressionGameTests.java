package io.github.luoyan.adventureworldgen.testcompanion;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The reported snowy-taiga capacity-search crash, with real Minecraft structure adapters. */
@GameTestHolder("testcompanion_capacity")
@PrefixGameTestTemplate(false)
public final class CapacityRegressionGameTests {
    @GameTest(templateNamespace="testcompanion_capacity",template="empty",timeoutTicks=2400)
    public static void seed7993PlansAndReloadsWithoutSearchExhaustion(GameTestHelper helper) {
        AdventureWorldGameTests.productionProfilePlansAndReloads(helper,7993,"production-capacity-7993");
    }
}
