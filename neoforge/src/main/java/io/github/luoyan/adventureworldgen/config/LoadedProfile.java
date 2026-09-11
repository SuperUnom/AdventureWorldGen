package io.github.luoyan.adventureworldgen.config;

import io.github.luoyan.adventureworldgen.plan.ContentId;

/**
 * A fully parsed author profile: its identity, the validated configuration, the canonical JSON
 * used for the plan input identity, and the pack that provided it.
 *
 * <p>Pure config-layer data. Reading datapack resources and querying Minecraft registries is
 * performed by the integration layer, which produces this record; the config package itself has
 * no Minecraft dependency.
 *
 * <p>There is deliberately no separate configuration digest. The only identity that matters is
 * derived from {@code canonicalJson} together with the production identity by
 * {@code runtime.PlanIdentity.hash}; a second, independently maintained summary could silently
 * disagree with it. Compute a configuration-only digest at the point of use if a log ever needs
 * one.
 */
public record LoadedProfile(ContentId id, AdventureWorldConfig config, String canonicalJson,
                            String sourcePack) {}
