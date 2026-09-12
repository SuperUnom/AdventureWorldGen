package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.hydrology.RiverNetwork;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import io.github.luoyan.adventureworldgen.plan.ContentId;

class BiomeFragmentationTest {
    @Test void defaultTerrainAndFillerKeepLargeInteriorsAcrossRegressionSeeds() throws Exception {
        AdventureWorldConfig config;
        try (var reader=Files.newBufferedReader(Path.of("src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json"))) {
            config=new AdventureWorldConfigParser().parse(reader);
        }
        var coast=new Coastline(List.of(new Vec2(-3000,-3000),new Vec2(3000,-3000),new Vec2(3000,3000),new Vec2(-3000,3000)));
        for(long seed:new long[]{345705185492107788L,4126649097427443736L,1}) {
            var plan=new GeneratedAdventurePlan(seed,config,coast,new RiverNetwork(List.of(),List.of(),"test"),64,128,256,"test",null);
            int n=256; ContentId[] labels=new ContentId[n*n]; boolean[] seen=new boolean[n*n];
            for(int z=0;z<n;z++)for(int x=0;x<n;x++) labels[z*n+x]=plan.landBiomeAt(x*4-512,z*4-512);
            int components=0, tinyArea=0;
            for(int p=0;p<labels.length;p++)if(!seen[p]) {
                ArrayDeque<Integer> q=new ArrayDeque<>();q.add(p);seen[p]=true;int area=0;boolean border=false;
                while(!q.isEmpty()) {
                    int v=q.removeFirst(),x=v%n,z=v/n;area++;border|=x==0||z==0||x==n-1||z==n-1;
                    for(int[] d:List.of(new int[]{1,0},new int[]{-1,0},new int[]{0,1},new int[]{0,-1})) {
                        int nx=x+d[0],nz=z+d[1],next=nz*n+nx;
                        if(nx>=0&&nz>=0&&nx<n&&nz<n&&!seen[next]&&labels[next].equals(labels[v])) {seen[next]=true;q.add(next);}
                    }
                }
                components++;if(!border&&area<=16)tinyArea+=area;
            }
            // r14 deliberately has four times as many filler sites and a broader species pool.
            // Limit fragmentation relative to this density, and tiny islands to 1% of land cells.
            assertTrue(components<=128,"broad multi-biome fragmentation for seed " + seed + ": " + components);
            assertTrue(tinyArea<=n*n/100,"too much area in <=256-block islands: " + tinyArea);
        }
    }
}
