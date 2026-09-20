package io.github.luoyan.adventureworldgen.plan;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The inventory of random-domain names the planner actually draws from.
 *
 * <p>This is a <em>description of the code</em>, maintained by hand against the call sites and
 * checked by {@code RandomDomainsTest}, which scans the production sources for every string used as
 * a random-domain name and fails when one is missing here. Adding a new domain is therefore a
 * deliberate act: either reuse an existing name or register it.
 *
 * <p>Why it exists: the plan's {@code random_keys} array is historical descriptive metadata,
 * not a manifest - it is written for wire compatibility, never read back, and its names are not the
 * names the code uses. Someone reading it would conclude the random surface is validated when it is
 * not. This class is the honest list; {@code PlanV2Codec} points here.
 *
 * <p><strong>Values are frozen.</strong> Every name here is an input to
 * {@code DeterministicRandom.seed/sample} or a noise-domain label, so changing one changes generated
 * terrain, climate, hydrology or structure placement while leaving every version string untouched.
 * Do not tidy, rename or re-case an entry. The names are also deliberately not merged into a single
 * namespace: a stage name and a salt name may legitimately repeat across packages with different
 * meanings, and unifying them would change the draws.
 *
 * <p>The three kinds are kept apart because they are reached through different functions and carry
 * different consequences:
 *
 * <ul>
 *   <li>{@link #stageDomains()} - the stage argument of {@code DeterministicRandom.seed/sample}.</li>
 *   <li>{@link #seedSalts()} - the {@code field} argument of {@code DeterministicRandom.seed}, which
 *       selects the stream a stage draws from.</li>
 *   <li>{@link #noiseDomains()} - domain labels passed to {@code GradientNoise}, {@code ValueNoise}
 *       and {@code ContinuousDomainWarp}. These are precomputed into the noise's own seed, and
 *       several are built from a prefix and a component, so the test matches them by prefix.</li>
 * </ul>
 */
public final class RandomDomains {
    /**
     * Stage names handed to {@code DeterministicRandom.seed/sample}. Order is not significant here;
     * the inventory is a set, and the draw order lives in the call sites.
     */
    private static final List<String> STAGE_DOMAINS = List.of(
            // Terrain recipes and region composition.
            "terrain", "recipe", "composite", "worley", "cubic",
            // Coast and ocean.
            "coast-r6", "cell", "shelf", "shelf-width", "ocean",
            // Rivers, lakes and erosion.
            "hydrology", "path-growth", "lake", "erosion",
            // Climate.
            "climate", "humidity",
            // Placement, biome allocation and structures.
            "indexed-structure", "biome-seed", "structure", "spawn-structure",
            "region-center", "mountain-range", "filler-biome",
            // Query-time blending.
            "query-blend");

    /**
     * The {@code field} argument of {@code DeterministicRandom.seed}: the salt that separates one
     * stream of a stage from another. Values are load-bearing, not labels.
     */
    private static final List<String> SEED_SALTS = List.of(
            "terrain-r5", "terrain-r21", "ftf-noise", "gradient", "worley", "cubic", "cell", "composite",
            "coast-r6", "mountain-range", "recipe", "region-center", "path-growth");

    /**
     * Domain labels of the noise objects. Several call sites build the label from a prefix plus a
     * component (an octave index, an axis, an id), so a prefix here also admits its derived names.
     */
    private static final List<String> NOISE_DOMAINS = List.of(
            "ftf/coast/x/", "ftf/coast/z/",
            "coast/fractal-shear/",
            "ocean/basins", "ocean/detail", "ocean/hills", "ocean/shelf-width",
            "region-warp/x", "region-warp/z",
            "terrain/composite",
            "climate/region", "climate/detail", "climate/warp-x", "climate/warp-z", "climate/foothills",
            "humidity/region", "humidity/detail", "humidity/shore",
            "river/reaches", "river/bars", "river/banks", "river/bank-detail",
            "filler/frontier", "filler/boundary",
            "growth/",
            "query-blend/coarse-x", "query-blend/coarse-z", "query-blend/detail-x", "query-blend/detail-z",
            "temperature-preview/organic/climate", "temperature-preview/organic/detail-x",
            "temperature-preview/organic/detail-z", "temperature-preview/organic/secondary",
            "temperature-preview/organic/warp-x", "temperature-preview/organic/warp-z");

    public static List<String> stageDomains() { return STAGE_DOMAINS; }

    public static List<String> seedSalts() { return SEED_SALTS; }

    public static List<String> noiseDomains() { return NOISE_DOMAINS; }

    /** Every registered name across the three kinds, for duplicate and lookup checks. */
    public static Set<String> all() {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(STAGE_DOMAINS);
        all.addAll(SEED_SALTS);
        all.addAll(NOISE_DOMAINS);
        return Set.copyOf(all);
    }

    /** Whether {@code literal} is a registered stage name or a registered noise-domain prefix. */
    public static boolean covers(String literal) {
        if (STAGE_DOMAINS.contains(literal) || SEED_SALTS.contains(literal) || NOISE_DOMAINS.contains(literal)) {
            return true;
        }
        return NOISE_DOMAINS.stream().anyMatch(domain -> domain.endsWith("/") && literal.startsWith(domain))
                || STAGE_DOMAINS.stream().anyMatch(domain -> domain.endsWith("/") && literal.startsWith(domain));
    }

    private RandomDomains() {}
}
