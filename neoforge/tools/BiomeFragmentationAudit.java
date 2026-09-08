import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.runtime.*;
import io.github.luoyan.adventureworldgen.terrain.*;
import io.github.luoyan.adventureworldgen.hydrology.*;
import io.github.luoyan.adventureworldgen.spatial.*;
import java.nio.file.*;
import java.util.*;

/** Isolates filler + terrain transitions from required masks and erosion. Same probe runs
 * against the published old JAR and the new classes; output is not an in-game screenshot. */
public class BiomeFragmentationAudit {
    public static void main(String[] args) throws Exception {
        var config = new AdventureWorldConfigParser().parse(Files.newBufferedReader(Path.of(
            "neoforge/src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json")));
        System.out.println("seed\tcenter_x\tcenter_z\tcomponents\tsmall_components\tsmall_cells\tboundary_edges");
        for (long seed : new long[]{345705185492107788L, 4126649097427443736L, 1}) {
            var coast = new Coastline(List.of(new Vec2(-3000,-3000),new Vec2(3000,-3000),new Vec2(3000,3000),new Vec2(-3000,3000)));
            var plan = new GeneratedAdventurePlan(seed, config, coast, new RiverNetwork(List.of(),List.of(),"audit"),64,128,256,"audit",null);
            for (int[] center : new int[][]{{0,0},{1144,72},{-1200,1200}}) {
                int n=256; String[] labels=new String[n*n]; boolean[] seen=new boolean[n*n];
                int edges=0, components=0, small=0, smallCells=0;
                for(int z=0;z<n;z++)for(int x=0;x<n;x++) {
                    int p=z*n+x; labels[p]=plan.landBiomeAt(center[0]-512+x*4,center[1]-512+z*4).value();
                    if(x>0&&!labels[p].equals(labels[p-1]))edges++;
                    if(z>0&&!labels[p].equals(labels[p-n]))edges++;
                }
                for(int p=0;p<labels.length;p++)if(!seen[p]) {
                    int area=0; boolean border=false; ArrayDeque<Integer> q=new ArrayDeque<>();q.add(p);seen[p]=true;
                    while(!q.isEmpty()) {
                        int v=q.removeFirst(),x=v%n,z=v/n; area++;border|=x==0||x==n-1||z==0||z==n-1;
                        for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                            int nx=x+d[0],nz=z+d[1],v2=nz*n+nx;
                            if(nx>=0&&nx<n&&nz>=0&&nz<n&&!seen[v2]&&labels[v].equals(labels[v2])){seen[v2]=true;q.add(v2);}
                        }
                    }
                    components++;if(!border&&area<=16){small++;smallCells+=area;}
                }
                System.out.printf("%d\t%d\t%d\t%d\t%d\t%d\t%d%n",seed,center[0],center[1],components,small,smallCells,edges);
            }
        }
    }
}
