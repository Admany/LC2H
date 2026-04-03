package org.admany.lc2h.worldgen.gpu;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.worldgen.coord.RegionCoord;
import org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner;
import org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner;
import org.admany.lc2h.worldgen.async.planner.AsyncTerrainFeaturePlanner;
import org.admany.lc2h.worldgen.async.planner.AsyncTerrainCorrectionPlanner;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;
import org.admany.lc2h.worldgen.async.generator.AsyncPaletteGenerator;
import org.admany.lc2h.worldgen.async.generator.AsyncDebrisGenerator;
import org.admany.quantified.api.opencl.QuantifiedOpenCL;
import org.admany.quantified.api.vulkan.QuantifiedVulkan;
import org.lwjgl.PointerBuffer;

public record RegionProcessingGPUTask(IDimensionInfo provider, RegionCoord region)
    implements QuantifiedOpenCL.Workload<Boolean>, QuantifiedVulkan.Workload<Boolean> {

    static final int CHUNKS_PER_REGION = 25; // This is a 5x5 region
    static final int SUMMARY_COMPONENTS = 4;
    static final int INPUTS_PER_REGION = CHUNKS_PER_REGION;
    static final int RESULTS_PER_REGION = INPUTS_PER_REGION * SUMMARY_COMPONENTS;
    static final float[] GPU_WARM_SUMMARY = new float[] { 1.0f, 0.0f, 0.0f, 0.0f };
    private static final long CL_MEM_WRITE_ONLY = 1L << 1;
    private static final long CL_MEM_READ_ONLY = 1L << 2;

    public static final long VRAM_BYTES = (long) INPUTS_PER_REGION * Float.BYTES * 2L;
    public static final int PARALLEL_UNITS = INPUTS_PER_REGION;

    public long taskKey() {
        long base = (((long) region.regionX()) & 0xFFFF_FFFFL) << 32;
        base |= ((long) region.regionZ()) & 0xFFFF_FFFFL;
        base ^= region.dimension().location().hashCode();
        return base & 0x7FFF_FFFF_FFFF_FFFFL;
    }

    @Override
    public long estimatedVramBytes() {
        return VRAM_BYTES;
    }

    @Override
    public int estimatedComputeUnits() {
        return PARALLEL_UNITS;
    }

    @Override
    public Boolean execute(QuantifiedOpenCL.Context context) {
        try {
            LC2H.LOGGER.debug("[LC2H] Processing region {} on GPU using Quantified API", region);

            long kernel = context.createKernel("terrain_generation");

            float[] encodedCoords = buildInputCoords(provider, region);
            int dataSize = encodedCoords.length;
            int outputSize = dataSize;

            long bufferInputCoords = context.createBuffer(CL_MEM_READ_ONLY, dataSize * 4L);
            long bufferOutputFeatures = context.createBuffer(CL_MEM_WRITE_ONLY, outputSize * 4L);

            try {
                java.nio.ByteBuffer inputBytes = java.nio.ByteBuffer.allocateDirect(dataSize * 4)
                    .order(java.nio.ByteOrder.nativeOrder());
                java.nio.FloatBuffer inputCoords = inputBytes.asFloatBuffer();
                inputCoords.put(encodedCoords);
                inputCoords.flip();

                context.enqueueWriteBuffer(bufferInputCoords, true, 0, dataSize * 4L, inputBytes);
                context.setKernelArgBuffer(kernel, 0, bufferInputCoords);
                context.setKernelArgBuffer(kernel, 1, bufferOutputFeatures);

                PointerBuffer workSize = PointerBuffer.allocateDirect(1);
                workSize.put(dataSize);
                workSize.flip();

                context.enqueueNDRangeKernel(kernel, 1, workSize);
                context.finish();

                java.nio.ByteBuffer resultBytes = java.nio.ByteBuffer.allocateDirect(outputSize * 4)
                    .order(java.nio.ByteOrder.nativeOrder());
                context.enqueueReadBuffer(bufferOutputFeatures, true, 0, outputSize * 4L, resultBytes);
                resultBytes.rewind();
                float[] results = new float[outputSize];
                resultBytes.asFloatBuffer().get(results);

                processGPUResults(results, region);
                GPUMemoryManager.continuousCleanup();
                AsyncChunkWarmup.recordOpenClBatchProcessed(1);

                LC2H.LOGGER.debug("[LC2H] Successfully processed region {} on OpenCL with {} data points", region, results.length);
                return Boolean.TRUE;

            } finally {
                context.releaseBuffer(bufferInputCoords);
                context.releaseBuffer(bufferOutputFeatures);
                context.releaseKernel(kernel);
            }

        } catch (Exception e) {
            LC2H.LOGGER.error("[LC2H] GPU region processing failed for {}: {}", region, e.getMessage());
            return processRegionOnCPU(provider, region);
        }
    }

    @Override
    public Boolean execute(QuantifiedVulkan.Context context) {
        try {
            float[] encodedCoords = buildInputCoords(provider, region);
            float[] results = context.terrainGeneration(encodedCoords);
            processGPUResults(results, 0, region);
            GPUMemoryManager.continuousCleanup();
            AsyncChunkWarmup.recordVulkanBatchProcessed(1);
            LC2H.LOGGER.debug("[LC2H] Successfully processed region {} on Vulkan device {}", region, context.deviceName());
            return Boolean.TRUE;
        } catch (Exception e) {
            LC2H.LOGGER.error("[LC2H] Vulkan region processing failed for {}: {}", region, e.getMessage());
            return processRegionOnCPU(provider, region);
        }
    }

    public static Boolean processRegionOnCPU(IDimensionInfo provider, RegionCoord region) {
        return AsyncChunkWarmup.runWithinCpuWarmup(() -> {
            AsyncChunkWarmup.recordCpuFallbackProcessed(1);
            LC2H.LOGGER.debug("[LC2H] Processing region {} on CPU (GPU fallback)", region);

            for (int localX = 0; localX < 5; localX++) {
                for (int localZ = 0; localZ < 5; localZ++) {
                    ChunkCoord chunk = region.getChunk(localX, localZ);

                    AsyncMultiChunkPlanner.preSchedule(provider, chunk);
                    AsyncBuildingInfoPlanner.preSchedule(provider, chunk);
                    AsyncTerrainFeaturePlanner.preSchedule(provider, chunk);
                    AsyncPaletteGenerator.preSchedule(provider, chunk);
                    AsyncDebrisGenerator.preSchedule(provider, chunk);
                    AsyncTerrainCorrectionPlanner.preSchedule(provider, chunk);
                }
            }
            return Boolean.TRUE;
        });
    }

    static float[] buildInputCoords(IDimensionInfo provider, RegionCoord region) {
        float[] inputCoords = new float[INPUTS_PER_REGION];
        encodeInputCoords(provider, region, inputCoords, 0);
        return inputCoords;
    }

    static void encodeInputCoords(IDimensionInfo provider, RegionCoord region, float[] target, int offset) {
        for (int i = 0; i < INPUTS_PER_REGION; i++) {
            int localX = i / 5;
            int localZ = i % 5;

            ChunkCoord chunk = region.getChunk(localX, localZ);
            int worldX = chunk.chunkX() * 16 + 8;
            int worldZ = chunk.chunkZ() * 16 + 8;

            target[offset + i] = worldX + worldZ * 10000.0f + provider.getSeed() * 0.000001f;
        }
    }

    static void processGPUResults(float[] results, RegionCoord region) {
        processGPUResults(results, 0, region);
    }

    static void processGPUResults(float[] results, int resultOffset, RegionCoord region) {
        for (int localX = 0; localX < 5; localX++) {
            for (int localZ = 0; localZ < 5; localZ++) {
                ChunkCoord chunk = region.getChunk(localX, localZ);
                int chunkIndex = resultOffset + localX * 5 + localZ;
                int summaryOffset = resultOffset + (localX * 5 + localZ) * SUMMARY_COMPONENTS;
                float[] gpuTerrainData = extractChunkSummary(results, summaryOffset);

                injectGPUDataIntoCaches(chunk, gpuTerrainData);

                LC2H.LOGGER.debug("[LC2H] Injected GPU data into caches for chunk {} in region {}", chunk, region);
            }
        }
    }

    private static float[] extractChunkSummary(float[] results, int summaryOffset) {
        if (results == null || summaryOffset < 0 || (summaryOffset + SUMMARY_COMPONENTS) > results.length) {
            return GPU_WARM_SUMMARY;
        }
        float[] summary = new float[SUMMARY_COMPONENTS];
        System.arraycopy(results, summaryOffset, summary, 0, SUMMARY_COMPONENTS);
        return summary;
    }

    private static void injectGPUDataIntoCaches(ChunkCoord chunk, float[] gpuData) {
        boolean cachedInRam = GPUMemoryManager.putGPUData(chunk, gpuData, AsyncMultiChunkPlanner.GPU_DATA_CACHE);
        if (!cachedInRam) {
            return;
        }

        GPUMemoryManager.putGPUDataRuntimeOnly(chunk, gpuData, AsyncBuildingInfoPlanner.GPU_DATA_CACHE);
        GPUMemoryManager.putGPUDataRuntimeOnly(chunk, gpuData, AsyncTerrainFeaturePlanner.GPU_DATA_CACHE);
        GPUMemoryManager.putGPUDataRuntimeOnly(chunk, gpuData, AsyncPaletteGenerator.GPU_DATA_CACHE);
        GPUMemoryManager.putGPUDataRuntimeOnly(chunk, gpuData, AsyncDebrisGenerator.GPU_DATA_CACHE);
        GPUMemoryManager.putGPUDataRuntimeOnly(chunk, gpuData, AsyncTerrainCorrectionPlanner.GPU_DATA_CACHE);
    }
}
