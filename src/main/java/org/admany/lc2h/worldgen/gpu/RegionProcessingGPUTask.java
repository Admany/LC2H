package org.admany.lc2h.worldgen.gpu;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.worldgen.coord.RegionCoord;
import org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner;
import org.admany.lc2h.worldgen.async.planner.AsyncBuildingInfoPlanner;
import org.admany.lc2h.worldgen.async.planner.AsyncTerrainFeaturePlanner;
import org.admany.lc2h.worldgen.async.planner.AsyncTerrainCorrectionPlanner;
import org.admany.lc2h.worldgen.async.generator.AsyncPaletteGenerator;
import org.admany.lc2h.worldgen.async.generator.AsyncDebrisGenerator;
import org.admany.quantified.api.opencl.QuantifiedOpenCL;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;

public record RegionProcessingGPUTask(IDimensionInfo provider, RegionCoord region)
    implements QuantifiedOpenCL.Workload<Boolean> {

    private static final int CHUNKS_PER_REGION = 25; // This is a 5x5 region
    private static final int BLOCKS_PER_CHUNK = 256; // This is a 16x16 area

    public static final long VRAM_BYTES = 25L * 256L * 4L * 4L;
    public static final int PARALLEL_UNITS = CHUNKS_PER_REGION * BLOCKS_PER_CHUNK;

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

            int dataSize = CHUNKS_PER_REGION * BLOCKS_PER_CHUNK;
            int outputSize = dataSize * 4;

            long bufferInputCoords = context.createBuffer(CL10.CL_MEM_READ_ONLY, dataSize * 4L);
            long bufferOutputFeatures = context.createBuffer(CL10.CL_MEM_WRITE_ONLY, outputSize * 4L);

            try {
                java.nio.ByteBuffer inputBytes = java.nio.ByteBuffer.allocateDirect(dataSize * 4)
                    .order(java.nio.ByteOrder.nativeOrder());
                java.nio.FloatBuffer inputCoords = inputBytes.asFloatBuffer();

                for (int i = 0; i < dataSize; i++) {
                    int chunkIndex = i / BLOCKS_PER_CHUNK;
                    int blockIndex = i % BLOCKS_PER_CHUNK;

                    int localX = chunkIndex / 5;
                    int localZ = chunkIndex % 5;
                    int blockX = blockIndex % 16;
                    int blockZ = blockIndex / 16;

                    ChunkCoord chunk = region.getChunk(localX, localZ);
                    int worldX = chunk.chunkX() * 16 + blockX;
                    int worldZ = chunk.chunkZ() * 16 + blockZ;

                    inputCoords.put(worldX + worldZ * 10000.0f + provider.getSeed() * 0.000001f);
                }

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
                java.nio.FloatBuffer results = resultBytes.asFloatBuffer();

                processGPUResults(results, provider, region);

                LC2H.LOGGER.debug("[LC2H] Successfully processed region {} on GPU with {} data points", region, results.capacity());
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

    public static Boolean processRegionOnCPU(IDimensionInfo provider, RegionCoord region) {
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
    }

    private static void processGPUResults(java.nio.FloatBuffer results, IDimensionInfo provider, RegionCoord region) {
        for (int localX = 0; localX < 5; localX++) {
            for (int localZ = 0; localZ < 5; localZ++) {
                ChunkCoord chunk = region.getChunk(localX, localZ);
                int chunkIndex = localX * 5 + localZ;

                float[] gpuTerrainData = new float[BLOCKS_PER_CHUNK * 4];
                for (int blockIndex = 0; blockIndex < BLOCKS_PER_CHUNK; blockIndex++) {
                    int globalBaseIndex = chunkIndex * BLOCKS_PER_CHUNK * 4 + blockIndex * 4;
                    gpuTerrainData[blockIndex * 4] = results.get(globalBaseIndex);
                    gpuTerrainData[blockIndex * 4 + 1] = results.get(globalBaseIndex + 1);
                    gpuTerrainData[blockIndex * 4 + 2] = results.get(globalBaseIndex + 2);
                    gpuTerrainData[blockIndex * 4 + 3] = results.get(globalBaseIndex + 3);
                }

                injectGPUDataIntoCaches(chunk, gpuTerrainData, provider);

                LC2H.LOGGER.debug("[LC2H] Injected GPU data into caches for chunk {} in region {}", chunk, region);

                GPUMemoryManager.continuousCleanup();
            }
        }
    }

    private static void injectGPUDataIntoCaches(ChunkCoord chunk, float[] gpuData, IDimensionInfo provider) {
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
