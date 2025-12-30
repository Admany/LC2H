package org.admany.lc2h.benchmark;

import com.google.gson.JsonObject;
import org.admany.lc2h.LC2H;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

final class BenchmarkResultIO {
    private BenchmarkResultIO() {
    }

    static void submit(BenchmarkResult result) {
        if (result == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject payload = result.toJson();
                byte[] data = BenchmarkManager.GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
                HttpURLConnection connection = (HttpURLConnection) new URL(BenchmarkManager.UPLOAD_ENDPOINT).openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(7000);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(data);
                }
                int code = connection.getResponseCode();
                LC2H.LOGGER.info("LC2H benchmark upload responded with HTTP {}", code);
                connection.disconnect();
            } catch (Exception ex) {
                LC2H.LOGGER.warn("LC2H benchmark upload failed: {}", ex.toString());
            }
        });
    }

    static void save(BenchmarkResult result) {
        if (result == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                Path resultsDir = Paths.get("benchmark_results");
                Files.createDirectories(resultsDir);
                String timestamp = Instant.now().toString().replace(":", "-").replace(".", "-");
                String filename = "benchmark_" + timestamp + ".json";
                Path resultFile = resultsDir.resolve(filename);
                JsonObject json = result.toJson();
                Files.writeString(resultFile, BenchmarkManager.GSON.toJson(json), StandardCharsets.UTF_8);
                LC2H.LOGGER.info("LC2H benchmark result saved to: {}", resultFile.toAbsolutePath());
            } catch (Exception ex) {
                LC2H.LOGGER.warn("LC2H benchmark file save failed: {}", ex.toString());
            }
        });
    }
}
