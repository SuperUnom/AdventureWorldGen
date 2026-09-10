package io.github.luoyan.adventureworldgen.climate;

import com.google.gson.Gson;
import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import io.github.luoyan.adventureworldgen.plan.ClimateState;
import io.github.luoyan.adventureworldgen.climate.ClimatePlan;
import io.github.luoyan.adventureworldgen.climate.OrganicTemperatureField;

class OrganicTemperatureFieldTest {
    @Test void matchesAcceptedV7PreviewSamples() {
        // Golden values from the accepted v7 fields.bin, not recomputed by the production formula.
        // Effective heights reconstructed from float cooling samples permit 1e-6 rounding error.
        double[][] samples={
                {-1198.222222222222,-1447.111111111111,205.018614138090,1.182686686516},
                {3.555555555556,-1198.222222222222,216.700948451116,0},
                {650.666666666667,-899.555555555556,219.245520254282,.808829724789},
                {-1596.444444444444,-800,122.935535606971,4.814715385437},
                {202.666666666667,-302.222222222222,90.581172416608,9.138573646545},
                {3.555555555556,3.555555555556,94.044892698526,5.384471893311},
                {-1098.666666666667,650.666666666667,105.445032278697,4.753783702850},
                {1098.666666666667,999.111111111111,90.433770130078,4.730640411377}};
        var field=new OrganicTemperatureField(7331);
        for(var s:samples)assertEquals(s[3],field.temperature(s[0],s[1],s[2]),1e-6);
        assertEquals(.408,OrganicTemperatureField.cooling(110),1e-12);
        assertEquals(6.908,OrganicTemperatureField.cooling(210),1e-12);
    }
    private AdventureWorldConfig config() {
        return new AdventureWorldConfigParser().parse("""
          {"world":{"radius":256},"spawn":{"biome":"test:a"},"biomes":{"filler":["test:a"]}}
          """);
    }
    private static final MacroTerrain TERRAIN=(x,z)->new MacroSample(
            80+150*Math.exp(-x*x/(80.0*80)),Double.NaN,WaterKind.NONE,false,"r","mountains","test");

    @Test void productionUsesFixedFieldIncludingSpawnAndRestoresWithoutTerrainSampling() {
        var c=config();var original=new ClimatePlan(7331,c,TERRAIN,new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(c,ClimatePlan.STEP));var field=new OrganicTemperatureField(7331);
        var json=new Gson();String encoded=json.toJson(original.snapshot());
        var restored=new ClimatePlan(7331,c,(x,z)->{throw new AssertionError("reload sampled terrain");},v->{},
                json.fromJson(encoded,ClimateState.class),new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(c,ClimatePlan.STEP));
        assertEquals(OrganicTemperatureField.VERSION,restored.snapshot().temperatureField());
        assertArrayEquals(new double[]{2.5,5,7.5},restored.snapshot().thresholds());
        assertTrue(restored.snapshot().corrections().isEmpty());
        for(double z:new double[]{-190,-2,0,2,65.5,190})for(double x:new double[]{-190,-2,0,2,65.5,190}) {
            var sample=TERRAIN.sample(x,z);double value=original.valueAt(x,z,sample);
            assertEquals(field.temperature(x,z,original.effectiveHeightAt(x,z,sample)),value);
            assertEquals(value,restored.valueAt(x,z,sample));
            assertEquals(original.humidity().valueAt(x,z,sample),restored.humidity().valueAt(x,z,sample));
        }
    }
    @Test void legacyStatesRemainLegacyAndUnknownOrModifiedNewStatesAreRejected() {
        var c=config();var original=new ClimatePlan(7331,c,TERRAIN,new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(c,ClimatePlan.STEP));var gson=new Gson();
        var tree=gson.toJsonTree(original.snapshot()).getAsJsonObject();
        tree.remove("temperatureField");
        var legacy=new ClimatePlan(7331,c,(x,z)->{throw new AssertionError();},v->{},gson.fromJson(tree,ClimateState.class),new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(c,ClimatePlan.STEP));
        assertNull(legacy.snapshot().temperatureField());
        var again=new ClimatePlan(7331,c,TERRAIN,v->{},gson.fromJson(gson.toJson(legacy.snapshot()),ClimateState.class),new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(c,ClimatePlan.STEP));
        assertEquals(legacy.valueAt(150,100,TERRAIN.sample(150,100)),again.valueAt(150,100,TERRAIN.sample(150,100)));
        tree.addProperty("temperatureField","unknown-version");
        assertThrows(IllegalArgumentException.class,()->new ClimatePlan(7331,c,TERRAIN,v->{},gson.fromJson(tree,ClimateState.class),new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(c,ClimatePlan.STEP)));
        tree.addProperty("temperatureField",OrganicTemperatureField.VERSION);
        tree.getAsJsonArray("thresholds").set(0,new com.google.gson.JsonPrimitive(2));
        assertThrows(IllegalArgumentException.class,()->new ClimatePlan(7331,c,TERRAIN,v->{},gson.fromJson(tree,ClimateState.class),new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(c,ClimatePlan.STEP)));
    }
}
