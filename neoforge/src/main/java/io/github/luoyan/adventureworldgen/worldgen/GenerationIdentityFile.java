package io.github.luoyan.adventureworldgen.worldgen;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** World-level compatibility guard. Changing READY must never allow half a structure to change resources. */
public final class GenerationIdentityFile {
    private GenerationIdentityFile() {}
    public static void check(Path world, String digest) throws IOException {
        Path file = world.resolve("adventureworldgen/structure-generation.sha256");
        if (Files.exists(file)) {
            if (!Files.readString(file).strip().equals(digest))
                throw new IllegalStateException("structure generation inputs changed; use a new world (existing chunks are not migrated)");
            return;
        }
        Path region = world.resolve("region");
        if (Files.isDirectory(region)) {
            try (var files = Files.list(region)) {
                if (files.anyMatch(p -> p.getFileName().toString().endsWith(".mca")))
                    throw new IllegalStateException("world has generated chunks but no structure execution identity; use a new world");
            }
        }
    }
    public static void verifyOrCreate(Path world, String digest) throws IOException {
        check(world, digest);
        Path file = world.resolve("adventureworldgen/structure-generation.sha256");
        if (Files.exists(file)) return;
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "structure-generation-", ".tmp");
        try {
            Files.writeString(temporary, digest + "\n", StandardCharsets.UTF_8);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
}
