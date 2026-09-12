package io.github.luoyan.adventureworldgen.terrain;

/**
 * Answers the one question about author filler rules that the region sampler needs: may these two
 * recipes meet inside a single filler biome?
 *
 * <p>The author profile owns the decision, but the sampler only needs the answer. Passing a policy
 * instead of the whole {@code AdventureWorldConfig} is what keeps terrain from depending on the
 * author model, which in turn was the last package cycle in the project.
 */
@FunctionalInterface
public interface FillerTerrainPolicy {
    boolean sharesFiller(TerrainTemplate first, TerrainTemplate second);

    /** Used when the author declared no filler rules: only recipes of the same category may meet. */
    FillerTerrainPolicy CATEGORY_ONLY = (first, second) -> first.category().equals(second.category());
}
