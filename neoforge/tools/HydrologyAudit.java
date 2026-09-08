import io.github.luoyan.adventureworldgen.terrain.*;
import io.github.luoyan.adventureworldgen.hydrology.*;
import io.github.luoyan.adventureworldgen.erosion.*;
import io.github.luoyan.adventureworldgen.planner.*;
import io.github.luoyan.adventureworldgen.api.*;
import java.util.*;
/** Reproducible block-column adjacency audit, including planning-time erosion. */
public class HydrologyAudit {
 public static void main(String[] args) {
  long seed=Long.parseLong(args[0]);
  var coast=new CoastGenerator(PlannerProfile.V2).generate(seed,3000,300);
  var island=new IslandMacroTerrain(coast.coastline(),new RegionTerrain(seed,PlannerProfile.V2),seed,64,coast.landBand(),coast.seaBand(),"audit");
  var erosion=new ErosionGenerator(PlannerProfile.V2,HydrologyProfile.FINITE_CONTINENT).generate(seed,island,-3256,-3256,8,815,815);
  var base=new ErodedTerrain(island,erosion,"erosion-v1");
  var rivers=new HydrologyGenerator(PlannerProfile.V2,HydrologyProfile.FINITE_CONTINENT).generate(seed,3000,64,coast.coastline(),base);
  var terrain=new HydrologyTerrain(base,rivers);
  int count=0, walls=0, spills=0;double max=0;String at="";
  for(var channel:rivers.channels()) for(int i=1;i<channel.points().size()-1;i++) {
   var p=channel.points().get(i);var prev=channel.points().get(i-1);var next=channel.points().get(i+1);
   double len=prev.distance(next),nx=-(next.z()-prev.z())/len,nz=(next.x()-prev.x())/len;
   for(int cross=-(int)Math.ceil(RiverMorphology.maximumBedRadius(channel.shape())+6);cross<=Math.ceil(RiverMorphology.maximumBedRadius(channel.shape())+6);cross++) for(int along=-3;along<=3;along+=3) {
    int x=(int)Math.floor(p.x()+nx*cross+nz*along),z=(int)Math.floor(p.z()+nz*cross-nx*along);
    var a=terrain.sample(x+.5,z+.5);
    if(!a.wet()||a.waterKind()==WaterKind.OCEAN||Math.floor(a.waterSurface())<=Math.floor(a.groundSurface()))continue;
    for(int[] delta:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
     var b=terrain.sample(x+delta[0]+.5,z+delta[1]+.5);count++;
     double top=Math.max(terrain.solidSurfaceAt(x+delta[0],z+delta[1],b), b.wet()?Math.floor(b.waterSurface()):-1000);
     double jump=Math.floor(a.waterSurface())-top;
     if(jump>max){max=jump;at=x+","+z+" "+a+" -> "+b;}
     if(b.wet()&&jump>1)walls++;
     if(!b.wet()&&jump>0)spills++;
    }
   }
  }
  System.out.println("seed="+seed+" channels="+rivers.channels().size()+" lakes="+rivers.channels().stream().filter(c->c.lake()!=null).count()+" wetlands="+rivers.wetlands().size()+" pairs="+count+" waterWalls="+walls+" drySpills="+spills+" max="+max+" at="+at);
  if(walls>0||spills>0)throw new AssertionError("Uncontained water in seed "+seed);
 }
}
