package org.admany.lc2h.tweaks;

import mcjty.lostcities.varia.ChunkCoord;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;


public final class ComputationRequestKey {

    private final ComputationRequest.TaskType type;
    private final ChunkCoord coord;
    private final ResourceKey<Level> dimension;

    public ComputationRequestKey(ComputationRequest.TaskType type, ChunkCoord coord, ResourceKey<Level> dimension) {
        this.type = Objects.requireNonNull(type, "type");
        this.coord = Objects.requireNonNull(coord, "coord");
        this.dimension = dimension; // May be null for legacy providers
    }

    public ComputationRequest.TaskType type() {
        return type;
    }

    public ChunkCoord coord() {
        return coord;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ComputationRequestKey that)) return false;
        if (type != that.type) return false;
        if (!coord.equals(that.coord)) return false;
        return Objects.equals(dimension, that.dimension);
    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + coord.hashCode();
        result = 31 * result + (dimension != null ? dimension.hashCode() : 0);
        return result;
    }
}