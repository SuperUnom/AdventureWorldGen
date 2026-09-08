package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.runtime.ColumnQueryCache;

/** Bounded memoization of exact integer/half-block queries on an immutable surface.
 * All other coordinates use the continuous function directly, without quantization. */
public final class ExactGridTerrain implements MacroTerrain {
    private final MacroTerrain base;
    private final ColumnQueryCache<MacroSample> samples;
    private final java.util.function.BiFunction<Integer,Integer,MacroSample> query;

    public ExactGridTerrain(MacroTerrain base,int capacity) {
        this.base=base;this.samples=new ColumnQueryCache<>(capacity);
        this.query=(x,z)->base.sample(x*.5,z*.5);
    }

    @Override public MacroSample sample(double x,double z) {
        double sx=x*2,sz=z*2;
        int ix=(int)sx,iz=(int)sz;
        if(sx!=ix||sz!=iz||Double.doubleToRawLongBits(x)==Long.MIN_VALUE
                ||Double.doubleToRawLongBits(z)==Long.MIN_VALUE)return base.sample(x,z);
        return samples.get(ix,iz,query);
    }
}
