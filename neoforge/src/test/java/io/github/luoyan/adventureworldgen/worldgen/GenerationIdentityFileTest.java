package io.github.luoyan.adventureworldgen.worldgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GenerationIdentityFileTest {
    @TempDir Path world;
    @Test void changedIdentityDoesNotOverwriteTheOriginalGuard() throws Exception {
        GenerationIdentityFile.verifyOrCreate(world, "first");
        GenerationIdentityFile.verifyOrCreate(world, "first");
        assertThrows(IllegalStateException.class, () -> GenerationIdentityFile.verifyOrCreate(world, "changed"));
        assertEquals("first\n", Files.readString(world.resolve("adventureworldgen/structure-generation.sha256")));
    }
    @Test void existingChunksCannotBeAdoptedWithoutAnExecutionIdentity() throws Exception {
        Files.createDirectory(world.resolve("region"));
        Files.write(world.resolve("region/r.0.0.mca"), new byte[0]);
        assertThrows(IllegalStateException.class, () -> GenerationIdentityFile.verifyOrCreate(world, "first"));
        assertFalse(Files.exists(world.resolve("adventureworldgen/structure-generation.sha256")));
    }
}
