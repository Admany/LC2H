package org.admany.lc2h.client.gui;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.benchmark.BenchmarkManager;
import org.admany.lc2h.logging.config.ConfigManager;
import org.admany.lc2h.network.ConfigSyncNetwork;

import java.nio.file.Files;
import java.nio.file.Path;

final class Lc2hConfigController {
    static final boolean RESTART_CITY_EDGE = true;
    static final int CACHE_CAP_MIN_MB = 64;
    static final int CACHE_CAP_MAX_MB = 16384;
    static final int CACHE_TTL_MIN_MINUTES = 1;
    static final int CACHE_TTL_MAX_MINUTES = 10_000;
    private final ConfigManager.Config initialConfig;
    private final UiState uiState;

    private Component statusMessage = Component.empty();
    private int statusColor = 0x55FF55;

    private boolean listenerRegistered;
    private final BenchmarkManager.StatusListener benchmarkListener = status -> {
        if (status != null) {
            statusMessage = status.message();
            statusColor = status.color();
        }
    };

    Lc2hConfigController() {
        ConfigManager.Config base = ConfigManager.CONFIG != null ? ConfigManager.CONFIG : new ConfigManager.Config();
        this.initialConfig = copyConfig(base);
        this.uiState = toState(base);
    }

    Component getStatusMessage() {
        return statusMessage;
    }

    int getStatusColor() {
        return statusColor;
    }

    void setStatus(Component message, int color) {
        statusMessage = message;
        statusColor = color;
    }

    void registerBenchmarkListener() {
        if (!listenerRegistered) {
            BenchmarkManager.addListener(benchmarkListener);
            listenerRegistered = true;
        }
    }

    void unregisterBenchmarkListener() {
        if (listenerRegistered) {
            BenchmarkManager.removeListener(benchmarkListener);
            listenerRegistered = false;
        }
    }

