package io.github.luoyan.adventureworldgen.plan;

import java.util.List;

/**
 * Frozen climate state: the two blurred height fields, the band thresholds, the spawn band, the
 * recorded demand/actual shares, the legacy corrections and the frozen humidity field.
 *
 * <p>Serialized reflectively inside the plan-v3 biome layout, so the component names and order are
 * part of the persisted format. The components deliberately stay flat ({@code ratios} /
 * {@code actual} / {@code supply}) rather than nesting diagnostic objects, because that is the
 * existing wire shape.
 */
public record ClimateState(int extent, double[] slopeHeight, double[] regionalHeight, double angle,
                    double low, double high, double[] thresholds, boolean snowBoundary,
                    TemperatureType spawnType, double[] ratios, double[] actual,
                    List<ClimateCorrection> corrections, List<ClimateSupply> supply, HumidityState humidity, String temperatureField) {
    /** Source-compatible constructor for legacy plan states without an explicit temperature version. */
    public ClimateState(int extent,double[] slopeHeight,double[] regionalHeight,double angle,double low,double high,
                 double[] thresholds,boolean snowBoundary,TemperatureType spawnType,double[] ratios,double[] actual,
                 List<ClimateCorrection> corrections,List<ClimateSupply> supply,HumidityState humidity) {
        this(extent,slopeHeight,regionalHeight,angle,low,high,thresholds,snowBoundary,spawnType,ratios,actual,
                corrections,supply,humidity,null);
    }
}
