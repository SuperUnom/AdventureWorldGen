package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.noise.DeterministicRandom;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.spatial.Vec2;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import io.github.luoyan.adventureworldgen.noise.ValueNoise;

/** Infinite, world-aligned jittered Voronoi regions with continuous shared-boundary blending. */
public final class RegionTerrain {
    public enum Template { PLAINS, HILLS, PLATEAU, MOUNTAINS }

    private final long seed;
    private final String algorithmVersion;
    private final PlannerProfile.Terrain profile;
    private final ValueNoise warpX;
    private final ValueNoise warpZ;
    private final TerrainRecipes recipes;
    private final EcotoneNoise recipeEcotone;
    private final TerrainSettings settings;
    private final ValueNoise compositeNoise;
    private final io.github.luoyan.adventureworldgen.config.AdventureWorldConfig config;
    private final Map<GridKey, Region> regions = new ConcurrentHashMap<>();
    private record Neighborhood(long x, long z, Region[] regions) {}
    private final ThreadLocal<Neighborhood> neighborhoodCache = new ThreadLocal<>();
    private static final class SampleWorkspace {
        final double[] regionDistances=new double[25];
        final double[] recipeDistances=new double[TerrainTemplate.values().length];
        final Region[] owners=new Region[recipeDistances.length];
        final int[] nearby=new int[25];
    }
    private final ThreadLocal<SampleWorkspace> sampleWorkspace=ThreadLocal.withInitial(SampleWorkspace::new);
    private final GridKey centralRegion;
    private final TerrainCapacityPlan capacities;

    public RegionTerrain(long seed, PlannerProfile plannerProfile) {
        this(seed,plannerProfile,TerrainCapacityPlan.empty());
    }
    public RegionTerrain(long seed, PlannerProfile plannerProfile,TerrainCapacityPlan capacities) {
        this(seed,plannerProfile,capacities,TerrainSettings.defaults(),null);
    }
    public RegionTerrain(long seed, PlannerProfile plannerProfile,TerrainCapacityPlan capacities,
                         TerrainSettings settings,io.github.luoyan.adventureworldgen.config.AdventureWorldConfig config) {
        this.capacities=capacities;
        this.settings=settings; this.config=config;
        this.seed = seed;
        this.algorithmVersion = plannerProfile.algorithmVersion();
        this.profile = plannerProfile.terrain();
        this.warpX = new ValueNoise(seed, "region-warp/x", profile.coordinateWarpScale());
        this.warpZ = new ValueNoise(seed, "region-warp/z", profile.coordinateWarpScale());
        this.recipes = new TerrainRecipes(seed,settings);
        this.recipeEcotone=new EcotoneNoise(seed,"terrain/biome-ecotone-r10",40);
        this.compositeNoise = new ValueNoise(seed,"terrain/composite",320);
        this.centralRegion = nearestPair(warped(0, 0)).nearest.key;
        // It was provisionally created before the central key was known.
        regions.remove(centralRegion);
    }

