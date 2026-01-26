package org.admany.lc2h.dev.debug.chunk;

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
import mcjty.lostcities.worldgen.lost.DamageArea;
import mcjty.lostcities.worldgen.lost.Explosion;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.admany.lc2h.config.ConfigManager;
import org.admany.lc2h.dev.diagnostics.ChunkGenTracker;
import org.admany.lc2h.mixin.accessor.lostcities.MultiChunkAccessor;
import org.admany.lc2h.util.lostcities.MultiChunkCacheAccess;
import org.admany.lc2h.worldgen.async.snapshot.MultiChunkSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.stream.Collectors;

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
                BuildingInfo buildingInfo = null;
                try {
                    if (provider != null) {
                        buildingInfo = BuildingInfo.getBuildingInfo(coord, provider);
                    }
                } catch (Throwable ignored) {
                }
                chunk.add("characteristics", buildCharacteristicsJson(provider, coord, buildingInfo));
                chunk.add("undergroundScan", buildUndergroundScanJson(player, coord, buildingInfo));
                chunk.add("structures", buildStructureDebugJson(player.level(), coord));
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

            MultiChunk multiChunk = MultiChunkCacheAccess.get(multiCoord);
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

    private static JsonObject buildCharacteristicsJson(IDimensionInfo provider, ChunkCoord coord, BuildingInfo buildingInfo) {
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
            int groundLevel = 0;
            int profileGround = 0;
            try {
                if (buildingInfo != null) {
                    groundLevel = buildingInfo.getCityGroundLevel();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (provider != null && provider.getProfile() != null) {
                    profileGround = provider.getProfile().GROUNDLEVEL;
                }
            } catch (Throwable ignored) {
            }
            int minDamageOffset = Math.max(0, Integer.getInteger("lc2h.damage.minCityOffset", 0));
            int baseY = groundLevel > 0 ? groundLevel : (profileGround > 0 ? profileGround : info.cityLevel);
            int minDamageY = baseY - minDamageOffset;
            obj.addProperty("damageMinCityOffset", minDamageOffset);
            obj.addProperty("damageMinCityY", minDamageY);
            obj.addProperty("cityGroundLevel", groundLevel);
            obj.addProperty("profileGroundLevel", profileGround);
            obj.addProperty("debrisEnabled", ConfigManager.ENABLE_EXPLOSION_DEBRIS);
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
            try {
                if (buildingInfo != null) {
                    DamageArea area = buildingInfo.getDamageArea();
                    List<Explosion> explosions = area != null ? area.getExplosions() : null;
                    JsonObject damageObj = new JsonObject();
                    if (explosions == null || explosions.isEmpty()) {
                        damageObj.addProperty("explosionCount", 0);
                    } else {
                        int minY = Integer.MAX_VALUE;
                        int maxY = Integer.MIN_VALUE;
                        for (Explosion explosion : explosions) {
                            if (explosion == null || explosion.getCenter() == null) {
                                continue;
                            }
                            int y = explosion.getCenter().getY();
                            if (y < minY) minY = y;
                            if (y > maxY) maxY = y;
                        }
                        damageObj.addProperty("explosionCount", explosions.size());
                        if (minY != Integer.MAX_VALUE) {
                            damageObj.addProperty("explosionMinY", minY);
                        }
                        if (maxY != Integer.MIN_VALUE) {
                            damageObj.addProperty("explosionMaxY", maxY);
                        }
                    }
                    if (area != null) {
                        try {
                            damageObj.addProperty("damageFactor", area.getDamageFactor());
                        } catch (Throwable ignored) {
                        }
                    }
                    obj.add("damage", damageObj);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            obj.addProperty("available", false);
            obj.addProperty("error", t.getClass().getSimpleName());
        }
        return obj;
    }

    private static JsonArray buildStructureDebugJson(Level level, ChunkCoord coord) {
        JsonArray out = new JsonArray();
        if (level == null || coord == null) {
            return out;
        }
        try {
            LevelChunk chunk = level.getChunk(coord.chunkX(), coord.chunkZ());
            if (chunk == null) {
                return out;
            }
            Map<Structure, StructureStart> starts = chunk.getAllStarts();
            if (starts == null || starts.isEmpty()) {
                return out;
            }
            for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
                Structure structure = entry.getKey();
                StructureStart start = entry.getValue();
                if (start == null || !start.isValid()) {
                    continue;
                }
                JsonObject obj = new JsonObject();
                ResourceLocation key = null;
                try {
                    key = level.registryAccess()
                        .registryOrThrow(Registries.STRUCTURE)
                        .getKey(structure);
                } catch (Throwable ignored) {
                }
                if (key != null) {
                    obj.addProperty("id", key.toString());
                } else if (structure != null) {
                    obj.addProperty("id", structure.toString());
                }
                try {
                    var bb = start.getBoundingBox();
                    if (bb != null) {
                        obj.addProperty("minX", bb.minX());
                        obj.addProperty("minY", bb.minY());
                        obj.addProperty("minZ", bb.minZ());
                        obj.addProperty("maxX", bb.maxX());
                        obj.addProperty("maxY", bb.maxY());
                        obj.addProperty("maxZ", bb.maxZ());
                    }
                } catch (Throwable ignored) {
                }
                out.add(obj);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static JsonObject buildUndergroundScanJson(ServerPlayer player, ChunkCoord coord, BuildingInfo buildingInfo) {
        JsonObject obj = new JsonObject();
        if (player == null || coord == null) {
            obj.addProperty("available", false);
            obj.addProperty("reason", "player-missing");
            return obj;
        }

        Level level = player.level();
        if (level == null) {
            obj.addProperty("available", false);
            obj.addProperty("reason", "level-missing");
            return obj;
        }

        int baseY = 0;
        try {
            if (buildingInfo != null) {
                baseY = buildingInfo.getCityGroundLevel();
            }
        } catch (Throwable ignored) {
        }
        if (baseY <= 0 && buildingInfo != null) {
            try {
                baseY = buildingInfo.profile != null ? buildingInfo.profile.GROUNDLEVEL : 0;
            } catch (Throwable ignored) {
            }
        }

        if (baseY <= 0) {
            obj.addProperty("available", false);
            obj.addProperty("reason", "no-baseY");
            return obj;
        }

        int scanMaxY = baseY - 1;
        int scanMinY = Math.max(level.getMinBuildHeight(), baseY - 64);
        if (scanMaxY < scanMinY) {
            obj.addProperty("available", false);
            obj.addProperty("reason", "invalid-scan-range");
            return obj;
        }

        int airCount = 0;
        int airMinY = Integer.MAX_VALUE;
        int airMaxY = Integer.MIN_VALUE;
        int stairCount = 0;
        int mossyStoneBricksCount = 0;
        int mossyStoneBrickStairsCount = 0;
        int stoneBrickStairsCount = 0;
        int total = 0;

        Map<String, Integer> blockCounts = new HashMap<>();
        Map<String, Integer> airNeighborCounts = new HashMap<>();

        int baseX = coord.chunkX() << 4;
        int baseZ = coord.chunkZ() << 4;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = scanMinY; y <= scanMaxY; y++) {
                    BlockState state = level.getBlockState(new BlockPos(baseX + x, y, baseZ + z));
                    total++;
                    if (state == null || state.isAir()) {
                        airCount++;
                        if (y < airMinY) {
                            airMinY = y;
                        }
                        if (y > airMaxY) {
                            airMaxY = y;
                        }
                        // Track what surrounds air pockets to identify carving sources.
                        collectAirNeighbors(level, baseX + x, y, baseZ + z, airNeighborCounts);
                        continue;
                    }
                    Block block = state.getBlock();
                    if (block != null) {
                        if (state.is(BlockTags.STAIRS)) {
                            stairCount++;
                        }
                        if (block == Blocks.MOSSY_STONE_BRICKS) {
                            mossyStoneBricksCount++;
                        } else if (block == Blocks.MOSSY_STONE_BRICK_STAIRS) {
                            mossyStoneBrickStairsCount++;
                        } else if (block == Blocks.STONE_BRICK_STAIRS) {
                            stoneBrickStairsCount++;
                        }

                        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
                        if (key != null) {
                            String id = key.toString();
                            blockCounts.put(id, blockCounts.getOrDefault(id, 0) + 1);
                        }
                    }
                }
            }
        }

        obj.addProperty("available", true);
        obj.addProperty("scanMinY", scanMinY);
        obj.addProperty("scanMaxY", scanMaxY);
        obj.addProperty("totalBlocks", total);
        obj.addProperty("airCount", airCount);
        if (airCount > 0) {
            obj.addProperty("airMinY", airMinY);
            obj.addProperty("airMaxY", airMaxY);
            obj.addProperty("airDepthMin", baseY - airMaxY);
            obj.addProperty("airDepthMax", baseY - airMinY);
        }
        obj.addProperty("stairCount", stairCount);
        obj.addProperty("mossyStoneBricksCount", mossyStoneBricksCount);
        obj.addProperty("mossyStoneBrickStairsCount", mossyStoneBrickStairsCount);
        obj.addProperty("stoneBrickStairsCount", stoneBrickStairsCount);

        List<Map.Entry<String, Integer>> topBlocks = blockCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .limit(8)
            .collect(Collectors.toList());
        JsonArray top = new JsonArray();
        for (Map.Entry<String, Integer> entry : topBlocks) {
            JsonObject item = new JsonObject();
            item.addProperty("id", entry.getKey());
            item.addProperty("count", entry.getValue());
            top.add(item);
        }
        obj.add("topBlocks", top);

        List<Map.Entry<String, Integer>> topNeighbors = airNeighborCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .limit(8)
            .collect(Collectors.toList());
        JsonArray airNeighbors = new JsonArray();
        for (Map.Entry<String, Integer> entry : topNeighbors) {
            JsonObject item = new JsonObject();
            item.addProperty("id", entry.getKey());
            item.addProperty("count", entry.getValue());
            airNeighbors.add(item);
        }
        obj.add("airNeighborTopBlocks", airNeighbors);
        return obj;
    }

    private static void collectAirNeighbors(Level level, int x, int y, int z, Map<String, Integer> counts) {
        if (level == null || counts == null) {
            return;
        }
        collectNeighbor(level, x + 1, y, z, counts);
        collectNeighbor(level, x - 1, y, z, counts);
        collectNeighbor(level, x, y + 1, z, counts);
        collectNeighbor(level, x, y - 1, z, counts);
        collectNeighbor(level, x, y, z + 1, counts);
        collectNeighbor(level, x, y, z - 1, counts);
    }

    private static void collectNeighbor(Level level, int x, int y, int z, Map<String, Integer> counts) {
        try {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (state == null || state.isAir()) {
                return;
            }
            Block block = state.getBlock();
            if (block == null) {
                return;
            }
            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
            if (key == null) {
                return;
            }
            String id = key.toString();
            counts.put(id, counts.getOrDefault(id, 0) + 1);
        } catch (Throwable ignored) {
        }
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
