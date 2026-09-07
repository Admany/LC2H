package org.admany.lc2h.worldgen.kernel;

public interface LostCityCpuKernel {
    KernelCapabilities capabilities();

    void warmRoleLookups(LostCityKernelInput input);

    LostCityMultiChunkPlan planMultiChunk(LostCityKernelInput input);

    LostCityStageResult planBuildingInfo(LostCityKernelInput input);

    LostCityStageResult planTerrainFeatures(LostCityKernelInput input);

    LostCityStageResult planTerrainCorrections(LostCityKernelInput input);

    LostCityStageResult planCityLayout(LostCityKernelInput input);
}
