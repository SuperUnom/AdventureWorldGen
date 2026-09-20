package io.github.luoyan.adventureworldgen.persistence;

import io.github.luoyan.adventureworldgen.plan.ContentId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicPlanRepositoryTest {
    private static final ContentId PROFILE = new ContentId("adventureworldgen:default");
    @TempDir Path world;

    @Test
    void publishesLoadsAndTreatsIdenticalRepublishAsNoOp() throws Exception {
        var repository = new AtomicPlanRepository();
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

    @Test
    void rejectsTruncationWrongInputAndUnknownVersionThenCanRetry() throws Exception {
        var repository = new AtomicPlanRepository();
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

    private Path planDirectory() {
        return world.resolve("adventureworldgen/plans/adventureworldgen_default");
    }
}
