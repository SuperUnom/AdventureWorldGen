package io.github.luoyan.adventureworldgen.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import io.github.luoyan.adventureworldgen.worldgen.structure.TerrainSettings;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.neoforged.fml.ModList;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Conservative fingerprint of active definitions and template contents, including generated-template overrides. */
public final class StructureExecutionIdentity {
    public static final String VERSION = "chunk-structures-1";
    private StructureExecutionIdentity() {}
    public static String hash(MinecraftServer server, StructureExecutionCatalog catalog, String planIdentity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, VERSION); add(digest, planIdentity);
            ModList.get().getMods().stream().sorted(java.util.Comparator.comparing(m -> m.getModId()))
                    .forEach(m -> add(digest, m.getModId() + "=" + m.getVersion()));
            for (var id : catalog.ids().stream().sorted().toList()) {
                add(digest, id.toString());
                add(digest, canonical(TerrainSettings.CODEC.encodeStart(JsonOps.INSTANCE, catalog.terrain(id)).getOrThrow()).toString());
            }
            var registries = server.registryAccess();
            var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
            registry(digest, registries.registryOrThrow(Registries.STRUCTURE), Structure.DIRECT_CODEC, ops);
            registry(digest, registries.registryOrThrow(Registries.TEMPLATE_POOL), StructureTemplatePool.DIRECT_CODEC, ops);
            registry(digest, registries.registryOrThrow(Registries.PROCESSOR_LIST), StructureProcessorType.DIRECT_CODEC, ops);
            var templates = server.getStructureManager();
            for (var id : templates.listTemplates().distinct().sorted().toList()) {
                add(digest, id.toString());
                var template = templates.get(id).orElseThrow(() -> new IllegalStateException("unreadable template " + id));
                add(digest, canonical(NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, template.save(new CompoundTag()))).toString());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static <T> void registry(MessageDigest digest, Registry<T> registry, Codec<T> codec, RegistryOps<JsonElement> ops) {
        add(digest, registry.key().location().toString());
        for (var id : registry.keySet().stream().sorted().toList()) {
            add(digest, id.toString()); add(digest, canonical(codec.encodeStart(ops, registry.get(id)).getOrThrow()).toString());
        }
    }
    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            var sorted = new JsonObject();
            value.getAsJsonObject().keySet().stream().sorted().forEach(key -> sorted.add(key, canonical(value.getAsJsonObject().get(key))));
            return sorted;
        }
        if (value.isJsonArray()) {
            var result = new JsonArray(); value.getAsJsonArray().forEach(item -> result.add(canonical(item))); return result;
        }
        return value;
    }
}
