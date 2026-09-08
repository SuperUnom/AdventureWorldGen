package io.github.luoyan.adventureworldgen.terrain;

import java.util.*;

/** Author settings, also included in the canonical input/cache fingerprint. Weight zero disables a recipe. */
public record TerrainSettings(Map<TerrainTemplate,TerrainTemplate.Settings> templates,
                              boolean composite, boolean mountainRanges) {
    public TerrainSettings {
        var complete=new EnumMap<TerrainTemplate,TerrainTemplate.Settings>(TerrainTemplate.class);
        for(var t:TerrainTemplate.values())complete.put(t,t.defaults());
        complete.putAll(templates); templates=Map.copyOf(complete);
        if(templates.values().stream().noneMatch(s->s.weight()>0))throw new IllegalArgumentException("at least one terrain template must be enabled");
    }
    public static TerrainSettings defaults() { return new TerrainSettings(Map.of(),true,true); }
    public TerrainTemplate.Settings get(TerrainTemplate t) { return templates.get(t); }
    /** Conservative height envelope, including plateau's positive post-terrace surface. */
    public double maximumShape(TerrainTemplate primary,TerrainTemplate secondary) {
        return (primary==TerrainTemplate.PLATEAU||secondary==TerrainTemplate.PLATEAU)
                ?Math.max(1.05,1+.05*get(TerrainTemplate.PLATEAU).detailStrength()):1.05;
    }
    public Set<String> enabled() {
        var result=new TreeSet<String>(); templates.forEach((t,s)->{if(s.weight()>0)result.add(t.id());}); return Set.copyOf(result);
    }
}
