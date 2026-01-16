package org.admany.lc2h.dev.benchmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

final class BenchmarkHardware {
    private BenchmarkHardware() {
    }

    static JsonObject collectEnvironmentHardware() {
        Runtime runtime = Runtime.getRuntime();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();

        JsonObject info = new JsonObject();
        info.addProperty("osName", os.getName());
        info.addProperty("osArch", os.getArch());
        info.addProperty("osVersion", os.getVersion());
        info.addProperty("logicalProcessors", runtime.availableProcessors());
        info.addProperty("systemMemoryMB", getTotalSystemMemoryMB(os));
        info.addProperty("maxMemoryMB", bytesToMegabytes(runtime.maxMemory()));
        info.addProperty("allocatedMemoryMB", bytesToMegabytes(runtime.totalMemory()));
        info.addProperty("freeMemoryMB", bytesToMegabytes(runtime.freeMemory()));
        info.addProperty("javaVersion", System.getProperty("java.version"));
        info.addProperty("javaVendor", System.getProperty("java.vendor"));
        info.addProperty("cpuModel", detectCpuModel());
        info.addProperty("gpuRenderer", detectGpuRenderer());
        info.add("jvmFlags", toJsonArray(runtimeMxBean.getInputArguments()));
        info.addProperty("timestamp", Instant.now().toString());
        return info;
    }

    static JsonObject collectClientHardware() {
        JsonObject clientInfo = new JsonObject();
        Runtime runtime = Runtime.getRuntime();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();

        clientInfo.addProperty("osName", os.getName());
        clientInfo.addProperty("osArch", os.getArch());
        clientInfo.addProperty("osVersion", os.getVersion());
        clientInfo.addProperty("logicalProcessors", runtime.availableProcessors());
        clientInfo.addProperty("systemMemoryMB", getTotalSystemMemoryMB(os));
        clientInfo.addProperty("maxMemoryMB", bytesToMegabytes(runtime.maxMemory()));
        clientInfo.addProperty("allocatedMemoryMB", bytesToMegabytes(runtime.totalMemory()));
        clientInfo.addProperty("freeMemoryMB", bytesToMegabytes(runtime.freeMemory()));
        clientInfo.addProperty("javaVersion", System.getProperty("java.version"));
        clientInfo.addProperty("javaVendor", System.getProperty("java.vendor"));

        clientInfo.addProperty("cpuModel", detectCpuModel());

        long totalMemoryMB = getTotalSystemMemoryMB(os);
        clientInfo.addProperty("systemMemoryMB", totalMemoryMB);

        clientInfo.addProperty("ramType", "unknown");
        clientInfo.addProperty("ramSpeedMHz", 0);

        clientInfo.addProperty("gpuRenderer", detectGpuRenderer());

        clientInfo.add("jvmFlags", toJsonArray(runtimeMxBean.getInputArguments()));
        clientInfo.addProperty("timestamp", Instant.now().toString());
        return clientInfo;
    }

    private static long getTotalSystemMemoryMB(OperatingSystemMXBean os) {
        try {
            Method method = os.getClass().getMethod("getTotalPhysicalMemorySize");
            Object value = method.invoke(os);
            if (value instanceof Number number) {
                return bytesToMegabytes(number.longValue());
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    private static long bytesToMegabytes(long bytes) {
        if (bytes <= 0) {
            return 0L;
        }
        return bytes / 1024L / 1024L;
    }

    private static String detectCpuModel() {
        String[] envKeys = {"PROCESSOR_IDENTIFIER", "PROCESSOR_DESCRIPTION", "PROCESSOR_NAME", "PROCESSOR", "CPU"};
        for (String key : envKeys) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                value = value.trim();
                if (value.contains("GenuineIntel")) {
                    value = value.replace("Intel64 Family 6 Model", "Intel Model").replace("GenuineIntel", "").trim();
                } else if (value.contains("AuthenticAMD")) {
                    value = value.replace("AMD64 Family", "AMD Family").replace("AuthenticAMD", "").trim();
                }
                return value;
            }
        }
        return System.getProperty("os.arch", "unknown");
    }

    private static String detectGpuRenderer() {
        try {
            Class<?> renderSystem = Class.forName("com.mojang.blaze3d.platform.RenderSystem");
            Method method = renderSystem.getMethod("getBackendDescription");
            Object value = method.invoke(null);
            if (value instanceof String str && !str.isBlank()) {
                return str.trim();
            }
        } catch (Throwable ignored) {
        }
        return System.getProperty("org.lwjgl.opengl.renderer", "unknown");
    }

    private static JsonArray toJsonArray(List<String> list) {
        JsonArray array = new JsonArray();
        for (String entry : list) {
            array.add(entry);
        }
        return array;
    }
}
