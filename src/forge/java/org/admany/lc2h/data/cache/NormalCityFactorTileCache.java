package org.admany.lc2h.data.cache;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.worldgen.IDimensionInfo;
import org.admany.lc2h.util.PackedCoordinateKey;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Exact 32 by 32 city-factor fields shared by overlapping LC planners. */
public final class NormalCityFactorTileCache {

    public static final int TILE_SIDE = 32;

    private static final int MAX_TILES = Math.max(128,
        Integer.getInteger("lc2h.city.normalFactorCacheTiles", 2_048));

    private static final ConcurrentHashMap<IDimensionInfo,
        ConcurrentHashMap<LostCityProfile, ConcurrentHashMap<Long, TileState>>> TILES =
        new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<TileToken> ORDER = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger TILE_COUNT = new AtomicInteger();
    private static final AtomicLong EPOCH = new AtomicLong();
    private static final ThreadLocal<LocalTile> LOCAL = new ThreadLocal<>();

    private NormalCityFactorTileCache() {
    }

    public static float get(IDimensionInfo provider,
                            LostCityProfile profile,
                            int chunkX,
                            int chunkZ,
                            boolean allowWait,
                            Builder builder) {
        int tileX = Math.floorDiv(chunkX, TILE_SIDE);
        int tileZ = Math.floorDiv(chunkZ, TILE_SIDE);
        long tileKey = PackedCoordinateKey.of(tileX, tileZ);
        long epoch = EPOCH.get();
        LocalTile local = LOCAL.get();
        if (local != null && local.matches(epoch, provider, profile, tileKey)) {
            return local.values[index(chunkX, chunkZ)];
        }

        ConcurrentHashMap<LostCityProfile, ConcurrentHashMap<Long, TileState>> byProfile =
            TILES.computeIfAbsent(provider, ignored -> new ConcurrentHashMap<>());
        ConcurrentHashMap<Long, TileState> tiles =
            byProfile.computeIfAbsent(profile, ignored -> new ConcurrentHashMap<>());
        TileState state = tiles.get(tileKey);
        boolean owner = false;
        if (state == null) {
            TileState created = new TileState();
            TileState previous = tiles.putIfAbsent(tileKey, created);
            state = previous == null ? created : previous;
            owner = previous == null;
        }

        BuildResult result;
        if (owner) {
            try {
                result = build(builder, tileX * TILE_SIDE, tileZ * TILE_SIDE);
                state.future.complete(result);
            } catch (RuntimeException | Error failure) {
                state.future.completeExceptionally(failure);
                tiles.remove(tileKey, state);
                prune(provider, profile, byProfile, tiles);
                throw failure;
            }
            if (result.cacheable()) {
                ORDER.offer(new TileToken(provider, profile, tiles, tileKey, state));
                TILE_COUNT.incrementAndGet();
                trim();
            } else {
                tiles.remove(tileKey, state);
                prune(provider, profile, byProfile, tiles);
            }
        } else {
            result = state.future.getNow(null);
            if (result == null) {
                if (allowWait) {
                    try {
                        result = state.future.join();
                    } catch (CompletionException failure) {
                        tiles.remove(tileKey, state);
                        throw failure;
                    }
                } else {
                    // A planner never parks behind a vanilla caller. Its local
                    // exact tile is still reused for every following cell.
                    result = build(builder, tileX * TILE_SIDE, tileZ * TILE_SIDE);
                }
            }
        }

        LOCAL.set(new LocalTile(epoch, new WeakReference<>(provider),
            new WeakReference<>(profile), tileKey, result.values()));
        return result.values()[index(chunkX, chunkZ)];
    }

    public static void clear() {
        EPOCH.incrementAndGet();
        TILES.clear();
        ORDER.clear();
        TILE_COUNT.set(0);
        LOCAL.remove();
    }

    private static BuildResult build(Builder builder, int originX, int originZ) {
        BuildResult result;
        try {
            result = builder.build(originX, originZ, TILE_SIDE);
        } catch (RuntimeException | Error failure) {
            throw failure;
        }
        if (result == null || result.values() == null
            || result.values().length != TILE_SIDE * TILE_SIDE) {
            throw new IllegalStateException("Invalid normal city-factor tile");
        }
        return result;
    }

    private static int index(int chunkX, int chunkZ) {
        return Math.floorMod(chunkZ, TILE_SIDE) * TILE_SIDE
            + Math.floorMod(chunkX, TILE_SIDE);
    }

    private static void trim() {
        while (TILE_COUNT.get() > MAX_TILES) {
            TileToken oldest = ORDER.poll();
            if (oldest == null) {
                return;
            }
            if (!oldest.tiles.remove(oldest.tileKey, oldest.state)) {
                continue;
            }
            TILE_COUNT.decrementAndGet();
            ConcurrentHashMap<LostCityProfile, ConcurrentHashMap<Long, TileState>> byProfile =
                TILES.get(oldest.provider);
            if (byProfile != null) {
                prune(oldest.provider, oldest.profile, byProfile, oldest.tiles);
            }
        }
    }

    private static void prune(IDimensionInfo provider,
                              LostCityProfile profile,
                              ConcurrentHashMap<LostCityProfile, ConcurrentHashMap<Long, TileState>> byProfile,
                              ConcurrentHashMap<Long, TileState> tiles) {
        if (tiles.isEmpty()) {
            byProfile.remove(profile, tiles);
            if (byProfile.isEmpty()) {
                TILES.remove(provider, byProfile);
            }
        }
    }

    @FunctionalInterface
    public interface Builder {
        BuildResult build(int originX, int originZ, int side);
    }

    public record BuildResult(float[] values, boolean cacheable) {
    }

    private static final class TileState {
        private final CompletableFuture<BuildResult> future = new CompletableFuture<>();
    }

    private record TileToken(IDimensionInfo provider,
                             LostCityProfile profile,
                             ConcurrentHashMap<Long, TileState> tiles,
                             long tileKey,
                             TileState state) {
    }

    private record LocalTile(long epoch,
                             WeakReference<IDimensionInfo> provider,
                             WeakReference<LostCityProfile> profile,
                             long tileKey,
                             float[] values) {
        private boolean matches(long expectedEpoch,
                                IDimensionInfo expectedProvider,
                                LostCityProfile expectedProfile,
                                long expectedTileKey) {
            return epoch == expectedEpoch
                && provider.get() == expectedProvider
                && profile.get() == expectedProfile
                && tileKey == expectedTileKey;
        }
    }
}
