package org.admany.lc2h.debug.frustum;

import net.minecraft.resources.ResourceLocation;

public record FrustumDebugState(boolean enabled, ResourceLocation dimension) {
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
        buf.writeBoolean(enabled);
        buf.writeBoolean(dimension != null);
        if (dimension != null) {
            buf.writeResourceLocation(dimension);
        }
    }

    public static FrustumDebugState decode(net.minecraft.network.FriendlyByteBuf buf) {
        boolean enabled = buf.readBoolean();
        ResourceLocation dimension = null;
        if (buf.readBoolean()) {
            dimension = buf.readResourceLocation();
        }
        return new FrustumDebugState(enabled, dimension);
    }
}
