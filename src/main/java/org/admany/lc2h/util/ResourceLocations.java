package org.admany.lc2h.util;

import net.minecraft.resources.ResourceLocation;

public final class ResourceLocations {

    private ResourceLocations() {
    }

    public static ResourceLocation of(String namespace, String path) {
        return parse(namespace + ":" + path);
    }

    public static ResourceLocation parse(String id) {
        ResourceLocation location = tryParse(id);
        if (location == null) {
            throw new IllegalArgumentException("Invalid resource location: " + id);
        }
        return location;
    }

    /**
     * Parses a resource id without linking against the newer tryParse method.
     * The single argument constructor is present in the 1.20.1 runtime and
     * keeps the omni jar compatible with the older Forge mappings too.
     */
    public static ResourceLocation tryParse(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return new ResourceLocation(id.trim());
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
