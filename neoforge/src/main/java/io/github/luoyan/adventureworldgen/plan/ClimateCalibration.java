package io.github.luoyan.adventureworldgen.plan;

/** Frozen macro choice and smooth spawn-core correction; independent of query/cache order. */
public record ClimateCalibration(double offsetX,double offsetZ,double temperatureBias,double humidityBias,
                                 double spawnTemperatureDelta,double spawnHumidityDelta) {
    public static final ClimateCalibration IDENTITY = new ClimateCalibration(0,0,0,0,0,0);
    public ClimateCalibration {
        for(double v:new double[]{offsetX,offsetZ,temperatureBias,humidityBias,spawnTemperatureDelta,spawnHumidityDelta})
            if(!Double.isFinite(v))throw new IllegalArgumentException("nonfinite climate calibration");
        if(Math.abs(temperatureBias)>2||Math.abs(humidityBias)>.3||Math.abs(spawnTemperatureDelta)>10
                ||Math.abs(spawnHumidityDelta)>1)throw new IllegalArgumentException("invalid climate calibration");
    }
}
