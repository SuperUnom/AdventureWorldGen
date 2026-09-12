package io.github.luoyan.adventureworldgen.persistence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.luoyan.adventureworldgen.api.PlanRepository;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Same-filesystem, READY-last persistence for immutable plan-v2 payloads. */
public final class AtomicPlanRepository implements PlanRepository {
    private static final String PAYLOAD = "plan.json.gz";
    private static final String MANIFEST = "manifest.json";
    private static final String READY = "READY";

    @Override
    public Optional<ReadyPlan> loadReady(Path worldDirectory, ContentId profileId,
                                         String expectedInputHash) throws IOException {
        Path directory = finalDirectory(worldDirectory, profileId);
        if (!Files.isRegularFile(directory.resolve(READY))) return Optional.empty();
        try {
            JsonObject manifest = JsonParser.parseString(Files.readString(directory.resolve(MANIFEST))).getAsJsonObject();
            if (manifest.size() != 4 || !PlannerProfile.V2.planFormatVersion().equals(required(manifest, "format"))
                    || !profileId.value().equals(required(manifest, "profile"))
                    || !expectedInputHash.equals(required(manifest, "input_sha256"))) return Optional.empty();
            byte[] compressed = Files.readAllBytes(directory.resolve(PAYLOAD));
            String expectedPayloadHash = required(manifest, "payload_sha256");
            if (!expectedPayloadHash.equals(sha256(compressed))) return Optional.empty();
            byte[] canonical;
            try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                canonical = input.readAllBytes();
            }
            return Optional.of(new ReadyPlan(canonical, expectedPayloadHash, expectedInputHash));
        } catch (RuntimeException | IOException malformed) {
            return Optional.empty();
        }
    }

    @Override
    public void publishAtomically(Path worldDirectory, ContentId profileId, byte[] canonicalPlan,
                                  String inputHash) throws IOException {
        Path parent = plansDirectory(worldDirectory);
        Files.createDirectories(parent);
        Path destination = finalDirectory(worldDirectory, profileId);
        Optional<ReadyPlan> existing = loadReady(worldDirectory, profileId, inputHash);
        if (existing.isPresent()) {
            if (!MessageDigest.isEqual(existing.get().canonicalPlan(), canonicalPlan))
                throw new IOException("a different READY plan already exists for the same input hash");
            return;
        }
        // A corrupt/incomplete prior destination is not readable and may be discarded for a deterministic retry.
        if (Files.exists(destination)) deleteExactTree(destination);

        Path temporary = parent.resolve("." + directoryName(profileId) + ".tmp-" + UUID.randomUUID());
        Files.createDirectory(temporary);
        boolean published = false;
        try {
            byte[] compressed = gzip(canonicalPlan);
            String payloadHash = sha256(compressed);
            writeAndForce(temporary.resolve(PAYLOAD), compressed);
            String manifest = "{\"format\":\"" + PlannerProfile.V2.planFormatVersion()
                    + "\",\"input_sha256\":\"" + inputHash + "\",\"payload_sha256\":\""
                    + payloadHash + "\",\"profile\":\"" + profileId.value() + "\"}";
            writeAndForce(temporary.resolve(MANIFEST), manifest.getBytes(StandardCharsets.UTF_8));
            writeAndForce(temporary.resolve(READY), new byte[0]);
            forceDirectory(temporary);
            try {
                Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("plan directory does not support required atomic rename", unsupported);
            }
            forceDirectory(parent);
            published = true;
        } finally {
            if (!published && Files.exists(temporary)) deleteExactTree(temporary);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] gzip(byte[] source) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(source); }
        return bytes.toByteArray();
    }

    private static void writeAndForce(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
        Files.setLastModifiedTime(path, FileTime.fromMillis(0));
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }

    private static String required(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.getAsJsonPrimitive(field).isString()) throw new IllegalArgumentException("invalid manifest field " + field);
        return object.get(field).getAsString();
    }

    private static Path plansDirectory(Path worldDirectory) {
        return worldDirectory.resolve("adventureworldgen").resolve("plans");
    }

    private static Path finalDirectory(Path worldDirectory, ContentId profileId) {
        return plansDirectory(worldDirectory).resolve(directoryName(profileId));
    }

    private static String directoryName(ContentId profileId) {
        return profileId.value().replace(':', '_').replace('/', '_');
    }

    private static void deleteExactTree(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
