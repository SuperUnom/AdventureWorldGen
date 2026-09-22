package io.github.luoyan.adventureworldgen.plan;

/**
 * Demand and supply accounting for one required biome, recorded when the plan was built.
 *
 * <p>Diagnostic data only: nothing reads it back to shape a field or a search decision. It is
 * frozen plan data and is serialized reflectively inside the plan-v3 biome layout, so the component
 * names and order are part of the persisted format.
 */
public record ClimateSupply(String biome,long target,long legalArea,long climateArea,
                            long jointArea,long lowlandLegalArea,long lowlandColdArea,String diagnosis) {
    public ClimateSupply(String biome,long target,long legalArea,long climateArea) {
        this(biome,target,legalArea,climateArea,climateArea,0,0,"TEMPERATURE_ONLY");
    }
}
