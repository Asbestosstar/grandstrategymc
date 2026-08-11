package com.asbestosstar.grandstrategy.common.mixin;

import com.asbestosstar.grandstrategy.common.network.NetworkManager;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides and consumes Grand Strategy's loader-independent server sync messages.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

	@Inject(method = "handleSystemChat", at = @At("HEAD"), cancellable = true)
	private void grandstrategy$handleSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
		if (NetworkManager.getInstance().handleClientSystemMessage(packet.content().getString())) {
			ci.cancel();
		}
	}
}
