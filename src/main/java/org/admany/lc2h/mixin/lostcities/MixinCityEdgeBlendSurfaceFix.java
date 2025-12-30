package org.admany.lc2h.mixin.lostcities;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.ChunkDriver;
import mcjty.lostcities.worldgen.ChunkHeightmap;
import mcjty.lostcities.worldgen.LostCityTerrainFeature;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = LostCityTerrainFeature.class, remap = false)
public class MixinCityEdgeBlendSurfaceFix {

    @Shadow
    public ChunkDriver driver;

    @Inject(method = "generateBorder", at = @At("TAIL"), remap = false)
    private void lc2h$capBlendSurface(BuildingInfo info, boolean canDoParks, int x, int z, BuildingInfo adjacent, ChunkHeightmap heightmap, CallbackInfo ci) {
        if (!info.profile.isDefault() && !info.profile.isSpheres()) {
            return;
        }

        WorldGenLevel world = info.provider.getWorld();
        if (world == null) {
            return;
        }

        int groundY = info.getCityGroundLevel();
        int minY = world.getMinBuildHeight();
        if (groundY <= minY + 1) {
            return;
        }

        ChunkCoord c = info.coord;
        int wx = (c.chunkX() << 4) + x;
        int wz = (c.chunkZ() << 4) + z;

        int dx = 0;
        int dz = 0;
        if (x == 0) dx = -1;
        else if (x == 15) dx = 1;
        if (z == 0) dz = -1;
        else if (z == 15) dz = 1;

        if (dx == 0 && dz == 0) {
            return;
        }

        int sx = wx + dx;
        int sz = wz + dz;

        // WorldGenRegion throws if we try to query chunks outside its bounds. Corner blending can probe
        // diagonal chunks that are not part of the current region. Fall back to a side neighbor if possible.
        if (!lc2h$hasChunkForBlock(world, sx, sz)) {
            if (dx != 0 && dz != 0) { // corner
                if (lc2h$hasChunkForBlock(world, wx + dx, wz)) {
                    sz = wz;
                    dz = 0;
                } else if (lc2h$hasChunkForBlock(world, wx, wz + dz)) {
                    sx = wx;
                    dx = 0;
                } else {
                    return;
                }
            } else {
                return;
            }
        }

        Pair<BlockState, BlockState> candidate = pickSurfaceCandidate(world, sx, sz, dx, dz, wx, wz);
        if (candidate == null || candidate.getFirst() == null) {
            return;
        }

        // LostCities 7.4.x can sometimes provide a groundY that points at non-solid surface vegetation
        // (tall grass/fern/etc). If we write a solid block there, it replaces the plant.
        // Snap down to the first solid surface block instead.
        int placeY = groundY;
        while (placeY > minY + 1) {
            BlockPos probe = new BlockPos(wx, placeY, wz);
            BlockState probeState = world.getBlockState(probe);
            if (!world.getFluidState(probe).isEmpty()) {
                return;
            }
            if (!probeState.isAir() && !probeState.getCollisionShape(world, probe).isEmpty()) {
                break;
            }
            placeY--;
        }

        BlockPos capPos = new BlockPos(wx, placeY, wz);
        BlockPos above = capPos.above();
        if (world.getFluidState(above).is(Fluids.WATER)) {
            return;
        }

        driver.current(x, placeY, z).block(candidate.getFirst());

        if (candidate.getSecond() != null && placeY - 1 > minY) {
            driver.current(x, placeY - 1, z).block(candidate.getSecond());
        }
    }

    private static Pair<BlockState, BlockState> pickSurfaceCandidate(WorldGenLevel world, int sampleX, int sampleZ, int dx, int dz, int placeX, int placeZ) {
        int stepX = dx == 0 ? 4 : dx * 4;
        int stepZ = dz == 0 ? 4 : dz * 4;

        List<Pair<BlockState, BlockState>> candidates = new ArrayList<>(4);
        addCandidate(world, sampleX, sampleZ, candidates);
        addCandidate(world, sampleX + stepX, sampleZ, candidates);
        addCandidate(world, sampleX, sampleZ + stepZ, candidates);
        addCandidate(world, sampleX + stepX, sampleZ + stepZ, candidates);

        if (candidates.isEmpty()) {
            Pair<BlockState, BlockState> biomeCandidate = biomePaletteCandidate(world, sampleX, sampleZ);
            if (biomeCandidate != null) {
                return biomeCandidate;
            }
            return Pair.of(Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState());
        }

        double noise = getSurfaceNoise(world, placeX, placeZ);
        int idx = Mth.clamp((int) (noise * candidates.size()), 0, candidates.size() - 1);
        return candidates.get(idx);
    }

