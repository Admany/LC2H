package org.admany.lc2h.dev.debug;

import mcjty.lostcities.varia.ChunkCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.worldgen.apply.ChunkShadowMutationPlan;
import org.admany.lc2h.worldgen.apply.ShadowBlockMutationApplier;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeCaptureContext;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeEventHandler;
import org.admany.lc2h.worldgen.lostcities.DeferredTreeQueue;
import org.admany.lc2h.worldgen.lostcities.MidgardTreeCaptureHooks;
import org.admany.lc2h.worldgen.lostcities.TreeCapturePolicy;
import org.admany.lc2h.worldgen.lostcities.TreeCompatTracker;
import org.admany.lc2h.util.ResourceLocations;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.lang.reflect.Method;

public final class ShadowMutationRuntimeHarness {

    public record ScenarioResult(String scenario, boolean success, int drainCycles, List<String> details) {
        public String summary() {
            return "scenario=" + scenario + " success=" + success + " drainCycles=" + drainCycles + " details=" + String.join(" | ", details);
        }
    }

    private static final int DEBUG_FLAGS = 2;

    private ShadowMutationRuntimeHarness() {
    }

    public static List<ScenarioResult> run(ServerLevel level, BlockPos requestedOrigin, String scenarioName) {
        List<ScenarioResult> results = new ArrayList<>();
        String scenario = scenarioName == null ? "all" : scenarioName.toLowerCase(Locale.ROOT);
        if (ShadowBlockMutationApplier.hasPendingWork()) {
            return List.of(new ScenarioResult(
                scenario,
                false,
                0,
                List.of("shadow mutation queue is already busy; rerun on a clean dev world")));
        }
        BlockPos anchor = alignAnchor(requestedOrigin);
        touchChunks(level, anchor, 3);
        switch (scenario) {
            case "gravity" -> results.add(runGravity(level, anchor));
            case "vines" -> results.add(runVines(level, anchor.offset(0, 0, 16)));
            case "fluids" -> results.add(runFluids(level, anchor.offset(0, 0, 32)));
            case "treeplacer" -> results.add(runTreePlacer(level, anchor.offset(0, 0, 48)));
            case "midgard" -> results.add(runMidgard(level, anchor.offset(0, 0, 64)));
            case "all" -> {
                results.add(runGravity(level, anchor));
                results.add(runVines(level, anchor.offset(0, 0, 16)));
                results.add(runFluids(level, anchor.offset(0, 0, 32)));
                results.add(runTreePlacer(level, anchor.offset(0, 0, 48)));
            }
            default -> results.add(new ScenarioResult(
                scenario,
                false,
                0,
                List.of("unknown scenario; expected gravity, vines, fluids, treeplacer or all")));
        }
        return results;
    }

    private static ScenarioResult runGravity(ServerLevel level, BlockPos anchor) {
        clearVolume(level, anchor.offset(-4, -3, -4), anchor.offset(24, 8, 6));
        touchChunks(level, anchor, 2);

        LinkedHashMap<ChunkCoord, ChunkShadowMutationPlan.Builder> plans = new LinkedHashMap<>();
        long txId = txId(level, anchor, "gravity");
        int y = anchor.getY();
        BlockPos sandPos = new BlockPos(anchor.getX() + 1, y + 1, anchor.getZ());
        BlockPos gravelPos = new BlockPos(anchor.getX(), y + 1, anchor.getZ() + 1);
        BlockPos powderPos = new BlockPos(anchor.getX() + 2, y + 1, anchor.getZ() + 2);

        add(plans, level, txId, sandPos.below(), Blocks.STONE.defaultBlockState());
        add(plans, level, txId, gravelPos.below(), Blocks.STONE.defaultBlockState());
        add(plans, level, txId, powderPos.below(), Blocks.STONE.defaultBlockState());
        add(plans, level, txId, sandPos, Blocks.SAND.defaultBlockState());
        add(plans, level, txId, gravelPos, Blocks.GRAVEL.defaultBlockState());
        add(plans, level, txId, powderPos, Blocks.WHITE_CONCRETE_POWDER.defaultBlockState());

        // Force multiple bounded apply cycles instead of one giant instant pass.
        for (int dx = -4; dx <= 23; dx++) {
            for (int dz = 3; dz <= 5; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    add(plans, level, txId, anchor.offset(dx, dy, dz), Blocks.GLASS.defaultBlockState());
                }
            }
        }

