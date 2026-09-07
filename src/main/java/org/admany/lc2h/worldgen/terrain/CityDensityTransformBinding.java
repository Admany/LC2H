package org.admany.lc2h.worldgen.terrain;

import net.minecraft.world.level.levelgen.blending.Blender;

/** Lets the terrain gate replace a Blender captured during an earlier status. */
public interface CityDensityTransformBinding {
    void lc2h$bindDensityTransform(Blender blender);
}
