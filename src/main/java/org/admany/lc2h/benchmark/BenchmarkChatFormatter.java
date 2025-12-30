package org.admany.lc2h.benchmark;

import net.minecraft.network.chat.Component;

final class BenchmarkChatFormatter {
    private BenchmarkChatFormatter() {
    }

    static Component buildSuccessMessage(BenchmarkResult result, String uploadStatus) {
        String cpuModel = result.hardwareInfo().get("cpuModel").getAsString();
        int ramGB = result.hardwareInfo().get("systemMemoryMB").getAsInt() / 1024;
        String gpuRenderer = result.hardwareInfo().get("gpuRenderer").getAsString();
        String ramType = result.hardwareInfo().has("ramType") ? result.hardwareInfo().get("ramType").getAsString() : "unknown";
        int ramSpeedMHz = result.hardwareInfo().has("ramSpeedMHz") ? result.hardwareInfo().get("ramSpeedMHz").getAsInt() : 0;

        int cpuColor = cpuModel.toLowerCase().contains("intel") ? BenchmarkManager.COLOR_BLUE : (cpuModel.toLowerCase().contains("amd") ? BenchmarkManager.COLOR_RED : BenchmarkManager.COLOR_SUCCESS);
        int gpuColor;
        String gpuLower = gpuRenderer.toLowerCase();
        if (gpuLower.contains("nvidia")) {
            gpuColor = BenchmarkManager.COLOR_GREEN;
        } else if (gpuLower.contains("radeon") || gpuLower.contains("amd")) {
            gpuColor = BenchmarkManager.COLOR_RED;
        } else if (gpuLower.contains("intel")) {
            gpuColor = BenchmarkManager.COLOR_BLUE;
        } else {
            gpuColor = BenchmarkManager.COLOR_SUCCESS;
        }

        return Component.literal("[LC2H] ")
            .withStyle(style -> style.withColor(BenchmarkManager.COLOR_INFO).withBold(true))
            .append(Component.literal("Benchmark Complete!").withStyle(style -> style.withColor(BenchmarkManager.COLOR_SUCCESS).withBold(true)))
            .append(Component.literal("\nCPM: ").withStyle(style -> style.withColor(BenchmarkManager.COLOR_INFO)))
            .append(Component.literal(String.format("%.1f", result.effectiveChunksPerMinute())).withStyle(style -> style.withColor(BenchmarkManager.COLOR_SUCCESS)))
            .append(Component.literal("\nTPS: ").withStyle(style -> style.withColor(BenchmarkManager.COLOR_INFO)))
            .append(Component.literal(String.format("%.1f", result.commonTps())).withStyle(style -> style.withColor(BenchmarkManager.COLOR_SUCCESS)))
            .append(Component.literal("\nScore: ").withStyle(style -> style.withColor(BenchmarkManager.COLOR_INFO)))
            .append(Component.literal(String.format("%.0f", result.scoreEstimate())).withStyle(style -> style.withColor(BenchmarkManager.COLOR_SUCCESS)))
            .append(Component.literal("\nFreezes: ").withStyle(style -> style.withColor(BenchmarkManager.COLOR_INFO)))
            .append(Component.literal(String.valueOf(result.freezeCount())).withStyle(style -> style.withColor(BenchmarkManager.COLOR_SUCCESS)))
            .append(Component.literal("\nUpload: ").withStyle(style -> style.withColor(BenchmarkManager.COLOR_INFO)))
            .append(Component.literal(uploadStatus).withStyle(style -> style.withColor(BenchmarkManager.COLOR_SUCCESS)))
            .append(Component.literal("\nHardware:").withStyle(style -> style.withColor(BenchmarkManager.COLOR_INFO)))
            .append(Component.literal("\n《▓ CPU: ").withStyle(style -> style.withColor(BenchmarkManager.COLOR_INFO)))
            .append(Component.literal(cpuModel).withStyle(style -> style.withColor(cpuColor)))
            .append(Component.literal(" ▓》").withStyle(style -> style.withColor(BenchmarkManager.COLOR_INFO)))
            .append(Component.literal("\n《▓ RAM: ").withStyle(style -> style.withColor(BenchmarkManager.COLOR_INFO)))
            .append(Component.literal(ramGB + "GB " + ramType + " " + ramSpeedMHz + "MHz").withStyle(style -> style.withColor(BenchmarkManager.COLOR_YELLOW)))
            .append(Component.literal(" ▓》").withStyle(style -> style.withColor(BenchmarkManager.COLOR_INFO)))
            .append(Component.literal("\n《▓ GPU: ").withStyle(style -> style.withColor(BenchmarkManager.COLOR_INFO)))
            .append(Component.literal(gpuRenderer).withStyle(style -> style.withColor(gpuColor)))
            .append(Component.literal(" ▓》").withStyle(style -> style.withColor(BenchmarkManager.COLOR_INFO)))
            .append(Component.literal("\n\nCPM Calculation (Chunks Per Minute):").withStyle(style -> style.withColor(BenchmarkManager.COLOR_COUNTDOWN_DARK_GRAY).withBold(true)))
            .append(Component.literal("\nFormula: CPM = (Chunks Loaded × 60) ÷ Real Time (seconds)").withStyle(style -> style.withColor(BenchmarkManager.COLOR_COUNTDOWN_DARK_GRAY).withBold(true)))
            .append(Component.literal("\n- Chunks Loaded: Number of chunks that reached FULL status during the 60-second run.").withStyle(style -> style.withColor(BenchmarkManager.COLOR_COUNTDOWN_DARK_GRAY).withBold(true)))
            .append(Component.literal("\n- Real Time: Actual elapsed time (accounts for freezes/lag).").withStyle(style -> style.withColor(BenchmarkManager.COLOR_COUNTDOWN_DARK_GRAY).withBold(true)))
            .append(Component.literal("\nExample: 1594 chunks in 60.5 seconds = (1594 × 60) ÷ 60.5 ≈ 1582 CPM").withStyle(style -> style.withColor(BenchmarkManager.COLOR_COUNTDOWN_DARK_GRAY).withBold(true)))
            .append(Component.literal("\nFreeze Detection: Any tick lasting 3+ seconds is counted as a freeze.").withStyle(style -> style.withColor(BenchmarkManager.COLOR_COUNTDOWN_DARK_GRAY).withBold(true)))
            .append(Component.literal("\nExample: 5-second lag spike = 1 freeze (penalizes score by 5000 points).").withStyle(style -> style.withColor(BenchmarkManager.COLOR_COUNTDOWN_DARK_GRAY).withBold(true)))
            .append(Component.literal("\nHigher CPM + Fewer Freezes = Better worldgen performance!").withStyle(style -> style.withColor(BenchmarkManager.COLOR_COUNTDOWN_DARK_GRAY).withBold(true)));
    }

    static Component buildAbortMessage(String reason) {
        return Component.literal("[LC2H] ")
            .withStyle(style -> style.withColor(BenchmarkManager.COLOR_ERROR))
            .append(Component.literal("Benchmark Cancelled: " + reason)
                .withStyle(style -> style.withColor(BenchmarkManager.COLOR_ERROR)));
    }
}
