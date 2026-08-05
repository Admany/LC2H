package org.admany.lc2h.worldgen.kernel;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import mcjty.lostcities.worldgen.lost.Railway;
import org.admany.lc2h.dev.diagnostics.Lc2hTimingRegistry;
import org.admany.lc2h.mixin.accessor.lostcities.MultiChunkInvoker;
import org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner;
import org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner;
import org.admany.lc2h.worldgen.noise.CheapChunkNoiseField;

import java.util.EnumSet;

public final class JavaScalarLostCityKernel implements LostCityCpuKernel {

    public static final JavaScalarLostCityKernel INSTANCE = new JavaScalarLostCityKernel();

    private static final KernelCapabilities CAPABILITIES = new KernelCapabilities(
        "java-scalar",
        "v4-java-scalar-1",
        false,
        false,
        EnumSet.of(
            LostCityKernelStage.CLASSIFY_CHUNKS,
            LostCityKernelStage.PLAN_MULTICHUNK,
            LostCityKernelStage.PLAN_ROADS,
            LostCityKernelStage.PLAN_RAILWAYS,
            LostCityKernelStage.PLAN_BUILDINGS,
            LostCityKernelStage.PLAN_TERRAIN,
            LostCityKernelStage.PLAN_TERRAIN_CORRECTION,
            LostCityKernelStage.PLAN_CITY_LAYOUT
        )
    );

    private JavaScalarLostCityKernel() {
    }

    @Override
    public KernelCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public void warmRoleLookups(LostCityKernelInput input) {
        long startNs = System.nanoTime();
        try {
            if (input == null) {
                return;
            }
            IDimensionInfo provider = input.provider();
            ChunkCoord multiCoord = input.coord();
            int areaSize = input.areaSize();
            if (provider == null || multiCoord == null || areaSize <= 0) {
                return;
            }

            LostCityProfile profile;
            try {
                profile = provider.getProfile();
            } catch (Throwable ignored) {
                return;
            }

            ChunkCoord topLeft = topLeft(multiCoord, areaSize);
            try {
                City.isChunkOccupied(provider, topLeft);
            } catch (Throwable ignored) {
            }

            int baseX = topLeft.chunkX();
            int baseZ = topLeft.chunkZ();
            for (int x = 0; x < areaSize; x++) {
                for (int z = 0; z < areaSize; z++) {
                    warmChunkLookups(provider, profile, new ChunkCoord(topLeft.dimension(), baseX + x, baseZ + z));
                }
            }
        } finally {
            Lc2hTimingRegistry.record("kernel.role_warmup", System.nanoTime() - startNs);
        }
    }

    @Override
    public LostCityMultiChunkPlan planMultiChunk(LostCityKernelInput input) {
        long startNs = System.nanoTime();
        try {
            return AsyncMultiChunkPlanner.runInternal(() -> {
                MultiChunk multiChunk = new MultiChunk(input.coord(), input.areaSize());
                MultiChunk result = ((MultiChunkInvoker) multiChunk).lc2h$calculateBuildings(input.provider());
                return new LostCityMultiChunkPlan(input.coord(), input.areaSize(), result, input.signature());
            });
        } finally {
            Lc2hTimingRegistry.record("kernel.multichunk_plan", System.nanoTime() - startNs);
        }
    }

    @Override
    public LostCityStageResult planBuildingInfo(LostCityKernelInput input) {
        long startNs = System.nanoTime();
        try {
            Object buildingInfo = AsyncBuildingInfoPlanner.runInternal(() -> {
                AsyncMultiChunkPlanner.ensureScheduled(input.provider(), input.coord());
                AsyncMultiChunkPlanner.tryConsumePrepared(input.provider(), input.coord());
                return BuildingInfo.getBuildingInfo(input.coord(), input.provider());
            });
            return LostCityStageResult.completed(
                LostCityKernelStage.PLAN_BUILDING_INFO,
                input.coord(),
                new LostCityStagePayloads.BuildingInfoPlan(input.coord(), buildingInfo),
                input.signature()
            );
        } finally {
            Lc2hTimingRegistry.record("kernel.building_info", System.nanoTime() - startNs);
        }
    }

