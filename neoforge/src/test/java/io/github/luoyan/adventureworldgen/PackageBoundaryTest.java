package io.github.luoyan.adventureworldgen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the layering that the P1 refactor established, so a later change cannot quietly
 * reintroduce the dependencies it removed. These are source-level checks: they are about which
 * package may reference which, not about behaviour.
 *
 * <p>The source tree is located through the {@value #SOURCE_ROOT_PROPERTY} system property, which
 * {@code build.gradle} sets to an absolute path derived from the project directory. Reading an
 * absolute path is what makes these rules independent of the directory Gradle was started from
 * ({@code .}, the repository root with {@code -p neoforge}, or an absolute {@code -p}); a missing
 * or unreadable tree fails the test instead of silently passing it. Set
 * {@code -PawgSourceRoot=<path>} to point the rules at another tree, which is how the negative
 * check in the acceptance notes is reproduced.
 */
class PackageBoundaryTest {
    static final String SOURCE_ROOT_PROPERTY = "adventureworldgen.sourceRoot";

    /**
     * Packages the rules below are written against. Each one must exist and hold at least one
     * source file: a renamed or deleted package has to break the guard rather than shrink it.
     */
    private static final List<String> REQUIRED_PACKAGES = List.of(
            "api", "biome", "client", "climate", "compat", "config", "cost", "erosion",
            "hydrology", "mixin", "noise", "persistence", "plan", "planner", "runtime",
            "spatial", "terrain", "worldgen");

    /**
     * Responsibilities that are declared in the target layout but have no implementation yet, so
     * an absent directory is the expected state. Their rules activate as soon as the package
     * appears, without ever turning into a skipped test. {@code surface} is the only entry today:
     * surface and river-bed materials are executed by the vanilla surface pipeline
     * ({@code AdventureChunkGenerator.buildPlannedSurface}), so no package owns them yet.
     */
    private static final List<String> RESERVED_PACKAGES = List.of("surface");

    private Path sourceRoot;

    @BeforeEach
    void locateSourceTree() {
        String configured = System.getProperty(SOURCE_ROOT_PROPERTY);
        assertNotNull(configured, () -> "missing system property " + SOURCE_ROOT_PROPERTY
                + "; the layering rules need an absolute source root, which the test task sets");
        assertFalse(configured.isBlank(), () -> "blank system property " + SOURCE_ROOT_PROPERTY);
        Path root = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(root), () -> SOURCE_ROOT_PROPERTY + " does not point at a directory: " + root
                + "; pass an existing tree, or omit the override to use the project default");
        sourceRoot = root;
    }

    @Test
    void everyPackageTheLayeringRulesNameExists() throws IOException {
        // Replaces the old per-rule assumption: a package that the rules depend on must be present
        // and non-empty, otherwise the guard would still be "green" on a tree it never inspected.
        List<String> problems = new ArrayList<>();
        List<String> declared = new ArrayList<>(REQUIRED_PACKAGES);
        declared.addAll(RESERVED_PACKAGES);
        for (String pkg : declared) {
            Path directory = sourceRoot.resolve(pkg);
            if (!Files.isDirectory(directory)) {
                if (RESERVED_PACKAGES.contains(pkg)) continue;
                problems.add(pkg + ": directory is missing");
                continue;
            }
            if (countJavaFiles(directory) == 0 && !RESERVED_PACKAGES.contains(pkg)) {
                problems.add(pkg + ": directory holds no source file");
            }
        }
        assertTrue(problems.isEmpty(), () -> "the layering rules name packages that are not there:\n  "
                + String.join("\n  ", problems));
    }

    @Test
    void configLayerHasNoMinecraftOrNeoForgeDependency() throws IOException {
        // config owns the author model, parsing and semantic validation. Datapack reading and
        // registry queries are supplied by the integration layer (see worldgen.ProfileReloadListener).
        assertNoImport("config", "net.minecraft", "net.neoforged");
    }

    @Test
    void planningAlgorithmsDoNotDependOnMinecraftRuntimeOrWorldgen() throws IOException {
        // Planner <-> runtime was a real package cycle; progress is now an explicit observer
        // parameter and planned data lives in the plan package. Structure planning must remain a
        // pure solver contract rather than importing Minecraft types through worldgen.
        assertNoImport("planner", "net.minecraft", "net.neoforged",
                "io.github.luoyan.adventureworldgen.runtime",
                "io.github.luoyan.adventureworldgen.worldgen");
    }

    @Test
    void planDataDoesNotDependOnPlanningRuntimeConfigOrHydrology() throws IOException {
        // plan is the shared vocabulary layer (ids, patches, versions, profile, failure contract).
        // Nothing it depends on may import it, so it must stay at the bottom of the graph.
        assertNoImport("plan", "net.minecraft", "net.neoforged",
                "io.github.luoyan.adventureworldgen.planner",
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
        // implementations, so a mod author's contract never pulls in our domain code. The reserved
        // surface package is listed so the rule already holds on the day it is created.
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
    void waterGeometryAndErosionChooseNoBlocksOrBiomes() throws IOException {
        // The author model keeps water geometry and the erosion field outside the material
        // pipeline: neither package may reach into Minecraft or into biome selection, so swapping
        // river-bed materials never reopens hydrology. Material strategy itself belongs to the
        // reserved surface responsibility and stays in the execution layer until it exists.
        assertNoImport("hydrology", "net.minecraft", "net.neoforged",
                "io.github.luoyan.adventureworldgen.biome");
        assertNoImport("erosion", "net.minecraft", "net.neoforged",
                "io.github.luoyan.adventureworldgen.biome");
    }

    @Test
    void reservedSurfacePackageKeepsItsBoundaryOnceItExists() throws IOException {
        // surface has no implementation yet, so there is nothing to guard and nothing to skip: the
        // check simply passes while the directory is absent. It becomes a real rule the moment the
        // package is created, because material choice must not reach water geometry or execution.
        if (!Files.isDirectory(sourceRoot.resolve("surface"))) {
            assertTrue(RESERVED_PACKAGES.contains("surface"));
            return;
        }
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
    void vanillaCompatibilityCarriesNoCoreDependency() throws IOException {
        // compat.vanilla holds vanilla-specific implementations and default content rules. The
        // public registration entry consumes them, never the other way round, so this package may
        // use the contracts and the plan vocabulary and nothing else.
        assertNoImport("compat", "io.github.luoyan.adventureworldgen.runtime",
                "io.github.luoyan.adventureworldgen.planner",
                "io.github.luoyan.adventureworldgen.worldgen",
                "io.github.luoyan.adventureworldgen.terrain",
                "io.github.luoyan.adventureworldgen.hydrology",
                "io.github.luoyan.adventureworldgen.config");
    }

    @Test
    void runtimeIsMinecraftFree() throws IOException {
        // The plan registry was keyed by a Minecraft ResourceLocation, which was the only reason this
        // package imported the game. The key is plan vocabulary now and the worldgen boundary
        // converts its serialized profile id once, so runtime is a pure session and query layer.
        assertNoImport("runtime", "net.minecraft", "net.neoforged");
    }

    @Test
    void climateFieldsDoNotDependOnThePlanner() throws IOException {
        // The temperature and humidity fields publish demand statistics but must not compute them:
        // ClimateStatistics is injected, and the author preferences the field needs live on the
        // config model. Together with the rules reading the fields from biome, that is what lets
        // climate sit beside the planner instead of inside it.
        assertNoImport("climate", "io.github.luoyan.adventureworldgen.planner",
                "io.github.luoyan.adventureworldgen.runtime");
    }

    @Test
    void terrainDoesNotDependOnTheAuthorModel() throws IOException {
        // config <-> terrain was the last package cycle: the region sampler took the whole
        // AdventureWorldConfig just to ask which recipes a filler biome admits. It now receives a
        // one-method FillerTerrainPolicy, so the edge is one-way again: the author model names
        // terrain vocabulary, and terrain never sees the model.
        assertNoImport("terrain", "io.github.luoyan.adventureworldgen.config");
    }

    @Test
    void storageLayerOnlyHandlesFrozenData() throws IOException {
        // persistence encodes and decodes PlanSnapshot: frozen data only. Reaching the runtime
        // session object would make the storage layer depend on executable query state, and a READY
        // reload could then re-enter layout solving through the codec instead of restoring it.
        assertNoImport("persistence", "io.github.luoyan.adventureworldgen.runtime");
    }

    @Test
    void plannedStructuresRemainPureDataAndExecutorsOnlyConsumeResolvedInputs() throws IOException {
        assertNoImport("worldgen/structure", "io.github.luoyan.adventureworldgen.planner",
                "io.github.luoyan.adventureworldgen.runtime", "io.github.luoyan.adventureworldgen.config",
                "io.github.luoyan.adventureworldgen.persistence", "io.github.luoyan.adventureworldgen.plan.");
        String plannerCode = stripComments(Files.readString(sourceRoot.resolve("planner/JointPlanner.java"), StandardCharsets.UTF_8));
        for (String forbidden : List.of("StructurePiece", "StructureStart", "CompoundTag", "StructureExecutor", "canonicalNbt", "minecraft:desert_pyramid"))
            assertFalse(plannerCode.contains(forbidden), () -> "planner contains execution detail: " + forbidden);
        try (var files = Files.walk(sourceRoot.resolve("worldgen/structure"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(file));
                assertFalse(code.contains("minecraft:"), () -> "executor contains a concrete vanilla structure ID: " + file);
            }
        }
    }

    private static long countJavaFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(path -> path.toString().endsWith(".java")).count();
        }
    }

    private void assertNoImport(String pkg, String... forbiddenPrefixes) throws IOException {
        Path directory = sourceRoot.resolve(pkg);
        assertTrue(Files.isDirectory(directory), () -> "no such package to check: " + directory);
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
