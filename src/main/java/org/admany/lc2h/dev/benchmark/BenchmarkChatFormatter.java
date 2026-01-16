package org.admany.lc2h.dev.benchmark;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.admany.lc2h.util.log.ChatMessenger;

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

        Style info = Style.EMPTY.withColor(BenchmarkManager.COLOR_INFO);
        Style success = Style.EMPTY.withColor(BenchmarkManager.COLOR_SUCCESS);
        Style dark = Style.EMPTY.withColor(BenchmarkManager.COLOR_COUNTDOWN_DARK_GRAY).withBold(true);

        Component cpuValue = Component.literal(cpuModel).withStyle(Style.EMPTY.withColor(cpuColor));
        Component ramValue = Component.literal(ramGB + "GB " + ramType + " " + ramSpeedMHz + "MHz").withStyle(Style.EMPTY.withColor(BenchmarkManager.COLOR_YELLOW));
        Component gpuValue = Component.literal(gpuRenderer).withStyle(Style.EMPTY.withColor(gpuColor));

        return ChatMessenger.prefixComponent()
            .withStyle(info.withBold(true))
            .append(Component.translatable("lc2h.benchmark.chat.complete").withStyle(success.withBold(true)))
            .append(Component.literal("\n"))
            .append(Component.translatable("lc2h.benchmark.chat.cpm", Component.literal(String.format("%.1f", result.effectiveChunksPerMinute())).withStyle(success)).withStyle(info))
            .append(Component.literal("\n"))
            .append(Component.translatable("lc2h.benchmark.chat.tps", Component.literal(String.format("%.1f", result.commonTps())).withStyle(success)).withStyle(info))
            .append(Component.literal("\n"))
            .append(Component.translatable("lc2h.benchmark.chat.score", Component.literal(String.format("%.0f", result.scoreEstimate())).withStyle(success)).withStyle(info))
            .append(Component.literal("\n"))
            .append(Component.translatable("lc2h.benchmark.chat.freezes", Component.literal(String.valueOf(result.freezeCount())).withStyle(success)).withStyle(info))
            .append(Component.literal("\n"))
            .append(Component.translatable("lc2h.benchmark.chat.upload", Component.literal(uploadStatus).withStyle(success)).withStyle(info))
            .append(Component.literal("\n"))
            .append(Component.translatable("lc2h.benchmark.chat.hardware").withStyle(info))
            .append(Component.literal("\n"))
            .append(Component.translatable("lc2h.benchmark.chat.hw_cpu", cpuValue).withStyle(info))
            .append(Component.literal("\n"))
            .append(Component.translatable("lc2h.benchmark.chat.hw_ram", ramValue).withStyle(info))
            .append(Component.literal("\n"))
            .append(Component.translatable("lc2h.benchmark.chat.hw_gpu", gpuValue).withStyle(info))
            .append(Component.literal("\n\n"))
            .append(Component.translatable("lc2h.benchmark.chat.cpm_calc_title").withStyle(dark))
            .append(Component.literal("\n"))
            .append(Component.translatable("lc2h.benchmark.chat.cpm_calc_formula").withStyle(dark))
            .append(Component.literal("\n"))
            .append(Component.translatable("lc2h.benchmark.chat.cpm_calc_chunks_loaded").withStyle(dark))
            .append(Component.literal("\n"))
            .append(Component.translatable("lc2h.benchmark.chat.cpm_calc_real_time").withStyle(dark))
            .append(Component.literal("\n"))
            .append(Component.translatable("lc2h.benchmark.chat.cpm_calc_example").withStyle(dark))
            .append(Component.literal("\n"))
            .append(Component.translatable("lc2h.benchmark.chat.freeze_detection").withStyle(dark))
            .append(Component.literal("\n"))
            .append(Component.translatable("lc2h.benchmark.chat.freeze_example").withStyle(dark))
            .append(Component.literal("\n"))
            .append(Component.translatable("lc2h.benchmark.chat.tip").withStyle(dark));
    }

    static Component buildAbortMessage(String reason) {
        return ChatMessenger.prefixedComponent(
            Component.translatable("lc2h.benchmark.cancelled", reason),
            BenchmarkManager.COLOR_ERROR);
    }
}
