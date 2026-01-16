package org.admany.lc2h.dev.debug.chunk;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

public record ChunkDebugSelection(
    boolean enabled,
    ResourceLocation dimension,
    ChunkPos primary,
    ChunkPos secondary,
    Integer primaryY,
    Integer secondaryY
) {
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
        buf.writeBoolean(enabled);
        buf.writeResourceLocation(dimension != null ? dimension : ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
        buf.writeBoolean(primary != null);
        if (primary != null) {
            buf.writeInt(primary.x);
            buf.writeInt(primary.z);
        }
        buf.writeBoolean(secondary != null);
        if (secondary != null) {
            buf.writeInt(secondary.x);
            buf.writeInt(secondary.z);
        }
        buf.writeBoolean(primaryY != null);
        if (primaryY != null) {
            buf.writeInt(primaryY);
        }
        buf.writeBoolean(secondaryY != null);
        if (secondaryY != null) {
            buf.writeInt(secondaryY);
        }
    }

    public static ChunkDebugSelection decode(net.minecraft.network.FriendlyByteBuf buf) {
        boolean enabled = buf.readBoolean();
        ResourceLocation dimension = buf.readResourceLocation();
        ChunkPos primary = null;
        if (buf.readBoolean()) {
            int x = buf.readInt();
            int z = buf.readInt();
            primary = new ChunkPos(x, z);
        }
        ChunkPos secondary = null;
        if (buf.readBoolean()) {
            int x = buf.readInt();
            int z = buf.readInt();
            secondary = new ChunkPos(x, z);
        }
        Integer primaryY = null;
        if (buf.readBoolean()) {
            primaryY = buf.readInt();
        }
        Integer secondaryY = null;
        if (buf.readBoolean()) {
            secondaryY = buf.readInt();
        }
        return new ChunkDebugSelection(enabled, dimension, primary, secondary, primaryY, secondaryY);
    }
}
