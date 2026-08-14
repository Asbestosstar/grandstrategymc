package com.asbestosstar.grandstrategy.common.world;

import com.asbestosstar.grandstrategy.common.GrandStrategyCommon;
import com.asbestosstar.grandstrategy.common.engine.StrategyEngine;
import com.asbestosstar.grandstrategy.common.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Objects;

/** Bridges Minecraft's server/world lifecycle to the loader-agnostic common code. */
public final class WorldSessionManager {
    private static MinecraftServer activeServer;
    private static Path activeWorldRoot;

    private WorldSessionManager() {
    }

    public static synchronized void onServerTick(MinecraftServer server) {
        Objects.requireNonNull(server, "server");

        // tickServer can be reached while the server is still preparing its levels.
        if (!server.isReady()) {
            return;
        }

        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        if (activeServer != server || !worldRoot.equals(activeWorldRoot)) {
            closeActiveSession();
            GrandStrategyCommon.startWorld(worldRoot);
            activeServer = server;
            activeWorldRoot = worldRoot;
        }

        WorldMapTracker.getInstance().tick(server);
        // Every permanent city has a physical beacon command post. Geography is
        // resolved first, then command posts, then the villagers who may capture them.
        ProvidenceCommandPostSystem.tick(server);
        PhysicalVillagerSystem.getInstance().tick(server);
        StrategyEngine.getInstance().tick();
        NetworkManager.getInstance().serverTick(server);
    }

    public static synchronized void onServerStopping(MinecraftServer server) {
        if (server == activeServer) {
            NetworkManager.getInstance().onServerStopped(server);
            closeActiveSession();
        }
    }

    private static void closeActiveSession() {
        if (activeServer != null || StrategyEngine.getInstance().isRunning()) {
            GrandStrategyCommon.stopWorld();
        }
        activeServer = null;
        activeWorldRoot = null;
    }
}




