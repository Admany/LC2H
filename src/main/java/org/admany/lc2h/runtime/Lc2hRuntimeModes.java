package org.admany.lc2h.runtime;

public final class Lc2hRuntimeModes {
    private static final boolean BASELINE_MODE = Boolean.parseBoolean(System.getProperty("lc2h.baselineMode", "false"));
    private static final boolean WORLD_PARITY_AUTO = Boolean.parseBoolean(System.getProperty("lc2h.worldparity.auto", "false"));
    private static final boolean MULTICHUNK_PARITY_AUTO = Boolean.parseBoolean(System.getProperty("lc2h.parity.auto", "false"));

    private Lc2hRuntimeModes() {
    }

    public static boolean baselineMode() {
        return BASELINE_MODE;
    }

    public static boolean worldParityAuto() {
        return WORLD_PARITY_AUTO;
    }

    public static boolean anyWorldParityRun() {
        return WORLD_PARITY_AUTO;
    }

    public static boolean multiChunkParityAuto() {
        return MULTICHUNK_PARITY_AUTO;
    }

    public static boolean anyParityAutorun() {
        return WORLD_PARITY_AUTO || MULTICHUNK_PARITY_AUTO;
    }

    public static boolean isolatedWorldParityBaseline() {
        return BASELINE_MODE && WORLD_PARITY_AUTO;
    }

    public static String modeSummary() {
        if (BASELINE_MODE) {
            return "baseline";
        }
        if (WORLD_PARITY_AUTO) {
            return "worldparity";
        }
        if (MULTICHUNK_PARITY_AUTO) {
            return "multichunk-parity";
        }
        return "normal";
    }
}
