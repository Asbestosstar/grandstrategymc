package com.asbestosstar.grandstrategy.common.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Loads data-driven Grand Strategy definitions.
 *
 * Data is loaded when a world session starts, not during game start-up. Global
 * definitions live in <game>/grandstrategy and a save can override them from
 * <world>/grandstrategy.
 */
public final class DataManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<String, Civilisation> CIVILISATIONS = new ConcurrentHashMap<>();
    private static final Map<String, Leader> LEADERS = new ConcurrentHashMap<>();
    private static final Map<String, Providence> PROVIDENCES = new ConcurrentHashMap<>();
    private static final Map<String, FocusTree> FOCUS_TREES = new ConcurrentHashMap<>();
    private static final Map<String, GrandStrategyEvent> EVENTS = new ConcurrentHashMap<>();
    private static final Map<String, Religion> RELIGIONS = new ConcurrentHashMap<>();
    private static final Map<String, Ideology> IDEOLOGIES = new ConcurrentHashMap<>();
    private static final Map<String, Technology> TECHNOLOGIES = new ConcurrentHashMap<>();
    private static final Map<String, FactoryType> FACTORY_TYPES = new ConcurrentHashMap<>();
    private static final Map<String, FactoryRecipe> FACTORY_RECIPES = new ConcurrentHashMap<>();
    private static final Map<String, TroopType> TROOP_TYPES = new ConcurrentHashMap<>();

    private DataManager() {
    }

    public static void clearAll() {
        CIVILISATIONS.clear();
        LEADERS.clear();
        PROVIDENCES.clear();
        FOCUS_TREES.clear();
        EVENTS.clear();
        RELIGIONS.clear();
        IDEOLOGIES.clear();
        TECHNOLOGIES.clear();
        FACTORY_TYPES.clear();
        FACTORY_RECIPES.clear();
        TROOP_TYPES.clear();
        MinecraftItemRegistry.clearCache();
    }

    public static void loadData(java.io.File rootDirectory) {
        if (rootDirectory == null) {
            return;
        }
        loadData(rootDirectory.toPath());
    }

    public static void loadData(Path rootDirectory) {
        Path gsDirectory = rootDirectory.toAbsolutePath().normalize().resolve("grandstrategy");

        loadDirectory(gsDirectory.resolve("leaders"), Leader.class, Leader::getId, LEADERS, "leader");
        loadDirectory(gsDirectory.resolve("civilisations"), Civilisation.class, Civilisation::getId,
                CIVILISATIONS, "civilisation");
        loadDirectory(gsDirectory.resolve("providences"), Providence.class, Providence::getId,
                PROVIDENCES, "providence");
        loadDirectory(gsDirectory.resolve("focustrees"), FocusTree.class, FocusTree::getCivilisationId,
                FOCUS_TREES, "focus tree");
        loadDirectory(gsDirectory.resolve("events"), GrandStrategyEvent.class, GrandStrategyEvent::getId,
                EVENTS, "event");
        loadDirectory(gsDirectory.resolve("religions"), Religion.class, Religion::getId,
                RELIGIONS, "religion");
        loadDirectory(gsDirectory.resolve("ideologies"), Ideology.class, Ideology::getId,
                IDEOLOGIES, "ideology");
        loadDirectory(gsDirectory.resolve("technologies"), Technology.class, Technology::getId,
                TECHNOLOGIES, "technology");
        loadDirectory(gsDirectory.resolve("factorytypes"), FactoryType.class, FactoryType::getId,
                FACTORY_TYPES, "factory type");
        loadDirectory(gsDirectory.resolve("factoryrecipes"), FactoryRecipe.class, FactoryRecipe::getId,
                FACTORY_RECIPES, "factory recipe");
        loadDirectory(gsDirectory.resolve("troops"), TroopType.class, TroopType::getId,
                TROOP_TYPES, "troop type");
    }

    private static <T> void loadDirectory(
            Path directory,
            Class<T> type,
            Function<T, String> idFunction,
            Map<String, T> destination,
            String description) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            System.err.println("Could not create Grand Strategy data directory " + directory);
            e.printStackTrace();
            return;
        }

        try (var stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .sorted()
                    .forEach(path -> loadFile(path, type, idFunction, destination, description));
        } catch (IOException e) {
            System.err.println("Could not list Grand Strategy data directory " + directory);
            e.printStackTrace();
        }
    }

    private static <T> void loadFile(
            Path path,
            Class<T> type,
            Function<T, String> idFunction,
            Map<String, T> destination,
            String description) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            T value = GSON.fromJson(reader, type);
            if (value == null) {
                System.err.println("Ignoring empty " + description + " file " + path);
                return;
            }

            if (value instanceof Civilisation civilisation) {
                civilisation.normaliseAfterLoad();
            }
            if (value instanceof Providence providence) {
                providence.normaliseAfterLoad();
            }
            if (value instanceof Leader leader) leader.normaliseAfterLoad();
            if (value instanceof Religion religion) religion.normaliseAfterLoad();
            if (value instanceof Ideology ideology) ideology.normaliseAfterLoad();
            if (value instanceof Technology technology) technology.normaliseAfterLoad();
            if (value instanceof FactoryType factoryType) factoryType.normaliseAfterLoad();
            if (value instanceof FactoryRecipe recipe) recipe.normaliseAfterLoad();

            String id = idFunction.apply(value);
            if (id == null || id.isBlank()) {
                System.err.println("Ignoring " + description + " without an id in " + path);
                return;
            }

            destination.put(id, value);
        } catch (IOException | JsonParseException e) {
            System.err.println("Failed to load " + description + " from " + path);
            e.printStackTrace();
        } catch (RuntimeException e) {
            System.err.println("Invalid " + description + " in " + path);
            e.printStackTrace();
        }
    }

    public static Map<String, Civilisation> getCivilisations() { return CIVILISATIONS; }

    public static Civilisation findCivilisation(String id) {
        return id == null || id.isBlank() ? null : CIVILISATIONS.get(id);
    }

    public static Map<String, Leader> getLeaders() { return LEADERS; }

    /**
     * Null-safe lookup helpers for optional data-driven IDs. ConcurrentHashMap
     * deliberately rejects null keys, while legacy saves and optional leader/
     * society fields legitimately use null to mean "not configured". Keep that
     * boundary here so callers cannot accidentally crash the server with get(null).
     */
    public static Leader findLeader(String id) {
        return id == null || id.isBlank() ? null : LEADERS.get(id);
    }

    public static Religion findReligion(String id) {
        return id == null || id.isBlank() ? null : RELIGIONS.get(id);
    }

    public static Ideology findIdeology(String id) {
        return id == null || id.isBlank() ? null : IDEOLOGIES.get(id);
    }

    public static Map<String, Providence> getProvidences() { return PROVIDENCES; }
    public static Map<String, FocusTree> getFocusTrees() { return FOCUS_TREES; }
    public static Map<String, GrandStrategyEvent> getEvents() { return EVENTS; }
    public static Map<String, Religion> getReligions() { return RELIGIONS; }
    public static Map<String, Ideology> getIdeologies() { return IDEOLOGIES; }
    public static Map<String, Technology> getTechnologies() { return TECHNOLOGIES; }
    public static Map<String, FactoryType> getFactoryTypes() { return FACTORY_TYPES; }
    public static Map<String, FactoryRecipe> getFactoryRecipes() { return FACTORY_RECIPES; }
    public static Map<String, TroopType> getTroopTypes() { return TROOP_TYPES; }
}




