package io.github.luoyan.adventureworldgen.erosion;

import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.hydrology.HydrologyProfile;
import io.github.luoyan.adventureworldgen.noise.DeterministicRandom;
import io.github.luoyan.adventureworldgen.planner.PlannerProfile;
import io.github.luoyan.adventureworldgen.planner.PlanningFailure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/** Adapted FTF droplet erosion, frozen during planning; ErodedTerrain applies the block filter. */
public final class ErosionGenerator {
    private final PlannerProfile planner;
    private final HydrologyProfile profile;

    public ErosionGenerator(PlannerProfile planner, HydrologyProfile profile) {
        this.planner = planner; this.profile = profile;
    }

    public ErosionDeltaField generate(long seed, MacroTerrain terrain, int originX, int originZ,
                                      int spacing, int width, int height) {
        return generate(seed, terrain, originX, originZ, spacing, width, height, ignored -> {});
    }

    public ErosionDeltaField generate(long seed, MacroTerrain terrain, int originX, int originZ,
                                      int spacing, int width, int height, java.util.function.DoubleConsumer progress) {
        Brush brush = new Brush(StrictMath.max(1.01, 4.0 / spacing));
        int size = Math.multiplyExact(width, height);
        double[] heights = new double[size];
        float[] delta = new float[size];
        for (int x = 0; x < width; x++) for (int z = 0; z < height; z++)
            heights[index(x, z, height)] = terrain.sample(originX + x * (double) spacing,
                    originZ + z * (double) spacing).groundSurface();

        progress.accept(0.1);
        var erosion = profile.erosion();
        int chunksX = StrictMath.max(1, (int) StrictMath.ceil((width - 1) * spacing / 16.0));
        int chunksZ = StrictMath.max(1, (int) StrictMath.ceil((height - 1) * spacing / 16.0));
        List<ChunkTask> active = new ArrayList<>();
        for (int chunkX = 0; chunkX < chunksX; chunkX++) {
            for (int chunkZ = 0; chunkZ < chunksZ; chunkZ++) {
                double chunkCenterX = originX + chunkX * 16.0 + 8.0;
                double chunkCenterZ = originZ + chunkZ * 16.0 + 8.0;
                if (terrain.sample(chunkCenterX, chunkCenterZ).waterKind() == io.github.luoyan.adventureworldgen.api.WaterKind.OCEAN
                        && terrain.sample(chunkCenterX - 8, chunkCenterZ - 8).waterKind() == io.github.luoyan.adventureworldgen.api.WaterKind.OCEAN
                        && terrain.sample(chunkCenterX + 8, chunkCenterZ - 8).waterKind() == io.github.luoyan.adventureworldgen.api.WaterKind.OCEAN
                        && terrain.sample(chunkCenterX - 8, chunkCenterZ + 8).waterKind() == io.github.luoyan.adventureworldgen.api.WaterKind.OCEAN
                        && terrain.sample(chunkCenterX + 8, chunkCenterZ + 8).waterKind() == io.github.luoyan.adventureworldgen.api.WaterKind.OCEAN)
                    continue;
                active.add(new ChunkTask(chunkX, chunkZ, "chunk/" + Math.floorDiv(originX, 16) + "/"
                        + Math.floorDiv(originZ, 16) + "/" + chunkX + "/" + chunkZ));
            }
        }
        progress.accept(0.2);
        // Default droplets travel 12 blocks, with at most 16 blocks of grid brush support.
        // Keep the conservative r21 scheduling separation: chunks in one 16x16 color
        // are therefore disjoint; fixed color barriers make parallel scheduling output-invariant.
        var workers = Executors.newFixedThreadPool(StrictMath.max(1, StrictMath.min(4,
                Runtime.getRuntime().availableProcessors())), runnable -> {
            Thread thread = new Thread(runnable, "adventureworldgen-erosion"); thread.setDaemon(true); return thread;
        });
        try {
            for (int colorX = 0; colorX < 16; colorX++) for (int colorZ = 0; colorZ < 16; colorZ++) {
                List<Callable<Void>> batch = new ArrayList<>();
                for (ChunkTask task : active) if (Math.floorMod(task.chunkX, 16) == colorX
                        && Math.floorMod(task.chunkZ, 16) == colorZ) batch.add(() -> {
                    StableSequence sequence = new StableSequence(DeterministicRandom.seed(seed, planner.algorithmVersion(),
                            "erosion", task.id, 0));
                    for (int drop = 0; drop < erosion.dropletsPerChunk(); drop++) {
                        double worldX = originX + task.chunkX * 16.0 + sequence.nextDouble() * 16.0;
                        double worldZ = originZ + task.chunkZ * 16.0 + sequence.nextDouble() * 16.0;
                        erodeDroplet(worldX, worldZ, originX, originZ, spacing, width, height,
                                heights, delta, erosion, brush);
                    }
                    return null;
                });
                for (var future : workers.invokeAll(batch)) future.get();
                progress.accept(0.2 + 0.75 * (colorX * 16 + colorZ + 1) / 256.0);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PlanningFailure(PlanningFailure.Code.EXECUTION_FAILED, "erosion",
                    "erosion worker interrupted", Map.of("completed_tasks", "partial"));
        } catch (java.util.concurrent.ExecutionException failed) {
            throw new PlanningFailure(PlanningFailure.Code.EXECUTION_FAILED, "erosion",
                    "erosion worker failed", Map.of("reason", String.valueOf(failed.getCause())));
        } finally {
            workers.shutdownNow();
        }
        // The upstream full-height smoothing pass is evaluated by ErodedTerrain at
        // block resolution. Applying a 1.8-cell kernel here would make it 14.4 blocks
        // wide at the persisted spacing of 8, rounding hills and plateau edges away.
        long operations = Math.multiplyExact((long) active.size(), erosion.dropletsPerChunk());
        progress.accept(1);
        return new ErosionDeltaField(originX, originZ, spacing, width, height, delta, operations);
    }

    static void erodeDroplet(double worldX, double worldZ, int originX, int originZ, int spacing,
                                     int width, int height, double[] heights, float[] delta,
                                     HydrologyProfile.Erosion profile) {
        erodeDroplet(worldX, worldZ, originX, originZ, spacing, width, height, heights, delta,
                profile, new Brush(StrictMath.max(1.01, 4.0 / spacing)));
    }

    private static void erodeDroplet(double worldX, double worldZ, int originX, int originZ, int spacing,
                                     int width, int height, double[] heights, float[] delta,
                                     HydrologyProfile.Erosion profile, Brush brush) {
        double x = (worldX - originX) / spacing, z = (worldZ - originZ) / spacing;
        double dirX = 0, dirZ = 0, speed = profile.velocity(), water = profile.volume(), sediment = 0;
        // FTF droplets move one BLOCK per step and carry normalized Levels units.
        // This persisted grid is coarser: convert displacement, gradients and sediment
        // volume explicitly instead of treating an 8-block cell as one block.
        double step=1.0/spacing, cellArea=(double)spacing*spacing;
        for (int life = 0; life < profile.lifetime(); life++) {
            int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
            if (ix < 1 || iz < 1 || ix >= width - 2 || iz >= height - 2) break;
            double oldHeight = sampleHeight(x, z, heights, delta, height);
            double gradX = (sampleHeight(x + 0.5, z, heights, delta, height)
                    - sampleHeight(x - 0.5, z, heights, delta, height));
            double gradZ = (sampleHeight(x, z + 0.5, heights, delta, height)
                    - sampleHeight(x, z - 0.5, heights, delta, height));
            dirX = dirX * 0.05 - gradX / (spacing * 256.0) * 0.95;
            dirZ = dirZ * 0.05 - gradZ / (spacing * 256.0) * 0.95;
            double directionLength = StrictMath.hypot(dirX, dirZ);
            if (directionLength < 1e-12) break;
            dirX /= directionLength; dirZ /= directionLength;
            double nextX = x + dirX * step, nextZ = z + dirZ * step;
            double newHeight = sampleHeight(nextX, nextZ, heights, delta, height);
            double heightChange = newHeight - oldHeight;
            double capacity = StrictMath.max(-heightChange * speed * water * 4.0, 0.01 * 256.0);
            if (sediment > capacity || heightChange > 0) {
                double amount = heightChange > 0 ? StrictMath.min(sediment, heightChange)
                        : (sediment - capacity) * profile.depositRate();
                amount = StrictMath.max(0.0, amount);
                deposit(delta, width, height, x, z, amount / cellArea);
                sediment -= amount;
            } else {
                double amount = StrictMath.min((capacity - sediment) * profile.erosionRate(),
                        StrictMath.max(0.0, -heightChange));
                amount = StrictMath.max(0.0, amount);
                erode(delta, width, height, ix, iz, amount / cellArea, brush);
                sediment += amount;
            }
            speed = StrictMath.sqrt(StrictMath.max(0.0, speed * speed + heightChange * (3.0 / 256.0)));
            water *= 0.99;
            x = nextX; z = nextZ;
        }
    }

    /** Preserve the original dx/dz accumulation order and floating-point operations. */
    private static final class Brush {
        final int[] dx, dz;
        final double[] weights;
        final double total;
        final int extent;
        Brush(double radius) {
            extent=(int)StrictMath.ceil(radius);
            int size=(2*extent+1)*(2*extent+1), count=0;
            int[] xs=new int[size],zs=new int[size];double[] ws=new double[size];
            double sum=0;
            for(int x=-extent;x<=extent;x++)for(int z=-extent;z<=extent;z++) {
                double w=StrictMath.max(0.0,radius-StrictMath.hypot(x,z));
                sum+=w;
                if(w>0){xs[count]=x;zs[count]=z;ws[count++]=w;}
            }
            dx=java.util.Arrays.copyOf(xs,count);dz=java.util.Arrays.copyOf(zs,count);
            weights=java.util.Arrays.copyOf(ws,count);total=sum;
        }
    }

    private static void erode(float[] delta, int width, int height, int centerX, int centerZ,
                              double amount, Brush brush) {
        double total=brush.total;
        boolean interior=centerX>=brush.extent&&centerZ>=brush.extent
                &&centerX+brush.extent<width&&centerZ+brush.extent<height;
        if(!interior) {
            total=0;
            for(int i=0;i<brush.weights.length;i++) {
                int x=centerX+brush.dx[i],z=centerZ+brush.dz[i];
                if(x>=0&&z>=0&&x<width&&z<height)total+=brush.weights[i];
            }
        }
        if(total==0)return;
        for(int i=0;i<brush.weights.length;i++) {
            int x=centerX+brush.dx[i],z=centerZ+brush.dz[i];
            if(interior||(x>=0&&z>=0&&x<width&&z<height))
                accumulate(delta,index(x,z,height),-amount*brush.weights[i]/total);
        }
    }

    private static void deposit(float[] delta, int width, int height, double x, double z, double amount) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double tx = x - ix, tz = z - iz;
        add(delta, width, height, ix, iz, amount * (1 - tx) * (1 - tz));
        add(delta, width, height, ix + 1, iz, amount * tx * (1 - tz));
        add(delta, width, height, ix, iz + 1, amount * (1 - tx) * tz);
        add(delta, width, height, ix + 1, iz + 1, amount * tx * tz);
    }

