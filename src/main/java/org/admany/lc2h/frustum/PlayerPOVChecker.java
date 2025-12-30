package org.admany.lc2h.frustum;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.admany.lc2h.LC2H;

public class PlayerPOVChecker {

    static boolean isChunkInPOV(double originX,
                                double originZ,
                                double dirX,
                                double dirZ,
                                double cosHalfFov,
                                double maxDistanceSq,
                                ChunkPos chunkPos) {
        if (chunkPos == null) {
            return false;
        }

        double chunkCenterX = chunkPos.getMinBlockX() + 8.0;
        double chunkCenterZ = chunkPos.getMinBlockZ() + 8.0;

        double dx = chunkCenterX - originX;
        double dz = chunkCenterZ - originZ;

        double distSq = (dx * dx) + (dz * dz);
        if (maxDistanceSq > 0.0 && distSq > maxDistanceSq) {
            return false;
        }
        if (distSq <= 1.0e-9) {
            return true;
        }

        double dirLenSq = (dirX * dirX) + (dirZ * dirZ);
        if (dirLenSq <= 1.0e-9) {
            return false;
        }

        double invDist = 1.0 / Math.sqrt(distSq);
        double invDir = 1.0 / Math.sqrt(dirLenSq);

        double nx = dx * invDist;
        double nz = dz * invDist;
        double ndx = dirX * invDir;
        double ndz = dirZ * invDir;

        double dot = (ndx * nx) + (ndz * nz);
        return dot >= cosHalfFov;
    }

    public static boolean isChunkInPOV(Player player, ChunkPos chunkPos) {
        if (player == null || chunkPos == null) return false;

        try {
            Vec3 lookVec = player.getLookAngle();

            int viewDistanceChunks = 10;
            try {
                if (player.level() != null) {
                    viewDistanceChunks = Math.max(2, player.level().getServer().getPlayerList().getViewDistance());
                }
            } catch (Throwable ignored) {
            }

            double maxDistanceBlocks = (viewDistanceChunks + 1) * 16.0;
            double maxDistanceSq = maxDistanceBlocks * maxDistanceBlocks;

            double fovDeg = 140.0;
            double cosHalfFov = Math.cos(Math.toRadians(fovDeg * 0.5));

            return isChunkInPOV(player.getX(), player.getZ(), lookVec.x, lookVec.z, cosHalfFov, maxDistanceSq, chunkPos);
        } catch (Exception e) {
            LC2H.LOGGER.error("Error checking POV for chunk " + chunkPos + ": " + e.getMessage(), e);
            return false;
        }
    }

    public static boolean isChunkInAnyPlayerPOV(Iterable<? extends Player> players, ChunkPos chunkPos) {
        for (Player player : players) {
            if (isChunkInPOV(player, chunkPos)) {
                return true;
            }
        }
        return false;
    }
}
