package io.github.luoyan.adventureworldgen.hydrology;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RiverMorphologyTest {
    @Test void tributaryDischargeWidensReceivingReachGradually() {
        var shape = new HydrologyProfile.RiverShape(5, 2, 6, 20, 12, 0.75);
        var parent = new RiverNetwork.Channel("parent", 0, null, List.of(new Vec2(0, 0), new Vec2(1000, 0)),
                List.of(0.0, 1000.0), List.of(100.0, 100.0), shape, null);
        var branch = new RiverNetwork.Channel("branch", 1, "parent", List.of(new Vec2(500, 1000), new Vec2(500, 0)),
                List.of(0.0, 1000.0), List.of(100.0, 100.0), shape, null);
        var morphology = new RiverMorphology(new RiverNetwork(List.of(parent, branch), List.of(), RiverMorphology.VERSION));
        // Hold the sediment field fixed to isolate the effect of incoming discharge.
        double upstream = morphology.bedRadius(parent, 0.40, 500, 0, 500, 0);
        double downstream = morphology.bedRadius(parent, 0.60, 500, 0, 500, 0);
        assertTrue(downstream > upstream * 1.4, "tributary did not enlarge its receiving river");
        double previous = upstream;
        for (double along = 0.401; along <= 0.60; along += 0.001) {
            double width = morphology.bedRadius(parent, along, 500, 0, 500, 0);
            assertTrue(width >= previous && width - previous < 0.3, "abrupt width step at confluence");
            previous = width;
        }
    }

    @Test void rasterizedRiverHasNarrowAndBroadReachesAndDifferentBanks() {
        MacroTerrain land = (x, z) -> new MacroSample(120, Double.NaN, WaterKind.NONE, false, "test", "plains", "test");
        var channel = new RiverNetwork.Channel("broad", 0, null, List.of(new Vec2(-1000, 0), new Vec2(1000, 0)),
                List.of(0.0, 2000.0), List.of(100.0, 100.0), new HydrologyProfile.RiverShape(8, 2, 6, 20, 30, 0.75), null);
        var terrain = new HydrologyTerrain(land, new RiverNetwork(List.of(channel), List.of(), RiverMorphology.VERSION));
        int min = 1000, max = 0, asymmetric = 0, edgeTurns = 0, previousEdge = 0, previousDelta = 0;
        for (int x = -900; x <= 900; x += 2) {
            int left = 0, right = 0;
            for (int z = 0; z < 60; z++) {
                if (terrain.sample(x + 0.5, -z - 0.5).wet()) left++;
                if (terrain.sample(x + 0.5, z + 0.5).wet()) right++;
            }
            min = Math.min(min, left + right); max = Math.max(max, left + right);
            if (Math.abs(left - right) >= 2) asymmetric++;
            int delta = right - previousEdge;
            if (delta != 0) { if (delta * previousDelta < 0) edgeTurns++; previousDelta = delta; }
            previousEdge = right;
            assertTrue(terrain.sample(x + 0.5, 0).waterDepth() >= 2, "channel disconnected");
        }
        assertTrue(max >= 60 && max >= min * 2, "missing broad and narrow reaches: " + min + ".." + max);
        assertTrue(asymmetric > 40, "banks still mirror each other");
        assertTrue(edgeTurns > 20, "bank edge lacks local coves: " + edgeTurns);
        for (int x : new int[]{-768, -512, -256, 0, 256, 512, 768}) for (int z = -60; z <= 60; z++)
            assertEquals(terrain.sample(x - 0.0001, z).groundSurface(), terrain.sample(x + 0.0001, z).groundSurface(), 0.01);
    }

    @Test void headwaterWidensAndDeepensGraduallyInsteadOfStartingWithABluntCap() {
        var shape = new HydrologyProfile.RiverShape(6,2,6,20,18,.75);
        var channel = new RiverNetwork.Channel("headwater",0,null,
                List.of(new Vec2(0,0),new Vec2(400,0)),List.of(0.0,400.0),List.of(100.0,90.0),shape,null);
        var morphology=new RiverMorphology(new RiverNetwork(List.of(channel),List.of(),"test"));
        double source=morphology.bedRadius(channel,0,0,0,0,0);
        double middle=morphology.bedRadius(channel,.5,200,0,200,0);
        assertTrue(source<middle*.55,"headwater does not taper enough: "+source+" vs "+middle);
        double previous=source;
        for(int distance=4;distance<=96;distance+=4) {
            double radius=morphology.bedRadius(channel,distance/400.0,distance,0,distance,0);
            assertTrue(radius>=previous-1.0,"headwater width changes abruptly");
            previous=radius;
        }
        assertTrue(morphology.bedDepth(channel,0,0,0,source)
                <morphology.bedDepth(channel,.5,200,0,middle));
    }
}
