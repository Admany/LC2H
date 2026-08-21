package org.admany.lc2h.worldgen.lostcities;

public final class HighwayHeightmapPrefetchContext {
    private static final ThreadLocal<State> STATE = new ThreadLocal<>();

    private HighwayHeightmapPrefetchContext() {
    }

    public static void begin(int[] candidates) {
        State state = STATE.get();
        if (state == null) {
            STATE.set(new State(1, candidates));
        } else {
            state.depth++;
            if (candidates != null && candidates.length > 0) {
                state.candidates = candidates;
            }
        }
    }

    public static int[] consume() {
        State state = STATE.get();
        if (state == null) {
            return null;
        }
        int[] candidates = state.candidates;
        state.candidates = new int[0];
        return candidates;
    }

    public static boolean isActive() {
        return STATE.get() != null;
    }

    public static void clear() {
        State state = STATE.get();
        if (state == null || --state.depth <= 0) {
            STATE.remove();
        }
    }

    private static final class State {
        private int depth;
        private int[] candidates;

        private State(int depth, int[] candidates) {
            this.depth = depth;
            this.candidates = candidates == null ? new int[0] : candidates;
        }
    }
}
