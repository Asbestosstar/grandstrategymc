package com.asbestosstar.grandstrategy.common.world;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.DataManager;
import com.asbestosstar.grandstrategy.common.data.Leader;
import com.asbestosstar.grandstrategy.common.data.Providence;
import com.asbestosstar.grandstrategy.common.engine.HistoricalTimeline;
import com.asbestosstar.grandstrategy.common.engine.SocietySystem;
import com.asbestosstar.grandstrategy.common.engine.WarSystem;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persists mutable strategy state inside the active Minecraft save. */
public final class WorldStateStore {
    private static final int STATE_VERSION = 9;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String STATE_FILE = "world_state.json";

    private WorldStateStore() {
    }

    public static synchronized long load(Path worldRoot, HistoricalTimeline timeline) {
        Path file = stateDirectory(worldRoot).resolve(STATE_FILE);
        if (!Files.isRegularFile(file)) {
            return 0L;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            State state = GSON.fromJson(reader, State.class);
            if (state == null) return 0L;
            if (state.version > STATE_VERSION) {
                System.err.println("Grand Strategy world state is from a newer version: " + state.version);
                return 0L;
            }

            timeline.restore(state.currentYear, state.currentDay, state.partialDay,
                    state.minecraftDayTick, state.gsDaysPerMinecraftDay);

            if (state.civilisations != null && !state.civilisations.isEmpty()) {
                state.civilisations.forEach((id, civilisation) -> {
                    if (civilisation != null && id != null && !id.isBlank()) {
                        civilisation.normaliseAfterLoad();
                        DataManager.getCivilisations().put(id, civilisation);
                    }
                });
            } else if (state.civilisationStability != null) {
                // Version 1 migration.
                state.civilisationStability.forEach((id, stability) -> {
                    Civilisation civilisation = DataManager.getCivilisations().get(id);
                    if (civilisation != null && stability != null) {
                        civilisation.setStability(stability);
                    }
                });
            }

            if (state.fullProvidences != null && !state.fullProvidences.isEmpty()) {
                state.fullProvidences.forEach((id, providence) -> {
                    if (id != null && !id.isBlank() && providence != null) {
                        providence.normaliseAfterLoad();
                        DataManager.getProvidences().put(id, providence);
                    }
                });
            } else if (state.providences != null) {
                // Version 1 migration.
                state.providences.forEach((id, saved) -> {
                    Providence providence = DataManager.getProvidences().get(id);
                    if (providence != null && saved != null) {
                        providence.setOwnerId(saved.ownerId);
                        providence.setResistanceLevel(saved.resistanceLevel);
                    }
                });
            }

            if (state.version < 5) {
                migrateVersionFiveCountryDefaults();
            }
            if (state.version < 9) {
                migrateVersionNineStrategicSystems();
                // Do not retroactively fire every ancient religion-emergence event
                // when a pre-v9 world is first opened. Future religions still fire
                // normally when their historical year is crossed.
                SocietySystem.markReligionsThroughYearAsAlreadyEmerged(timeline.getCurrentYear());
            } else {
                SocietySystem.restoreEmergedReligionIds(state.emergedReligionIds);
            }

            if (state.leaders != null && !state.leaders.isEmpty()) {
                state.leaders.forEach((id, leader) -> {
                    if (leader == null || id == null || id.isBlank()) return;
                    leader.normaliseAfterLoad();
                    DataManager.getLeaders().put(id, leader);
                });
            }

            WarSystem.getInstance().restore(state.wars);
            System.out.println("Grand Strategy world state loaded from " + file);
            return Math.max(0L, state.simulationTicks);
        } catch (IOException | JsonParseException e) {
            System.err.println("Could not load Grand Strategy world state from " + file);
            e.printStackTrace();
            return 0L;
        }
    }

