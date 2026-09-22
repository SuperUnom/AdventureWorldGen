package io.github.luoyan.adventureworldgen.runtime;
import io.github.luoyan.adventureworldgen.plan.PlannedBiomePatch;

import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.*;
import java.io.IOException;

/**
 * Diagnostic timing and statistics for one planning run, written next to the plan.
 *
 * <p>Wall-clock and heap observations only: deliberately excluded from the plan bytes, the input
 * identity and every random input, so a slow or fast run changes nothing about the result. The
 * orchestrator only brackets its stages with {@link #finish(Stage)}; what a stage is called, and
 * what the file contains, stays here.
 */
final class PlanningMetrics {
    /**
     * The stages this file reports. The key strings are the JSON keys of {@code stage_ms}, so
     * renaming one changes the diagnostics file, not the plan.
     */
    enum Stage {
        COAST("coast"), CAPACITY_RESERVATION("capacity_reservation"), EROSION("erosion"), RIVERS("rivers"),
        COST_GRAPH("cost_graph"), FILLER_AND_TRANSITION("filler_and_transition"), VALIDATION("validation"),
        SAVE("save");

        private final String key;
        Stage(String key) { this.key = key; }
        String key() { return key; }
    }

    private final Map<String,Long> millis=new LinkedHashMap<>();
    private long previous=System.nanoTime(), observedHeap;
    private final com.google.gson.JsonArray adventure=new com.google.gson.JsonArray();
    private final com.google.gson.JsonArray relaxations=new com.google.gson.JsonArray();
    void relaxation(io.github.luoyan.adventureworldgen.planner.MinimumAreaPolicy.Relaxation value) {
        relaxations.add(new com.google.gson.Gson().toJsonTree(value));
    }
    void adventure(java.util.List<PlannedBiomePatch> patches,
                   io.github.luoyan.adventureworldgen.cost.CostPlanner.Result costs,double radius) {
        for(var patch:patches) {
            if(patch.patchId().startsWith("filler/"))continue;
            var entry=new com.google.gson.JsonObject();entry.addProperty("patch",patch.patchId());
            entry.addProperty("biome",patch.biomeId().value());entry.addProperty("requested",patch.adventureLevel());
            entry.addProperty("actual",costs.normalizedPreferenceAt(patch.anchorX(),patch.anchorZ(),radius));
            adventure.add(entry);
        }
    }
    void finish(Stage stage) {
        record(stage.key());
    }

    /**
     * Joint planning reports its intermediate checkpoints by name; they share the same timeline, so
     * the diagnostics file keeps one ordered map of stage and checkpoint durations.
     */
    void finish(String checkpoint) {
        record(checkpoint);
    }

    private void record(String key) {
        long now=System.nanoTime(); millis.put(key,(now-previous)/1_000_000); previous=now;
        observedHeap=Math.max(observedHeap,Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
    }
    void write(Path world,long seed,GeneratedAdventurePlan plan) throws IOException {
        var json=new com.google.gson.JsonObject(); json.addProperty("seed",seed);
        var stages=new com.google.gson.JsonObject(); millis.forEach(stages::addProperty); json.add("stage_ms",stages);
        json.addProperty("observed_heap_bytes",observedHeap);
        var gson=new com.google.gson.Gson();
        json.add("humidity_actual_ratios",gson.toJsonTree(plan.climate().humidity().actualRatios()));
        json.add("temperature_target_ratios",gson.toJsonTree(plan.climate().targetRatios()));
        json.add("temperature_actual_ratios",gson.toJsonTree(plan.climate().actualRatios()));
        json.add("temperature_land_supply",gson.toJsonTree(plan.climate().supply()));
        json.addProperty("filler_seeds",plan.fillerSeedCount());
        json.add("area_relaxations",relaxations);
        json.add("operations",gson.toJsonTree(plan.snapshot().diagnostics()));
        json.addProperty("road_operations",plan.roads().operations());
        json.addProperty("road_columns",plan.roads().columns().size());
        json.add("adventure_preferences",adventure);
        Path file=world.resolve("adventureworldgen/planning-metrics.json");
        Files.createDirectories(file.getParent()); Files.writeString(file,json.toString()+"\n");
    }
    void writeFailure(Path world,long seed,String stage,Throwable failure) throws IOException {
        record("unfinished_"+stage.toLowerCase(java.util.Locale.ROOT));
        var json=new com.google.gson.JsonObject();json.addProperty("seed",seed);json.addProperty("failed_stage",stage);
        var stages=new com.google.gson.JsonObject();millis.forEach(stages::addProperty);json.add("stage_ms",stages);
        json.addProperty("observed_heap_bytes",observedHeap);json.addProperty("failure",failure.toString());
        json.add("area_relaxations",relaxations);
        var file=world.resolve("adventureworldgen/planning-failure.json");Files.createDirectories(file.getParent());
        Files.writeString(file,json+"\n");
    }
}
