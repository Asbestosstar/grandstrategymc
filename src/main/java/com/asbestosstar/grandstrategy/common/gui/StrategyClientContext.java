package com.asbestosstar.grandstrategy.common.gui;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.DataManager;
import com.asbestosstar.grandstrategy.common.data.FocusTree;
import com.asbestosstar.grandstrategy.common.data.FactoryRecipe;
import com.asbestosstar.grandstrategy.common.data.FactoryType;
import com.asbestosstar.grandstrategy.common.data.Ideology;
import com.asbestosstar.grandstrategy.common.data.Leader;
import com.asbestosstar.grandstrategy.common.data.Religion;
import com.asbestosstar.grandstrategy.common.data.Technology;
import com.asbestosstar.grandstrategy.common.data.GrandStrategyEvent;
import com.asbestosstar.grandstrategy.common.data.Providence;
import com.asbestosstar.grandstrategy.common.data.VillagerJob;
import com.asbestosstar.grandstrategy.common.engine.PlayerCountryService;
import com.asbestosstar.grandstrategy.common.engine.StrategyEngine;
import com.asbestosstar.grandstrategy.common.engine.WarSystem;
import com.asbestosstar.grandstrategy.common.network.NetworkManager;
import com.asbestosstar.grandstrategy.common.network.StrategyClientState;
import com.asbestosstar.grandstrategy.common.world.PhysicalVillagerSystem;
import com.asbestosstar.grandstrategy.common.world.WorldMapTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.List;

/** Client GUI access to the server-authoritative strategy state. */
public final class StrategyClientContext {
    private StrategyClientContext() {
    }

