package org.admany.lc2h.debug.chunk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mcjty.lostcities.api.LostChunkCharacteristics;
import mcjty.lostcities.api.MultiPos;
import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraftforge.fml.loading.FMLPaths;
import org.admany.lc2h.diagnostics.ChunkGenTracker;
import org.admany.lc2h.mixin.accessor.MultiChunkAccessor;
import org.admany.lc2h.worldgen.async.snapshot.MultiChunkSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.lang.reflect.Method;
import java.util.Map;

public final class ChunkDebugExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault());

    private ChunkDebugExporter() {
    }

    public static Path exportSelection(ServerPlayer player, ChunkDebugManager.ChunkSelection selection, String label) throws Exception {
        if (player == null) {
            throw new IllegalArgumentException("player");
        }

        ChunkPos primary = selection != null ? selection.primary() : null;
        ChunkPos secondary = selection != null ? selection.secondary() : null;
        if (primary == null && secondary == null) {
            primary = player.chunkPosition();
            secondary = primary;
        } else if (primary == null) {
            primary = secondary;
        } else if (secondary == null) {
            secondary = primary;
        }

        ResourceLocation dimension = selection != null && selection.dimension() != null
            ? selection.dimension()
            : player.level().dimension().location();

        int minChunkX = Math.min(primary.x, secondary.x);
        int maxChunkX = Math.max(primary.x, secondary.x);
        int minChunkZ = Math.min(primary.z, secondary.z);
        int maxChunkZ = Math.max(primary.z, secondary.z);

        JsonObject root = new JsonObject();
        root.addProperty("dimension", dimension.toString());
        root.addProperty("exportedAt", Instant.now().toString());
        root.addProperty("player", player.getGameProfile().getName());

        JsonObject selectionObj = new JsonObject();
        selectionObj.addProperty("primaryChunkX", primary.x);
        selectionObj.addProperty("primaryChunkZ", primary.z);
        selectionObj.addProperty("secondaryChunkX", secondary.x);
        selectionObj.addProperty("secondaryChunkZ", secondary.z);
        selectionObj.addProperty("minChunkX", minChunkX);
        selectionObj.addProperty("minChunkZ", minChunkZ);
        selectionObj.addProperty("maxChunkX", maxChunkX);
        selectionObj.addProperty("maxChunkZ", maxChunkZ);
        selectionObj.addProperty("chunkCount", (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1));
        root.add("selection", selectionObj);

        IDimensionInfo provider = resolveProvider(player);
        root.addProperty("providerAvailable", provider != null);

        JsonArray chunks = new JsonArray();
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                ChunkCoord coord = new ChunkCoord(player.level().dimension(), x, z);
                JsonObject chunk = new JsonObject();
                chunk.addProperty("chunkX", x);
                chunk.addProperty("chunkZ", z);
                chunk.add("chunkinfo", ChunkGenTracker.buildReportJson(coord));
                chunk.add("multichunk", buildMultiChunkJson(provider, coord));
                chunk.add("characteristics", buildCharacteristicsJson(provider, coord));
                chunks.add(chunk);
            }
        }
        root.add("chunks", chunks);

        String timestamp = FILE_TS.format(Instant.now());
        String baseName = "chunkdebug_" + timestamp + "_" + minChunkX + "_" + minChunkZ + "_to_" + maxChunkX + "_" + maxChunkZ;
        if (label != null && !label.isBlank()) {
            baseName += "_" + sanitize(label);
        }

        Path outDir = FMLPaths.GAMEDIR.get().resolve("lc2h").resolve("chunkdebug");
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve(baseName + ".json");
        Files.writeString(outFile, GSON.toJson(root), StandardCharsets.UTF_8);
        return outFile;
    }

    private static IDimensionInfo resolveProvider(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        try {
            if (player.level() instanceof WorldGenLevel worldGenLevel) {
                IDimensionInfo info = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(worldGenLevel);
                if (info != null) {
                    info.setWorld(worldGenLevel);
                }
                return info;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static JsonObject buildMultiChunkJson(IDimensionInfo provider, ChunkCoord coord) {
        JsonObject obj = new JsonObject();
        if (provider == null || coord == null) {
            obj.addProperty("available", false);
            obj.addProperty("reason", "provider-missing");
            return obj;
        }
        try {
            int areaSize = provider.getWorldStyle().getMultiSettings().areasize();
            if (areaSize <= 0) {
                obj.addProperty("available", false);
                obj.addProperty("reason", "invalid-area-size");
                return obj;
            }

            ChunkCoord multiCoord = new ChunkCoord(coord.dimension(),
                Math.floorDiv(coord.chunkX(), areaSize),
                Math.floorDiv(coord.chunkZ(), areaSize));

            obj.addProperty("available", true);
            obj.addProperty("multiCoordX", multiCoord.chunkX());
            obj.addProperty("multiCoordZ", multiCoord.chunkZ());
            obj.addProperty("areaSize", areaSize);

            Map<ChunkCoord, MultiChunk> cache = MultiChunkAccessor.lc2h$getCache();
            MultiChunk multiChunk = cache != null ? cache.get(multiCoord) : null;
            obj.addProperty("cached", multiChunk != null);
            if (multiChunk == null) {
                return obj;
            }

            MultiChunkAccessor accessor = (MultiChunkAccessor) multiChunk;
            ChunkCoord topLeft = accessor.lc2h$getTopLeft();
            if (topLeft != null) {
                obj.addProperty("topLeftChunkX", topLeft.chunkX());
                obj.addProperty("topLeftChunkZ", topLeft.chunkZ());
            }
            obj.addProperty("areaSizeResolved", accessor.lc2h$getAreaSize());

            MultiChunkSnapshot.MultiChunkCell cell = MultiChunkSnapshot.describeCell(multiChunk, coord);
            if (cell != null) {
                JsonObject cellObj = new JsonObject();
                cellObj.addProperty("relX", cell.relX());
                cellObj.addProperty("relZ", cell.relZ());
                cellObj.addProperty("name", cell.name());
                cellObj.addProperty("offsetX", cell.offsetX());
                cellObj.addProperty("offsetZ", cell.offsetZ());
                obj.add("cell", cellObj);
            }
        } catch (Throwable t) {
            obj.addProperty("available", false);
            obj.addProperty("error", t.getClass().getSimpleName());
        }
        return obj;
    }

    private static JsonObject buildCharacteristicsJson(IDimensionInfo provider, ChunkCoord coord) {
        JsonObject obj = new JsonObject();
        if (provider == null || coord == null) {
            obj.addProperty("available", false);
            obj.addProperty("reason", "provider-missing");
            return obj;
        }
        try {
            LostChunkCharacteristics info = BuildingInfo.getChunkCharacteristics(coord, provider);
            if (info == null) {
                obj.addProperty("available", false);
                obj.addProperty("reason", "no-characteristics");
                return obj;
            }
            obj.addProperty("available", true);
            obj.addProperty("isCity", info.isCity);
            obj.addProperty("couldHaveBuilding", info.couldHaveBuilding);
            obj.addProperty("cityLevel", info.cityLevel);
            if (info.cityStyleId != null) {
                obj.addProperty("cityStyleId", info.cityStyleId.toString());
            }
            if (info.cityStyle != null) {
                obj.addProperty("cityStyleName", safeName(info.cityStyle));
            }
            if (info.multiBuildingId != null) {
                obj.addProperty("multiBuildingId", info.multiBuildingId.toString());
            }
            if (info.multiBuilding != null) {
                obj.addProperty("multiBuildingName", safeName(info.multiBuilding));
            }
            if (info.buildingTypeId != null) {
                obj.addProperty("buildingTypeId", info.buildingTypeId.toString());
            }
            if (info.buildingType != null) {
                obj.addProperty("buildingTypeName", safeName(info.buildingType));
            }
            MultiPos multiPos = info.multiPos;
            if (multiPos != null) {
                JsonObject mp = new JsonObject();
                mp.addProperty("x", multiPos.x());
                mp.addProperty("z", multiPos.z());
                mp.addProperty("w", multiPos.w());
                mp.addProperty("h", multiPos.h());
                mp.addProperty("isSingle", multiPos.isSingle());
                mp.addProperty("isTopLeft", multiPos.isTopLeft());
                obj.add("multiPos", mp);
            }
        } catch (Throwable t) {
            obj.addProperty("available", false);
            obj.addProperty("error", t.getClass().getSimpleName());
        }
        return obj;
    }

    private static String safeName(Object target) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod("getName");
            Object v = m.invoke(target);
            if (v != null) {
                return String.valueOf(v);
            }
        } catch (Throwable ignored) {
        }
        return String.valueOf(target);
    }

    private static String sanitize(String label) {
        String cleaned = label.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() > 48 ? cleaned.substring(0, 48) : cleaned;
    }
}
