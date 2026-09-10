package io.github.luoyan.adventureworldgen.plan;

/**
 * Canonical version identities that shape a persisted plan.
 *
 * <p>These strings are contract names, not revision counters: they participate in
 * {@code runtime.PlanIdentity.hash} and in the {@code plan-v2} header, so a value change
 * invalidates existing READY plans and must be a reviewed format decision. They live here so
 * version information is maintained in one place and the domain packages can depend on it
 * instead of the other way round.
 */
public final class PlanVersions {
    /** Hydrology geometry/morphology version, consumed by the plan header and the input hash. */
    public static final String HYDROLOGY = "ftf-hydrology-adapted-v2";

    private PlanVersions() {}
}
