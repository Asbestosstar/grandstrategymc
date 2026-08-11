package com.asbestosstar.grandstrategy.common.mixin;

/**
 * Compatibility tombstone for upgrades made by extracting this project over an
 * older copy.
 *
 * The old implementation injected into the client Minecraft tick and advanced
 * the grand strategy simulation even when no world was running. World
 * simulation is now owned by MinecraftServerMixin and WorldSessionManager. This
 * class intentionally has no Mixin annotation and no behaviour, so an archive
 * overwrite safely disables the legacy hook.
 *
 * It may be deleted from a clean checkout.
 */
@Deprecated(forRemoval = true)
public final class MinecraftMixin {
	private MinecraftMixin() {
	}
}
