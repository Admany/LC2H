package org.admany.lc2h.debug.chunk;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.admany.lc2h.LC2H;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = LC2H.MODID, value = Dist.CLIENT)
public final class ChunkDebugOverlay {

    private static volatile ChunkDebugSelection SELECTION = new ChunkDebugSelection(false, null, null, null);

    private ChunkDebugOverlay() {
    }

    public static void applyServerUpdate(ChunkDebugSelection selection) {
        if (selection == null) {
            SELECTION = new ChunkDebugSelection(false, null, null, null);
        } else {
            SELECTION = selection;
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            return;
        }
        ChunkDebugSelection selection = SELECTION;
        if (selection == null || !selection.enabled()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) {
            return;
        }

        ResourceLocation dimension = selection.dimension();
        if (dimension != null && !dimension.equals(mc.level.dimension().location())) {
            return;
        }

        ChunkPos primary = selection.primary();
        ChunkPos secondary = selection.secondary();
        if (primary == null && secondary == null) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        var camera = event.getCamera();
        var cameraPos = camera.getPosition();
        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer builder = buffer.getBuffer(RenderType.lines());

        double baseY = Math.floor(mc.player.getY()) + 0.05;
        double yMin = baseY;
        double yMax = baseY + 2.0;

        if (primary != null) {
            renderChunkBox(poseStack, builder, primary, yMin, yMax, 0.2f, 0.9f, 0.3f, 1.0f);
        }
        if (secondary != null) {
            renderChunkBox(poseStack, builder, secondary, yMin, yMax, 0.95f, 0.25f, 0.25f, 1.0f);
        }
        if (primary != null && secondary != null) {
            renderSelectionBox(poseStack, builder, primary, secondary, yMin, yMax, 0.25f, 0.6f, 1.0f, 1.0f);
        }

        buffer.endBatch(RenderType.lines());
        poseStack.popPose();
    }

    private static void renderChunkBox(PoseStack poseStack, VertexConsumer builder, ChunkPos pos,
                                       double yMin, double yMax,
                                       float r, float g, float b, float a) {
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        int maxX = minX + 16;
        int maxZ = minZ + 16;
        LevelRenderer.renderLineBox(poseStack, builder, minX, yMin, minZ, maxX, yMax, maxZ, r, g, b, a);
    }

    private static void renderSelectionBox(PoseStack poseStack, VertexConsumer builder, ChunkPos a, ChunkPos b,
                                           double yMin, double yMax,
                                           float r, float g, float bCol, float aCol) {
        int minChunkX = Math.min(a.x, b.x);
        int maxChunkX = Math.max(a.x, b.x);
        int minChunkZ = Math.min(a.z, b.z);
        int maxChunkZ = Math.max(a.z, b.z);
        int minX = minChunkX * 16;
        int minZ = minChunkZ * 16;
        int maxX = (maxChunkX + 1) * 16;
        int maxZ = (maxChunkZ + 1) * 16;
        LevelRenderer.renderLineBox(poseStack, builder, minX, yMin, minZ, maxX, yMax, maxZ, r, g, bCol, aCol);
    }
}
