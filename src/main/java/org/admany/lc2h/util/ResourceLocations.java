package org.admany.lc2h.util;

import net.minecraft.resources.ResourceLocation;

public final class ResourceLocations {

    private ResourceLocations() {
    }

    public static ResourceLocation of(String namespace, String path) {
        return parse(namespace + ":" + path);
    }

    public static ResourceLocation parse(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            throw new IllegalArgumentException("Invalid resource location: " + id);
        }
        return location;
    }
}
