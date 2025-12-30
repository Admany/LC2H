package org.admany.lc2h.benchmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.admany.lc2h.LC2H;

import java.time.Instant;
import java.util.Map;

public record BenchmarkResult(boolean success, boolean aborted, String abortReason, long chunksGenerated,
                       double simulatedChunksPerMinute, double simulatedDurationSeconds,
                       double realDurationSeconds, double minTps, double maxTps, double commonTps,
                       double averageTps, double roundedTps, double averageTickMillis,
                       double maxTickMillis, double effectiveChunksPerMinute, double scoreEstimate,
                       int freezeCount, int tickSamples, long seed, ResourceKey<Level> dimension,
                       String modVersion, String minecraftVersion, String playerName,
                       Map<Integer, Integer> tpsHistogram, JsonObject hardwareInfo) {

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("success", success);
        root.addProperty("aborted", aborted);
        if (aborted) {
            root.addProperty("abortReason", abortReason);
        }
        root.addProperty("modId", LC2H.MODID);
        root.addProperty("modVersion", modVersion);
        root.addProperty("minecraftVersion", minecraftVersion);
        root.addProperty("player", playerName);
        root.addProperty("seed", seed);
        root.addProperty("dimension", dimension.location().toString());
        root.addProperty("timestamp", Instant.now().toString());

        JsonObject metrics = new JsonObject();
        metrics.addProperty("chunksGenerated", chunksGenerated);
        metrics.addProperty("simulatedChunksPerMinute", simulatedChunksPerMinute);
        metrics.addProperty("effectiveChunksPerMinute", effectiveChunksPerMinute);
        metrics.addProperty("simulatedDurationSeconds", simulatedDurationSeconds);
        metrics.addProperty("realDurationSeconds", realDurationSeconds);
        metrics.addProperty("minTPS", minTps);
        metrics.addProperty("maxTPS", maxTps);
        metrics.addProperty("commonTPS", commonTps);
        metrics.addProperty("averageTPS", averageTps);
        metrics.addProperty("roundedTPS", roundedTps);
        metrics.addProperty("averageTickMillis", averageTickMillis);
        metrics.addProperty("maxTickMillis", maxTickMillis);
        metrics.addProperty("freezeCount", freezeCount);
        metrics.addProperty("tickSamples", tickSamples);
        metrics.addProperty("scoreEstimate", scoreEstimate);
        metrics.add("tpsHistogram", histogramToJson(tpsHistogram));
        root.add("metrics", metrics);

        root.add("hardware", hardwareInfo);
        return root;
    }

    private JsonArray histogramToJson(Map<Integer, Integer> histogram) {
        JsonArray array = new JsonArray();
        for (Map.Entry<Integer, Integer> entry : histogram.entrySet()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("tps", entry.getKey() / 2.0);
            obj.addProperty("samples", entry.getValue());
            array.add(obj);
        }
        return array;
    }
}
