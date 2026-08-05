package org.admany.lc2h.testutil;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;

public final class TestResourceKeys {
    private static final Method CREATE_METHOD = resolveCreateMethod();

    private TestResourceKeys() {
    }

    public static ResourceKey<Level> testDimension(String id) {
        try {
            @SuppressWarnings("unchecked")
            ResourceKey<Level> key = (ResourceKey<Level>) CREATE_METHOD.invoke(
                null,
                ResourceLocation.parse("minecraft:dimension"),
                ResourceLocation.parse(id)
            );
            return key;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to create test ResourceKey for " + id, e);
        }
    }

    private static Method resolveCreateMethod() {
        try {
            Method method = ResourceKey.class.getDeclaredMethod("create", ResourceLocation.class, ResourceLocation.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to resolve private ResourceKey#create(ResourceLocation, ResourceLocation)", e);
        }
    }
}
