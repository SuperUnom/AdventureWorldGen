package io.github.luoyan.adventureworldgen.config;

import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.luoyan.adventureworldgen.api.MacroSample;
import java.util.Objects;

/** Immutable, normalized author configuration for planner-v2. */
public record AdventureWorldConfig(
        WorldSettings world,
        SpawnSettings spawn,
        BiomeSettings biomes,
        List<StructureSettings> structures
) {
    public AdventureWorldConfig {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(spawn, "spawn");
        Objects.requireNonNull(biomes, "biomes");
        structures = List.copyOf(structures);
    }

    public record WorldSettings(double radius) {
    }

    public record SpawnSettings(ContentId biome, SpawnStructure structure) {
        public boolean hasBiome() {
            return biome != null;
        }

        public boolean hasStructure() {
            return structure != null;
        }
    }

    public record SpawnStructure(ContentId id, Vec3d spawnPoint) {
        public SpawnStructure {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(spawnPoint, "spawnPoint");
        }
    }

    public record BiomeSettings(List<RequiredBiome> required, List<ContentId> filler,
                                Map<ContentId, TerrainRule> terrainRules, int blendRadius) {
        public BiomeSettings(List<RequiredBiome> required, List<ContentId> filler, Map<ContentId, TerrainRule> rules) {
            this(required, filler, rules, 4);
        }
        public BiomeSettings(List<RequiredBiome> required, List<ContentId> filler) {
            this(required, filler, Map.of(), 4);
        }
        public BiomeSettings {
            required = List.copyOf(required);
            filler = List.copyOf(filler);
            terrainRules = Map.copyOf(terrainRules);
        }
        public boolean allows(ContentId biome, MacroSample sample) {
            TerrainRule rule = terrainRules.get(biome);
            return rule == null || rule.accepts(sample);
        }
        public int temperature(ContentId biome) {
            var rule = terrainRules.get(biome);
            return rule == null ? 5 : rule.temperatureLevel();
        }
    }

    public enum TemperatureType {
        COLD, MEDIUM, HOT;
        public static TemperatureType fromLevel(int level) { return level <= 3 ? COLD : level >= 7 ? HOT : MEDIUM; }
    }

    /** Rules apply to land ownership on the final macro terrain, before river/lake biome overlays. */
    public record TerrainRule(Set<String> allowedTerrain, Double minHeight, Double maxHeight, int temperatureLevel,
                              Map<TemperatureType, Double> temperatures, Double preferredMinHeight,
                              Double preferredMaxHeight, double heightPenalty, double fillerWeight, Integer adventureLevel) {
        public TerrainRule(Set<String> allowed, Double min, Double max, int temperature) {
            this(allowed,min,max,temperature,Map.of(TemperatureType.fromLevel(temperature),1.0),null,null,1,1,null);
        }
        public TerrainRule(Set<String> allowedTerrain, Double minHeight, Double maxHeight) {
            this(allowedTerrain, minHeight, maxHeight, 5);
        }
        public static final Set<String> TEMPLATES = Set.of("plains", "hills", "plateau", "mountains");
        public TerrainRule {
            allowedTerrain = Set.copyOf(allowedTerrain);
            temperatures = Map.copyOf(temperatures);
            if (temperatureLevel < 0 || temperatureLevel > 10) throw new IllegalArgumentException("temperature_level must be in [0,10]");
        }
        public double heightCost(double height) {
            double deviation = preferredMinHeight == null ? 0 : Math.max(0, preferredMinHeight-height);
            deviation += preferredMaxHeight == null ? 0 : Math.max(0,height-preferredMaxHeight);
            return heightPenalty * deviation / 32.0;
        }
        public boolean accepts(MacroSample sample) {
            return allowedTerrain.contains(sample.terrainTemplate())
                    && (minHeight == null || sample.groundSurface() >= minHeight)
                    && (maxHeight == null || sample.groundSurface() <= maxHeight);
        }
    }

    public record RequiredBiome(String requestId, ContentId id, int adventureLevel, AreaRange area) {
        public RequiredBiome {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(area, "area");
        }

        public String patchId() {
            return "patch/" + requestId;
        }
    }

    public record StructureSettings(
            ContentId id,
            int adventureLevel,
            CountRange count,
            AllowedBiomes allowedBiomes,
            PlacementMode placementMode,
            Spacing spacing,
            Vec3d entrance
    ) {
        public StructureSettings {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(count, "count");
            Objects.requireNonNull(allowedBiomes, "allowedBiomes");
            Objects.requireNonNull(placementMode, "placementMode");
            Objects.requireNonNull(spacing, "spacing");
            Objects.requireNonNull(entrance, "entrance");
        }

        public long effectiveMinimum(boolean isSpawnStructure) {
            return isSpawnStructure ? Math.max(1, count.min()) : count.min();
        }
    }

    public record AllowedBiomes(List<ContentId> ids, AreaRange area) {
        public AllowedBiomes {
            ids = List.copyOf(ids);
            Objects.requireNonNull(area, "area");
        }

        public boolean acceptsAnySupportedBiome() {
            return ids.isEmpty();
        }
    }

    public record AreaRange(long min, long max, long target) {
        public AreaRange(long min, long max) { this(min,max,max); }
        public static final AreaRange DEFAULT = new AreaRange(16_384, 196_608, 131_072);

        public CellRange inCells(int cellSide) {
            if (cellSide <= 0) {
                throw new IllegalArgumentException("cellSide must be positive");
            }
            long cellArea = Math.multiplyExact((long) cellSide, cellSide);
            long minimumCells = min / cellArea + (min % cellArea == 0 ? 0 : 1);
            return new CellRange(minimumCells, max / cellArea);
        }
    }

    public record CellRange(long min, long max) {
        public boolean isEmpty() {
            return min > max;
        }
    }

    public record CountRange(long min, long max) {
    }

    /** A null maximum denotes the configured absence of an upper spacing bound. */
    public record Spacing(double min, Double max) {
        public static final Spacing DEFAULT = new Spacing(0.0, null);

        public boolean hasMaximum() {
            return max != null;
        }
    }

    public enum PlacementMode {
        SCATTERED
    }

    public record Vec3d(double x, double y, double z) {
    }
}
