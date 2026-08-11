package com.asbestosstar.grandstrategy.common.network;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.FactoryRecipe;
import com.asbestosstar.grandstrategy.common.data.FactoryType;
import com.asbestosstar.grandstrategy.common.data.Ideology;
import com.asbestosstar.grandstrategy.common.data.Leader;
import com.asbestosstar.grandstrategy.common.data.Religion;
import com.asbestosstar.grandstrategy.common.data.Technology;
import com.asbestosstar.grandstrategy.common.data.FocusTree;
import com.asbestosstar.grandstrategy.common.data.GrandStrategyEvent;
import com.asbestosstar.grandstrategy.common.data.Providence;
import com.asbestosstar.grandstrategy.common.engine.WarSystem;
import com.asbestosstar.grandstrategy.common.world.PhysicalVillagerSystem;
import com.asbestosstar.grandstrategy.common.world.WorldMapTracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-side read-only mirror of server-owned Grand Strategy state.
 *
 * A dedicated-server client must never read the client's local DataManager as if
 * it were the server world. All GUI screens read this mirror instead.
 */
public final class StrategyClientState {
    private static final StrategyClientState INSTANCE = new StrategyClientState();

    private volatile Map<String, Civilisation> civilisations = Map.of();
    private volatile Map<String, Providence> providences = Map.of();
    private volatile Map<String, FocusTree> focusTrees = Map.of();
    private volatile Map<String, GrandStrategyEvent> events = Map.of();
    private volatile Map<String, Technology> technologies = Map.of();
    private volatile Map<String, FactoryType> factoryTypes = Map.of();
    private volatile Map<String, FactoryRecipe> factoryRecipes = Map.of();
    private volatile Map<String, Religion> religions = Map.of();
    private volatile Map<String, Ideology> ideologies = Map.of();
    private volatile Map<String, Leader> leaders = Map.of();
    private volatile List<WarSystem.WarState> wars = List.of();
    private volatile List<PhysicalVillagerSystem.VillagerMapMarker> villagers = List.of();
    private volatile List<PhysicalVillagerSystem.WorkZoneMapMarker> workZones = List.of();
    private volatile WorldMapTracker.Snapshot mapSnapshot = WorldMapTracker.Snapshot.empty();
    private volatile long currentYear = -3_900;
    private volatile int currentDay;
    private volatile double gsDaysPerMinecraftDay = 36_500.0;
    private volatile boolean synchronised;
    private volatile long lastSyncMillis;

    private StrategyClientState() {
    }

    public static StrategyClientState getInstance() {
        return INSTANCE;
    }

    synchronized void apply(NetworkManager.SyncState state) {
        if (state == null) return;

        Map<String, Civilisation> nextCivilisations = new HashMap<>();
        if (state.civilisations != null) {
            for (Civilisation civilisation : state.civilisations) {
                if (civilisation == null || civilisation.getId() == null) continue;
                civilisation.normaliseAfterLoad();
                nextCivilisations.put(civilisation.getId(), civilisation);
            }
        }

        Map<String, Providence> nextProvidences = new HashMap<>();
        if (state.providences != null) {
            for (Providence providence : state.providences) {
                if (providence == null || providence.getId() == null) continue;
                providence.normaliseAfterLoad();
                nextProvidences.put(providence.getId(), providence);
            }
        }

        Map<String, FocusTree> nextFocusTrees = new HashMap<>();
        if (state.focusTrees != null) {
            for (FocusTree tree : state.focusTrees) {
                if (tree == null || tree.getCivilisationId() == null) continue;
                nextFocusTrees.put(tree.getCivilisationId(), tree);
            }
        }

        Map<String, GrandStrategyEvent> nextEvents = new HashMap<>();
        if (state.events != null) {
            for (GrandStrategyEvent event : state.events) {
                if (event == null || event.getId() == null) continue;
                nextEvents.put(event.getId(), event);
            }
        }

        Map<String, Technology> nextTechnologies = new HashMap<>();
        if (state.technologies != null) for (Technology value : state.technologies)
            if (value != null && value.getId() != null) nextTechnologies.put(value.getId(), value);
        Map<String, FactoryType> nextFactoryTypes = new HashMap<>();
        if (state.factoryTypes != null) for (FactoryType value : state.factoryTypes)
            if (value != null && value.getId() != null) nextFactoryTypes.put(value.getId(), value);
        Map<String, FactoryRecipe> nextFactoryRecipes = new HashMap<>();
        if (state.factoryRecipes != null) for (FactoryRecipe value : state.factoryRecipes)
            if (value != null && value.getId() != null) nextFactoryRecipes.put(value.getId(), value);
        Map<String, Religion> nextReligions = new HashMap<>();
        if (state.religions != null) for (Religion value : state.religions)
            if (value != null && value.getId() != null) nextReligions.put(value.getId(), value);
        Map<String, Ideology> nextIdeologies = new HashMap<>();
        if (state.ideologies != null) for (Ideology value : state.ideologies)
            if (value != null && value.getId() != null) nextIdeologies.put(value.getId(), value);
        Map<String, Leader> nextLeaders = new HashMap<>();
        if (state.leaders != null) for (Leader value : state.leaders) {
            if (value == null || value.getId() == null) continue;
            value.normaliseAfterLoad();
            nextLeaders.put(value.getId(), value);
        }

        WorldMapTracker.Snapshot previousMap = this.mapSnapshot;
        Map<Long, WorldMapTracker.MapTile> mergedTiles = new HashMap<>();
        if (!state.fullMap && previousMap != null) {
            for (WorldMapTracker.MapTile tile : previousMap.tiles()) {
                if (tile != null) mergedTiles.put(
                        WorldMapTracker.chunkKey(tile.chunkX(), tile.chunkZ()), tile);
            }
        }
        if (state.tiles != null) {
            for (WorldMapTracker.MapTile tile : state.tiles) {
                if (tile != null) mergedTiles.put(
                        WorldMapTracker.chunkKey(tile.chunkX(), tile.chunkZ()), tile);
            }
        }

        List<WorldMapTracker.MapTile> tiles = new ArrayList<>(mergedTiles.values());
        tiles.sort((a, b) -> {
            int byZ = Integer.compare(a.chunkZ(), b.chunkZ());
            return byZ != 0 ? byZ : Integer.compare(a.chunkX(), b.chunkX());
        });
        Set<Long> keys = new HashSet<>(mergedTiles.keySet());

        this.civilisations = Collections.unmodifiableMap(nextCivilisations);
        this.providences = Collections.unmodifiableMap(nextProvidences);
        this.focusTrees = Collections.unmodifiableMap(nextFocusTrees);
        this.events = Collections.unmodifiableMap(nextEvents);
        this.technologies = Collections.unmodifiableMap(nextTechnologies);
        this.factoryTypes = Collections.unmodifiableMap(nextFactoryTypes);
        this.factoryRecipes = Collections.unmodifiableMap(nextFactoryRecipes);
        this.religions = Collections.unmodifiableMap(nextReligions);
        this.ideologies = Collections.unmodifiableMap(nextIdeologies);
        this.leaders = Collections.unmodifiableMap(nextLeaders);
        this.wars = state.wars == null ? List.of() : List.copyOf(state.wars);
        this.villagers = state.villagers == null ? List.of() : List.copyOf(state.villagers);
        this.workZones = state.workZones == null ? List.of() : List.copyOf(state.workZones);
        this.mapSnapshot = new WorldMapTracker.Snapshot(
                Collections.unmodifiableList(tiles),
                Collections.unmodifiableSet(keys),
                state.projectionOriginSet,
                state.projectionOriginBlockX,
                state.projectionOriginBlockZ,
                state.minChunkX,
                state.maxChunkX,
                state.minChunkZ,
                state.maxChunkZ);
        this.currentYear = state.currentYear;
        this.currentDay = state.currentDay;
        this.gsDaysPerMinecraftDay = state.gsDaysPerMinecraftDay;
        this.synchronised = true;
        this.lastSyncMillis = System.currentTimeMillis();
    }

