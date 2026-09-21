package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.plan.ContentId;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Per-profile hard barrier shared by biome and chunk-generation entry points.
 *
 * <p>Keyed by {@link ContentId} rather than a Minecraft resource location: the key is plan
 * vocabulary, and the worldgen boundary converts its serialized profile id once. Nothing in this
 * package imports Minecraft.
 */
public final class RuntimePlanRegistry {
    private static final Map<ContentId, CompletableFuture<AdventurePlanView>> PLANS = new ConcurrentHashMap<>();
    private static final ExecutorService PLANNERS = Executors.newFixedThreadPool(
            StrictMath.max(1, StrictMath.min(4, Runtime.getRuntime().availableProcessors())), runnable -> {
                Thread thread = new Thread(runnable, "adventureworldgen-planner");
                thread.setDaemon(true);
                return thread;
            });
    private RuntimePlanRegistry() {}

    public static CompletableFuture<AdventurePlanView> start(ContentId profile,
                                                              Supplier<AdventurePlanView> planner) {
        return PLANS.computeIfAbsent(profile, ignored -> CompletableFuture.supplyAsync(planner, PLANNERS));
    }

    public static AdventurePlanView await(ContentId profile) {
        CompletableFuture<AdventurePlanView> plan = PLANS.get(profile);
        if (plan == null) throw new IllegalStateException("planning has not started for profile " + profile);
        return plan.join();
    }

    /** Release a closed consumer's plan without removing another profile or a newer publication. */
    public static void release(ContentId profile, AdventurePlanView expected) {
        PLANS.computeIfPresent(profile, (key, future) -> future.isDone() && !future.isCompletedExceptionally()
                && future.getNow(null) == expected ? null : future);
    }

    public static void clear() { PLANS.clear(); PlanningProgress.clear(); }
}
