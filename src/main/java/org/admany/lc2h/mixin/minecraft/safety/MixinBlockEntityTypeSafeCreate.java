package org.admany.lc2h.mixin.minecraft.safety;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockEntityType.class)
public abstract class MixinBlockEntityTypeSafeCreate {

    @Inject(
        method = "create(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void lc2h$skipInvalidBlockEntity(BlockPos pos, BlockState state, CallbackInfoReturnable<BlockEntity> cir) {
        if (state == null || state.isAir()) {
            try {
                ResourceLocation id = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey((BlockEntityType<?>) (Object) this);
                if (id != null && id.getNamespace().equals("create") && id.getPath().equals("crushing_wheel_controller")) {
                    return;
                }
            } catch (Throwable ignored) {
            }
            cir.setReturnValue(null);
        }
    }

    @Inject(
        method = "m_155264_",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void lc2h$skipInvalidBlockEntityObf(BlockPos pos, BlockState state, CallbackInfoReturnable<BlockEntity> cir) {
        if (state == null || state.isAir()) {
            try {
                ResourceLocation id = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey((BlockEntityType<?>) (Object) this);
                if (id != null && id.getNamespace().equals("create") && id.getPath().equals("crushing_wheel_controller")) {
                    return;
                }
            } catch (Throwable ignored) {
            }
            cir.setReturnValue(null);
        }
    }
}
