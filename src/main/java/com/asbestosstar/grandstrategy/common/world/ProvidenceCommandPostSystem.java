package com.asbestosstar.grandstrategy.common.world;

import com.asbestosstar.grandstrategy.common.data.City;
import com.asbestosstar.grandstrategy.common.data.DataManager;
import com.asbestosstar.grandstrategy.common.data.Providence;
import com.asbestosstar.grandstrategy.common.engine.ProvidenceSystem;
import com.asbestosstar.grandstrategy.common.engine.WarSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Objects;

/** Physical beacon command posts for every permanent providence city. */
public final class ProvidenceCommandPostSystem {
    private static final int RECONCILE_INTERVAL_TICKS = 20;
    private static long ticks;

    private ProvidenceCommandPostSystem() {
    }

    public static void reset() {
        ticks = 0L;
    }

    /** Called on the Minecraft server thread after ProvidenceSystem has updated geography. */
    public static void tick(MinecraftServer server) {
        if (server == null) return;
        ticks++;
        if (ticks % RECONCILE_INTERVAL_TICKS != 0) return;
        ServerLevel level = server.overworld();
        if (level == null) return;
        for (Providence providence : DataManager.getProvidences().values()) {
            ensureCommandPost(level, providence);
        }
    }

    public static BlockPos commandPostPosition(ServerLevel level, Providence providence) {
        if (level == null || providence == null || !providence.isEstablished() || providence.getCity() == null) {
            return null;
        }
        City city = providence.getCity();
        int chunkX = Math.floorDiv(city.getBlockX(), WorldMapTracker.CHUNK_SIZE);
        int chunkZ = Math.floorDiv(city.getBlockZ(), WorldMapTracker.CHUNK_SIZE);
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) return null;

        if (!city.hasCommandPostPosition()) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, city.getBlockX(), city.getBlockZ());
            city.setCommandPostY(y);
        }
        return new BlockPos(city.getBlockX(), city.getCommandPostY(), city.getBlockZ());
    }

    public static BlockPos ensureCommandPost(ServerLevel level, Providence providence) {
        BlockPos pos = commandPostPosition(level, providence);
        if (pos == null) return null;
        if (!level.getBlockState(pos).is(Blocks.BEACON)) {
            level.setBlockAndUpdate(pos, Blocks.BEACON.defaultBlockState());
        }
        return pos;
    }

    /** Peaceful/colonial capture: administrator must already control >50% of chunks. */
    public static boolean captureByAdministrator(ServerLevel level, Providence providence, String civilisationId) {
        if (level == null || providence == null || civilisationId == null || providence.getCity() == null) return false;
        if (providence.territoryControlShare(civilisationId) <= ProvidenceSystem.ADMIN_CAPTURE_THRESHOLD) return false;
        String previous = providence.getCity().getControllerId();
        if (previous != null && WarSystem.getInstance().areAtWar(civilisationId, previous)) return false;
        boolean changed = ProvidenceSystem.captureCommandPost(providence, civilisationId);
        ensureCommandPost(level, providence);
        return changed;
    }

    /**
     * Wartime capture: a soldier physically breaks the enemy beacon; the command
     * post immediately reappears in the same block under the capturer's control.
     */
    public static boolean breakAndCaptureBySoldier(ServerLevel level, Providence providence, String civilisationId) {
        if (level == null || providence == null || civilisationId == null || providence.getCity() == null) return false;
        String previous = providence.getCity().getControllerId();
        if (previous == null || Objects.equals(previous, civilisationId)
                || !WarSystem.getInstance().areAtWar(civilisationId, previous)) return false;
        BlockPos pos = ensureCommandPost(level, providence);
        if (pos == null) return false;

        level.destroyBlock(pos, false);
        boolean changed = ProvidenceSystem.captureCommandPost(providence, civilisationId);
        level.setBlockAndUpdate(pos, Blocks.BEACON.defaultBlockState());
        if (changed) {
            WarSystem.getInstance().reportCommandPostCapture(civilisationId, previous, providence.getId());
        }
        return changed;
    }
}



