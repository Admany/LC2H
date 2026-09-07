package org.admany.lc2h.util.chunk;

import org.admany.lc2h.config.ConfigManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FloatingVegetationCandidateTest {

    @Test
    void builtInAttachmentIdsRemainRecognizedBeforeOptionalRegistriesAreReady() {
        assertTrue(ConfigManager.isFloatingVegetationId("minecraft:glow_lichen"));
        assertTrue(ConfigManager.isFloatingVegetationId("IMMERSIVE_WEATHERING:FROST"));
    }

    @Test
    void chunkScanCursorMovesPastTheFinalBlock() throws Exception {
        // Load the nested cursor without initializing the Minecraft-backed
        // outer class; this keeps the boundary test usable in plain JUnit.
        Class<?> cursorType = Class.forName(
            "org.admany.lc2h.util.chunk.ChunkPostProcessor$ScanCursor",
            false,
            getClass().getClassLoader());
        Constructor<?> constructor = cursorType.getDeclaredConstructor(int.class, int.class, int.class);
        constructor.setAccessible(true);
        Object finalBlock = constructor.newInstance(15, 0, 15);
        Method advance = cursorType.getDeclaredMethod("advance", int.class);
        advance.setAccessible(true);
        Object end = advance.invoke(finalBlock, 0);
        Field y = cursorType.getDeclaredField("y");
        y.setAccessible(true);

        assertEquals(1, y.getInt(end));
    }

}
