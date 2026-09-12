package io.github.luoyan.adventureworldgen.terrain;
import io.github.luoyan.adventureworldgen.planner.*;
import io.github.luoyan.adventureworldgen.erosion.*;
import io.github.luoyan.adventureworldgen.hydrology.*;
import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.spatial.*;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
class PlanningOptimizationGoldenTest {
 // Records the deliberate r33 shelf revision (land and erosion vectors unchanged) and still protects every sampled field.
 @org.junit.jupiter.api.Test void preservesR33SamplingAndErosionBits()throws Exception {
  var expected=java.util.List.of(
"f9459ebd62eb92c5c4eefadf22abe11e7d2c7ae0047f569af44845fc73527583",
"7bb66ef6f62a87243e458331bd283f6d09719e211c2f6716222e28ab08efc3de",
"3ceb589a27aa2ff7706d96f8d1bf92092ada829996b77b383d4d62219962b162",
"fdcf546d8c4f0871c92e7071392fbf5449d0362865fab6d61ac8c7d9d96a3240",
"89715307b5a10c027540d515117dbffee20e10c729e5b9a0effc52b5333cba83",
"a68398dddbe3c378893f06fc534ee725bde85663c68c055329c4d226308b0b55",
"7438b43a2fd1e32061b26c8d194ff128ed2fd66b626208c1b5c72aaac60cce0c");
  var actual=new java.util.ArrayList<String>();
  for(long seed:new long[]{9,7331,4126649097427443736L}) {
   var regions=new RegionTerrain(seed,PlannerProfile.V2);var d=MessageDigest.getInstance("SHA-256");var random=new Random(seed);
   for(int i=0;i<4096;i++) {
    double x=i<1024?(i%32-16)*768+.5:random.nextDouble()*16000-8000;
    double z=i<1024?(i/32-16)*768-.5:random.nextDouble()*16000-8000;
    d.update(regions.sample(x,z).toString().getBytes(StandardCharsets.UTF_8));
   }
   actual.add(HexFormat.of().formatHex(d.digest()));
  }
  var coast=new Coastline(List.of(new Vec2(-900,-800),new Vec2(850,-850),new Vec2(500,-100),new Vec2(950,500),new Vec2(100,800),new Vec2(-850,550)));
  var island=new IslandMacroTerrain(coast,new RegionTerrain(7331,PlannerProfile.V2),7331,64,128,128,"test");
  var d=MessageDigest.getInstance("SHA-256");
  for(int x=-1100;x<=1100;x+=11)for(int z=-1100;z<=1100;z+=11)d.update(island.sample(x+.5,z+.5).toString().getBytes(StandardCharsets.UTF_8));
  actual.add(HexFormat.of().formatHex(d.digest()));
  MacroTerrain slope=(x,z)->new MacroSample(100+.1*x+.3*z+Math.sin(x*.2)*2,Double.NaN,WaterKind.NONE,false,"test","hills","test");
  for(int spacing:new int[]{1,2,8}) {
   var e=new ErosionGenerator(PlannerProfile.V2,HydrologyProfile.FINITE_CONTINENT).generate(7331,slope,-32,-32,spacing,33,33);
   d.reset();for(float v:e.copyDeltas())d.update(java.nio.ByteBuffer.allocate(4).putFloat(v).array());
   actual.add(HexFormat.of().formatHex(d.digest()));
  }
  org.junit.jupiter.api.Assertions.assertEquals(expected,actual);
 }
}
