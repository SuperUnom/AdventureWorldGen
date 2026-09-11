package io.github.luoyan.adventureworldgen.plan;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure stages are a closed vocabulary, and the mapping to progress stages is explicit.
 *
 * <p>The failure stage used to be an arbitrary string typed at each throw site while
 * {@code PlanningStage} was an enum used for the progress bar. Automation reading a diagnostic had
 * no way to tell which progress stage a failure belonged to, and a new string could appear without
 * anyone registering it. These checks close both holes.
 */
class FailureStageTest {
    /** Stages that deliberately sit outside the progress bar, with the reason. */
    private static final Set<FailureStage> OUTSIDE_PROGRESS = EnumSet.of(
            FailureStage.ADAPTER_REGISTRATION,   // runs during mod construction, before any world
            FailureStage.CONTENT_PREFLIGHT,      // runs during server start, before progress exists
            FailureStage.UNKNOWN);               // not a real stage: the fallback for foreign input

    @Test
    void everyStageEitherMapsToAProgressStageOrDeclaresItselfOutside() {
        for (FailureStage stage : FailureStage.values()) {
            boolean outside = stage.outsideProgress();
            assertEquals(OUTSIDE_PROGRESS.contains(stage), outside,
                    stage + " changed its progress-stage status without being registered in this test");
            assertEquals(!outside, stage.progressStage().isPresent(),
                    stage + " must either expose a progress stage or declare itself outside the bar");
        }
    }

    @Test
    void lookupsAreStableAndNeverDependOnOrdinals() {
        for (FailureStage stage : FailureStage.values()) {
            if (stage == FailureStage.UNKNOWN) continue;
            assertSame(stage, FailureStage.of(stage.id()), stage.id() + " must round-trip");
            assertSame(stage, FailureStage.of(stage.id().toUpperCase(java.util.Locale.ROOT)),
                    "lookup is case-insensitive");
            assertSame(stage, FailureStage.of("  " + stage.id() + "  "), "lookup trims");
        }
        // Foreign input has a defined fallback rather than an exception, a null or a wrong guess.
        assertSame(FailureStage.UNKNOWN, FailureStage.of("stage-from-a-newer-build"));
        assertSame(FailureStage.UNKNOWN, FailureStage.of(null));
        assertSame(FailureStage.UNKNOWN, FailureStage.of(""));
        assertFalse(FailureStage.UNKNOWN.progressStage().isPresent());
        // Ids are unique, so a lookup can never be ambiguous.
        assertEquals(FailureStage.values().length,
                java.util.Arrays.stream(FailureStage.values()).map(FailureStage::id).distinct().count());
    }

    @Test
    void theStringFormStaysCompatibleWhileTheTypedFormIsAvailable() {
        var failure = new PlanningFailure(PlanningFailure.Code.RESOURCE_LIMIT, FailureStage.COST_GRAPH,
                "compact cost graph exceeds the planner-v2 working memory budget",
                java.util.Map.of("estimated_bytes", 64, "maximum_bytes", 32));
        // The wire/log shape is exactly what it was before the vocabulary existed.
        assertEquals("cost-graph", failure.stage());
        assertEquals("[RESOURCE_LIMIT] cost-graph: compact cost graph exceeds the planner-v2 working memory budget"
                + " {estimated_bytes=64, maximum_bytes=32}", failure.getMessage());
        assertSame(FailureStage.COST_GRAPH, failure.failureStage());
        assertSame(PlanningStage.COSTS, failure.progressStage().orElseThrow());
        // A foreign stage string still reaches the reader unchanged, and classifies as unknown.
        var foreign = new PlanningFailure(PlanningFailure.Code.EXECUTION_FAILED, "some-new-stage", "boom");
        assertEquals("some-new-stage", foreign.stage());
        assertSame(FailureStage.UNKNOWN, foreign.failureStage());
        assertTrue(foreign.progressStage().isEmpty());
    }

    @Test
    void resourceLimitsAndBudgetExhaustionStaySeparatelyMatchable() {
        var resources = new PlanningFailure(PlanningFailure.Code.RESOURCE_LIMIT, FailureStage.COST_GRAPH, "over budget");
        var budget = new PlanningFailure(PlanningFailure.Code.SEARCH_BUDGET_EXHAUSTED,
                FailureStage.COMPETITIVE_GROWTH, "operations exhausted");
        assertNotEquals(resources.code(), budget.code());
        assertNotEquals(resources.failureStage(), budget.failureStage());
        // Same code, different stage: the machine classification and the location are independent.
        var otherLimit = new PlanningFailure(PlanningFailure.Code.RESOURCE_LIMIT, FailureStage.FILLER, "over budget");
        assertEquals(resources.code(), otherLimit.code());
        assertNotEquals(resources.failureStage(), otherLimit.failureStage());
    }

    @Test
    void productionNeverInventsAFailureStageString() throws IOException {
        String configured = System.getProperty("adventureworldgen.sourceRoot");
        assertTrue(configured != null && !configured.isBlank(),
                "missing adventureworldgen.sourceRoot; the test task sets it");
        Path root = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(root), () -> "no source tree at " + root);
        Pattern literal = Pattern.compile(
                "PlanningFailure\\s*\\(\\s*(?:PlanningFailure\\.)?Code\\.\\w+\\s*,\\s*\"([^\"]*)\"");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = literal.matcher(source);
                while (matcher.find()) offenders.add(file.getFileName() + ": \"" + matcher.group(1) + "\"");
            }
        }
        assertTrue(offenders.isEmpty(), () -> "a failure stage was written as a raw string again; "
                + "add it to FailureStage and use the constant:\n  " + String.join("\n  ", offenders));
    }
}