        enqueueAll(plans);
        int cycles = ShadowBlockMutationApplier.forceDrainForDebug(16);
        List<String> details = new ArrayList<>();
        boolean success = true;
        success &= expectState(level, sandPos, Blocks.SAND.defaultBlockState(), details, "sand");
        success &= expectState(level, gravelPos, Blocks.GRAVEL.defaultBlockState(), details, "gravel");
        success &= expectState(level, powderPos, Blocks.WHITE_CONCRETE_POWDER.defaultBlockState(), details, "concrete_powder");
        ShadowBlockMutationApplier.RuntimeSnapshot snapshot = ShadowBlockMutationApplier.runtimeSnapshot();
        int fallingEntities = countFallingBlocks(level, anchor);
        success &= evaluateGravitySettlement(cycles, snapshot, fallingEntities, details);
        return new ScenarioResult("gravity", success, cycles, details);
    }

    static boolean evaluateGravitySettlement(int cycles,
                                             ShadowBlockMutationApplier.RuntimeSnapshot snapshot,
                                             int fallingEntities,
                                             List<String> details) {
        boolean success = true;
        if (snapshot.hasPendingWork()) {
            success = false;
            details.add("shadow queue still pending after gravity scenario");
        }
        if (snapshot.failedTransactions() > 0L) {
            success = false;
            details.add("failedTransactions=" + snapshot.failedTransactions());
        }
        if (snapshot.applyFailures() > 0L) {
            success = false;
            details.add("applyFailures=" + snapshot.applyFailures());
        }
        if (fallingEntities > 0) {
            success = false;
            details.add("fallingEntitiesStillActive=" + fallingEntities);
        }
        if (cycles <= 1) {
            details.add("gravity settled in a single eligible drain cycle (cycles=" + cycles + ")");
        } else {
            details.add("gravity required bounded multi-cycle drain (cycles=" + cycles + ")");
        }
        return success;
    }

    private static ScenarioResult runVines(ServerLevel level, BlockPos anchor) {
        clearVolume(level, anchor.offset(-2, -1, -2), anchor.offset(18, 12, 6));
        touchChunks(level, anchor, 2);

        LinkedHashMap<ChunkCoord, ChunkShadowMutationPlan.Builder> plans = new LinkedHashMap<>();
        long txId = txId(level, anchor, "vines");
        int y = anchor.getY();

        BlockPos vineSupportEast = new BlockPos(anchor.getX() + 1, y + 2, anchor.getZ());
        BlockPos vineSupportSouth = new BlockPos(anchor.getX(), y + 2, anchor.getZ() + 1);
        BlockPos vinePos = new BlockPos(anchor.getX(), y + 2, anchor.getZ());
        BlockState vineState = Blocks.VINE.defaultBlockState()
            .setValue(VineBlock.EAST, true)
            .setValue(VineBlock.SOUTH, true);

        BlockPos rootsCeiling = new BlockPos(anchor.getX() + 3, y + 6, anchor.getZ());
        BlockPos rootsPos = rootsCeiling.below();

        BlockPos chainCeiling = new BlockPos(anchor.getX() + 5, y + 7, anchor.getZ());
        BlockPos chainTop = chainCeiling.below();
        BlockPos chainBottom = chainTop.below();

        BlockPos caveSupport = new BlockPos(anchor.getX() + 7, y + 8, anchor.getZ());
        BlockPos cavePlant = caveSupport.below();
        BlockPos caveHead = cavePlant.below();
        BlockState caveHeadState = Blocks.CAVE_VINES.defaultBlockState().setValue(CaveVines.BERRIES, true);

        BlockPos weepingSupport = new BlockPos(anchor.getX() + 9, y + 8, anchor.getZ());
        BlockPos weepingPlant = weepingSupport.below();
        BlockPos weepingHead = weepingPlant.below();
        BlockState weepingHeadState = Blocks.WEEPING_VINES.defaultBlockState().setValue(GrowingPlantHeadBlock.AGE, 17);

        BlockPos twistingBase = new BlockPos(anchor.getX() + 11, y, anchor.getZ());
        BlockPos twistingPlant = twistingBase.above();
        BlockPos twistingHead = twistingPlant.above();
        BlockState twistingHeadState = Blocks.TWISTING_VINES.defaultBlockState().setValue(GrowingPlantHeadBlock.AGE, 19);

        add(plans, level, txId, vineSupportEast, Blocks.STONE.defaultBlockState());
        add(plans, level, txId, vineSupportSouth, Blocks.STONE.defaultBlockState());
        add(plans, level, txId, vinePos, vineState);

        add(plans, level, txId, rootsCeiling, Blocks.STONE.defaultBlockState());
        add(plans, level, txId, rootsPos, Blocks.HANGING_ROOTS.defaultBlockState());

        add(plans, level, txId, chainCeiling, Blocks.STONE.defaultBlockState());
        add(plans, level, txId, chainTop, Blocks.CHAIN.defaultBlockState());
        add(plans, level, txId, chainBottom, Blocks.CHAIN.defaultBlockState());

        add(plans, level, txId, caveSupport, Blocks.MOSS_BLOCK.defaultBlockState());
        add(plans, level, txId, cavePlant, Blocks.CAVE_VINES_PLANT.defaultBlockState());
        add(plans, level, txId, caveHead, caveHeadState);

        add(plans, level, txId, weepingSupport, Blocks.NETHER_WART_BLOCK.defaultBlockState());
        add(plans, level, txId, weepingPlant, Blocks.WEEPING_VINES_PLANT.defaultBlockState());
        add(plans, level, txId, weepingHead, weepingHeadState);

        add(plans, level, txId, twistingBase, Blocks.WARPED_NYLIUM.defaultBlockState());
        add(plans, level, txId, twistingPlant, Blocks.TWISTING_VINES_PLANT.defaultBlockState());
        add(plans, level, txId, twistingHead, twistingHeadState);

        enqueueAll(plans);
        int cycles = ShadowBlockMutationApplier.forceDrainForDebug(16);
        List<String> details = new ArrayList<>();
        boolean success = true;
        success &= expectState(level, vinePos, vineState, details, "vine_multiface");
        success &= expectState(level, rootsPos, Blocks.HANGING_ROOTS.defaultBlockState(), details, "hanging_roots");
        success &= expectState(level, chainTop, Blocks.CHAIN.defaultBlockState(), details, "chain_top");
        success &= expectState(level, chainBottom, Blocks.CHAIN.defaultBlockState(), details, "chain_bottom");
        success &= expectState(level, cavePlant, Blocks.CAVE_VINES_PLANT.defaultBlockState(), details, "cave_vines_plant");
        success &= expectState(level, caveHead, caveHeadState, details, "cave_vines_head");
        success &= expectState(level, weepingPlant, Blocks.WEEPING_VINES_PLANT.defaultBlockState(), details, "weeping_vines_plant");
        success &= expectState(level, weepingHead, weepingHeadState, details, "weeping_vines_head");
        success &= expectState(level, twistingPlant, Blocks.TWISTING_VINES_PLANT.defaultBlockState(), details, "twisting_vines_plant");
        success &= expectState(level, twistingHead, twistingHeadState, details, "twisting_vines_head");
        if (ShadowBlockMutationApplier.hasPendingWork()) {
            success = false;
            details.add("shadow queue still pending after vine scenario");
        }
        return new ScenarioResult("vines", success, cycles, details);
    }

    private static int countFallingBlocks(ServerLevel level, BlockPos anchor) {
        AABB box = new AABB(anchor.offset(-2, -2, -2), anchor.offset(6, 6, 6));
        return level.getEntitiesOfClass(FallingBlockEntity.class, box).size();
    }

    private static ScenarioResult runFluids(ServerLevel level, BlockPos anchor) {
        clearVolume(level, anchor.offset(-2, -2, -2), anchor.offset(22, 10, 8));
        touchChunks(level, anchor, 2);

        LinkedHashMap<ChunkCoord, ChunkShadowMutationPlan.Builder> plans = new LinkedHashMap<>();
        long txId = txId(level, anchor, "fluids");
        int y = anchor.getY();

        buildCage(plans, level, txId, anchor.offset(0, 0, 0), 3, 4, 3);
        buildCage(plans, level, txId, anchor.offset(5, 0, 0), 3, 4, 3);
        buildCage(plans, level, txId, anchor.offset(10, 0, 0), 3, 5, 3);
        buildCage(plans, level, txId, anchor.offset(15, 0, 0), 3, 5, 3);

        BlockPos waterSource = anchor.offset(1, 2, 1);
        BlockPos flowingWater = anchor.offset(6, 2, 1);
        BlockPos lavaSource = anchor.offset(11, 2, 1);
        BlockPos flowingLava = anchor.offset(16, 2, 1);
        BlockPos waterloggedStairsPos = anchor.offset(20, 1, 1);
        BlockPos waterloggedContainerFloor = waterloggedStairsPos.below();
        BlockPos waterColumnA = anchor.offset(2, 1, 4);
        BlockPos waterColumnB = waterColumnA.above();
        BlockPos waterColumnC = waterColumnB.above();

        BlockState flowingWaterState = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3);
        BlockState flowingLavaState = Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 5);
        BlockState waterloggedStairs = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(StairBlock.FACING, Direction.NORTH)
            .setValue(StairBlock.HALF, Half.BOTTOM)
            .setValue(BlockStateProperties.WATERLOGGED, true);

        add(plans, level, txId, waterSource, Blocks.WATER.defaultBlockState());
        add(plans, level, txId, flowingWater, flowingWaterState);
        add(plans, level, txId, lavaSource, Blocks.LAVA.defaultBlockState());
        add(plans, level, txId, flowingLava, flowingLavaState);
        add(plans, level, txId, waterloggedContainerFloor, Blocks.STONE.defaultBlockState());
        add(plans, level, txId, waterloggedStairsPos, waterloggedStairs);
        add(plans, level, txId, waterColumnA, Blocks.WATER.defaultBlockState());
        add(plans, level, txId, waterColumnB, Blocks.WATER.defaultBlockState());
        add(plans, level, txId, waterColumnC, Blocks.WATER.defaultBlockState());

        enqueueAll(plans);
        int cycles = ShadowBlockMutationApplier.forceDrainForDebug(16);
        List<String> details = new ArrayList<>();
        boolean success = true;
        success &= expectState(level, waterSource, Blocks.WATER.defaultBlockState(), details, "water_source");
        success &= expectState(level, flowingWater, flowingWaterState, details, "water_flowing");
        success &= expectState(level, lavaSource, Blocks.LAVA.defaultBlockState(), details, "lava_source");
        success &= expectState(level, flowingLava, flowingLavaState, details, "lava_flowing");
        success &= expectState(level, waterloggedStairsPos, waterloggedStairs, details, "waterlogged_stairs");
        success &= expectFluid(level, waterloggedStairsPos, true, details, "waterlogged_stairs_fluid");
        success &= expectState(level, waterColumnA, Blocks.WATER.defaultBlockState(), details, "water_column_a");
        success &= expectState(level, waterColumnB, Blocks.WATER.defaultBlockState(), details, "water_column_b");
        success &= expectState(level, waterColumnC, Blocks.WATER.defaultBlockState(), details, "water_column_c");
        if (ShadowBlockMutationApplier.hasPendingWork()) {
            success = false;
            details.add("shadow queue still pending after fluid scenario");
        }
        return new ScenarioResult("fluids", success, cycles, details);
    }

    private static ScenarioResult runTreePlacer(ServerLevel level, BlockPos anchor) {
        TreeCompatTracker.refreshRuntimeAvailability();
        clearVolume(level, anchor.offset(-2, -1, -2), anchor.offset(14, 18, 14));
        touchChunks(level, anchor, 2);

        List<String> details = new ArrayList<>();
        Method growTree = resolveTreePlacerGrowTree();
        if (growTree == null) {
            return new ScenarioResult("treeplacer", false, 0, List.of("TreePlacer growTree entrypoint unavailable in active runtime"));
        }
        BlockPos saplingPos = anchor.offset(0, 1, 0);
        level.setBlock(anchor, Blocks.DIRT.defaultBlockState(), DEBUG_FLAGS);
        level.setBlock(saplingPos, Blocks.OAK_SAPLING.defaultBlockState(), DEBUG_FLAGS);
        try {
            Object raw = growTree.invoke(null,
                level,
                level.getChunkSource().getGenerator(),
                saplingPos,
                level.getBlockState(saplingPos),
                RandomSource.create(level.getSeed() ^ saplingPos.asLong()),
                false);
            if (!(raw instanceof Integer result) || result <= 0) {
                return new ScenarioResult("treeplacer", false, 0, List.of("TreePlacer growTree returned " + raw));
            }
        } catch (Throwable t) {
            return new ScenarioResult("treeplacer", false, 0, List.of("TreePlacer growTree invoke failed: " + t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
        int cycles = ShadowBlockMutationApplier.forceDrainForDebug(16);

        boolean hasLog = false;
        boolean hasLeaves = false;
        for (BlockPos pos : BlockPos.betweenClosed(saplingPos.offset(-4, 0, -4), saplingPos.offset(4, 12, 4))) {
            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.LOGS)) {
                hasLog = true;
            }
            if (state.is(BlockTags.LEAVES)) {
                hasLeaves = true;
            }
        }
        if (!hasLog) {
            details.add("no logs found after TreePlacer scenario");
        }
        if (!hasLeaves) {
            details.add("no leaves found after TreePlacer scenario");
        }
        details.add("validated via real TreePlacer.growTree boundary");
        if (ShadowBlockMutationApplier.hasPendingWork()) {
            details.add("shadow queue still pending after TreePlacer scenario");
        }
        return new ScenarioResult(
            "treeplacer",
            hasLog && hasLeaves && !ShadowBlockMutationApplier.hasPendingWork(),
            cycles,
            details);
    }

    private static ScenarioResult runMidgard(ServerLevel level, BlockPos anchor) {
        TreeCompatTracker.refreshRuntimeAvailability();
        List<String> details = new ArrayList<>();
        boolean featurePresent = classPresent("dev.corgitaco.ohthetreesyoullgrow.world.level.levelgen.feature.TreeFromStructureNBTFeature");
        boolean featureV2Present = classPresent("dev.corgitaco.ohthetreesyoullgrow.world.level.levelgen.feature.TreeFromStructureNBTFeatureV2");
        boolean treePlacerPresent = classPresent("com.outrightwings.growth.TreePlacer");
        if (!featurePresent && !featureV2Present) {
            details.add("Midgard/OHTTYG structure feature unavailable in active runtime");
            return new ScenarioResult("midgard", false, 0, details);
        }
        if (!treePlacerPresent) {
            details.add("TreePlacer dependency unavailable in active runtime");
            return new ScenarioResult("midgard", false, 0, details);
        }

            ResourceLocation featureId = ResourceLocations.of("midgard", "aspen/huge_aspen_tree_1");
        ConfiguredFeature<?, ?> feature;
        try {
            Registry<ConfiguredFeature<?, ?>> configuredFeatures = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
            feature = configuredFeatures.get(featureId);
        } catch (Throwable t) {
            return new ScenarioResult("midgard", false, 0, List.of("failed to resolve Midgard configured feature: " + t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
        if (feature == null) {
            return new ScenarioResult("midgard", false, 0, List.of("configured feature missing: " + featureId));
        }

        boolean oldSeamFix = ConfigManager.CITY_BLEND_TREE_SEAM_FIX;
        try {
            // This is a test-only enablement: use the genuine capture policy to
            // locate a city seam, then restore production configuration.
            ConfigManager.CITY_BLEND_TREE_SEAM_FIX = true;
            BlockPos origin = findCaptureOrigin(level, anchor);
            if (origin == null) {
                return new ScenarioResult("midgard", false, 0, List.of("no Lost Cities tree-capture seam found near harness anchor"));
            }

            clearVolume(level, origin.offset(-24, -2, -24), origin.offset(24, 64, 24));
            touchChunks(level, origin, 3);
            level.setBlock(origin.below(), Blocks.DIRT.defaultBlockState(), DEBUG_FLAGS);

            long capturesBefore = TreeCompatTracker.captureCount(DeferredTreeCaptureContext.CaptureSource.MIDGARD_STRUCTURE);
            long appliedBefore = TreeCompatTracker.appliedCount(DeferredTreeCaptureContext.CaptureSource.MIDGARD_STRUCTURE);
            MidgardTreeCaptureHooks.resetTestTrace();
            boolean placed = feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(level.getSeed() ^ origin.asLong()), origin);
            DeferredTreeQueue.promoteReadyLoaded(level, 64);
            int replayCycles = DeferredTreeEventHandler.forceReplayReadyForDebug(level.getServer(), 64, 1_000_000);
            // Tree replay goes through the normal shadow applier. Drain it before
            // checking blocks because a replay count alone is not completion.
            int applyCycles = ShadowBlockMutationApplier.forceDrainForDebug(64);
            int cycles = replayCycles + applyCycles;
            long captures = TreeCompatTracker.captureCount(DeferredTreeCaptureContext.CaptureSource.MIDGARD_STRUCTURE) - capturesBefore;
            long applied = TreeCompatTracker.appliedCount(DeferredTreeCaptureContext.CaptureSource.MIDGARD_STRUCTURE) - appliedBefore;
            int midgardBlocks = countMidgardTreeBlocks(level, origin, 24, 64);
            int pending = DeferredTreeQueue.pendingCount(level);
            int ready = DeferredTreeQueue.readyCount(level);
            details.add("feature=" + featureId + " origin=" + origin + " placed=" + placed);
            details.add("captureTrace=" + MidgardTreeCaptureHooks.testTrace());
            details.add("captureDelta=" + captures + " appliedDelta=" + applied + " replayCycles=" + replayCycles + " shadowApplyCycles=" + applyCycles + " midgardBlocks=" + midgardBlocks + " pending=" + pending + " ready=" + ready);
            return new ScenarioResult("midgard", placed && captures > 0 && applied > 0 && midgardBlocks > 0 && pending == 0 && ready == 0, cycles, details);
        } catch (Throwable t) {
            return new ScenarioResult("midgard", false, 0, List.of("Midgard configured feature placement failed: " + t.getClass().getSimpleName() + ": " + t.getMessage()));
        } finally {
            ConfigManager.CITY_BLEND_TREE_SEAM_FIX = oldSeamFix;
        }
    }

    private static BlockPos findCaptureOrigin(ServerLevel level, BlockPos anchor) {
        int y = Math.max(level.getMinBuildHeight() + 4, anchor.getY());
        // City seams are sparse. This runs only in the explicit dev harness,
        // so search a meaningful city-sized window one chunk at a time rather
        // than incorrectly treating a near-spawn miss as incompatible.
        for (int radius = 0; radius <= 1024; radius += 16) {
            for (int dx = -radius; dx <= radius; dx += 16) {
                for (int dz = -radius; dz <= radius; dz += 16) {
                    if (radius != 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    BlockPos candidate = new BlockPos(anchor.getX() + dx, y, anchor.getZ() + dz);
                    if (TreeCapturePolicy.shouldCaptureAt(level, candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static int countMidgardTreeBlocks(ServerLevel level, BlockPos origin, int horizontalRadius, int height) {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-horizontalRadius, 0, -horizontalRadius), origin.offset(horizontalRadius, height, horizontalRadius))) {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock());
            if (id != null && "midgard".equals(id.getNamespace())
                && (id.getPath().contains("aspen") || id.getPath().contains("branch") || id.getPath().contains("trunk"))) {
                count++;
            }
        }
        return count;
    }

    private static Method resolveTreePlacerGrowTree() {
        try {
            Class<?> treePlacer = Class.forName("com.outrightwings.growth.TreePlacer", false, ShadowMutationRuntimeHarness.class.getClassLoader());
            return treePlacer.getDeclaredMethod(
                "growTree",
                ServerLevel.class,
                net.minecraft.world.level.chunk.ChunkGenerator.class,
                BlockPos.class,
                BlockState.class,
                RandomSource.class,
                boolean.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean classPresent(String className) {
        try {
            Class.forName(className, false, ShadowMutationRuntimeHarness.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void buildCage(LinkedHashMap<ChunkCoord, ChunkShadowMutationPlan.Builder> plans,
                                  ServerLevel level,
                                  long txId,
                                  BlockPos origin,
                                  int width,
                                  int height,
                                  int depth) {
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                add(plans, level, txId, origin.offset(dx, 0, dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy < height; dy++) {
                    boolean wall = dx == 0 || dz == 0 || dx == width - 1 || dz == depth - 1;
                    if (wall) {
                        add(plans, level, txId, origin.offset(dx, dy, dz), Blocks.GLASS.defaultBlockState());
                    }
                }
            }
        }
    }

    private static void enqueueAll(LinkedHashMap<ChunkCoord, ChunkShadowMutationPlan.Builder> plans) {
        for (ChunkShadowMutationPlan.Builder builder : plans.values()) {
            ShadowBlockMutationApplier.enqueue(builder.build());
        }
    }

    private static void add(LinkedHashMap<ChunkCoord, ChunkShadowMutationPlan.Builder> plans,
                            ServerLevel level,
                            long txId,
                            BlockPos pos,
                            BlockState state) {
        ChunkCoord chunk = new ChunkCoord(level.dimension(), pos.getX() >> 4, pos.getZ() >> 4);
        plans.computeIfAbsent(chunk, key -> ChunkShadowMutationPlan.builder(level, key))
            .transaction(txId, System.currentTimeMillis(), ChunkShadowMutationPlan.MutationKind.GENERIC)
            .add(pos, state, DEBUG_FLAGS, false);
    }

    private static boolean expectState(ServerLevel level,
                                       BlockPos pos,
                                       BlockState expected,
                                       List<String> details,
                                       String label) {
        BlockState actual = level.getBlockState(pos);
        if (!actual.equals(expected)) {
            details.add(label + " mismatch expected=" + expected + " actual=" + actual + " pos=" + pos);
            return false;
        }
        details.add(label + " ok@" + pos);
        return true;
    }

    private static boolean expectFluid(ServerLevel level,
                                       BlockPos pos,
                                       boolean expectedPresent,
                                       List<String> details,
                                       String label) {
        boolean present = !level.getFluidState(pos).isEmpty();
        if (present != expectedPresent) {
            details.add(label + " mismatch expectedFluid=" + expectedPresent + " actualFluid=" + present + " pos=" + pos);
            return false;
        }
        details.add(label + " ok@" + pos);
        return true;
    }

    private static void clearVolume(ServerLevel level, BlockPos min, BlockPos max) {
        int minX = Math.min(min.getX(), max.getX());
        int minY = Math.min(min.getY(), max.getY());
        int minZ = Math.min(min.getZ(), max.getZ());
        int maxX = Math.max(min.getX(), max.getX());
        int maxY = Math.max(min.getY(), max.getY());
        int maxZ = Math.max(min.getZ(), max.getZ());
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    level.removeBlockEntity(cursor);
                    level.setBlock(cursor, Blocks.AIR.defaultBlockState(), DEBUG_FLAGS);
                }
            }
        }
    }

    private static void touchChunks(ServerLevel level, BlockPos anchor, int radius) {
        int cx = anchor.getX() >> 4;
        int cz = anchor.getZ() >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                level.getChunk(cx + dx, cz + dz);
            }
        }
    }

    private static BlockPos alignAnchor(BlockPos origin) {
        int x = ((origin.getX() >> 4) << 4) + 15;
        int z = ((origin.getZ() >> 4) << 4) + 15;
        int y = Math.max(80, origin.getY());
        return new BlockPos(x, y, z);
    }

    private static long txId(ServerLevel level, BlockPos origin, String scenario) {
        long hash = level.getSeed() ^ origin.asLong();
        for (int i = 0; i < scenario.length(); i++) {
            hash = (hash * 31L) ^ scenario.charAt(i);
        }
        return hash == 0L ? 1L : hash;
    }
}
