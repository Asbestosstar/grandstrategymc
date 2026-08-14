package com.asbestosstar.grandstrategy.common.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;

/**
 * Dedicated physical body for Grand Strategy population.
 *
 * The server entity is deliberately only a PathfinderMob: it has no vanilla
 * Villager brain, profession, work-site, gossip, breeding or schedule system.
 * Grand Strategy's WorkerBrainState/PhysicalVillagerSystem is its only AI.
 *
 * For loader-independent client rendering it advertises the vanilla HUSK entity
 * type on the wire. That gives clients a built-in humanoid renderer without
 * requiring Fabric/Forge/NeoForge renderer registration. On the authoritative
 * server it is NOT a Husk/Monster and receives no zombie AI or daylight logic.
 * If a world reload materialises the saved backing type as a vanilla Husk,
 * PhysicalVillagerSystem replaces it with this server-side body on its next tick.
 */
public final class GrandStrategyHumanoidEntity extends PathfinderMob {
    public GrandStrategyHumanoidEntity(Level level) {
        super(EntityType.HUSK, level);
    }

    @Override
    protected void registerGoals() {
        // Intentionally empty. WorkerBrainState is the complete behaviour system.
    }

    public static GrandStrategyHumanoidEntity spawn(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return null;
        GrandStrategyHumanoidEntity humanoid = new GrandStrategyHumanoidEntity(level);
        humanoid.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return level.addFreshEntity(humanoid) ? humanoid : null;
    }
}


