package org.admany.lc2h.frustum;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.async.Priority;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = LC2H.MODID, value = Dist.CLIENT)
public final class ClientChunkPriorityManager {

    private record ViewSample(
        ResourceLocation dimension,
        double x,
        double z,
        double dirX,
        double dirZ,
        double cosHalfFov,
        double maxDistanceSq
    ) {
    }

    private static volatile ViewSample LOCAL_VIEW;

    private ClientChunkPriorityManager() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        updateLocalView();
    }

    private static void updateLocalView() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) {
            LOCAL_VIEW = null;
            return;
        }

        int renderDistanceChunks = 10;
        try {
            renderDistanceChunks = Math.max(2, mc.options.renderDistance().get());
        } catch (Throwable ignored) {
        }

        double maxDistanceBlocks = (renderDistanceChunks + 1) * 16.0;
        double maxDistanceSq = maxDistanceBlocks * maxDistanceBlocks;

        double fovDeg = 90.0;
        try {
            fovDeg = Math.max(30.0, Math.min(179.0, mc.options.fov().get()));
        } catch (Throwable ignored) {
        }
        double cosHalfFov = Math.cos(Math.toRadians(fovDeg * 0.5));

        var look = mc.player.getLookAngle();
        LOCAL_VIEW = new ViewSample(
            mc.level.dimension().location(),
            mc.player.getX(),
            mc.player.getZ(),
            look.x,
            look.z,
            cosHalfFov,
            maxDistanceSq
        );
    }

    public static Priority getPriorityForChunk(ResourceLocation dimension, int chunkX, int chunkZ) {
        ViewSample view = LOCAL_VIEW;
        if (view == null || dimension == null || !dimension.equals(view.dimension)) {
            return Priority.LOW;
        }

        boolean inPov = PlayerPOVChecker.isChunkInPOV(
            view.x, view.z,
            view.dirX, view.dirZ,
            view.cosHalfFov, view.maxDistanceSq,
            new ChunkPos(chunkX, chunkZ)
        );
        return inPov ? Priority.HIGH : Priority.LOW;
    }
}

