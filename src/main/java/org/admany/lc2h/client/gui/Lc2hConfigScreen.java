package org.admany.lc2h.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;
import java.util.IdentityHashMap;
import java.util.Map;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.util.FormattedCharSequence;

@OnlyIn(Dist.CLIENT)
public class Lc2hConfigScreen extends Screen {
    private static class CustomButton extends Button {
        public CustomButton(int x, int y, int width, int height, Component message, Button.OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        }

        @Override
        public void onPress() {
            super.onPress();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
        }
    }
    private final Screen parent;
    private final Lc2hConfigController controller;
    private final Lc2hConfigController.UiState uiState;
    private int contentLeft;
    private int contentRight;
	    private int contentWidth;
	    private int contentTop;
	    private int headerBottom;
	    private float scrollOffset = 0f;
	    private int maxScrollOffset = 0;
	    private float targetScrollOffset = 0f;
    private float fadeIn = 0f;
    private int footerY;
    private int contentFullHeight;
    private int viewportHeight;
    private float time = 0f;
    private final List<Particle> particles = new ArrayList<>();
    private final List<Star> stars = new ArrayList<>();

    private static class Particle {
        float x, y, vx, vy, life, maxLife;
        int color;

        Particle(float x, float y, int color) {
            this.x = x;
            this.y = y;
            this.vx = (float) (Math.random() - 0.5) * 0.8f;
            this.vy = (float) (Math.random() - 0.5) * 0.8f;
            this.life = 0f;
            this.maxLife = (float) (Math.random() * 40 + 20);
            this.color = color;
        }

        boolean update() {
            x += vx;
            y += vy;
            life++;
            return life < maxLife;
        }
    }

    private static class Star {
        float x, y, size, brightness, pulseSpeed;
        int baseColor;

        Star(float x, float y) {
            this.x = x;
            this.y = y;
            this.size = (float) (Math.random() * 1.5 + 0.5);
            this.brightness = (float) (Math.random() * 0.7 + 0.3);
            this.pulseSpeed = (float) (Math.random() * 0.05 + 0.02);
            this.baseColor = 0xFFAAAAAA;
        }

        void update(float time) {
            brightness = (float) (0.3 + 0.7 * Math.abs(Math.sin(time * pulseSpeed)));
        }

        int getColor() {
            int alpha = (int) (brightness * 200);
            return (alpha << 24) | (baseColor & 0xFFFFFF);
        }
    }

    public Lc2hConfigScreen(Screen parent) {
        super(Component.literal("《▓ LC²H | Lost Cities Multithreaded ▓》"));
        this.parent = parent;
        this.controller = new Lc2hConfigController();
        this.uiState = controller.getUiState();

        for (int i = 0; i < 25; i++) {
            particles.add(new Particle(0, 0, 0x33AAAAAA));
        }

        for (int i = 0; i < 150; i++) {
            stars.add(new Star(0, 0));
        }
    }

    private EditBox blendWidthBox;
    private EditBox blendSoftnessBox;
    private final List<LabelEntry> labels = new ArrayList<>();
    private final Map<Button, Float> buttonScale = new IdentityHashMap<>();
    private final Map<Button, int[]> buttonRects = new IdentityHashMap<>();
    private final Map<Button, Float> buttonHover = new IdentityHashMap<>();
    private final Map<Button, Float> widgetAlpha = new IdentityHashMap<>();
    private final Map<Button, Float> widgetOffsetY = new IdentityHashMap<>();
    private final Map<Button, Float> fixedButtonHover = new IdentityHashMap<>();
    private final Map<Button, Integer> buttonOriginalY = new IdentityHashMap<>();
    private final Map<EditBox, Integer> textFieldOriginalY = new IdentityHashMap<>();
    private final Map<EditBox, Float> textFieldAlpha = new IdentityHashMap<>();
    private final Map<EditBox, Float> textFieldOffsetY = new IdentityHashMap<>();
    private final Map<Button, Float> buttonPulse = new IdentityHashMap<>();

    private record LabelEntry(int x, int y, int descStartY, Component title,
                              List<FormattedCharSequence> lines, boolean requiresRestart) { }

    private static class LayoutHelper {
        private final int columns;
        private final int columnWidth;
        private final int columnSpacing;
        private final int verticalSpacing;
        private final int startX;
        private final int[] heights;

        LayoutHelper(int startX, int topY, int columns, int columnWidth, int columnSpacing, int verticalSpacing) {
            this.startX = startX;
            this.columns = Math.max(1, columns);
            this.columnWidth = columnWidth;
            this.columnSpacing = columnSpacing;
            this.verticalSpacing = verticalSpacing;
            this.heights = new int[this.columns];
            Arrays.fill(this.heights, topY);
        }

        int columnWidth() {
            return columnWidth;
        }

        Placement place(int height) {
            int column = 0;
            for (int i = 1; i < columns; i++) {
                if (heights[i] < heights[column]) {
                    column = i;
                }
            }
            int y = heights[column];
            heights[column] = y + height + verticalSpacing;
            int x = startX + column * (columnWidth + columnSpacing);
            return new Placement(column, x, y, columnWidth);
        }

        int alignAndGetY() {
            int max = maxHeight();
            Arrays.fill(heights, max);
            return max;
        }

        void bumpAll(int amount) {
            for (int i = 0; i < columns; i++) {
                heights[i] += amount + verticalSpacing;
            }
        }

        int maxHeight() {
            int max = heights[0];
            for (int i = 1; i < columns; i++) {
                if (heights[i] > max) {
                    max = heights[i];
                }
            }
            return max;
        }

        private record Placement(int column, int x, int y, int width) { }
    }

    private record EntryPlacement(int controlX, int controlY, int controlWidth, int controlHeight) { }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();
        this.labels.clear();
        this.buttonScale.clear();
        this.buttonRects.clear();
        this.buttonHover.clear();
        this.fixedButtonHover.clear();
        this.buttonOriginalY.clear();
        this.textFieldOriginalY.clear();
        this.widgetAlpha.clear();
        this.widgetOffsetY.clear();
        this.textFieldAlpha.clear();
        this.textFieldOffsetY.clear();
        this.buttonPulse.clear();

        controller.registerBenchmarkListener();

        final Lc2hConfigController.UiState working = this.uiState;

