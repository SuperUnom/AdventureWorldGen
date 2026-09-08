package io.github.luoyan.adventureworldgen.api;

import io.github.luoyan.adventureworldgen.config.ContentId;

/** Public compatibility contract used during preflight and planning. */
public interface BiomeAdapter {
    ContentId biomeId();
    String adapterVersion();
    Compatibility compatibility(MacroSample terrain);
    /** Legacy palette API; ordinary runtime terrain now uses registered overworld surface rules. */
    SurfacePalette surface(MacroSample terrain);

    /** Legacy spatial palette query, retained for adapter callers; not a land surface override. */
    default SurfacePalette surface(MacroSample terrain, long seed, int blockX, int blockZ) {
        return surface(terrain);
    }

    record Compatibility(boolean allowed, double softPreference, String reason) {
        public Compatibility {
            if (!Double.isFinite(softPreference) || softPreference < 0.0 || softPreference > 1.0)
                throw new IllegalArgumentException("softPreference must be in [0,1]");
        }
    }

    record SurfacePalette(ContentId top, ContentId under, ContentId stone, int underDepth) {
        public SurfacePalette {
            if (underDepth < 0) throw new IllegalArgumentException("underDepth must be non-negative");
        }
    }
}
