package io.github.luoyan.adventureworldgen;

import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import io.github.luoyan.adventureworldgen.worldgen.ModWorldgen;

@Mod(AdventureWorldGen.MOD_ID)
public final class AdventureWorldGen {
    public static final String MOD_ID = "adventureworldgen";

    public AdventureWorldGen(IEventBus modBus) {
        ModWorldgen.register(modBus);
    }
}