        int screenW = Math.max(1, this.width);
        int screenH = Math.max(1, this.height);
        for (Particle p : particles) {
            p.x = (float) (Math.random() * screenW);
            p.y = (float) (Math.random() * screenH);
        }
        for (Star s : stars) {
            s.x = (float) (Math.random() * screenW);
            s.y = (float) (Math.random() * screenH);
        }

        int padding = Math.max(12, this.width / 40);
        int calculatedWidth = Math.min(940, this.width - padding * 2);
        if (calculatedWidth < 360) {
            calculatedWidth = Math.max(320, this.width - 48);
        }
        this.contentWidth = Math.max(320, calculatedWidth);
        this.contentLeft = (this.width - this.contentWidth) / 2;
        this.contentRight = this.contentLeft + this.contentWidth;

        int headerTop = Math.max(36, this.height / 12);
        this.headerBottom = headerTop + 76;
        this.contentTop = this.headerBottom + 16;

        int columnCount = this.contentWidth >= 720 ? 2 : 1;
        int columnSpacing = columnCount > 1 ? 20 : 0;
        int columnWidth = (this.contentWidth - (columnCount - 1) * columnSpacing) / columnCount;
        LayoutHelper layout = new LayoutHelper(this.contentLeft, this.contentTop, columnCount, columnWidth, columnSpacing, 8);

        addSectionHeader(layout, Component.literal("GENERAL"));
        addToggle(layout, "Async Double Block Batcher", working.enableAsyncDoubleBlockBatcher,
                "Use async batching for double blocks", false, val -> working.enableAsyncDoubleBlockBatcher = val);
        addToggle(layout, "Floating Vegetation Removal", working.enableFloatingVegetationRemoval,
                "Remove floating vegetation artifacts after terrain generation.", false, val -> working.enableFloatingVegetationRemoval = val);
        addToggle(layout, "Explosion Debris Spill", working.enableExplosionDebris,
                "Allow Lost Cities explosions to scatter rubble into adjacent chunks (can add clutter around streets)", false,
                val -> working.enableExplosionDebris = val);
        addToggle(layout, "Lost Cities Safe Gen Lock (Recommended)", working.enableLostCitiesGenerationLock,
                "Reduces rare Lost Cities chunk-gen bugs (duplicated/bugged chunks). Leave ON unless you're troubleshooting performance.", false,
                val -> working.enableLostCitiesGenerationLock = val);
        addToggle(layout, "Part Safety Checks (Recommended)", working.enableLostCitiesPartSliceCompat,
                "Prevents rare crashes from broken/invalid Lost Cities parts in addon packs. Leave ON unless you're troubleshooting.", false,
                val -> working.enableLostCitiesPartSliceCompat = val);
        addToggle(layout, "Scattered Parts2 Overlay Fix (Recommended)", working.enableScatteredParts2OverlayFix,
                "Fixes a Lost Cities scattered-structure bug that can create stacked duplicate copies 6 blocks higher (addon packs often trigger this).", false,
                val -> working.enableScatteredParts2OverlayFix = val);
        addToggle(layout, "Enable Cache Stats Logging", working.enableCacheStatsLogging,
                "Log cache statistics periodically.", false, val -> working.enableCacheStatsLogging = val);
        addToggle(layout, "Hide Experimental Warning", working.hideExperimentalWarning,
                "Hide the vanilla experimental warning screen for this mod.", false, val -> working.hideExperimentalWarning = val);
        addToggle(layout, "Debug Logging", working.enableDebugLogging,
                "Enable verbose debug logging for warmup/memory operations.", false, val -> working.enableDebugLogging = val);

