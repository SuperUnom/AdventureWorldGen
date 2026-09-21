package io.github.luoyan.adventureworldgen.persistence;

import io.github.luoyan.adventureworldgen.plan.ContentId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtomicPlanRepositoryTest {
    private static final ContentId PROFILE = new ContentId("adventureworldgen:default");
    @TempDir Path world;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishesLoadsAndTreatsIdenticalRepublishAsNoOp(boolean windowsPolicy) throws Exception {
        var repository = repository(windowsPolicy);
        byte[] plan = "{\"format\":\"plan-v3\",\"seed\":42}".getBytes(StandardCharsets.UTF_8);
        String input = AtomicPlanRepository.sha256("input".getBytes(StandardCharsets.UTF_8));
        repository.publishAtomically(world, PROFILE, plan, input);
        Path directory = planDirectory();
        long manifestTime = Files.getLastModifiedTime(directory.resolve("manifest.json")).toMillis();
        repository.publishAtomically(world, PROFILE, plan, input);

        var loaded = repository.loadReady(world, PROFILE, input).orElseThrow();
        assertArrayEquals(plan, loaded.canonicalPlan());
        assertEquals(manifestTime, Files.getLastModifiedTime(directory.resolve("manifest.json")).toMillis());
        assertEquals(0, Files.getLastModifiedTime(directory.resolve("plan.json.gz")).toMillis());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsTruncationWrongInputAndUnknownVersionThenCanRetry(boolean windowsPolicy) throws Exception {
        var repository = repository(windowsPolicy);
        byte[] plan = "{\"format\":\"plan-v3\"}".getBytes(StandardCharsets.UTF_8);
        String input = AtomicPlanRepository.sha256("input".getBytes(StandardCharsets.UTF_8));
        repository.publishAtomically(world, PROFILE, plan, input);
        assertFalse(repository.loadReady(world, PROFILE, "wrong").isPresent());

        Files.write(planDirectory().resolve("plan.json.gz"), new byte[] {1, 2, 3});
        assertFalse(repository.loadReady(world, PROFILE, input).isPresent());
        repository.publishAtomically(world, PROFILE, plan, input);
        assertTrue(repository.loadReady(world, PROFILE, input).isPresent());

        String manifest = Files.readString(planDirectory().resolve("manifest.json"))
                .replace("plan-v3", "plan-v999");
        Files.writeString(planDirectory().resolve("manifest.json"), manifest);
        assertFalse(repository.loadReady(world, PROFILE, input).isPresent());
    }

    @Test
    void ignoresTemporaryDirectoryWithoutReadyMarker() throws Exception {
        Files.createDirectories(world.resolve("adventureworldgen/plans/.adventureworldgen_default.tmp-interrupted"));
        assertFalse(new AtomicPlanRepository().loadReady(world, PROFILE, "input").isPresent());
    }

    @Test
    void windowsDirectorySyncStillRejectsMissingPathsAndRegularFiles() throws Exception {
        var repository = new AtomicPlanRepository(false);
        assertThrows(NoSuchFileException.class, () -> repository.forceDirectory(world.resolve("missing")));
        Path file = Files.writeString(world.resolve("file"), "not a directory");
        assertThrows(NotDirectoryException.class, () -> repository.forceDirectory(file));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicationIoFailureIsNotSuppressed(boolean windowsPolicy) throws Exception {
        Files.writeString(world.resolve("adventureworldgen"), "blocks creation of the plans directory");
        var repository = repository(windowsPolicy);
        assertThrows(IOException.class, () -> repository.publishAtomically(world, PROFILE, new byte[] {1}, "input"));
        assertFalse(repository.loadReady(world, PROFILE, "input").isPresent());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conflictingRepublishPreservesReadyPlan(boolean windowsPolicy) throws Exception {
        var repository = repository(windowsPolicy);
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        repository.publishAtomically(world, PROFILE, original, "input");
        assertThrows(IOException.class, () -> repository.publishAtomically(
                world, PROFILE, "different".getBytes(StandardCharsets.UTF_8), "input"));
        assertArrayEquals(original, repository.loadReady(world, PROFILE, "input").orElseThrow().canonicalPlan());
    }

    private AtomicPlanRepository repository(boolean windowsPolicy) {
        return windowsPolicy ? new AtomicPlanRepository(false) : new AtomicPlanRepository();
    }

    private Path planDirectory() {
        return world.resolve("adventureworldgen/plans/adventureworldgen_default");
    }
}
