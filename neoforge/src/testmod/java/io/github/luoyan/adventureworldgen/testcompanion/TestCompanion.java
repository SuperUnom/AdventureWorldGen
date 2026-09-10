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
        // The piece type goes through the game's own registration phase, and the adapter through the
        // public registration contract, so neither needs a change in the production mod.
        WaystationPiece.register(modBus);
        AdapterRegistrations.register(TestCompanionAdapters.ASHEN_GROVE);
        AdapterRegistrations.register(TestCompanionAdapters.WAYSTATION);
        modBus.addListener(TestCompanion::registerGameTests);
        // The normal-world acceptance only acts when the audit system property is set.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(WaystationAudit::onServerStarted);
    }

    private static void registerGameTests(RegisterGameTestsEvent event) {
        event.register(AdventureWorldGameTests.class);
    }
}