    public Sample sample(double x, double z) {
        Vec2 query = warped(x, z);
        // The blend already visits this 5x5 neighborhood. Reuse those exact distances
        // for nearest-region selection instead of searching the same regions twice.
        var workspace=sampleWorkspace.get();
        double[] regionDistances=workspace.regionDistances;
        long gx = fastFloor(query.x() / profile.regionSpacing()), gz = fastFloor(query.z() / profile.regionSpacing());
        Region[] neighborhood=neighborhood(gx,gz);
        int first=-1, runnerUp=-1;
        java.util.Arrays.fill(regionDistances,Double.POSITIVE_INFINITY);
        // Establish an exact near-pair bound in the central 3x3 window first.
        // Remaining sites beyond both that pair and the widest (200-block) blend
        // cannot affect height, recipe selection (48 blocks), or internal weight.
        for(int pass=0;pass<2;pass++)for(int i=0;i<neighborhood.length;i++) {
            boolean central=i/5>=1&&i/5<=3&&i%5>=1&&i%5<=3;
            if(central!=(pass==0))continue;
            if(pass==1) {
                double bound=Math.nextUp(Math.max(regionDistances[runnerUp],regionDistances[first]+200));
                if(Math.abs(query.x()-neighborhood[i].center.x())>bound
                        ||Math.abs(query.z()-neighborhood[i].center.z())>bound)continue;
            }
            double distance=query.distance(neighborhood[i].center);regionDistances[i]=distance;
            if(first<0||compare(distance,neighborhood[i],regionDistances[first],neighborhood[first])<0) {
                runnerUp=first;first=i;
            } else if(runnerUp<0||compare(distance,neighborhood[i],regionDistances[runnerUp],neighborhood[runnerUp])<0)runnerUp=i;
        }
        double unsearchedLowerBound = (2 - profile.maximumRegionJitterFraction()) * profile.regionSpacing();
        Pair pair = regionDistances[runnerUp] < unsearchedLowerBound
                ? new Pair(new Candidate(neighborhood[first].key,neighborhood[first],regionDistances[first]),
                           new Candidate(neighborhood[runnerUp].key,neighborhood[runnerUp],regionDistances[runnerUp]))
                : nearestPair(query);
        double ratio = pair.nearest.distance / pair.second.distance;
        double t = clamp((1.0 - ratio) / 0.35);
        double internalWeight = smooth(t);
        double sum = 0, weights = 0, mountain = 0, detailSum=0, detailWeights=0;
        double[] distances=workspace.recipeDistances;java.util.Arrays.fill(distances,Double.POSITIVE_INFINITY);
        Region[] owners=workspace.owners;
        int[] nearby=workspace.nearby;int count=0;
        for(int i=0;i<neighborhood.length;i++) {
            Region region=neighborhood[i];double distance=regionDistances[i];
            if(distance<distances[region.recipe.ordinal()]){distances[region.recipe.ordinal()]=distance;owners[region.recipe.ordinal()]=region;}
            if(distance-pair.nearest.distance<200)nearby[count++]=i;
        }
        for(int i=0;i<count;i++) {
            int candidate=nearby[i];var region=neighborhood[candidate];
            double w=1,dw=1;
            // Preserve pairwise weighting and the original neighborhood summation order.
            for(int j=0;j<count;j++)if(i!=j) {
                double difference=Math.max(0,regionDistances[candidate]-regionDistances[nearby[j]]);
                w*=smooth(clamp(1-difference/transitionWidth(region.recipe,neighborhood[nearby[j]].recipe)));
                dw*=smooth(clamp(1-difference/32));
            }
            if(w<=0)continue;
            var height=templateHeight(region,x,z);
            sum+=w*height.coarse;weights+=w;
            detailSum+=dw*height.detail;detailWeights+=dw;
            if(region.recipe.mountain())mountain+=w*height.envelope;
        }
        int primary=-1,secondary=-1;
        for(int i=0;i<distances.length;i++)if(Double.isFinite(distances[i])) {
            if(primary<0||distances[i]<distances[primary]){secondary=primary;primary=i;}
            else if(secondary<0||distances[i]<distances[secondary])secondary=i;
        }
        int selected=secondary<0||distances[secondary]-distances[primary]>=48?primary
                :EcotoneSelector.select(distances,recipeEcotone.threshold(x,z),48);
        Region owner=owners[selected];
        double blend=compositeWeight(owner,x,z);
        // A narrow ecotone selects one of the actual contributing recipes. Both composite ingredients are exposed
        // and both checked by biome rules. Height blending never changes recipe permissions.
        return new Sample(sum/weights+detailSum/detailWeights,owner.id,owner.template,internalWeight,pair.nearest.distance,
                pair.second.distance,owner.recipe,owner.secondary,blend,mountain/weights);
    }

    private static int compare(double a,Region ar,double b,Region br) {
        int order=Double.compare(a,b);return order!=0?order:ar.id.compareTo(br.id);
    }

    public static double transitionWidth(TerrainTemplate a,TerrainTemplate b) {
        if(a==b)return 96;
        if(a.mountain()||b.mountain())return 200;
        if(a==TerrainTemplate.PLATEAU||b==TerrainTemplate.PLATEAU||a==TerrainTemplate.BADLANDS||b==TerrainTemplate.BADLANDS)return 88;
        return 144;
    }

    /** Adjacent terrain/filter queries usually share a region neighborhood. Keep only
     * one immutable 5x5 window per sampling thread; no cache state participates in selection. */
    private Region[] neighborhood(long x, long z) {
        Neighborhood cached = neighborhoodCache.get();
        if (cached != null && cached.x == x && cached.z == z) return cached.regions;
        Region[] nearby = new Region[25];
        int i = 0;
        for (long rx = x - 2; rx <= x + 2; rx++) for (long rz = z - 2; rz <= z + 2; rz++)
            nearby[i++] = region(new GridKey(rx, rz));
        neighborhoodCache.set(new Neighborhood(x, z, nearby));
        return nearby;
    }

