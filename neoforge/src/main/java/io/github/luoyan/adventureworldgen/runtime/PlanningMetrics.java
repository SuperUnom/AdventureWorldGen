package io.github.luoyan.adventureworldgen.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.*;
import java.io.IOException;

/** Diagnostic timing only; deliberately excluded from deterministic plan bytes and random inputs. */
final class PlanningMetrics {
    private final Map<String,Long> millis=new LinkedHashMap<>();
    private long previous=System.nanoTime(), observedHeap;
    private final com.google.gson.JsonArray adventure=new com.google.gson.JsonArray();
    void adventure(java.util.List<GeneratedAdventurePlan.PlannedBiomePatch> patches,
                   io.github.luoyan.adventureworldgen.cost.CostPlanner.Result costs,double radius) {
        for(var patch:patches) {
            if(patch.patchId().startsWith("filler/"))continue;
            var entry=new com.google.gson.JsonObject();entry.addProperty("patch",patch.patchId());
            entry.addProperty("biome",patch.biomeId().value());entry.addProperty("requested",patch.adventureLevel());
            entry.addProperty("actual",costs.normalizedPreferenceAt(patch.anchorX(),patch.anchorZ(),radius));
            adventure.add(entry);
        }
    }
    void finish(String stage) {
        long now=System.nanoTime(); millis.put(stage,(now-previous)/1_000_000); previous=now;
        observedHeap=Math.max(observedHeap,Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
    }
    void write(Path world,long seed,GeneratedAdventurePlan plan) throws IOException {
        var json=new com.google.gson.JsonObject(); json.addProperty("seed",seed);
        var stages=new com.google.gson.JsonObject(); millis.forEach(stages::addProperty); json.add("stage_ms",stages);
        json.addProperty("observed_heap_bytes",observedHeap);
        var gson=new com.google.gson.Gson();
        json.add("temperature_target_ratios",gson.toJsonTree(plan.climate().targetRatios()));
        json.add("temperature_actual_ratios",gson.toJsonTree(plan.climate().actualRatios()));
        json.add("temperature_land_supply",gson.toJsonTree(plan.climate().supply()));
        json.addProperty("filler_seeds",plan.fillerSeedCount());
        json.add("adventure_preferences",adventure);
        Path file=world.resolve("adventureworldgen/planning-metrics.json");
        Files.createDirectories(file.getParent()); Files.writeString(file,json.toString()+"\n");
    }
}
