package io.github.luoyan.adventureworldgen.config;

import io.github.luoyan.adventureworldgen.persistence.AtomicPlanRepository;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Strict resource reload owner for the one fixed v1 profile. */
public final class ProfileManager extends SimplePreparableReloadListener<ProfileManager.LoadedProfile> {
    public static final ContentId DEFAULT_ID = new ContentId("adventureworldgen:default");
    private static final ResourceLocation DEFAULT_RESOURCE = ResourceLocation.fromNamespaceAndPath(
            "adventureworldgen", "adventureworldgen/profiles/default.json");
    private static final AtomicReference<LoadedProfile> CURRENT = new AtomicReference<>();

    @Override
    protected LoadedProfile prepare(ResourceManager resources, ProfilerFiller profiler) {
        List<Resource> stack = resources.getResourceStack(DEFAULT_RESOURCE);
        if (stack.isEmpty()) throw new ConfigException(ConfigErrorCode.CONFIG_ERROR, "$",
                "missing required profile resource " + DEFAULT_RESOURCE);
        // The built-in resource plus at most one overriding datapack is the only unambiguous v1 stack.
        if (stack.size() > 2) throw new ConfigException(ConfigErrorCode.CONFIG_CONFLICT, "$",
                "multiple datapacks provide profile " + DEFAULT_ID + ": "
                        + stack.stream().map(Resource::sourcePackId).toList());
        Resource selected = stack.getLast();
        try (var reader = selected.openAsReader()) {
            AdventureWorldConfig config = new AdventureWorldConfigParser().parse(reader);
            String canonical = CanonicalConfigJson.write(config);
            return new LoadedProfile(DEFAULT_ID, config, canonical,
                    AtomicPlanRepository.sha256(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    selected.sourcePackId());
        } catch (IOException failure) {
            throw new ConfigException(ConfigErrorCode.CONFIG_ERROR, "$",
                    "could not read profile from " + selected.sourcePackId() + ": " + failure.getMessage());
        }
    }

    @Override
    protected void apply(LoadedProfile loaded, ResourceManager resources, ProfilerFiller profiler) {
        CURRENT.set(loaded);
    }

    public static LoadedProfile current() {
        LoadedProfile loaded = CURRENT.get();
        if (loaded == null) throw new IllegalStateException("adventureworldgen:default has not completed resource loading");
        return loaded;
    }

    public record LoadedProfile(ContentId id, AdventureWorldConfig config, String canonicalJson,
                                String configHash, String sourcePack) {}
}
