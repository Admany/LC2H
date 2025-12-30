package org.admany.lc2h.mixin.minecraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.admany.lc2h.logging.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@OnlyIn(Dist.CLIENT)
@Mixin(value = Minecraft.class)
public abstract class MixinAutoConfirmExperimentalWarningScreen {

    @Inject(method = "setScreen", at = @At("TAIL"), require = 0)
    private void lc2h$autoConfirmExperimental(Screen screen, CallbackInfo ci) {
        if (!ConfigManager.HIDE_EXPERIMENTAL_WARNING) {
            return;
        }
        if (screen == null) {
            return;
        }
        String name = screen.getClass().getName();
        if (!name.endsWith("ConfirmExperimentalSettingsScreen") && !name.contains("ConfirmExperimental")) {
            return;
        }

        tryInvokeAccept(screen);
    }

    private static void tryInvokeAccept(Screen screen) {
        Class<?> cls = screen.getClass();
        Field[] fields;
        try {
            fields = cls.getDeclaredFields();
        } catch (Throwable t) {
            return;
        }

        for (Field f : fields) {
            Object value;
            try {
                f.setAccessible(true);
                value = f.get(screen);
            } catch (Throwable t) {
                continue;
            }
            if (value == null) {
                continue;
            }

            if (value instanceof java.util.function.Consumer<?> c) {
                try {
                    @SuppressWarnings("unchecked")
                    java.util.function.Consumer<Boolean> bc = (java.util.function.Consumer<Boolean>) c;
                    bc.accept(Boolean.TRUE);
                    return;
                } catch (Throwable ignored) {
                }
            }

            Method acceptBool = findAcceptBoolean(value.getClass());
            if (acceptBool != null) {
                try {
                    acceptBool.setAccessible(true);
                    acceptBool.invoke(value, true);
                    return;
                } catch (Throwable ignored) {
                }
            }

            Method acceptObj = findAcceptObject(value.getClass());
            if (acceptObj != null) {
                try {
                    acceptObj.setAccessible(true);
                    acceptObj.invoke(value, Boolean.TRUE);
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Method findAcceptBoolean(Class<?> cls) {
        try {
            return cls.getMethod("accept", boolean.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findAcceptObject(Class<?> cls) {
        try {
            return cls.getMethod("accept", Object.class);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
