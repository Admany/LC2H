package org.admany.lc2h.worldgen.async.snapshot;

import mcjty.lostcities.worldgen.lost.MultiChunk;
import org.admany.lc2h.LC2H;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class MultiChunkMBReflector {
    private static final Class<?> MB_CLASS;
    private static final Constructor<?> CTOR;
    private static final boolean REQUIRES_OWNER;
    private static final Method NAME_METHOD;
    private static final Method OFFSET_X_METHOD;
    private static final Method OFFSET_Z_METHOD;

    static {
        Class<?> mbClass = null;
        Constructor<?> ctor = null;
        boolean requiresOwner = false;
        Method nameMethod = null;
        Method offsetXMethod = null;
        Method offsetZMethod = null;
        try {
            mbClass = Class.forName("mcjty.lostcities.worldgen.lost.MultiChunk$MB");
            Constructor<?>[] constructors = mbClass.getDeclaredConstructors();
            if (constructors.length > 0) {
                ctor = constructors[0];
                ctor.setAccessible(true);
                Class<?>[] params = ctor.getParameterTypes();
                requiresOwner = params.length == 4 && MultiChunk.class.isAssignableFrom(params[0]);
            }
            nameMethod = mbClass.getDeclaredMethod("name");
            offsetXMethod = mbClass.getDeclaredMethod("offsetX");
            offsetZMethod = mbClass.getDeclaredMethod("offsetZ");
            nameMethod.setAccessible(true);
            offsetXMethod.setAccessible(true);
            offsetZMethod.setAccessible(true);
        } catch (Throwable throwable) {
            LC2H.LOGGER.error("Failed to initialize MultiChunk MB reflector: {}", throwable.toString());
        }
        MB_CLASS = mbClass;
        CTOR = ctor;
        REQUIRES_OWNER = requiresOwner;
        NAME_METHOD = nameMethod;
        OFFSET_X_METHOD = offsetXMethod;
        OFFSET_Z_METHOD = offsetZMethod;
    }

    private MultiChunkMBReflector() {
    }

    public static boolean ready() {
        return MB_CLASS != null && CTOR != null && NAME_METHOD != null && OFFSET_X_METHOD != null && OFFSET_Z_METHOD != null;
    }

    public static String name(Object entry) {
        if (!ready() || entry == null) {
            return null;
        }
        try {
            return (String) NAME_METHOD.invoke(entry);
        } catch (Throwable throwable) {
            return null;
        }
    }

    public static int offsetX(Object entry) {
        if (!ready() || entry == null) {
            return 0;
        }
        try {
            return (Integer) OFFSET_X_METHOD.invoke(entry);
        } catch (Throwable throwable) {
            return 0;
        }
    }

    public static int offsetZ(Object entry) {
        if (!ready() || entry == null) {
            return 0;
        }
        try {
            return (Integer) OFFSET_Z_METHOD.invoke(entry);
        } catch (Throwable throwable) {
            return 0;
        }
    }

    public static Object create(MultiChunk owner, String name, int offsetX, int offsetZ) {
        if (!ready()) {
            return null;
        }
        try {
            if (REQUIRES_OWNER) {
                return CTOR.newInstance(owner, name, offsetX, offsetZ);
            } else {
                return CTOR.newInstance(name, offsetX, offsetZ);
            }
        } catch (Throwable throwable) {
            LC2H.LOGGER.debug("Failed to construct MultiChunk MB: {}", throwable.toString());
            return null;
        }
    }
}