    @Override
    public LostCityStageResult planTerrainFeatures(LostCityKernelInput input) {
        long startNs = System.nanoTime();
        try {
            TerrainFeatureSummary summary = TerrainFeatureSummary.compute(input.coord());
            return LostCityStageResult.completed(
                LostCityKernelStage.PLAN_TERRAIN,
                input.coord(),
                LostCityStagePayloads.TerrainFeatureIntentPlan.from(input.coord(), summary),
                input.signature()
            );
        } finally {
            Lc2hTimingRegistry.record("kernel.terrain_features", System.nanoTime() - startNs);
        }
    }

    @Override
    public LostCityStageResult planTerrainCorrections(LostCityKernelInput input) {
        long startNs = System.nanoTime();
        try {
            TerrainCorrectionSummary summary = TerrainCorrectionSummary.compute(input.coord());
            return LostCityStageResult.completed(
                LostCityKernelStage.PLAN_TERRAIN_CORRECTION,
                input.coord(),
                LostCityStagePayloads.TerrainCorrectionIntentPlan.from(input.coord(), summary),
                input.signature()
            );
        } finally {
            Lc2hTimingRegistry.record("kernel.terrain_corrections", System.nanoTime() - startNs);
        }
    }

    @Override
    public LostCityStageResult planCityLayout(LostCityKernelInput input) {
        long startNs = System.nanoTime();
        try {
            CityLayoutSummary summary = new CityLayoutSummary(input.coord().chunkX(), input.coord().chunkZ());
            return LostCityStageResult.completed(
                LostCityKernelStage.PLAN_CITY_LAYOUT,
                input.coord(),
                LostCityStagePayloads.CityLayoutPlan.from(input.coord(), summary),
                input.signature()
            );
        } finally {
            Lc2hTimingRegistry.record("kernel.city_layout", System.nanoTime() - startNs);
        }
    }

    private static ChunkCoord topLeft(ChunkCoord multiCoord, int areaSize) {
        return new ChunkCoord(multiCoord.dimension(), multiCoord.chunkX() * areaSize, multiCoord.chunkZ() * areaSize);
    }

    private static void warmChunkLookups(IDimensionInfo provider, LostCityProfile profile, ChunkCoord coord) {
        boolean cityRaw = false;
        try {
            cityRaw = BuildingInfo.isCityRaw(coord, provider, profile);
        } catch (Throwable ignored) {
        }
        if (!cityRaw) {
            return;
        }
        try {
            City.getCityStyle(coord, provider, profile);
        } catch (Throwable ignored) {
        }
        try {
            Railway.getRailChunkType(coord, provider, profile);
        } catch (Throwable ignored) {
        }
        try {
            BuildingInfo.hasHighway(coord, provider, profile);
        } catch (Throwable ignored) {
        }
    }

    public record TerrainFeatureSummary(int heightChecksum, int excavationChecksum, int ruinCount) {
        static TerrainFeatureSummary compute(ChunkCoord coord) {
            double[] noiseField = CheapChunkNoiseField.getOrCompute(coord);
            int heightChecksum = 0;
            int excavationChecksum = 0;
            int ruinCount = 0;
            for (int x = 0; x < 16; x++) {
                int row = x << 4;
                for (int z = 0; z < 16; z++) {
                    int height = 62 + (int) (noiseField[row | z] * 3);
                    boolean street = (x % 8 == 0) || (z % 8 == 0);
                    if (street) {
                        height -= 2;
                    } else if ((x % 4 == 1) && (z % 4 == 1)) {
                        height += 1;
                        excavationChecksum += 3;
                    }
                    if ((x + z) % 16 == 0) {
                        ruinCount++;
                    }
                    heightChecksum = (heightChecksum * 31) ^ height;
                }
            }
            return new TerrainFeatureSummary(heightChecksum, excavationChecksum, ruinCount);
        }
    }

    public record TerrainCorrectionSummary(int correctionChecksum) {
        static TerrainCorrectionSummary compute(ChunkCoord coord) {
            return new TerrainCorrectionSummary((coord.chunkX() * 73428767) ^ (coord.chunkZ() * 912931));
        }
    }

    public record CityLayoutSummary(int chunkX, int chunkZ) {
    }
}
