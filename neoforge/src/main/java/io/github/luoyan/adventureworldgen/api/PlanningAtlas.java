package io.github.luoyan.adventureworldgen.api;

import io.github.luoyan.adventureworldgen.plan.PlanningExecution;
import io.github.luoyan.adventureworldgen.plan.PlanningPolicy;
import io.github.luoyan.adventureworldgen.spatial.ColumnQueryCache;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/** Bounded exact sample atlas. Tiles publish immutable primitive arrays; no interpolation is introduced. */
public final class PlanningAtlas implements MacroTerrain {
    public static final int TILE_SIDE = PlanningPolicy.CURRENT.tileSide();
    private final MacroTerrain source;
    private final int maximumTiles;
    private final Map<Key, Tile> tiles = new LinkedHashMap<>(32,.75f,true);
    private final Map<Key,java.util.concurrent.CompletableFuture<Tile>> building=new HashMap<>();
    private final ColumnQueryCache<MacroSample> exact = new ColumnQueryCache<>(65536);
    private final LongAdder samples = new LongAdder();
    private record Key(int x,int z,int step) {}
    private static final WaterKind[] WATER = WaterKind.values();
    private record Tile(int side,double[] values,int[] tags,byte[] flags,List<String> dictionary) {
        MacroSample sample(int index) {
            int v=index*7,t=index*5,f=Byte.toUnsignedInt(flags[index]);
            return new MacroSample(values[v],values[v+1],WATER[f>>1],(f&1)!=0,
                    dictionary.get(tags[t]),dictionary.get(tags[t+1]),dictionary.get(tags[t+2]),
                    dictionary.get(tags[t+3]),dictionary.get(tags[t+4]),values[v+2],values[v+3],
                    values[v+4],values[v+5],values[v+6]);
        }
    }
    public PlanningAtlas(MacroTerrain source,long cacheBytes) {
        this.source=Objects.requireNonNull(source);
        maximumTiles=(int)Math.max(1,Math.min(Integer.MAX_VALUE,cacheBytes/(4096L*96)));
    }
    private Tile build(Key key) {
        int side=TILE_SIDE/key.step,n=side*side;
        double[] values=new double[n*7];int[] tags=new int[n*5];byte[] flags=new byte[n];
        var dictionary=new ArrayList<String>();var ids=new HashMap<String,Integer>();
        for(int z=0;z<side;z++)for(int x=0;x<side;x++) {
            PlanningExecution.checkCancelled();
            int i=z*side+x,v=i*7,t=i*5;
            MacroSample s=source.sample(key.x*TILE_SIDE+x*key.step+2,key.z*TILE_SIDE+z*key.step+2);
            samples.increment();
            values[v]=s.groundSurface();values[v+1]=s.waterSurface();values[v+2]=s.secondaryWeight();
            values[v+3]=s.mountainInfluence();values[v+4]=s.slope();values[v+5]=s.localRelief();values[v+6]=s.relativeElevation();
            String[] names={s.regionId(),s.terrainTemplate(),s.terrainVersion(),s.recipe(),s.secondaryRecipe()};
            for(int j=0;j<5;j++)tags[t+j]=ids.computeIfAbsent(names[j],name->{dictionary.add(name);return dictionary.size()-1;});
            flags[i]=(byte)(s.waterKind().ordinal()*2+(s.hazardous()?1:0));
        }
        return new Tile(side,values,tags,flags,List.copyOf(dictionary));
    }
    private Tile tile(Key key) {
        java.util.concurrent.CompletableFuture<Tile> pending;boolean owner;
        synchronized(tiles) {
            var known=tiles.get(key);if(known!=null)return known;
            pending=building.get(key);owner=pending==null;
            if(owner){pending=new java.util.concurrent.CompletableFuture<>();building.put(key,pending);}
        }
        if(owner)try {
            var built=build(key);
            synchronized(tiles) {
                tiles.put(key,built);
                while(tiles.size()>maximumTiles)tiles.remove(tiles.keySet().iterator().next());
                building.remove(key);pending.complete(built);
            }
        } catch(RuntimeException|Error failure) {
            synchronized(tiles){building.remove(key);pending.completeExceptionally(failure);}
            throw failure;
        }
        try {return pending.get();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("tile cancelled");}
        catch(java.util.concurrent.ExecutionException failure) {
            if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
            if(failure.getCause() instanceof Error error)throw error;
            throw new IllegalStateException(failure.getCause());
        }
    }
    public MacroSample cell(int x,int z,int step) {
        var tile=tile(new Key(Math.floorDiv(x,TILE_SIDE),Math.floorDiv(z,TILE_SIDE),step));
        return tile.sample(Math.floorMod(z,TILE_SIDE)/step*tile.side+Math.floorMod(x,TILE_SIDE)/step);
    }
    public void prepare(double radius,PlanningExecution execution) {
        int lo=Math.floorDiv(-(int)Math.ceil(radius),TILE_SIDE),hi=Math.floorDiv((int)Math.ceil(radius),TILE_SIDE);
        var keys=new ArrayList<Key>();
        for(int z=lo;z<=hi;z++)for(int x=lo;x<=hi;x++)keys.add(new Key(x,z,16));
        // Batches bound retained outputs too; tasks never mutate the shared LRU or merge in completion order.
        for(int start=0;start<keys.size();start+=execution.workers()) {
            int offset=start,count=Math.min(execution.workers(),keys.size()-start);
            var built=execution.map(count,1L<<20,i->tile(keys.get(offset+i)));
            synchronized(tiles) {
                for(int i=0;i<count;i++)tiles.put(keys.get(offset+i),built.get(i));
                while(tiles.size()>maximumTiles)tiles.remove(tiles.keySet().iterator().next());
            }
        }
    }
    @Override public MacroSample sample(double x,double z) {
        if(x==(int)x&&z==(int)z&&Math.floorMod((int)x,4)==2&&Math.floorMod((int)z,4)==2) {
            int cx=(int)x-2,cz=(int)z-2;
            int step=Math.floorMod(cx,16)==0&&Math.floorMod(cz,16)==0?16:4;
            return cell(cx,cz,step);
        }
        if(x==(int)x&&z==(int)z)return exact.get((int)x,(int)z,(a,b)->{samples.increment();return source.sample(a,b);});
        samples.increment();return source.sample(x,z);
    }
    public long sampleCount() {return samples.sum();}
}
