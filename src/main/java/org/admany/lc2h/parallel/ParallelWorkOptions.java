package org.admany.lc2h.parallel;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

public final class ParallelWorkOptions<T> {
    private final String cacheName;
    private final Function<Integer, String> cacheKeyFunction;
    private final Function<T, byte[]> cacheSerializer;
    private final Function<byte[], T> cacheDeserializer;
    private final Duration cacheTtl;
    private final long cacheMaxEntries;
    private final boolean cachePersistent;
    private final boolean cacheCompression;

    private ParallelWorkOptions(String cacheName,
                                Function<Integer, String> cacheKeyFunction,
                                Function<T, byte[]> cacheSerializer,
                                Function<byte[], T> cacheDeserializer,
                                Duration cacheTtl,
                                long cacheMaxEntries,
                                boolean cachePersistent,
                                boolean cacheCompression) {
        this.cacheName = cacheName;
        this.cacheKeyFunction = cacheKeyFunction;
        this.cacheSerializer = cacheSerializer;
        this.cacheDeserializer = cacheDeserializer;
        this.cacheTtl = cacheTtl;
        this.cacheMaxEntries = cacheMaxEntries;
        this.cachePersistent = cachePersistent;
        this.cacheCompression = cacheCompression;
    }

    public static <T> ParallelWorkOptions<T> none() {
        return new ParallelWorkOptions<>(null, null, null, null, null, 0L, false, false);
    }

    public static <T> ParallelWorkOptions<T> persistentCache(String cacheName,
                                                             Function<Integer, String> cacheKeyFunction,
                                                             Function<T, byte[]> serializer,
                                                             Function<byte[], T> deserializer,
                                                             Duration ttl,
                                                             long maxEntries,
                                                             boolean compression) {
        Objects.requireNonNull(cacheName, "cacheName");
        Objects.requireNonNull(cacheKeyFunction, "cacheKeyFunction");
        Objects.requireNonNull(serializer, "serializer");
        Objects.requireNonNull(deserializer, "deserializer");
        return new ParallelWorkOptions<>(cacheName, cacheKeyFunction, serializer, deserializer, ttl, maxEntries, true, compression);
    }

    public boolean cacheEnabled() {
        return cacheKeyFunction != null && cacheSerializer != null && cacheDeserializer != null;
    }

    public String cacheName() {
        return cacheName;
    }

    public Function<Integer, String> cacheKeyFunction() {
        return cacheKeyFunction;
    }

    public Function<T, byte[]> cacheSerializer() {
        return cacheSerializer;
    }

    public Function<byte[], T> cacheDeserializer() {
        return cacheDeserializer;
    }

    public Duration cacheTtl() {
        return cacheTtl;
    }

    public long cacheMaxEntries() {
        return cacheMaxEntries;
    }

    public boolean cachePersistent() {
        return cachePersistent;
    }

    public boolean cacheCompression() {
        return cacheCompression;
    }
}
