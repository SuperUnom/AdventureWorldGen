package io.github.luoyan.adventureworldgen.config;

import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.luoyan.adventureworldgen.api.MacroSample;
import java.util.Objects;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.TemperatureType;

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

    public record WorldSettings(double radius, io.github.luoyan.adventureworldgen.terrain.TerrainSettings terrain) {
        public WorldSettings(double radius) { this(radius,io.github.luoyan.adventureworldgen.terrain.TerrainSettings.defaults()); }
        public WorldSettings { Objects.requireNonNull(terrain); }
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

    /**
     * The author's filler rules, reduced to the single question the terrain sampler asks: may these
     * two recipes meet inside one filler biome? Kept on the author model so terrain only ever sees
     * the answer, never the config type.
     */
    public io.github.luoyan.adventureworldgen.terrain.FillerTerrainPolicy fillerTerrainPolicy() {
        return (first, second) -> biomes().filler().stream().anyMatch(id -> {
            var rule = biomes().terrainRules().get(id);
            return rule == null || (!rule.shoreOnly() && rule.landforms().isEmpty()
                    && rule.minHeight() == null && rule.maxHeight() == null
                    && rule.effectiveTemplates().contains(first.id())
                    && rule.effectiveTemplates().contains(second.id()));
        });
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

    // The temperature classification is shared with the frozen climate state and lives in plan.

    public enum HumidityType { DRY, MEDIUM, WET }

    /** Rules apply to land ownership on the final macro terrain, before river/lake biome overlays. */
    public record TerrainRule(Set<String> allowedTerrain, Double minHeight, Double maxHeight, int temperatureLevel,
                              Map<TemperatureType, Double> temperatures, Double preferredMinHeight,
                              Double preferredMaxHeight, double heightPenalty, double fillerWeight, Integer adventureLevel,
                              Map<HumidityType, Double> humidities, boolean shoreOnly,
                              Set<String> allowedTemplates, Set<String> landforms) {
        public TerrainRule(Set<String> allowed, Double min, Double max, int temperature,
                           Map<TemperatureType, Double> temperatures, Double preferredMin, Double preferredMax,
                           double heightPenalty, double fillerWeight, Integer adventureLevel,
                           Map<HumidityType, Double> humidities, boolean shoreOnly) {
            this(allowed,min,max,temperature,temperatures,preferredMin,preferredMax,heightPenalty,fillerWeight,
                 adventureLevel,humidities,shoreOnly,io.github.luoyan.adventureworldgen.terrain.TerrainTemplate.ids(),Set.of());
        }
        public TerrainRule(Set<String> allowed, Double min, Double max, int temperature,
                           Map<TemperatureType, Double> temperatures, Double preferredMin, Double preferredMax,
                           double heightPenalty, double fillerWeight, Integer adventureLevel) {
            this(allowed,min,max,temperature,temperatures,preferredMin,preferredMax,heightPenalty,fillerWeight,adventureLevel,Map.of(),false);
        }
        public TerrainRule(Set<String> allowed, Double min, Double max, int temperature) {
            this(allowed,min,max,temperature,Map.of(TemperatureType.fromLevel(temperature),1.0),null,null,1,1,null);
        }
        public TerrainRule(Set<String> allowedTerrain, Double minHeight, Double maxHeight) {
            this(allowedTerrain, minHeight, maxHeight, 5, TemperatureType.unrestricted(),null,null,1,1,null);
        }
        public static final Set<String> TEMPLATES = Set.of("plains", "hills", "plateau", "mountains");
        public TerrainRule {
            allowedTerrain = Set.copyOf(allowedTerrain);
            allowedTemplates = Set.copyOf(allowedTemplates);
            landforms = Set.copyOf(landforms);
            if(!TEMPLATES.containsAll(allowedTerrain)||allowedTerrain.isEmpty()
                ||!io.github.luoyan.adventureworldgen.terrain.TerrainTemplate.ids().containsAll(allowedTemplates)
                ||allowedTemplates.isEmpty()||!Set.of("lowland","foothill","slope","peak").containsAll(landforms))
                throw new IllegalArgumentException("invalid terrain/template/landform selection");
            temperatures = Map.copyOf(temperatures);
            humidities = Map.copyOf(humidities);
            for(double weight:humidities.values())if(!Double.isFinite(weight)||weight<=0)
                throw new IllegalArgumentException("humidity preferences must be finite and positive");
            if (temperatureLevel < 0 || temperatureLevel > 10) throw new IllegalArgumentException("temperature_level must be in [0,10]");
        }
        public double heightCost(double height) {
            double deviation = preferredMinHeight == null ? 0 : Math.max(0, preferredMinHeight-height);
            deviation += preferredMaxHeight == null ? 0 : Math.max(0,height-preferredMaxHeight);
            return heightPenalty * deviation / 32.0;
        }
        public Set<String> effectiveTemplates() {
            var result=new java.util.TreeSet<String>();
            for(var t:io.github.luoyan.adventureworldgen.terrain.TerrainTemplate.values())
                if(allowedTerrain.contains(t.category())&&allowedTemplates.contains(t.id()))result.add(t.id());
            return Set.copyOf(result);
        }
        public boolean accepts(MacroSample sample) {
            return allowedTerrain.contains(sample.terrainTemplate())
                    && allowedTemplates.contains(sample.recipe())
                    && (sample.secondaryWeight() <= 0 || (allowedTemplates.contains(sample.secondaryRecipe())
                        && allowedTerrain.contains(io.github.luoyan.adventureworldgen.terrain.TerrainTemplate.byId(sample.secondaryRecipe()).category())))
                    && (landforms.isEmpty() || landforms.contains(sample.landform()))
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
        public static final AreaRange DEFAULT = new AreaRange(32_768, Long.MAX_VALUE, 393_216);

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
