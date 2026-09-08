package io.github.luoyan.adventureworldgen.client;

import io.github.luoyan.adventureworldgen.AdventureWorldGen;
import io.github.luoyan.adventureworldgen.runtime.PlanningProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Integrated-server planning runs before spawn chunks; render it in the existing loading screen. */
@EventBusSubscriber(modid = AdventureWorldGen.MOD_ID, value = Dist.CLIENT)
public final class PlanningLoadingOverlay {
    private PlanningLoadingOverlay() {}
    @SubscribeEvent
    public static void render(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof LevelLoadingScreen)) return;
        var progress = PlanningProgress.current();
        if (progress == null || progress.status() == PlanningProgress.Status.READY) return;
        event.setCanceled(true);
        var screen = event.getScreen();
        var graphics = event.getGuiGraphics();
        var font = Minecraft.getInstance().font;
        screen.renderBackground(graphics, event.getMouseX(), event.getMouseY(), event.getPartialTick());
        int width = Math.min(280, screen.width - 32), left = (screen.width - width) / 2;
        int center = screen.width / 2, top = screen.height / 2 - 30;
        graphics.drawCenteredString(font, Component.translatable("adventureworldgen.planning.title"), center, top, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable(progress.stage().translationKey()), center, top + 19, 0xD6E6DA);
        graphics.fill(left, top + 39, left + width, top + 51, 0xFF16261E);
        graphics.fill(left + 1, top + 40, left + width - 1, top + 50, 0xFF33483C);
        int fill = (width - 2) * progress.percent() / 100;
        int color = progress.status() == PlanningProgress.Status.FAILED ? 0xFFD86565 : 0xFF78C98F;
        graphics.fill(left + 1, top + 40, left + 1 + fill, top + 50, color);
        graphics.drawCenteredString(font, Component.translatable("adventureworldgen.planning.progress", progress.percent()),
                center, top + 61, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("adventureworldgen.planning.elapsed", progress.elapsedSeconds()),
                center, top + 77, 0xBCCABE);
        if (!progress.detail().isEmpty()) graphics.drawCenteredString(font, Component.literal(progress.detail()), center, top + 113, 0xE7C981);
        if (progress.status() == PlanningProgress.Status.FAILED)
            graphics.drawCenteredString(font, Component.translatable("adventureworldgen.planning.failed"), center, top + 98, 0xFF9090);
    }
}
