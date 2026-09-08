package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Per-profile hard barrier shared by biome and chunk-generation entry points. */
public final class RuntimePlanRegistry {
    private static final Map<ResourceLocation, CompletableFuture<AdventurePlanView>> PLANS = new ConcurrentHashMap<>();
    private static final ExecutorService PLANNERS = Executors.newFixedThreadPool(
            StrictMath.max(1, StrictMath.min(4, Runtime.getRuntime().availableProcessors())), runnable -> {
                Thread thread = new Thread(runnable, "adventureworldgen-planner");
                thread.setDaemon(true);
                return thread;
            });
    private RuntimePlanRegistry() {}

    public static CompletableFuture<AdventurePlanView> start(ResourceLocation profile,
                                                              Supplier<AdventurePlanView> planner) {
        return PLANS.computeIfAbsent(profile, ignored -> CompletableFuture.supplyAsync(planner, PLANNERS));
    }

    public static AdventurePlanView await(ResourceLocation profile) {
        CompletableFuture<AdventurePlanView> plan = PLANS.get(profile);
        if (plan == null) throw new IllegalStateException("planning has not started for profile " + profile);
        return plan.join();
    }

    public static void clear() { PLANS.clear(); PlanningProgress.clear(); }
}
