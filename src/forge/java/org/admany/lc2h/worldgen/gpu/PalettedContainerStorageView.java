package org.admany.lc2h.worldgen.gpu;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Cached, read-only access to Minecraft's packed section storage.
 *
 * <p>Forge production names private fields differently from the mapped dev
 * workspace. Resolving the three fields once keeps that version detail here;
 * all hot calls are direct cached {@link Field#get(Object)} reads while the
 * container's own threading detector is held.</p>
 */
final class PalettedContainerStorageView {
    private static final Access ACCESS = Access.resolve();

    private PalettedContainerStorageView() {
    }

    static <T> Snapshot<T> capture(PalettedContainer<T> container, int maxPaletteSize) {
        if (!ACCESS.available()) {
            return null;
        }
        container.acquire();
        try {
            Object data = ACCESS.data().get(container);
            BitStorage storage = (BitStorage) ACCESS.storage().get(data);
            @SuppressWarnings("unchecked")
            Palette<T> palette = (Palette<T>) ACCESS.palette().get(data);
            int size = palette.getSize();
            if (size <= 0 || size > maxPaletteSize) {
                return null;
            }
            List<T> entries = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                entries.add(palette.valueFor(i));
            }
            return new Snapshot<>(storage.getBits(), storage.getRaw().clone(), List.copyOf(entries));
        } catch (ReflectiveOperationException | LinkageError exception) {
            return null;
        } finally {
            container.release();
        }
    }

    static String mode() {
        return ACCESS.available() ? "packed-direct" : "unavailable:" + ACCESS.failure();
    }

    record Snapshot<T>(int bits, long[] raw, List<T> paletteEntries) {
    }

    private record Access(Field data, Field storage, Field palette, String failure) {
        static Access resolve() {
            try {
                Field data = field(PalettedContainer.class, "data", "f_188032_");
                Class<?> dataType = data.getType();
                Field storage = field(dataType, "storage", "f_188101_");
                Field palette = field(dataType, "palette", "f_188102_");
                return new Access(data, storage, palette, "none");
            } catch (ReflectiveOperationException | LinkageError exception) {
                return new Access(null, null, null, exception.getClass().getSimpleName() + ":" + exception.getMessage());
            }
        }

        boolean available() {
            return data != null && storage != null && palette != null;
        }

        private static Field field(Class<?> owner, String... names) throws NoSuchFieldException {
            for (String name : names) {
                try {
                    Field field = owner.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                }
            }
            throw new NoSuchFieldException(owner.getName() + " " + String.join("/", names));
        }
    }
}
