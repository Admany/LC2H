package org.admany.lc2h.debug.frustum;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.async.Priority;
import org.admany.lc2h.frustum.ClientChunkPriorityManager;

import java.lang.reflect.Method;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = LC2H.MODID, value = Dist.CLIENT)
public final class FrustumDebugOverlay {

    private static volatile FrustumDebugState STATE = new FrustumDebugState(false, null);

    private FrustumDebugOverlay() {
    }

    public static void applyServerUpdate(FrustumDebugState state) {
        if (state == null) {
            STATE = new FrustumDebugState(false, null);
        } else {
            STATE = state;
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            return;
        }
        FrustumDebugState state = STATE;
        if (state == null || !state.enabled()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) {
            return;
        }

        ResourceLocation dimension = state.dimension();
        if (dimension != null && !dimension.equals(mc.level.dimension().location())) {
            return;
        }

        int renderDistanceChunks = 10;
        try {
            renderDistanceChunks = Math.max(2, mc.options.renderDistance().get());
        } catch (Throwable ignored) {
        }

        int minY = mc.level.getMinBuildHeight();
        int maxY = mc.level.getMaxBuildHeight();
        ResourceLocation levelDim = mc.level.dimension().location();
        int baseChunkX = mc.player.chunkPosition().x;
        int baseChunkZ = mc.player.chunkPosition().z;

        PoseStack poseStack = event.getPoseStack();
        var camera = event.getCamera();
        var cameraPos = camera.getPosition();
        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        FogSnapshot fogSnapshot = disableFog();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.lineWidth(2.0F);
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer builder = buffer.getBuffer(RenderType.lines());

        for (int dx = -renderDistanceChunks; dx <= renderDistanceChunks; dx++) {
            int chunkX = baseChunkX + dx;
            for (int dz = -renderDistanceChunks; dz <= renderDistanceChunks; dz++) {
                int chunkZ = baseChunkZ + dz;
                Priority priority = ClientChunkPriorityManager.getPriorityForChunk(levelDim, chunkX, chunkZ);
                if (priority == Priority.HIGH) {
                    renderChunkBox(poseStack, builder, chunkX, chunkZ, minY, maxY, 1.0f, 1.0f, 0.0f, 1.0f);
                } else {
                    renderChunkBox(poseStack, builder, chunkX, chunkZ, minY, maxY, 0.1f, 0.5f, 1.0f, 1.0f);
                }
            }
        }

        buffer.endBatch(RenderType.lines());
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.lineWidth(1.0F);
        restoreFog(fogSnapshot);
        poseStack.popPose();
    }

    private static void renderChunkBox(PoseStack poseStack, VertexConsumer builder,
                                       int chunkX, int chunkZ, int minY, int maxY,
                                       float r, float g, float b, float a) {
        int minX = chunkX * 16;
        int minZ = chunkZ * 16;
        int maxX = minX + 16;
        int maxZ = minZ + 16;
        LevelRenderer.renderLineBox(poseStack, builder, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a);
    }

    private static final class FogSnapshot {
        private final Float start;
        private final Float end;

        private FogSnapshot(Float start, Float end) {
            this.start = start;
            this.end = end;
        }
    }

    private static FogSnapshot disableFog() {
        Float start = null;
        Float end = null;
        try {
            Method getStart = RenderSystem.class.getMethod("getShaderFogStart");
            Method getEnd = RenderSystem.class.getMethod("getShaderFogEnd");
            Object startValue = getStart.invoke(null);
            Object endValue = getEnd.invoke(null);
            if (startValue instanceof Float s) {
                start = s;
            }
            if (endValue instanceof Float e) {
                end = e;
            }
        } catch (Throwable ignored) {
        }
        try {
            Method setStart = RenderSystem.class.getMethod("setShaderFogStart", float.class);
            Method setEnd = RenderSystem.class.getMethod("setShaderFogEnd", float.class);
            setStart.invoke(null, Float.MAX_VALUE);
            setEnd.invoke(null, Float.MAX_VALUE);
        } catch (Throwable ignored) {
        }
        if (start == null && end == null) {
            return null;
        }
        return new FogSnapshot(start, end);
    }

    private static void restoreFog(FogSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            Method setStart = RenderSystem.class.getMethod("setShaderFogStart", float.class);
            if (snapshot.start != null) {
                setStart.invoke(null, snapshot.start);
            }
        } catch (Throwable ignored) {
        }
        try {
            Method setEnd = RenderSystem.class.getMethod("setShaderFogEnd", float.class);
            if (snapshot.end != null) {
                setEnd.invoke(null, snapshot.end);
            }
        } catch (Throwable ignored) {
        }
    }
}
