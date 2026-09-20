package io.github.luoyan.adventureworldgen.plan;

/**
 * Frozen humidity field: the two distance fields, their water levels, the achieved band shares and
 * the weather offset.
 *
 * <p>Serialized reflectively inside the plan-v3 biome layout. Component names and order are part of
 * the persisted format, and the arrays are defensively copied by the owning planner on both
 * directions of the boundary.
 */
public record HumidityState(int extent,double[] freshDistance,double[] oceanDistance,
                            double[] freshLevel,double[] oceanLevel,double[] actual,double weatherOffset) {}
