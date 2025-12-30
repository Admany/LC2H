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

    void applyChanges(FormValues values, Minecraft minecraft) {
        if (values == null) {
            return;
        }
        tryParseInt(values.cityBlendWidth(), v -> uiState.cityBlendWidth = Math.max(4, v));
        tryParseDouble(values.cityBlendSoftness(), v -> uiState.cityBlendSoftness = Math.max(0.5, v));

        ConfigManager.Config baseConfig = ConfigManager.CONFIG != null ? ConfigManager.CONFIG : initialConfig;
        ConfigManager.Config updated = toConfig(uiState, baseConfig);
        boolean needsRestart = false;

        boolean isDedicated = minecraft != null && minecraft.getSingleplayerServer() == null;
        if (isDedicated) {
            boolean sent = ConfigSyncNetwork.sendApplyRequest(updated);
            if (sent) {
                statusMessage = Component.literal("Sent config changes to server (OP only).");
                statusColor = 0x55FF55;
            } else {
                statusMessage = Component.literal("Failed to send config to server. Ensure config sync is reachable.");
                statusColor = 0xFF5555;
            }
            return;
        }

        ConfigManager.writePrettyJsonConfig(updated);
        ConfigManager.CONFIG = updated;
        ConfigManager.initializeGlobals();

        if (needsRestart && (minecraft == null || minecraft.getSingleplayerServer() == null)) {
            statusMessage = Component.literal("Saved. One or more changes require a server restart to take effect.");
            statusColor = 0xFF5555;
        } else {
            statusMessage = Component.literal("Saved and applied.");
            statusColor = 0x55FF55;
        }
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
        c.enableScatteredParts2OverlayFix = src.enableScatteredParts2OverlayFix;
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
        state.enableScatteredParts2OverlayFix = config.enableScatteredParts2OverlayFix;
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
        config.enableScatteredParts2OverlayFix = state.enableScatteredParts2OverlayFix;
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
        public boolean enableScatteredParts2OverlayFix;
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