        /*
        EntryPlacement powerProfilePlacement = prepareEntry(layout, "Power Profile",
                "Select the operational power profile for LC²H. 'BALANCED' is recommended; 'BOOST' prioritizes throughput; 'CONSERVE' minimizes resource usage.",
                false, 180, 20);
        String initialProfile = (working.powerProfile != null && !working.powerProfile.isEmpty())
                ? working.powerProfile
                : (powerProfiles != null && !powerProfiles.isEmpty() ? powerProfiles.get(0) : "N/A");
        working.powerProfile = initialProfile;
        CustomButton profileBtn = createAnimatedButton(powerProfilePlacement.controlX(), powerProfilePlacement.controlY(),
                powerProfilePlacement.controlWidth(), powerProfilePlacement.controlHeight(), Component.literal("Profile: " + initialProfile), b -> {
                    String nextProfile = controller.cyclePowerProfile();
                    if (nextProfile == null || nextProfile.isEmpty()) {
                        nextProfile = powerProfiles != null && !powerProfiles.isEmpty() ? powerProfiles.get(0) : "N/A";
                        working.powerProfile = nextProfile;
                    }
                    b.setMessage(Component.literal("Profile: " + nextProfile));
                });
        profileBtn.active = powerProfiles != null && !powerProfiles.isEmpty();
        addRenderableWidget(profileBtn);

        this.batchSizeBox = addNumberField(layout, "Operation Batch Size",
                "Number of asynchronous operations submitted per batch. Increasing this enhances throughput but increases CPU and memory footprint.",
                String.valueOf(working.batchSize), false);
        this.thresholdBox = addNumberField(layout, "Background Score Threshold",
                "Minimum score to route tasks to background queues (0.0 - 1.0). Applies instantly.",
                String.valueOf(working.backgroundScoreThreshold), false);

        addSectionHeader(layout, Component.literal("THREADING - Performance Tuning"));
        addToggle(layout, "Enable Noise Threading", working.enableNoiseThreading,
                "Toggle noise generator on/off (restart recommended)", true, val -> working.enableNoiseThreading = val);
        addToggle(layout, "Enable Building Threading", working.enableBuildingThreading,
                "Toggle building generator on/off", true, val -> working.enableBuildingThreading = val);
        addToggle(layout, "Enable Cache Threading", working.enableCacheThreading,
                "Toggle cache threading (restart required)", true, val -> working.enableCacheThreading = val);

        EntryPlacement logicalPlacement = prepareEntry(layout, "Use Hyperthreading (Logical Cores)",
                "When enabled, LC²H will utilize logical CPU cores. For dedicated servers, a restart may be required for changes to fully apply.",
                true, 180, 20);
        CustomButton logicalBtn = createAnimatedButton(logicalPlacement.controlX(), logicalPlacement.controlY(),
                logicalPlacement.controlWidth(), logicalPlacement.controlHeight(),
                Component.literal(working.useLogicalCores ? "Logical Cores" : "Physical Only"), b -> {
                    working.useLogicalCores = !working.useLogicalCores;
                    b.setMessage(Component.literal(working.useLogicalCores ? "Logical Cores" : "Physical Only"));
                });
        addRenderableWidget(logicalBtn);

        this.overrideCoreBox = addNumberField(layout, "Override Core Count (0 = auto)",
                "Explicitly set the maximum number of CPU cores LC²H may utilize. '0' will auto-detect; restart recommended after changes.",
                String.valueOf(working.overrideCoreCount), true);
        this.noiseThreadsBox = addNumberField(layout, "Noise Threads",
                "Configure dedicated threads for noise generation. Use '0' to let the system choose; restart is recommended to apply.",
                String.valueOf(working.noiseThreadCount), true);
        this.buildingThreadsBox = addNumberField(layout, "Building Threads",
                "Configure dedicated threads for building generation. Small improvements may produce diminishing returns; restart recommended.",
                String.valueOf(working.buildingThreadCount), true);
        this.cacheThreadsBox = addNumberField(layout, "Cache Threads",
                "Threads dedicated to cache operations; tuning may increase throughput but will consume more resources (restart recommended).",
                String.valueOf(working.cacheThreadCount), true);

        addSectionHeader(layout, Component.literal("PRELOADER - Warmup Engine"));
        addToggle(layout, "Enable Preloader", working.enablePreloader,
                "Toggle player-based preloading on/off.", false, val -> working.enablePreloader = val);
        this.preloaderIntervalBox = addNumberField(layout, "Preloader Interval (seconds)",
                "How often the preloader ticks (seconds). Applies instantly on singleplayer.",
                String.valueOf(working.preloaderIntervalSeconds), false);

        addSectionHeader(layout, Component.literal("ADVANCED SETTINGS"));
        addToggle(layout, "Automated Error Fallback", working.autoFallbackOnError,
                "Automatically fallback to conservative execution on repeated async failures to preserve stability.",
                false, val -> working.autoFallbackOnError = val);
        addToggle(layout, "Enable Cache Stats Logging", working.enableCacheStatsLogging,
                "Log cache statistics periodically.", false, val -> working.enableCacheStatsLogging = val);
        addToggle(layout, "Enable Performance Monitoring", working.enablePerformanceMonitoring,
                "Enable performance monitoring introspection.", false, val -> working.enablePerformanceMonitoring = val);

        */
        addSectionHeader(layout, Component.literal("CITY EDGE"));
        addToggle(layout, "Enable City Blend", working.cityBlendEnabled,
                "Smoothly blend city edges into surrounding terrain. [EXPERIMENTAL]", false, val -> working.cityBlendEnabled = val);
        addToggle(layout, "Clear Trees Near City Border", working.cityBlendClearTrees,
                "Prevent trees from generating close to city borders.", false, val -> working.cityBlendClearTrees = val);
        this.blendWidthBox = addNumberField(layout, "Blend Width (blocks)",
                "How many blocks to blend from city edge into terrain.", String.valueOf(working.cityBlendWidth), false);
        this.blendSoftnessBox = addNumberField(layout, "Blend Softness",
                "Softness of the falloff (higher = softer).", String.valueOf(working.cityBlendSoftness), false);

        addSectionHeader(layout, Component.literal("BENCHMARK - Performance Validation"));
        addActionButton(layout, "Automated Throughput Trial",
                "Creates an automated Lost Cities stress run at 10k×10k to capture chunks/min, TPS range, and freeze counts. Keep hands off inputs during the minute-long sweep.",
                Component.literal("Launch Benchmark"), btn -> controller.requestBenchmarkStart(), false);

        int layoutBottom = layout.maxHeight();
        int desiredFooterY = this.height - 48;
        int minFooterY = this.contentTop + 80;
        int maxFooterY = this.height - 28;
        if (minFooterY > maxFooterY) {
            minFooterY = maxFooterY;
	        }
	        this.footerY = clampInt(minFooterY, maxFooterY, desiredFooterY);
	
	        int footerTop = this.footerY - 12;
	        this.contentFullHeight = Math.max(0, layoutBottom - this.contentTop);
	        this.viewportHeight = Math.max(0, footerTop - this.contentTop - 8);
	        this.maxScrollOffset = Math.max(0, this.contentFullHeight - this.viewportHeight);
	        this.scrollOffset = Math.min(this.scrollOffset, this.maxScrollOffset);
        this.targetScrollOffset = Math.min(this.targetScrollOffset, this.maxScrollOffset);

        CustomButton applyButton = createAnimatedButton(this.contentLeft, footerY, 160, 20,
                Component.literal("Apply & Save"), btn -> {
                    Lc2hConfigController.FormValues values = new Lc2hConfigController.FormValues(
                            blendWidthBox.getValue(),
                            blendSoftnessBox.getValue()
                    );
                    controller.applyChanges(values, Minecraft.getInstance());
                }, false);
        addRenderableWidget(applyButton);

        CustomButton revertButton = createAnimatedButton(this.contentLeft + 172, footerY, 140, 20,
                Component.literal("Revert"), btn -> Minecraft.getInstance().setScreen(new Lc2hConfigScreen(parent)), false);
        addRenderableWidget(revertButton);

        int rightPrimaryX = this.contentRight - 160;
        int rightSecondaryX = rightPrimaryX - 172;

        CustomButton openConfigButton = createAnimatedButton(rightSecondaryX, footerY, 160, 20,
                Component.literal("Open Config File"), btn -> controller.openConfigFile(), false);
        addRenderableWidget(openConfigButton);

