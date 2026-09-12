package io.github.luoyan.adventureworldgen.worldgen;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.config.CanonicalConfigJson;
import io.github.luoyan.adventureworldgen.config.ConfigErrorCode;
import io.github.luoyan.adventureworldgen.config.ConfigException;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.config.LoadedProfile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Strict resource reload owner for the one fixed v1 profile.
 *
 * <p>This is the integration layer the config boundary defers to: it reads datapack resources and
 * produces the pure {@link LoadedProfile}, so author-config parsing and semantic validation stay
 * free of Minecraft datapack and registry access.
 */
public final class ProfileReloadListener extends SimplePreparableReloadListener<LoadedProfile> {
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
            return new LoadedProfile(DEFAULT_ID, config, canonical, selected.sourcePackId());
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
}
