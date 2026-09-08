import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.persistence.PlanV2Codec;
import io.github.luoyan.adventureworldgen.terrain.TerrainTemplate;
import java.nio.file.*;
import java.util.*;

/** Local relief and slope measured from the exact final, eroded and hydrological height field. */
public class FrozenTerrainMetrics {
    private static final class Metrics {
        final List<Double> relief=new ArrayList<>(),slope=new ArrayList<>();
        int flat,composite,foothill,side,peak;
    }
    public static void main(String[] args)throws Exception {
        Path directory=Path.of(args[0]);
        var config=new AdventureWorldConfigParser().parse(Files.readString(Path.of(
                "neoforge/src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json")));
        var report=new StringBuilder("seed\ttemplate\tsamples\tlocal_relief_64_p50\tlocal_relief_64_p95\tslope_p50\tslope_p95\tflat_fraction\tcomposite\tfoothill\tslope_landform\tpeak\n");
        for(int a=1;a<args.length;a++) {
            long seed=Long.parseLong(args[a]);
            var plan=new PlanV2Codec().decode(Files.readAllBytes(directory.resolve(seed+"-plan.json")),
                    new ContentId("adventureworldgen:default"),"audit",config);
            var metrics=new TreeMap<String,Metrics>();
            for(int z=-3000;z<=3000;z+=32)for(int x=-3000;x<=3000;x+=32) {
                var s=plan.terrainAt(x,z);if(s.wet())continue;
                var m=metrics.computeIfAbsent(s.recipe(),ignored->new Metrics());
                double low=s.groundSurface(),high=low;
                for(int dz:new int[]{-32,0,32})for(int dx:new int[]{-32,0,32}) {
                    double h=plan.terrainAt(x+dx,z+dz).groundSurface();low=Math.min(low,h);high=Math.max(high,h);
                }
                double dx=plan.terrainAt(x+1,z).groundSurface()-s.groundSurface(),
                       dz=plan.terrainAt(x,z+1).groundSurface()-s.groundSurface();
                m.relief.add(high-low);m.slope.add(Math.hypot(dx,dz));
                if(Math.abs(dx)<=.01&&Math.abs(dz)<=.01)m.flat++;
                if(s.secondaryWeight()>0)m.composite++;
                switch(s.landform()){case "foothill"->m.foothill++;case "slope"->m.side++;case "peak"->m.peak++;}
            }
            for(var t:TerrainTemplate.values()) {
                var m=metrics.get(t.id());if(m==null)continue;
                Collections.sort(m.relief);Collections.sort(m.slope);int n=m.relief.size();
                report.append(String.format(Locale.ROOT,"%d\t%s\t%d\t%.3f\t%.3f\t%.4f\t%.4f\t%.5f\t%d\t%d\t%d\t%d%n",
                        seed,t.id(),n,m.relief.get(n/2),m.relief.get(n*95/100),m.slope.get(n/2),m.slope.get(n*95/100),
                        m.flat/(double)n,m.composite,m.foothill,m.side,m.peak));
            }
            System.out.println("MEASURED final local relief seed="+seed);
        }
        Files.writeString(directory.resolve("frozen-local-metrics.tsv"),report);
    }
}
