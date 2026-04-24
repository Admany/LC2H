package org.admany.lc2h.worldgen.gpu;

import mcjty.lostcities.varia.ChunkCoord;
import org.admany.lc2h.LC2H;
import org.admany.quantified.core.common.cache.CacheManager;
import org.admany.quantified.core.common.cache.impl.CaffeineThreadSafeCache;
import org.admany.quantified.core.common.cache.interfaces.ThreadSafeCache;
import org.admany.quantified.core.common.util.QuantifiedPaths;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class GpuDiskCache {

    private static final ThreadSafeCache<String, float[]> DISK_BACKING_CACHE = CacheManager.register(
        "lc2h.gpuData",
        () -> new CaffeineThreadSafeCache.CacheBuilderSpec(4096, Duration.ofHours(6), true, 0),
        true,
        true
    );

    private static final ExecutorService DISK_IO = Executors.newFixedThreadPool(
        Math.max(1, Integer.getInteger("lc2h.gpu.diskThreads", 1)),
        newNamedDaemonFactory("LC2H-GpuDisk")
    );
    private static final ConcurrentHashMap<String, CompletableFuture<float[]>> IN_FLIGHT_READS = new ConcurrentHashMap<>();

    private static final String DISK_CACHE_FOLDER = "gpuData";
    private static volatile Path diskCacheDirectory;

    private GpuDiskCache() {
    }

    static void persist(ChunkCoord coord, float[] data) {
        if (coord == null || data == null) {
            return;
        }

        float[] snapshot = Arrays.copyOf(data, data.length);
        String diskKey = toDiskKey(coord);

        try {
            DISK_BACKING_CACHE.put(diskKey, snapshot);
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Failed to persist GPU data for {} to persistent cache: {}", coord, t.getMessage());
        }

        scheduleWrite(coord, snapshot);
    }

    static float[] load(ChunkCoord coord) {
        if (coord == null) {
            return null;
        }

        String diskKey = toDiskKey(coord);
        try {
            float[] stored = DISK_BACKING_CACHE.getIfPresent(diskKey);
            if (stored != null) {
                return Arrays.copyOf(stored, stored.length);
            }
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Failed to access persistent cache for {}: {}", coord, t.getMessage());
        }

        scheduleRead(coord, diskKey);
        return null;
    }

    static void delete(ChunkCoord coord) {
        if (coord == null) {
            return;
        }

        String diskKey = toDiskKey(coord);
        try {
            DISK_BACKING_CACHE.invalidate(diskKey);
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Failed to invalidate persistent cache entry for {}: {}", coord, t.getMessage());
        }

        scheduleDelete(coord);
    }

    static void clearAll() {
        try {
            DISK_BACKING_CACHE.invalidateAll();
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Failed to clear disk GPU cache: {}", t.getMessage());
        }
        scheduleClear();
    }

    static long getBackingCacheEntryCount() {
        try {
            return DISK_BACKING_CACHE.size();
        } catch (Throwable t) {
            return 0L;
        }
    }

    static long estimateBackingCacheBytes() {
        try {
            Map<String, float[]> snapshot = DISK_BACKING_CACHE.snapshot();
            long bytes = 0L;
            for (float[] data : snapshot.values()) {
                if (data != null) {
                    bytes += (long) data.length * 4L;
                }
            }
            return bytes;
        } catch (Throwable t) {
            long entries = getBackingCacheEntryCount();
            return entries * 4096L;
        }
    }

    private static void writeDiskFile(ChunkCoord coord, float[] data) {
        Path directory = getDiskCacheDirectory();
        if (directory == null) {
            return;
        }

        Path file = directory.resolve(fileNameFor(coord));
        try (ObjectOutputStream oos = new ObjectOutputStream(
            new BufferedOutputStream(new GZIPOutputStream(
                Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))))) {
            oos.writeObject(data);
        } catch (IOException e) {
            LC2H.LOGGER.debug("Failed to write GPU disk cache for {}: {}", coord, e.getMessage());
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Unexpected error writing GPU disk cache for {}: {}", coord, t.getMessage());
        }
    }

    private static float[] readDiskFile(ChunkCoord coord) {
        Path directory = getDiskCacheDirectory();
        if (directory == null) {
            return null;
        }

        Path file = directory.resolve(fileNameFor(coord));
        if (!Files.exists(file)) {
            return null;
        }

        try (ObjectInputStream ois = new ObjectInputStream(
            new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file, StandardOpenOption.READ))))) {
            Object stored = ois.readObject();
            if (stored instanceof float[] floats) {
                return floats;
            }
            LC2H.LOGGER.debug("Disk cache entry for {} contained unexpected type {}", coord, stored != null ? stored.getClass().getName() : "null");
        } catch (IOException | ClassNotFoundException e) {
            LC2H.LOGGER.debug("Failed to read GPU disk cache for {}: {}", coord, e.getMessage());
            try {
                Files.deleteIfExists(file);
            } catch (IOException deleteError) {
                LC2H.LOGGER.debug("Failed to delete corrupt GPU disk cache entry for {}: {}", coord, deleteError.getMessage());
            }
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Unexpected error reading GPU disk cache for {}: {}", coord, t.getMessage());
        }
        return null;
    }

    private static void scheduleRead(ChunkCoord coord, String diskKey) {
        IN_FLIGHT_READS.computeIfAbsent(diskKey, key -> CompletableFuture.supplyAsync(() -> readDiskFile(coord), DISK_IO)
            .whenComplete((data, throwable) -> {
                try {
                    if (data != null) {
                        DISK_BACKING_CACHE.put(key, Arrays.copyOf(data, data.length));
                    }
                } catch (Throwable ignored) {
                } finally {
                    IN_FLIGHT_READS.remove(key);
                }
            }));
    }

    private static void scheduleWrite(ChunkCoord coord, float[] data) {
        CompletableFuture.runAsync(() -> writeDiskFile(coord, data), DISK_IO);
    }

    private static void scheduleDelete(ChunkCoord coord) {
        CompletableFuture.runAsync(() -> {
            Path directory = diskCacheDirectory != null ? diskCacheDirectory : getDiskCacheDirectory();
            if (directory == null) {
                return;
            }
            Path file = directory.resolve(fileNameFor(coord));
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LC2H.LOGGER.debug("Failed to delete GPU disk cache entry for {}: {}", coord, e.getMessage());
            } catch (Throwable t) {
                LC2H.LOGGER.debug("Unexpected error deleting GPU disk cache entry for {}: {}", coord, t.getMessage());
            }
        }, DISK_IO);
    }

    private static void scheduleClear() {
        CompletableFuture.runAsync(GpuDiskCache::clearDiskDirectory, DISK_IO);
    }

    private static void clearDiskDirectory() {
        Path directory = getDiskCacheDirectory();
        if (directory == null) {
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    continue;
                }
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException e) {
                    LC2H.LOGGER.debug("Failed to delete GPU disk cache file {}: {}", entry, e.getMessage());
                }
            }
        } catch (IOException e) {
            LC2H.LOGGER.debug("Failed to iterate GPU disk cache directory: {}", e.getMessage());
        } catch (Throwable t) {
            LC2H.LOGGER.debug("Unexpected error clearing GPU disk cache directory: {}", t.getMessage());
        }
    }

    static Path getDiskCacheDirectory() {
        Path directory = diskCacheDirectory;
        if (directory != null) {
            return directory;
        }
        synchronized (GpuDiskCache.class) {
            directory = diskCacheDirectory;
            if (directory == null) {
                directory = initializeDiskCacheDirectory();
                diskCacheDirectory = directory;
            }
        }
        return directory;
    }

    private static Path initializeDiskCacheDirectory() {
        try {
            QuantifiedPaths.ensureCacheLayout();
            Path basePath = QuantifiedPaths.getCacheDir().resolve("lc2h").resolve(DISK_CACHE_FOLDER);
            Files.createDirectories(basePath);
            LC2H.LOGGER.info("GPU disk cache directory initialised at {}", basePath.toAbsolutePath());
            return basePath;
        } catch (IOException e) {
            LC2H.LOGGER.warn("Unable to prepare GPU disk cache directory: {}", e.getMessage());
        } catch (Throwable t) {
            LC2H.LOGGER.warn("Unexpected error preparing GPU disk cache directory: {}", t.getMessage());
        }
        return null;
    }

    private static String fileNameFor(ChunkCoord coord) {
        return sanitizeForFile(toDiskKey(coord)) + ".cache.gz";
    }

    private static String toDiskKey(ChunkCoord coord) {
        try {
            String dimension = "unknown";
            Object dimObj = coord.dimension();
            if (dimObj != null) {
                try {
                    Object location = dimObj.getClass().getMethod("location").invoke(dimObj);
                    if (location != null) {
                        dimension = location.toString();
                    }
                } catch (Throwable ignored) {
                    dimension = dimObj.toString();
                }
            }
            return dimension + '|' + coord.chunkX() + '|' + coord.chunkZ();
        } catch (Throwable t) {
            return "unknown|" + coord.hashCode();
        }
    }

    private static String sanitizeForFile(String input) {
        StringBuilder builder = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.') {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }

    private static ThreadFactory newNamedDaemonFactory(String prefix) {
        java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(prefix + "-" + idx.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
