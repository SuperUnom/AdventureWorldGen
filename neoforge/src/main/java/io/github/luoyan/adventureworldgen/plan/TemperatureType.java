package io.github.luoyan.adventureworldgen.plan;

import java.util.Map;

/**
 * Temperature classification shared by the author model, the frozen climate state and the
 * environment rules.
 *
 * <p>It lives in {@code plan} because the persisted layout stores it as part of the climate state
 * and because {@code config} already depends on {@code plan}. Leaving it inside
 * {@code AdventureWorldConfig} would force the frozen state types to depend on the author model,
 * which is exactly the edge the plan package must not have.
 */
public enum TemperatureType {
    VERY_COLD, COLD, MEDIUM, HOT;
    public static TemperatureType fromLevel(int level) { return level <= 1 ? VERY_COLD : level <= 3 ? COLD : level >= 7 ? HOT : MEDIUM; }
    public static Map<TemperatureType, Double> unrestricted() {
        return Map.of(VERY_COLD,1.0,COLD,1.0,MEDIUM,1.0,HOT,1.0);
    }
}
