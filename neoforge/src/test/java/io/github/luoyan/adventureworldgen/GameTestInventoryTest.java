package io.github.luoyan.adventureworldgen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registered GameTest surface, written out method by method. NeoForge enables a single set of
 * namespaces per launch, so "all tests" requires separate runs; this inventory is what makes the
 * runs checkable against a fixed list instead of a number someone typed into a README.
 *
 * <p>Adding, renaming or deleting a {@code @GameTest} method must update the matching list below
 * together with {@code tools/run-full-gametest.sh} and the acceptance tables that quote these
 * counts. The counterpart is the run itself: the script compares the server's
 * "N tests are now running" against the same group size, so a registration that silently stops
 * being discovered also shows up.
 */
class GameTestInventoryTest {
    static final String TESTMOD_SOURCE_ROOT_PROPERTY = "adventureworldgen.testmodSourceRoot";

    private static final Pattern HOLDER = Pattern.compile("@GameTestHolder\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern TEST_ANNOTATION = Pattern.compile("@GameTest\\s*\\(");
    private static final Pattern VOID_METHOD = Pattern.compile("\\bvoid\\s+(\\w+)\\s*\\(");

    /** The default entry: {@code ./gradlew runGameTestServer}. */
    private static final List<String> DEFAULT_GROUP = List.of(
            "AdventureWorldGameTests#companionProfilePlansSafeSpawn",
            "AdventureWorldGameTests#externalBiomeAdapterAndResourcesRemainActive",
            "AdventureWorldGameTests#plannedOceanColumnsPreserveDeepCavesAndContinuousWater",
            "AdventureWorldGameTests#crashSeedPlansEveryRequiredBiome",
            "AdventureWorldGameTests#constrainedCoastStillPlansAllRequiredBiomes",
            "AdventureWorldGameTests#productionProfilePlansIrregularContinentAtRadius3000",
            "AdventureWorldGameTests#reportedSeedHasContainedDescendingRivers",
            "NativeClimateGameTests#altitudeSnowMatchesNativeTemperateBiomes",
            "SurfaceGameTests#everyTerrainRecipeReachesNativeChunkSurface",
            "SurfaceGameTests#nativeMaterialsAndSurfaceExtensions");

    /** {@code testcompanion_performance}: chunk-query and worldgen-fix regressions. */
    private static final List<String> PERFORMANCE_GROUP = List.of(
            "ChunkQueryGameTests#shelfOceanPreservesNativeCaves",
            "ChunkQueryGameTests#nativeMaterialsStillMatchPlan",
            "ChunkQueryGameTests#allRecipesStillMatchPlan",
            "ChunkQueryGameTests#cachedQueriesPreserveColumnsAndSurfaces",
            "WorldgenFixGameTests#villageFoundationsCrossChunkEdges",
            "WorldgenFixGameTests#snowSpringsAreFilteredBeforeFluidTicks");

    /** {@code testcompanion_capacity}: one long planning run that must not exhaust the search. */
    private static final List<String> CAPACITY_GROUP = List.of(
            "CapacityRegressionGameTests#seed7993PlansAndReloadsWithoutSearchExhaustion");

    /** {@code testcompanion_planning}: fresh planning plus reload on a production profile. */
    private static final List<String> PLANNING_GROUP = List.of(
            "PlanningPerformanceGameTests#productionPlanningAndReload",
            "PlanningPerformanceGameTests#sharedStructureCarriersPlanAndReload",
            "RoadRegressionGameTests#reportedRoadBudgetSeedPlansAndReplays",
            "RoadRegressionGameTests#reportedTaigaDisconnectSeedPlansAndReplays",
            "RoadRegressionGameTests#reportedVillageDistanceSeedUsesInstanceBounds",
            "RoadRegressionGameTests#reportedCrossingAndSpawnBumpSeedPlansAndReplays");

    /** Namespace -> the group that enables it. */
    private static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("testcompanion", DEFAULT_GROUP);
        GROUPS.put("testcompanion_performance", PERFORMANCE_GROUP);
        GROUPS.put("testcompanion_capacity", CAPACITY_GROUP);
        GROUPS.put("testcompanion_planning", PLANNING_GROUP);
        GROUPS.put("testcompanion_roads", List.of("RoadGameTests#frozenRoadsRespectChunkOrderAndPlayerEdits",
                "RoadGameTests#neighbouringDecorationCannotOverwriteRoadOrHeadroom",
                "RoadGameTests#shortBridgePreservesWaterAndItsApproaches"));
        GROUPS.put("testcompanion_structure", List.of(
                "StructureExecutionGameTests#locateChecksNearestStartsOnlyAndConsumesReturnedReferences",
                "StructureExecutionGameTests#realChunksGenerateInEitherOrderAndResumeAfterReload",
                "StructureExecutionGameTests#invalidInputsFailBeforeSilentStructureLoss",
                "StructureExecutionGameTests#templateFootprintsMatchNativeStartsAndRotation",
                "StructureTerrainGameTests#fillFlattenNoneAndNativeDensityStayDistinct"));
    }

    private Path testmodSourceRoot;

    @BeforeEach
    void locateTestmodSourceTree() {
        String configured = System.getProperty(TESTMOD_SOURCE_ROOT_PROPERTY);
        assertNotNull(configured, () -> "missing system property " + TESTMOD_SOURCE_ROOT_PROPERTY);
        assertFalse(configured.isBlank(), () -> "blank system property " + TESTMOD_SOURCE_ROOT_PROPERTY);
        Path root = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(root), () -> TESTMOD_SOURCE_ROOT_PROPERTY + " does not point at a directory: " + root);
        testmodSourceRoot = root;
    }