        CustomButton doneButton = createAnimatedButton(rightPrimaryX, footerY, 160, 20,
                Component.translatable("gui.done"), btn -> onClose(), false);
        addRenderableWidget(doneButton);
    }

    private void addSectionHeader(LayoutHelper layout, Component title) {
        int headerHeight = 18;
        int headerY = layout.alignAndGetY();
        CustomButton header = createAnimatedButton(this.contentLeft, headerY, this.contentWidth, headerHeight, title, b -> {});
        header.active = false;
        addRenderableWidget(header);
        layout.bumpAll(headerHeight);
    }

    private void addToggle(LayoutHelper layout, String title, boolean initialValue, String description,
                           boolean requiresRestart, Consumer<Boolean> onChange) {
        EntryPlacement placement = prepareEntry(layout, title, description, requiresRestart, 150, 20);
        final boolean[] state = { initialValue };
        CustomButton button = createAnimatedButton(placement.controlX(), placement.controlY(), placement.controlWidth(), placement.controlHeight(),
                Component.literal(state[0] ? "Enabled" : "Disabled"), b -> {
                    state[0] = !state[0];
                    onChange.accept(state[0]);
                    b.setMessage(Component.literal(state[0] ? "Enabled" : "Disabled"));
                });
        addRenderableWidget(button);
    }

    private EditBox addNumberField(LayoutHelper layout, String title, String description, String value, boolean requiresRestart) {
        EntryPlacement placement = prepareEntry(layout, title, description, requiresRestart, 150, 18);
        EditBox box = new EditBox(this.font, placement.controlX(), placement.controlY(), placement.controlWidth(), placement.controlHeight(), Component.literal(title));
        box.setValue(value);
        addRenderableWidget(box);
        textFieldOriginalY.put(box, placement.controlY());
        textFieldAlpha.put(box, 1f);
        textFieldOffsetY.put(box, 0f);
        return box;
    }

    private CustomButton addActionButton(LayoutHelper layout, String title, String description, Component message, Button.OnPress onPress,
                                         boolean requiresRestart) {
        EntryPlacement placement = prepareEntry(layout, title, description, requiresRestart, 180, 20);
        CustomButton button = createAnimatedButton(placement.controlX(), placement.controlY(), placement.controlWidth(), placement.controlHeight(), message, onPress);
        addRenderableWidget(button);
        return button;
    }

    private EntryPlacement prepareEntry(LayoutHelper layout, String title, String description, boolean requiresRestart,
                                        int preferredControlWidth, int controlHeight) {
        int columnWidth = layout.columnWidth();
        int controlWidth = Math.min(preferredControlWidth, columnWidth - 120);
        if (controlWidth < 110) {
            controlWidth = Math.max(96, columnWidth / 3);
        }
        int textWidth = columnWidth - controlWidth - 12;
        if (textWidth < 160) {
            textWidth = Math.max(140, columnWidth - 120);
            controlWidth = Math.max(96, columnWidth - textWidth - 12);
        }
        String descText = requiresRestart && !description.toLowerCase().contains("(restart required)") ? description + " (Restart required)" : description;
        List<FormattedCharSequence> lines = wrapText(descText, textWidth);
        int descHeight = lines.size() * this.font.lineHeight;
        int totalHeight = this.font.lineHeight + descHeight + controlHeight + 8;
        LayoutHelper.Placement placement = layout.place(totalHeight);
        addLabel(placement.x(), placement.y(), Component.literal(title), lines, requiresRestart);
        int controlX = placement.x() + placement.width() - controlWidth;
        int controlY = placement.y() + this.font.lineHeight + (descHeight > 0 ? descHeight + 6 : 6);
        return new EntryPlacement(controlX, controlY, controlWidth, controlHeight);
    }

    private List<FormattedCharSequence> wrapText(String text, int width) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return this.font.split(Component.literal(text), width);
    }

    private void addLabel(int x, int y, Component title, List<FormattedCharSequence> lines, boolean requiresRestart) {
        int descStartY = y + this.font.lineHeight + 1;
        labels.add(new LabelEntry(x, y, descStartY, title, lines, requiresRestart));
    }

    private CustomButton createAnimatedButton(int x, int y, int width, int height, Component message, Button.OnPress handler) {
        return createAnimatedButton(x, y, width, height, message, handler, true);
    }

    private CustomButton createAnimatedButton(int x, int y, int width, int height, Component message, Button.OnPress handler, boolean scrollable) {
        CustomButton button = new CustomButton(x, y, width, height, message, (b) -> {
            controller.playClickSound();
            if (scrollable) buttonScale.put(b, 1.0f);
            if (handler != null) handler.onPress(b);
        });
        buttonRects.put(button, new int[]{x,y,width,height});
        if (scrollable) {
            buttonScale.put(button, 0f);
            buttonOriginalY.put(button, y);
            buttonHover.put(button, 0f);
            widgetAlpha.put(button, 1f);
            widgetOffsetY.put(button, 0f);
            buttonPulse.put(button, (float) (Math.random() * Math.PI * 2));
        }
        else {
            fixedButtonHover.put(button, 0f);
            buttonPulse.put(button, (float) (Math.random() * Math.PI * 2));
        }
        return button;
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        this.scrollOffset = 0f;
        this.targetScrollOffset = 0f;
        super.resize(minecraft, width, height);
    }

    @Override
    public void removed() {
        super.removed();
        controller.unregisterBenchmarkListener();
    }

    @Override
    public void tick() {
        time++;

        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);
            if (!p.update()) {
                particles.set(i, new Particle(
                        (float) (Math.random() * width),
                        (float) (Math.random() * height),
                        0x33AAAAAA
                ));
            }
        }

        for (Star star : stars) {
            star.update(time);
        }

        for (Map.Entry<Button, Float> entry : new ArrayList<>(buttonScale.entrySet())) {
            float v = entry.getValue() == null ? 0f : entry.getValue();
            if (v <= 0f) continue;
            v = Math.max(0f, v - 0.04f);
            buttonScale.put(entry.getKey(), v);
        }

        for (Map.Entry<Button, Float> entry : buttonPulse.entrySet()) {
            float pulse = entry.getValue() + 0.05f;
            if (pulse > Math.PI * 2) pulse -= Math.PI * 2;
            buttonPulse.put(entry.getKey(), pulse);
        }

        scrollOffset += (targetScrollOffset - scrollOffset) * 0.15f;
        if (Math.abs(scrollOffset - targetScrollOffset) < 0.5f) {
            scrollOffset = targetScrollOffset;
        }
        fadeIn = Math.min(1f, fadeIn + 0.02f);
        targetScrollOffset = clamp(0f, (float)maxScrollOffset, targetScrollOffset);
        scrollOffset = clamp(0f, (float)maxScrollOffset, scrollOffset);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int screenW = this.width;
        int screenH = this.height;

        renderUniverseBackground(graphics, screenW, screenH, partialTick);

        int headerTop = 36;
        int headerBottom = this.headerBottom;
        int outerLeft = this.contentLeft - 24;
        int outerRight = this.contentRight + 24;

        renderHeader(graphics, outerLeft, outerRight, headerTop, headerBottom);
        renderContentArea(graphics, mouseX, mouseY, partialTick);
        renderFooter(graphics);
        renderTextContent(graphics);
        renderScrollBar(graphics);

        int bodyTop = this.contentTop;
        int bodyBottom = Math.max(bodyTop, (this.footerY - 12) - 8);
        withBodyScissor(graphics, bodyTop, bodyBottom, () -> Lc2hConfigScreen.super.render(graphics, mouseX, mouseY, partialTick));
        renderButtonHighlights(graphics);
        renderButtonText(graphics);
    }

    private void renderUniverseBackground(GuiGraphics graphics, int screenW, int screenH, float partialTick) {
        float time = this.time + partialTick;

        graphics.fill(0, 0, screenW, screenH, 0xFF0A0A15);

        renderStars(graphics, screenW, screenH);

        for (int i = 0; i < screenW; i += 6) {
            float wave = (float) Math.sin(i * 0.015 + time * 0.08) * 1.5f;
            int alpha = (int) (4 + wave * 3);
            graphics.fill(i, 0, i + 2, screenH, (alpha << 24) | 0x1A1A2E);
        }

        for (Particle p : particles) {
            float lifeRatio = p.life / p.maxLife;
            int alpha = (int) (30 * (1 - lifeRatio));
            int size = 1 + (int) (1.5 * lifeRatio);
            graphics.fill((int)p.x, (int)p.y, (int)p.x + size, (int)p.y + size, (alpha << 24) | (p.color & 0xFFFFFF));
        }

        for (int i = 0; i < 2; i++) {
            float x = (float) (Math.sin(time * 0.015 + i) * 80 + screenW/2);
            float y = (float) (Math.cos(time * 0.012 + i) * 60 + screenH/3);
            renderNebula(graphics, x, y, 0x33333A5E, 60 + i * 15);
        }
    }

    private void renderStars(GuiGraphics graphics, int screenW, int screenH) {
        for (Star star : stars) {
            int starSize = (int) star.size;
            int color = star.getColor();
            graphics.fill((int)star.x, (int)star.y, (int)star.x + starSize, (int)star.y + starSize, color);

            if (star.size > 1.2f) {
                graphics.fill((int)star.x - 1, (int)star.y, (int)star.x, (int)star.y + starSize, color);
                graphics.fill((int)star.x + starSize, (int)star.y, (int)star.x + starSize + 1, (int)star.y + starSize, color);
            }
        }

        renderConstellations(graphics, screenW, screenH);
    }

    private void renderConstellations(GuiGraphics graphics, int screenW, int screenH) {
        float time = this.time;

        for (int i = 0; i < 8; i++) {
            float x1 = (float) (Math.sin(time * 0.01 + i) * 100 + screenW/2 + i * 40);
            float y1 = (float) (Math.cos(time * 0.008 + i) * 80 + screenH/4 + i * 30);
            float x2 = (float) (Math.sin(time * 0.01 + i + 1) * 120 + screenW/2 + (i + 1) * 40);
            float y2 = (float) (Math.cos(time * 0.008 + i + 1) * 100 + screenH/4 + (i + 1) * 30);

            renderStarLine(graphics, x1, y1, x2, y2, 0x22FFFFFF);
        }
    }

    private void renderStarLine(GuiGraphics graphics, float x1, float y1, float x2, float y2, int color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        int steps = (int) (distance / 2);

        for (int i = 0; i < steps; i++) {
            float progress = (float) i / steps;
            int x = (int) (x1 + dx * progress);
            int y = (int) (y1 + dy * progress);
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    private void renderNebula(GuiGraphics graphics, float x, float y, int color, int size) {
        for (int i = size; i > 0; i -= 6) {
            int alpha = (int) (20 * (1 - (float)i/size));
            graphics.fill((int)(x - i/2), (int)(y - i/2), (int)(x + i/2), (int)(y + i/2), (alpha << 24) | color);
        }
    }

    private void renderHeader(GuiGraphics graphics, int outerLeft, int outerRight, int headerTop, int headerBottom) {
        graphics.fillGradient(outerLeft, headerTop - 20, outerRight, headerBottom + 12,
                0xCC1A1A2E, 0xCC0A0A15);

        graphics.fill(outerLeft + 2, headerTop - 18, outerRight - 2, headerBottom + 10, 0x661A1A2E);

        int centerX = this.width / 2;

        renderCenteredTextWithGlow(graphics, this.font, this.title, centerX, 20, 0xFF3A86FF, 0x663A86FF);

        renderCenteredTextWithGlow(graphics, this.font,
                Component.literal("Multithreading Engine for The Lost Cities. Made to deliver best performance. Powered by Quantified API"),
                centerX, 44, 0x88FFFFFF, 0x33333333);

        renderCenteredTextWithGlow(graphics, this.font,
                Component.literal("Includes bug fixes, general improvements, and... DUCKS :DDD. Fields marked in red require a server restart."),
                centerX, 62, 0x66FFFFFF, 0x22222222);
    }

    private void renderCenteredTextWithGlow(GuiGraphics graphics, net.minecraft.client.gui.Font font, Component text, int x, int y, int color, int glowColor) {
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                if (offsetX == 0 && offsetY == 0) continue;
                graphics.drawString(font, text, x - font.width(text) / 2 + offsetX, y + offsetY, glowColor, false);
            }
        }
        graphics.drawString(font, text, x - font.width(text) / 2, y, color, false);
    }

    private void withBodyScissor(GuiGraphics graphics, int bodyTop, int bodyBottom, Runnable draw) {
        int left = clampInt(0, this.width, this.contentLeft - 12);
        int right = clampInt(0, this.width, this.contentRight + 12);
        int top = clampInt(0, this.height, bodyTop);
        int bottom = clampInt(0, this.height, bodyBottom);
        if (right <= left || bottom <= top) {
            return;
        }
        graphics.enableScissor(left, top, right, bottom);
        try {
            draw.run();
        } finally {
            graphics.disableScissor();
        }
    }

    private void renderContentArea(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int bodyTop = this.contentTop;
        int footerTop = this.footerY - 12;
        int bodyBottom = Math.max(bodyTop, footerTop - 8);
        int bodyBottomClipped = bodyBottom;

        graphics.fillGradient(this.contentLeft - 18, bodyTop - 8, this.contentRight + 18, bodyBottomClipped + 18,
                0xCC1A1A2E, 0xCC0A0A15);

        graphics.fill(this.contentLeft - 12, bodyTop, this.contentRight + 12, bodyBottomClipped + 8, 0x881A1A2E);

        int fadeZone = 24;
        int maxSlide = 8;

        withBodyScissor(graphics, bodyTop, bodyBottomClipped, () -> {
            renderScrollableWidgets(graphics, mouseX, mouseY, bodyTop, bodyBottomClipped, fadeZone, maxSlide);
            renderTextFields(graphics, bodyTop, bodyBottomClipped, fadeZone, maxSlide);
        });
        renderFixedButtons(graphics, mouseX, mouseY);
    }

    private void renderScrollableWidgets(GuiGraphics graphics, int mouseX, int mouseY, int bodyTop, int bodyBottomClipped, int fadeZone, int maxSlide) {
        for (Map.Entry<Button, int[]> entry : buttonRects.entrySet()) {
            if (!buttonOriginalY.containsKey(entry.getKey())) continue;
            int[] r = entry.getValue();
            if (r == null) continue;
            Button button = entry.getKey();
            int bx = r[0];
            int originalY = buttonOriginalY.getOrDefault(button, r[1]);
            int bw = r[2];
            int bh = r[3];

            int byBase = originalY - (int)scrollOffset;
            int distToTop = byBase + bh - bodyTop;
            int distToBottom = bodyBottomClipped - byBase;
            float minDist = Math.min(distToTop, distToBottom);
            float targetAlpha = 1f;
            float targetOffset = 0f;
            if (minDist < fadeZone) {
                float progress = Math.max(0f, Math.min(1f, minDist / (float)fadeZone));
                targetAlpha = progress;
                boolean closerTop = distToTop < distToBottom;
                targetOffset = (1f - progress) * (closerTop ? -1 : 1) * maxSlide;
            }
            float currAlpha = widgetAlpha.getOrDefault(button, 1f);
            float currOffset = widgetOffsetY.getOrDefault(button, 0f);
            currAlpha += (targetAlpha - currAlpha) * 0.12f;
            currOffset += (targetOffset - currOffset) * 0.12f;
            widgetAlpha.put(button, currAlpha);
            widgetOffsetY.put(button, currOffset);
            int by = byBase + Math.round(currOffset);

            button.setPosition(bx, by);
            boolean intersects = (by + bh) >= bodyTop && by <= bodyBottomClipped;
            boolean fullyInside = by >= bodyTop && (by + bh) <= bodyBottomClipped;
            button.visible = currAlpha > 0.03f && intersects;
            button.active = fullyInside;

            float currentHover = buttonHover.getOrDefault(button, 0f);
            if (!intersects) {
                currentHover += (0f - currentHover) * 0.1f;
                buttonHover.put(button, currentHover);
                continue;
            }

            renderButtonBackground(graphics, bx, by, bw, bh, currAlpha, currentHover, button);

            boolean mouseInViewport = mouseY >= bodyTop && mouseY <= bodyBottomClipped;
            boolean over = mouseInViewport && mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
            float targetHover = over ? 1f : 0f;
            currentHover += (targetHover - currentHover) * 0.1f;
            buttonHover.put(button, currentHover);
        }
    }

    private void renderButtonBackground(GuiGraphics graphics, int x, int y, int width, int height, float alpha, float hover, Button button) {
        int bgAlpha = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        float pulse = (float) Math.sin(buttonPulse.getOrDefault(button, 0f)) * 0.1f + 0.9f;

        int baseColor = 0x1A1A2E;
        int hoverColor = 0x3A3A5E;
        int borderColor = 0x3A86FF;

        int currentColor = blendColors(baseColor, hoverColor, hover * 0.3f);
        currentColor = adjustColorBrightness(currentColor, pulse);

        graphics.fill(x - 3, y - 3, x + width + 3, y + height + 3, (bgAlpha << 24) | 0x0A0A15);
        graphics.fill(x - 2, y - 2, x + width + 2, y + height + 2, (bgAlpha << 24) | borderColor);
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, (bgAlpha << 24) | currentColor);

        if (hover > 0.01f) {
            int glowAlpha = (int)(hover * 80 * fadeIn);
            for (int i = 0; i < 3; i++) {
                int glowSize = i * 2;
                graphics.fill(x - 3 - glowSize, y - 3 - glowSize,
                        x + width + 3 + glowSize, y + height + 3 + glowSize,
                        (glowAlpha / (i + 2) << 24) | borderColor);
            }
        }

        float scale = 1f + hover * 0.05f;
        int scaledWidth = (int)(width * scale);
        int scaledHeight = (int)(height * scale);
        int offsetX = (width - scaledWidth) / 2;
        int offsetY = (height - scaledHeight) / 2;

        graphics.fill(x + offsetX, y + offsetY, x + offsetX + scaledWidth, y + offsetY + scaledHeight,
                (bgAlpha << 24) | currentColor);
    }

    private void renderTextFields(GuiGraphics graphics, int bodyTop, int bodyBottomClipped, int fadeZone, int maxSlide) {
        for (Map.Entry<EditBox, Integer> entry : textFieldOriginalY.entrySet()) {
            EditBox field = entry.getKey();
            int originalY = entry.getValue();
            int newY = originalY - (int)scrollOffset;
            int fieldH = field.getHeight();
            int distTop = newY + fieldH - bodyTop;
            int distBottom = bodyBottomClipped - newY;
            float minDist = Math.min(distTop, distBottom);
            float targetAlpha = 1f;
            float targetOffset = 0f;
            if (minDist < fadeZone) {
                float progress = Math.max(0f, Math.min(1f, minDist / (float)fadeZone));
                targetAlpha = progress;
                boolean closerTop = distTop < distBottom;
                targetOffset = (1f - progress) * (closerTop ? -1 : 1) * maxSlide;
            }
            float currAlpha = textFieldAlpha.getOrDefault(field, 1f);
            float currOffset = textFieldOffsetY.getOrDefault(field, 0f);
            currAlpha += (targetAlpha - currAlpha) * 0.12f;
            currOffset += (targetOffset - currOffset) * 0.12f;
            textFieldAlpha.put(field, currAlpha);
            textFieldOffsetY.put(field, currOffset);
            int drawY = newY + Math.round(currOffset);
            field.setY(drawY);
            boolean intersects = drawY + fieldH >= bodyTop && drawY <= bodyBottomClipped;
            boolean fullyInside = drawY >= bodyTop && (drawY + fieldH) <= bodyBottomClipped;
            field.setVisible(currAlpha > 0.03f && intersects);
            field.active = fullyInside;
            if (!intersects) {
                continue;
            }

            if (currAlpha < 0.999f) {
                int overlayAlpha = Math.max(0, Math.min(220, Math.round((1f - currAlpha) * 220)));
                graphics.fill(field.getX() - 2, drawY - 2, field.getX() + field.getWidth() + 2, drawY + field.getHeight() + 2,
                        (overlayAlpha << 24) | 0x0A0A15);
            }

            graphics.fill(field.getX() - 1, drawY - 1, field.getX() + field.getWidth() + 1, drawY + field.getHeight() + 1,
                    ((int)(currAlpha * 255) << 24) | 0x3A86FF);
            graphics.fill(field.getX(), drawY, field.getX() + field.getWidth(), field.getY() + field.getHeight(),
                    ((int)(currAlpha * 200) << 24) | 0x1A1A2E);
        }
    }

    private void renderFixedButtons(GuiGraphics graphics, int mouseX, int mouseY) {
        for (Map.Entry<Button, int[]> entry : buttonRects.entrySet()) {
            if (buttonOriginalY.containsKey(entry.getKey())) continue;
            int[] r = entry.getValue();
            if (r == null) continue;
            int bx = r[0], by = r[1], bw = r[2], bh = r[3];

            float pulse = (float) Math.sin(buttonPulse.getOrDefault(entry.getKey(), 0f)) * 0.05f + 0.95f;
            boolean over = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
            float targetHover = over ? 1f : 0f;
            float currentHover = fixedButtonHover.getOrDefault(entry.getKey(), 0f);
            currentHover += (targetHover - currentHover) * 0.1f;
            fixedButtonHover.put(entry.getKey(), currentHover);

            renderModernButton(graphics, bx, by, bw, bh, currentHover, pulse, over);
        }
    }

    private void renderModernButton(GuiGraphics graphics, int x, int y, int width, int height, float hover, float pulse, boolean isOver) {
        int baseColor = 0x1A1A2E;
        int hoverColor = 0x3A3A5E;
        int accentColor = 0x3A86FF;

        int currentColor = blendColors(baseColor, hoverColor, hover);
        currentColor = adjustColorBrightness(currentColor, pulse);

        graphics.fill(x - 3, y - 3, x + width + 3, y + height + 3, 0xAA0A0A15);
        graphics.fill(x - 2, y - 2, x + width + 2, y + height + 2, accentColor);
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, currentColor);

        float scale = 1f + hover * 0.08f;
        int scaledWidth = (int)(width * scale);
        int scaledHeight = (int)(height * scale);
        int offsetX = (width - scaledWidth) / 2;
        int offsetY = (height - scaledHeight) / 2;

        graphics.fill(x + offsetX, y + offsetY, x + offsetX + scaledWidth, y + offsetY + scaledHeight, currentColor);

        if (hover > 0.01f) {
            int glowAlpha = (int)(hover * 100);
            for (int i = 0; i < 4; i++) {
                int glowSize = i * 3;
                graphics.fill(x - 4 - glowSize, y - 4 - glowSize,
                        x + width + 4 + glowSize, y + height + 4 + glowSize,
                        (glowAlpha / (i + 3) << 24) | accentColor);
            }
        }

        if (isOver) {
            renderRippleEffect(graphics, x + width/2, y + height/2, hover, accentColor);
        }
    }

    private void renderRippleEffect(GuiGraphics graphics, int centerX, int centerY, float intensity, int color) {
        for (int i = 0; i < 3; i++) {
            float ripple = (time + i * 10) % 60 / 60f;
            if (ripple > intensity) continue;
            int size = (int) (ripple * 40);
            int alpha = (int) ((1 - ripple) * 30 * intensity);
            graphics.fill(centerX - size, centerY - size, centerX + size, centerY + size, (alpha << 24) | color);
        }
    }

    private void renderFooter(GuiGraphics graphics) {
        int footerTop = this.footerY - 12;
        graphics.fillGradient(this.contentLeft - 18, footerTop, this.contentRight + 18, this.footerY + 32,
                0xCC1A1A2E, 0xCC0A0A15);
    }

    private void renderTextContent(GuiGraphics graphics) {
        int bodyTop = this.contentTop;
        int footerTop = this.footerY - 12;
        int bodyBottomClipped = Math.max(bodyTop, footerTop - 8);
        int fadeZone = 24;
        int centerX = this.contentLeft + this.contentWidth / 2;

        withBodyScissor(graphics, bodyTop, bodyBottomClipped, () -> {
            for (LabelEntry entry : labels) {
                int entryY = entry.y() - (int)scrollOffset;
                int entryBottom = entry.descStartY() - (int)scrollOffset + entry.lines().size() * this.font.lineHeight;
                if (entryBottom < bodyTop || entryY > bodyBottomClipped) continue;
                int titleBaseColor = entry.requiresRestart() ? 0xFF6B6B : 0xFFFFFF;
                int distToTop = entryBottom - bodyTop;
                int distToBottom = bodyBottomClipped - entryY;
                float minDist = Math.min(distToTop, distToBottom);
                float titleAlpha = minDist < fadeZone ? Math.max(0f, Math.min(1f, minDist / (float)fadeZone)) : 1f;
                int titleColor = (Math.max(0, Math.min(255, Math.round(titleAlpha * 255))) << 24) | (titleBaseColor & 0xFFFFFF);
                graphics.drawString(this.font, entry.title(), entry.x(), entryY, titleColor);
                int lineY = entry.descStartY() - (int)scrollOffset;
                for (FormattedCharSequence line : entry.lines()) {
                    if (lineY + this.font.lineHeight >= bodyTop && lineY <= bodyBottomClipped) {
                        int base = 0xA0C0FF;
                        int alpha = Math.max(0, Math.min(255, Math.round(titleAlpha * 255))) << 24;
                        graphics.drawString(this.font, line, entry.x(), lineY, alpha | (base & 0xFFFFFF));
                    }
                    lineY += this.font.lineHeight;
                }
            }
        });

        Component statusMessage = controller.getStatusMessage();
        if (!statusMessage.getString().isEmpty()) {
            renderCenteredTextWithGlow(graphics, this.font, statusMessage, centerX, 76,
                    controller.getStatusColor(), 0x33000000);
        }
    }

    private void renderScrollBar(GuiGraphics graphics) {
        if (maxScrollOffset > 0) {
            int barX = this.contentRight + 10;
            int barY = this.contentTop + 8;
            int barWidth = 6;
            int barHeight = Math.max(0, this.viewportHeight);
            int thumbHeight;
            if (this.contentFullHeight <= 0) thumbHeight = barHeight;
            else thumbHeight = Math.max(20, (int)(barHeight * (float)this.viewportHeight / (float)Math.max(1, this.contentFullHeight)));
            int thumbY = maxScrollOffset > 0 ? barY + (int)((scrollOffset / (float)maxScrollOffset) * (barHeight - thumbHeight)) : barY;

            graphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0x661A1A2E);
            graphics.fill(barX - 1, thumbY - 1, barX + barWidth + 1, thumbY + thumbHeight + 1, 0xFF3A86FF);
            graphics.fill(barX, thumbY, barX + barWidth, thumbY + thumbHeight, 0xFF1A1A2E);

            float pulse = (float) Math.sin(time * 0.1) * 0.2f + 0.8f;
            int pulseColor = adjustColorBrightness(0xFF3A86FF, pulse);
            graphics.fill(barX + 1, thumbY + 1, barX + barWidth - 1, thumbY + thumbHeight - 1, pulseColor);
        }
    }

    private void renderButtonHighlights(GuiGraphics graphics) {
        int bodyTop = this.contentTop;
        int footerTop = this.footerY - 12;
        int bodyBottomClipped = Math.max(bodyTop, footerTop - 8);

        withBodyScissor(graphics, bodyTop, bodyBottomClipped, () -> {
            for (Map.Entry<Button, Float> entry : buttonScale.entrySet()) {
                float v = entry.getValue() == null ? 0f : entry.getValue();
                if (v <= 0.001f) continue;
                int[] rect = buttonRects.get(entry.getKey());
                if (rect == null) continue;
                int originalY = buttonOriginalY.getOrDefault(entry.getKey(), rect[1]);
                int bx = rect[0];
                int bw = rect[2];
                int bh = rect[3];
                float currOffset = widgetOffsetY.getOrDefault(entry.getKey(), 0f);
                int by = originalY - (int)scrollOffset + Math.round(currOffset);
                if (by + bh < bodyTop || by > bodyBottomClipped) continue;
                int alpha = Math.min(200, Math.round(200 * v * fadeIn));
                int color = (alpha << 24) | 0x3A86FF;
                graphics.fill(bx, by, bx + bw, by + bh, color);
            }
        });
    }

    private void renderButtonText(GuiGraphics graphics) {
        int bodyTop = this.contentTop;
        int footerTop = this.footerY - 12;
        int bodyBottomClipped = Math.max(bodyTop, footerTop - 8);

        withBodyScissor(graphics, bodyTop, bodyBottomClipped, () -> {
            for (Map.Entry<Button, int[]> entry : buttonRects.entrySet()) {
                Button cb = entry.getKey();
                if (!buttonOriginalY.containsKey(cb)) continue;
                int[] r = entry.getValue();
                if (r == null) continue;
                int originalY = buttonOriginalY.getOrDefault(cb, r[1]);
                int bx = r[0];
                int bw = r[2];
                int bh = r[3];
                float currOffset = widgetOffsetY.getOrDefault(cb, 0f);
                int by = originalY - (int)scrollOffset + Math.round(currOffset);
                if (by + bh < bodyTop || by > bodyBottomClipped) continue;
                float visibleAlpha = widgetAlpha.getOrDefault(cb, 1f);
                int baseColor = cb.visible ? 0xFFFFFF : 0x666666;
                int alphaInt = Math.max(0, Math.min(255, Math.round(visibleAlpha * 255))) << 24;
                int textColor = alphaInt | (baseColor & 0xFFFFFF);

                graphics.drawCenteredString(this.font, cb.getMessage(), bx + bw / 2, by + (bh - this.font.lineHeight) / 2 + 1, textColor);
            }
        });

        for (Map.Entry<Button, int[]> entry : buttonRects.entrySet()) {
            Button cb = entry.getKey();
            if (buttonOriginalY.containsKey(cb)) continue;
            int[] r = entry.getValue();
            if (r == null) continue;
            int bx = r[0];
            int by = r[1];
            int bw = r[2];
            int bh = r[3];
            int baseColor = cb.active ? 0xFFFFFF : 0x666666;
            int textColor = 0xFF000000 | (baseColor & 0xFFFFFF);
            graphics.drawCenteredString(this.font, cb.getMessage(), bx + bw / 2, by + (bh - this.font.lineHeight) / 2 + 1, textColor);
        }
    }

    private int blendColors(int color1, int color2, float ratio) {
        int r1 = (color1 >> 16) & 0xFF;
        int g1 = (color1 >> 8) & 0xFF;
        int b1 = color1 & 0xFF;
        int r2 = (color2 >> 16) & 0xFF;
        int g2 = (color2 >> 8) & 0xFF;
        int b2 = color2 & 0xFF;

        int r = (int)(r1 + (r2 - r1) * ratio);
        int g = (int)(g1 + (g2 - g1) * ratio);
        int b = (int)(b1 + (b2 - b1) * ratio);

        return (r << 16) | (g << 8) | b;
    }

    private int adjustColorBrightness(int color, float factor) {
        int r = (int)(((color >> 16) & 0xFF) * factor);
        int g = (int)(((color >> 8) & 0xFF) * factor);
        int b = (int)((color & 0xFF) * factor);

        r = Math.min(255, Math.max(0, r));
        g = Math.min(255, Math.max(0, g));
        b = Math.min(255, Math.max(0, b));

        return (r << 16) | (g << 8) | b;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (controller.isBenchmarkRunning()) return true;

        int bodyTop = this.contentTop;
        int footerTop = this.footerY - 12;
        int bodyBottomClipped = Math.max(bodyTop, footerTop - 8);

        if (mouseX >= this.contentLeft - 18 && mouseX <= this.contentRight + 18 && mouseY >= bodyTop && mouseY <= bodyBottomClipped) {
            if (this.maxScrollOffset > 0) {
                float speed = 40f;
                targetScrollOffset = clamp(0f, (float) this.maxScrollOffset, targetScrollOffset - (float) delta * speed);
                return true;
            }
            return false;
        }

        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (controller.isBenchmarkRunning()) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (controller.isBenchmarkRunning()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                controller.requestUserCancelFromClient("User pressed ESC");
                controller.cancelBenchmarkFeedback(Component.literal("Benchmark cancelled by user"), 0xFF5555);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static float clamp(float min, float max, float v) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(int min, int max, int value) {
        if (max < min) {
            return max;
        }
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
