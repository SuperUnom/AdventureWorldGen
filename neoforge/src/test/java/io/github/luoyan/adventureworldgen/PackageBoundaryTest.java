package io.github.luoyan.adventureworldgen;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Locks in the layering that the P1 refactor established, so a later change cannot quietly
 * reintroduce the dependencies it removed. These are source-level checks: they are about which
 * package may reference which, not about behaviour.
 */
class PackageBoundaryTest {
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "io", "github", "luoyan",
            "adventureworldgen");

    @Test
    void configLayerHasNoMinecraftOrNeoForgeDependency() throws IOException {
        // config owns the author model, parsing and semantic validation. Datapack reading and
        // registry queries are supplied by the integration layer (see worldgen.ProfileReloadListener).
        assertNoImport("config", "net.minecraft", "net.neoforged");
    }

    @Test
    void planningAlgorithmsDoNotDependOnTheRuntimeLayer() throws IOException {
        // Planner <-> runtime was a real package cycle; progress is now an explicit observer
        // parameter and planned data lives in the plan package.
        assertNoImport("planner", "io.github.luoyan.adventureworldgen.runtime");
    }

    @Test
    void planDataDoesNotDependOnPlanningRuntimeConfigOrHydrology() throws IOException {
        // plan is the shared vocabulary layer (ids, patches, versions, profile, failure contract).
        // Nothing it depends on may import it, so it must stay at the bottom of the graph.
        assertNoImport("plan", "io.github.luoyan.adventureworldgen.planner",
                "io.github.luoyan.adventureworldgen.runtime",
                "io.github.luoyan.adventureworldgen.config",
                "io.github.luoyan.adventureworldgen.hydrology");
    }

    @Test
    void sharedBasePackagesDependOnlyOnEachOther() throws IOException {
        // noise may use spatial (Vec2); spatial must stay dependency-free, because the plan
        // package already depends on it for the ownership grid and a back edge would be a cycle.
        assertNoImport("noise", "io.github.luoyan.adventureworldgen.planner",
                "io.github.luoyan.adventureworldgen.runtime",
                "io.github.luoyan.adventureworldgen.terrain",
                "io.github.luoyan.adventureworldgen.config");
        assertNoImport("spatial", "io.github.luoyan.adventureworldgen.");
    }

    @Test
    void adapterAndConfigLayersDoNotDependOnPlanning() throws IOException {
        // api is the third-party contract surface: it must not contain or reach built-in
        // implementations, so a mod author's contract never pulls in our domain code.
        assertNoImport("api", "io.github.luoyan.adventureworldgen.planner",
                "io.github.luoyan.adventureworldgen.runtime",
                "io.github.luoyan.adventureworldgen.hydrology",
                "io.github.luoyan.adventureworldgen.terrain",
                "io.github.luoyan.adventureworldgen.surface",
                "io.github.luoyan.adventureworldgen.worldgen");
        // Author config describes intent; it must not reach into the solver or the session.
        assertNoImport("config", "io.github.luoyan.adventureworldgen.planner",
                "io.github.luoyan.adventureworldgen.runtime");
    }

    @Test
    void surfaceMaterialsDoNotDependOnHydrologyOrExecution() throws IOException {
        // Surface and river-bed material choice is separate from water geometry and from the
        // Minecraft execution layer, so a material change never requires touching hydrology.
        assertNoImport("surface", "io.github.luoyan.adventureworldgen.hydrology",
                "io.github.luoyan.adventureworldgen.planner",
                "io.github.luoyan.adventureworldgen.runtime",
                "io.github.luoyan.adventureworldgen.worldgen");
    }

    @Test
    void terrainConsumesReservedResultsWithoutDoingDemandSolving() throws IOException {
        // Demand-driven capacity reservation lives in planner; terrain only consumes the frozen
        // TerrainCapacityPlan. A terrain -> planner edge would mean quota solving leaked back in.
        assertNoImport("terrain", "io.github.luoyan.adventureworldgen.planner",
                "io.github.luoyan.adventureworldgen.runtime");
    }

    @Test
    void biomeStrategiesDoNotDependOnPlanningOrGameLifecycle() throws IOException {
        // Biome ownership, transition and water-biome strategies read environment fields and plan
        // data; they must not reach into the solver or the runtime session.
        assertNoImport("biome", "io.github.luoyan.adventureworldgen.planner",
                "io.github.luoyan.adventureworldgen.runtime");
    }

    @Test
    void storageLayerOnlyHandlesFrozenData() throws IOException {
        // persistence encodes and decodes PlanSnapshot: frozen data only. Reaching the runtime
        // session object would make the storage layer depend on executable query state, and a READY
        // reload could then re-enter layout solving through the codec instead of restoring it.
        assertNoImport("persistence", "io.github.luoyan.adventureworldgen.runtime");
    }

    @Test
    void theSharedChunkGeneratorRestoresOnlyRegisteredPieceTypes() throws IOException {
        // P4: support for a structure is a registration, not a branch. The generator may resolve
        // frozen pieces through the piece registry, but naming a concrete piece class or a single
        // structure's piece type constant would mean every new structure edits the generator.
        Path generator = SOURCE_ROOT.resolve("worldgen").resolve("AdventureChunkGenerator.java");
        Path restore = SOURCE_ROOT.resolve("worldgen").resolve("FrozenPieceRestore.java");
        assumeTrue(Files.isRegularFile(generator) && Files.isRegularFile(restore),
                "source tree not found at " + generator.toAbsolutePath());
        String generatorCode = stripComments(Files.readString(generator, StandardCharsets.UTF_8));
        assertFalse(generatorCode.contains("structure.structures."),
                "the shared chunk generator must not reference a concrete structure piece class");
        assertFalse(generatorCode.contains("StructurePieceType."),
                "the shared chunk generator must not branch on a named structure piece type");
        String restoreCode = stripComments(Files.readString(restore, StandardCharsets.UTF_8));
        assertTrue(restoreCode.contains("BuiltInRegistries.STRUCTURE_PIECE"),
                "frozen pieces must be resolved in the registry the game registers piece types in");
        // P4.4: the companion mod's piece type proves the extension point. If the core named it -
        // or named the companion mod at all - the test would be proving a branch instead.
        for (String code : new String[]{generatorCode, restoreCode}) {
            assertFalse(code.contains("testcompanion"),
                    "the core must not name the test companion mod or its piece type");
            assertFalse(code.contains("waystation"),
                    "the core must not name the test companion's structure or piece");
        }
    }

    private static void assertNoImport(String pkg, String... forbiddenPrefixes) throws IOException {
        Path directory = SOURCE_ROOT.resolve(pkg);
        assumeTrue(Files.isDirectory(directory), "source tree not found at " + directory.toAbsolutePath());
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                // Scan the whole source, not just import lines: a fully qualified reference used
                // inline creates exactly the same dependency and would otherwise go unnoticed.
                // Comments are stripped first so documentation may still name other packages.
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                for (String line : code.lines().toList()) {
                    // A package declaration states where the file lives, it is not a dependency.
                    if (line.stripLeading().startsWith("package ")) continue;
                    for (String forbidden : forbiddenPrefixes)
                        if (line.contains(forbidden)) violations.add(file + ": " + line.trim());
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> pkg + " must not depend on " + String.join(", ", forbiddenPrefixes)
                + " but found:\n  " + String.join("\n  ", violations));
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }
}