    boolean requestBenchmarkStart() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            statusMessage = Component.literal("You need to be in a world to run the benchmark.");
            statusColor = 0xFF5555;
            return false;
        }
        BenchmarkManager.BenchmarkStatus status = BenchmarkManager.requestStart();
        statusMessage = status.message();
        statusColor = status.color();
        return status.stage() == BenchmarkManager.Stage.PREPARING || status.stage() == BenchmarkManager.Stage.RUNNING;
    }

    boolean isBenchmarkRunning() {
        return BenchmarkManager.isRunning();
    }

    void requestUserCancelFromClient(String reason) {
        BenchmarkManager.requestUserCancelFromClient(reason);
    }

    void cancelBenchmarkFeedback(Component message, int color) {
        statusMessage = message;
        statusColor = color;
    }

    UiState getUiState() {
        return uiState;
    }

    boolean hasUnsavedChanges() {
        ConfigManager.Config baseConfig = ConfigManager.CONFIG != null ? ConfigManager.CONFIG : initialConfig;
        ConfigManager.Config pending = toConfig(uiState, baseConfig);
        return !configsEqual(baseConfig, pending);
    }

    boolean hasUnsavedChanges(FormValues values) {
        ConfigManager.Config baseConfig = ConfigManager.CONFIG != null ? ConfigManager.CONFIG : initialConfig;
        UiState pendingState = copyState(uiState);
        applyFormValuesToState(pendingState, values);
        ConfigManager.Config pending = toConfig(pendingState, baseConfig);
        return !configsEqual(baseConfig, pending);
    }

    void playClickSound() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getSoundManager() == null) {
            return;
        }
        if (LC2H.BUTTON_CLICK_SOUND != null && LC2H.BUTTON_CLICK_SOUND.isPresent()) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(LC2H.BUTTON_CLICK_SOUND.get(), 1.0F));
        }
    }

    void openConfigFile() {
        Path configPath = FMLPaths.CONFIGDIR.get().resolve("lc2h").resolve("lc2h_config.json");
        try {
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath.getParent());
                ConfigManager.writePrettyJsonConfig(ConfigManager.CONFIG != null ? ConfigManager.CONFIG : new ConfigManager.Config());
            }
            Util.getPlatform().openFile(configPath.toFile());
            statusMessage = Component.literal("Opened config file in system editor");
            statusColor = 0x55FF55;
        } catch (Exception ex) {
            statusMessage = Component.literal("Failed to open config file: " + ex.getMessage());
            statusColor = 0xFF5555;
        }
    }

    boolean applyChanges(FormValues values, Minecraft minecraft) {
        return applyChanges(values, minecraft, true);
    }

    CacheCapChange inspectCacheCapChange(FormValues values) {
        if (values == null) {
            return null;
        }
        ConfigManager.Config baseConfig = ConfigManager.CONFIG != null ? ConfigManager.CONFIG : initialConfig;
        int currentCombined = baseConfig != null ? clampCacheMaxMb(baseConfig.cacheCombinedMaxMB) : clampCacheMaxMb(ConfigManager.CACHE_COMBINED_MAX_MB);
        int currentLc2h = baseConfig != null ? clampCacheMaxMb(baseConfig.cacheMaxMB) : clampCacheMaxMb(ConfigManager.CACHE_MAX_MB);
        int currentLost = baseConfig != null ? clampCacheMaxMb(baseConfig.cacheLostCitiesMaxMB) : clampCacheMaxMb(ConfigManager.LOSTCITIES_CACHE_MAX_MB);
        boolean currentEnforce = baseConfig != null ? baseConfig.cacheEnforceCombinedMax : ConfigManager.CACHE_ENFORCE_COMBINED_MAX;
        boolean currentSplit = baseConfig != null ? baseConfig.cacheSplitEqual : ConfigManager.CACHE_SPLIT_EQUAL;
        int currentTtl = baseConfig != null ? baseConfig.cacheLostCitiesTtlMinutes : ConfigManager.LOSTCITIES_CACHE_TTL_MINUTES;
        int currentDiskTtl = baseConfig != null ? baseConfig.cacheLostCitiesDiskTtlHours : ConfigManager.LOSTCITIES_CACHE_DISK_TTL_HOURS;

        Integer reqCombined = parseCacheMaxMb(values.cacheCombinedMaxMB());
        Integer reqLc2h = parseCacheMaxMb(values.cacheMaxMB());
        Integer reqLost = parseCacheMaxMb(values.cacheLostCitiesMaxMB());
        Integer reqTtl = parseCacheMaxMb(values.cacheLostCitiesTtlMinutes());
        Integer reqDiskTtl = parseCacheMaxMb(values.cacheLostCitiesDiskTtlHours());

        int requestedCombined = reqCombined != null ? clampCacheMaxMb(reqCombined) : currentCombined;
        int requestedLc2h = reqLc2h != null ? clampCacheMaxMb(reqLc2h) : currentLc2h;
        int requestedLost = reqLost != null ? clampCacheMaxMb(reqLost) : currentLost;
        int requestedTtl = reqTtl != null ? clampIntRange(reqTtl, CACHE_TTL_MIN_MINUTES, CACHE_TTL_MAX_MINUTES) : currentTtl;
        int requestedDiskTtl = reqDiskTtl != null ? clampIntRange(reqDiskTtl, CACHE_TTL_MIN_MINUTES, CACHE_TTL_MAX_MINUTES) : currentDiskTtl;

        StringBuilder summary = new StringBuilder();
        if (requestedCombined != currentCombined) {
            appendSummary(summary, "Combined: " + currentCombined + " -> " + requestedCombined + " MB");
        }
        if (requestedLc2h != currentLc2h) {
            appendSummary(summary, "LC2H: " + currentLc2h + " -> " + requestedLc2h + " MB");
        }
        if (requestedLost != currentLost) {
            appendSummary(summary, "Lost Cities: " + currentLost + " -> " + requestedLost + " MB");
        }
        if (currentEnforce != uiState.cacheEnforceCombinedMax) {
            appendSummary(summary, "Combined enforcement: " + (currentEnforce ? "on" : "off") + " -> " + (uiState.cacheEnforceCombinedMax ? "on" : "off"));
        }
        if (currentSplit != uiState.cacheSplitEqual) {
            appendSummary(summary, "Split evenly: " + (currentSplit ? "on" : "off") + " -> " + (uiState.cacheSplitEqual ? "on" : "off"));
        }
        if (requestedTtl != currentTtl) {
            appendSummary(summary, "Lost Cities RAM TTL: " + currentTtl + " -> " + requestedTtl + " min");
        }
        if (requestedDiskTtl != currentDiskTtl) {
            appendSummary(summary, "Lost Cities disk TTL: " + currentDiskTtl + " -> " + requestedDiskTtl + " hr");
        }
        if (summary.length() == 0) {
            return null;
        }
        return new CacheCapChange(summary.toString());
    }

    int getCurrentCacheMaxMb() {
        ConfigManager.Config baseConfig = ConfigManager.CONFIG != null ? ConfigManager.CONFIG : initialConfig;
        if (baseConfig == null) {
            return clampCacheMaxMb(ConfigManager.CACHE_COMBINED_MAX_MB);
        }
        return clampCacheMaxMb(baseConfig.cacheCombinedMaxMB);
    }

    boolean applyToggleChange(Minecraft minecraft, boolean showStatus) {
        ConfigManager.Config baseConfig = ConfigManager.CONFIG != null ? ConfigManager.CONFIG : initialConfig;
        ConfigManager.Config updated = toConfig(uiState, baseConfig);
        return applyConfig(updated, minecraft, baseConfig, showStatus);
    }

    private boolean applyChanges(FormValues values, Minecraft minecraft, boolean showStatus) {
        if (values == null) {
            return false;
        }
        applyFormValuesToState(uiState, values);

        ConfigManager.Config baseConfig = ConfigManager.CONFIG != null ? ConfigManager.CONFIG : initialConfig;
        ConfigManager.Config updated = toConfig(uiState, baseConfig);
        return applyConfig(updated, minecraft, baseConfig, showStatus);
    }

    private boolean applyConfig(ConfigManager.Config updated, Minecraft minecraft, ConfigManager.Config baseConfig, boolean showStatus) {
        boolean needsRestart = requiresRestartForChanges(baseConfig, updated);
        boolean isDedicated = minecraft != null && minecraft.level != null && minecraft.getSingleplayerServer() == null;
        if (isDedicated) {
            boolean sent = ConfigSyncNetwork.sendApplyRequest(updated);
            if (showStatus) {
                if (sent) {
                    statusMessage = Component.literal("Sent config changes to server (OP only).");
                    statusColor = 0x55FF55;
                } else {
                    statusMessage = Component.literal("Failed to send config to server. Ensure config sync is reachable.");
                    statusColor = 0xFF5555;
                }
            }
            return needsRestart;
        }

        ConfigManager.writePrettyJsonConfig(updated);
        ConfigManager.CONFIG = updated;
        ConfigManager.initializeGlobals();

        if (showStatus) {
            if (needsRestart && (minecraft == null || minecraft.getSingleplayerServer() == null)) {
                statusMessage = Component.literal("Saved. One or more changes require a server restart to take effect.");
                statusColor = 0xFF5555;
            } else {
                statusMessage = Component.literal("Saved and applied.");
                statusColor = 0x55FF55;
            }
        }
        return needsRestart;
    }

    private static boolean requiresRestartForChanges(ConfigManager.Config before, ConfigManager.Config after) {
        if (!RESTART_CITY_EDGE || before == null || after == null) {
            return false;
        }
        if (before.cityBlendEnabled != after.cityBlendEnabled) return true;
        if (before.cityBlendWidth != after.cityBlendWidth) return true;
        if (Double.compare(before.cityBlendSoftness, after.cityBlendSoftness) != 0) return true;
        return before.cityBlendClearTrees != after.cityBlendClearTrees;
    }

    private void tryParseInt(String value, IntConsumer consumer) {
        if (value == null) {
            return;
        }
        try {
            consumer.accept(Integer.parseInt(value.trim()));
        } catch (Exception ignored) {
        }
    }

    private void tryParseDouble(String value, DoubleConsumer consumer) {
        if (value == null) {
            return;
        }
        try {
            consumer.accept(Double.parseDouble(value.trim()));
        } catch (Exception ignored) {
        }
    }

    private void tryParseCacheMaxMb(String value, IntConsumer consumer) {
        Integer parsed = parseCacheMaxMb(value);
        if (parsed == null) {
            return;
        }
        consumer.accept(clampCacheMaxMb(parsed));
    }

    private void applyFormValuesToState(UiState target, FormValues values) {
        if (target == null || values == null) {
            return;
        }
        tryParseInt(values.cityBlendWidth(), v -> target.cityBlendWidth = Math.max(4, v));
        tryParseDouble(values.cityBlendSoftness(), v -> target.cityBlendSoftness = Math.max(0.5, v));
        tryParseCacheMaxMb(values.cacheCombinedMaxMB(), v -> target.cacheCombinedMaxMB = v);
        tryParseCacheMaxMb(values.cacheMaxMB(), v -> target.cacheMaxMB = v);
        tryParseCacheMaxMb(values.cacheLostCitiesMaxMB(), v -> target.cacheLostCitiesMaxMB = v);
        tryParseInt(values.cacheLostCitiesTtlMinutes(), v -> target.cacheLostCitiesTtlMinutes = clampIntRange(v, CACHE_TTL_MIN_MINUTES, CACHE_TTL_MAX_MINUTES));
        tryParseInt(values.cacheLostCitiesDiskTtlHours(), v -> target.cacheLostCitiesDiskTtlHours = clampIntRange(v, CACHE_TTL_MIN_MINUTES, CACHE_TTL_MAX_MINUTES));
        String normalizedAccent = normalizeHexColor(values.uiAccentColor());
        if (normalizedAccent != null) {
            target.uiAccentColor = normalizedAccent;
        }
    }

    private UiState copyState(UiState src) {
        UiState copy = new UiState();
        if (src == null) {
            return copy;
        }
        copy.enableAsyncDoubleBlockBatcher = src.enableAsyncDoubleBlockBatcher;
        copy.enableLostCitiesGenerationLock = src.enableLostCitiesGenerationLock;
        copy.enableLostCitiesPartSliceCompat = src.enableLostCitiesPartSliceCompat;
        copy.enableCacheStatsLogging = src.enableCacheStatsLogging;
        copy.enableFloatingVegetationRemoval = src.enableFloatingVegetationRemoval;
        copy.enableExplosionDebris = src.enableExplosionDebris;
        copy.hideExperimentalWarning = src.hideExperimentalWarning;
        copy.enableDebugLogging = src.enableDebugLogging;
        copy.uiAccentColor = src.uiAccentColor;
        copy.cacheMaxMB = src.cacheMaxMB;
        copy.cacheLostCitiesMaxMB = src.cacheLostCitiesMaxMB;
        copy.cacheCombinedMaxMB = src.cacheCombinedMaxMB;
        copy.cacheEnforceCombinedMax = src.cacheEnforceCombinedMax;
        copy.cacheSplitEqual = src.cacheSplitEqual;
        copy.cacheLostCitiesTtlMinutes = src.cacheLostCitiesTtlMinutes;
        copy.cacheLostCitiesDiskTtlHours = src.cacheLostCitiesDiskTtlHours;
        copy.cityBlendEnabled = src.cityBlendEnabled;
        copy.cityBlendWidth = src.cityBlendWidth;
        copy.cityBlendSoftness = src.cityBlendSoftness;
        copy.cityBlendClearTrees = src.cityBlendClearTrees;
        return copy;
    }

    private Integer parseCacheMaxMb(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int clampCacheMaxMb(int value) {
        return Math.max(CACHE_CAP_MIN_MB, Math.min(CACHE_CAP_MAX_MB, value));
    }

    private void appendSummary(StringBuilder sb, String entry) {
        if (sb.length() > 0) {
            sb.append("; ");
        }
        sb.append(entry);
    }

    private int clampIntRange(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String normalizeHexColor(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            trimmed = trimmed.substring(2);
        }
        if (trimmed.length() == 3) {
            char r = trimmed.charAt(0);
            char g = trimmed.charAt(1);
            char b = trimmed.charAt(2);
            trimmed = "" + r + r + g + g + b + b;
        }
        if (trimmed.length() != 6) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return null;
            }
        }
        return trimmed.toUpperCase();
    }

    private ConfigManager.Config copyConfig(ConfigManager.Config src) {
        if (src == null) {
            return new ConfigManager.Config();
        }
        ConfigManager.Config c = new ConfigManager.Config();
        c.enableAsyncDoubleBlockBatcher = src.enableAsyncDoubleBlockBatcher;
        c.enableLostCitiesGenerationLock = src.enableLostCitiesGenerationLock;
        c.enableLostCitiesPartSliceCompat = src.enableLostCitiesPartSliceCompat;
        c.enableCacheStatsLogging = src.enableCacheStatsLogging;
        c.enableFloatingVegetationRemoval = src.enableFloatingVegetationRemoval;
        c.enableExplosionDebris = src.enableExplosionDebris;
        c.hideExperimentalWarning = src.hideExperimentalWarning;
        c.enableDebugLogging = src.enableDebugLogging;
        c.uiAccentColor = src.uiAccentColor;
        c.cacheMaxMB = src.cacheMaxMB;
        c.cacheLostCitiesMaxMB = src.cacheLostCitiesMaxMB;
        c.cacheCombinedMaxMB = src.cacheCombinedMaxMB;
        c.cacheEnforceCombinedMax = src.cacheEnforceCombinedMax;
        c.cacheSplitEqual = src.cacheSplitEqual;
        c.cacheLostCitiesTtlMinutes = src.cacheLostCitiesTtlMinutes;
        c.cacheLostCitiesDiskTtlHours = src.cacheLostCitiesDiskTtlHours;
        c.cityBlendEnabled = src.cityBlendEnabled;
        c.cityBlendWidth = src.cityBlendWidth;
        c.cityBlendSoftness = src.cityBlendSoftness;
        c.cityBlendClearTrees = src.cityBlendClearTrees;
        return c;
    }

    private UiState toState(ConfigManager.Config config) {
        UiState state = new UiState();
        if (config == null) {
            state.cacheCombinedMaxMB = clampCacheMaxMb(ConfigManager.CACHE_COMBINED_MAX_MB);
            state.cacheMaxMB = clampCacheMaxMb(ConfigManager.CACHE_MAX_MB);
            state.cacheLostCitiesMaxMB = clampCacheMaxMb(ConfigManager.LOSTCITIES_CACHE_MAX_MB);
            state.cacheEnforceCombinedMax = ConfigManager.CACHE_ENFORCE_COMBINED_MAX;
            state.cacheSplitEqual = ConfigManager.CACHE_SPLIT_EQUAL;
            state.cacheLostCitiesTtlMinutes = clampIntRange(ConfigManager.LOSTCITIES_CACHE_TTL_MINUTES, CACHE_TTL_MIN_MINUTES, CACHE_TTL_MAX_MINUTES);
            state.cacheLostCitiesDiskTtlHours = clampIntRange(ConfigManager.LOSTCITIES_CACHE_DISK_TTL_HOURS, CACHE_TTL_MIN_MINUTES, CACHE_TTL_MAX_MINUTES);
            return state;
        }
        state.enableAsyncDoubleBlockBatcher = config.enableAsyncDoubleBlockBatcher;
        state.enableLostCitiesGenerationLock = config.enableLostCitiesGenerationLock;
        state.enableLostCitiesPartSliceCompat = config.enableLostCitiesPartSliceCompat;
        state.enableCacheStatsLogging = config.enableCacheStatsLogging;
        state.enableFloatingVegetationRemoval = config.enableFloatingVegetationRemoval;
        state.enableExplosionDebris = config.enableExplosionDebris;
        state.hideExperimentalWarning = config.hideExperimentalWarning;
        state.enableDebugLogging = config.enableDebugLogging;
        state.uiAccentColor = config.uiAccentColor;
        state.cacheMaxMB = clampCacheMaxMb(config.cacheMaxMB);
        state.cacheLostCitiesMaxMB = clampCacheMaxMb(config.cacheLostCitiesMaxMB);
        state.cacheCombinedMaxMB = clampCacheMaxMb(config.cacheCombinedMaxMB);
        state.cacheEnforceCombinedMax = config.cacheEnforceCombinedMax;
        state.cacheSplitEqual = config.cacheSplitEqual;
        state.cacheLostCitiesTtlMinutes = clampIntRange(config.cacheLostCitiesTtlMinutes, CACHE_TTL_MIN_MINUTES, CACHE_TTL_MAX_MINUTES);
        state.cacheLostCitiesDiskTtlHours = clampIntRange(config.cacheLostCitiesDiskTtlHours, CACHE_TTL_MIN_MINUTES, CACHE_TTL_MAX_MINUTES);
        state.cityBlendEnabled = config.cityBlendEnabled;
        state.cityBlendWidth = config.cityBlendWidth;
        state.cityBlendSoftness = config.cityBlendSoftness;
        state.cityBlendClearTrees = config.cityBlendClearTrees;
        return state;
    }

    private ConfigManager.Config toConfig(UiState state, ConfigManager.Config base) {
        ConfigManager.Config config = copyConfig(base);
        config.enableAsyncDoubleBlockBatcher = state.enableAsyncDoubleBlockBatcher;
        config.enableLostCitiesGenerationLock = state.enableLostCitiesGenerationLock;
        config.enableLostCitiesPartSliceCompat = state.enableLostCitiesPartSliceCompat;
        config.enableCacheStatsLogging = state.enableCacheStatsLogging;
        config.enableFloatingVegetationRemoval = state.enableFloatingVegetationRemoval;
        config.enableExplosionDebris = state.enableExplosionDebris;
        config.hideExperimentalWarning = state.hideExperimentalWarning;
        config.enableDebugLogging = state.enableDebugLogging;
        config.uiAccentColor = state.uiAccentColor;
        config.cacheMaxMB = state.cacheMaxMB;
        config.cacheLostCitiesMaxMB = state.cacheLostCitiesMaxMB;
        config.cacheCombinedMaxMB = state.cacheCombinedMaxMB;
        config.cacheEnforceCombinedMax = state.cacheEnforceCombinedMax;
        config.cacheSplitEqual = state.cacheSplitEqual;
        config.cacheLostCitiesTtlMinutes = state.cacheLostCitiesTtlMinutes;
        config.cacheLostCitiesDiskTtlHours = state.cacheLostCitiesDiskTtlHours;
        config.cityBlendEnabled = state.cityBlendEnabled;
        config.cityBlendWidth = state.cityBlendWidth;
        config.cityBlendSoftness = state.cityBlendSoftness;
        config.cityBlendClearTrees = state.cityBlendClearTrees;
        return config;
    }

    private boolean configsEqual(ConfigManager.Config a, ConfigManager.Config b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.enableAsyncDoubleBlockBatcher == b.enableAsyncDoubleBlockBatcher
            && a.enableLostCitiesGenerationLock == b.enableLostCitiesGenerationLock
            && a.enableLostCitiesPartSliceCompat == b.enableLostCitiesPartSliceCompat
            && a.enableCacheStatsLogging == b.enableCacheStatsLogging
            && a.enableFloatingVegetationRemoval == b.enableFloatingVegetationRemoval
            && a.enableExplosionDebris == b.enableExplosionDebris
            && a.hideExperimentalWarning == b.hideExperimentalWarning
            && a.enableDebugLogging == b.enableDebugLogging
            && safeEquals(a.uiAccentColor, b.uiAccentColor)
            && a.cacheMaxMB == b.cacheMaxMB
            && a.cacheLostCitiesMaxMB == b.cacheLostCitiesMaxMB
            && a.cacheCombinedMaxMB == b.cacheCombinedMaxMB
            && a.cacheEnforceCombinedMax == b.cacheEnforceCombinedMax
            && a.cacheSplitEqual == b.cacheSplitEqual
            && a.cacheLostCitiesTtlMinutes == b.cacheLostCitiesTtlMinutes
            && a.cacheLostCitiesDiskTtlHours == b.cacheLostCitiesDiskTtlHours
            && a.cityBlendEnabled == b.cityBlendEnabled
            && a.cityBlendWidth == b.cityBlendWidth
            && Double.compare(a.cityBlendSoftness, b.cityBlendSoftness) == 0
            && a.cityBlendClearTrees == b.cityBlendClearTrees;
    }

    private boolean safeEquals(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    record FormValues(
        String cityBlendWidth,
        String cityBlendSoftness,
        String uiAccentColor,
        String cacheMaxMB,
        String cacheLostCitiesMaxMB,
        String cacheCombinedMaxMB,
        String cacheLostCitiesTtlMinutes,
        String cacheLostCitiesDiskTtlHours
    ) {
    }

    static final class UiState {
        public boolean enableAsyncDoubleBlockBatcher;
        public boolean enableLostCitiesGenerationLock;
        public boolean enableLostCitiesPartSliceCompat;
        public boolean enableCacheStatsLogging;
        public boolean enableFloatingVegetationRemoval;
        public boolean enableExplosionDebris;
        public boolean hideExperimentalWarning;
        public boolean enableDebugLogging;
        public String uiAccentColor;
        public int cacheMaxMB;
        public int cacheLostCitiesMaxMB;
        public int cacheCombinedMaxMB;
        public boolean cacheEnforceCombinedMax;
        public boolean cacheSplitEqual;
        public int cacheLostCitiesTtlMinutes;
        public int cacheLostCitiesDiskTtlHours;
        public boolean cityBlendEnabled;
        public int cityBlendWidth;
        public double cityBlendSoftness;
        public boolean cityBlendClearTrees;
    }

    record CacheCapChange(String summary) {
    }

    @FunctionalInterface
    private interface IntConsumer {
        void accept(int value);
    }

    @FunctionalInterface
    private interface DoubleConsumer {
        void accept(double value);
    }
}
