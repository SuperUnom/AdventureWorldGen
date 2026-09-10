package io.github.luoyan.adventureworldgen;

import io.github.luoyan.adventureworldgen.worldgen.ProfileReloadListener;
import io.github.luoyan.adventureworldgen.runtime.RuntimePlanRegistry;
import io.github.luoyan.adventureworldgen.runtime.RuntimePlanner;
import io.github.luoyan.adventureworldgen.worldgen.AdventureChunkGenerator;
import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelResource;
import io.github.luoyan.adventureworldgen.config.ContentPreflight;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.worldgen.MinecraftAdapters;
import net.minecraft.resources.ResourceLocation;

@EventBusSubscriber(modid = AdventureWorldGen.MOD_ID)
public final class AdventureEvents {
    private AdventureEvents() {}

    @SubscribeEvent
    public static void addReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ProfileReloadListener());
    }

    /** Starts before ServerLevel construction, because structure-ring futures may query biomes in that constructor. */
    @SubscribeEvent
    public static void serverAboutToStart(ServerAboutToStartEvent event) {
        var server = event.getServer();
        var stems = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        var overworld = stems.get(LevelStem.OVERWORLD);
        if (overworld == null || !(overworld.generator() instanceof AdventureChunkGenerator generator)) return;
        var loaded = ProfileReloadListener.current();
        long seed = server.getWorldData().worldGenOptions().seed();
        var worldDirectory = server.getWorldPath(LevelResource.ROOT);
        var adapters = MinecraftAdapters.builtIn();
        new ContentPreflight().validate(loaded.config(), new ContentPreflight.RegistryLookup() {
            @Override public boolean biomeExists(ContentId id) {
                return server.registryAccess().registryOrThrow(Registries.BIOME).containsKey(ResourceLocation.parse(id.value()));
            }
            @Override public Boolean snowyAtSeaLevel(ContentId id) {
                var biome=server.registryAccess().registryOrThrow(Registries.BIOME).get(ResourceLocation.parse(id.value()));
                return biome!=null && biome.hasPrecipitation() && biome.coldEnoughToSnow(new net.minecraft.core.BlockPos(0,64,0));
            }
            @Override public boolean structureExists(ContentId id) {
                return server.registryAccess().registryOrThrow(Registries.STRUCTURE).containsKey(ResourceLocation.parse(id.value()));
            }
        }, adapters);
        // Do not join before Minecraft creates its ChunkProgressListener: the client spins until
        // that listener exists and cannot render a loading screen during this event. Biome/chunk
        // queries and levelLoaded retain the READY barrier while the loading screen renders.
        RuntimePlanRegistry.start(generator.profile(), () -> RuntimePlanner.plan(seed, loaded, worldDirectory, adapters));
    }

    @SubscribeEvent
    public static void serverStarting(ServerStartingEvent event) {
        var server = event.getServer();
        if (!(server.overworld().getChunkSource().getGenerator() instanceof AdventureChunkGenerator generator)) return;
        var loaded = ProfileReloadListener.current();
        long seed = server.getWorldData().worldGenOptions().seed();
        var worldDirectory = server.getWorldPath(LevelResource.ROOT);
        var adapters = MinecraftAdapters.builtIn();
        var plan = RuntimePlanRegistry.start(generator.profile(), () -> RuntimePlanner.plan(seed, loaded, worldDirectory, adapters)).join();
        var spawn = plan.spawnPosition();
        server.overworld().setDefaultSpawnPos(BlockPos.containing(spawn.x(), spawn.y(), spawn.z()), spawn.yaw());
    }

    /** Fires after the overworld object exists but before vanilla searches/generates initial spawn chunks. */
    @SubscribeEvent
    public static void levelLoaded(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD
                || !(level.getChunkSource().getGenerator() instanceof AdventureChunkGenerator generator)) return;
        var loaded = ProfileReloadListener.current();
        var worldDirectory = level.getServer().getWorldPath(LevelResource.ROOT);
        var adapters = MinecraftAdapters.builtIn();
        RuntimePlanRegistry.start(generator.profile(), () -> RuntimePlanner.plan(level.getSeed(), loaded, worldDirectory, adapters)).join();
    }

    @SubscribeEvent
    public static void createSpawn(LevelEvent.CreateSpawnPosition event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD
                || !(level.getChunkSource().getGenerator() instanceof AdventureChunkGenerator generator)) return;
        var plan = RuntimePlanRegistry.await(generator.profile());
        var spawn = plan.spawnPosition();
        event.getSettings().setSpawn(BlockPos.containing(spawn.x(), spawn.y(), spawn.z()), spawn.yaw());
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void serverStopped(ServerStoppedEvent event) {
        RuntimePlanRegistry.clear();
    }
}
