package io.github.luoyan.adventureworldgen.terrain;
import io.github.luoyan.adventureworldgen.planner.*;
import io.github.luoyan.adventureworldgen.erosion.*;
import io.github.luoyan.adventureworldgen.hydrology.*;
import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.spatial.*;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
class PlanningOptimizationGoldenTest {
 // Recorded from the unmodified r24 release; includes every RegionTerrain/MacroSample field.
 @org.junit.jupiter.api.Test void preservesR24SamplingAndErosionBits()throws Exception {
  var expected=java.util.List.of(
"58f713fafdc6dc2e0d43376a7b8d43ee0bedd28d6576e591fd55aa01b09a93c7",
"c8616e04ed520f2c87746d8b47803f4e04169586cb9f679070edb3c411d6bf9c",
"cac29a931f6c4520a8e22131f9281475f84f3340a2d21fb1f9db2a66c2e8dbb2",
"c71a21585359a915daf18d62d14eceb74fb142bd54cdc7f0915a711f418822f1",
"89715307b5a10c027540d515117dbffee20e10c729e5b9a0effc52b5333cba83",
"a68398dddbe3c378893f06fc534ee725bde85663c68c055329c4d226308b0b55",
"7438b43a2fd1e32061b26c8d194ff128ed2fd66b626208c1b5c72aaac60cce0c");
  int golden=0;
  for(long seed:new long[]{9,7331,4126649097427443736L}) {
   var regions=new RegionTerrain(seed,PlannerProfile.V2);var d=MessageDigest.getInstance("SHA-256");var random=new Random(seed);
   for(int i=0;i<4096;i++) {
    double x=i<1024?(i%32-16)*768+.5:random.nextDouble()*16000-8000;
    double z=i<1024?(i/32-16)*768-.5:random.nextDouble()*16000-8000;
    d.update(regions.sample(x,z).toString().getBytes(StandardCharsets.UTF_8));
   }
   org.junit.jupiter.api.Assertions.assertEquals(expected.get(golden++),HexFormat.of().formatHex(d.digest()),"region seed="+seed);
  }
  var coast=new Coastline(List.of(new Vec2(-900,-800),new Vec2(850,-850),new Vec2(500,-100),new Vec2(950,500),new Vec2(100,800),new Vec2(-850,550)));
  var island=new IslandMacroTerrain(coast,new RegionTerrain(7331,PlannerProfile.V2),7331,64,128,128,"test");
  var d=MessageDigest.getInstance("SHA-256");
  for(int x=-1100;x<=1100;x+=11)for(int z=-1100;z<=1100;z+=11)d.update(island.sample(x+.5,z+.5).toString().getBytes(StandardCharsets.UTF_8));
  org.junit.jupiter.api.Assertions.assertEquals(expected.get(golden++),HexFormat.of().formatHex(d.digest()),"coast tile saturation");
  MacroTerrain slope=(x,z)->new MacroSample(100+.1*x+.3*z+Math.sin(x*.2)*2,Double.NaN,WaterKind.NONE,false,"test","hills","test");
  for(int spacing:new int[]{1,2,8}) {
   var e=new ErosionGenerator(PlannerProfile.V2,HydrologyProfile.FINITE_CONTINENT).generate(7331,slope,-32,-32,spacing,33,33);
   d.reset();for(float v:e.copyDeltas())d.update(java.nio.ByteBuffer.allocate(4).putFloat(v).array());
   org.junit.jupiter.api.Assertions.assertEquals(expected.get(golden++),HexFormat.of().formatHex(d.digest()),"erosion spacing="+spacing);
  }
 }
}