    public synchronized void clear() {
        civilisations = Map.of();
        providences = Map.of();
        focusTrees = Map.of();
        events = Map.of();
        technologies = Map.of();
        factoryTypes = Map.of();
        factoryRecipes = Map.of();
        religions = Map.of();
        ideologies = Map.of();
        leaders = Map.of();
        wars = List.of();
        villagers = List.of();
        workZones = List.of();
        mapSnapshot = WorldMapTracker.Snapshot.empty();
        currentYear = -3_900;
        currentDay = 0;
        gsDaysPerMinecraftDay = 36_500.0;
        synchronised = false;
        lastSyncMillis = 0L;
    }

    public Map<String, Civilisation> getCivilisations() {
        return civilisations;
    }

    public Map<String, Providence> getProvidences() {
        return providences;
    }

    public Civilisation getCivilisation(String id) {
        return id == null ? null : civilisations.get(id);
    }

    public Map<String, FocusTree> getFocusTrees() { return focusTrees; }
    public Map<String, GrandStrategyEvent> getEvents() { return events; }
    public FocusTree getFocusTree(String civilisationId) {
        FocusTree specific = civilisationId == null ? null : focusTrees.get(civilisationId);
        return specific != null ? specific : focusTrees.get("generic");
    }
    public GrandStrategyEvent getEvent(String eventId) {
        return eventId == null ? null : events.get(eventId);
    }

    public Map<String, Technology> getTechnologies() { return technologies; }
    public Map<String, FactoryType> getFactoryTypes() { return factoryTypes; }
    public Map<String, FactoryRecipe> getFactoryRecipes() { return factoryRecipes; }
    public Map<String, Religion> getReligions() { return religions; }
    public Map<String, Ideology> getIdeologies() { return ideologies; }
    public Map<String, Leader> getLeaders() { return leaders; }
    public Leader getLeader(String id) { return id == null ? null : leaders.get(id); }

    public List<WarSystem.WarState> getWars() { return wars; }
    public List<PhysicalVillagerSystem.VillagerMapMarker> getVillagers() { return villagers; }
    public List<PhysicalVillagerSystem.WorkZoneMapMarker> getWorkZones() { return workZones; }

    public WorldMapTracker.Snapshot getMapSnapshot() {
        return mapSnapshot;
    }

    public long getCurrentYear() {
        return currentYear;
    }

    public int getCurrentDay() {
        return currentDay;
    }

    public double getGsDaysPerMinecraftDay() {
        return gsDaysPerMinecraftDay;
    }

    public boolean isSynchronised() {
        return synchronised;
    }

    public long getLastSyncMillis() {
        return lastSyncMillis;
    }

    public String getFormattedYear() {
        return currentYear < 0 ? Math.abs(currentYear) + " BCE" : currentYear + " CE";
    }
}




