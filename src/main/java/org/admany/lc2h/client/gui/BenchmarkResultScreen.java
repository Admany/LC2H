package org.admany.lc2h.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.admany.lc2h.dev.benchmark.BenchmarkResult;

@OnlyIn(Dist.CLIENT)
public class BenchmarkResultScreen extends Screen {
    private final Screen parent;
    private final BenchmarkResult result;
    private final String uploadStatus;
    private Button closeButton;
    private Button copyButton;

    public BenchmarkResultScreen(Screen parent, BenchmarkResult result, String uploadStatus) {
        super(Component.translatable("lc2h.benchmark.results.title"));
        this.parent = parent;
        this.result = result;
        this.uploadStatus = uploadStatus;
    }

    @Override
    protected void init() {
        int buttonWidth = 100;
        int buttonHeight = 20;
        int spacing = 8;
        int centerX = this.width / 2;
        int footerY = this.height - 35;

        closeButton = Button.builder(CommonComponents.GUI_DONE, btn -> this.onClose())
                .bounds(centerX - buttonWidth - spacing, footerY, buttonWidth, buttonHeight)
                .build();
        copyButton = Button.builder(Component.translatable("lc2h.benchmark.results.copy"), btn -> copyResultsToClipboard())
                .bounds(centerX + spacing, footerY, buttonWidth, buttonHeight)
                .build();

        addRenderableWidget(closeButton);
        addRenderableWidget(copyButton);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx);
        RenderSystem.enableBlend();
        gfx.fillGradient(20, 20, this.width - 20, this.height - 50, 0xD0000000, 0xC0000000);

        int titleY = 30;
        gfx.drawCenteredString(this.font, this.title, this.width / 2, titleY, 0xFFFFFF);

        int left = this.width / 2 - 140;
        int top = titleY + 20;
        int line = top;
        int lineSpacing = 14;

        line = drawSection(gfx, Component.translatable("lc2h.benchmark.results.section.summary"), left, line, lineSpacing);
        line = drawMetric(gfx, Component.translatable("lc2h.benchmark.results.label.chunks_loaded"), String.valueOf(result.chunksGenerated()), left, line, lineSpacing);
        line = drawMetric(gfx, Component.translatable("lc2h.benchmark.results.label.effective_cpm"), format(result.effectiveChunksPerMinute()), left, line, lineSpacing);
        line = drawMetric(gfx, Component.translatable("lc2h.benchmark.results.label.average_tps"), format(result.commonTps()), left, line, lineSpacing);
        line = drawMetric(gfx, Component.translatable("lc2h.benchmark.results.label.freezes"), String.valueOf(result.freezeCount()), left, line, lineSpacing);
        line = drawMetric(gfx, Component.translatable("lc2h.benchmark.results.label.run_time_seconds"), format(result.realDurationSeconds()), left, line, lineSpacing);

        line += lineSpacing;
        line = drawSection(gfx, Component.translatable("lc2h.benchmark.results.section.performance"), left, line, lineSpacing);
        line = drawMetric(gfx, Component.translatable("lc2h.benchmark.results.label.min_tps"), format(result.minTps()), left, line, lineSpacing);
        line = drawMetric(gfx, Component.translatable("lc2h.benchmark.results.label.max_tps"), format(result.maxTps()), left, line, lineSpacing);
        line = drawMetric(gfx, Component.translatable("lc2h.benchmark.results.label.avg_tick_ms"), format(result.averageTickMillis()), left, line, lineSpacing);
        line = drawMetric(gfx, Component.translatable("lc2h.benchmark.results.label.max_tick_ms"), format(result.maxTickMillis()), left, line, lineSpacing);
        line = drawMetric(gfx, Component.translatable("lc2h.benchmark.results.label.score"), format(result.scoreEstimate()), left, line, lineSpacing);

        line += lineSpacing;
        line = drawSection(gfx, Component.translatable("lc2h.benchmark.results.section.environment"), left, line, lineSpacing);
        line = drawMetric(gfx, Component.translatable("lc2h.benchmark.results.label.seed"), String.valueOf(result.seed()), left, line, lineSpacing);
        line = drawMetric(gfx, Component.translatable("lc2h.benchmark.results.label.dimension"), result.dimension().location().toString(), left, line, lineSpacing);
        line = drawMetric(gfx, Component.translatable("lc2h.benchmark.results.label.lc2h_version"), result.modVersion(), left, line, lineSpacing);
        line = drawMetric(gfx, Component.translatable("lc2h.benchmark.results.label.minecraft_version"), result.minecraftVersion(), left, line, lineSpacing);
        line = drawMetric(gfx, Component.translatable("lc2h.benchmark.results.label.player"), result.playerName(), left, line, lineSpacing);
        line = drawMetric(gfx, Component.translatable("lc2h.benchmark.results.label.upload"), uploadStatus, left, line, lineSpacing);

