package org.admany.lc2h.worldgen.kernel;

import java.util.Set;

public record KernelCapabilities(
    String backendName,
    String backendVersion,
    boolean nativeBackend,
    boolean vectorBackend,
    Set<LostCityKernelStage> stages
) {
    public boolean supports(LostCityKernelStage stage) {
        return stages != null && stages.contains(stage);
    }
}
