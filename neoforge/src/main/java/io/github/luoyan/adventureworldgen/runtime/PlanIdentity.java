package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.api.AdapterRegistry;
import io.github.luoyan.adventureworldgen.config.LoadedProfile;
import io.github.luoyan.adventureworldgen.persistence.AtomicPlanRepository;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;

import java.nio.charset.StandardCharsets;
import io.github.luoyan.adventureworldgen.plan.PlanVersions;

/**
 * The identity of a planning run: everything that decides whether a stored READY plan still
 * describes the world this session is about to generate.
 *
 * <p>It is deliberately separate from the stage orchestrator because changing it is a format
 * decision, not a scheduling one: a plan is reloaded only when this hash matches, so every input
 * that can change generation must appear here. Author configuration enters through the canonical
 * profile JSON, which is why a config edit invalidates the plan.
 *
 * <p>The profile is a parameter, not a fixed {@code PlannerProfile.V2} reference: the algorithm and
 * hydrology identities recorded here must be the ones that planned the world. A profile that
 * changes a resource budget without changing at least one of those identities would produce the
 * same hash for different planning parameters, so an experimental budget must carry its own
 * version marker - the two travel together by design.
 */
public final class PlanIdentity {
    /** Internal cache key revision; the public planner remains v2 while the payload is plan-v3. */
    public static final String IMPLEMENTATION_REVISION = "planner-v2-impl-2026-09-21-nested-road-connections-r39";

    private PlanIdentity() {}

    /**
     * Canonical input fingerprint of one planning run, recorded as {@code input_sha256} in the plan.
     *
     * <p>Order is fixed and each contribution is named, so a value change is visible in review.
     * Adapter versions are sorted by the registry before they are joined.
     */
    public static String hash(long seed, LoadedProfile loaded, AdapterRegistry adapters, PlannerProfile profile) {
        String input = loaded.canonicalJson() + "\nseed=" + seed + "\nalgorithm=" + profile.algorithmVersion()
                + "\nstructure_planning=" + loaded.structurePlanning().canonicalIdentity()
                + "\nimplementation=" + IMPLEMENTATION_REVISION
                + "\nhydrology=" + profile.hydrologyVersion() + "\nterrain=" + PlanVersions.TERRAIN + "\nadapters="
                + String.join(",", adapters.versionKeys()) + "\ncost=directed-cost-16x8-v1\nerosion=ftf-erosion-block-units-v2";
        return AtomicPlanRepository.sha256(input.getBytes(StandardCharsets.UTF_8));
    }
}
