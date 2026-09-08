package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.hydrology.RiverNetwork;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.*;
import io.github.luoyan.adventureworldgen.planner.PlannerProfile;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** A wavy line can cross a scanline many times. Test enclosed 2-D islands on BOTH sides instead. */
class EcotoneTopologyTest {
    @Test void planningMaskHasNaturalContourWithoutMixedIslands() {
        var patch = new GeneratedAdventurePlan.PlannedBiomePatch("patch/required/23",new ContentId("minecraft:forest"),5,-128,-128,128,128);
        boolean[][] cells = new boolean[80][80];
        long area=0;
        for(int z=0;z<80;z++)for(int x=0;x<80;x++) {
            cells[z][x]=patch.contains(x*4-158,z*4-158);
            if(cells[z][x])area+=16;
        }
        assertEquals(patch.area(),area,"published patch area differs from the actual transition mask");
        assertEquals(0,islands(cells,true),"planning layer introduced small mixed islands");
        assertEquals(0,islands(cells,false),"planning layer introduced small holes");
        assertTrue(patch.contains(0,0),"required biome lost its protected core");
    }

    @Test void mutuallyExclusiveTerrainRulesStillHaveAnInterleavedBoundary() {
        int mountainIslands=0, ordinaryIslands=0;
        for(long seed:new long[]{345705185492107788L,4126649097427443736L,1}) {
            var regions=new RegionTerrain(seed,PlannerProfile.V2);
            boolean[][] mountain=new boolean[384][384];
            for(int z=0;z<384;z++)for(int x=0;x<384;x++)
                mountain[z][x]=regions.sample(x*4-768,z*4-768).template()==RegionTerrain.Template.MOUNTAINS;
            int a=islands(mountain,true), b=islands(mountain,false);
            assertTrue(a+b<=24,"terrain slope is fragmented across a broad band");
            mountainIslands+=a;ordinaryIslands+=b;
        }
        assertTrue(mountainIslands>0 && ordinaryIslands>0,
                "missing two-sided terrain patches: " + mountainIslands + "," + ordinaryIslands);
    }

    @Test void fillerPlanningKeepsLargeContiguousInteriors() {
        var config=new AdventureWorldConfigParser().parse("""
            {"world":{"radius":3000},"spawn":{"biome":"minecraft:plains"},
             "biomes":{"filler":["minecraft:forest","minecraft:desert"]}}
            """);
        var coast=new Coastline(List.of(new Vec2(-3000,-3000),new Vec2(3000,-3000),new Vec2(3000,3000),new Vec2(-3000,3000)));
        var plan=new GeneratedAdventurePlan(345705185492107788L,config,coast,new RiverNetwork(List.of(),List.of(),"test"),64,128,256,"test",null);
        boolean[][] cells=new boolean[384][384];
        for(int z=0;z<384;z++)for(int x=0;x<384;x++)cells[z][x]=plan.landBiomeAt(x*4-768,z*4-768).value().equals("minecraft:forest");
        int forestIslands=islands(cells,true), desertIslands=islands(cells,false);
        assertTrue(forestIslands+desertIslands<=24,"biome interiors break into a broad mosaic");
    }

    // Disregard isolated single quart cells and the large biome interiors.
    private static int islands(boolean[][] map, boolean value) {
        int h=map.length,w=map[0].length,count=0;boolean[][] seen=new boolean[h][w];
        int[] dx={1,-1,0,0},dz={0,0,1,-1};
        for(int z=0;z<h;z++)for(int x=0;x<w;x++)if(!seen[z][x]&&map[z][x]==value) {
            ArrayDeque<Integer> queue=new ArrayDeque<>();queue.add(z*w+x);seen[z][x]=true;
            int area=0;boolean border=false;
            while(!queue.isEmpty()) {
                int p=queue.removeFirst(),cx=p%w,cz=p/w;area++;
                border|=cx==0||cz==0||cx==w-1||cz==h-1;
                for(int d=0;d<4;d++) {
                    int nx=cx+dx[d],nz=cz+dz[d];
                    if(nx>=0&&nz>=0&&nx<w&&nz<h&&!seen[nz][nx]&&map[nz][nx]==value){seen[nz][nx]=true;queue.add(nz*w+nx);}
                }
            }
            if(!border&&area>=4&&area<=256)count++;
        }
        return count;
    }
}
