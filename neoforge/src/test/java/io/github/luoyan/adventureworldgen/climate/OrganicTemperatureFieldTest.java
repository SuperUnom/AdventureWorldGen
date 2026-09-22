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
    @Test void lowlandsContainVeryColdAndAltitudeOnlyAddsCooling() {
        var field=new OrganicTemperatureField(7331);int cold=0,warm=0;
        for(int z=-3000;z<=3000;z+=32)for(int x=-3000;x<=3000;x+=32) {
            double low=field.temperature(x,z,76),high=field.temperature(x,z,160);
            assertTrue(high<=low);if(low<2.5)cold++;if(low>=7.5)warm++;
        }
        assertTrue(cold>100,"lowland cold must have area, not isolated samples");assertTrue(warm>100);
        assertEquals(.408,OrganicTemperatureField.cooling(110),1e-12);
    }
    private AdventureWorldConfig config() {
        return new AdventureWorldConfigParser().parse("""
          {"world":{"radius":256},"spawn":{"biome":"test:a"},"biomes":{"filler":["test:a"]}}
          """);
    }
    private static final MacroTerrain TERRAIN=(x,z)->new MacroSample(
            80+150*Math.exp(-x*x/(80.0*80)),Double.NaN,WaterKind.NONE,false,"r","mountains","test");

    @Test void productionFreezesSupplyCalibrationAndRestoresWithoutResolvingIt() {
        var c=config();var original=new ClimatePlan(7331,c,TERRAIN,new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(c,ClimatePlan.STEP));var field=new OrganicTemperatureField(7331);
        var json=new Gson();String encoded=json.toJson(original.snapshot());
        var queries=new java.util.concurrent.atomic.AtomicInteger();
        var restored=new ClimatePlan(7331,c,(x,z)->{queries.incrementAndGet();return TERRAIN.sample(x,z);},v->{},
                json.fromJson(encoded,ClimateState.class),new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(c,ClimatePlan.STEP));
        assertEquals(0,queries.get(),"restore must not resolve climate or resample the continent");
        assertEquals(OrganicTemperatureField.VERSION,restored.snapshot().temperatureField());
        assertArrayEquals(new double[]{2.5,5,7.5},restored.snapshot().thresholds());
        assertTrue(restored.snapshot().corrections().isEmpty());
        for(double z:new double[]{-190,-2,0,2,65.5,190})for(double x:new double[]{-190,-2,0,2,65.5,190}) {
            var sample=TERRAIN.sample(x,z);double value=original.valueAt(x,z,sample);
            assertTrue(value>=0&&value<=10);
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
