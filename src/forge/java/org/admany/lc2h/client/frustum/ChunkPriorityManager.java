package org.admany.lc2h.client.frustum;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.concurrency.async.Priority;
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
        double maxDistanceSq,
        double viewDistanceSq
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

        double viewDistanceBlocks = viewDistanceChunks * 16.0;
        double maxDistanceBlocks = (viewDistanceChunks + 1) * 16.0;
        double maxDistanceSq = maxDistanceBlocks * maxDistanceBlocks;
        double viewDistanceSq = viewDistanceBlocks * viewDistanceBlocks;

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
            double dirLenSq = (dirX * dirX) + (dirZ * dirZ);
            if (dirLenSq < 1.0e-6) {
                float yaw = player.getYRot();
                double yawRad = Math.toRadians(yaw);
                dirX = -Math.sin(yawRad);
                dirZ = Math.cos(yawRad);
            }
            samples[idx++] = new ViewSample(dim, player.getX(), player.getZ(), dirX, dirZ, cosHalfFov, maxDistanceSq, viewDistanceSq);
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

    public static boolean isChunkWithinViewDistance(ResourceLocation dimension, int chunkX, int chunkZ) {
        if (dimension == null) {
            return false;
        }
        ViewSample[] views = SERVER_VIEWS;
        if (views.length == 0) {
            return false;
        }
        double cx = chunkX * 16.0 + 8.0;
        double cz = chunkZ * 16.0 + 8.0;
        for (ViewSample view : views) {
            if (view == null || !dimension.equals(view.dimension)) {
                continue;
            }
            double dx = cx - view.x;
            double dz = cz - view.z;
            double distSq = dx * dx + dz * dz;
            if (distSq <= view.viewDistanceSq) {
                return true;
            }
        }
        return false;
    }

    public static boolean isRegionWithinViewDistance(ResourceLocation dimension,
                                                     int minChunkX,
                                                     int maxChunkX,
                                                     int minChunkZ,
                                                     int maxChunkZ) {
        if (dimension == null) {
            return false;
        }
        ViewSample[] views = SERVER_VIEWS;
        if (views.length == 0) {
            return false;
        }
        double minX = minChunkX * 16.0;
        double maxX = maxChunkX * 16.0 + 15.0;
        double minZ = minChunkZ * 16.0;
        double maxZ = maxChunkZ * 16.0 + 15.0;
        for (ViewSample view : views) {
            if (view == null || !dimension.equals(view.dimension)) {
                continue;
            }
            double dx = distanceToRange(view.x, minX, maxX);
            double dz = distanceToRange(view.z, minZ, maxZ);
            double distSq = dx * dx + dz * dz;
            if (distSq <= view.viewDistanceSq) {
                return true;
            }
        }
        return false;
    }

    private static double distanceToRange(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0;
    }
}
