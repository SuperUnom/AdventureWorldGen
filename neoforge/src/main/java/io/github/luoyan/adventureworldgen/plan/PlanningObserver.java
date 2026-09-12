package io.github.luoyan.adventureworldgen.plan;

import java.util.function.DoubleConsumer;

/**
 * Observation-only progress contract for plan construction.
 *
 * <p>Progress never participates in generation. Implementations must not influence retries,
 * random inputs, traversal order, budgets or fallbacks, and must not terminate a search on
 * wall-clock time. Algorithms report through this interface so they do not read a static UI
 * progress registry: timing, logging and screen updates stay in the outer runtime layer.
 */
public interface PlanningObserver {
    /** Discards every observation; used when constructing a plan without a visible run. */
    PlanningObserver NONE = new PlanningObserver() {};

    /** Enters a stage at zero progress within it. */
    default void stage(PlanningStage stage) {}

    /** Publishes a short human-readable note for the current stage. */
    default void detail(String text) {}

    /** A sink for progress inside one stage, where {@code 0..1} is that stage's own span. */
    default DoubleConsumer within(PlanningStage stage) { return NONE_PROGRESS; }

    DoubleConsumer NONE_PROGRESS = fraction -> {};
}
