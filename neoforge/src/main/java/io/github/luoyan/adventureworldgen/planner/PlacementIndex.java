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
import io.github.luoyan.adventureworldgen.plan.FailureStage;

/** One coarse candidate catalog and lazily cached exact quart samples for a frozen terrain. */
public final class PlacementIndex {
    public record Point(int x, int z) { public long cell() { return CellMask.key(x,z); } }
    private final PlanningAtlas terrain;
    private final JointPlanner.LevelConstraint levels;
    private final JointPlanner.BiomeConstraint adapters;
    private final AdventureWorldConfig config;
    // Packed x/z keys collide heavily under Long.hashCode() (x XOR z).
    // Primitive maps mix the full key and avoid boxed tree nodes on the million-cell grid.

    private final Map<ContentId, io.github.luoyan.adventureworldgen.spatial.TiledBitField> compatibility = new HashMap<>();
    private final Map<Integer, List<Point>> candidates = new HashMap<>();
    private final List<Point> land = new ArrayList<>();
    private final Map<List<ContentId>,Long> terrainCapacity=new HashMap<>();


    private final Map<Integer,Long2ByteOpenHashMap> accepted=new HashMap<>();
    private final Map<Integer,it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap> penalties=new HashMap<>();
    private long queries;
    private final Map<ContentId,io.github.luoyan.adventureworldgen.spatial.TiledBitField> environment=new HashMap<>();
    final SupplyRegionGraph supply=new SupplyRegionGraph();
    private io.github.luoyan.adventureworldgen.biome.BiomeEnvironmentRules environmentRules;
    public void prepareEnvironments(List<ContentId> biomes,io.github.luoyan.adventureworldgen.biome.BiomeEnvironmentRules rules,
                                    io.github.luoyan.adventureworldgen.plan.PlanningExecution execution) {
        environmentRules=rules;var ids=biomes.stream().distinct().sorted().toList();
        // Third-party adapters have no concurrency contract; evaluate them in stable order first.
        var admitted=new ArrayList<java.util.BitSet>();
        for(var id:ids){var bits=new java.util.BitSet(land.size());for(int i=0;i<land.size();i++) {
            var p=land.get(i);if(allows(id,p.x,p.z))bits.set(i);
        }admitted.add(bits);}
        record Prepared(io.github.luoyan.adventureworldgen.spatial.TiledBitField field,List<Long> legal) {}
        var prepared=execution.map(ids.size(),Math.max(1,land.size()*24L),i->{
            var field=new io.github.luoyan.adventureworldgen.spatial.TiledBitField();var legal=new ArrayList<Long>();
            for(int j=0;j<land.size();j++) {
                var p=land.get(j);boolean valid=admitted.get(i).get(j)&&rules.allows(ids.get(i),p.x,p.z,sample(p.x,p.z));
                field.get(p.x,p.z,()->valid);if(valid)legal.add(p.cell());
            }
            return new Prepared(field,legal);
        });
        for(int i=0;i<ids.size();i++){environment.put(ids.get(i),prepared.get(i).field);supply.add(ids.get(i),prepared.get(i).legal);}
    }
    boolean environmentAllows(ContentId id,int x,int z) {
        return environment.computeIfAbsent(id,ignored->new io.github.luoyan.adventureworldgen.spatial.TiledBitField())
                .get(x,z,()->allows(id,x,z)&&environmentRules.allows(id,x,z,sample(x,z)));
    }
    boolean hasEnvironment(){return environmentRules!=null;}

    public PlacementIndex(AdventureWorldConfig config, MacroTerrain terrain, JointPlanner.LevelConstraint levels,
                          JointPlanner.BiomeConstraint adapters, PlannerProfile profile) {
        this(config,terrain,levels,adapters,profile,ignored -> {});
    }
    /**
     * @param profile the profile whose node budget bounds the candidate grid. It is passed in, not
     *                read from {@code PlannerProfile.V2}, so a differently budgeted or re-versioned
     *                profile reaches this stage instead of silently keeping the V2 limit.
     */
    public PlacementIndex(AdventureWorldConfig config, MacroTerrain terrain, JointPlanner.LevelConstraint levels,
                          JointPlanner.BiomeConstraint adapters, PlannerProfile profile,
                          java.util.function.DoubleConsumer progress) {
        this.config = config; this.terrain = terrain instanceof PlanningAtlas atlas ? atlas : new PlanningAtlas(terrain, profile.maximumWorkingMemoryBytes()/4); this.levels = levels; this.adapters = adapters;
        int extent = (int) StrictMath.ceil(config.world().radius() / 16);
        long nodes = (2L * extent + 1) * (2L * extent + 1);
        if (nodes > profile.maximumCostNodes()) throw new PlanningFailure(PlanningFailure.Code.RESOURCE_LIMIT, FailureStage.PLACEMENT_INDEX, "candidate grid exceeds node budget",
                Map.of("nodes", nodes, "maximum_nodes", (long) profile.maximumCostNodes()));
        for (int gz = -extent; gz <= extent; gz++) for (int gx = -extent; gx <= extent; gx++) {
            if(gx==-extent) progress.accept((gz+extent)/(double)(2*extent+1));
            int x = gx * 16, z = gz * 16;
            if (StrictMath.hypot(x,z) > config.world().radius()) continue;
            MacroSample sample = sample(x,z);
            if (sample.waterKind() == WaterKind.NONE && !sample.hazardous()) land.add(new Point(x,z));
        }
    }
    public MacroSample sampleAt(double x,double z) { return terrain.sample(x,z); }
    public MacroSample sample(int x, int z) {
        return terrain.sample(Math.floorDiv(x,4)*4+2,Math.floorDiv(z,4)*4+2);
    }
    public boolean allows(ContentId biome, int x, int z) {
        var cache = compatibility.computeIfAbsent(biome, ignored -> new io.github.luoyan.adventureworldgen.spatial.TiledBitField());
        return cache.get(x,z,()-> {
            MacroSample s=sample(x,z);
            return !s.wet()&&!s.hazardous()&&config.biomes().allows(biome,s)
                    &&adapters.accepts(biome,Math.floorDiv(x,4)*4+2,Math.floorDiv(z,4)*4+2);
        });
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
    /** Fine candidates are streamed tile by tile: no whole-continent fine object catalog. */
    public Iterable<Point> candidates(int level,int step) {
        if(step==16)return candidates(level);
        if(step!=8&&step!=4)throw new IllegalArgumentException("unsupported candidate spacing");
        return () -> new Iterator<>() {
            final int low=Math.floorDiv(-(int)Math.ceil(config.world().radius()),256);
            final int high=Math.floorDiv((int)Math.ceil(config.world().radius()),256);
            int tx=low,tz=low,lx=0,lz=0;
            Point next=advance();
            private Point advance() {
                while(tz<=high) {
                    io.github.luoyan.adventureworldgen.plan.PlanningExecution.checkCancelled();
                    int x=tx*256+lx,z=tz*256+lz;
                    lx+=step;if(lx>=256){lx=0;lz+=step;if(lz>=256){lz=0;tx++;if(tx>high){tx=low;tz++;}}}
                    if(Math.hypot(x,z)>config.world().radius())continue;
                    var s=sample(x,z);
                    if(!s.wet()&&!s.hazardous())return new Point(x,z);
                }
                return null;
            }
            public boolean hasNext(){return next!=null;}
            public Point next(){if(next==null)throw new NoSuchElementException();Point value=next;next=advance();return value;}
        };
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
    public long queries() { return terrain.sampleCount(); }
}
