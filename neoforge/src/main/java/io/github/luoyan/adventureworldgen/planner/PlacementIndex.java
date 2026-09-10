package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import java.util.*;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import io.github.luoyan.adventureworldgen.spatial.CellMask;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;

/** One coarse candidate catalog and lazily cached exact quart samples for a frozen terrain. */
public final class PlacementIndex {
    public record Point(int x, int z) { public long cell() { return CellMask.key(x,z); } }
    private final MacroTerrain terrain;
    private final JointPlanner.LevelConstraint levels;
    private final JointPlanner.BiomeConstraint adapters;
    private final AdventureWorldConfig config;
    // Packed x/z keys collide heavily under Long.hashCode() (x XOR z).
    // Primitive maps mix the full key and avoid boxed tree nodes on the million-cell grid.
    private final Long2ObjectOpenHashMap<MacroSample> samples = new Long2ObjectOpenHashMap<>();
    private final Map<ContentId, Long2ByteOpenHashMap> compatibility = new HashMap<>();
    private final Map<Integer, List<Point>> candidates = new HashMap<>();
    private final List<Point> land = new ArrayList<>();
    private final Map<List<ContentId>,Long> terrainCapacity=new HashMap<>();
    private final Map<Integer,List<Point>> finerLand = new HashMap<>();
    private final Long2ObjectOpenHashMap<MacroSample> exactSamples=new Long2ObjectOpenHashMap<>();
    private final Map<Integer,Long2ByteOpenHashMap> accepted=new HashMap<>();
    private final Map<Integer,it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap> penalties=new HashMap<>();
    private long queries;

    public PlacementIndex(AdventureWorldConfig config, MacroTerrain terrain, JointPlanner.LevelConstraint levels,
                          JointPlanner.BiomeConstraint adapters) {
        this(config,terrain,levels,adapters,ignored -> {});
    }
    public PlacementIndex(AdventureWorldConfig config, MacroTerrain terrain, JointPlanner.LevelConstraint levels,
                          JointPlanner.BiomeConstraint adapters,java.util.function.DoubleConsumer progress) {
        this.config = config; this.terrain = terrain; this.levels = levels; this.adapters = adapters;
        int extent = (int) StrictMath.ceil(config.world().radius() / 16);
        long nodes = (2L * extent + 1) * (2L * extent + 1);
        if (nodes > PlannerProfile.V2.maximumCostNodes()) throw new PlanningFailure(PlanningFailure.Code.RESOURCE_LIMIT,
                "placement-index", "candidate grid exceeds node budget", Map.of("nodes", nodes));
        for (int gz = -extent; gz <= extent; gz++) for (int gx = -extent; gx <= extent; gx++) {
            if(gx==-extent) progress.accept((gz+extent)/(double)(2*extent+1));
            int x = gx * 16, z = gz * 16;
            if (StrictMath.hypot(x,z) > config.world().radius()) continue;
            MacroSample sample = sample(x,z);
            if (sample.waterKind() == WaterKind.NONE && !sample.hazardous()) land.add(new Point(x,z));
        }
    }
    public MacroSample sampleAt(double x,double z) {
        if(x!=(int)x||z!=(int)z)return terrain.sample(x,z);
        int ix=(int)x,iz=(int)z;
        if(Math.floorMod(ix,4)==2&&Math.floorMod(iz,4)==2)return sample(ix-2,iz-2);
        long key=((long)ix<<32)^(iz&0xffffffffL);
        return exactSamples.computeIfAbsent(key,ignored->terrain.sample(x,z));
    }
    public MacroSample sample(int x, int z) {
        long cell = CellMask.key(x,z);
        MacroSample result = samples.get(cell);
        if (result == null) {
            queries++;
            result = terrain.sample(CellMask.x(cell) + 2, CellMask.z(cell) + 2);
            samples.put(cell, result);
        }
        return result;
    }
    public boolean allows(ContentId biome, int x, int z) {
        var cache = compatibility.computeIfAbsent(biome, ignored -> new Long2ByteOpenHashMap());
        long cell = CellMask.key(x,z);
        byte result = cache.get(cell);
        if (result == 0) {
            MacroSample s = sample(x,z);
            boolean allowed = s.waterKind() == WaterKind.NONE && !s.hazardous()
                    && config.biomes().allows(biome,s) && adapters.accepts(biome,CellMask.x(cell)+2,CellMask.z(cell)+2);
            result = (byte) (allowed ? 1 : 2);
            cache.put(cell, result);
        }
        return result == 1;
    }
    /** Coarse frozen legal terrain supply; independent of temperature and adventure preference. */
    public long terrainCapacity(List<ContentId> biomes) {
        var key=biomes.stream().distinct().sorted().toList();
        return terrainCapacity.computeIfAbsent(key,ignored->land.stream()
                .filter(p->key.stream().anyMatch(id->allows(id,p.x,p.z))).count()*256);
    }
    public List<Point> candidates(int level) {
        return candidates.computeIfAbsent(level, ignored -> land.stream().filter(p -> levels.mightAccept(level,p.x,p.z)).toList());
    }
    public List<Point> candidates(int level,int step) {
        if(step==16)return candidates(level);
        if(step!=8&&step!=4)throw new IllegalArgumentException("unsupported candidate spacing");
        // Finer fallback deliberately bypasses the coarse cost filter: a coarse graph can miss a narrow route.
        return finerLand.computeIfAbsent(step,ignored -> {
            List<Point> points=new ArrayList<>(); int extent=(int)StrictMath.ceil(config.world().radius()/step);
            long visits=0;
            for(int gz=-extent;gz<=extent;gz++)for(int gx=-extent;gx<=extent;gx++) {
                int x=gx*step,z=gz*step;
                if(StrictMath.hypot(x,z)>config.world().radius())continue;
                if(++visits>2_000_000)throw new PlanningFailure(PlanningFailure.Code.SEARCH_BUDGET_EXHAUSTED,"candidate-refinement",
                        "fine candidate catalog exceeded cell budget",Map.of("spacing",step,"visits",visits));
                var s=sample(x,z); if(s.waterKind()==WaterKind.NONE&&!s.hazardous())points.add(new Point(x,z));
            }
            return List.copyOf(points);
        });
    }
    public boolean accepts(int level, Point p) {
        var cache=accepted.computeIfAbsent(level,ignored->new Long2ByteOpenHashMap());
        byte value=cache.get(p.cell());
        if(value==0){value=(byte)(levels.accepts(level,p.x,p.z)?1:2);cache.put(p.cell(),value);}
        return value==1;
    }
    public double penalty(int level, Point p) {
        var cache=penalties.computeIfAbsent(level,ignored->new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap());
        return cache.computeIfAbsent(p.cell(),ignored->levels.penalty(level,p.x,p.z));
    }
    public long queries() { return queries; }
}
