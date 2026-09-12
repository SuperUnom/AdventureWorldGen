package io.github.luoyan.adventureworldgen.plan;

/**
 * One additive temperature correction of the legacy climate field.
 *
 * <p>Frozen plan data: it is serialized reflectively as part of the plan-v2 biome layout, so the
 * component names and order are part of the persisted format.
 */
public record ClimateCorrection(double x,double z,double radius,double delta) {}
