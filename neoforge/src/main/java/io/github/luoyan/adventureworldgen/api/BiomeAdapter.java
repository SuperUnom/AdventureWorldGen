package io.github.luoyan.adventureworldgen.api;

import io.github.luoyan.adventureworldgen.config.ContentId;

/** Public compatibility contract used during preflight, planning, and surface generation. */
public interface BiomeAdapter {
    ContentId biomeId();
    String adapterVersion();
    Compatibility compatibility(MacroSample terrain);
    SurfacePalette surface(MacroSample terrain);

    /** Spatial surface patterns; existing third-party adapters keep their original policy. */
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
