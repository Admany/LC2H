package org.admany.lc2h.dev.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.admany.lc2h.worldgen.apply.ShadowMutationTraceRegistry;
import org.admany.lc2h.util.ResourceLocations;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class WorldParityMismatchAnalyzer {
    enum MismatchDisposition {
        ACCEPTED_MATERIAL_VARIANCE,
        HARD_FAILURE,
        UNCLASSIFIED
    }

    record MismatchClassification(MismatchDisposition disposition, String reason) {
    }

    private static final Set<String> TREE_BLOCK_IDS = Set.of(
        "minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log", "minecraft:jungle_log",
        "minecraft:acacia_log", "minecraft:dark_oak_log", "minecraft:mangrove_log", "minecraft:cherry_log",
        "minecraft:crimson_stem", "minecraft:warped_stem", "minecraft:mushroom_stem"
    );
    private static final Set<String> VINE_IDS = Set.of(
        "minecraft:vine", "minecraft:cave_vines", "minecraft:cave_vines_plant",
        "minecraft:weeping_vines", "minecraft:weeping_vines_plant",
        "minecraft:twisting_vines", "minecraft:twisting_vines_plant"
    );
    private static final Set<String> ROOT_IDS = Set.of(
        "minecraft:hanging_roots", "minecraft:mangrove_roots", "minecraft:muddy_mangrove_roots",
        "minecraft:rooted_dirt", "minecraft:crimson_roots", "minecraft:warped_roots"
    );
    private static final Set<String> BUILDING_SHELL_MARKERS = Set.of(
        "brick", "glass", "planks", "terracotta", "concrete", "stone_bricks", "deepslate_tiles", "deepslate_bricks"
    );
    private static final Set<String> BIOSPHERE_MATERIAL_MARKERS = Set.of(
        "clay", "tuff", "deepslate", "calcite", "dripstone", "granite", "diorite", "andesite", "terracotta",
        "packed_mud", "mud", "moss", "moss_block", "rooted_dirt", "mycelium"
    );
    private static final Set<String> ORE_MATERIAL_MARKERS = Set.of(
        "_ore", "stone", "deepslate", "tuff", "granite", "diorite", "andesite", "calcite", "dripstone", "clay", "gravel", "sand"
    );

    private WorldParityMismatchAnalyzer() {
    }

    static MismatchSample analyze(long seed,
                                  String profile,
                                  String dimension,
                                  int chunkX,
                                  int chunkZ,
                                  WorldParityHarness.BlockCell baseline,
                                  WorldParityHarness.BlockCell test,
                                  Map<String, WorldParityHarness.BlockCell> baselineBlocks,
                                  Map<String, WorldParityHarness.BlockCell> testBlocks,
                                  WorldParityHarness.BlockEntityCell baselineBe,
                                  WorldParityHarness.BlockEntityCell testBe) {
        int x = baseline != null ? baseline.x : test.x;
        int y = baseline != null ? baseline.y : test.y;
        int z = baseline != null ? baseline.z : test.z;
        BlockPos worldPos = new BlockPos((chunkX << 4) + x, y, (chunkZ << 4) + z);
        String baselineState = baseline == null ? "<air>" : baseline.state;
        String testState = test == null ? "<air>" : test.state;
        String baselineFluid = baseline == null ? "<none>" : baseline.fluid;
        String testFluid = test == null ? "<none>" : test.fluid;
        ShadowMutationTraceRegistry.TraceSnapshot trace = ShadowMutationTraceRegistry.findLatest(ResourceLocations.tryParse(dimension), worldPos);
        String bucket = classify(baselineState, testState, baselineFluid, testFluid, baselineBe, testBe);
        boolean nearBorder = x == 0 || x == 15 || z == 0 || z == 15;
        NeighborScan neighborScan = scanNeighbors(x, y, z, bucket, baselineBlocks, testBlocks);
        boolean treeBoundingBoxHint = trace != null && trace.deferredTreeId() > 0
            || isTreeLikeBlockId(extractBlockId(baselineState))
            || isTreeLikeBlockId(extractBlockId(testState))
            || neighborScan.treeCount > 0;
        boolean fluidAdjacent = !"<none>".equals(baselineFluid)
            || !"<none>".equals(testFluid)
            || neighborScan.fluidCount > 0;
        boolean caveVineAdjacent = isCaveVineLike(extractBlockId(baselineState))
            || isCaveVineLike(extractBlockId(testState))
            || neighborScan.caveVineCount > 0;
        boolean buildingShellAdjacent = isBuildingShellLike(extractBlockId(baselineState))
            || isBuildingShellLike(extractBlockId(testState))
            || neighborScan.buildingShellCount > 0;
        boolean biosphereShellMaterialAdjacent = isBiosphereMaterialLike(extractBlockId(baselineState))
            || isBiosphereMaterialLike(extractBlockId(testState))
            || neighborScan.biosphereMaterialCount > 0;
        boolean oreMaterialLike = isOreMaterialLike(extractBlockId(baselineState))
            || isOreMaterialLike(extractBlockId(testState))
            || neighborScan.oreMaterialCount > 0;
        return new MismatchSample(
            bucket,
            seed,
            profile,
            dimension,
            chunkX,
            chunkZ,
            x,
            y,
            z,
            worldPos.getX(),
            worldPos.getY(),
            worldPos.getZ(),
            baselineState,
            testState,
            baselineFluid,
            testFluid,
            baselineBe == null ? null : baselineBe.type,
            baselineBe == null ? null : baselineBe.nbt,
            testBe == null ? null : testBe.type,
            testBe == null ? null : testBe.nbt,
            trace == null ? 0L : trace.transactionId(),
            trace == null ? 0L : trace.deferredTreeId(),
            trace == null ? -1 : trace.operationIndex(),
            trace == null ? chunkX : trace.destinationChunkX(),
            trace == null ? chunkZ : trace.destinationChunkZ(),
            trace != null && trace.captured(),
            trace != null && trace.finalized(),
            trace != null && trace.applied(),
            trace != null && trace.rejected(),
            false,
            trace != null && trace.neighborAdjusted(),
            trace != null && (trace.scheduledFluidTick() || trace.scheduledGravityTick()),
            nearBorder,
            neighborScan.summary(),
            treeBoundingBoxHint,
            fluidAdjacent,
            caveVineAdjacent,
            buildingShellAdjacent,
            biosphereShellMaterialAdjacent,
            oreMaterialLike,
            1,
            baseRepresentativeScore(bucket, nearBorder, treeBoundingBoxHint, fluidAdjacent, caveVineAdjacent, buildingShellAdjacent, biosphereShellMaterialAdjacent, oreMaterialLike),
            trace == null ? null : trace.mutationKind(),
            trace == null ? null : trace.lastStage(),
            trace == null ? null : trace.rejectReason(),
            trace == null ? null : trace.retryReason(),
            trace == null ? null : trace.firstObservedTime()
        );
    }

    private static String classify(String baselineState,
                                   String testState,
                                   String baselineFluid,
                                   String testFluid,
                                   WorldParityHarness.BlockEntityCell baselineBe,
                                   WorldParityHarness.BlockEntityCell testBe) {
        String combined = (baselineState + "|" + testState + "|" + baselineFluid + "|" + testFluid).toLowerCase(Locale.ROOT);
        String baselineId = extractBlockId(baselineState);
        String testId = extractBlockId(testState);
        if (baselineBe != null || testBe != null) {
            return "block_entity";
        }
        if (isCaveVineLike(baselineId) || isCaveVineLike(testId)) {
            return combined.contains("berries=true") ? "berry_state" : "cave_vines";
        }
        if (matchesEitherId(baselineId, testId, "minecraft:weeping_vines", "minecraft:weeping_vines_plant")) {
            return "weeping_vines";
        }
        if (matchesEitherId(baselineId, testId, "minecraft:twisting_vines", "minecraft:twisting_vines_plant")) {
            return "twisting_vines";
        }
        if (matchesEitherId(baselineId, testId, "minecraft:vine")) {
            return "vines";
        }
        if (matchesEitherId(baselineId, testId, "minecraft:hanging_roots")) {
            return "hanging_roots";
        }
        if (isRootLike(baselineId) || isRootLike(testId)) {
            return "tree_root";
        }
        if (containsToken(baselineId, "branch") || containsToken(testId, "branch")) {
            return "tree_branch";
        }
        if (isLeafLike(baselineId) || isLeafLike(testId)) {
            return "leaves";
        }
        if (isTrunkLike(baselineId) || isTrunkLike(testId)) {
            return "tree_trunk";
        }
        if (combined.contains("waterlogged=true")) {
            return "waterlogged_block";
        }
        if (isWaterLike(baselineId, baselineFluid) || isWaterLike(testId, testFluid)) {
            if (combined.contains("source=false") || combined.contains("amount=") && !combined.contains("amount=8")) {
                return "flowing_water";
            }
            return "water_source";
        }
        if (isLavaLike(baselineId, baselineFluid) || isLavaLike(testId, testFluid)) {
            if (combined.contains("source=false") || combined.contains("amount=") && !combined.contains("amount=8")) {
                return "flowing_lava";
            }
            return "lava_source";
        }
        if (isGravityLike(baselineId) || isGravityLike(testId)) {
            return "gravity_sensitive";
        }
        if (containsToken(baselineId, "rail") || containsToken(testId, "rail")) {
            return "railway";
        }
        if (containsToken(baselineId, "highway") || containsToken(testId, "highway")) {
            return "highway";
        }
        if (containsToken(baselineId, "road") || containsToken(testId, "road")
            || containsToken(baselineId, "street") || containsToken(testId, "street")
            || containsToken(baselineId, "asphalt") || containsToken(testId, "asphalt")) {
            return "road_street";
        }
        if (isBuildingShellLike(baselineId) || isBuildingShellLike(testId)) {
            return "building_shell";
        }
        if (!"<none>".equals(baselineFluid) || !"<none>".equals(testFluid)) {
            return "post_processing";
        }
        return "unknown";
    }

    static MismatchClassification classifyDisposition(MismatchSample sample) {
        String baselineId = extractBlockId(sample.baselineState());
        String testId = extractBlockId(sample.lc2hState());
        String baselineFluid = sample.baselineFluid();
        String testFluid = sample.lc2hFluid();

        if (sample.baselineBlockEntityType() != null || sample.lc2hBlockEntityType() != null) {
            return new MismatchClassification(MismatchDisposition.HARD_FAILURE, "block_entity");
        }
        if (!Objects.equals(baselineFluid, testFluid)
            || !"<none>".equals(baselineFluid)
            || !"<none>".equals(testFluid)
            || containsToken(sample.baselineState(), "waterlogged")
            || containsToken(sample.lc2hState(), "waterlogged")) {
            return new MismatchClassification(MismatchDisposition.HARD_FAILURE, "fluid_or_waterlogged");
        }
        if (isAirState(sample.baselineState()) != isAirState(sample.lc2hState())) {
            return new MismatchClassification(MismatchDisposition.HARD_FAILURE, "air_solid");
        }
        if (isTreeLikeBlockId(baselineId) || isTreeLikeBlockId(testId)
            || isCaveVineLike(baselineId) || isCaveVineLike(testId)
            || matchesEitherId(baselineId, testId, "minecraft:vine", "minecraft:weeping_vines", "minecraft:weeping_vines_plant",
                "minecraft:twisting_vines", "minecraft:twisting_vines_plant")
            || isGravityLike(baselineId) || isGravityLike(testId)) {
            return new MismatchClassification(MismatchDisposition.HARD_FAILURE, "tree_vine_or_gravity");
        }
        if (sameMaterialFamily(baselineId, testId)
            && (!isLeafLike(baselineId) || leafStateIsPaletteOnly(sample.baselineState(), sample.lc2hState()))) {
            return new MismatchClassification(MismatchDisposition.ACCEPTED_MATERIAL_VARIANCE, materialFamily(baselineId));
        }
        if (Objects.equals(baselineId, testId) && isPaletteMaterialId(baselineId)
            && paletteStateOnly(sample.baselineState(), sample.lc2hState())) {
            return new MismatchClassification(MismatchDisposition.ACCEPTED_MATERIAL_VARIANCE, materialFamily(baselineId));
        }
        if (isBuildingShellLike(baselineId) || isBuildingShellLike(testId)
            || containsToken(baselineId, "structure") || containsToken(testId, "structure")) {
            return new MismatchClassification(MismatchDisposition.HARD_FAILURE, "building_shell");
        }
        return new MismatchClassification(MismatchDisposition.UNCLASSIFIED, sample.bucket());
    }

    private static boolean isAirState(String state) {
        String id = extractBlockId(state);
        return state == null || state.isBlank() || "<air>".equals(state)
            || isId(id, "minecraft:air", "minecraft:cave_air", "minecraft:void_air");
    }

    private static boolean sameMaterialFamily(String baselineId, String testId) {
        String a = materialFamily(baselineId);
        return a != null && a.equals(materialFamily(testId));
    }

    private static String materialFamily(String id) {
        if (id == null || id.isBlank()) return null;
        if (isLeafLike(id)) return "leaves_palette";
        if (containsToken(id, "moss") || isId(id, "minecraft:moss_block", "minecraft:mossy_cobblestone", "minecraft:mossy_stone_bricks")) {
            return "moss_palette";
        }
        if (isId(id, "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt", "minecraft:podzol", "minecraft:mycelium")) {
            return "dirt_palette";
        }
        if (isId(id, "minecraft:stone_bricks", "minecraft:cracked_stone_bricks", "minecraft:mossy_stone_bricks")) {
            return "masonry_palette";
        }
        if (isId(id, "minecraft:cobblestone", "minecraft:mossy_cobblestone", "minecraft:stone", "minecraft:gravel",
            "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:deepslate", "minecraft:tuff")) {
            return "rubble_palette";
        }
        return null;
    }

    private static boolean isPaletteMaterialId(String id) {
        return materialFamily(id) != null;
    }

    private static boolean paletteStateOnly(String baselineState, String testState) {
        return !containsToken(baselineState, "waterlogged") && !containsToken(testState, "waterlogged")
            && !containsToken(baselineState, "powered") && !containsToken(testState, "powered")
            && !containsToken(baselineState, "lit") && !containsToken(testState, "lit")
            && !containsToken(baselineState, "facing") && !containsToken(testState, "facing");
    }

    private static boolean leafStateIsPaletteOnly(String baselineState, String testState) {
        return paletteStateOnly(baselineState, testState)
            && !containsToken(baselineState, "distance") && !containsToken(testState, "distance")
            && !containsToken(baselineState, "persistent") && !containsToken(testState, "persistent");
    }

    private static NeighborScan scanNeighbors(int x,
                                              int y,
                                              int z,
                                              String bucket,
                                              Map<String, WorldParityHarness.BlockCell> baselineBlocks,
                                              Map<String, WorldParityHarness.BlockCell> testBlocks) {
        int sameBucket = 0;
        int fluid = 0;
        int tree = 0;
        int caveVine = 0;
        int buildingShell = 0;
        int biosphereMaterial = 0;
        int oreMaterial = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    String key = (x + dx) + "," + (y + dy) + "," + (z + dz);
                    WorldParityHarness.BlockCell baseline = baselineBlocks.get(key);
                    WorldParityHarness.BlockCell test = testBlocks.get(key);
                    if (baseline == null && test == null) {
                        continue;
                    }
                    String baselineState = baseline == null ? "<air>" : baseline.state;
                    String testState = test == null ? "<air>" : test.state;
                    String baselineFluid = baseline == null ? "<none>" : baseline.fluid;
                    String testFluid = test == null ? "<none>" : test.fluid;
                    String baselineId = extractBlockId(baselineState);
                    String testId = extractBlockId(testState);
                    if (!Objects.equals(baselineState, testState) || !Objects.equals(baselineFluid, testFluid)) {
                        String neighborBucket = classify(baselineState, testState, baselineFluid, testFluid, null, null);
                        if (bucket.equals(neighborBucket)) {
                            sameBucket++;
                        }
                    }
                    if (isWaterLike(baselineId, baselineFluid) || isWaterLike(testId, testFluid) || isLavaLike(baselineId, baselineFluid) || isLavaLike(testId, testFluid)) {
                        fluid++;
                    }
                    if (isTreeLikeBlockId(baselineId) || isTreeLikeBlockId(testId)) {
                        tree++;
                    }
                    if (isCaveVineLike(baselineId) || isCaveVineLike(testId) || matchesEitherId(baselineId, testId, "minecraft:vine")) {
                        caveVine++;
                    }
                    if (isBuildingShellLike(baselineId) || isBuildingShellLike(testId)) {
                        buildingShell++;
                    }
                    if (isBiosphereMaterialLike(baselineId) || isBiosphereMaterialLike(testId)) {
                        biosphereMaterial++;
                    }
                    if (isOreMaterialLike(baselineId) || isOreMaterialLike(testId)) {
                        oreMaterial++;
                    }
                }
            }
        }
        return new NeighborScan(sameBucket, fluid, tree, caveVine, buildingShell, biosphereMaterial, oreMaterial);
    }

    private static double baseRepresentativeScore(String bucket,
                                                  boolean nearBorder,
                                                  boolean treeBoundingBoxHint,
                                                  boolean fluidAdjacent,
                                                  boolean caveVineAdjacent,
                                                  boolean buildingShellAdjacent,
                                                  boolean biosphereShellMaterialAdjacent,
                                                  boolean oreMaterialLike) {
        double score = switch (bucket) {
            case "unknown" -> 120.0;
            case "flowing_water", "flowing_lava", "water_source", "lava_source" -> 110.0;
            case "cave_vines", "berry_state", "vines", "weeping_vines", "twisting_vines",
                "tree_root", "tree_branch", "tree_trunk", "leaves", "hanging_roots" -> 100.0;
            case "building_shell" -> 95.0;
            case "gravity_sensitive" -> 90.0;
            case "block_entity" -> 85.0;
            default -> 80.0;
        };
        if (treeBoundingBoxHint) score += 8.0;
        if (fluidAdjacent) score += 8.0;
        if (caveVineAdjacent) score += 6.0;
        if (buildingShellAdjacent) score += 6.0;
        if (biosphereShellMaterialAdjacent) score += 10.0;
        if (oreMaterialLike) score += 4.0;
        if (nearBorder) score += 2.0;
        return score;
    }

    private static boolean isTreeLikeBlockId(String blockId) {
        return isLeafLike(blockId) || isTrunkLike(blockId) || isRootLike(blockId) || containsToken(blockId, "sapling");
    }

    private static boolean isLeafLike(String blockId) {
        return hasSuffix(blockId, "_leaves") || containsToken(blockId, "azalea_leaves");
    }

    private static boolean isTrunkLike(String blockId) {
        return TREE_BLOCK_IDS.contains(blockId) || hasSuffix(blockId, "_log") || hasSuffix(blockId, "_stem")
            || hasSuffix(blockId, "_wood") || hasSuffix(blockId, "_hyphae");
    }

    private static boolean isRootLike(String blockId) {
        return ROOT_IDS.contains(blockId);
    }

    private static boolean isCaveVineLike(String blockId) {
        return isId(blockId, "minecraft:cave_vines", "minecraft:cave_vines_plant");
    }

    private static boolean isGravityLike(String blockId) {
        return isId(blockId, "minecraft:sand", "minecraft:red_sand", "minecraft:gravel", "minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil", "minecraft:scaffolding")
            || hasSuffix(blockId, "_concrete_powder");
    }

    private static boolean isBuildingShellLike(String blockId) {
        return containsAnyToken(blockId, BUILDING_SHELL_MARKERS);
    }

    private static boolean isBiosphereMaterialLike(String blockId) {
        return containsAnyToken(blockId, BIOSPHERE_MATERIAL_MARKERS);
    }

    private static boolean isOreMaterialLike(String blockId) {
        return containsAnyToken(blockId, ORE_MATERIAL_MARKERS);
    }

    private static boolean isWaterLike(String blockId, String fluidState) {
        return isId(blockId, "minecraft:water") || fluidState.toLowerCase(Locale.ROOT).contains("minecraft:water");
    }

    private static boolean isLavaLike(String blockId, String fluidState) {
        return isId(blockId, "minecraft:lava") || fluidState.toLowerCase(Locale.ROOT).contains("minecraft:lava");
    }

    private static boolean isId(String blockId, String... ids) {
        if (blockId == null || blockId.isBlank()) {
            return false;
        }
        for (String id : ids) {
            if (id.equals(blockId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesEitherId(String baselineId, String testId, String... ids) {
        return isId(baselineId, ids) || isId(testId, ids);
    }

    private static boolean containsAnyToken(String blockId, Set<String> markers) {
        if (blockId == null || blockId.isBlank()) {
            return false;
        }
        for (String marker : markers) {
            if (containsToken(blockId, marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsToken(String value, String token) {
        if (value == null || value.isBlank()) {
            return false;
        }
        // Block ids are normalized to lower-case by extractBlockId().  This
        // method sits inside the per-mismatch neighbour scan, so allocating
        // lowercase/replaced copies for every marker turns large parity
        // reports into minutes of CPU-only work.
        int start = value.startsWith("minecraft:") ? "minecraft:".length() : 0;
        int valueLength = value.length();
        int tokenLength = token.length();
        if (tokenLength == 0 || start + tokenLength > valueLength) {
            return false;
        }
        for (int index = value.indexOf(token, start); index >= 0; index = value.indexOf(token, index + 1)) {
            boolean leftBoundary = index == start || value.charAt(index - 1) == '_';
            int end = index + tokenLength;
            boolean rightBoundary = end == valueLength || value.charAt(end) == '_';
            if (leftBoundary && rightBoundary) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSuffix(String value, String suffix) {
        return value != null && value.endsWith(suffix);
    }

    private static String extractBlockId(String state) {
        if (state == null || state.isBlank() || state.charAt(0) == '<') {
            return "";
        }
        int propertyIndex = state.indexOf('[');
        return (propertyIndex >= 0 ? state.substring(0, propertyIndex) : state).toLowerCase(Locale.ROOT);
    }

    record MismatchSample(String bucket,
                          long seed,
                          String profile,
                          String dimension,
                          int chunkX,
                          int chunkZ,
                          int localX,
                          int y,
                          int localZ,
                          int worldX,
                          int worldY,
                          int worldZ,
                          String baselineState,
                          String lc2hState,
                          String baselineFluid,
                          String lc2hFluid,
                          String baselineBlockEntityType,
                          String baselineBlockEntityNbt,
                          String lc2hBlockEntityType,
                          String lc2hBlockEntityNbt,
                          long transactionId,
                          long deferredTreeId,
                          int operationIndex,
                          int destinationChunkX,
                          int destinationChunkZ,
                          boolean captured,
                          boolean finalized,
                          boolean applied,
                          boolean rejected,
                          boolean syncFallback,
                          boolean neighborAffected,
                          boolean scheduledTickEffect,
                          boolean nearChunkBorder,
                          String neighborSummary,
                          boolean treeBoundingBoxHint,
                          boolean fluidAdjacent,
                          boolean caveVineAdjacent,
                          boolean buildingShellAdjacent,
                          boolean biosphereShellMaterialAdjacent,
                          boolean oreMaterialLike,
                          int clusterSize,
                          double representativeScore,
                          String mutationKind,
                          String lastStage,
                          String rejectReason,
                          String retryReason,
                          String firstObservedTime) {

        String localKey() {
            return localX + "," + y + "," + localZ;
        }

        MismatchSample withClusterMetrics(int newClusterSize, double newRepresentativeScore) {
            return new MismatchSample(
                bucket, seed, profile, dimension, chunkX, chunkZ, localX, y, localZ, worldX, worldY, worldZ,
                baselineState, lc2hState, baselineFluid, lc2hFluid,
                baselineBlockEntityType, baselineBlockEntityNbt, lc2hBlockEntityType, lc2hBlockEntityNbt,
                transactionId, deferredTreeId, operationIndex, destinationChunkX, destinationChunkZ,
                captured, finalized, applied, rejected, syncFallback, neighborAffected, scheduledTickEffect, nearChunkBorder,
                neighborSummary, treeBoundingBoxHint, fluidAdjacent, caveVineAdjacent, buildingShellAdjacent,
                biosphereShellMaterialAdjacent, oreMaterialLike, newClusterSize, newRepresentativeScore,
                mutationKind, lastStage, rejectReason, retryReason, firstObservedTime
            );
        }
    }

    private record NeighborScan(int sameBucketCount,
                                int fluidCount,
                                int treeCount,
                                int caveVineCount,
                                int buildingShellCount,
                                int biosphereMaterialCount,
                                int oreMaterialCount) {
        String summary() {
            return "sameBucketR1=" + sameBucketCount
                + ",fluidR1=" + fluidCount
                + ",treeR1=" + treeCount
                + ",caveVineR1=" + caveVineCount
                + ",shellR1=" + buildingShellCount
                + ",biosphereR1=" + biosphereMaterialCount
                + ",oreR1=" + oreMaterialCount;
        }
    }
}
