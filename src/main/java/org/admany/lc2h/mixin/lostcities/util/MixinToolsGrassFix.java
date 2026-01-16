package org.admany.lc2h.mixin.lostcities.util;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import mcjty.lostcities.varia.Tools;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Tools.class)
public class MixinToolsGrassFix {

    private static final java.util.concurrent.atomic.AtomicReference<java.lang.reflect.Field> BUILTIN_BLOCK_REGISTRY_FIELD =
        new java.util.concurrent.atomic.AtomicReference<>();

    @Inject(method = "stringToState", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fixGrassBlockName(String s, CallbackInfoReturnable<BlockState> cir) {
        if (s == null || s.isEmpty()) {
            return;
        }
        if ("tall_grass".equals(s) || "minecraft:tall_grass".equals(s) || "tallgrass".equals(s) || "minecraft:tallgrass".equals(s)) {
            cir.setReturnValue(Blocks.TALL_GRASS.defaultBlockState());
            return;
        }

        if ("fern".equals(s) || "minecraft:fern".equals(s)) {
            cir.setReturnValue(Blocks.FERN.defaultBlockState());
            return;
        }

        if ("large_fern".equals(s) || "minecraft:large_fern".equals(s)) {
            cir.setReturnValue(Blocks.LARGE_FERN.defaultBlockState());
            return;
        }

        if ("grass".equals(s) || "minecraft:grass".equals(s)) {
            cir.setReturnValue(Blocks.GRASS.defaultBlockState());
        }

        if (!s.contains("[")) {
            return;
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            return;
        }

        HolderLookup<Block> lookup = resolveBuiltInBlockLookup();
        if (lookup == null) {
            return;
        }
        try {
            BlockStateParser.BlockResult parser = BlockStateParser.parseForBlock(lookup, new StringReader(s), false);
            cir.setReturnValue(parser.blockState());
        } catch (CommandSyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static HolderLookup<Block> resolveBuiltInBlockLookup() {
        java.lang.reflect.Field field = BUILTIN_BLOCK_REGISTRY_FIELD.get();
        if (field == null) {
            field = findBuiltInBlockRegistryField();
            if (field != null) {
                BUILTIN_BLOCK_REGISTRY_FIELD.set(field);
            }
        }
        if (field == null) {
            return null;
        }
        try {
            Object registry = field.get(null);
            if (!(registry instanceof net.minecraft.core.Registry<?> reg)) {
                return null;
            }
            return (HolderLookup<Block>) reg.asLookup();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static java.lang.reflect.Field findBuiltInBlockRegistryField() {
        try {
            Class<?> cls = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            java.lang.reflect.Field f = cls.getField("BLOCK");
            f.setAccessible(true);
            return f;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
