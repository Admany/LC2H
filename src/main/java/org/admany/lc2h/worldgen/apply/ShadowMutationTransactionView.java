package org.admany.lc2h.worldgen.apply;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ShadowMutationTransactionView {

    private final ServerLevel level;
    private final Map<Long, BlockState> visibleStates;
    private final Set<Long> plannedPositions;
    private final LevelReader reader;

    private ShadowMutationTransactionView(ServerLevel level, Map<Long, BlockState> visibleStates, Set<Long> plannedPositions) {
        this.level = level;
        this.visibleStates = visibleStates;
        this.plannedPositions = plannedPositions;
        this.reader = new TransactionLevelReader(level, visibleStates);
    }

    static ShadowMutationTransactionView of(ServerLevel level, Collection<ChunkShadowMutationPlan> plans) {
        if (level == null || plans == null || plans.isEmpty()) {
            return null;
        }
        Set<Long> plannedPositions = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (ChunkShadowMutationPlan plan : plans) {
            if (plan == null || plan.chunk() == null || plan.entries() == null) {
                continue;
            }
            for (ChunkShadowMutationPlan.Entry entry : plan.entries()) {
                if (entry == null || entry.state() == null) {
                    continue;
                }
                ChunkShadowMutationPlan.unpack(plan.chunk(), entry.packedPos(), cursor);
                plannedPositions.add(cursor.asLong());
            }
        }
        return plannedPositions.isEmpty() ? null : new ShadowMutationTransactionView(level, new HashMap<>(), plannedPositions);
    }

    LevelReader reader() {
        return reader;
    }

    public static LevelReader loadedOnlyReader(ServerLevel level) {
        return new TransactionLevelReader(level, Map.of());
    }

    void recordApplied(BlockPos pos, BlockState state) {
        if (pos == null || state == null) {
            return;
        }
        visibleStates.put(pos.asLong(), state);
    }

    BlockState getVisibleOrActual(BlockPos pos) {
        BlockState visible = pos == null ? null : visibleStates.get(pos.asLong());
        return visible != null ? visible : getLoadedActualState(pos);
    }

    boolean hasPlannedSupport(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        long key = pos.asLong();
        return visibleStates.containsKey(key) || plannedPositions.contains(key);
    }

    private BlockState getLoadedActualState(BlockPos pos) {
        if (pos == null) {
            return Blocks.AIR.defaultBlockState();
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
    }

    private static final class TransactionLevelReader implements LevelReader {
        private final ServerLevel level;
        private final Map<Long, BlockState> visibleStates;

        private TransactionLevelReader(ServerLevel level, Map<Long, BlockState> visibleStates) {
            this.level = level;
            this.visibleStates = visibleStates;
        }

        private BlockState getVisibleOrActual(BlockPos pos) {
            BlockState visible = pos == null ? null : visibleStates.get(pos.asLong());
            if (visible != null) {
                return visible;
            }
            if (pos == null) {
                return Blocks.AIR.defaultBlockState();
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
            if (pos == null) {
                return null;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? null : chunk.getBlockEntity(pos);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return getVisibleOrActual(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            BlockState visible = pos == null ? null : visibleStates.get(pos.asLong());
            return visible != null ? visible.getFluidState() : getVisibleOrActual(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return level.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return level.getMinBuildHeight();
        }

        @Override
        public float getShade(Direction direction, boolean shaded) {
            return level.getShade(direction, shaded);
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return level.getLightEngine();
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            return level.getBlockTint(pos, colorResolver);
        }

        @Override
        public ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus status, boolean nonnull) {
            return level.getChunkSource().getChunkNow(chunkX, chunkZ);
        }

        @Override
        public boolean hasChunk(int chunkX, int chunkZ) {
            return level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
        }

        @Override
        public int getHeight(Heightmap.Types type, int x, int z) {
            return level.getHeight(type, x, z);
        }

        @Override
        public int getSkyDarken() {
            return level.getSkyDarken();
        }

        @Override
        public BiomeManager getBiomeManager() {
            return level.getBiomeManager();
        }

        @Override
        public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
            return level.getUncachedNoiseBiome(x, y, z);
        }

        @Override
        public boolean isClientSide() {
            return level.isClientSide();
        }

        @Override
        public int getSeaLevel() {
            return level.getSeaLevel();
        }

        @Override
        public DimensionType dimensionType() {
            return level.dimensionType();
        }

        @Override
        public RegistryAccess registryAccess() {
            return level.registryAccess();
        }

        @Override
        public FeatureFlagSet enabledFeatures() {
            return level.enabledFeatures();
        }

        @Override
        public WorldBorder getWorldBorder() {
            return level.getWorldBorder();
        }

        @Override
        public @Nullable BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
            return level.getChunkForCollisions(chunkX, chunkZ);
        }

        @Override
        public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB box) {
            return level.getEntityCollisions(entity, box);
        }
    }
}
