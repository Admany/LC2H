package org.admany.lc2h.dev.debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import mcjty.lostcities.api.LostChunkCharacteristics;
import mcjty.lostcities.api.MultiPos;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.versions.forge.ForgeVersion;
import net.minecraftforge.registries.ForgeRegistries;
import org.admany.lc2h.runtime.Lc2hRuntimeModes;
import org.admany.lc2h.worldgen.apply.ShadowBlockMutationApplier;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeEventHandler;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeQueue;
import org.admany.lc2h.worldgen.seams.SeamOwnershipJournal;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class WorldParityHarness {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson WRITE_GSON = new Gson();
    private static final String MATRIX_ID = WorldParityLegacyArtifacts.ACTIVE_MATRIX_ID;
    private static final int MAX_MISMATCH_SAMPLES = Math.max(32, Integer.getInteger("lc2h.worldparity.sampleLimit", 256));
    private static final int REPRESENTATIVE_SAMPLE_LIMIT = Math.max(20, Integer.getInteger("lc2h.worldparity.representativeSampleLimit", 20));
    private static final int CHUNK_REPRESENTATIVE_LIMIT = Math.max(REPRESENTATIVE_SAMPLE_LIMIT, Integer.getInteger("lc2h.worldparity.chunkRepresentativeLimit", 64));
    private static final int EXPORT_SETTLE_MAX_CYCLES = Math.max(1, Integer.getInteger("lc2h.worldparity.exportSettleCycles", 8));
    private static final int EXPORT_SETTLE_READY_PROMOTIONS = Math.max(32, Integer.getInteger("lc2h.worldparity.exportSettleReadyPromotions", 1024));
    private static final int EXPORT_SETTLE_READY_CHECKS = Math.max(128, Integer.getInteger("lc2h.worldparity.exportSettleReadyChecks", 8192));
    private static final int EXPORT_SETTLE_REPLAYS = Math.max(2, Integer.getInteger("lc2h.worldparity.exportSettleReplays", 32));
    private static final int EXPORT_SETTLE_REPLAY_BLOCKS = Math.max(1024, Integer.getInteger("lc2h.worldparity.exportSettleReplayBlocks", 24576));
    private static final int EXPORT_SETTLE_SHADOW_DRAINS = Math.max(1, Integer.getInteger("lc2h.worldparity.exportSettleShadowDrains", 6));
    private static final Set<String> TREE_MARKERS = Set.of("log", "leaves", "leaf", "sapling", "mushroom_stem", "root", "roots", "branch");
    private static final Set<String> VINE_MARKERS = Set.of("vine", "cave_vines", "weeping_vines", "twisting_vines");
    private static final Set<String> GRAVITY_MARKERS = Set.of("sand", "gravel", "concrete_powder", "anvil", "scaffolding");

    private WorldParityHarness() {
    }

    public static List<ChunkCoord> buildMatrix(ChunkCoord centerChunk, int radius, int samples) {
        LinkedHashSet<ChunkCoord> coords = new LinkedHashSet<>();
        addCenterGrid(coords, centerChunk, radius);
        addRelativeTargets(coords, centerChunk);
        ArrayList<ChunkCoord> ordered = new ArrayList<>(coords);
        if (samples > 0 && ordered.size() > samples) {
            return ordered.subList(0, samples);
        }
        return ordered;
    }

    private static void addCenterGrid(LinkedHashSet<ChunkCoord> coords, ChunkCoord centerChunk, int radius) {
        coords.add(centerChunk);
        for (int ring = 1; ring <= radius; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    coords.add(new ChunkCoord(centerChunk.dimension(), centerChunk.chunkX() + dx, centerChunk.chunkZ() + dz));
                }
            }
        }
    }

    private static void addRelativeTargets(LinkedHashSet<ChunkCoord> coords, ChunkCoord centerChunk) {
        int[][] offsets = {
            {-2, -2}, {-2, -1}, {-2, 0}, {-2, 1}, {-2, 2},
            {-1, -2}, {-1, 2},
            {0, -2}, {0, 2},
            {1, -2}, {1, 2},
            {2, -2}, {2, -1}, {2, 0}, {2, 1}, {2, 2},
            {-4, 0}, {4, 0}, {0, -4}, {0, 4},
            {-6, -6}, {-6, 6}, {6, -6}, {6, 6},
            {-8, 0}, {8, 0}, {0, -8}, {0, 8},
            {-15, -15}, {15, 15}, {-15, 15}, {15, -15}
        };
        for (int[] offset : offsets) {
            coords.add(new ChunkCoord(
                centerChunk.dimension(),
                centerChunk.chunkX() + offset[0],
                centerChunk.chunkZ() + offset[1]
            ));
        }
    }

    public static ExportSummary exportWorld(MinecraftServer server,
                                            ServerLevel level,
                                            IDimensionInfo provider,
                                            String role,
                                            Collection<ChunkCoord> coords,
                                            Path outputFile) throws IOException {
        ExportCapture capture = beginExport(server, level, provider, role, coords);
        while (capture.hasRemaining()) {
            capture.captureNext();
        }
        return finishExport(capture, outputFile);
    }

    public static ExportCapture beginExport(MinecraftServer server,
                                            ServerLevel level,
                                            IDimensionInfo provider,
                                            String role,
                                            Collection<ChunkCoord> coords) {
        return beginExport(server, level, provider, role, coords, null);
    }

    public static ExportCapture beginExport(MinecraftServer server,
                                            ServerLevel level,
                                            IDimensionInfo provider,
                                            String role,
                                            Collection<ChunkCoord> coords,
                                            ExportMetadata metadata) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(coords, "coords");
        ExportDocument document = new ExportDocument();
        document.matrixId = MATRIX_ID;
        document.exportedAt = Instant.now().toString();
        document.role = role;
        document.dimension = level.dimension().location().toString();
        document.seed = level.getSeed();
        document.profile = safeProfile(provider);
        document.outsideProfile = safeOutsideProfile(provider);
        document.worldStyle = safeWorldStyle(provider);
        document.laneId = metadata == null ? System.getProperty("lc2h.worldparity.laneId", role) : metadata.laneId();
        document.generatedCenterChunkX = metadata == null ? Integer.MIN_VALUE : metadata.generatedCenterChunkX();
        document.generatedCenterChunkZ = metadata == null ? Integer.MIN_VALUE : metadata.generatedCenterChunkZ();
        document.generatedMultiChunkX = metadata == null ? Integer.MIN_VALUE : metadata.generatedMultiChunkX();
        document.generatedMultiChunkZ = metadata == null ? Integer.MIN_VALUE : metadata.generatedMultiChunkZ();
        document.exportWindow = exportWindow(coords);
        document.environment = fingerprint(server, level, provider, role);
        document.chunks = new ArrayList<>(coords.size());
        return new ExportCapture(level, provider, document, new ArrayList<>(coords));
    }

    public static ExportSummary finishExport(ExportCapture capture, Path outputFile) throws IOException {
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(outputFile, "outputFile");
        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (var writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            WRITE_GSON.toJson(capture.document(), writer);
        }
        return new ExportSummary(
            capture.document().role,
            outputFile.toAbsolutePath().toString(),
            capture.document().chunks.size(),
            capture.document().environment.fingerprintHash
        );
    }

    public static ComparisonSummary compareExports(Path baselinePath, Path testPath, Path outputFile) throws IOException {
        ExportDocument baseline;
        ExportDocument test;
        try (Reader reader = Files.newBufferedReader(baselinePath, StandardCharsets.UTF_8)) {
            baseline = GSON.fromJson(reader, ExportDocument.class);
        }
        try (Reader reader = Files.newBufferedReader(testPath, StandardCharsets.UTF_8)) {
            test = GSON.fromJson(reader, ExportDocument.class);
        }

        ComparisonDocument out = new ComparisonDocument();
        out.generatedAt = Instant.now().toString();
        out.matrixId = MATRIX_ID;
        out.baselinePath = baselinePath.toAbsolutePath().toString();
        out.testPath = testPath.toAbsolutePath().toString();
        out.baselineFingerprint = baseline.environment;
        out.testFingerprint = test.environment;
        out.baselineMatrixId = baseline.matrixId;
        out.testMatrixId = test.matrixId;
        out.baselineLaneId = baseline.laneId;
        out.testLaneId = test.laneId;
        out.baselineExportWindow = baseline.exportWindow;
        out.testExportWindow = test.exportWindow;
        out.legacyRetiredMatrix = WorldParityLegacyArtifacts.RETIRED_MATRIX_ID;
        out.environmentMatch = environmentMatches(baseline.environment, test.environment);
        out.environmentDifferences = environmentDifferences(baseline.environment, test.environment);
        out.matrixMatch = Objects.equals(baseline.matrixId, test.matrixId) && WorldParityLegacyArtifacts.isActiveMatrix(baseline.matrixId);
        out.windowMatch = Objects.equals(baseline.exportWindow, test.exportWindow);

        Map<String, ChunkSnapshot> baselineChunks = index(baseline.chunks);
        Map<String, ChunkSnapshot> testChunks = index(test.chunks);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.addAll(baselineChunks.keySet());
        keys.addAll(testChunks.keySet());

        out.totalChunksCompared = keys.size();
        out.totalBlocksCompared = 0L;
        out.missingChunks = 0;
        out.extraChunks = 0;
        out.chunkStatusMismatches = 0;
        out.probableDrift = 0;
        out.borderSeams = 0;
        out.multichunkOriginMismatches = 0;
        out.blockMismatches = 0L;
        out.blockStateOnlyMismatches = 0L;
        out.blockEntityMismatches = 0;
        out.heightmapDifferences = 0;
        out.treeMismatches = 0L;
        out.vineMismatches = 0L;
        out.gravityMismatches = 0L;
        out.fluidMismatches = 0L;
        out.unknownMismatches = 0L;
        out.acceptedMaterialVariance = 0L;
        out.hardMismatchCount = 0L;
        out.unclassifiedMismatchCount = 0L;
        out.unsupportedFields = List.of("scheduled_block_ticks", "scheduled_fluid_ticks", "carving_masks", "post_processing_marks");
        out.mismatchBuckets = new LinkedHashMap<>();
        out.acceptedVarianceBuckets = new LinkedHashMap<>();
        out.hardMismatchBuckets = new LinkedHashMap<>();
        out.hardStatePairBuckets = new LinkedHashMap<>();
        out.unclassifiedMismatchBuckets = new LinkedHashMap<>();
        ArrayList<WorldParityMismatchAnalyzer.MismatchSample> mismatchCandidates = new ArrayList<>();
        out.mismatchSamples = new ArrayList<>();
        out.chunkDetails = new ArrayList<>();
        LinkedHashSet<String> seamKeys = new LinkedHashSet<>();

        for (String key : keys) {
            ChunkSnapshot a = baselineChunks.get(key);
            ChunkSnapshot b = testChunks.get(key);
            ChunkComparison detail = new ChunkComparison();
            detail.chunkKey = key;
            detail.baselinePresent = a != null && a.present;
            detail.testPresent = b != null && b.present;
            if (!detail.baselinePresent && !detail.testPresent) {
                continue;
            }
            if (a == null || !a.present) {
                out.extraChunks += (b != null && b.present) ? 1 : 0;
                detail.category = "extra_chunk";
                out.chunkDetails.add(detail);
                continue;
            }
            if (b == null || !b.present) {
                out.missingChunks++;
                detail.category = "missing_chunk";
                out.chunkDetails.add(detail);
                continue;
            }

            out.totalBlocksCompared += Math.min(a.totalBlocks, b.totalBlocks);
            if (!Objects.equals(a.status, b.status)) {
                out.chunkStatusMismatches++;
                detail.statusMismatch = a.status + " vs " + b.status;
            }

            if (!Objects.equals(a.structureAnchorHash, b.structureAnchorHash) || !Objects.equals(a.multichunkHash, b.multichunkHash)) {
                out.multichunkOriginMismatches++;
                detail.multichunkMismatch = true;
            }

            if (!Objects.equals(a.heightmapHash, b.heightmapHash)) {
                out.heightmapDifferences++;
                detail.heightmapMismatch = true;
            }

            if (!Objects.equals(a.stateHash, b.stateHash)) {
                ChunkSnapshot driftMatch = findNeighborHashMatch(baselineChunks, b);
                if (driftMatch != null) {
                    out.probableDrift++;
                    detail.probableDrift = driftMatch.chunkX + "," + driftMatch.chunkZ;
                }
                BlockDiffResult diff = compareBlocks(baseline.seed, baseline.profile, baseline.dimension, a, b);
                out.blockMismatches += diff.blockMismatches;
                out.blockStateOnlyMismatches += diff.stateOnlyMismatches;
                out.treeMismatches += diff.treeMismatches;
                out.vineMismatches += diff.vineMismatches;
                out.gravityMismatches += diff.gravityMismatches;
                out.fluidMismatches += diff.fluidMismatches;
                out.unknownMismatches += diff.unknownMismatches;
                out.acceptedMaterialVariance += diff.acceptedMaterialVariance;
                out.hardMismatchCount += diff.hardMismatchCount;
                out.unclassifiedMismatchCount += diff.unclassifiedMismatchCount;
                seamKeys.addAll(diff.seamKeys);
                mergeBuckets(out.mismatchBuckets, diff.bucketCounts);
                mergeBuckets(out.acceptedVarianceBuckets, diff.acceptedVarianceBuckets);
                mergeBuckets(out.hardMismatchBuckets, diff.hardMismatchBuckets);
                mergeBuckets(out.hardStatePairBuckets, diff.hardStatePairBuckets);
                mergeBuckets(out.unclassifiedMismatchBuckets, diff.unclassifiedMismatchBuckets);
                mismatchCandidates.addAll(diff.samples);
                detail.blockMismatchCount = diff.blockMismatches;
                detail.stateOnlyMismatchCount = diff.stateOnlyMismatches;
            }

            BlockEntityDiffResult blockEntityDiff = compareBlockEntities(baseline.seed, baseline.profile, baseline.dimension, a, b);
            if (blockEntityDiff.mismatches > 0) {
                out.blockEntityMismatches += blockEntityDiff.mismatches;
                out.hardMismatchCount += blockEntityDiff.mismatches;
                mergeBuckets(out.mismatchBuckets, blockEntityDiff.bucketCounts);
                mismatchCandidates.addAll(blockEntityDiff.samples);
                detail.blockEntityMismatchCount = blockEntityDiff.mismatches;
            }

            if (detail.hasAnyMismatch()) {
                detail.category = detail.category == null ? "mismatch" : detail.category;
                out.chunkDetails.add(detail);
            }
        }

        out.borderSeams = seamKeys.size();
        out.mismatchSamples = selectLaneRepresentativeSamples(mismatchCandidates);
        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (var writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            WRITE_GSON.toJson(out, writer);
        }
        if (!out.matrixMatch) {
            throw new IllegalStateException("World parity compare refused: invalid matrix baseline=" + baseline.matrixId + " test=" + test.matrixId);
        }
        if (!out.environmentMatch) {
            throw new IllegalStateException("World parity compare refused: environment mismatch " + out.environmentDifferences);
        }
        if (!out.windowMatch) {
            throw new IllegalStateException("World parity compare refused: chunk window mismatch baseline=" + baseline.exportWindow + " test=" + test.exportWindow);
        }
        return new ComparisonSummary(
            out.environmentMatch,
            out.matrixMatch,
            out.windowMatch,
            out.totalChunksCompared,
            out.missingChunks,
            out.extraChunks,
            out.chunkStatusMismatches,
            out.probableDrift,
            out.borderSeams,
            out.multichunkOriginMismatches,
            out.totalBlocksCompared,
            out.blockMismatches,
            out.blockStateOnlyMismatches,
            out.blockEntityMismatches,
            out.heightmapDifferences,
            out.treeMismatches,
            out.vineMismatches,
            out.gravityMismatches,
            out.unknownMismatches,
            out.fluidMismatches,
            out.acceptedMaterialVariance,
            out.hardMismatchCount,
            out.unclassifiedMismatchCount,
            outputFile.toAbsolutePath().toString(),
            out.environmentDifferences
        );
    }

    private static EnvironmentFingerprint fingerprint(MinecraftServer server, ServerLevel level, IDimensionInfo provider, String role) {
        EnvironmentFingerprint fp = new EnvironmentFingerprint();
        fp.role = role;
        fp.runtimeMode = Lc2hRuntimeModes.modeSummary();
        fp.baselineMode = Lc2hRuntimeModes.baselineMode();
        fp.minecraftVersion = SharedConstants.getCurrentVersion().getName();
        fp.forgeVersion = ForgeVersion.getVersion();
        fp.mappings = System.getProperty("lc2h.runtimeMappings", "unknown");
        fp.dimension = level.dimension().location().toString();
        fp.seed = level.getSeed();
        fp.profile = safeProfile(provider);
        fp.outsideProfile = safeOutsideProfile(provider);
        fp.worldStyle = safeWorldStyle(provider);
        fp.lostCitiesVersion = modVersion("lostcities");
        fp.lc2hVersion = modVersion("lc2h");
        fp.lc2hEnabled = !Lc2hRuntimeModes.baselineMode();
        fp.runDir = FMLPaths.GAMEDIR.get().toAbsolutePath().toString();
        fp.selectedPacks = server.getPackRepository().getSelectedIds().stream().sorted().toList();
        fp.mods = ModList.get().getMods().stream()
            .map(mod -> mod.getModId() + ":" + mod.getVersion())
            .sorted()
            .toList();
        fp.gameRules = snapshotGameRules(level);
        fp.configHashes = snapshotConfigHashes(server);
        if (server.getWorldData() instanceof PrimaryLevelData primaryLevelData) {
            fp.worldGenSettingsHash = stableHash(worldGenSettingsFingerprint(primaryLevelData));
        } else {
            fp.worldGenSettingsHash = stableHash(safeString(server.getWorldData()));
        }
        fp.fingerprintHash = stableHash(
            fp.minecraftVersion,
            fp.forgeVersion,
            fp.mappings,
            fp.dimension,
            String.valueOf(fp.seed),
            fp.profile,
            fp.outsideProfile,
            fp.worldStyle,
            fp.lostCitiesVersion,
            fp.lc2hVersion,
            String.valueOf(fp.lc2hEnabled),
            String.valueOf(fp.baselineMode),
            String.join("|", fp.selectedPacks),
            String.join("|", fp.mods),
            fp.gameRules.toString(),
            fp.configHashes.toString(),
            fp.worldGenSettingsHash
        );
        return fp;
    }

    private static Map<String, String> snapshotGameRules(ServerLevel level) {
        LinkedHashMap<String, String> rules = new LinkedHashMap<>();
        GameRules gameRules = level.getGameRules();
        gameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                rules.put(key.getId(), gameRules.getRule(key).toString());
            }
        });
        return rules;
    }

    private static Map<String, String> snapshotConfigHashes(MinecraftServer server) {
        LinkedHashMap<String, String> hashes = new LinkedHashMap<>();
        List<String> candidates = List.of(
            "serverconfig/lostcities-server.toml",
            "serverconfig/forge-server.toml",
            "config/lc2h-common.toml",
            "config/lc2h-client.toml"
        );
        for (String candidate : candidates) {
            Path path = server.getFile(candidate).toPath();
            if (Files.exists(path)) {
                try {
                    hashes.put(candidate, stableHash(Files.readString(path, StandardCharsets.UTF_8)));
                } catch (IOException e) {
                    hashes.put(candidate, "read-error:" + e.getClass().getSimpleName());
                }
            }
        }
        return hashes;
    }

    private static ExportWindow exportWindow(Collection<ChunkCoord> coords) {
        if (coords == null || coords.isEmpty()) {
            return new ExportWindow(0, 0, 0, 0, 0);
        }
        int minChunkX = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;
        int count = 0;
        for (ChunkCoord coord : coords) {
            if (coord == null) {
                continue;
            }
            minChunkX = Math.min(minChunkX, coord.chunkX());
            maxChunkX = Math.max(maxChunkX, coord.chunkX());
            minChunkZ = Math.min(minChunkZ, coord.chunkZ());
            maxChunkZ = Math.max(maxChunkZ, coord.chunkZ());
            count++;
        }
        if (count == 0) {
            return new ExportWindow(0, 0, 0, 0, 0);
        }
        return new ExportWindow(minChunkX, maxChunkX, minChunkZ, maxChunkZ, count);
    }

    private static String modVersion(String modId) {
        try {
            return ModList.get().getModContainerById(modId)
                .map(container -> String.valueOf(container.getModInfo().getVersion()))
                .orElse("missing");
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static ChunkSnapshot captureChunk(ServerLevel level, IDimensionInfo provider, ChunkCoord coord) {
        ChunkSnapshot snapshot = new ChunkSnapshot();
        snapshot.chunkX = coord.chunkX();
        snapshot.chunkZ = coord.chunkZ();
        snapshot.dimension = coord.dimension() == null ? "unknown" : coord.dimension().location().toString();
        snapshot.minBuildY = level.getMinBuildHeight();
        snapshot.maxBuildY = level.getMaxBuildHeight() - 1;
        snapshot.totalBlocks = (long) (snapshot.maxBuildY - snapshot.minBuildY + 1) * 16L * 16L;
        settleChunkForParityCapture(level, coord);
        LevelChunk chunk = level.getChunkSource().getChunkNow(coord.chunkX(), coord.chunkZ());
        snapshot.present = chunk != null;
        if (chunk == null) {
            snapshot.status = "missing";
            return snapshot;
        }
        warmChunkSnapshotInputs(level, provider, coord, chunk);
        settleChunkForParityCapture(level, coord);
        chunk = level.getChunkSource().getChunkNow(coord.chunkX(), coord.chunkZ());
        snapshot.present = chunk != null;
        if (chunk == null) {
            snapshot.status = "missing_after_settle";
            return snapshot;
        }
        snapshot.status = String.valueOf(chunk.getStatus());
        snapshot.heightmaps = captureHeightmaps(level, coord);
        snapshot.heightmapHash = stableHash(stableJson(snapshot.heightmaps));
        snapshot.structures = captureStructures(level, chunk);
        snapshot.structureAnchorHash = stableHash(stableJson(snapshot.structures));
        snapshot.characteristics = captureCharacteristics(provider, coord);
        snapshot.multichunkHash = stableHash(stableJson(snapshot.characteristics));
        snapshot.blocks = captureSparseBlocks(chunk, snapshot.minBuildY, snapshot.maxBuildY);
        snapshot.stateHash = stableHash(stableJson(snapshot.blocks));
        snapshot.blockEntities = captureBlockEntities(chunk);
        snapshot.blockEntityHash = stableHash(stableJson(snapshot.blockEntities));
        return snapshot;
    }

    private static void warmChunkSnapshotInputs(ServerLevel level, IDimensionInfo provider, ChunkCoord coord, LevelChunk chunk) {
        if (level == null || provider == null || coord == null || chunk == null) {
            return;
        }
        captureHeightmaps(level, coord);
        captureStructures(level, chunk);
        captureCharacteristics(provider, coord);
    }

    private static void settleChunkForParityCapture(ServerLevel level, ChunkCoord coord) {
        if (level == null || coord == null) {
            return;
        }
        Set<Long> interestChunks = exportInterestChunks(coord.chunkX(), coord.chunkZ());
        for (int cycle = 0; cycle < EXPORT_SETTLE_MAX_CYCLES; cycle++) {
            SeamOwnershipJournal.flushLoadedChunk(level, coord.chunkX(), coord.chunkZ());
            DeferredTreeQueue.promoteReadyLoaded(
                level,
                EXPORT_SETTLE_READY_PROMOTIONS,
                interestChunks,
                EXPORT_SETTLE_READY_CHECKS
            );
            DeferredTreeEventHandler.forceReplayReadyForDebug(
                level.getServer(),
                EXPORT_SETTLE_REPLAYS,
                EXPORT_SETTLE_REPLAY_BLOCKS,
                interestChunks
            );
            ShadowBlockMutationApplier.forceDrainForDebug(EXPORT_SETTLE_SHADOW_DRAINS);
            if (!hasExportResidue(level, interestChunks)) {
                return;
            }
        }
    }

    private static boolean hasExportResidue(ServerLevel level, Set<Long> interestChunks) {
        return ShadowBlockMutationApplier.hasPendingWork(level, interestChunks)
            || ShadowBlockMutationApplier.getPendingTransactionCount(level, interestChunks) > 0
            || ShadowBlockMutationApplier.getHeldTicketCount(level, interestChunks) > 0L
            || DeferredTreeQueue.pendingCount(level, interestChunks) > 0
            || DeferredTreeQueue.readyCount(level, interestChunks) > 0;
    }

    private static Set<Long> exportInterestChunks(int chunkX, int chunkZ) {
        LinkedHashSet<Long> interest = new LinkedHashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                interest.add(new ChunkPos(chunkX + dx, chunkZ + dz).toLong());
            }
        }
        return interest;
    }

    private static Map<String, int[]> captureHeightmaps(ServerLevel level, ChunkCoord coord) {
        LinkedHashMap<String, int[]> maps = new LinkedHashMap<>();
        for (Heightmap.Types type : List.of(
            Heightmap.Types.WORLD_SURFACE,
            Heightmap.Types.OCEAN_FLOOR,
            Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES)) {
            int[] values = new int[256];
            int idx = 0;
            int baseX = coord.chunkX() << 4;
            int baseZ = coord.chunkZ() << 4;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    values[idx++] = level.getHeight(type, baseX + x, baseZ + z);
                }
            }
            maps.put(type.getSerializationKey(), values);
        }
        return maps;
    }

    private static List<StructureInfo> captureStructures(ServerLevel level, LevelChunk chunk) {
        ArrayList<StructureInfo> out = new ArrayList<>();
        Map<Structure, StructureStart> starts = chunk.getAllStarts();
        if (starts == null || starts.isEmpty()) {
            return out;
        }
        for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
            StructureStart start = entry.getValue();
            if (start == null || !start.isValid()) {
                continue;
            }
            StructureInfo info = new StructureInfo();
            ResourceLocation key = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).getKey(entry.getKey());
            info.id = key == null ? String.valueOf(entry.getKey()) : key.toString();
            info.minX = start.getBoundingBox().minX();
            info.minY = start.getBoundingBox().minY();
            info.minZ = start.getBoundingBox().minZ();
            info.maxX = start.getBoundingBox().maxX();
            info.maxY = start.getBoundingBox().maxY();
            info.maxZ = start.getBoundingBox().maxZ();
            out.add(info);
        }
        out.sort(Comparator.comparing(a -> a.id));
        return out;
    }

    private static ChunkCharacteristics captureCharacteristics(IDimensionInfo provider, ChunkCoord coord) {
        ChunkCharacteristics out = new ChunkCharacteristics();
        try {
            LostChunkCharacteristics info = BuildingInfo.getChunkCharacteristics(coord, provider);
            if (info != null) {
                out.isCity = info.isCity;
                out.cityLevel = info.cityLevel;
                out.buildingType = safeBuildingName(info.buildingType);
                out.canHaveBuilding = info.couldHaveBuilding;
                MultiPos multiPos = info.multiPos;
                if (multiPos != null) {
                    out.multiX = multiPos.x();
                    out.multiZ = multiPos.z();
                    out.multiW = multiPos.w();
                    out.multiH = multiPos.h();
                    out.multiTopLeft = multiPos.isTopLeft();
                }
            }
        } catch (Throwable t) {
            out.error = t.getClass().getSimpleName();
        }
        return out;
    }

    private static String safeBuildingName(Object buildingType) {
        if (buildingType == null) {
            return "<none>";
        }
        try {
            Method method = buildingType.getClass().getMethod("getName");
            Object value = method.invoke(buildingType);
            if (value != null) {
                return String.valueOf(value);
            }
        } catch (Throwable ignored) {
        }
        return safeString(buildingType);
    }

    private static List<BlockCell> captureSparseBlocks(LevelChunk chunk, int minY, int maxY) {
        ArrayList<BlockCell> cells = new ArrayList<>();
        ChunkPos pos = chunk.getPos();
        int baseX = pos.x << 4;
        int baseZ = pos.z << 4;
        for (int y = minY; y <= maxY; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockPos worldPos = new BlockPos(baseX + x, y, baseZ + z);
                    BlockState state = chunk.getBlockState(worldPos);
                    FluidState fluid = state.getFluidState();
                    BlockEntity blockEntity = chunk.getBlockEntity(worldPos);
                    if (state.isAir() && fluid.isEmpty() && blockEntity == null) {
                        continue;
                    }
                    BlockCell cell = new BlockCell();
                    cell.x = x;
                    cell.y = y;
                    cell.z = z;
                    cell.state = stableStateString(state);
                    cell.fluid = stableFluidString(fluid);
                    cells.add(cell);
                }
            }
        }
        return cells;
    }

    private static List<BlockEntityCell> captureBlockEntities(LevelChunk chunk) {
        ArrayList<BlockEntityCell> cells = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockEntity be = entry.getValue();
            if (be == null) {
                continue;
            }
            CompoundTag tag = new CompoundTag();
            try {
                tag = be.saveWithoutMetadata();
            } catch (Throwable ignored) {
            }
            tag.remove("x");
            tag.remove("y");
            tag.remove("z");
            BlockEntityCell cell = new BlockEntityCell();
            cell.x = entry.getKey().getX() & 15;
            cell.y = entry.getKey().getY();
            cell.z = entry.getKey().getZ() & 15;
            cell.type = String.valueOf(ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType()));
            cell.nbt = tag.toString();
            cells.add(cell);
        }
        cells.sort(Comparator.comparingInt((BlockEntityCell c) -> c.y).thenComparingInt(c -> c.z).thenComparingInt(c -> c.x));
        return cells;
    }

    private static Map<String, ChunkSnapshot> index(List<ChunkSnapshot> chunks) {
        LinkedHashMap<String, ChunkSnapshot> map = new LinkedHashMap<>();
        if (chunks == null) {
            return map;
        }
        for (ChunkSnapshot chunk : chunks) {
            map.put(chunk.chunkX + "," + chunk.chunkZ, chunk);
        }
        return map;
    }

    private static boolean environmentMatches(EnvironmentFingerprint baseline, EnvironmentFingerprint test) {
        return environmentDifferences(baseline, test).isEmpty();
    }

    private static List<String> environmentDifferences(EnvironmentFingerprint baseline, EnvironmentFingerprint test) {
        ArrayList<String> diffs = new ArrayList<>();
        if (!Objects.equals(baseline.minecraftVersion, test.minecraftVersion)) diffs.add("minecraftVersion");
        if (!Objects.equals(baseline.forgeVersion, test.forgeVersion)) diffs.add("forgeVersion");
        if (!Objects.equals(baseline.mappings, test.mappings)) diffs.add("mappings");
        if (!Objects.equals(baseline.dimension, test.dimension)) diffs.add("dimension");
        if (baseline.seed != test.seed) diffs.add("seed");
        if (!Objects.equals(baseline.profile, test.profile)) diffs.add("profile");
        if (!Objects.equals(baseline.outsideProfile, test.outsideProfile)) diffs.add("outsideProfile");
        if (!Objects.equals(baseline.worldStyle, test.worldStyle)) diffs.add("worldStyle");
        if (!Objects.equals(baseline.lostCitiesVersion, test.lostCitiesVersion)) diffs.add("lostCitiesVersion");
        if (!Objects.equals(baseline.lc2hVersion, test.lc2hVersion)) diffs.add("lc2hVersion");
        if (!Objects.equals(baseline.selectedPacks, test.selectedPacks)) diffs.add("selectedPacks");
        if (!Objects.equals(baseline.mods, test.mods)) diffs.add("mods");
        if (!Objects.equals(baseline.gameRules, test.gameRules)) diffs.add("gameRules");
        if (!Objects.equals(baseline.configHashes, test.configHashes)) diffs.add("configHashes");
        if (!Objects.equals(baseline.worldGenSettingsHash, test.worldGenSettingsHash)) diffs.add("worldGenSettingsHash");
        return diffs;
    }

    private static ChunkSnapshot findNeighborHashMatch(Map<String, ChunkSnapshot> baselineChunks, ChunkSnapshot test) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                ChunkSnapshot candidate = baselineChunks.get((test.chunkX + dx) + "," + (test.chunkZ + dz));
                if (candidate != null && Objects.equals(candidate.stateHash, test.stateHash)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static BlockDiffResult compareBlocks(long seed, String profile, String dimension, ChunkSnapshot baseline, ChunkSnapshot test) {
        Map<String, BlockCell> a = toBlockMap(baseline.blocks);
        Map<String, BlockCell> b = toBlockMap(test.blocks);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.addAll(a.keySet());
        keys.addAll(b.keySet());
        long mismatches = 0L;
        long stateOnly = 0L;
        long tree = 0L;
        long vines = 0L;
        long gravity = 0L;
        long fluids = 0L;
        long unknown = 0L;
        long acceptedMaterialVariance = 0L;
        long hardMismatchCount = 0L;
        long unclassifiedMismatchCount = 0L;
        LinkedHashSet<String> seamKeys = new LinkedHashSet<>();
        LinkedHashMap<String, Long> bucketCounts = new LinkedHashMap<>();
        LinkedHashMap<String, Long> acceptedVarianceBuckets = new LinkedHashMap<>();
        LinkedHashMap<String, Long> hardMismatchBuckets = new LinkedHashMap<>();
        LinkedHashMap<String, Long> hardStatePairBuckets = new LinkedHashMap<>();
        LinkedHashMap<String, Long> unclassifiedMismatchBuckets = new LinkedHashMap<>();
        ArrayList<WorldParityMismatchAnalyzer.MismatchSample> rawSamples = new ArrayList<>();
        Map<String, BlockEntityCell> baselineBlockEntities = toBlockEntityMap(baseline.blockEntities);
        Map<String, BlockEntityCell> testBlockEntities = toBlockEntityMap(test.blockEntities);
        for (String key : keys) {
            BlockCell ca = a.get(key);
            BlockCell cb = b.get(key);
            String stateA = ca == null ? "<air>" : ca.state;
            String stateB = cb == null ? "<air>" : cb.state;
            String fluidA = ca == null ? "<none>" : ca.fluid;
            String fluidB = cb == null ? "<none>" : cb.fluid;
            if (Objects.equals(stateA, stateB) && Objects.equals(fluidA, fluidB)) {
                continue;
            }
            mismatches++;
            if (ca != null && cb != null) {
                stateOnly++;
            }
            if (matchesAny(stateA, stateB, TREE_MARKERS)) tree++;
            if (matchesAny(stateA, stateB, VINE_MARKERS)) vines++;
            if (matchesAny(stateA, stateB, GRAVITY_MARKERS)) gravity++;
            if (!Objects.equals(fluidA, fluidB) || stateA.contains("waterlogged") || stateB.contains("waterlogged")) fluids++;
            WorldParityMismatchAnalyzer.MismatchSample sample = WorldParityMismatchAnalyzer.analyze(
                seed, profile, dimension, baseline.chunkX, baseline.chunkZ, ca, cb, a, b,
                baselineBlockEntities.get(key), testBlockEntities.get(key));
            bucketCounts.merge(sample.bucket(), 1L, Long::sum);
            WorldParityMismatchAnalyzer.MismatchClassification classification = WorldParityMismatchAnalyzer.classifyDisposition(sample);
            switch (classification.disposition()) {
                case ACCEPTED_MATERIAL_VARIANCE -> {
                    acceptedMaterialVariance++;
                    acceptedVarianceBuckets.merge(classification.reason(), 1L, Long::sum);
                }
                case HARD_FAILURE -> {
                    hardMismatchCount++;
                    hardMismatchBuckets.merge(classification.reason(), 1L, Long::sum);
                    hardStatePairBuckets.merge(statePairKey(stateA, stateB), 1L, Long::sum);
                }
                case UNCLASSIFIED -> {
                    unclassifiedMismatchCount++;
                    unclassifiedMismatchBuckets.merge(classification.reason(), 1L, Long::sum);
                }
            }
            if ("unknown".equals(sample.bucket())) {
                unknown++;
            }
            rawSamples.add(sample);
            int x = ca != null ? ca.x : cb.x;
            int z = ca != null ? ca.z : cb.z;
            if (x == 0) seamKeys.add(baseline.chunkX + "," + baseline.chunkZ + ":W");
            if (x == 15) seamKeys.add(baseline.chunkX + "," + baseline.chunkZ + ":E");
            if (z == 0) seamKeys.add(baseline.chunkX + "," + baseline.chunkZ + ":N");
            if (z == 15) seamKeys.add(baseline.chunkX + "," + baseline.chunkZ + ":S");
        }
        return new BlockDiffResult(
            mismatches,
            stateOnly,
            tree,
            vines,
            gravity,
            unknown,
            fluids,
            acceptedMaterialVariance,
            hardMismatchCount,
            unclassifiedMismatchCount,
            seamKeys,
            bucketCounts,
            acceptedVarianceBuckets,
            hardMismatchBuckets,
            hardStatePairBuckets,
            unclassifiedMismatchBuckets,
            selectChunkRepresentativeSamples(rawSamples)
        );
    }

    private static String statePairKey(String baselineState, String testState) {
        return stateBlockId(baselineState) + " -> " + stateBlockId(testState);
    }

    private static String stateBlockId(String state) {
        if (state == null || state.isBlank() || "<air>".equals(state)) {
            return "<air>";
        }
        int properties = state.indexOf('[');
        return properties < 0 ? state : state.substring(0, properties);
    }

    private static BlockEntityDiffResult compareBlockEntities(long seed, String profile, String dimension, ChunkSnapshot baseline, ChunkSnapshot test) {
        Map<String, BlockEntityCell> a = toBlockEntityMap(baseline.blockEntities);
        Map<String, BlockEntityCell> b = toBlockEntityMap(test.blockEntities);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.addAll(a.keySet());
        keys.addAll(b.keySet());
        int mismatches = 0;
        LinkedHashMap<String, Long> bucketCounts = new LinkedHashMap<>();
        ArrayList<WorldParityMismatchAnalyzer.MismatchSample> samples = new ArrayList<>();
        for (String key : keys) {
            BlockEntityCell ca = a.get(key);
            BlockEntityCell cb = b.get(key);
            if (ca == null || cb == null) {
                mismatches++;
            } else if (!Objects.equals(ca.type, cb.type) || !Objects.equals(ca.nbt, cb.nbt)) {
                mismatches++;
            }
            if ((ca == null || cb == null || !Objects.equals(ca.type, cb.type) || !Objects.equals(ca.nbt, cb.nbt))
                && (ca != null || cb != null)) {
                String[] coords = key.split(",");
                BlockCell baselineCell = new BlockCell();
                baselineCell.x = Integer.parseInt(coords[0]);
                baselineCell.y = Integer.parseInt(coords[1]);
                baselineCell.z = Integer.parseInt(coords[2]);
                baselineCell.state = "<block_entity>";
                baselineCell.fluid = "<none>";
                WorldParityMismatchAnalyzer.MismatchSample sample = WorldParityMismatchAnalyzer.analyze(
                    seed, profile, dimension, baseline.chunkX, baseline.chunkZ, baselineCell, baselineCell, Map.of(), Map.of(), ca, cb);
                bucketCounts.merge(sample.bucket(), 1L, Long::sum);
                samples.add(sample);
            }
        }
        return new BlockEntityDiffResult(mismatches, bucketCounts, selectChunkRepresentativeSamples(samples));
    }

    private static void mergeBuckets(Map<String, Long> target, Map<String, Long> source) {
        if (target == null || source == null || source.isEmpty()) {
            return;
        }
        source.forEach((bucket, count) -> target.merge(bucket, count, Long::sum));
    }

    private static Map<String, BlockCell> toBlockMap(List<BlockCell> blocks) {
        LinkedHashMap<String, BlockCell> map = new LinkedHashMap<>();
        if (blocks == null) {
            return map;
        }
        for (BlockCell block : blocks) {
            map.put(block.x + "," + block.y + "," + block.z, block);
        }
        return map;
    }

    private static Map<String, BlockEntityCell> toBlockEntityMap(List<BlockEntityCell> blockEntities) {
        LinkedHashMap<String, BlockEntityCell> map = new LinkedHashMap<>();
        if (blockEntities == null) {
            return map;
        }
        for (BlockEntityCell blockEntity : blockEntities) {
            map.put(blockEntity.x + "," + blockEntity.y + "," + blockEntity.z, blockEntity);
        }
        return map;
    }

    private static boolean matchesAny(String stateA, String stateB, Set<String> markers) {
        String combined = (stateA + "|" + stateB).toLowerCase(Locale.ROOT);
        for (String marker : markers) {
            if (combined.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static List<WorldParityMismatchAnalyzer.MismatchSample> selectChunkRepresentativeSamples(List<WorldParityMismatchAnalyzer.MismatchSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }
        Map<String, List<WorldParityMismatchAnalyzer.MismatchSample>> byBucket = new LinkedHashMap<>();
        for (WorldParityMismatchAnalyzer.MismatchSample sample : samples) {
            byBucket.computeIfAbsent(sample.bucket(), ignored -> new ArrayList<>()).add(sample);
        }
        ArrayList<WorldParityMismatchAnalyzer.MismatchSample> representatives = new ArrayList<>();
        for (Map.Entry<String, List<WorldParityMismatchAnalyzer.MismatchSample>> entry : byBucket.entrySet()) {
            representatives.addAll(clusterBucketSamples(entry.getValue()));
        }
        representatives.sort(Comparator
            .comparingInt(WorldParityMismatchAnalyzer.MismatchSample::clusterSize).reversed()
            .thenComparingDouble(WorldParityMismatchAnalyzer.MismatchSample::representativeScore).reversed()
            .thenComparingInt(WorldParityMismatchAnalyzer.MismatchSample::worldY));
        if (representatives.size() > CHUNK_REPRESENTATIVE_LIMIT) {
            return new ArrayList<>(representatives.subList(0, CHUNK_REPRESENTATIVE_LIMIT));
        }
        return representatives;
    }

    private static List<WorldParityMismatchAnalyzer.MismatchSample> clusterBucketSamples(List<WorldParityMismatchAnalyzer.MismatchSample> bucketSamples) {
        if (bucketSamples == null || bucketSamples.isEmpty()) {
            return List.of();
        }
        Map<String, WorldParityMismatchAnalyzer.MismatchSample> byPos = new LinkedHashMap<>();
        for (WorldParityMismatchAnalyzer.MismatchSample sample : bucketSamples) {
            byPos.put(sample.localKey(), sample);
        }
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        ArrayList<WorldParityMismatchAnalyzer.MismatchSample> representatives = new ArrayList<>();
        for (WorldParityMismatchAnalyzer.MismatchSample seed : bucketSamples) {
            if (!visited.add(seed.localKey())) {
                continue;
            }
            ArrayList<WorldParityMismatchAnalyzer.MismatchSample> cluster = new ArrayList<>();
            ArrayList<String> frontier = new ArrayList<>();
            frontier.add(seed.localKey());
            for (int i = 0; i < frontier.size(); i++) {
                String currentKey = frontier.get(i);
                WorldParityMismatchAnalyzer.MismatchSample current = byPos.get(currentKey);
                if (current == null) {
                    continue;
                }
                cluster.add(current);
                for (String neighbor : neighborKeys(current.localX(), current.y(), current.localZ())) {
                    if (byPos.containsKey(neighbor) && visited.add(neighbor)) {
                        frontier.add(neighbor);
                    }
                }
            }
            representatives.add(pickClusterRepresentative(cluster));
        }
        return representatives;
    }

    private static WorldParityMismatchAnalyzer.MismatchSample pickClusterRepresentative(List<WorldParityMismatchAnalyzer.MismatchSample> cluster) {
        double sumX = 0.0D;
        double sumY = 0.0D;
        double sumZ = 0.0D;
        for (WorldParityMismatchAnalyzer.MismatchSample sample : cluster) {
            sumX += sample.worldX();
            sumY += sample.worldY();
            sumZ += sample.worldZ();
        }
        double centerX = sumX / cluster.size();
        double centerY = sumY / cluster.size();
        double centerZ = sumZ / cluster.size();
        WorldParityMismatchAnalyzer.MismatchSample best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (WorldParityMismatchAnalyzer.MismatchSample sample : cluster) {
            double distancePenalty = Math.abs(sample.worldX() - centerX)
                + Math.abs(sample.worldY() - centerY)
                + Math.abs(sample.worldZ() - centerZ);
            double score = sample.representativeScore()
                + cluster.size() * 4.0D
                + Math.max(0.0D, 12.0D - distancePenalty);
            if (score > bestScore) {
                bestScore = score;
                best = sample;
            }
        }
        return best == null ? cluster.get(0) : best.withClusterMetrics(cluster.size(), bestScore);
    }

    private static List<String> neighborKeys(int x, int y, int z) {
        return List.of(
            (x - 1) + "," + y + "," + z,
            (x + 1) + "," + y + "," + z,
            x + "," + (y - 1) + "," + z,
            x + "," + (y + 1) + "," + z,
            x + "," + y + "," + (z - 1),
            x + "," + y + "," + (z + 1)
        );
    }

    private static List<WorldParityMismatchAnalyzer.MismatchSample> selectLaneRepresentativeSamples(List<WorldParityMismatchAnalyzer.MismatchSample> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, List<WorldParityMismatchAnalyzer.MismatchSample>> byBucket = new LinkedHashMap<>();
        for (WorldParityMismatchAnalyzer.MismatchSample sample : candidates) {
            byBucket.computeIfAbsent(sample.bucket(), ignored -> new ArrayList<>()).add(sample);
        }
        byBucket.values().forEach(list -> list.sort(Comparator
            .comparingInt(WorldParityMismatchAnalyzer.MismatchSample::clusterSize).reversed()
            .thenComparingDouble(WorldParityMismatchAnalyzer.MismatchSample::representativeScore).reversed()
            .thenComparingInt(WorldParityMismatchAnalyzer.MismatchSample::worldY)));
        ArrayList<Map.Entry<String, List<WorldParityMismatchAnalyzer.MismatchSample>>> orderedBuckets = new ArrayList<>(byBucket.entrySet());
        orderedBuckets.sort(Comparator
            .<Map.Entry<String, List<WorldParityMismatchAnalyzer.MismatchSample>>>comparingInt(entry -> entry.getValue().isEmpty() ? 0 : entry.getValue().get(0).clusterSize()).reversed()
            .thenComparing(entry -> entry.getKey()));

        LinkedHashSet<String> seenCoords = new LinkedHashSet<>();
        ArrayList<WorldParityMismatchAnalyzer.MismatchSample> selected = new ArrayList<>();
        for (Map.Entry<String, List<WorldParityMismatchAnalyzer.MismatchSample>> entry : orderedBuckets) {
            addNextDistinctSample(entry.getValue(), selected, seenCoords);
            if (selected.size() >= REPRESENTATIVE_SAMPLE_LIMIT) {
                return selected;
            }
        }
        ArrayList<WorldParityMismatchAnalyzer.MismatchSample> remaining = new ArrayList<>();
        for (Map.Entry<String, List<WorldParityMismatchAnalyzer.MismatchSample>> entry : orderedBuckets) {
            remaining.addAll(entry.getValue());
        }
        remaining.sort(Comparator
            .comparingInt(WorldParityMismatchAnalyzer.MismatchSample::clusterSize).reversed()
            .thenComparingDouble(WorldParityMismatchAnalyzer.MismatchSample::representativeScore).reversed());
        for (WorldParityMismatchAnalyzer.MismatchSample sample : remaining) {
            if (selected.size() >= REPRESENTATIVE_SAMPLE_LIMIT) {
                break;
            }
            String coordKey = sample.chunkX() + "," + sample.chunkZ() + ":" + sample.localKey();
            if (seenCoords.add(coordKey)) {
                selected.add(sample);
            }
        }
        return selected;
    }

    private static void addNextDistinctSample(List<WorldParityMismatchAnalyzer.MismatchSample> source,
                                              List<WorldParityMismatchAnalyzer.MismatchSample> target,
                                              Set<String> seenCoords) {
        for (WorldParityMismatchAnalyzer.MismatchSample sample : source) {
            String coordKey = sample.chunkX() + "," + sample.chunkZ() + ":" + sample.localKey();
            if (seenCoords.add(coordKey)) {
                target.add(sample);
                return;
            }
        }
    }

    private static String stableStateString(BlockState state) {
        if (state == null) {
            return "<null>";
        }
        Block block = state.getBlock();
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        StringBuilder sb = new StringBuilder(key == null ? String.valueOf(block) : key.toString());
        if (!state.getValues().isEmpty()) {
            ArrayList<String> props = new ArrayList<>();
            state.getValues().forEach((property, value) -> props.add(property.getName() + "=" + value));
            props.sort(String::compareTo);
            sb.append('[').append(String.join(",", props)).append(']');
        }
        return sb.toString();
    }

    private static String stableFluidString(FluidState fluid) {
        if (fluid == null || fluid.isEmpty()) {
            return "<none>";
        }
        ResourceLocation key = ForgeRegistries.FLUIDS.getKey(fluid.getType());
        return (key == null ? String.valueOf(fluid.getType()) : key.toString())
            + "[amount=" + fluid.getAmount() + ",source=" + fluid.isSource() + "]";
    }

    private static String stableHash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update((value == null ? "<null>" : value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String stableJson(Object value) {
        return WRITE_GSON.toJson(value);
    }

    private static String safeProfile(IDimensionInfo dimInfo) {
        try {
            return dimInfo.getProfile() != null && dimInfo.getProfile().getName() != null ? dimInfo.getProfile().getName() : "unknown";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String safeOutsideProfile(IDimensionInfo dimInfo) {
        try {
            return dimInfo.getOutsideProfile() != null && dimInfo.getOutsideProfile().getName() != null ? dimInfo.getOutsideProfile().getName() : "unknown";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String safeWorldStyle(IDimensionInfo dimInfo) {
        try {
            return dimInfo.getWorldStyle() != null && dimInfo.getWorldStyle().getName() != null ? dimInfo.getWorldStyle().getName() : "unknown";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String safeString(Object value) {
        return value == null ? "<none>" : String.valueOf(value);
    }

    private static String worldGenSettingsFingerprint(PrimaryLevelData data) {
        if (data == null) {
            return "<none>";
        }
        WorldOptions options = data.worldGenOptions();
        if (options == null) {
            return "worldOptions=<none>|lifecycle=" + safeString(data.worldGenSettingsLifecycle());
        }
        return "seed=" + options.seed()
            + "|generateStructures=" + options.generateStructures()
            + "|generateBonusChest=" + options.generateBonusChest()
            + "|oldCustomizedWorld=" + options.isOldCustomizedWorld()
            + "|lifecycle=" + safeString(data.worldGenSettingsLifecycle());
    }

    public record ExportSummary(String role, String outputPath, int chunkCount, String fingerprintHash) {
        public String summary() {
            return "role=" + role + " chunks=" + chunkCount + " fingerprint=" + fingerprintHash + " output=" + outputPath;
        }
    }

    public static final class ExportCapture {
        private final ServerLevel level;
        private final IDimensionInfo provider;
        private final ExportDocument document;
        private final List<ChunkCoord> coords;
        private int nextIndex;

        private ExportCapture(ServerLevel level, IDimensionInfo provider, ExportDocument document, List<ChunkCoord> coords) {
            this.level = level;
            this.provider = provider;
            this.document = document;
            this.coords = coords;
        }

        public int totalChunks() {
            return coords.size();
        }

        public int completedChunks() {
            return nextIndex;
        }

        public int remainingChunks() {
            return Math.max(0, coords.size() - nextIndex);
        }

        public boolean hasRemaining() {
            return nextIndex < coords.size();
        }

        public void captureNext() {
            if (!hasRemaining()) {
                return;
            }
            ChunkCoord coord = coords.get(nextIndex++);
            document.chunks.add(captureChunk(level, provider, coord));
        }

        private ExportDocument document() {
            return document;
        }
    }

    public record ComparisonSummary(boolean environmentMatch,
                                    boolean matrixMatch,
                                    boolean windowMatch,
                                    int totalChunksCompared,
                                    int missingChunks,
                                    int extraChunks,
                                    int chunkStatusMismatches,
                                    int probableDrift,
                                    int borderSeams,
                                    int multichunkOriginMismatches,
                                    long totalBlocksCompared,
                                    long blockMismatches,
                                    long blockStateOnlyMismatches,
                                    int blockEntityMismatches,
                                    int heightmapDifferences,
                                    long treeMismatches,
                                    long vineMismatches,
                                    long gravityMismatches,
                                    long unknownMismatches,
                                    long fluidMismatches,
                                    long acceptedMaterialVariance,
                                    long hardMismatchCount,
                                    long unclassifiedMismatchCount,
                                    String outputPath,
                                    List<String> environmentDifferences) {
        public String summary() {
            return "envMatch=" + environmentMatch
                + " matrixMatch=" + matrixMatch
                + " windowMatch=" + windowMatch
                + " chunks=" + totalChunksCompared
                + " missing=" + missingChunks
                + " extra=" + extraChunks
                + " statusMismatch=" + chunkStatusMismatches
                + " drift=" + probableDrift
                + " seams=" + borderSeams
                + " multichunk=" + multichunkOriginMismatches
                + " blocks=" + totalBlocksCompared
                + " blockMismatch=" + blockMismatches
                + " stateOnly=" + blockStateOnlyMismatches
                + " beMismatch=" + blockEntityMismatches
                + " heightmap=" + heightmapDifferences
                + " tree=" + treeMismatches
                + " vines=" + vineMismatches
                + " gravity=" + gravityMismatches
                + " unknown=" + unknownMismatches
                + " fluid=" + fluidMismatches
                + " acceptedMaterial=" + acceptedMaterialVariance
                + " hard=" + hardMismatchCount
                + " unclassified=" + unclassifiedMismatchCount
                + " output=" + outputPath
                + " envDiffs=" + environmentDifferences;
        }
    }

    private static final class ExportDocument {
        String matrixId;
        String exportedAt;
        String role;
        String dimension;
        long seed;
        String profile;
        String outsideProfile;
        String worldStyle;
        String laneId;
        int generatedCenterChunkX;
        int generatedCenterChunkZ;
        int generatedMultiChunkX;
        int generatedMultiChunkZ;
        ExportWindow exportWindow;
        EnvironmentFingerprint environment;
        List<ChunkSnapshot> chunks;
    }

    private static final class ComparisonDocument {
        String generatedAt;
        String matrixId;
        String baselinePath;
        String testPath;
        EnvironmentFingerprint baselineFingerprint;
        EnvironmentFingerprint testFingerprint;
        String baselineMatrixId;
        String testMatrixId;
        String baselineLaneId;
        String testLaneId;
        ExportWindow baselineExportWindow;
        ExportWindow testExportWindow;
        String legacyRetiredMatrix;
        boolean environmentMatch;
        boolean matrixMatch;
        boolean windowMatch;
        List<String> environmentDifferences;
        int totalChunksCompared;
        int missingChunks;
        int extraChunks;
        int chunkStatusMismatches;
        int probableDrift;
        int borderSeams;
        int multichunkOriginMismatches;
        long totalBlocksCompared;
        long blockMismatches;
        long blockStateOnlyMismatches;
        int blockEntityMismatches;
        int heightmapDifferences;
        long treeMismatches;
        long vineMismatches;
        long gravityMismatches;
        long unknownMismatches;
        long fluidMismatches;
        long acceptedMaterialVariance;
        long hardMismatchCount;
        long unclassifiedMismatchCount;
        List<String> unsupportedFields;
        Map<String, Long> mismatchBuckets;
        Map<String, Long> acceptedVarianceBuckets;
        Map<String, Long> hardMismatchBuckets;
        Map<String, Long> hardStatePairBuckets;
        Map<String, Long> unclassifiedMismatchBuckets;
        List<WorldParityMismatchAnalyzer.MismatchSample> mismatchSamples;
        List<ChunkComparison> chunkDetails;
    }

    public static final class EnvironmentFingerprint {
        String role;
        String runtimeMode;
        boolean baselineMode;
        String minecraftVersion;
        String forgeVersion;
        String mappings;
        String dimension;
        long seed;
        String profile;
        String outsideProfile;
        String worldStyle;
        String lostCitiesVersion;
        String lc2hVersion;
        boolean lc2hEnabled;
        String runDir;
        List<String> selectedPacks;
        List<String> mods;
        Map<String, String> gameRules;
        Map<String, String> configHashes;
        String worldGenSettingsHash;
        String fingerprintHash;
    }

    public static final class ChunkSnapshot {
        int chunkX;
        int chunkZ;
        String dimension;
        boolean present;
        String status;
        int minBuildY;
        int maxBuildY;
        long totalBlocks;
        Map<String, int[]> heightmaps;
        String heightmapHash;
        List<StructureInfo> structures;
        String structureAnchorHash;
        ChunkCharacteristics characteristics;
        String multichunkHash;
        List<BlockCell> blocks;
        String stateHash;
        List<BlockEntityCell> blockEntities;
        String blockEntityHash;
    }

    public static final class StructureInfo {
        String id;
        int minX;
        int minY;
        int minZ;
        int maxX;
        int maxY;
        int maxZ;
    }

    public static final class ChunkCharacteristics {
        boolean isCity;
        int cityLevel;
        boolean canHaveBuilding;
        String buildingType;
        String bridgeType;
        int multiX = Integer.MIN_VALUE;
        int multiZ = Integer.MIN_VALUE;
        int multiW = Integer.MIN_VALUE;
        int multiH = Integer.MIN_VALUE;
        boolean multiTopLeft;
        String error;
    }

    public static final class BlockCell {
        int x;
        int y;
        int z;
        String state;
        String fluid;
    }

    public static final class BlockEntityCell {
        int x;
        int y;
        int z;
        String type;
        String nbt;
    }

    private static final class ChunkComparison {
        String chunkKey;
        String category;
        boolean baselinePresent;
        boolean testPresent;
        String statusMismatch;
        String probableDrift;
        boolean multichunkMismatch;
        boolean heightmapMismatch;
        long blockMismatchCount;
        long stateOnlyMismatchCount;
        int blockEntityMismatchCount;

        boolean hasAnyMismatch() {
            return statusMismatch != null
                || probableDrift != null
                || multichunkMismatch
                || heightmapMismatch
                || blockMismatchCount > 0
                || blockEntityMismatchCount > 0
                || (category != null && !category.isBlank());
        }
    }

    private record BlockDiffResult(long blockMismatches,
                                   long stateOnlyMismatches,
                                   long treeMismatches,
                                   long vineMismatches,
                                   long gravityMismatches,
                                   long unknownMismatches,
                                   long fluidMismatches,
                                   long acceptedMaterialVariance,
                                   long hardMismatchCount,
                                   long unclassifiedMismatchCount,
                                   Set<String> seamKeys,
                                   Map<String, Long> bucketCounts,
                                   Map<String, Long> acceptedVarianceBuckets,
                                   Map<String, Long> hardMismatchBuckets,
                                   Map<String, Long> hardStatePairBuckets,
                                   Map<String, Long> unclassifiedMismatchBuckets,
                                   List<WorldParityMismatchAnalyzer.MismatchSample> samples) {
    }

    private record BlockEntityDiffResult(int mismatches,
                                         Map<String, Long> bucketCounts,
                                         List<WorldParityMismatchAnalyzer.MismatchSample> samples) {
    }

    public record ExportMetadata(String laneId,
                                 int generatedCenterChunkX,
                                 int generatedCenterChunkZ,
                                 int generatedMultiChunkX,
                                 int generatedMultiChunkZ) {
    }

    public record ExportWindow(int minChunkX,
                               int maxChunkX,
                               int minChunkZ,
                               int maxChunkZ,
                               int chunkCount) {
    }
}
