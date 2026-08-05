package org.admany.lc2h.mixin.lostcities.terrain;

import mcjty.lostcities.setup.ForgeEventHandlers;
import mcjty.lostcities.worldgen.GlobalTodo;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = ForgeEventHandlers.class, remap = false)
public abstract class MixinForgeEventHandlersTodoPrimeGate {

    @Redirect(
        method = "onWorldTick",
        at = @At(
            value = "INVOKE",
            target = "Lmcjty/lostcities/worldgen/GlobalTodo;executeAndClearTodo(Lnet/minecraft/server/level/ServerLevel;)V"
        ),
        require = 0,
        expect = 0,
        remap = false
    )
    private void lc2h$skipTodoDuringParityPrime(GlobalTodo todo, ServerLevel level) {
        todo.executeAndClearTodo(level);
    }
}
