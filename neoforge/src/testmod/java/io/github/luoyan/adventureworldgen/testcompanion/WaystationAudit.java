package io.github.luoyan.adventureworldgen.testcompanion;

import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.plan.PlanningStage;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan;
import io.github.luoyan.adventureworldgen.runtime.PlanningProgress;
import io.github.luoyan.adventureworldgen.runtime.RuntimePlanRegistry;
import io.github.luoyan.adventureworldgen.worldgen.FrozenPieceRestore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Normal-world acceptance for the frozen-piece path, driven by {@code runTestCompanionServer}.
 *
 * <p>Two runs against the same world, selected by {@code -Dtestcompanion.audit.phase}:
 *
 * <ul>
 *   <li>{@code cold}: plan, generate every FULL chunk the planned waystation touches, check the
 *       start, the cross-chunk references, the generated blocks and the containers, write the
 *       counts, save and exit.</li>
 *   <li>{@code ready}: restart on the same world, prove the plan came from READY rather than a new
 *       planning run, repeat every check and require exactly the same counts and piece NBT.</li>
 * </ul>
 *
 * <p>A failed check prints {@code WAYSTATION AUDIT FAILED} and halts with a non-zero exit code, so
 * the Gradle task fails instead of reporting a green build over a broken structure.
 */
public final class WaystationAudit {
    public static final String ENABLE_PROPERTY = "testcompanion.audit";
    public static final String PHASE_PROPERTY = "testcompanion.audit.phase";
    private static final String PROFILE = "adventureworldgen:default";
    private static final int NATIVE_SCAN_RADIUS = 48;
    private static final int NATIVE_CANDIDATES = 2;

    private WaystationAudit() {}

