package org.admany.lc2h.frustum;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.async.Priority;
import net.minecraft.resources.ResourceLocation;
import mcjty.lostcities.varia.ChunkCoord;

import java.util.List;

import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = LC2H.MODID)
public class ChunkPriorityManager {

    private record ViewSample(
        ResourceLocation dimension,
        double x,
        double z,
        double dirX,
        double dirZ,
        double cosHalfFov,
        double maxDistanceSq
    ) {}

    private static volatile ViewSample[] SERVER_VIEWS = new ViewSample[0];

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        updateServerSnapshot(event.getServer());
    }

    private static void updateServerSnapshot(MinecraftServer server) {
        if (server == null || server.getPlayerList() == null) {
            SERVER_VIEWS = new ViewSample[0];
            return;
        }

        int viewDistanceChunks = 10;
        try {
            viewDistanceChunks = Math.max(2, server.getPlayerList().getViewDistance());
        } catch (Throwable ignored) {
        }

        double maxDistanceBlocks = (viewDistanceChunks + 1) * 16.0;
        double maxDistanceSq = maxDistanceBlocks * maxDistanceBlocks;

        Double overrideFov = null;
        try {
            String prop = System.getProperty("lc2h.frustum.server_fov_deg");
            if (prop != null) {
                overrideFov = Double.parseDouble(prop);
            }
        } catch (Throwable ignored) {
        }
        double fovDeg = overrideFov != null ? clampDouble(60.0, 179.0, overrideFov) : clampDouble(110.0, 170.0, 100.0 + viewDistanceChunks * 2.0);
        double cosHalfFov = Math.cos(Math.toRadians(fovDeg * 0.5));

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players == null || players.isEmpty()) {
            SERVER_VIEWS = new ViewSample[0];
            return;
        }

        ViewSample[] samples = new ViewSample[players.size()];
        int idx = 0;
        for (ServerPlayer player : players) {
            if (player == null || player.level() == null) {
                continue;
            }
            ResourceLocation dim = player.level().dimension().location();
            var look = player.getLookAngle();
            double dirX = look.x;
            double dirZ = look.z;
            samples[idx++] = new ViewSample(dim, player.getX(), player.getZ(), dirX, dirZ, cosHalfFov, maxDistanceSq);
        }
        if (idx != samples.length) {
            ViewSample[] trimmed = new ViewSample[idx];
            System.arraycopy(samples, 0, trimmed, 0, idx);
            samples = trimmed;
        }
        SERVER_VIEWS = samples;
    }

    private static double clampDouble(double min, double max, double v) {
        return Math.max(min, Math.min(max, v));
    }

    public static Priority getPriorityForChunk(ChunkCoord coord) {
        if (coord == null || coord.dimension() == null) {
            return Priority.LOW;
        }
        return getPriorityForChunk(coord.dimension().location(), coord.chunkX(), coord.chunkZ());
    }

    public static Priority getPriorityForChunk(ResourceLocation dimension, int chunkX, int chunkZ) {
        if (dimension == null) {
            return Priority.LOW;
        }

        ViewSample[] views = SERVER_VIEWS;
        if (views.length == 0) {
            return Priority.LOW;
        }

        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        for (ViewSample view : views) {
            if (view == null || !dimension.equals(view.dimension)) {
                continue;
            }
            if (PlayerPOVChecker.isChunkInPOV(view.x, view.z, view.dirX, view.dirZ, view.cosHalfFov, view.maxDistanceSq, chunkPos)) {
                if (LC2H.LOGGER.isDebugEnabled()) {
                    LC2H.LOGGER.debug("Chunk {} prioritized HIGH (player POV)", chunkPos);
                }
                return Priority.HIGH;
            }
        }
        return Priority.LOW;
    }

    public static Priority getPriorityForChunk(int chunkX, int chunkZ) {
        return getPriorityForChunk(Level.OVERWORLD.location(), chunkX, chunkZ);
    }
}
