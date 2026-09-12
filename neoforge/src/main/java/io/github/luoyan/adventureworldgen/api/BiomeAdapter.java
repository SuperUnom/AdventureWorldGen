package io.github.luoyan.adventureworldgen.api;

import io.github.luoyan.adventureworldgen.plan.ContentId;

/** Public compatibility contract used during preflight and planning. */
public interface BiomeAdapter {
    ContentId biomeId();
    String adapterVersion();
    Compatibility compatibility(MacroSample terrain);
    record Compatibility(boolean allowed, double softPreference, String reason) {
        public Compatibility {
            if (!Double.isFinite(softPreference) || softPreference < 0.0 || softPreference > 1.0)
                throw new IllegalArgumentException("softPreference must be in [0,1]");
        }
    }
}
