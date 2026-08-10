package com.asbestosstar.grandstrategy.common;

import com.asbestosstar.grandstrategy.common.data.DataManager;
import com.asbestosstar.grandstrategy.common.data.DefaultRegistry;
import com.asbestosstar.grandstrategy.common.engine.StrategyEngine;
import com.asbestosstar.grandstrategy.common.engine.ProvidenceSystem;
import com.asbestosstar.grandstrategy.common.world.WorldMapTracker;
import com.asbestosstar.grandstrategy.common.world.PhysicalVillagerSystem;
import com.asbestosstar.grandstrategy.common.world.ProvidenceCommandPostSystem;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Loader-agnostic bootstrap and world lifecycle entry point.
 *
 * Mod-loader initialisation must remain lightweight: no world state is loaded and
 * no simulation threads are started until a MinecraftServer is actually running a world.
 */
public final class GrandStrategyCommon {
    public static final String MOD_ID = "grandstrategy";

    private static Path gameDirectory;
    private static boolean initialised;
    private static boolean useDax;

    private GrandStrategyCommon() {
    }

    /**
     * Called by Fabric/Forge/NeoForge/FeatureCreep during mod discovery.
     * This deliberately does not load world data or start the strategy engine.
     */
    public static synchronized void init(File gameDir) {
        Objects.requireNonNull(gameDir, "gameDir");

        Path requestedDirectory = gameDir.toPath().toAbsolutePath().normalize();
        if (initialised) {
            if (!requestedDirectory.equals(gameDirectory)) {
                System.err.println("Grand Strategy was already initialised for " + gameDirectory
                        + "; ignoring second game directory " + requestedDirectory);
            }
            return;
        }

        gameDirectory = requestedDirectory;
        checkDax();
        initialised = true;
        System.out.println("Grand Strategy common bootstrap initialised. Waiting for a world.");
    }

    /**
     * Starts a new world-scoped Grand Strategy session.
     */
    public static synchronized void startWorld(Path worldRoot) {
        ensureInitialised();
        Objects.requireNonNull(worldRoot, "worldRoot");

        Path normalisedWorldRoot = worldRoot.toAbsolutePath().normalize();

        // Never leak mutable state from the previously opened save into this save.
        StrategyEngine.getInstance().stop();
        WorldMapTracker.getInstance().stop();
        ProvidenceCommandPostSystem.reset();
        ProvidenceSystem.resetRuntimeState();
        DataManager.clearAll();

        // Built-ins first, then global user content, then save-specific overrides.
        DefaultRegistry.registerDefaults();
        DataManager.loadData(gameDirectory.toFile());
        if (!normalisedWorldRoot.equals(gameDirectory)) {
            DataManager.loadData(normalisedWorldRoot.toFile());
        }

        WorldMapTracker.getInstance().start(normalisedWorldRoot);
        StrategyEngine.getInstance().start(normalisedWorldRoot);
        PhysicalVillagerSystem.getInstance().start(normalisedWorldRoot);
        System.out.println("Grand Strategy world session started: " + normalisedWorldRoot);
    }

    /**
     * Stops and saves the active world session.
     */
    public static synchronized void stopWorld() {
        PhysicalVillagerSystem.getInstance().stop();
        StrategyEngine.getInstance().stop();
        WorldMapTracker.getInstance().stop();
        ProvidenceCommandPostSystem.reset();
        ProvidenceSystem.resetRuntimeState();
        DataManager.clearAll();
    }

    public static boolean isDaxAvailable() {
        return useDax;
    }

    public static Path getGameDirectory() {
        ensureInitialised();
        return gameDirectory;
    }

    private static void ensureInitialised() {
        if (!initialised) {
            throw new IllegalStateException("Grand Strategy common bootstrap has not been initialised");
        }
    }

    private static void checkDax() {
        String arch = System.getProperty("os.arch");
        if (arch == null || !arch.toLowerCase().contains("sparc")) {
            useDax = false;
            return;
        }

        try {
            Class.forName("com.oracle.dax.DaxIntStream");
            useDax = true;
            System.out.println("SPARC DAX acceleration detected and available.");
        } catch (ClassNotFoundException e) {
            useDax = false;
            System.out.println("SPARC architecture detected, but DaxIntStream was not found.");
        }
    }
}



