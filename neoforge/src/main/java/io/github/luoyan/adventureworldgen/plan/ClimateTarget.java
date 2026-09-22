package io.github.luoyan.adventureworldgen.plan;

import java.util.BitSet;
import java.util.List;

/** Pure supply request over the stable climate statistics sites. No author/game objects. */
public record ClimateTarget(String id,long targetArea,boolean required,List<Option> options) {
    public ClimateTarget {options=List.copyOf(options);}
    public record Option(BitSet terrain,int temperatures,int humidities,boolean lowlandCold) {
        public Option {terrain=(BitSet)terrain.clone();}
        @Override public BitSet terrain(){return (BitSet)terrain.clone();}
        public boolean terrainEligible(int index){return terrain.get(index);}
        public boolean permits(int index,int temperature,int humidity) {
            return terrain.get(index)&&(temperatures&(1<<temperature))!=0&&(humidities&(1<<humidity))!=0;
        }
    }
}
