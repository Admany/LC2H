package org.admany.lc2h.worldgen.lostcities;

import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Bound uncached railway and highway work in the Lost Cities preview. */
public final class LostCitiesGuiPreviewGuard {
    private static final int MAX_NEW_COMPUTATIONS_PER_FRAME = Math.max(8,
        Integer.getInteger("lc2h.guiPreview.maxNewComputationsPerFrame", 64));
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);
    private static final AtomicLong PREVIEW_REVISION = new AtomicLong();

    private LostCitiesGuiPreviewGuard() {
    }

    public static void beginFrame() {
        State state = STATE.get();
        state.active = true;
        state.newComputations = 0;
    }

    public static void endFrame() {
        State state = STATE.get();
        state.active = false;
    }

    public static void clear() {
        State state = STATE.get();
        state.attempted.clear();
        state.newComputations = 0;
    }

    public static void refreshPreview() {
        PREVIEW_REVISION.incrementAndGet();
        clear();
    }

    public static Object cacheScope(IDimensionInfo provider, LostCityProfile profile) {
        if (!isPreview(provider)) {
            return provider;
        }
        return new PreviewScope(profile, provider.getSeed(), provider.getType(), PREVIEW_REVISION.get());
    }

    public static Object cacheScope(IDimensionInfo provider) {
        return cacheScope(provider, isPreview(provider) ? provider.getProfile() : null);
    }

    public static boolean isPreviewScope(Object scope) {
        return scope instanceof PreviewScope;
    }

    /**
     * @return true when the caller should return its cheap no-result value.
     */
    public static boolean shouldDefer(IDimensionInfo provider,
                                      LostCityProfile profile,
                                      ChunkCoord coord,
                                      String operation) {
        if (!isPreview(provider) || profile == null || coord == null) {
            return false;
        }
        State state = STATE.get();
        if (!state.active) {
            return false;
        }
        PreviewKey key = new PreviewKey(
            operation == null ? "unknown" : operation,
            coord.toString(),
            profile.getName(),
            System.identityHashCode(profile));
        if (state.attempted.contains(key)) {
            // A later frame can use the normal cache-hit path.
            return false;
        }
        if (state.newComputations >= MAX_NEW_COMPUTATIONS_PER_FRAME) {
            return true;
        }
        state.attempted.add(key);
        state.newComputations++;
        return false;
    }

    private static boolean isPreview(IDimensionInfo provider) {
        return provider != null
            && "mcjty.lostcities.gui.NullDimensionInfo".equals(provider.getClass().getName());
    }

    private static final class State {
        private boolean active;
        private int newComputations;
        private final Set<PreviewKey> attempted = new HashSet<>();
    }

    private record PreviewKey(String operation, String coord, String profile, int profileIdentity) {
    }

    private record PreviewScope(LostCityProfile profile, long seed, Object dimension, long revision) {
    }
}