    @Test
    void registeredGameTestsMatchTheAcceptanceInventory() throws IOException {
        Map<String, List<String>> found = scanTestmodSources();
        Map<String, List<String>> problems = new TreeMap<>();
        for (Map.Entry<String, List<String>> group : GROUPS.entrySet()) {
            List<String> actual = new ArrayList<>(found.getOrDefault(group.getKey(), List.of()));
            List<String> expected = new ArrayList<>(group.getValue());
            actual.sort(String::compareTo);
            expected.sort(String::compareTo);
            if (!actual.equals(expected)) problems.put(group.getKey(), actual);
        }
        assertTrue(problems.isEmpty(), () -> "the registered GameTest surface no longer matches the inventory in "
                + GameTestInventoryTest.class.getSimpleName()
                + "; update the list, tools/run-full-gametest.sh and the acceptance tables:\n"
                + describe(problems));
        for (String namespace : found.keySet()) {
            assertTrue(GROUPS.containsKey(namespace), () -> "namespace '" + namespace
                    + "' is registered in the test companion but belongs to no acceptance group; "
                    + "add it to a group in this inventory and to tools/gametest-group.gradle");
        }
    }

    @Test
    void everyAcceptanceGroupHasTheDocumentedSize() throws IOException {
        Map<String, List<String>> found = scanTestmodSources();
        // The sizes expected by tools/run-full-gametest.sh.
        assertEquals(10, found.getOrDefault("testcompanion", List.of()).size(), "default group size");
        assertEquals(6, found.getOrDefault("testcompanion_performance", List.of()).size(), "performance group size");
        assertEquals(1, found.getOrDefault("testcompanion_capacity", List.of()).size(), "capacity group size");
        assertEquals(6, found.getOrDefault("testcompanion_planning", List.of()).size(), "planning group size");
        assertEquals(5, found.getOrDefault("testcompanion_structure", List.of()).size(), "structure group size");
        int total = GROUPS.values().stream().mapToInt(List::size).sum();
        assertEquals(3, found.getOrDefault("testcompanion_roads", List.of()).size(), "roads group size");
        assertEquals(31, total, "the full acceptance entry covers every registered method exactly once");
    }

    private Map<String, List<String>> scanTestmodSources() throws IOException {
        Map<String, List<String>> byNamespace = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(testmodSourceRoot)) {
            files = walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        for (Path file : files) {
            String source = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher holder = HOLDER.matcher(source);
            if (!holder.find()) continue;
            String namespace = holder.group(1);
            String className = file.getFileName().toString().replace(".java", "");
            String[] lines = source.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!TEST_ANNOTATION.matcher(lines[i]).find()) continue;
                String method = null;
                for (int j = i; j < Math.min(i + 8, lines.length); j++) {
                    Matcher candidate = VOID_METHOD.matcher(lines[j]);
                    if (candidate.find()) {
                        method = candidate.group(1);
                        break;
                    }
                }
                // stripComments keeps every newline, so the array index is the 1-based file line.
                if (method == null) {
                    throw new IllegalStateException("cannot read the method name of a @GameTest in "
                            + file + " at line " + (i + 1));
                }
                byNamespace.computeIfAbsent(namespace, key -> new ArrayList<>()).add(className + "#" + method);
            }
        }
        return byNamespace;
    }

    private static String describe(Map<String, List<String>> problems) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : problems.entrySet()) {
            text.append("  ").append(entry.getKey()).append(" now has ")
                    .append(entry.getValue().size()).append(":\n");
            for (String method : entry.getValue()) text.append("    ").append(method).append('\n');
        }
        return text.toString();
    }

    /**
     * Removes comments while keeping every newline, so a reported line number still matches the
     * file and a wrapped annotation keeps its layout. String literals are skipped so a {@code //}
     * inside one is not mistaken for a comment.
     */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"') {
                out.append(c);
                i++;
                while (i < source.length()) {
                    char inner = source.charAt(i);
                    out.append(inner);
                    i++;
                    if (inner == '\\' && i < source.length()) {
                        out.append(source.charAt(i));
                        i++;
                    } else if (inner == '"') {
                        break;
                    }
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i = Math.min(i + 2, source.length());
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
