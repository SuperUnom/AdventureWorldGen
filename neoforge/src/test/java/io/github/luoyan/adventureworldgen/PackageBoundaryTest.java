package io.github.luoyan.adventureworldgen;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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
    void planDataDoesNotDependOnPlanningOrRuntime() throws IOException {
        assertNoImport("plan", "io.github.luoyan.adventureworldgen.planner",
                "io.github.luoyan.adventureworldgen.runtime");
    }

    @Test
    void sharedNoiseToolsDoNotDependOnPlanningOrRuntime() throws IOException {
        assertNoImport("noise", "io.github.luoyan.adventureworldgen.planner",
                "io.github.luoyan.adventureworldgen.runtime");
    }

    private static void assertNoImport(String pkg, String... forbiddenPrefixes) throws IOException {
        Path directory = SOURCE_ROOT.resolve(pkg);
        assumeTrue(Files.isDirectory(directory), "source tree not found at " + directory.toAbsolutePath());
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.startsWith("import ")) continue;
                    for (String forbidden : forbiddenPrefixes)
                        if (line.contains(forbidden)) violations.add(file + ": " + line.trim());
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> pkg + " must not depend on " + String.join(", ", forbiddenPrefixes)
                + " but found:\n  " + String.join("\n  ", violations));
    }
}
