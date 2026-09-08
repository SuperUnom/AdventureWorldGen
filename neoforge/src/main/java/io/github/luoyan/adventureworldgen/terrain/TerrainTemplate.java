package io.github.luoyan.adventureworldgen.terrain;

import java.util.*;

/** Concrete recipes; the four planning categories remain a separate compatibility contract. */
public enum TerrainTemplate {
    STEPPE("plains", 18, 12), PLAINS("plains", 20, 22),
    HILLS_1("hills", 16, 52), HILLS_2("hills", 12, 72), DALES("hills", 14, 42),
    TORRIDONIAN("hills", 6, 82), PLATEAU("plateau", 10, 90), BADLANDS("plateau", 5, 90),
    MOUNTAINS_1("mountains", 8, 180), MOUNTAINS_2("mountains", 6, 165),
    MOUNTAINS_3("mountains", 5, 200), VOLCANO("mountains", 0.6, 180);

    private final String category;
    private final double weight, amplitude;
    TerrainTemplate(String category, double weight, double amplitude) {
        this.category=category; this.weight=weight; this.amplitude=amplitude;
    }
    public String id() { return name().toLowerCase(Locale.ROOT); }
    public String category() { return category; }
    public RegionTerrain.Template planningCategory() { return RegionTerrain.Template.valueOf(category.toUpperCase(Locale.ROOT)); }
    public boolean mountain() { return category.equals("mountains"); }
    public Settings defaults() { return new Settings(weight,1,amplitude,1); }
    public static TerrainTemplate byId(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }
    public static Set<String> ids() {
        var ids=new TreeSet<String>(); for(var t:values())ids.add(t.id()); return Set.copyOf(ids);
    }
    public record Settings(double weight, double horizontalScale, double verticalAmplitude, double detailStrength) {
        public Settings {
            if(!Double.isFinite(weight)||weight<0||weight>10000
                ||!Double.isFinite(horizontalScale)||horizontalScale<0.25||horizontalScale>8
                ||!Double.isFinite(verticalAmplitude)||verticalAmplitude<=0||verticalAmplitude>220
                ||!Double.isFinite(detailStrength)||detailStrength<0||detailStrength>2)
                throw new IllegalArgumentException("weight [0,10000], horizontal_scale [0.25,8], vertical_amplitude (0,220], detail_strength [0,2] required");
        }
    }
}
