import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.persistence.PlanV2Codec;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;

/** READY reload of an existing frozen plan: read, decode and rebuild the query object.
 * Args: plan directory containing manifest.json and plan.json.gz; matching profile JSON; repetitions.
 * Repeated inside one JVM, which is the same work a server does once per world load. */
public class PlanReloadBenchmark {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("expected plan directory, profile JSON and repetitions");
        var dir = Path.of(args[0]);
        var config = new AdventureWorldConfigParser().parse(Files.readString(Path.of(args[1])));
        var hash = com.google.gson.JsonParser.parseString(Files.readString(dir.resolve("manifest.json")))
                .getAsJsonObject().get("input_sha256").getAsString();
        int repetitions = Integer.parseInt(args[2]);
        byte[] bytes;
        try (var in = new GZIPInputStream(Files.newInputStream(dir.resolve("plan.json.gz")))) {
            bytes = in.readAllBytes();
        }
        for (int run = 0; run < repetitions; run++) {
            long start = System.nanoTime();
            var snapshot = new PlanV2Codec().decode(bytes, new ContentId("adventureworldgen:default"), hash);
            var plan = GeneratedAdventurePlan.restore(config, snapshot);
            System.out.printf("run=%d reload_seconds=%.3f patches=%d%n",
                    run, (System.nanoTime() - start) / 1e9, plan.biomePatches().size());
        }
    }
}
