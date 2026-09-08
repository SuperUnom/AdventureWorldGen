package io.github.luoyan.adventureworldgen.testcompanion;

import io.github.luoyan.adventureworldgen.planner.VanillaAltitudeSnow;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("testcompanion")
@PrefixGameTestTemplate(false)
public final class NativeClimateGameTests {
    @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty")
    public static void altitudeSnowMatchesNativeTemperateBiomes(GameTestHelper helper) {
        var registry=helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        for(String name:new String[]{"windswept_hills","windswept_forest","windswept_gravelly_hills",
                "taiga","old_growth_spruce_taiga","old_growth_pine_taiga"}) {
            var biome=registry.get(ResourceLocation.withDefaultNamespace(name));
            var id=new io.github.luoyan.adventureworldgen.config.ContentId("minecraft:"+name);
            for(int x=-3000;x<3000;x+=173)for(int z=-3000;z<3000;z+=197)for(int y=80;y<240;y++) {
                var pos=new BlockPos(x,y,z);
                helper.assertTrue(VanillaAltitudeSnow.snowy(id,x,y,z)==biome.coldEnoughToSnow(pos),
                        "altitude snow differs from native "+name+" at "+pos);
            }
        }
        helper.succeed();
    }
}
