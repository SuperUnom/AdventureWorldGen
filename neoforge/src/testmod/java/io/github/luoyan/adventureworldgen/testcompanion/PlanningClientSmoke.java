package io.github.luoyan.adventureworldgen.testcompanion;

import io.github.luoyan.adventureworldgen.runtime.PlanningProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Opt-in executable UI smoke test; never packaged with the mod. */
@EventBusSubscriber(modid = "testcompanion", value = Dist.CLIENT)
public final class PlanningClientSmoke {
    private static boolean captured, finished;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(PlanningClientSmoke.class);
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void rendered(ScreenEvent.Render.Pre event) {
        if (!Boolean.getBoolean("adventureworldgen.planningSmoke") || captured
                || !(event.getScreen() instanceof LevelLoadingScreen)) return;
        var progress = PlanningProgress.current();
        if (progress == null || progress.status() != PlanningProgress.Status.RUNNING || progress.percent() < 10) return;
        if (!event.isCanceled()) throw new AssertionError("planning overlay did not replace the loading display");
        event.getGuiGraphics().flush();
        captured = true;
        var minecraft = Minecraft.getInstance();
        Screenshot.grab(minecraft.gameDirectory, "planning-r8.png", minecraft.getMainRenderTarget(),
                message -> LOGGER.info("Planning UI capture: {}", message.getString()));
        LOGGER.info("Planning UI visible at {} percent, stage {}", progress.percent(), progress.stage());
    }
    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("adventureworldgen.planningSmoke") || finished) return;
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;
        if (!captured) throw new AssertionError("entered world without rendering planning progress");
        if (PlanningProgress.current().status() != PlanningProgress.Status.READY)
            throw new AssertionError("entered world before plan READY");
        finished = true;
        LOGGER.info("Planning UI smoke passed: progress rendered and integrated world entered");
        minecraft.stop();
    }
}
