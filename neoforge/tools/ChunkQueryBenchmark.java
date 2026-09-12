import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.persistence.PlanV2Codec;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;
import com.google.gson.JsonParser;
/** Replays horizontal queries from 256 fresh chunk positions against an existing frozen plan.
 * Args: plan directory containing manifest.json and plan.json.gz; matching profile JSON.
 * This measures query work only, excluding block writes, caves, structures and features. */
public class ChunkQueryBenchmark {
 static volatile long sink;
 public static void main(String[] args)throws Exception {
  if(args.length!=2)throw new IllegalArgumentException("expected plan directory and matching profile JSON");
  var dir=Path.of(args[0]);
  var config=new AdventureWorldConfigParser().parse(Files.readString(Path.of(args[1])));
  var hash=JsonParser.parseString(Files.readString(dir.resolve("manifest.json"))).getAsJsonObject().get("input_sha256").getAsString();
  byte[] bytes;try(var in=new GZIPInputStream(Files.newInputStream(dir.resolve("plan.json.gz")))){bytes=in.readAllBytes();}
  var plan=GeneratedAdventurePlan.restore(config,
    new PlanV2Codec().decode(bytes,new ContentId("adventureworldgen:default"),hash));
  for(int run=0;run<4;run++) {
   long start=System.nanoTime(),sum=0;
   for(int chunk=0;chunk<64;chunk++) {
    int bx=(chunk%8-4)*16+run*192,bz=(chunk/8-4)*16;
    for(int y=-16;y<80;y++)for(int x=0;x<4;x++)for(int z=0;z<4;z++)sum+=plan.biomeAt(bx+x*4,y*4,bz+z*4).hashCode();
    for(int pass=0;pass<4;pass++)for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
     var sample=plan.terrainAt(bx+x+.5,bz+z+.5);sum+=Double.doubleToLongBits(sample.groundSurface());
    }
   }
   sink=sum;System.out.printf("run=%d chunks=64 seconds=%.3f checksum=%d%n",run,(System.nanoTime()-start)/1e9,sum);
  }
 }
}
