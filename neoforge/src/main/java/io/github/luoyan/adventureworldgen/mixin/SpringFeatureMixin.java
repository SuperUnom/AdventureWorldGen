package io.github.luoyan.adventureworldgen.mixin;

import io.github.luoyan.adventureworldgen.worldgen.MountainSpringFilter;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.SpringFeature;
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SpringFeature.class)
public abstract class SpringFeatureMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void adventureworldgen$filterMountainWaterfall(FeaturePlaceContext<SpringConfiguration> context,
                                                          CallbackInfoReturnable<Boolean> result) {
        if (MountainSpringFilter.suppress(context)) result.setReturnValue(false);
    }
}