    public static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) return;
        String phase = System.getProperty(PHASE_PROPERTY, "cold");
        ServerLevel level = event.getServer().overworld();
        try {
            Map<String, String> measured = run(level, phase);
            System.out.println("WAYSTATION AUDIT PASSED phase=" + phase + " " + measured);
            event.getServer().saveEverything(true, true, true);
            event.getServer().halt(false);
        } catch (RuntimeException | Error failure) {
            System.out.println("WAYSTATION AUDIT FAILED phase=" + phase + ": " + failure);
            failure.printStackTrace(System.out);
            Runtime.getRuntime().halt(1);
        }
    }

    private static Map<String, String> run(ServerLevel level, String phase) {
        var plan = (GeneratedAdventurePlan) RuntimePlanRegistry.await(ResourceLocation.parse(PROFILE));
        AdventurePlanView.PlannedStructure planned = plan.structures().stream()
                .filter(structure -> structure.structureId().equals(TestCompanionAdapters.WAYSTATION_ID))
                .findFirst().orElseThrow(() -> new IllegalStateException("the plan contains no waystation"));
        Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .get(ResourceLocation.parse(planned.structureId().value()));
        require(structure != null, "the waystation structure is missing from the registry");

        Path record = level.getServer().getWorldPath(LevelResource.ROOT).resolve("waystation-audit.properties");
        Path ready = record.getParent().resolve("adventureworldgen").resolve("plans")
                .resolve("adventureworldgen_default").resolve("READY");
        // A READY restore leaves the progress hook at its initial CACHE stage; a fresh planning run
        // stops at SAVE. This is how each phase proves which path it took, and both phases compare
        // the published plan bytes below.
        var progress = PlanningProgress.current();
        PlanningStage stage = progress == null ? null : progress.stage();
        if ("ready".equals(phase)) {
            require(Files.exists(ready), "the ready phase started without a published plan");
            require(stage == PlanningStage.CACHE,
                    "the ready phase replanned instead of restoring the frozen plan (stage " + stage + ")");
        } else {
            require(stage == PlanningStage.SAVE,
                    "the cold phase did not plan a fresh plan (stage " + stage + ")");
        }

        require(Files.exists(ready), "the plan was not published before the audit ran");

        StructurePieceSerializationContext context =
                FrozenPieceRestore.context(level.registryAccess(), level.getStructureManager());
        List<StructurePiece> restored = FrozenPieceRestore.restore(planned, context);
        ChunkPos origin = new ChunkPos(Math.floorDiv(planned.originX(), 16), Math.floorDiv(planned.originZ(), 16));
        List<ChunkPos> chunks = intersectingChunks(planned);

        Map<String, String> counts = measure(level, structure, origin, chunks, planned, restored, context);
        Map<String, String> repeated = measure(level, structure, origin, chunks, planned, restored, context);
        require(counts.equals(repeated),
                "loading the same chunks twice changed the result: " + counts + " -> " + repeated);

        // The controlled id is suppressed wherever it appears, so generate real candidate chunks
        // rather than accepting "no candidate existed" as proof of suppression.
        List<ChunkPos> candidates = nativeCandidates(level, structure, origin);
        require(!candidates.isEmpty(),
                "found no native candidate chunk for the controlled waystation, so suppression is unproven");
        for (ChunkPos candidate : candidates) {
            StructureStart start = level.getChunk(candidate.x, candidate.z).getStartForStructure(structure);
            require(start == null || !start.isValid(),
                    "a native candidate in " + candidate + " was not suppressed");
        }
        counts.put("native_candidates", String.valueOf(candidates.size()));

        // The published payload is byte-stable for the same plan, so comparing it proves both phases
        // used the same frozen plan rather than two independently planned ones.
        counts.put("plan_sha256", sha256Of(ready.resolveSibling("plan.json.gz")));
        if ("cold".equals(phase)) {
            writeRecord(record, counts);
        } else {
            Map<String, String> cold = readRecord(record);
            require(cold.equals(counts),
                    "the ready phase disagrees with the cold phase: cold=" + cold + " ready=" + counts);
        }
        return counts;
    }

    /** Generates every chunk the planned structure touches and counts what actually landed. */
    private static Map<String, String> measure(ServerLevel level, Structure structure, ChunkPos origin,
                                               List<ChunkPos> chunks, AdventurePlanView.PlannedStructure planned,
                                               List<StructurePiece> restored, StructurePieceSerializationContext context) {
        Map<String, String> counts = new LinkedHashMap<>();
        MessageDigest digest = sha256();
        int markers = 0, containers = 0, starts = 0;
        for (ChunkPos pos : chunks) {
            ChunkAccess chunk = level.getChunk(pos.x, pos.z);
            StructureStart start = chunk.getStartForStructure(structure);
            if (pos.equals(origin)) {
                require(start != null && start.isValid(), "the origin chunk has no valid planned start");
                require(start.getPieces().size() == restored.size(), "the origin start carries "
                        + start.getPieces().size() + " pieces, the plan froze " + restored.size());
                for (int index = 0; index < restored.size(); index++) {
                    StructurePiece piece = start.getPieces().get(index);
                    var frozen = planned.pieces().get(index);
                    require(piece instanceof WaystationPiece,
                            "origin piece " + index + " came back as " + piece.getClass().getName());
                    require(sameBox(piece, frozen), "origin piece " + index + " does not occupy the frozen box");
                    // The world stores the start in the chunk; re-serializing it is the persistence check.
                    digest.update(piece.createTag(context).toString().getBytes(StandardCharsets.UTF_8));
                }
                starts++;
            } else {
                require(start == null || !start.isValid(),
                        "chunk " + pos + " carries its own start for the controlled waystation");
            }
            var references = chunk.getReferencesForStructure(structure);
            require(references.size() == 1 && references.contains(origin.toLong()),
                    "chunk " + pos + " does not reference exactly the origin start: " + references);
        }
        require(starts == 1, "expected exactly one planned start, found " + starts);

        for (StructurePiece piece : restored) {
            WaystationPiece waystation = (WaystationPiece) piece;
            for (BlockPos marker : waystation.markerWorldPositions()) {
                require(level.getBlockState(marker).is(Blocks.RED_CONCRETE),
                        "direction marker missing at " + marker + " (found " + level.getBlockState(marker) + ")");
                markers++;
            }
            for (WaystationPiece.BodySample sample : waystation.bodySamples()) {
                require(level.getBlockState(sample.pos()).equals(sample.state()),
                        "body block at " + sample.pos() + " is " + level.getBlockState(sample.pos())
                                + ", expected " + sample.state());
            }
            for (int which = 0; which < 2; which++) {
                BlockPos pos = waystation.containerWorldPos(which);
                require(level.getBlockEntity(pos) instanceof ChestBlockEntity,
                        "container missing at " + pos + " (found " + level.getBlockState(pos) + ")");
                ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(pos);
                require(waystation.lootTable().equals(chest.getLootTable()),
                        "container at " + pos + " lost its frozen loot table: " + chest.getLootTable());
                require(waystation.lootSeed() == chest.getLootTableSeed(),
                        "container at " + pos + " lost its frozen loot seed: " + chest.getLootTableSeed());
                containers++;
            }
        }
        counts.put("chunks", String.valueOf(chunks.size()));
        counts.put("pieces", String.valueOf(restored.size()));
        counts.put("markers", String.valueOf(markers));
        counts.put("containers", String.valueOf(containers));
        counts.put("piece_nbt_sha256", hex(digest.digest()));
        return counts;
    }

    private static boolean sameBox(StructurePiece piece, AdventurePlanView.PlannedPiece frozen) {
        var box = piece.getBoundingBox();
        return box.minX() == frozen.minX() && box.minY() == frozen.minY() && box.minZ() == frozen.minZ()
                && box.maxX() == frozen.maxX() && box.maxY() == frozen.maxY() && box.maxZ() == frozen.maxZ();
    }

    /** Chunk positions whose 16x16 area intersects any frozen piece of the planned structure. */
    private static List<ChunkPos> intersectingChunks(AdventurePlanView.PlannedStructure planned) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (var piece : planned.pieces())
            for (int x = Math.floorDiv(piece.minX(), 16); x <= Math.floorDiv(piece.maxX(), 16); x++)
                for (int z = Math.floorDiv(piece.minZ(), 16); z <= Math.floorDiv(piece.maxZ(), 16); z++) {
                    ChunkPos pos = new ChunkPos(x, z);
                    if (!chunks.contains(pos)) chunks.add(pos);
                }
        return chunks;
    }

    /** Real chunks where a native structure set would place the controlled structure. */
    private static List<ChunkPos> nativeCandidates(ServerLevel level, Structure structure, ChunkPos origin) {
        var state = level.getChunkSource().getGeneratorState();
        List<ChunkPos> candidates = new ArrayList<>();
        for (var holder : state.possibleStructureSets()) {
            if (holder.value().structures().stream().noneMatch(entry -> entry.structure().value() == structure)) continue;
            var placement = holder.value().placement();
            for (int dx = -NATIVE_SCAN_RADIUS; dx <= NATIVE_SCAN_RADIUS && candidates.size() < NATIVE_CANDIDATES; dx++)
                for (int dz = -NATIVE_SCAN_RADIUS; dz <= NATIVE_SCAN_RADIUS && candidates.size() < NATIVE_CANDIDATES; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (placement.isStructureChunk(state, origin.x + dx, origin.z + dz))
                        candidates.add(new ChunkPos(origin.x + dx, origin.z + dz));
                }
        }
        return candidates;
    }

    private static void writeRecord(Path record, Map<String, String> counts) {
        Properties properties = new Properties();
        counts.forEach(properties::setProperty);
        try (var output = Files.newOutputStream(record)) {
            properties.store(output, "waystation audit: cold phase counts");
        } catch (IOException failure) {
            throw new IllegalStateException("could not write the audit record " + record, failure);
        }
    }

    private static Map<String, String> readRecord(Path record) {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(record)) {
            properties.load(input);
        } catch (IOException failure) {
            throw new IllegalStateException("could not read the audit record " + record, failure);
        }
        Map<String, String> counts = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) counts.put(name, properties.getProperty(name));
        return counts;
    }

    private static String sha256Of(Path path) {
        try {
            return hex(sha256().digest(Files.readAllBytes(path)));
        } catch (IOException failure) {
            throw new IllegalStateException("could not read " + path, failure);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format("%02x", value));
        return text.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
