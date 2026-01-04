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
    private EditBox accentColorBox;
    private static final int DEFAULT_ACCENT_COLOR = 0x3A86FF;
    private static final int QUANTIFIED_HIGHLIGHT_COLOR = 0xB36CFF;
    private String cachedAccentInput = "";
    private int cachedAccentColor = DEFAULT_ACCENT_COLOR;
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
    private boolean restartWarningVisible = false;
    private float restartWarningProgress = 0f;
    private Component restartWarningSummary = Component.empty();
    private Component restartWarningWarning = Component.empty();
    private Component restartWarningTitle = Component.empty();
    private Component restartPrimaryLabel = Component.empty();
    private boolean restartWarningServer = false;
    private float restartCloseHover = 0f;
    private float restartLaterHover = 0f;
    private final int[] restartCloseRect = new int[4];
    private final int[] restartLaterRect = new int[4];
    private static final Component RESTART_WARNING_TITLE = Component.literal("Restart Required");
    private static final Component RESTART_CLOSE_LABEL = Component.literal("Close Game");
    private static final Component RESTART_LATER_LABEL = Component.literal("Do It Later");
    private static final Component SERVER_RESTART_TITLE = Component.literal("Server Restart Required");
    private static final Component SERVER_RESTART_ACTION = Component.literal("Stop Server");

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
        this.restartWarningVisible = false;
        this.restartWarningProgress = 0f;
        this.restartWarningSummary = Component.empty();
        this.restartWarningWarning = Component.empty();
        this.restartWarningTitle = RESTART_WARNING_TITLE;
        this.restartPrimaryLabel = RESTART_CLOSE_LABEL;
        this.restartWarningServer = false;
        this.restartCloseHover = 0f;
        this.restartLaterHover = 0f;

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
        addToggle(layout, "Enable Cache Stats Logging", working.enableCacheStatsLogging,
                "Log cache statistics periodically.", false, val -> working.enableCacheStatsLogging = val);
        addToggle(layout, "Hide Experimental Warning", working.hideExperimentalWarning,
                "Hide the vanilla experimental warning screen for this mod.", false, val -> working.hideExperimentalWarning = val);
        addToggle(layout, "Debug Logging", working.enableDebugLogging,
                "Enable verbose debug logging for warmup/memory operations.", false, val -> working.enableDebugLogging = val);
        addSectionHeader(layout, Component.literal("INTERFACE"));
        String accentValue = working.uiAccentColor == null ? "3A86FF" : working.uiAccentColor;
        this.accentColorBox = addTextField(layout, "UI Accent Color (Hex)",
                "Accent color for the LC2H config UI (hex, example: 3A86FF).", accentValue, false, 9);
        this.accentColorBox.setFilter(this::isHexInput);
        this.accentColorBox.setResponder(value -> working.uiAccentColor = value);
        addSectionHeader(layout, Component.literal("CITY EDGE"));
        addToggle(layout, "Enable City Blend [EXPERIMENTAL]", working.cityBlendEnabled,
                "Smoothly blend city edges into surrounding terrain.", Lc2hConfigController.RESTART_CITY_EDGE, val -> working.cityBlendEnabled = val);
        addToggle(layout, "Clear Trees Near City Border", working.cityBlendClearTrees,
                "Prevent trees from generating close to city borders.", Lc2hConfigController.RESTART_CITY_EDGE, val -> working.cityBlendClearTrees = val);
        this.blendWidthBox = addNumberField(layout, "Blend Width (blocks)",
                "How many blocks to blend from city edge into terrain.", String.valueOf(working.cityBlendWidth), Lc2hConfigController.RESTART_CITY_EDGE);
        this.blendSoftnessBox = addNumberField(layout, "Blend Softness",
                "Softness of the falloff (higher = softer).", String.valueOf(working.cityBlendSoftness), Lc2hConfigController.RESTART_CITY_EDGE);

        addSectionHeader(layout, Component.literal("BENCHMARK - Performance Validation"));
        addActionButton(layout, "Automated Throughput Trial",
                "Creates an automated Lost Cities stress run at 10k×10k to capture chunks/min, TPS range, and freeze counts. Keep hands off inputs during the minute-long sweep.",
                Component.literal("Launch Benchmark"), btn -> {
                    if (controller.requestBenchmarkStart()) {
                        Minecraft.getInstance().setScreen(null);
                    }
                }, false);

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
                    Lc2hConfigController.FormValues values = buildFormValues();
                    boolean needsRestart = controller.applyChanges(values, Minecraft.getInstance());
                    if (needsRestart) {
                        showRestartWarning(null);
                    }
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
                    boolean needsRestart = controller.applyToggleChange(Minecraft.getInstance(), false);
                    if (needsRestart) {
                        showRestartWarning(title);
                    }
                });
        addRenderableWidget(button);
    }

    private EditBox addNumberField(LayoutHelper layout, String title, String description, String value, boolean requiresRestart) {
        return addTextField(layout, title, description, value, requiresRestart, 12);
    }

    private EditBox addTextField(LayoutHelper layout, String title, String description, String value, boolean requiresRestart, int maxLength) {
        EntryPlacement placement = prepareEntry(layout, title, description, requiresRestart, 150, 18);
        EditBox box = new EditBox(this.font, placement.controlX(), placement.controlY(), placement.controlWidth(), placement.controlHeight(), Component.literal(title));
        box.setValue(value == null ? "" : value);
        if (maxLength > 0) {
            box.setMaxLength(maxLength);
        }
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
        float warningTarget = restartWarningVisible ? 1f : 0f;
        restartWarningProgress += (warningTarget - restartWarningProgress) * 0.18f;
        if (!restartWarningVisible && restartWarningProgress < 0.01f) {
            restartWarningProgress = 0f;
        }
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
        renderContentArea(graphics, mouseX, mouseY, partialTick, true);
        renderFooter(graphics);
        renderTextContent(graphics);
        renderScrollBar(graphics);

        int bodyTop = this.contentTop;
        int bodyBottom = Math.max(bodyTop, (this.footerY - 12) - 8);
        withBodyScissor(graphics, bodyTop, bodyBottom, () -> Lc2hConfigScreen.super.render(graphics, mouseX, mouseY, partialTick));
        renderButtonHighlights(graphics);
        renderButtonText(graphics);
        renderRestartWarning(graphics, mouseX, mouseY);
    }

    private void renderUniverseBackground(GuiGraphics graphics, int screenW, int screenH, float partialTick) {
        float time = this.time + partialTick;
        int accent = getAccentColor();
        int baseBg = tint(0x0A0A15, accent, 0.08f);
        int waveColor = tint(0x1A1A2E, accent, 0.15f);

        graphics.fill(0, 0, screenW, screenH, 0xFF000000 | baseBg);

        renderStars(graphics, screenW, screenH);

        for (int i = 0; i < screenW; i += 6) {
            float wave = (float) Math.sin(i * 0.015 + time * 0.08) * 1.5f;
            int alpha = (int) (4 + wave * 3);
            graphics.fill(i, 0, i + 2, screenH, (alpha << 24) | waveColor);
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
            int nebulaColor = tint(0x333A5E, accent, 0.25f);
            renderNebula(graphics, x, y, nebulaColor, 60 + i * 15);
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
        int accent = getAccentColor();
        int headerTopColor = tintWithAlpha(0x1A1A2E, accent, 0.18f, 0xCC);
        int headerBottomColor = tintWithAlpha(0x0A0A15, accent, 0.12f, 0xCC);
        int headerInset = tintWithAlpha(0x1A1A2E, accent, 0.14f, 0x66);

        graphics.fillGradient(outerLeft, headerTop - 20, outerRight, headerBottom + 12,
                headerTopColor, headerBottomColor);

        graphics.fill(outerLeft + 2, headerTop - 18, outerRight - 2, headerBottom + 10, headerInset);

        int centerX = this.width / 2;

        renderCenteredTextWithGlow(graphics, this.font, this.title, centerX, 20,
                (0xFF << 24) | (accent & 0xFFFFFF),
                (0x66 << 24) | (accent & 0xFFFFFF));

        String subline = "Multithreading Engine for The Lost Cities. Made to deliver best performance. Powered by Quantified API";
        renderCenteredTextWithGlow(graphics, this.font, Component.literal(subline), centerX, 44, 0x88FFFFFF, 0x33333333);
        String highlight = "Quantified API";
        int highlightIndex = subline.indexOf(highlight);
        if (highlightIndex >= 0) {
            int fullWidth = this.font.width(subline);
            int startX = centerX - fullWidth / 2;
            int prefixWidth = this.font.width(subline.substring(0, highlightIndex));
            int highlightX = startX + prefixWidth;
            renderStringWithGlow(graphics, this.font, highlight, highlightX, 44,
                    (0xFF << 24) | (QUANTIFIED_HIGHLIGHT_COLOR & 0xFFFFFF),
                    (0x66 << 24) | (QUANTIFIED_HIGHLIGHT_COLOR & 0xFFFFFF));
        }

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

    private void renderStringWithGlow(GuiGraphics graphics, net.minecraft.client.gui.Font font, String text, int x, int y, int color, int glowColor) {
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                if (offsetX == 0 && offsetY == 0) continue;
                graphics.drawString(font, text, x + offsetX, y + offsetY, glowColor, false);
            }
        }
        graphics.drawString(font, text, x, y, color, false);
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

    private void renderContentArea(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, boolean renderWidgets) {
        int bodyTop = this.contentTop;
        int footerTop = this.footerY - 12;
        int bodyBottom = Math.max(bodyTop, footerTop - 8);
        int bodyBottomClipped = bodyBottom;
        int accent = getAccentColor();
        int panelTop = tintWithAlpha(0x1A1A2E, accent, 0.18f, 0xCC);
        int panelBottom = tintWithAlpha(0x0A0A15, accent, 0.12f, 0xCC);
        int panelFill = tintWithAlpha(0x1A1A2E, accent, 0.14f, 0x88);

        graphics.fillGradient(this.contentLeft - 18, bodyTop - 8, this.contentRight + 18, bodyBottomClipped + 18,
                panelTop, panelBottom);

        graphics.fill(this.contentLeft - 12, bodyTop, this.contentRight + 12, bodyBottomClipped + 8, panelFill);

        int fadeZone = 24;
        int maxSlide = 8;

        if (renderWidgets) {
            withBodyScissor(graphics, bodyTop, bodyBottomClipped, () -> {
                renderScrollableWidgets(graphics, mouseX, mouseY, bodyTop, bodyBottomClipped, fadeZone, maxSlide);
                renderTextFields(graphics, bodyTop, bodyBottomClipped, fadeZone, maxSlide);
                renderAccentPreview(graphics, bodyTop, bodyBottomClipped);
            });
            renderFixedButtons(graphics, mouseX, mouseY);
        }
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

        int accent = getAccentColor();
        int baseColor = tint(0x1A1A2E, accent, 0.08f);
        int hoverColor = tint(0x3A3A5E, accent, 0.12f);
        int borderColor = getAccentColor();

        int currentColor = blendColors(baseColor, hoverColor, hover * 0.3f);
        currentColor = adjustColorBrightness(currentColor, pulse);

        int outerPadX = 2;
        int outerPadY = 3;
        int midPadX = 1;
        int midPadY = 2;
        int innerPadX = 0;
        int innerPadY = 1;

        graphics.fill(x - outerPadX, y - outerPadY, x + width + outerPadX, y + height + outerPadY, (bgAlpha << 24) | tint(0x0A0A15, accent, 0.08f));
        graphics.fill(x - midPadX, y - midPadY, x + width + midPadX, y + height + midPadY, (bgAlpha << 24) | borderColor);
        graphics.fill(x - innerPadX, y - innerPadY, x + width + innerPadX, y + height + innerPadY, (bgAlpha << 24) | currentColor);

        if (hover > 0.01f) {
            int glowAlpha = (int)(hover * 80 * fadeIn);
            for (int i = 0; i < 3; i++) {
                int glowSize = i * 2;
                int glowPadX = outerPadX + glowSize;
                int glowPadY = outerPadY + glowSize;
                graphics.fill(x - glowPadX, y - glowPadY,
                        x + width + glowPadX, y + height + glowPadY,
                        (glowAlpha / (i + 2) << 24) | borderColor);
            }
        }

        graphics.fill(x, y, x + width, y + height, (bgAlpha << 24) | currentColor);
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
                int accent = getAccentColor();
                graphics.fill(field.getX() - 2, drawY - 2, field.getX() + field.getWidth() + 2, drawY + field.getHeight() + 2,
                        (overlayAlpha << 24) | tint(0x0A0A15, accent, 0.08f));
            }

            graphics.fill(field.getX() - 1, drawY - 1, field.getX() + field.getWidth() + 1, drawY + field.getHeight() + 1,
                    ((int)(currAlpha * 255) << 24) | getAccentColor());
            graphics.fill(field.getX(), drawY, field.getX() + field.getWidth(), field.getY() + field.getHeight(),
                    ((int)(currAlpha * 200) << 24) | tint(0x1A1A2E, getAccentColor(), 0.08f));
        }
    }

    private void renderAccentPreview(GuiGraphics graphics, int bodyTop, int bodyBottomClipped) {
        if (accentColorBox == null || !accentColorBox.visible) {
            return;
        }
        int fieldX = accentColorBox.getX();
        int fieldY = accentColorBox.getY();
        int fieldH = accentColorBox.getHeight();
        int fieldW = accentColorBox.getWidth();
        int swatchSize = Math.max(8, fieldH - 6);
        int swatchX = fieldX + fieldW - swatchSize - 3;
        int swatchY = fieldY + (fieldH - swatchSize) / 2;
        if (swatchY + swatchSize < bodyTop || swatchY > bodyBottomClipped) {
            return;
        }
        float alpha = textFieldAlpha.getOrDefault(accentColorBox, 1f);
        int accent = getAccentColor();
        graphics.fill(swatchX - 1, swatchY - 1, swatchX + swatchSize + 1, swatchY + swatchSize + 1,
                applyAlpha(0x1A1A2E, alpha));
        graphics.fill(swatchX, swatchY, swatchX + swatchSize, swatchY + swatchSize,
                applyAlpha(accent, alpha));
    }

    private void renderFixedButtons(GuiGraphics graphics, int mouseX, int mouseY) {
        for (Map.Entry<Button, int[]> entry : buttonRects.entrySet()) {
            if (buttonOriginalY.containsKey(entry.getKey())) continue;
            if (!entry.getKey().visible) continue;
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
        int accent = getAccentColor();
        int baseColor = tint(0x1A1A2E, accent, 0.08f);
        int hoverColor = tint(0x3A3A5E, accent, 0.12f);
        int accentColor = getAccentColor();

        int currentColor = blendColors(baseColor, hoverColor, hover);
        currentColor = adjustColorBrightness(currentColor, pulse);

        int outerPadX = 2;
        int outerPadY = 3;
        int midPadX = 1;
        int midPadY = 2;
        int innerPadX = 0;
        int innerPadY = 1;

        graphics.fill(x - outerPadX, y - outerPadY, x + width + outerPadX, y + height + outerPadY,
                (0xAA << 24) | tint(0x0A0A15, accent, 0.08f));
        graphics.fill(x - midPadX, y - midPadY, x + width + midPadX, y + height + midPadY, accentColor);
        graphics.fill(x - innerPadX, y - innerPadY, x + width + innerPadX, y + height + innerPadY, currentColor);

        graphics.fill(x, y, x + width, y + height, currentColor);

        if (hover > 0.01f) {
            int glowAlpha = (int)(hover * 100);
            for (int i = 0; i < 4; i++) {
                int glowSize = i * 3;
                int glowPadX = outerPadX + 1 + glowSize;
                int glowPadY = outerPadY + 1 + glowSize;
                graphics.fill(x - glowPadX, y - glowPadY,
                        x + width + glowPadX, y + height + glowPadY,
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
        int accent = getAccentColor();
        int footerTopColor = tintWithAlpha(0x1A1A2E, accent, 0.16f, 0xCC);
        int footerBottomColor = tintWithAlpha(0x0A0A15, accent, 0.12f, 0xCC);
        graphics.fillGradient(this.contentLeft - 18, footerTop, this.contentRight + 18, this.footerY + 32,
                footerTopColor, footerBottomColor);
    }

    private void renderTextContent(GuiGraphics graphics) {
        int bodyTop = this.contentTop;
        int footerTop = this.footerY - 12;
        int bodyBottomClipped = Math.max(bodyTop, footerTop - 8);
        int fadeZone = 24;
        int centerX = this.contentLeft + this.contentWidth / 2;
        int accent = getAccentColor();
        int descBase = blendColors(accent, 0xFFFFFF, 0.55f);

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
                        int alpha = Math.max(0, Math.min(255, Math.round(titleAlpha * 255))) << 24;
                        graphics.drawString(this.font, line, entry.x(), lineY, alpha | (descBase & 0xFFFFFF));
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

            graphics.fill(barX, barY, barX + barWidth, barY + barHeight,
                    (0x66 << 24) | tint(0x1A1A2E, getAccentColor(), 0.12f));
            int accent = getAccentColor();
            graphics.fill(barX - 1, thumbY - 1, barX + barWidth + 1, thumbY + thumbHeight + 1, (0xFF << 24) | (accent & 0xFFFFFF));
            graphics.fill(barX, thumbY, barX + barWidth, thumbY + thumbHeight,
                    (0xFF << 24) | tint(0x1A1A2E, accent, 0.12f));

            float pulse = (float) Math.sin(time * 0.1) * 0.2f + 0.8f;
            int pulseColor = adjustColorBrightness(accent, pulse);
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
                int color = (alpha << 24) | getAccentColor();
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
            if (!cb.visible) continue;
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

    private int tint(int baseRgb, int accentRgb, float amount) {
        return blendColors(baseRgb, accentRgb, clamp(0f, 1f, amount));
    }

    private int tintWithAlpha(int baseRgb, int accentRgb, float amount, int alpha) {
        int rgb = tint(baseRgb, accentRgb, amount);
        return ((alpha & 0xFF) << 24) | (rgb & 0xFFFFFF);
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

    private boolean isHexInput(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            trimmed = trimmed.substring(2);
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private String getAccentInput() {
        if (accentColorBox != null) {
            return accentColorBox.getValue();
        }
        return uiState.uiAccentColor;
    }

    private int getAccentColor() {
        String input = getAccentInput();
        if (input == null) {
            input = "";
        }
        if (!input.equals(cachedAccentInput)) {
            Integer parsed = parseHexColor(input);
            cachedAccentColor = parsed == null ? DEFAULT_ACCENT_COLOR : parsed;
            cachedAccentInput = input;
        }
        return cachedAccentColor;
    }

    private static Integer parseHexColor(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.startsWith("0x") || value.startsWith("0X")) {
            value = value.substring(2);
        }
        if (value.length() == 3) {
            char r = value.charAt(0);
            char g = value.charAt(1);
            char b = value.charAt(2);
            value = "" + r + r + g + g + b + b;
        }
        if (value.length() != 6) {
            return null;
        }
        try {
            return Integer.parseInt(value, 16) & 0xFFFFFF;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (isRestartWarningActive()) return true;
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
        if (isRestartWarningActive()) {
            return handleRestartWarningClick(mouseX, mouseY);
        }
        if (controller.isBenchmarkRunning()) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isRestartWarningActive()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                closeRestartWarning();
            }
            return true;
        }
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

    private Lc2hConfigController.FormValues buildFormValues() {
        return new Lc2hConfigController.FormValues(
                blendWidthBox.getValue(),
                blendSoftnessBox.getValue(),
                accentColorBox == null ? null : accentColorBox.getValue()
        );
    }

    private void showRestartWarning(String optionTitle) {
        restartWarningServer = shouldWarnServerRestart();
        restartWarningTitle = restartWarningServer ? SERVER_RESTART_TITLE : RESTART_WARNING_TITLE;
        restartPrimaryLabel = restartWarningServer ? SERVER_RESTART_ACTION : RESTART_CLOSE_LABEL;
        if (restartWarningServer) {
            if (optionTitle == null || optionTitle.isBlank()) {
                restartWarningSummary = Component.literal("Server restart required for these changes.");
            } else {
                restartWarningSummary = Component.literal(optionTitle + " needs a server restart to apply.");
            }
            restartWarningWarning = Component.literal(
                    "Stopping the server will disconnect everyone. Save all progress and be ready to manually start the server again.");
        } else if (optionTitle == null || optionTitle.isBlank()) {
            restartWarningSummary = Component.literal("One or more changes need a restart to fully apply.");
            restartWarningWarning = Component.empty();
        } else {
            restartWarningSummary = Component.literal(optionTitle + " needs a restart to fully apply.");
            restartWarningWarning = Component.empty();
        }
        restartWarningVisible = true;
    }

    private void closeRestartWarning() {
        restartWarningVisible = false;
    }

    private boolean isRestartWarningActive() {
        return restartWarningProgress > 0.01f;
    }

    private boolean handleRestartWarningClick(double mouseX, double mouseY) {
        if (isInRect(mouseX, mouseY, restartCloseRect)) {
            controller.playClickSound();
            if (restartWarningServer) {
                requestServerStop();
            } else {
                Minecraft.getInstance().stop();
            }
            return true;
        }
        if (isInRect(mouseX, mouseY, restartLaterRect)) {
            controller.playClickSound();
            closeRestartWarning();
            return true;
        }
        return true;
    }

    private boolean shouldWarnServerRestart() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getSingleplayerServer() != null) {
            return false;
        }
        return minecraft.player != null && minecraft.player.hasPermissions(2);
    }

    private void requestServerStop() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.player.connection == null) {
            closeRestartWarning();
            return;
        }
        minecraft.player.connection.sendCommand("stop");
        closeRestartWarning();
    }

    private void renderRestartWarning(GuiGraphics graphics, int mouseX, int mouseY) {
        if (restartWarningProgress <= 0.01f) {
            return;
        }
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0f, 0.0f, 200.0f);
        float eased = 1f - (float) Math.pow(1f - restartWarningProgress, 3);
        renderBlurOverlay(graphics, eased);

        int modalWidth = Math.min(420, Math.max(280, this.contentWidth - 40));
        int modalHeight = 170;
        int baseX = (this.width - modalWidth) / 2;
        int baseY = (this.height - modalHeight) / 2;

        float scale = 0.9f + 0.1f * eased;
        int drawWidth = Math.round(modalWidth * scale);
        int drawHeight = Math.round(modalHeight * scale);
        int drawX = baseX + (modalWidth - drawWidth) / 2;
        int drawY = baseY + (modalHeight - drawHeight) / 2;

        int borderColor = applyAlpha(getAccentColor(), eased);
        int frameColor = applyAlpha(0x1A1A2E, eased);
        int fillColor = applyAlpha(0x0A0A15, eased);

        graphics.fill(drawX - 3, drawY - 3, drawX + drawWidth + 3, drawY + drawHeight + 3, applyAlpha(0x000000, eased * 0.35f));
        graphics.fill(drawX - 2, drawY - 2, drawX + drawWidth + 2, drawY + drawHeight + 2, borderColor);
        graphics.fill(drawX - 1, drawY - 1, drawX + drawWidth + 1, drawY + drawHeight + 1, frameColor);
        graphics.fill(drawX, drawY, drawX + drawWidth, drawY + drawHeight, fillColor);

        int titleColor = applyAlpha(0xFF6B6B, eased);
        int glowColor = applyAlpha(0x552222, eased);
        renderCenteredTextWithGlow(graphics, this.font, restartWarningTitle, drawX + drawWidth / 2, drawY + 16, titleColor, glowColor);

        int textAreaWidth = drawWidth - 40;
        List<FormattedCharSequence> lines = this.font.split(restartWarningSummary, textAreaWidth);
        int textY = drawY + 42;
        int textColor = applyAlpha(0xFFFFFF, eased);
        for (FormattedCharSequence line : lines) {
            graphics.drawString(this.font, line, drawX + 20, textY, textColor);
            textY += this.font.lineHeight + 2;
        }
        if (!restartWarningWarning.getString().isEmpty()) {
            textY += 6;
            int warningColor = applyAlpha(0xFF4B4B, eased);
            graphics.drawString(this.font, Component.literal("WARNING:"), drawX + 20, textY, warningColor);
            textY += this.font.lineHeight + 2;
            List<FormattedCharSequence> warningLines = this.font.split(restartWarningWarning, textAreaWidth);
            int warningTextColor = applyAlpha(0xFFD25A, eased);
            for (FormattedCharSequence line : warningLines) {
                graphics.drawString(this.font, line, drawX + 20, textY, warningTextColor);
                textY += this.font.lineHeight + 2;
            }
        }

        int buttonWidth = 140;
        int buttonHeight = 18;
        int gap = 14;
        int buttonsTotalWidth = buttonWidth * 2 + gap;
        int buttonsX = drawX + (drawWidth - buttonsTotalWidth) / 2;
        int buttonsY = drawY + drawHeight - buttonHeight - 18;

        restartCloseRect[0] = buttonsX;
        restartCloseRect[1] = buttonsY;
        restartCloseRect[2] = buttonWidth;
        restartCloseRect[3] = buttonHeight;
        restartLaterRect[0] = buttonsX + buttonWidth + gap;
        restartLaterRect[1] = buttonsY;
        restartLaterRect[2] = buttonWidth;
        restartLaterRect[3] = buttonHeight;

        boolean overClose = isInRect(mouseX, mouseY, restartCloseRect);
        boolean overLater = isInRect(mouseX, mouseY, restartLaterRect);
        restartCloseHover += ((overClose ? 1f : 0f) - restartCloseHover) * 0.2f;
        restartLaterHover += ((overLater ? 1f : 0f) - restartLaterHover) * 0.2f;

        float pulse = 1f + (float) Math.sin(time * 0.08f) * 0.05f;
        renderModalButton(graphics, restartCloseRect[0], restartCloseRect[1], restartCloseRect[2], restartCloseRect[3], restartCloseHover, pulse, eased);
        renderModalButton(graphics, restartLaterRect[0], restartLaterRect[1], restartLaterRect[2], restartLaterRect[3], restartLaterHover, pulse, eased);

        int buttonTextColor = applyAlpha(0xFFFFFF, eased);
        graphics.drawCenteredString(this.font, restartPrimaryLabel,
                restartCloseRect[0] + restartCloseRect[2] / 2,
                restartCloseRect[1] + (restartCloseRect[3] - this.font.lineHeight) / 2 + 1,
                buttonTextColor);
        graphics.drawCenteredString(this.font, RESTART_LATER_LABEL,
                restartLaterRect[0] + restartLaterRect[2] / 2,
                restartLaterRect[1] + (restartLaterRect[3] - this.font.lineHeight) / 2 + 1,
                buttonTextColor);
        graphics.pose().popPose();
    }

    private void renderBlurOverlay(GuiGraphics graphics, float alpha) {
        int baseAlpha = Math.min(235, Math.round(210 * alpha));
        graphics.fill(0, 0, this.width, this.height, (baseAlpha << 24) | 0x0A0A15);

        int noiseAlpha = Math.max(0, Math.min(80, Math.round(55 * alpha)));
        int step = 10;
        int offset = (int) (time % step);
        for (int y = 0; y < this.height; y += step) {
            int lineAlpha = noiseAlpha + (((y + offset) % (step * 2) == 0) ? 18 : 6);
            graphics.fill(0, y, this.width, Math.min(this.height, y + 2), (lineAlpha << 24) | 0x1A1A2E);
        }
        for (int x = 0; x < this.width; x += step) {
            int lineAlpha = noiseAlpha + (((x + offset) % (step * 2) == 0) ? 16 : 4);
            graphics.fill(x, 0, Math.min(this.width, x + 2), this.height, (lineAlpha << 24) | 0x151523);
        }
    }

    private void renderModalButton(GuiGraphics graphics, int x, int y, int width, int height, float hover, float pulse, float alpha) {
        int accent = getAccentColor();
        int baseColor = adjustColorBrightness(tint(0x1A1A2E, accent, 0.08f), pulse);
        int hoverColor = adjustColorBrightness(tint(0x3A3A5E, accent, 0.12f), pulse);
        int accentColor = getAccentColor();

        int currentColor = blendColors(baseColor, hoverColor, hover);
        int border = applyAlpha(accentColor, alpha);
        int fill = applyAlpha(currentColor, alpha);
        int bg = applyAlpha(tint(0x0A0A15, accent, 0.08f), alpha * 0.8f);

        int outerPadX = 2;
        int outerPadY = 3;
        int midPadX = 1;
        int midPadY = 2;
        int innerPadX = 0;
        int innerPadY = 1;

        graphics.fill(x - outerPadX, y - outerPadY, x + width + outerPadX, y + height + outerPadY, bg);
        graphics.fill(x - midPadX, y - midPadY, x + width + midPadX, y + height + midPadY, border);
        graphics.fill(x - innerPadX, y - innerPadY, x + width + innerPadX, y + height + innerPadY, fill);
        graphics.fill(x, y, x + width, y + height, fill);

        if (hover > 0.01f) {
            int glowAlpha = (int)(hover * 90 * alpha);
            for (int i = 0; i < 3; i++) {
                int glowSize = i * 2;
                int glowPadX = outerPadX + glowSize;
                int glowPadY = outerPadY + glowSize;
                graphics.fill(x - glowPadX, y - glowPadY,
                        x + width + glowPadX, y + height + glowPadY,
                        (glowAlpha / (i + 2) << 24) | (accentColor & 0xFFFFFF));
            }
        }
    }

    private int applyAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        return (a << 24) | (color & 0xFFFFFF);
    }

    private boolean isInRect(double mouseX, double mouseY, int[] rect) {
        return mouseX >= rect[0] && mouseX < rect[0] + rect[2] && mouseY >= rect[1] && mouseY < rect[1] + rect[3];
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
