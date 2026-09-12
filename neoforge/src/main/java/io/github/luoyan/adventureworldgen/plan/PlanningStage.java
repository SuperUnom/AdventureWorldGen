package io.github.luoyan.adventureworldgen.plan;

/**
 * Ordered construction phases of a plan.
 *
 * <p>The declared order is meaningful: progress is clamped so a later phase never reports a
 * lower percentage than an earlier one. Constant names map to UI translation keys
 * ({@code adventureworldgen.planning.<lowercase name>}) and must not be renamed without
 * updating the language files.
 */
public enum PlanningStage {
    CACHE(0, 2), COAST(2, 8), EROSION(8, 46), RIVERS(46, 55), COSTS(55, 75), PLACEMENT(75, 78), TEMPERATURE(78, 80), HUMIDITY(80, 81), SEEDS(81, 84), GROWTH(84, 89), STRUCTURES(89, 90), FILLER(90, 94), TRANSITION(94, 95), VALIDATION(95, 98), SAVE(98, 100);

    private final int start, end;

    PlanningStage(int start, int end) {
        this.start = start;
        this.end = end;
    }

    /** Inclusive lower bound of this stage's share of the whole progress bar. */
    public int start() { return start; }

    /** Upper bound of this stage's share of the whole progress bar. */
    public int end() { return end; }

    public String translationKey() { return "adventureworldgen.planning." + name().toLowerCase(java.util.Locale.ROOT); }
}
