package com.asbestosstar.grandstrategy.common.engine;

import com.asbestosstar.grandstrategy.common.GrandStrategyCommon;
import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.DataManager;
import com.asbestosstar.grandstrategy.common.data.Providence;
import com.asbestosstar.grandstrategy.common.world.WorldStateStore;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;

/**
 * World-scoped Grand Strategy simulation engine.
 *
 * The engine is restartable, cannot queue an unbounded number of AI jobs, and applies
 * worker results on the server tick thread rather than mutating live game state from
 * background threads.
 */
public final class StrategyEngine {
    private static final StrategyEngine INSTANCE = new StrategyEngine();
    private static final int STRATEGY_STEP_INTERVAL_TICKS = 20;
    private static final int AUTO_SAVE_INTERVAL_TICKS = 6_000;
    /** Historical AI countries cannot exist during the first five Minecraft days. */
    public static final long HISTORICAL_CIVILISATION_DELAY_TICKS =
            5L * HistoricalTimeline.MINECRAFT_TICKS_PER_DAY;

    private final HistoricalTimeline timeline = new HistoricalTimeline();

    private ForkJoinPool simulationPool;
    private CompletableFuture<SimulationResult> pendingSimulation;
    private Path worldRoot;
    private volatile boolean running;
    private long worldTicks;
    private final ConcurrentLinkedQueue<Runnable> worldActions = new ConcurrentLinkedQueue<>();
    private volatile boolean saveRequested;

    private StrategyEngine() {
    }

    public static StrategyEngine getInstance() {
        return INSTANCE;
    }

    public synchronized void start(Path worldRoot) {
        Path requestedRoot = worldRoot.toAbsolutePath().normalize();
        if (running && requestedRoot.equals(this.worldRoot)) {
            return;
        }
        if (running) {
            stop();
        }

        this.worldRoot = requestedRoot;
        this.worldTicks = 0L;
        this.timeline.reset();
        WarSystem.getInstance().reset();
        SocietySystem.resetRuntimeState();

        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        int parallelism = Math.max(2, processors - 1);
        this.simulationPool = new ForkJoinPool(parallelism);
        this.pendingSimulation = null;
        this.worldActions.clear();
        this.saveRequested = false;

        this.worldTicks = WorldStateStore.load(requestedRoot, timeline);
        this.running = true;
        System.out.println("Strategy Engine started for world " + requestedRoot
                + " with " + parallelism + " simulation workers.");
    }

    /** Called once from the server's world tick, never from the title-screen/client tick. */
    public synchronized void tick() {
        if (!running) {
            return;
        }

        worldTicks++;
        drainWorldActions();
        resolveZeroPopulationCountries();
        applyCompletedSimulation();
        timeline.advanceTimeline();
        if (worldTicks >= HISTORICAL_CIVILISATION_DELAY_TICKS) {
            CivilisationSimulation.activateDueCivilisations(timeline.getCurrentYear());
        }

        if (worldTicks % STRATEGY_STEP_INTERVAL_TICKS == 0) {
            CivilisationSimulation.tickActiveEconomies();
            ResearchSystem.tick(timeline.getCurrentYear());
            SocietySystem.tick(timeline.getCurrentYear());
            FocusAndEventSystem.tick(timeline.getCurrentYear());
            DiplomacySystem.tick(timeline.getCurrentYear());
            resolveZeroPopulationCountries();
            WarSystem.getInstance().tick();
            submitSimulationIfIdle();
        }

        if (saveRequested || worldTicks % AUTO_SAVE_INTERVAL_TICKS == 0) {
            WorldStateStore.save(worldRoot, timeline, worldTicks);
            saveRequested = false;
        }
    }

    private void submitSimulationIfIdle() {
        if (pendingSimulation != null || simulationPool == null || simulationPool.isShutdown()) {
            return;
        }

        SimulationSnapshot snapshot = captureSnapshot();
        pendingSimulation = CompletableFuture.supplyAsync(() -> computeSimulation(snapshot), simulationPool);
    }

    private SimulationSnapshot captureSnapshot() {
        List<CivilisationSnapshot> civilisations = DataManager.getCivilisations().values().stream()
                .filter(Civilisation::isActive)
                .map(civilisation -> new CivilisationSnapshot(
                        civilisation.getId(), civilisation.getStability()))
                .toList();

        List<ProvidenceSnapshot> providences = DataManager.getProvidences().values().stream()
                .map(providence -> new ProvidenceSnapshot(
                        providence.getId(), providence.getOwnerId(), providence.getResistanceLevel()))
                .toList();

        return new SimulationSnapshot(civilisations, providences);
    }

    private SimulationResult computeSimulation(SimulationSnapshot snapshot) {
        Set<String> owners = snapshot.providences().stream()
                .map(ProvidenceSnapshot::ownerId)
                .filter(owner -> owner != null && !owner.isBlank())
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        Map<String, Double> stability = Map.of();

        Set<String> revolts = snapshot.providences().parallelStream()
                .filter(providence -> providence.resistanceLevel() > 0.8)
                .map(ProvidenceSnapshot::id)
                .collect(java.util.stream.Collectors.toSet());

        return new SimulationResult(new HashMap<>(stability), revolts);
    }

    private static double nextStability(CivilisationSnapshot civilisation, Set<String> owners) {
        if ("cliosoffice".equals(civilisation.id())) {
            return 1.0;
        }

        // Missing territorial ownership must not act as a rapid automatic death
        // timer. A country can temporarily be waiting for discovered land, have
        // an old save whose starting providence has not yet been repaired, or be
        // displaced by war. In those cases stability simply stops recovering; it
        // does not lose one percent every background step.
        if (!owners.contains(civilisation.id())) {
            return civilisation.stability();
        }

        return Math.min(1.0, civilisation.stability() + 0.005);
    }

