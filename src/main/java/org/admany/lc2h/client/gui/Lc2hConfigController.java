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

    void requestBenchmarkStart() {
        if (!Minecraft.getInstance().hasSingleplayerServer()) {
            statusMessage = Component.literal("Benchmark not available on dedicated servers.");
            statusColor = 0xFF5555;
            return;
        }
        BenchmarkManager.BenchmarkStatus status = BenchmarkManager.requestStart();
        statusMessage = status.message();
        statusColor = status.color();
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

    boolean applyToggleChange(Minecraft minecraft, boolean showStatus) {
        ConfigManager.Config baseConfig = ConfigManager.CONFIG != null ? ConfigManager.CONFIG : initialConfig;
        ConfigManager.Config updated = toConfig(uiState, baseConfig);
        return applyConfig(updated, minecraft, baseConfig, showStatus);
    }

    private boolean applyChanges(FormValues values, Minecraft minecraft, boolean showStatus) {
        if (values == null) {
            return false;
        }
        tryParseInt(values.cityBlendWidth(), v -> uiState.cityBlendWidth = Math.max(4, v));
        tryParseDouble(values.cityBlendSoftness(), v -> uiState.cityBlendSoftness = Math.max(0.5, v));

        ConfigManager.Config baseConfig = ConfigManager.CONFIG != null ? ConfigManager.CONFIG : initialConfig;
        ConfigManager.Config updated = toConfig(uiState, baseConfig);
        return applyConfig(updated, minecraft, baseConfig, showStatus);
    }

    private boolean applyConfig(ConfigManager.Config updated, Minecraft minecraft, ConfigManager.Config baseConfig, boolean showStatus) {
        boolean needsRestart = requiresRestartForChanges(baseConfig, updated);
        boolean isDedicated = minecraft != null && minecraft.getSingleplayerServer() == null;
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
        c.cityBlendEnabled = src.cityBlendEnabled;
        c.cityBlendWidth = src.cityBlendWidth;
        c.cityBlendSoftness = src.cityBlendSoftness;
        c.cityBlendClearTrees = src.cityBlendClearTrees;
        return c;
    }

    private UiState toState(ConfigManager.Config config) {
        UiState state = new UiState();
        if (config == null) {
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
        config.cityBlendEnabled = state.cityBlendEnabled;
        config.cityBlendWidth = state.cityBlendWidth;
        config.cityBlendSoftness = state.cityBlendSoftness;
        config.cityBlendClearTrees = state.cityBlendClearTrees;
        return config;
    }

    record FormValues(
        String cityBlendWidth,
        String cityBlendSoftness
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
        public boolean cityBlendEnabled;
        public int cityBlendWidth;
        public double cityBlendSoftness;
        public boolean cityBlendClearTrees;
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
