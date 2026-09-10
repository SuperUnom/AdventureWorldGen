package io.github.luoyan.adventureworldgen.plan;

/**
 * Frozen filler ownership grid: one label per filler cell plus the seed count it was grown from.
 *
 * <p>Serialized reflectively inside the plan-v2 biome layout. Component names and order are part of
 * the persisted format; a READY reload restores this grid instead of growing the layout again.
 */
public record FillerState(int extent,int[] labels,int seedCount) {}