    public Region region(long gridX, long gridZ) {
        return region(new GridKey(gridX, gridZ));
    }
    public GridKey regionKeyAt(double x,double z) { return nearestPair(warped(x,z)).nearest.key; }
    public GridKey interiorRegionAt(double x,double z,double margin) {
        var pair=nearestPair(warped(x,z));
        return pair.second.distance-pair.nearest.distance>=margin?pair.nearest.key:null;
    }

    private double compositeWeight(Region r,double x,double z) {
        return r.secondary==null?0:.45*smooth(clamp((compositeNoise.sample(x,z)+.65)/1.3));
    }
    private double mountainEnvelope(Region r,double x,double z) {
        if(!r.recipe.mountain())return 0;
        if(capacities.ranges().ranges().isEmpty()||capacities.at(r.key)!=null)return 1;
        return .2+.8*capacities.ranges().influence(x,z);
    }
    private Height templateHeight(Region r,double x,double z) {
        var primary=settings.get(r.recipe);
        double shape=recipes.shape(r.recipe,x,z,primary.horizontalScale());
        double detail=primary.detailStrength()*recipes.detail(r.recipe,x,z,primary.horizontalScale());
        double blend=compositeWeight(r,x,z);
        if(r.secondary!=null) {
            var secondary=settings.get(r.secondary);
            // Each ingredient retains its own amplitude and scale within the shared fitted envelope.
            double envelopeAmplitude=Math.max(primary.verticalAmplitude(),secondary.verticalAmplitude());
            double ratio=secondary.verticalAmplitude()/envelopeAmplitude;
            shape=TerrainRecipes.lerp(shape*primary.verticalAmplitude()/envelopeAmplitude,
                    recipes.shape(r.secondary,x,z,secondary.horizontalScale())*ratio,blend);
            detail=TerrainRecipes.lerp(detail*primary.verticalAmplitude()/envelopeAmplitude,
                    secondary.detailStrength()*recipes.detail(r.secondary,x,z,secondary.horizontalScale())*ratio,blend);
        }
        double envelope=mountainEnvelope(r,x,z);
        double height=r.baseElevation+r.amplitude*(r.recipe.mountain()?(.15+.85*envelope)*shape:shape);
        // Local detail is not subjected to broad regional averaging; its amplitude stays bounded.
        return new Height(height,r.amplitude*detail,envelope);
    }

    private record Height(double coarse,double detail,double envelope) {}

    private Pair nearestPair(Vec2 query) {
        int spacing = profile.regionSpacing();
        long baseX = fastFloor(query.x() / spacing), baseZ = fastFloor(query.z() / spacing);
        Candidate nearest = null, second = null;
        for (int radius = 1; ; radius++) {
            for (long gx = baseX - radius; gx <= baseX + radius; gx++) {
                for (long gz = baseZ - radius; gz <= baseZ + radius; gz++) {
                    if (radius > 1 && gx > baseX - radius && gx < baseX + radius
                            && gz > baseZ - radius && gz < baseZ + radius) continue;
                    Region region = region(new GridKey(gx, gz));
                    double distance = query.distance(region.center);
                    Candidate candidate = new Candidate(region.key, region, distance);
                    if (nearest == null || ORDER.compare(candidate, nearest) < 0) {
                        second = nearest; nearest = candidate;
                    } else if (second == null || ORDER.compare(candidate, second) < 0) {
                        second = candidate;
                    }
                }
            }
            double unsearchedLowerBound = StrictMath.max(0.0,
                    (radius - profile.maximumRegionJitterFraction()) * spacing);
            if (second != null && second.distance < unsearchedLowerBound) return new Pair(nearest, second);
            if (radius > 16) throw new IllegalStateException("region nearest-neighbor proof failed");
        }
    }

    private Region region(GridKey key) {
        return regions.computeIfAbsent(key, ignored -> createRegion(key));
    }