    private static void add(float[] delta, int width, int height, int x, int z, double value) {
        if (x >= 0 && z >= 0 && x < width && z < height) accumulate(delta, index(x, z, height), value);
    }

    private static double sampleHeight(double x, double z, double[] heights, float[] delta, int rowHeight) {
        int width = heights.length / rowHeight;
        int x0 = StrictMath.max(0, StrictMath.min(width - 2, (int) Math.floor(x)));
        int z0 = StrictMath.max(0, StrictMath.min(rowHeight - 2, (int) Math.floor(z)));
        double tx = StrictMath.max(0, StrictMath.min(1, x - x0));
        double tz = StrictMath.max(0, StrictMath.min(1, z - z0));
        double a = lerp(value(heights, delta, x0, z0, rowHeight), value(heights, delta, x0 + 1, z0, rowHeight), tx);
        double b = lerp(value(heights, delta, x0, z0 + 1, rowHeight), value(heights, delta, x0 + 1, z0 + 1, rowHeight), tx);
        return lerp(a, b, tz);
    }

    private static double value(double[] heights, float[] delta, int x, int z, int rowHeight) {
        int index = index(x, z, rowHeight); return heights[index] + delta[index];
    }

    /** SplitMix64 sequence seeded from one stable SHA key per pre-numbered chunk task. */
    private static final class StableSequence {
        private long state;
        StableSequence(long seed) { state = seed; }
        double nextDouble() {
            state += 0x9E3779B97F4A7C15L;
            long value = state;
            value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
            value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
            value ^= value >>> 31;
            return (value >>> 11) * 0x1.0p-53;
        }
    }
    private record ChunkTask(int chunkX, int chunkZ, String id) {}
    private static int index(int x, int z, int height) { return x * height + z; }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static void accumulate(float[] values, int index, double change) {
        values[index] = bounded(values[index] + change);
    }
    private static float bounded(double value) {
        if (!Double.isFinite(value)) return value < 0 ? -12.0f : 8.0f;
        return (float) StrictMath.max(-12.0, StrictMath.min(8.0, value));
    }
}
