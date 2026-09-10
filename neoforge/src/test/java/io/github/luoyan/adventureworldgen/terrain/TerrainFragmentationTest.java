package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.erosion.*;
import io.github.luoyan.adventureworldgen.hydrology.HydrologyProfile;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainFragmentationTest {
    @Test void blockSurfacesRetainConnectedSlopesAfterErosion() {
        for(var template:new TerrainTemplate[]{TerrainTemplate.PLAINS,TerrainTemplate.HILLS_1,TerrainTemplate.DALES}) {
            int fragments=0;
            for(long seed:new long[]{9,7331,8844}) {
                var recipes=new TerrainRecipes(seed);double amplitude=template.defaults().verticalAmplitude();
                MacroTerrain base=(x,z)->new MacroSample(86+amplitude*(recipes.shape(template,x,z,1)+recipes.detail(template,x,z,1)),
                        Double.NaN,WaterKind.NONE,false,"test",template.category(),"test");
                var delta=new ErosionGenerator(PlannerProfile.V2,HydrologyProfile.FINITE_CONTINENT)
                        .generate(seed,base,-192,-192,8,49,49);
                var terrain=new ErodedTerrain(base,delta,"test");
                int[] heights=new int[256*256];
                for(int x=0;x<256;x++)for(int z=0;z<256;z++)
                    heights[x*256+z]=(int)Math.floor(terrain.sample(x-127.5,z-127.5).groundSurface());
                fragments+=closedFragments(heights);
            }
            // r21 block-centre totals were 164 / 170 / 352. Count only small, enclosed extrema;
            // narrow steps on a continuous steep slope are not terrain fragments.
            int limit=switch(template){case PLAINS->80;case HILLS_1->30;default->120;};
            assertTrue(fragments<=limit,template+" fragmented into "+fragments+" isolated bumps/pits");
        }
    }

    private static int closedFragments(int[] height) {
        boolean[] seen=new boolean[height.length];int[] queue=new int[height.length];int fragments=0;
        for(int start=0;start<height.length;start++) {
            if(seen[start])continue;
            int head=0,tail=1;queue[0]=start;seen[start]=true;
            boolean border=false,lower=false,higher=false;
            while(head<tail) {
                int cell=queue[head++],x=cell/256,z=cell%256;
                border|=x==0||x==255||z==0||z==255;
                for(int d=0;d<4;d++) {
                    int nx=x+(d==0?-1:d==1?1:0),nz=z+(d==2?-1:d==3?1:0);
                    if(nx<0||nx>=256||nz<0||nz>=256)continue;
                    int next=nx*256+nz;
                    lower|=height[next]<height[start];higher|=height[next]>height[start];
                    if(!seen[next]&&height[next]==height[start]){seen[next]=true;queue[tail++]=next;}
                }
            }
            if(tail<=16&&!border&&(lower^higher))fragments++;
        }
        return fragments;
    }
}
