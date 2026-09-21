package io.github.luoyan.adventureworldgen.testcompanion.mixin;

import net.minecraft.gametest.framework.GameTestServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Mojang's GameTest server disables structures. Enable them only for the real structure pipeline suite. */
@Mixin(GameTestServer.class)
public abstract class StructureTestServerMixin {
    @ModifyArg(method = "<clinit>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/WorldOptions;<init>(JZZ)V"), index = 1)
    private static boolean testcompanion$enableStructures(boolean original) {
        return original || System.getProperty("neoforge.enabledGameTestNamespaces", "").contains("testcompanion_structure");
    }
}