        if (result.aborted()) {
            line += lineSpacing;
            drawSection(gfx, Component.translatable("lc2h.benchmark.results.section.status"), left, line, lineSpacing);
            drawMetric(gfx, Component.translatable("lc2h.benchmark.results.label.aborted"), result.abortReason(), left, line + lineSpacing, lineSpacing);
        }

        super.render(gfx, mouseX, mouseY, partialTick);
    }

    private int drawSection(GuiGraphics gfx, Component title, int left, int y, int spacing) {
        gfx.drawString(this.font, title, left, y, 0xFFAA00);
        return y + spacing;
    }

    private int drawMetric(GuiGraphics gfx, Component label, String value, int left, int y, int spacing) {
        gfx.drawString(this.font, label.copy().append(Component.literal(":")), left, y, 0xCCCCCC);
        gfx.drawString(this.font, value, left + 140, y, 0xFFFFFF);
        return y + spacing;
    }

    private String format(double val) {
        return String.format("%.2f", val);
    }

    private void copyResultsToClipboard() {
        StringBuilder sb = new StringBuilder();
        sb.append(Component.translatable("lc2h.benchmark.results.clipboard.title").getString()).append('\n');
        sb.append(Component.translatable("lc2h.benchmark.results.label.chunks_loaded").getString()).append(": ").append(result.chunksGenerated()).append('\n');
        sb.append(Component.translatable("lc2h.benchmark.results.label.effective_cpm").getString()).append(": ").append(format(result.effectiveChunksPerMinute())).append('\n');
        sb.append(Component.translatable("lc2h.benchmark.results.label.average_tps").getString()).append(": ").append(format(result.commonTps())).append('\n');
        sb.append(Component.translatable("lc2h.benchmark.results.label.freezes").getString()).append(": ").append(result.freezeCount()).append('\n');
        sb.append(Component.translatable("lc2h.benchmark.results.label.run_time_seconds").getString()).append(": ").append(format(result.realDurationSeconds())).append('\n');
        sb.append(Component.translatable("lc2h.benchmark.results.label.min_tps").getString()).append(": ").append(format(result.minTps())).append('\n');
        sb.append(Component.translatable("lc2h.benchmark.results.label.max_tps").getString()).append(": ").append(format(result.maxTps())).append('\n');
        sb.append(Component.translatable("lc2h.benchmark.results.label.avg_tick_ms").getString()).append(": ").append(format(result.averageTickMillis())).append('\n');
        sb.append(Component.translatable("lc2h.benchmark.results.label.max_tick_ms").getString()).append(": ").append(format(result.maxTickMillis())).append('\n');
        sb.append(Component.translatable("lc2h.benchmark.results.label.score").getString()).append(": ").append(format(result.scoreEstimate())).append('\n');
        sb.append(Component.translatable("lc2h.benchmark.results.label.seed").getString()).append(": ").append(result.seed()).append('\n');
        sb.append(Component.translatable("lc2h.benchmark.results.label.dimension").getString()).append(": ").append(result.dimension().location()).append('\n');
        sb.append(Component.translatable("lc2h.benchmark.results.label.minecraft_version").getString()).append(": ").append(result.minecraftVersion()).append('\n');
        sb.append(Component.translatable("lc2h.benchmark.results.label.lc2h_version").getString()).append(": ").append(result.modVersion()).append('\n');
        sb.append(Component.translatable("lc2h.benchmark.results.label.player").getString()).append(": ").append(result.playerName()).append('\n');
        sb.append(Component.translatable("lc2h.benchmark.results.label.upload").getString()).append(": ").append(uploadStatus).append('\n');
        if (result.aborted()) {
            sb.append(Component.translatable("lc2h.benchmark.results.label.aborted").getString()).append(": ").append(result.abortReason()).append('\n');
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(sb.toString());
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
