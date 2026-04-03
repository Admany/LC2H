package org.admany.lc2h.worldgen.gpu;

import mcjty.lostcities.worldgen.IDimensionInfo;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.worldgen.async.warmup.AsyncChunkWarmup;
import org.admany.lc2h.worldgen.coord.RegionCoord;
import org.admany.quantified.api.opencl.QuantifiedOpenCL;
import org.admany.quantified.api.vulkan.QuantifiedVulkan;
import org.lwjgl.PointerBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;

public final class RegionBatchProcessingGPUTask
    implements QuantifiedOpenCL.Workload<Boolean>, QuantifiedVulkan.Workload<Boolean> {

    private static final long CL_MEM_WRITE_ONLY = 1L << 1;
    private static final long CL_MEM_READ_ONLY = 1L << 2;

    public record Entry(IDimensionInfo provider, RegionCoord region) {
        public Entry {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(region, "region");
        }
    }

    private final List<Entry> entries;
    private final long taskKey;
    private final long estimatedVramBytes;
    private final int estimatedComputeUnits;

    public RegionBatchProcessingGPUTask(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("entries must not be empty");
        }
        this.entries = List.copyOf(entries);
        this.taskKey = computeTaskKey(this.entries);
        this.estimatedComputeUnits = RegionProcessingGPUTask.PARALLEL_UNITS * this.entries.size();
        this.estimatedVramBytes = RegionProcessingGPUTask.VRAM_BYTES * this.entries.size();
    }

    public long taskKey() {
        return taskKey;
    }

    @Override
    public long estimatedVramBytes() {
        return estimatedVramBytes;
    }

    @Override
    public int estimatedComputeUnits() {
        return estimatedComputeUnits;
    }

    @Override
    public Boolean execute(QuantifiedOpenCL.Context context) {
        try {
            float[] encodedCoords = buildInputCoords(entries);
            int inputFloats = encodedCoords.length;
            int outputFloats = entries.size() * RegionProcessingGPUTask.RESULTS_PER_REGION;

            long kernel = context.createKernel("terrain_generation");
            long bufferInputCoords = context.createBuffer(CL_MEM_READ_ONLY, inputFloats * (long) Float.BYTES);
            long bufferOutputFeatures = context.createBuffer(CL_MEM_WRITE_ONLY, outputFloats * (long) Float.BYTES);

            try {
                ByteBuffer inputBytes = ByteBuffer.allocateDirect(inputFloats * Float.BYTES).order(ByteOrder.nativeOrder());
                inputBytes.asFloatBuffer().put(encodedCoords).flip();

                context.enqueueWriteBuffer(bufferInputCoords, true, 0, inputFloats * (long) Float.BYTES, inputBytes);
                context.setKernelArgBuffer(kernel, 0, bufferInputCoords);
                context.setKernelArgBuffer(kernel, 1, bufferOutputFeatures);

                PointerBuffer workSize = PointerBuffer.allocateDirect(1);
                workSize.put(inputFloats);
                workSize.flip();

                context.enqueueNDRangeKernel(kernel, 1, workSize);
                context.finish();

                ByteBuffer resultBytes = ByteBuffer.allocateDirect(outputFloats * Float.BYTES).order(ByteOrder.nativeOrder());
                context.enqueueReadBuffer(bufferOutputFeatures, true, 0, outputFloats * (long) Float.BYTES, resultBytes);
                resultBytes.rewind();

                float[] results = new float[outputFloats];
                resultBytes.asFloatBuffer().get(results);
                processGPUResults(entries, results);
                AsyncChunkWarmup.recordOpenClBatchProcessed(entries.size());

                LC2H.LOGGER.debug("[LC2H] Successfully processed {} regions in one OpenCL batch", entries.size());
                return Boolean.TRUE;
            } finally {
                context.releaseBuffer(bufferInputCoords);
                context.releaseBuffer(bufferOutputFeatures);
                context.releaseKernel(kernel);
            }
        } catch (Exception e) {
            LC2H.LOGGER.error("[LC2H] Batched OpenCL region processing failed: {}", e.getMessage());
            return processBatchOnCPU(entries);
        }
    }

    @Override
    public Boolean execute(QuantifiedVulkan.Context context) {
        try {
            float[] results = context.terrainGeneration(buildInputCoords(entries));
            processGPUResults(entries, results);
            AsyncChunkWarmup.recordVulkanBatchProcessed(entries.size());
            LC2H.LOGGER.debug("[LC2H] Successfully processed {} regions in one Vulkan batch on {}", entries.size(), context.deviceName());
            return Boolean.TRUE;
        } catch (Exception e) {
            LC2H.LOGGER.error("[LC2H] Batched Vulkan region processing failed: {}", e.getMessage());
            return processBatchOnCPU(entries);
        }
    }

    public static Boolean processBatchOnCPU(List<Entry> entries) {
        for (Entry entry : entries) {
            RegionProcessingGPUTask.processRegionOnCPU(entry.provider(), entry.region());
        }
        return Boolean.TRUE;
    }

    private static float[] buildInputCoords(List<Entry> entries) {
        float[] input = new float[entries.size() * RegionProcessingGPUTask.INPUTS_PER_REGION];
        int offset = 0;
        for (Entry entry : entries) {
            RegionProcessingGPUTask.encodeInputCoords(entry.provider(), entry.region(), input, offset);
            offset += RegionProcessingGPUTask.INPUTS_PER_REGION;
        }
        return input;
    }

    private static void processGPUResults(List<Entry> entries, float[] results) {
        int regionResultOffset = 0;
        for (Entry entry : entries) {
            RegionProcessingGPUTask.processGPUResults(results, regionResultOffset, entry.region());
            regionResultOffset += RegionProcessingGPUTask.RESULTS_PER_REGION;
        }
        GPUMemoryManager.continuousCleanup();
    }

    private static long computeTaskKey(List<Entry> entries) {
        long hash = 0x9E3779B97F4A7C15L;
        for (Entry entry : entries) {
            RegionCoord region = entry.region();
            hash = mix(hash, region.dimension().location().hashCode());
            hash = mix(hash, region.regionX());
            hash = mix(hash, region.regionZ());
            hash = mix(hash, Long.hashCode(entry.provider().getSeed()));
        }
        return hash & Long.MAX_VALUE;
    }

    private static long mix(long current, long value) {
        long mixed = current ^ (value + 0x9E3779B97F4A7C15L + (current << 6) + (current >>> 2));
        return mixed ^ (mixed >>> 33);
    }
}
