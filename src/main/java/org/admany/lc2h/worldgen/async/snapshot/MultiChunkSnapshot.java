package org.admany.lc2h.worldgen.async.snapshot;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.lost.MultiChunk;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.admany.lc2h.LC2H;
import org.admany.lc2h.mixin.accessor.MultiChunkAccessor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public final class MultiChunkSnapshot {
    private static final int VERSION = 1;
    private static final Field GRID_FIELD;

    static {
        Field located = null;
        try {
            located = MultiChunk.class.getDeclaredField("buildingGrid");
            located.setAccessible(true);
        } catch (Throwable throwable) {
            LC2H.LOGGER.error("Failed to locate MultiChunk buildingGrid field: {}", throwable.toString());
        }
        GRID_FIELD = located;
    }

    private MultiChunkSnapshot() {
    }

    public static byte[] encode(MultiChunk multiChunk) {
        if (multiChunk == null || !MultiChunkMBReflector.ready()) {
            return null;
        }
        try {
            MultiChunkAccessor accessor = (MultiChunkAccessor) multiChunk;
            ChunkCoord topLeft = accessor.lc2h$getTopLeft();
            int areaSize = accessor.lc2h$getAreaSize();
            Object[][] grid = readGrid(multiChunk);
            List<Cell> cells = captureCells(grid);
            ResourceLocation dimension = topLeft.dimension().location();
            int multiX = Math.floorDiv(topLeft.chunkX(), areaSize);
            int multiZ = Math.floorDiv(topLeft.chunkZ(), areaSize);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(baos);
            data.writeShort(VERSION);
            data.writeUTF(dimension.toString());
            data.writeInt(multiX);
            data.writeInt(multiZ);
            data.writeInt(areaSize);
            data.writeInt(cells.size());
            for (Cell cell : cells) {
                data.writeInt(cell.x());
                data.writeInt(cell.z());
                data.writeUTF(cell.name());
                data.writeInt(cell.offsetX());
                data.writeInt(cell.offsetZ());
            }
            data.flush();
            return baos.toByteArray();
        } catch (Throwable throwable) {
            LC2H.LOGGER.debug("Failed to encode multichunk snapshot: {}", throwable.toString());
            return null;
        }
    }

    public static MultiChunk decode(byte[] payload) {
        if (payload == null || payload.length == 0 || !MultiChunkMBReflector.ready()) {
            return null;
        }
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(payload))) {
            int version = data.readUnsignedShort();
            if (version != VERSION) {
                return null;
            }
            String dimensionId = data.readUTF();
            ResourceLocation dimensionLocation = ResourceLocation.tryParse(dimensionId);
            ResourceKey<Level> dimension = dimensionLocation == null
                ? Level.OVERWORLD
                : ResourceKey.create(Registries.DIMENSION, dimensionLocation);
            int multiX = data.readInt();
            int multiZ = data.readInt();
            int areaSize = data.readInt();
            int cellCount = data.readInt();
            MultiChunk multiChunk = new MultiChunk(new ChunkCoord(dimension, multiX, multiZ), areaSize);
            Object[][] grid = readGrid(multiChunk);
            for (int i = 0; i < cellCount; i++) {
                int x = data.readInt();
                int z = data.readInt();
                String name = data.readUTF();
                int offsetX = data.readInt();
                int offsetZ = data.readInt();
                if (x < 0 || z < 0 || x >= areaSize || z >= areaSize) {
                    continue;
                }
                Object entry = MultiChunkMBReflector.create(multiChunk, name, offsetX, offsetZ);
                if (entry != null) {
                    grid[x][z] = entry;
                }
            }
            return multiChunk;
        } catch (Throwable throwable) {
            LC2H.LOGGER.debug("Failed to decode multichunk snapshot: {}", throwable.toString());
            return null;
        }
    }

    private static List<Cell> captureCells(Object[][] grid) {
        List<Cell> cells = new ArrayList<>();
        if (grid == null) {
            return cells;
        }
        for (int x = 0; x < grid.length; x++) {
            Object[] column = grid[x];
            if (column == null) {
                continue;
            }
            for (int z = 0; z < column.length; z++) {
                Object entry = column[z];
                if (entry == null) {
                    continue;
                }
                String name = MultiChunkMBReflector.name(entry);
                if (name == null || name.isBlank()) {
                    continue;
                }
                int offsetX = MultiChunkMBReflector.offsetX(entry);
                int offsetZ = MultiChunkMBReflector.offsetZ(entry);
                cells.add(new Cell(x, z, name, offsetX, offsetZ));
            }
        }
        return cells;
    }

    private record Cell(int x, int z, String name, int offsetX, int offsetZ) {
    }

    public record MultiChunkCell(int relX, int relZ, String name, int offsetX, int offsetZ) {
    }

    public static MultiChunkCell describeCell(MultiChunk multiChunk, ChunkCoord coord) {
        if (multiChunk == null || coord == null || GRID_FIELD == null || !MultiChunkMBReflector.ready()) {
            return null;
        }
        try {
            MultiChunkAccessor accessor = (MultiChunkAccessor) multiChunk;
            ChunkCoord topLeft = accessor.lc2h$getTopLeft();
            int areaSize = accessor.lc2h$getAreaSize();
            if (topLeft == null) {
                return null;
            }
            int relX = coord.chunkX() - topLeft.chunkX();
            int relZ = coord.chunkZ() - topLeft.chunkZ();
            if (relX < 0 || relZ < 0 || relX >= areaSize || relZ >= areaSize) {
                return null;
            }
            Object[][] grid = readGrid(multiChunk);
            if (grid == null || relX >= grid.length || grid[relX] == null || relZ >= grid[relX].length) {
                return null;
            }
            Object entry = grid[relX][relZ];
            if (entry == null) {
                return null;
            }
            String name = MultiChunkMBReflector.name(entry);
            int offsetX = MultiChunkMBReflector.offsetX(entry);
            int offsetZ = MultiChunkMBReflector.offsetZ(entry);
            return new MultiChunkCell(relX, relZ, name, offsetX, offsetZ);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static Object[][] readGrid(MultiChunk chunk) {
        if (GRID_FIELD == null || chunk == null) {
            return null;
        }
        try {
            Object value = GRID_FIELD.get(chunk);
            if (value instanceof Object[][] array) {
                return array;
            }
        } catch (Throwable throwable) {
            LC2H.LOGGER.debug("Failed to access MultiChunk grid: {}", throwable.toString());
        }
        return null;
    }
}
