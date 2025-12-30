package org.admany.lc2h.tweaks;

import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;


public final class ComputationRequest {

    public enum TaskType {
        MULTI_CHUNK,
        BUILDING_INFO
    }

    private final TaskType type;
    private final ChunkCoord coord;
    private final IDimensionInfo provider;

    public ComputationRequest(TaskType type, ChunkCoord coord, IDimensionInfo provider) {
        this.type = Objects.requireNonNull(type, "type");
        this.coord = Objects.requireNonNull(coord, "coord");
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public TaskType type() {
        return type;
    }

    public ChunkCoord coord() {
        return coord;
    }

    public IDimensionInfo provider() {
        return provider;
    }

    public ResourceKey<Level> dimension() {
        return provider.dimension();
    }

    public ComputationRequestKey key() {
        return new ComputationRequestKey(type, coord, dimension());
    }
}