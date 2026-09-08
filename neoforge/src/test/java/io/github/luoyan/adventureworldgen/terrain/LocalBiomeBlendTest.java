package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.config.ContentId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalBiomeBlendTest {
    @Test void mixesWithinQuartCellsButKeepsInteriorAndTerrainExclusions() {
        var forest=new ContentId("minecraft:forest");var desert=new ContentId("minecraft:desert");
        var blend=new LocalBiomeBlend(345705185492107788L);
        java.util.function.BiFunction<Integer,Integer,ContentId> carrier=(x,z)->x<0?forest:desert;
        int mixedQuarts=0;
        for(int z=-64;z<64;z+=4)for(int x=-32;x<32;x+=4) {
            var ids=new java.util.HashSet<ContentId>();
            for(int dz=0;dz<4;dz++)for(int dx=0;dx<4;dx++) {
                int wx=x+dx,wz=z+dz;
                var id=blend.sample(wx,wz,carrier,b->true,carrier.apply(wx,wz));ids.add(id);
                assertEquals(forest,blend.sample(wx,wz,carrier,b->b.equals(forest),forest));
                if(wx<-4)assertEquals(forest,id);if(wx>4)assertEquals(desert,id);
            }
            if(ids.size()==2)mixedQuarts++;
        }
        assertTrue(mixedQuarts>30,"surface is still a single label per 4x4 square");
        Integer previous=null;var positions=new java.util.HashSet<Integer>();
        for(int z=-64;z<64;z++) {
            int boundary=16;
            for(int x=-16;x<=16;x++)if(blend.sample(x,z,carrier,b->true,carrier.apply(x,z)).equals(desert)) {
                boundary=x;break;
            }
            positions.add(boundary);
            if(previous!=null)assertTrue(Math.abs(boundary-previous)<=1,"boundary contains an abrupt tooth");
            previous=boundary;
        }
        assertTrue(positions.size()>1,"continuous boundary warp did not bend the edge");
    }
    @Test void configurableRadiusNeverImportsDistantBiomesAndZeroDisablesMixing() {
        var a=new ContentId("test:a");var b=new ContentId("test:b");
        java.util.function.BiFunction<Integer,Integer,ContentId> raw=(x,z)->x*x+z*z<100?a:b;
        for(int radius:new int[]{0,1,4,12}) {
            var blend=new LocalBiomeBlend(7331,radius);
            for(int z=-40;z<=40;z++)for(int x=-40;x<=40;x++) {
                var original=raw.apply(x,z);var result=blend.sample(x,z,raw,id->true,original);
                assertEquals(result,blend.sample(x,z,raw,id->true,original));
                if(Math.abs(Math.hypot(x,z)-10)>radius+1)assertEquals(original,result);
                if(radius==0)assertEquals(original,result);
            }
        }
    }
}
