package io.github.luoyan.adventureworldgen.erosion;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.hydrology.HydrologyProfile;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErosionGeneratorTest {
    @Test void dropletCannotExcavateMoreThanTheDownhillDrop() {
        int side=64;double[] heights=new double[side*side];float[] delta=new float[heights.length];
        for(int x=0;x<side;x++)for(int z=0;z<side;z++)heights[x*side+z]=100+x*.001;
        var oneStep=new HydrologyProfile.Erosion(1,1,.7,.7,.5,.5);
        ErosionGenerator.erodeDroplet(32,32,0,0,1,side,side,heights,delta,oneStep);
        double removed=0;for(float d:delta)removed-=d;
        assertTrue(removed>0&&removed<=.001001,"erosion dug through its downhill height budget: "+removed);
    }

    @Test void coarseStorageDoesNotTurnOneBlockDropletIntoEightBlockExcavation() {
        int side=32;double[] heights=new double[side*side];float[] delta=new float[heights.length];
        for(int x=0;x<side;x++)for(int z=0;z<side;z++)heights[x*side+z]=100+x*8*.001;
        var oneStep=new HydrologyProfile.Erosion(1,1,.7,.7,.5,.5);
        ErosionGenerator.erodeDroplet(128,128,0,0,8,side,side,heights,delta,oneStep);
        double removedVolume=0;for(float d:delta)removedVolume-=d*64;
        assertTrue(removedVolume>0&&removedVolume<=.001001,"grid spacing changed physical erosion: "+removedVolume);
    }

    @Test void smoothingActsOnCompleteHeightAtBlockRadiusAndPreservesPlanesAndPeaks() {
        var zero=new ErosionDeltaField(-32,-32,8,9,9,new float[81]);
        MacroTerrain plane=(x,z)->new MacroSample(100+.1*x+.2*z,Double.NaN,WaterKind.NONE,false,"r","hills","test");
        var smoothPlane=new ErodedTerrain(plane,zero,"test");
        assertEquals(plane.sample(3.5,-2.5).groundSurface(),smoothPlane.sample(3.5,-2.5).groundSurface(),1e-10);
        MacroTerrain ripple=(x,z)->new MacroSample(100+Math.cos(x*Math.PI)*.4,Double.NaN,WaterKind.NONE,false,"r","plains","test");
        var smoothed=new ErodedTerrain(ripple,zero,"test");
        assertTrue(smoothed.sample(0,0).groundSurface()<100.2,"zero erosion delta bypassed full-height smoothing");
        double first=smoothed.sample(2.5,-3.5).groundSurface();
        for(int x=-20;x<20;x++)smoothed.sample(x,0);
        assertEquals(first,smoothed.sample(2.5,-3.5).groundSurface());
        MacroTerrain peak=(x,z)->new MacroSample(230+Math.cos(x)*8,Double.NaN,WaterKind.NONE,false,"r","mountains","test");
        assertEquals(238,new ErodedTerrain(peak,zero,"test").sample(0,0).groundSurface());
    }

    @Test void tiledStencilSurvivesEvictionAndConcurrentReplacement() throws Exception {
        MacroTerrain base=(x,z)->new MacroSample(100+Math.sin(x*.11)+Math.cos(z*.07),
                Double.NaN,WaterKind.NONE,false,"r","hills","test");
        var zero=new ErosionDeltaField(-32,-32,8,9,9,new float[81]);
        var shared=new ErodedTerrain(base,zero,"test");
        var reference=new ErodedTerrain(base,zero,"test");
        var points=new java.util.ArrayList<io.github.luoyan.adventureworldgen.spatial.Vec2>();
        for(int i=0;i<128;i++)points.add(new io.github.luoyan.adventureworldgen.spatial.Vec2(i*16-.5,-i*16+.5));
        var expected=points.stream().map(p->reference.sample(p.x(),p.z())).toList();
        // More than the 32,768 tile limit, including negative coordinates.
        for(int i=0;i<33000;i++)shared.sample((i%200-100)*16+2,(i/200-100)*16+2);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks=new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for(int worker=0;worker<4;worker++) {
                int offset=worker;
                tasks.add(()-> {
                    for(int j=0;j<points.size();j++) {
                        int i=(j*37+offset)%points.size();var p=points.get(i);
                        assertEquals(expected.get(i),shared.sample(p.x(),p.z()));
                    }
                    return null;
                });
            }
            for(var result:workers.invokeAll(tasks))result.get();
        }
    }

    @Test
    void producesDeterministicWorldAlignedDeltaAndKeepsQueriesInsideBoundary() {
        MacroTerrain slope = (x, z) -> new MacroSample(100 + x * 0.05 + z * 0.02,
                Double.NaN, WaterKind.NONE, false, "region/test", "hills", "terrain/test");
        var generator = new ErosionGenerator(PlannerProfile.V2, HydrologyProfile.FTF_ADAPTED_V1);
        ErosionDeltaField first = generator.generate(99, slope, -32, -32, 8, 9, 9);
        ErosionDeltaField second = generator.generate(99, slope, -32, -32, 8, 9, 9);
        assertEquals(first, second);
        assertTrue(Double.isFinite(first.sample(-1000, -1000)));
        assertTrue(Double.isFinite(first.sample(1000, 1000)));
        assertEquals(new ErodedTerrain(slope, first, "erosion/test").sample(0, 0),
                new ErodedTerrain(slope, second, "erosion/test").sample(0, 0));
        for (float delta : first.copyDeltas()) {
            assertTrue(Float.isFinite(delta));
            assertTrue(delta >= -12.0f && delta <= 8.0f);
        }
    }
}