    private void applyCompletedSimulation() {
        if (pendingSimulation == null || !pendingSimulation.isDone()) {
            return;
        }

        try {
            SimulationResult result = pendingSimulation.join();
            result.stabilityByCivilisation().forEach((id, stability) -> {
                Civilisation civilisation = DataManager.getCivilisations().get(id);
                if (civilisation != null) {
                    civilisation.setStability(stability);
                    maybeCollapseCivilisation(civilisation);
                }
            });

            for (String providenceId : result.revoltingProvidences()) {
                Providence providence = DataManager.getProvidences().get(providenceId);
                if (providence != null && providence.getResistanceLevel() > 0.8) {
                    System.out.println("Revolt triggered in providence: " + providence.getName());
                }
            }
        } catch (RuntimeException e) {
            System.err.println("Grand Strategy background simulation failed.");
            e.printStackTrace();
        } finally {
            pendingSimulation = null;
        }
    }


    /**
     * Zero physical population is a hard end-state. This check runs every server
     * tick and again after strategy effects so deaths and population-loss events
     * cannot leave a zero-population country active on the map.
     */
    private static void resolveZeroPopulationCountries() {
        for (Civilisation civilisation : List.copyOf(DataManager.getCivilisations().values())) {
            if (civilisation != null && civilisation.isActive() && civilisation.getPopulation() <= 0) {
                WarSystem.getInstance().resolveZeroPopulation(civilisation);
            }
        }
    }

    /** Backwards-compatible call site used by the background simulation completion. */
    private static void maybeCollapseCivilisation(Civilisation civilisation) {
        if (civilisation != null && civilisation.getPopulation() <= 0) {
            WarSystem.getInstance().resolveZeroPopulation(civilisation);
        }
    }

    /**
     * Queues a GUI/request mutation to be applied on the world server tick thread.
     * This keeps the feature loader-independent and avoids mutating live state from
     * the client render thread in an integrated world.
     */
    public boolean enqueueWorldAction(Runnable action) {
        if (action == null || !running) return false;
        worldActions.add(action);
        return true;
    }

    public void requestSave() {
        if (running) saveRequested = true;
    }

    /**
     * Operator/testing hook for moving only the Grand Strategy historical clock.
     * Unlike natural progression, an explicit cheat jump immediately checks and
     * activates every historical civilisation now due, bypassing the normal
     * five-Minecraft-day startup gate for this command only.
     */
    public synchronized long cheatAdvanceHistoricalDays(long days) {
        if (!running || days <= 0L) return 0L;

        long advanced = timeline.cheatAdvanceDays(days);
        if (advanced > 0L) {
            CivilisationSimulation.activateDueCivilisations(timeline.getCurrentYear());
            saveRequested = true;
        }
        return advanced;
    }

    private void drainWorldActions() {
        Runnable action;
        boolean changed = false;
        while ((action = worldActions.poll()) != null) {
            try {
                action.run();
                changed = true;
            } catch (RuntimeException e) {
                System.err.println("Grand Strategy queued world action failed.");
                e.printStackTrace();
            }
        }
        if (changed) saveRequested = true;
    }

    public synchronized void performAcceleratedOperation(int[] data) {
        if (data == null || data.length == 0) {
            return;
        }

        if (GrandStrategyCommon.isDaxAvailable()) {
            try {
                Class<?> daxClass = Class.forName("com.oracle.dax.DaxIntStream");
                java.lang.reflect.Method ofMethod = daxClass.getMethod("of", int[].class);
                ofMethod.invoke(null, (Object) data);
                return;
            } catch (ReflectiveOperationException e) {
                System.err.println("SPARC DAX operation failed; falling back to standard parallel processing.");
            }
        }

        performParallelStandard(data);
    }

    private void performParallelStandard(int[] data) {
        ForkJoinPool pool = simulationPool;
        if (pool == null || pool.isShutdown()) {
            IntStream.of(data).parallel().forEach(value -> { });
            return;
        }

        pool.submit(() -> IntStream.of(data).parallel().forEach(value -> { })).join();
    }

    public synchronized void stop() {
        if (!running && simulationPool == null) {
            return;
        }

        // Apply an already completed result, but never hold world shutdown waiting on AI work.
        applyCompletedSimulation();
        if (pendingSimulation != null && !pendingSimulation.isDone()) {
            pendingSimulation.cancel(true);
            pendingSimulation = null;
        }

        if (running && worldRoot != null) {
            WorldStateStore.save(worldRoot, timeline, worldTicks);
        }

        running = false;

        if (simulationPool != null) {
            simulationPool.shutdownNow();
            try {
                simulationPool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            simulationPool = null;
        }

        worldActions.clear();
        saveRequested = false;
        worldRoot = null;
        worldTicks = 0L;
        System.out.println("Strategy Engine stopped.");
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized long getWorldTicks() {
        return worldTicks;
    }

    public synchronized long getHistoricalCivilisationDelayRemainingTicks() {
        return Math.max(0L, HISTORICAL_CIVILISATION_DELAY_TICKS - worldTicks);
    }

    public HistoricalTimeline getTimeline() {
        return timeline;
    }

    private record CivilisationSnapshot(String id, double stability) { }
    private record ProvidenceSnapshot(String id, String ownerId, double resistanceLevel) { }
    private record SimulationSnapshot(List<CivilisationSnapshot> civilisations,
                                      List<ProvidenceSnapshot> providences) { }
    private record SimulationResult(Map<String, Double> stabilityByCivilisation,
                                    Set<String> revoltingProvidences) { }
}