    private static Pair<BlockState, BlockState> biomePaletteCandidate(WorldGenLevel world, int sampleX, int sampleZ) {
        var biomeHolder = world.getBiome(new BlockPos(sampleX, 0, sampleZ));

        try {
            if (biomeHolder.is(BiomeTags.IS_OCEAN)) {
                return Pair.of(Blocks.SAND.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState());
            }
            if (biomeHolder.is(BiomeTags.IS_RIVER)) {
                BlockState top = Blocks.GRASS_BLOCK.defaultBlockState();
                if (isForbiddenSurface(top)) top = Blocks.STONE.defaultBlockState();
                return Pair.of(top, null);
            }
        } catch (Throwable ignored) {
            // fallback to null
        }

        return null;
    }

    private static void addCandidate(WorldGenLevel world, int x, int z, List<Pair<BlockState, BlockState>> candidates) {
        if (!lc2h$hasChunkForBlock(world, x, z)) {
            return;
        }
        Pair<BlockState, BlockState> candidate = sampleSurfaceCandidate(world, x, z);
        if (candidate == null || candidate.getFirst() == null) {
            return;
        }
        if (isForbiddenSurface(candidate.getFirst())) {
            return;
        }
        for (Pair<BlockState, BlockState> existing : candidates) {
            if (existing.getFirst() == candidate.getFirst()) {
                return;
            }
        }
        candidates.add(candidate);
    }

    private static boolean isForbiddenSurface(BlockState state) {
        return state.is(Blocks.DIRT)
            || state.is(Blocks.COARSE_DIRT)
            || state.is(Blocks.ROOTED_DIRT)
            || state.is(Blocks.GRAVEL);
    }

    private static Pair<BlockState, BlockState> sampleSurfaceCandidate(WorldGenLevel world, int x, int z) {
        int surfaceY;
        try {
            surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        } catch (RuntimeException e) {
            return null;
        }
        int minY = world.getMinBuildHeight();

        BlockState top = null;
        BlockState under = null;

        int startY = surfaceY - 1;
        int bottomY = Math.max(minY + 1, startY - 32);
        for (int y = startY; y >= bottomY; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state;
            try {
                state = world.getBlockState(pos);
            } catch (RuntimeException e) {
                return null;
            }
            if (!world.getFluidState(pos).isEmpty() || state.isAir() || state.getCollisionShape(world, pos).isEmpty()) {
                continue;
            }
            top = state;
            break;
        }

        if (top == null) {
            return null;
        }

        for (int y = startY - 1; y >= bottomY; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state;
            try {
                state = world.getBlockState(pos);
            } catch (RuntimeException e) {
                return Pair.of(top, null);
            }
            if (!world.getFluidState(pos).isEmpty() || state.isAir() || state.getCollisionShape(world, pos).isEmpty()) {
                continue;
            }
            if (state != top) {
                under = state;
                break;
            }
        }

        if (under != null && isForbiddenSurface(under)) {
            under = null;
        }

        return Pair.of(top, under);
    }

    private static boolean lc2h$hasChunkForBlock(WorldGenLevel world, int blockX, int blockZ) {
        try {
            return world.hasChunk(blockX >> 4, blockZ >> 4);
        } catch (Throwable t) {
            return false;
        }
    }

    private static double getSurfaceNoise(WorldGenLevel world, int x, int z) {
        long seed = world.getSeed();
        double noise1 = Mth.sin(x * 0.01f + seed * 0.001f) * 0.5 + 0.5;
        double noise2 = Mth.cos(z * 0.01f + seed * 0.001f) * 0.5 + 0.5;
        double noise3 = Mth.sin((x + z) * 0.007f + seed * 0.001f) * 0.5 + 0.5;

        return (noise1 + noise2 + noise3) / 3.0;
    }

}
