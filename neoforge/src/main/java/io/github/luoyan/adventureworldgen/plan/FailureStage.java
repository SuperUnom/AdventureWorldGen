package io.github.luoyan.adventureworldgen.plan;

import java.util.Locale;
import java.util.Optional;

/**
 * The stable vocabulary of failure stages.
 *
 * <p>A {@link PlanningFailure} carries three separate things and they must not be conflated: the
 * {@link PlanningFailure.Code code} is the machine classification (resource limit, no solution,
 * unsupported content...), this type is the <em>location</em>, and the failure message is the
 * human-readable wording. Automation used to match on the free-text message to find out where a run
 * failed, which broke as soon as the wording changed.
 *
 * <p>Failure stages are finer-grained than {@link PlanningStage}, and that is deliberate: progress
 * broadcasts one coarse bar position while a diagnosis needs the exact step. The two namespaces
 * stay separate and are connected by {@link #progressStage()}, an explicit many-to-one mapping.
 *
 * <p>{@link #of(String)} never throws and never depends on enum ordinals: an unrecognised stage -
 * one written by a newer build, or read back from an older log - becomes {@link #UNKNOWN} with a
 * defined "not applicable" mapping, so a reader has a fallback instead of a crash or a wrong guess.
 */
public enum FailureStage {
    ADAPTER_REGISTRATION("adapter-registration", null),
    CONTENT_PREFLIGHT("content-preflight", null),
    REQUIREMENTS("requirements", PlanningStage.PLACEMENT),
    PLACEMENT_INDEX("placement-index", PlanningStage.PLACEMENT),
    CANDIDATE_REFINEMENT("candidate-refinement", PlanningStage.PLACEMENT),
    ADVENTURE_LEVELS("adventure-levels", PlanningStage.COSTS),
    COAST("coast", PlanningStage.COAST),
    TERRAIN_CAPACITY("terrain-capacity", PlanningStage.COAST),
    EROSION("erosion", PlanningStage.EROSION),
    HYDROLOGY("hydrology", PlanningStage.RIVERS),
    COST_GRAPH("cost-graph", PlanningStage.COSTS),
    CLIMATE("climate", PlanningStage.TEMPERATURE),
    HUMIDITY("humidity", PlanningStage.HUMIDITY),
    BIOME_SEED("biome-seed", PlanningStage.SEEDS),
    COMPETITIVE_GROWTH("competitive-growth", PlanningStage.GROWTH),
    AREA_CAPACITY("area-capacity", PlanningStage.PLACEMENT),
    AREA_ASSIGNMENT("area-assignment", PlanningStage.PLACEMENT),
    EFFECTIVE_AREA("effective-area", PlanningStage.PLACEMENT),
    STRUCTURE_CANDIDATES("structure-candidates", PlanningStage.STRUCTURES),
    STRUCTURE_IN_BIOME("structure-in-biome", PlanningStage.STRUCTURES),
    JOINT_PLACEMENT("joint-placement", PlanningStage.PLACEMENT),
    SPAWN_CORE("spawn-core", PlanningStage.PLACEMENT),
    SPAWN_RESERVATION("spawn-reservation", PlanningStage.PLACEMENT),
    SPAWN_CANDIDATE("spawn-candidate", PlanningStage.PLACEMENT),
    SPAWN_RESOLUTION("spawn-resolution", PlanningStage.PLACEMENT),
    FILLER("filler", PlanningStage.FILLER),
    ROADS("roads", PlanningStage.ROADS),
    FINAL_VALIDATION("final-validation", PlanningStage.VALIDATION),
    PLAN_LOAD("plan-load", PlanningStage.CACHE),
    /** A stage string this build does not know. Never a silent alias for a real stage. */
    UNKNOWN("unknown", null);

    private final String id;
    private final PlanningStage progress;

    FailureStage(String id, PlanningStage progress) {
        this.id = id;
        this.progress = progress;
    }

    /** The stable string written into diagnostics; unchanged from the literals it replaces. */
    public String id() { return id; }

    /**
     * The progress stage this failure belongs to, or empty when the failure happens outside the
     * progress bar entirely (registration and content preflight run before planning starts).
     */
    public Optional<PlanningStage> progressStage() { return Optional.ofNullable(progress); }

    /** The counterexample to {@link #progressStage()}: this stage is explicitly not on the bar. */
    public boolean outsideProgress() { return progress == null; }

    /**
     * Looks up a stage by its stable string. Always succeeds: an unknown value maps to
     * {@link #UNKNOWN}, so callers get a defined fallback rather than an exception or an ordinal.
     */
    public static FailureStage of(String id) {
        if (id != null) {
            String normalised = id.trim().toLowerCase(Locale.ROOT);
            for (FailureStage stage : values()) {
                if (stage != UNKNOWN && stage.id.equals(normalised)) return stage;
            }
        }
        return UNKNOWN;
    }
}