    public static String currentIgn() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.getName() != null) {
            String value = minecraft.player.getName().getString();
            if (value != null && !value.isBlank()) return value;
        }
        return "Player";
    }

    public static String currentUuid() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? null : minecraft.player.getUUID().toString();
    }

    public static Civilisation currentPlayerCountry() {
        String uuid = currentUuid();
        String ign = currentIgn();
        for (Civilisation civilisation : civilisations()) {
            if (civilisation == null || !civilisation.isPlayerCreated()
                    || !civilisation.isActive() || civilisation.isCollapsed()
                    || civilisation.getPopulation() <= 0) continue;
            if (uuid != null && uuid.equalsIgnoreCase(civilisation.getFounderUuid())) return civilisation;
            if ((civilisation.getFounderUuid() == null || civilisation.getFounderUuid().isBlank())
                    && civilisation.getFounderIgn() != null
                    && civilisation.getFounderIgn().equalsIgnoreCase(ign)) {
                return civilisation;
            }
        }

        // Integrated-server fallback while the first network snapshot is in flight.
        Minecraft minecraft = Minecraft.getInstance();
        if (!StrategyClientState.getInstance().isSynchronised()
                && minecraft.getSingleplayerServer() != null) {
            return PlayerCountryService.findForPlayer(uuid, ign);
        }
        return null;
    }

    public static long currentYear() {
        StrategyClientState state = StrategyClientState.getInstance();
        if (state.isSynchronised()) return state.getCurrentYear();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() != null) {
            return StrategyEngine.getInstance().getTimeline().getCurrentYear();
        }
        return -3_900L;
    }

    public static Collection<Civilisation> civilisations() {
        StrategyClientState state = StrategyClientState.getInstance();
        if (state.isSynchronised()) return state.getCivilisations().values();

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() != null) {
            return DataManager.getCivilisations().values().stream()
                    .filter(Civilisation::isActive)
                    .toList();
        }
        return List.of();
    }

    public static Collection<Providence> providences() {
        StrategyClientState state = StrategyClientState.getInstance();
        if (state.isSynchronised()) return state.getProvidences().values();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() != null) {
            return DataManager.getProvidences().values().stream()
                    .filter(Providence::isEstablished)
                    .toList();
        }
        return List.of();
    }

    public static Civilisation getCivilisation(String id) {
        // ConcurrentHashMap rejects null keys. Unowned providences/cities legitimately
        // use null after a country is destroyed, so GUI lookups must be null-safe.
        if (id == null || id.isBlank()) return null;
        StrategyClientState state = StrategyClientState.getInstance();
        if (state.isSynchronised()) return state.getCivilisation(id);
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getSingleplayerServer() == null ? null : DataManager.findCivilisation(id);
    }

    public static FocusTree focusTreeFor(Civilisation civilisation) {
        if (civilisation == null) return null;
        StrategyClientState state = StrategyClientState.getInstance();
        if (state.isSynchronised()) return state.getFocusTree(civilisation.getId());
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() == null) return null;
        FocusTree specific = DataManager.getFocusTrees().get(civilisation.getId());
        return specific != null ? specific : DataManager.getFocusTrees().get("generic");
    }

    public static GrandStrategyEvent event(String eventId) {
        StrategyClientState state = StrategyClientState.getInstance();
        if (state.isSynchronised()) return state.getEvent(eventId);
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getSingleplayerServer() == null ? null : DataManager.getEvents().get(eventId);
    }


    public static Collection<Technology> technologies() {
        StrategyClientState state = StrategyClientState.getInstance();
        if (state.isSynchronised()) return state.getTechnologies().values();
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getSingleplayerServer() == null ? List.of() : DataManager.getTechnologies().values();
    }

    public static Collection<FactoryType> factoryTypes() {
        StrategyClientState state = StrategyClientState.getInstance();
        if (state.isSynchronised()) return state.getFactoryTypes().values();
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getSingleplayerServer() == null ? List.of() : DataManager.getFactoryTypes().values();
    }

    public static Collection<FactoryRecipe> factoryRecipes() {
        StrategyClientState state = StrategyClientState.getInstance();
        if (state.isSynchronised()) return state.getFactoryRecipes().values();
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getSingleplayerServer() == null ? List.of() : DataManager.getFactoryRecipes().values();
    }

    public static Collection<Religion> religions() {
        StrategyClientState state = StrategyClientState.getInstance();
        if (state.isSynchronised()) return state.getReligions().values();
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getSingleplayerServer() == null ? List.of() : DataManager.getReligions().values();
    }

    public static Collection<Ideology> ideologies() {
        StrategyClientState state = StrategyClientState.getInstance();
        if (state.isSynchronised()) return state.getIdeologies().values();
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getSingleplayerServer() == null ? List.of() : DataManager.getIdeologies().values();
    }

    public static Leader leader(String id) {
        if (id == null || id.isBlank()) return null;
        StrategyClientState state = StrategyClientState.getInstance();
        if (state.isSynchronised()) return state.getLeader(id);
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getSingleplayerServer() == null ? null : DataManager.findLeader(id);
    }

    public static List<WarSystem.WarState> wars() {
        StrategyClientState state = StrategyClientState.getInstance();
        if (state.isSynchronised()) return state.getWars();
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getSingleplayerServer() == null ? List.of() : WarSystem.getInstance().snapshot();
    }

    public static List<PhysicalVillagerSystem.VillagerMapMarker> villagers() {
        StrategyClientState state = StrategyClientState.getInstance();
        if (state.isSynchronised()) return state.getVillagers();
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getSingleplayerServer() == null
                ? List.of()
                : PhysicalVillagerSystem.getInstance().snapshotMapMarkers();
    }

    public static List<PhysicalVillagerSystem.WorkZoneMapMarker> workZones() {
        StrategyClientState state = StrategyClientState.getInstance();
        if (state.isSynchronised()) return state.getWorkZones();
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getSingleplayerServer() == null
                ? List.of()
                : PhysicalVillagerSystem.getInstance().snapshotWorkZones();
    }

    public static WarSystem.WarState warBetween(String firstId, String secondId) {
        for (WarSystem.WarState war : wars()) {
            if (war == null) continue;
            if ((firstId != null && firstId.equals(war.attackerId) && secondId != null && secondId.equals(war.defenderId))
                    || (firstId != null && firstId.equals(war.defenderId) && secondId != null && secondId.equals(war.attackerId))) {
                return war;
            }
        }
        return null;
    }

    public static WorldMapTracker.Snapshot mapSnapshot() {
        StrategyClientState state = StrategyClientState.getInstance();
        if (state.isSynchronised()) return state.getMapSnapshot();
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getSingleplayerServer() == null
                ? WorldMapTracker.Snapshot.empty()
                : WorldMapTracker.getInstance().snapshot();
    }

    public static String formattedYear() {
        StrategyClientState state = StrategyClientState.getInstance();
        if (state.isSynchronised()) return state.getFormattedYear();
        return StrategyEngine.getInstance().getTimeline().getFormattedYear();
    }

    public static double gsDaysPerMinecraftDay() {
        StrategyClientState state = StrategyClientState.getInstance();
        if (state.isSynchronised()) return state.getGsDaysPerMinecraftDay();
        return StrategyEngine.getInstance().getTimeline().getGsDaysPerMinecraftDay();
    }

    public static boolean requestSync() { return NetworkManager.getInstance().requestSync(); }
    public static boolean requestCreateCountry() { return NetworkManager.getInstance().requestCreateCountry(); }
    public static boolean requestImproveRelations(String target) { return NetworkManager.getInstance().requestImproveRelations(target); }
    public static boolean requestDeclareWar(String target) { return NetworkManager.getInstance().requestDeclareWar(target); }
    public static boolean requestPeaceProposal(String target, String terms) { return NetworkManager.getInstance().requestPeaceProposal(target, terms); }
    public static boolean requestPeaceAccept(String target) { return NetworkManager.getInstance().requestPeaceAccept(target); }
    public static boolean requestPeaceReject(String target) { return NetworkManager.getInstance().requestPeaceReject(target); }
    public static boolean requestSoldierAutomatic() { return NetworkManager.getInstance().requestSoldierAutomatic(); }
    public static boolean requestSoldierMove(int blockX, int blockZ) { return NetworkManager.getInstance().requestSoldierMove(blockX, blockZ); }
    public static boolean requestWorkZone(String type, int blockX, int blockZ) { return NetworkManager.getInstance().requestWorkZone(type, blockX, blockZ); }
    public static boolean requestStartTechnology(String technologyId) { return NetworkManager.getInstance().requestStartTechnology(technologyId); }
    public static boolean requestQueueProduction(String recipeId, int amount) { return NetworkManager.getInstance().requestQueueProduction(recipeId, amount); }
    public static boolean requestCancelProduction(long serial) { return NetworkManager.getInstance().requestCancelProduction(serial); }
    public static boolean requestFactoryConversion(String zoneId, String factoryTypeId) { return NetworkManager.getInstance().requestFactoryConversion(zoneId, factoryTypeId); }
    public static boolean requestCycleConscription() { return NetworkManager.getInstance().requestCycleConscription(); }
    public static boolean requestCycleGovernment() { return NetworkManager.getInstance().requestCycleGovernment(); }
    public static boolean requestAutoAssign() { return NetworkManager.getInstance().requestAutoAssign(); }
    public static boolean requestReassignTo(VillagerJob job) { return NetworkManager.getInstance().requestReassignTo(job); }
    public static boolean requestReassignFrom(VillagerJob job) { return NetworkManager.getInstance().requestReassignFrom(job); }
    public static boolean requestToggleSpirit(String spiritId) { return NetworkManager.getInstance().requestToggleSpirit(spiritId); }
    public static boolean requestStartFocus(String focusId) { return NetworkManager.getInstance().requestStartFocus(focusId); }
    public static boolean requestResolveEvent(String eventId, String optionId) { return NetworkManager.getInstance().requestResolveEvent(eventId, optionId); }

    public static boolean isPlayerInOverworld() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null
                && minecraft.level != null
                && Level.OVERWORLD.equals(minecraft.level.dimension());
    }

    public static Integer currentPlayerBlockX() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !isPlayerInOverworld()) return null;
        return floorToInt(minecraft.player.getX());
    }

    public static Integer currentPlayerBlockZ() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !isPlayerInOverworld()) return null;
        return floorToInt(minecraft.player.getZ());
    }

    private static int floorToInt(double value) {
        int whole = (int) value;
        return value < whole ? whole - 1 : whole;
    }
}