    private Region createRegion(GridKey key) {
        int spacing = profile.regionSpacing();
        double jitter = spacing * profile.maximumRegionJitterFraction();
        String id = "region/" + key.x + "/" + key.z;
        double x = key.x * (double) spacing + signedSample(id, 0) * jitter;
        double z = key.z * (double) spacing + signedSample(id, 1) * jitter;
        var reservation=capacities.at(key);
        var allowed=new java.util.TreeSet<>(settings.enabled());
        if(reservation!=null)allowed.retainAll(reservation.allowedTemplates());
        boolean inRange=capacities.ranges().influence(x,z)>.12;
        // Reserve every region touched by the corridor, including where its spine crosses a
        // Voronoi corner. The continuous envelope, not a center-point coin toss, sets uplift.
        if(!inRange)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)
            if(capacities.ranges().influence(x+dx*spacing*.45,z+dz*spacing*.45)>.45)inRange=true;
        final boolean rangeRegion=inRange;
        TerrainTemplate recipe;
        if(reservation!=null)recipe=reservation.recipe();
        else {
            var choices=allowed.stream().map(TerrainTemplate::byId)
                .filter(t->key.equals(centralRegion)?!t.mountain():
                    !settings.mountainRanges()||capacities.ranges().ranges().isEmpty()||t.mountain()==rangeRegion).toList();
            if(choices.isEmpty())choices=allowed.stream().map(TerrainTemplate::byId).toList();
            recipe=key.equals(centralRegion)&&allowed.contains("plains")?TerrainTemplate.PLAINS:choose(id,choices,0);
        }
        TerrainTemplate secondary=null;
        if(settings.composite()&&!recipe.mountain()&&!key.equals(centralRegion)) {
            final var primary=recipe;
            var choices=allowed.stream().map(TerrainTemplate::byId)
                .filter(t->!t.mountain()&&t!=primary&&commonFiller(primary,t)).toList();
            if(!choices.isEmpty()&&DeterministicRandom.sample(seed,algorithmVersion,"composite",id,0)<.6)
                secondary=choose(id,choices,1);
        }
        double amplitude=settings.get(recipe).verticalAmplitude();
        if(secondary!=null)amplitude=Math.max(amplitude,settings.get(secondary).verticalAmplitude());
        double naturalBase=recipe.naturalBaseElevation();
        if(secondary!=null)naturalBase=Math.max(naturalBase,secondary.naturalBaseElevation());
        double elevation=Math.min(naturalBase,310-64-settings.maximumShape(recipe,secondary)*amplitude);
        if(reservation!=null) {elevation=reservation.baseElevation();amplitude=reservation.amplitude();secondary=reservation.secondary();}
        return new Region(key,id,new Vec2(x,z),recipe.planningCategory(),recipe,secondary,elevation,amplitude);
    }
    private boolean commonFiller(TerrainTemplate a,TerrainTemplate b) {
        if(config==null)return a.category().equals(b.category());
        return config.biomes().filler().stream().anyMatch(id->{
            var rule=config.biomes().terrainRules().get(id);
            return rule==null||(!rule.shoreOnly()&&rule.landforms().isEmpty()&&rule.minHeight()==null&&rule.maxHeight()==null
                &&rule.effectiveTemplates().contains(a.id())&&rule.effectiveTemplates().contains(b.id()));
        });
    }
    private TerrainTemplate choose(String id,java.util.List<TerrainTemplate> choices,int operation) {
        double total=choices.stream().mapToDouble(t->settings.get(t).weight()).sum();
        double value=DeterministicRandom.sample(seed,algorithmVersion,"recipe",id,operation)*total;
        for(var t:choices){value-=settings.get(t).weight();if(value<0)return t;}
        return choices.getLast();
    }

    private double signedSample(String id, long index) {
        return DeterministicRandom.sample(seed, algorithmVersion, "region-center", id, index) * 2.0 - 1.0;
    }

    private Vec2 warped(double x, double z) {
        return new Vec2(x + profile.coordinateWarpAmplitude() * warpX.sample(x, z),
                z + profile.coordinateWarpAmplitude() * warpZ.sample(x, z));
    }

    private static double clamp(double value) { return StrictMath.max(0.0, StrictMath.min(1.0, value)); }
    private static double smooth(double value) { return value * value * (3.0 - 2.0 * value); }
    private static long fastFloor(double value) { long i = (long) value; return value < i ? i - 1 : i; }

    private static final Comparator<Candidate> ORDER = Comparator.comparingDouble(Candidate::distance)
            .thenComparing(candidate -> candidate.region.id);

    public record Sample(double relativeHeight, String regionId, Template template, double internalWeight,
                         double nearestDistance, double secondDistance,TerrainTemplate recipe,
                         TerrainTemplate secondary,double secondaryWeight,double mountainInfluence) {}
    public record Region(GridKey key, String id, Vec2 center, Template template,TerrainTemplate recipe,
                         TerrainTemplate secondary,double baseElevation,double amplitude) {}
    public record GridKey(long x, long z) {}
    private record Candidate(GridKey key, Region region, double distance) {}
    private record Pair(Candidate nearest, Candidate second) {}
}
