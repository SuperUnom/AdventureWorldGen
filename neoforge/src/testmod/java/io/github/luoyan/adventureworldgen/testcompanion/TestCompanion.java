package io.github.luoyan.adventureworldgen.testcompanion;

import io.github.luoyan.adventureworldgen.api.AdapterRegistrations;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Deliberately isolated from the production JAR. */
@Mod(TestCompanion.ID)
public final class TestCompanion {
    public static final String ID = "testcompanion";

    public TestCompanion(IEventBus modBus) {
        AdapterRegistrations.register(TestCompanionAdapters.ASHEN_GROVE);
        modBus.addListener(TestCompanion::registerGameTests);
    }

    private static void registerGameTests(RegisterGameTestsEvent event) {
        event.register(AdventureWorldGameTests.class);
    }
}
