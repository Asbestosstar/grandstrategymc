package com.asbestosstar.grandstrategy.common.mixin;

import com.asbestosstar.grandstrategy.common.world.WorldSessionManager;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** Runs Grand Strategy only while an actual Minecraft world server exists. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Inject(method = "tickServer", at = @At("TAIL"))
    private void grandstrategy$afterWorldTick(BooleanSupplier haveTime, CallbackInfo ci) {
        WorldSessionManager.onServerTick((MinecraftServer) (Object) this);
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void grandstrategy$beforeServerStop(CallbackInfo ci) {
        WorldSessionManager.onServerStopping((MinecraftServer) (Object) this);
    }
}