    public static synchronized void save(Path worldRoot, HistoricalTimeline timeline, long simulationTicks) {
        if (worldRoot == null || timeline == null) return;

        Path directory = stateDirectory(worldRoot);
        Path file = directory.resolve(STATE_FILE);
        Path temporary = directory.resolve(STATE_FILE + ".tmp");

        try {
            Files.createDirectories(directory);

            State state = capture(timeline, simulationTicks);
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(state, writer);
            }

            try {
                Files.move(temporary, file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("Could not save Grand Strategy world state to " + file);
            e.printStackTrace();
        }
    }

    /**
     * Version 5 fixes two legacy defaults: countries must have distinct identity
     * colours, and a civilisation starts with one providence/city rather than four.
     * Untouched v4 auto-generated extra cities are merged back into the heartland,
     * while captured or contested providences are preserved as historical territory.
     */
    private static void migrateVersionFiveCountryDefaults() {
        Map<Integer, Integer> colourCounts = new HashMap<>();
        for (Civilisation civilisation : DataManager.getCivilisations().values()) {
            int rgb = civilisation.getBorderColourArgb() & 0x00FFFFFF;
            colourCounts.merge(rgb, 1, Integer::sum);
        }

        Set<Integer> usedColours = new HashSet<>();
        List<Civilisation> ordered = DataManager.getCivilisations().values().stream()
                .sorted((a, b) -> String.valueOf(a.getId()).compareTo(String.valueOf(b.getId())))
                .toList();

        for (Civilisation civilisation : ordered) {
            // CliosOffice is an ancient civilisation.  Its leader may eventually
            // snap to Catholicism and technocracy, but the state itself begins as
            // a secular scholarly polity alongside the other ancient starts.
            if ("cliosoffice".equalsIgnoreCase(civilisation.getId())) {
                civilisation.redefineHistoricalStartYear(-3850L);
                civilisation.redefineDefaultGovernment("scholarly_council");
                civilisation.setStateReligionId("secular");
                civilisation.setSnapIdeologyId("technocracy");
                civilisation.redefineDefaultCityNames(List.of(
                        "Clio's Office", "Archive", "Systems Annex",
                        "North Laboratory", "Observatory"));
            }

            int current = civilisation.getBorderColourArgb() & 0x00FFFFFF;
            boolean duplicateLegacyColour = colourCounts.getOrDefault(current, 0) > 1;
            if (Civilisation.hasBuiltInDefaultColour(civilisation.getId()) || duplicateLegacyColour) {
                civilisation.restoreDefaultCountryColour();
            }

            int candidate = civilisation.getBorderColourArgb();
            int attempt = 0;
            while (!usedColours.add(candidate & 0x00FFFFFF) && attempt++ < 16) {
                // Custom countries can still collide by hash. Nudge the ID-derived
                // default deterministically until this save has a unique colour.
                int seed = Civilisation.defaultBorderColourForId(
                        civilisation.getId() + "_colour_" + attempt);
                civilisation.setBorderColourArgb(seed);
                candidate = civilisation.getBorderColourArgb();
            }

            List<String> starting = civilisation.getStartingProvidences();
            if (starting.size() > 1) {
                String primaryId = starting.get(0);
                Providence primary = DataManager.getProvidences().get(primaryId);
                List<Long> mergedTerritory = primary == null
                        ? null : new java.util.ArrayList<>(primary.getTerritoryChunkKeys());
                boolean mergedAny = false;

                for (int i = 1; i < starting.size(); i++) {
                    String id = starting.get(i);
                    Providence extra = DataManager.getProvidences().get(id);
                    if (extra == null) continue;

                    boolean stillOriginal = java.util.Objects.equals(civilisation.getId(), extra.getOwnerId())
                            && (extra.getCountrysideControllerId() == null
                                || java.util.Objects.equals(civilisation.getId(), extra.getCountrysideControllerId()))
                            && (extra.getCity() == null || extra.getCity().getControllerId() == null
                                || java.util.Objects.equals(civilisation.getId(), extra.getCity().getControllerId()))
                            && extra.getResistanceLevel() < 0.05;

                    // Old v4 worlds were automatically handed four cities at birth.
                    // Collapse untouched legacy extras back into the real starting
                    // heartland so a test world immediately reflects the new one-city
                    // rule. Captured/contested extras are preserved as genuine history.
                    if (!extra.isEstablished() || (stillOriginal && primary != null)) {
                        if (extra.isEstablished() && mergedTerritory != null) {
                            mergedTerritory.addAll(extra.getTerritoryChunkKeys());
                            mergedAny = true;
                        }
                        DataManager.getProvidences().remove(id, extra);
                    }
                }

                if (mergedAny && primary != null && primary.isEstablished()) {
                    primary.establish(primary.getCentreBlockX(), primary.getCentreBlockZ(),
                            mergedTerritory, primary.getCity(), primary.getLandmassId());
                }
                civilisation.replaceStartingProvidences(List.of(primaryId));
            }
        }
    }

    /** v9 introduces the industry/research/social model and moves CliosOffice
     * into the ancient start era.  This migration must run for v5-v8 saves too,
     * not only the older colour/providence migration. */
    private static void migrateVersionNineStrategicSystems() {
        Civilisation clio = DataManager.getCivilisations().get("cliosoffice");
        if (clio != null) {
            clio.redefineHistoricalStartYear(-3850L);
            clio.redefineDefaultGovernment("scholarly_council");
            clio.setStateReligionId("secular");
            clio.setSnapIdeologyId("technocracy");
            clio.redefineDefaultCityNames(List.of(
                    "Clio's Office", "Archive", "Systems Annex",
                    "North Laboratory", "Observatory"));
            clio.setStartingPopulationModifier(1.0);
        }

        // Older saves have no explicit population-religion/ideology distributions
        // or research queues. normaliseAfterLoad() supplies safe defaults while
        // preserving the country's existing population, territory and resources.
        for (Civilisation civilisation : DataManager.getCivilisations().values()) {
            if (civilisation != null) civilisation.normaliseAfterLoad();
        }
    }

    private static State capture(HistoricalTimeline timeline, long simulationTicks) {
        State state = new State();
        state.version = STATE_VERSION;
        state.currentYear = timeline.getCurrentYear();
        state.currentDay = timeline.getCurrentDay();
        state.partialDay = timeline.getPartialDay();
        state.minecraftDayTick = timeline.getMinecraftDayTick();
        state.gsDaysPerMinecraftDay = timeline.getGsDaysPerMinecraftDay();
        state.simulationTicks = Math.max(0L, simulationTicks);

        state.civilisations = new HashMap<>();
        DataManager.getCivilisations().forEach((id, civilisation) ->
                state.civilisations.put(id, civilisation));

        state.leaders = new HashMap<>();
        DataManager.getLeaders().forEach((id, leader) -> state.leaders.put(id, leader));

        state.fullProvidences = new HashMap<>();
        DataManager.getProvidences().forEach((id, providence) ->
                state.fullProvidences.put(id, providence));

        state.wars = WarSystem.getInstance().snapshot();
        state.emergedReligionIds = new HashSet<>(SocietySystem.snapshotEmergedReligionIds());
        return state;
    }

    private static Path stateDirectory(Path worldRoot) {
        return worldRoot.toAbsolutePath().normalize().resolve("grandstrategy").resolve("state");
    }

    private static final class State {
        int version = STATE_VERSION;
        long currentYear = HistoricalTimeline.START_YEAR;
        int currentDay;
        double partialDay;
        int minecraftDayTick;
        double gsDaysPerMinecraftDay = Double.NaN;
        long simulationTicks;

        Map<String, Civilisation> civilisations = new HashMap<>();
        Map<String, Leader> leaders = new HashMap<>();
        Map<String, Providence> fullProvidences = new HashMap<>();
        List<WarSystem.WarState> wars = List.of();
        Set<String> emergedReligionIds = new HashSet<>();

        // Kept only so version-1 saves can still be loaded.
        Map<String, Double> civilisationStability;
        Map<String, ProvidenceState> providences;
    }

    private static final class ProvidenceState {
        String ownerId;
        double resistanceLevel;
    }
}



