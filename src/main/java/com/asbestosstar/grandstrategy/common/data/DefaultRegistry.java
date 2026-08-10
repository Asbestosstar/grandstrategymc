package com.asbestosstar.grandstrategy.common.data;

import java.util.Arrays;
import java.util.Map;
import java.util.List;

/** Registers built-in civilisations, focus trees, events and baseline strategy data. */
public final class DefaultRegistry {
    private DefaultRegistry() {
    }

    public static void registerDefaults() {
        registerLeaders();
        registerReligions();
        registerIdeologies();
        registerTechnologiesAndIndustry();
        registerCivilisationsAndProvidences();
        registerTroops();
        registerFocusTrees();
        registerEvents();
    }

    private static void registerLeaders() {
        DataManager.getLeaders().put("clioaite",
                new Leader("clioaite", "ClioAite", true, Arrays.asList("Immortal", "Strategist"),
                        null, "catholic", "scholarly_council", "technocracy", 30.0, 35.0));
        DataManager.getLeaders().put("enmerkar",
                new Leader("enmerkar", "Enmerkar", false, List.of("Founder")));
        DataManager.getLeaders().put("mesannepada",
                new Leader("mesannepada", "Mesannepada", false, List.of("Conqueror")));
        DataManager.getLeaders().put("narmer",
                new Leader("narmer", "Narmer", false, List.of("Unifier")));
        DataManager.getLeaders().put("eridu_council",
                new Leader("eridu_council", "Eridu Temple Council", false, List.of("Irrigators")));
        DataManager.getLeaders().put("susa_council",
                new Leader("susa_council", "Susa Council", false, List.of("Traders")));
        DataManager.getLeaders().put("indus_council",
                new Leader("indus_council", "Indus City Council", false, List.of("Urban Planners")));
        DataManager.getLeaders().put("minoan_council",
                new Leader("minoan_council", "Minoan Palace Council", false, List.of("Mariners")));
        DataManager.getLeaders().put("caral_council",
                new Leader("caral_council", "Caral Council", false, List.of("Builders")));
    }

    private static void registerCivilisationsAndProvidences() {
        registerCivilisation("uruk", "Uruk", "enmerkar", "monarchy", "sumerian_polytheism",
                -3900, 57, 51, 0xFFE67E22, 1.05,
                List.of("Uruk", "Kulaba", "Larsa", "Bad-tibira", "Nippur"));
        registerCivilisation("eridu", "Eridu", "eridu_council", "temple_city", "sumerian_polytheism",
                -3900, 56, 55, 0xFF2E86C1, 0.90,
                List.of("Eridu", "Kuara", "Urukug", "Adab"));
        registerCivilisation("susa", "Susa", "susa_council", "city_state", "proto_elamite",
                -3900, 63, 51, 0xFF8E44AD, 0.95,
                List.of("Susa", "Anshan", "Awan", "Madaktu"));
        registerCivilisation("ur", "Ur", "mesannepada", "monarchy", "sumerian_polytheism",
                -3800, 59, 54, 0xFF2ECC71, 1.00,
                List.of("Ur", "Kish", "Lagash", "Umma", "Isin"));
        registerCivilisation("egypt", "Egypt", "narmer", "theocracy", "egyptian_polytheism",
                -3100, 51, 57, 0xFFD4AC0D, 1.25,
                List.of("Memphis", "Abydos", "Thebes", "Heliopolis", "Elephantine", "Alexandria"));
        registerCivilisation("indus", "Indus", "indus_council", "city_confederation", "indus_tradition",
                -3000, 72, 54, 0xFF148F77, 1.20,
                List.of("Harappa", "Mohenjo-daro", "Dholavira", "Lothal", "Rakhigarhi"));
        registerCivilisation("minoan", "Minoan Crete", "minoan_council", "palatial", "minoan_religion",
                -3000, 47, 50, 0xFFC0392B, 0.85,
                List.of("Knossos", "Phaistos", "Malia", "Zakros"));
        registerCivilisation("caral", "Caral-Supe", "caral_council", "sacred_city", "andean_tradition",
                -3000, 18, 64, 0xFFB03A8F, 0.75,
                List.of("Caral", "Aspero", "Vichama", "Bandurria"));

        // CliosOffice is an ancient civilisation now, not a 2026 spawn. It remains
        // secular as a state by default. ClioAite personally snaps to Catholicism
        // once that religion appears, while the country itself later snaps from its
        // ancient scholarly council to the technocracy ideology when available.
        registerCivilisation("cliosoffice", "CliosOffice", "clioaite", "scholarly_council", "secular",
                -3850, 84, 78, 0xFF00BCD4, 1.00,
                List.of("Clio's Office", "Archive", "Systems Annex", "North Laboratory", "Observatory"));
        Civilisation clio = DataManager.getCivilisations().get("cliosoffice");
        if (clio != null) clio.setSnapIdeologyId("technocracy");
    }

    private static void registerCivilisation(String id, String name, String leaderId,
                                              String ideology, String religion, long startYear,
                                              int mapXPercent, int mapYPercent, int borderColourArgb,
                                              double populationModifier, List<String> defaultCityNames) {
        List<String> providenceIds = List.of(id + "_heartland");
        Civilisation civilisation = new Civilisation(
                id, name, leaderId, ideology, religion, providenceIds,
                startYear, mapXPercent, mapYPercent);
        civilisation.setBorderColourArgb(borderColourArgb);
        civilisation.setStartingPopulationModifier(populationModifier);
        civilisation.setDefaultCityNames(defaultCityNames);
        DataManager.getCivilisations().put(id, civilisation);
        String firstCity = defaultCityNames == null || defaultCityNames.isEmpty() ? name : defaultCityNames.get(0);
        registerProvidenceTemplate(providenceIds.get(0), firstCity + " Heartland", 1.25);
    }

    private static void registerProvidenceTemplate(String id, String name, double development) {
        DataManager.getProvidences().put(id,
                new Providence(id, name, null, 0.0, development));
    }

    private static void registerReligions() {
        // Policy/nonbelief entries.
        putReligion(new Religion("secular", "Secular State", "secular", null, null,
                Long.MIN_VALUE, List.of(), Map.of(), true, false, false, false,
                0.0, 0.0, 0.0, 0.04));
        putReligion(new Religion("atheism", "Atheism", "nonreligious", null, null,
                Long.MIN_VALUE, List.of(), Map.of(), false, true, true, false,
                0.0, 0.0, 0.0, 0.05));
        putReligion(new Religion("irreligion", "Irreligion", "nonreligious", null, null,
                Long.MIN_VALUE, List.of(), Map.of(), false, true, true, false,
                0.0, 0.0, 0.0, 0.02));
        putReligion(new Religion("local_cult", "Local Cults", "local_cult", null, null,
                Long.MIN_VALUE, List.of(), Map.of(), false, true, true, false,
                0.04, 0.04, 0.05, -0.02));

        putReligion(new Religion("sumerian_polytheism", "Sumerian Polytheism", "mesopotamian", null, null,
                -4500, List.of("Eridu", "Uruk"), Map.of(), false, true, true, false,
                0.10, 0.07, 0.08, -0.08));
        putReligion(new Religion("egyptian_polytheism", "Egyptian Polytheism", "egyptian", null, null,
                -3500, List.of("Abydos", "Memphis"), Map.of(), false, true, true, false,
                0.10, 0.05, 0.06, -0.08));
        putReligion(new Religion("proto_elamite", "Early Susian Tradition", "elamite", null, null,
                -4200, List.of("Susa"), Map.of(), false, true, true, false,
                0.08, 0.04, 0.05, -0.06));
        putReligion(new Religion("indus_tradition", "Indus Tradition", "indus", null, null,
                -3300, List.of("Harappa"), Map.of(), false, true, true, false,
                0.05, 0.10, 0.10, -0.04));
        putReligion(new Religion("minoan_religion", "Minoan Religion", "aegean", null, null,
                -3200, List.of("Knossos"), Map.of(), false, true, true, false,
                0.07, 0.08, 0.08, -0.05));
        putReligion(new Religion("andean_tradition", "Andean Tradition", "andean", null, null,
                -3200, List.of("Caral"), Map.of(), false, true, true, false,
                0.08, 0.11, 0.12, -0.05));

        // Major later families and sects. If the named origin city exists the
        // creation event is anchored there; otherwise the historical date still
        // allows the religion to appear globally so timelines never deadlock.
        putReligion(new Religion("christianity", "Christianity", "christian", null, null,
                100, List.of("Jerusalem", "Antioch"), Map.of(), false, true, true, false,
                0.10, 0.0, 0.0, -0.10));
        putReligion(new Religion("arian", "Arian Christianity", "christian", "christianity", "christianity",
                300, List.of("Alexandria"), Map.of(), false, true, true, false,
                0.11, 0.0, 0.0, -0.10));
        putReligion(new Religion("catholic", "Catholic Christianity", "christian", "christianity", "christianity",
                100, List.of("Rome", "Jerusalem"), Map.of(), false, true, true, false,
                0.12, 0.0, 0.0, -0.10));
        putReligion(new Religion("orthodox", "Orthodox Christianity", "christian", "christianity", "christianity",
                330, List.of("Constantinople"), Map.of(), false, true, true, false,
                0.12, 0.0, 0.0, -0.09));
        putReligion(new Religion("ethiopian_christian", "Ethiopian Christianity", "christian", "christianity", "christianity",
                330, List.of("Aksum"), Map.of(), false, true, true, false,
                0.11, 0.03, 0.03, -0.08));
        putReligion(new Religion("lutheran", "Lutheran Christianity", "christian", "christianity", "catholic",
                1517, List.of("Wittenberg"), Map.of(), false, true, true, false,
                0.10, 0.0, 0.0, -0.06));
        putReligion(new Religion("buddhism", "Buddhism", "buddhist", null, null,
                -500, List.of("Bodh Gaya", "Varanasi"), Map.of(), false, true, true, true,
                -0.18, 0.04, 0.05, 0.02));
        putReligion(new Religion("islam", "Islam", "islamic", null, null,
                610, List.of("Mecca", "Medina"), Map.of(), false, true, true, false,
                0.13, 0.0, 0.0, -0.08));
    }

    private static void putReligion(Religion religion) {
        DataManager.getReligions().put(religion.getId(), religion);
    }

    private static void registerIdeologies() {
        putIdeology(new Ideology("nonaligned", "Non-Aligned", "nonaligned", null, null,
                Long.MIN_VALUE, List.of(), Map.of(), Map.of(), true));
        putIdeology(new Ideology("monarchy", "Monarchy", "monarchist", null, "nonaligned",
                -5000, List.of(), Map.of("stability", 0.04), Map.of(), false));
        putIdeology(new Ideology("theocracy", "Theocracy", "theocratic", null, "monarchy",
                -5000, List.of(), Map.of("stability", 0.03),
                Map.of("secular", -0.55, "nonreligious", -0.65), false));
        putIdeology(new Ideology("city_state", "City State", "oligarchic", null, "nonaligned",
                -5000, List.of(), Map.of(), Map.of(), true));
        putIdeology(new Ideology("temple_city", "Temple City", "theocratic", "theocracy", "city_state",
                -5000, List.of(), Map.of(), Map.of(), false));
        putIdeology(new Ideology("city_confederation", "City Confederation", "republican", null, "city_state",
                -4000, List.of(), Map.of(), Map.of(), false));
        putIdeology(new Ideology("palatial", "Palatial", "monarchist", null, "monarchy",
                -4000, List.of(), Map.of(), Map.of(), false));
        putIdeology(new Ideology("sacred_city", "Sacred City", "theocratic", "theocracy", "city_state",
                -4000, List.of(), Map.of(), Map.of(), false));
        putIdeology(new Ideology("scholarly_council", "Scholarly Council", "nonaligned", null, "nonaligned",
                -5000, List.of(), Map.of("research", 0.08),
                Map.of("secular", 0.35, "nonreligious", 0.25), true));
        putIdeology(new Ideology("republic", "Republic", "democratic", null, "city_confederation",
                -500, List.of(), Map.of("research", 0.04), Map.of(), false));
        putIdeology(new Ideology("council_republic", "Council Republic", "democratic", "republic", "city_confederation",
                -500, List.of(), Map.of("stability", 0.03), Map.of(), false));
        putIdeology(new Ideology("oligarchy", "Oligarchy", "oligarchic", "city_state", "city_state",
                -3000, List.of(), Map.of("factory", 0.03), Map.of(), false));
        putIdeology(new Ideology("liberal_democracy", "Liberal Democracy", "democratic", "republic", "republic",
                1700, List.of("free_speech"), Map.of("research", 0.10),
                Map.of("secular", 0.55, "nonreligious", 0.40), false));
        putIdeology(new Ideology("social_democracy", "Social Democracy", "democratic", "republic", "liberal_democracy",
                1850, List.of("free_speech"), Map.of("stability", 0.05), Map.of(), false));
        putIdeology(new Ideology("technocracy", "Technocracy", "technocratic", null, "scholarly_council",
                1600, List.of("scientific_method"), Map.of("research", 0.18),
                Map.of("secular", 0.60, "nonreligious", 0.50, "local_cult", -0.20), false));
        putIdeology(new Ideology("communism", "Communism", "socialist", null, "republic",
                1848, List.of("industrialisation"), Map.of("factory", 0.08),
                Map.of("secular", 0.45, "nonreligious", 0.65, "christian", -0.35, "islamic", -0.35), false));
        putIdeology(new Ideology("fascism", "Fascism", "ultranationalist", null, "monarchy",
                1919, List.of("industrialisation"), Map.of("military", 0.12), Map.of(), false));
    }

    private static void putIdeology(Ideology ideology) {
        DataManager.getIdeologies().put(ideology.getId(), ideology);
    }

    private static void registerTechnologiesAndIndustry() {
        // Factory types.
        DataManager.getFactoryTypes().put("wooden_factory", new FactoryType(
                "wooden_factory", "Wooden Factory", "Flammable early workshop with crafting only.",
                List.of("CRAFTING"), List.of(), List.of("minecraft:crafting_table"), true));
        DataManager.getFactoryTypes().put("smelting_factory", new FactoryType(
                "smelting_factory", "Smelting Factory", "Stone workshop with crafting and furnace processing.",
                List.of("CRAFTING", "SMELTING"), List.of("smelting"), List.of("minecraft:furnace"), false));
        DataManager.getFactoryTypes().put("steel_factory", new FactoryType(
                "steel_factory", "Steel Factory", "Advanced metal-working factory enabled only when a steel item exists.",
                List.of("CRAFTING", "SMELTING", "STEEL"), List.of("steelworking"), List.of(), false));

        // Technology tree. Research time is real-time baseline for one productive researcher.
        putTechnology(tech("basic_crafting", "Basic Crafting", "Organised workshop crafting.", -10000, -5000, 45,
                List.of(), List.of("minecraft:crafting_table"), List.of(), List.of("wooden_factory"), List.of(),
                Map.of("*", "WOOD"), 0, 0, 0, 0));
        putTechnology(tech("stone_tools", "Stone Tools", "Standardised stone working tools.", -8000, -4000, 70,
                List.of("basic_crafting"), List.of("minecraft:stone_pickaxe"), List.of(), List.of(), List.of(),
                Map.of("*", "STONE"), 0, 0, 0, 0));
        putTechnology(tech("smelting", "Smelting", "Purpose-built furnaces for ore and fuel processing.", -3500, -2500, 150,
                List.of("basic_crafting"), List.of("minecraft:furnace"), List.of(), List.of("smelting_factory"),
                List.of("charcoal"), Map.of(), 0, 0, 0, 0));
        putTechnology(tech("ironworking", "Ironworking", "Reliable production and use of iron tools.", -1200, 500, 220,
                List.of("smelting", "stone_tools"), List.of("minecraft:iron_ingot"), List.of(), List.of(), List.of(),
                Map.of("*", "IRON"), 0, 0, 0, 0));
        putTechnology(tech("advanced_mining", "Advanced Mining", "High-hardness tools and systematic deep extraction.", 1200, 1750, 300,
                List.of("ironworking"), List.of("minecraft:diamond"), List.of(), List.of(), List.of(),
                Map.of("MINER", "DIAMOND", "LUMBERJACK", "DIAMOND", "FARMER", "DIAMOND", "SOLDIER", "DIAMOND"), 0, 0, 0, 0));
        putTechnology(tech("printing_press", "Printing Press", "Cheap replication of written knowledge.", 1450, 1750, 260,
                List.of("ironworking"), List.of("minecraft:paper"), List.of(), List.of(), List.of(), Map.of(), -2, -1, -2, 0));
        putTechnology(tech("scientific_method", "Scientific Method", "Systematic empirical testing and reproducibility.", 1600, 1850, 300,
                List.of("printing_press"), List.of(), List.of(), List.of(), List.of(), Map.of(), -8, -2, -4, 0.01));
        putTechnology(tech("heliocentrism", "Heliocentrism", "Astronomical evidence for a Sun-centred planetary system.", 1543, 1800, 240,
                List.of("printing_press"), List.of(), List.of(), List.of(), List.of(), Map.of(), -10, -2, -2, 0));
        putTechnology(tech("telescopic_astronomy", "Telescopic Astronomy", "Observational astronomy using telescopes.", 1610, 1850, 280,
                List.of("heliocentrism"), List.of(), List.of(), List.of(), List.of(), Map.of(), -9, -2, -2, 0));
        putTechnology(tech("free_speech", "Free Speech", "Institutions protecting criticism and open argument.", 1689, 1900, 320,
                List.of("printing_press"), List.of(), List.of(), List.of(), List.of(), Map.of(), -14, -3, -10, 0.01));
        putTechnology(tech("industrialisation", "Industrialisation", "Mechanised and specialised production systems.", 1750, 1950, 360,
                List.of("smelting", "scientific_method"), List.of(), List.of(), List.of(), List.of(), Map.of(), -3, -1, -2, 0));
        putTechnology(tech("darwinism", "Darwinism", "Evolution by natural selection reshapes biological understanding.", 1859, 2000, 380,
                List.of("scientific_method"), List.of(), List.of(), List.of(), List.of(), Map.of(), -22, -8, -5, 0));
        putTechnology(tech("steelworking", "Steelworking", "Industrial steel production and steel tooling.", 1850, 2000, 420,
                List.of("industrialisation", "ironworking"), List.of(),
                List.of("create:steel_ingot", "mekanism:ingot_steel", "immersiveengineering:ingot_steel", "modern_industrialization:steel_ingot"),
                List.of("steel_factory"), List.of(), Map.of(), -2, 0, 0, 0));

        // Player queueable factory products. The physical worker currently handles
        // these built-ins directly; JSON/mod integrations can add definitions and UI
        // availability without forcing nonexistent-item technologies into the tree.
        recipe("chest", "Chest", "minecraft:chest", 1, Map.of("minecraft:oak_planks", 8),
                List.of("wooden_factory", "smelting_factory", "steel_factory"), List.of("basic_crafting"), "CRAFTING");
        recipe("bread", "Bread", "minecraft:bread", 1, Map.of("minecraft:wheat", 3),
                List.of("wooden_factory", "smelting_factory", "steel_factory"), List.of("basic_crafting"), "CRAFTING");
        recipe("torch", "Torches", "minecraft:torch", 4, Map.of("minecraft:coal", 1, "minecraft:oak_planks", 1),
                List.of("wooden_factory", "smelting_factory", "steel_factory"), List.of("basic_crafting"), "CRAFTING");
        recipe("wooden_pickaxe", "Wooden Pickaxe", "minecraft:wooden_pickaxe", 1, Map.of("minecraft:oak_planks", 4),
                List.of("wooden_factory", "smelting_factory", "steel_factory"), List.of("basic_crafting"), "CRAFTING");
        recipe("stone_pickaxe", "Stone Pickaxe", "minecraft:stone_pickaxe", 1, Map.of("minecraft:cobblestone", 3, "minecraft:oak_planks", 1),
                List.of("wooden_factory", "smelting_factory", "steel_factory"), List.of("stone_tools"), "CRAFTING");
        recipe("iron_pickaxe", "Iron Pickaxe", "minecraft:iron_pickaxe", 1, Map.of("minecraft:iron_ingot", 3, "minecraft:oak_planks", 1),
                List.of("smelting_factory", "steel_factory"), List.of("ironworking"), "CRAFTING");
        recipe("charcoal", "Charcoal", "minecraft:charcoal", 1, Map.of("minecraft:oak_log", 4),
                List.of("smelting_factory", "steel_factory"), List.of("smelting"), "SMELTING");
    }

    private static Technology tech(String id, String name, String description,
                                   long baseYear, long backwaterYear, double seconds,
                                   List<String> prerequisites, List<String> required,
                                   List<String> anyRequired, List<String> factories,
                                   List<String> recipes, Map<String, String> tools,
                                   double religionExtremism, double religiosity,
                                   double ideologyExtremism, double stability) {
        return new Technology(id, name, description, baseYear, backwaterYear, seconds,
                prerequisites, required, anyRequired, factories, recipes, tools,
                religionExtremism, religiosity, ideologyExtremism, stability);
    }

    private static void putTechnology(Technology technology) {
        DataManager.getTechnologies().put(technology.getId(), technology);
    }

    private static void recipe(String id, String name, String output, int count,
                               Map<String, Integer> ingredients, List<String> factoryTypes,
                               List<String> technologies, String capability) {
        DataManager.getFactoryRecipes().put(id, new FactoryRecipe(id, name, output, count,
                ingredients, factoryTypes, technologies, List.of(output), capability));
    }

    private static void registerTroops() {
        DataManager.getTroopTypes().put("creeper",
                new TroopType("creeper", "Creeper", 10.0, 5.0, "minecraft:creeper"));
        DataManager.getTroopTypes().put("skeleton",
                new TroopType("skeleton", "Skeleton", 8.0, 6.0, "minecraft:skeleton"));
        DataManager.getTroopTypes().put("wolf",
                new TroopType("wolf", "Wolf", 7.0, 4.0, "minecraft:wolf"));
    }

    // -------------------------------------------------------------------------
    // Focus trees
    // -------------------------------------------------------------------------

    private static void registerFocusTrees() {
        DataManager.getFocusTrees().put("generic", new FocusTree("generic", List.of(
                focus("foundations", "Lay the Foundations", "Establish a functioning administration for the new state.",
                        30, 5, 2, List.of(), List.of(), List.of(
                                StrategyEffect.pp(20), StrategyEffect.stability(0.04))),
                focus("agrarian_path", "Agrarian Settlement", "Put food security and population growth first.",
                        40, 10, 1, List.of("foundations"), List.of("mining_path"), List.of(
                                StrategyEffect.spirit(NationalSpirit.AGRARIAN_TRADITION),
                                StrategyEffect.resource(ResourceType.FOOD, 300))),
                focus("mining_path", "Mining Frontier", "Organise extraction of the ores beneath the new country.",
                        40, 10, 1, List.of("foundations"), List.of("agrarian_path"), List.of(
                                StrategyEffect.spirit(NationalSpirit.MINING_CULTURE),
                                StrategyEffect.resource(ResourceType.IRON, 120),
                                StrategyEffect.resource(ResourceType.COAL, 100))),
                focus("civic_assembly", "Convene a Civic Assembly", "Disperse political authority through an elected council.",
                        50, 20, 1, List.of("foundations"), List.of("crown_authority"), List.of(
                                StrategyEffect.government("Council Republic"), StrategyEffect.stability(0.06),
                                StrategyEffect.pp(25))),
                focus("crown_authority", "Concentrate Authority", "Create a strong hereditary central government.",
                        50, 20, 1, List.of("foundations"), List.of("civic_assembly"), List.of(
                                StrategyEffect.government("Monarchy"), StrategyEffect.stability(0.08),
                                StrategyEffect.pp(15))),
                focus("roads_markets", "Roads and Markets", "Connect cities, farms and mines into one internal market.",
                        55, 15, 1.3, List.of("agrarian_path"), List.of(), List.of(
                                StrategyEffect.roads(3), StrategyEffect.spirit(NationalSpirit.ROAD_BUILDERS),
                                StrategyEffect.relationNearest(10))),
                focus("standing_army", "A Standing Army", "Turn a portion of the population into a permanent military force.",
                        55, 20, 1, List.of("mining_path"), List.of(), List.of(
                                StrategyEffect.conscription(ConscriptionLevel.EXTENSIVE_CONSCRIPTION),
                                StrategyEffect.resource(ResourceType.SUPPLIES, 200), StrategyEffect.stability(-0.02))),
                focus("centres_learning", "Centres of Learning", "Create institutions devoted to accumulated knowledge.",
                        60, 25, 1, List.of("roads_markets"), List.of(), List.of(
                                StrategyEffect.spirit(NationalSpirit.SCIENTIFIC_CULTURE), StrategyEffect.research(120)))
        )));

        registerSumerianTree("uruk", "Uruk", "Monumental Uruk", "Royal Administration");
        registerSumerianTree("ur", "Ur", "Harbour of Ur", "Dynastic Kingship");
        registerSumerianTree("eridu", "Eridu", "Temple of Eridu", "Priestly Administration");

        DataManager.getFocusTrees().put("susa", new FocusTree("susa", List.of(
                focus("susa_trade", "Highland Trade Routes", "Open regular exchange between the plain and the Zagros highlands.",
                        35, 5, 2, List.of(), List.of(), List.of(StrategyEffect.resource(ResourceType.GOLD, 80), StrategyEffect.relationNearest(15))),
                focus("susa_tablets", "Scribal Administration", "Record labour, stores and tribute systematically.",
                        45, 12, 1.4, List.of("susa_trade"), List.of(), List.of(StrategyEffect.pp(35), StrategyEffect.research(45))),
                focus("susa_council", "Strengthen the City Council", "Give merchants and administrators a permanent share in government.",
                        50, 18, 1, List.of("susa_tablets"), List.of("susa_palace"), List.of(StrategyEffect.government("Oligarchy"), StrategyEffect.stability(0.08))),
                focus("susa_palace", "A Palace Above the Plain", "Centralise taxation and command in a royal household.",
                        50, 18, 1, List.of("susa_tablets"), List.of("susa_council"), List.of(StrategyEffect.government("Monarchy"), StrategyEffect.pp(45))),
                focus("susa_workshops", "Specialist Workshops", "Concentrate skilled production in the city.",
                        55, 20, 1.2, List.of("susa_tablets"), List.of(), List.of(StrategyEffect.factories(1), StrategyEffect.spirit(NationalSpirit.INDUSTRIAL_DRIVE)))
        )));

        DataManager.getFocusTrees().put("egypt", new FocusTree("egypt", List.of(
                focus("nile_surveys", "Survey the Nile", "Measure fields, organise irrigation and regularise taxation.",
                        35, 5, 2, List.of(), List.of(), List.of(StrategyEffect.resource(ResourceType.FOOD, 450), StrategyEffect.pp(20))),
                focus("divine_kingship", "Divine Kingship", "Bind religious and temporal authority to the crown.",
                        50, 15, 1.4, List.of("nile_surveys"), List.of("nomarch_councils"), List.of(StrategyEffect.government("Theocracy"), StrategyEffect.stability(0.12))),
                focus("nomarch_councils", "Empower Regional Councils", "Let local elites share responsibility for the river provinces.",
                        50, 15, 1, List.of("nile_surveys"), List.of("divine_kingship"), List.of(StrategyEffect.government("Oligarchy"), StrategyEffect.pp(55))),
                focus("monumental_works", "Monumental Works", "Mobilise stone, labour and administration for state construction.",
                        60, 20, 1.2, List.of("divine_kingship"), List.of(), List.of(StrategyEffect.resource(ResourceType.STONE, -120), StrategyEffect.factories(1), StrategyEffect.stability(0.05))),
                focus("copper_army", "Copper-Armed Retainers", "Equip a permanent core of soldiers from central stores.",
                        55, 20, 1, List.of("nile_surveys"), List.of(), List.of(StrategyEffect.conscription(ConscriptionLevel.EXTENSIVE_CONSCRIPTION), StrategyEffect.resource(ResourceType.SUPPLIES, 250)))
        )));

        DataManager.getFocusTrees().put("indus", new FocusTree("indus", List.of(
                focus("planned_streets", "Planned Streets", "Lay out settlements on a consistent urban plan.",
                        40, 8, 2, List.of(), List.of(), List.of(StrategyEffect.roads(3), StrategyEffect.stability(0.05))),
                focus("standard_weights", "Standard Weights", "Standardise measures to strengthen long-distance commerce.",
                        45, 12, 1.5, List.of("planned_streets"), List.of(), List.of(StrategyEffect.pp(30), StrategyEffect.relationNearest(15))),
                focus("drainage", "Urban Drainage", "Invest in resilient civic infrastructure.",
                        50, 15, 1.3, List.of("planned_streets"), List.of(), List.of(StrategyEffect.spirit(NationalSpirit.CIVIC_ADMINISTRATION), StrategyEffect.stability(0.08))),
                focus("craft_quarters", "Craft Quarters", "Concentrate specialised production and experimentation.",
                        55, 18, 1.2, List.of("standard_weights"), List.of(), List.of(StrategyEffect.factories(1), StrategyEffect.research(80))),
                focus("city_confederacy", "League of Cities", "Formalise a cooperative political order among urban centres.",
                        60, 22, 1, List.of("drainage"), List.of(), List.of(StrategyEffect.government("Council Republic"), StrategyEffect.stability(0.07)))
        )));

        DataManager.getFocusTrees().put("minoan", new FocusTree("minoan", List.of(
                focus("palace_centres", "Palace Centres", "Coordinate storage, craft and administration around palace cities.",
                        40, 8, 2, List.of(), List.of(), List.of(StrategyEffect.factories(1), StrategyEffect.pp(25))),
                focus("maritime_trade", "Maritime Trade", "Turn the sea into the country's principal highway.",
                        45, 12, 1.7, List.of("palace_centres"), List.of(), List.of(StrategyEffect.resource(ResourceType.GOLD, 100), StrategyEffect.relationNearest(20))),
                focus("road_to_ports", "Roads to the Ports", "Connect inland producers to coastal exchange.",
                        50, 15, 1.2, List.of("palace_centres"), List.of(), List.of(StrategyEffect.roads(4), StrategyEffect.spirit(NationalSpirit.ROAD_BUILDERS))),
                focus("palatial_rule", "Palatial Rule", "Place the palace hierarchy above competing local institutions.",
                        55, 18, 1, List.of("palace_centres"), List.of("island_council"), List.of(StrategyEffect.government("Monarchy"), StrategyEffect.stability(0.08))),
                focus("island_council", "Island Council", "Create a broader council of the leading settlements.",
                        55, 18, 1, List.of("palace_centres"), List.of("palatial_rule"), List.of(StrategyEffect.government("Oligarchy"), StrategyEffect.pp(45)))
        )));

        DataManager.getFocusTrees().put("caral", new FocusTree("caral", List.of(
                focus("cotton_fishing", "Cotton-Fishing Exchange", "Bind inland cotton production to the resources of the coast.",
                        40, 8, 2, List.of(), List.of(), List.of(StrategyEffect.resource(ResourceType.FOOD, 300), StrategyEffect.relationNearest(10))),
                focus("platform_mounds", "Platform Mounds", "Mobilise labour for ceremonial and administrative centres.",
                        50, 14, 1.4, List.of("cotton_fishing"), List.of(), List.of(StrategyEffect.resource(ResourceType.STONE, -80), StrategyEffect.stability(0.10))),
                focus("ritual_confederacy", "Ritual Confederacy", "Use shared ceremonial institutions to bind settlements together.",
                        55, 18, 1.2, List.of("platform_mounds"), List.of(), List.of(StrategyEffect.government("Council Republic"), StrategyEffect.pp(35))),
                focus("irrigated_valleys", "Irrigated Valleys", "Expand reliable agriculture in the river valleys.",
                        50, 15, 1.6, List.of("cotton_fishing"), List.of(), List.of(StrategyEffect.spirit(NationalSpirit.AGRARIAN_TRADITION), StrategyEffect.resource(ResourceType.FOOD, 400)))
        )));

        DataManager.getFocusTrees().put("cliosoffice", new FocusTree("cliosoffice", List.of(
                focus("systems_engineering", "Systems Engineering", "Define measurable interfaces between every major institution.",
                        35, 5, 2, List.of(), List.of(), List.of(StrategyEffect.research(150), StrategyEffect.pp(25))),
                focus("evidence_governance", "Evidence-Based Governance", "Make policy dependent on measured outcomes rather than factional pressure.",
                        45, 12, 1.5, List.of("systems_engineering"), List.of(), List.of(StrategyEffect.government("Technocracy"), StrategyEffect.spirit(NationalSpirit.CIVIC_ADMINISTRATION))),
                focus("automated_industry", "Automated Industry", "Build a highly productive industrial base.",
                        55, 18, 1.4, List.of("systems_engineering"), List.of(), List.of(StrategyEffect.factories(2), StrategyEffect.spirit(NationalSpirit.INDUSTRIAL_DRIVE))),
                focus("research_network", "Research Network", "Link institutions into one national knowledge system.",
                        55, 20, 1.4, List.of("evidence_governance"), List.of(), List.of(StrategyEffect.spirit(NationalSpirit.SCIENTIFIC_CULTURE), StrategyEffect.research(250))),
                focus("infrastructure_model", "Infrastructure Model", "Optimise the movement of people, goods and supplies.",
                        50, 15, 1.2, List.of("automated_industry"), List.of(), List.of(StrategyEffect.roads(6), StrategyEffect.resource(ResourceType.SUPPLIES, 500)))
        )));
    }

    private static void registerSumerianTree(String civilisationId, String cityName,
                                             String monumentalTitle, String authorityTitle) {
        DataManager.getFocusTrees().put(civilisationId, new FocusTree(civilisationId, List.of(
                focus("irrigation", cityName + " Irrigation", "Organise canals and field labour around the city.",
                        35, 5, 2, List.of(), List.of(), List.of(StrategyEffect.resource(ResourceType.FOOD, 350), StrategyEffect.spirit(NationalSpirit.AGRARIAN_TRADITION))),
                focus("temple_economy", "Temple Economy", "Use central storehouses to coordinate labour and redistribution.",
                        45, 12, 1.5, List.of("irrigation"), List.of("royal_household"), List.of(StrategyEffect.government("Theocracy"), StrategyEffect.pp(35), StrategyEffect.stability(0.07))),
                focus("royal_household", authorityTitle, "Place more land, stores and soldiers under a royal household.",
                        45, 12, 1.4, List.of("irrigation"), List.of("temple_economy"), List.of(StrategyEffect.government("Monarchy"), StrategyEffect.pp(30))),
                focus("city_walls", "City Walls", "Concentrate stone and labour in the defence of the urban centre.",
                        50, 16, 1.2, List.of("irrigation"), List.of(), List.of(StrategyEffect.resource(ResourceType.STONE, -90), StrategyEffect.stability(0.06), StrategyEffect.resource(ResourceType.SUPPLIES, 180))),
                focus("regional_trade", "Regional Trade", "Regularise exchange with neighbouring cities.",
                        45, 12, 1.3, List.of("irrigation"), List.of(), List.of(StrategyEffect.resource(ResourceType.GOLD, 60), StrategyEffect.relationNearest(20))),
                focus("monumental_city", monumentalTitle, "Use the city's surplus for monumental construction and specialised labour.",
                        60, 20, 1, List.of("temple_economy"), List.of(), List.of(StrategyEffect.factories(1), StrategyEffect.research(60)))
        )));
    }

    private static FocusTree.FocusNode focus(String id, String title, String description,
                                              int durationSteps, double ppCost, double aiWeight,
                                              List<String> prerequisites, List<String> exclusive,
                                              List<StrategyEffect> effects) {
        return new FocusTree.FocusNode(id, title, description, durationSteps, ppCost, aiWeight,
                prerequisites, exclusive, effects);
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    private static void registerEvents() {
        putEvent(new GrandStrategyEvent("bountiful_harvest", "Bountiful Harvest",
                "The fields have produced far more food than expected.", Long.MIN_VALUE, Long.MAX_VALUE,
                0.0, 1.0, 20, 1.4, List.of(), List.of(
                option("store_grain", "Store the grain", "Build strategic reserves for harder years.", 1.2,
                        StrategyEffect.resource(ResourceType.FOOD, 300)),
                option("public_feast", "Hold public feasts", "Spend part of the surplus to strengthen social cohesion.", 1.0,
                        StrategyEffect.resource(ResourceType.FOOD, 160), StrategyEffect.stability(0.06))
        )));

        putEvent(new GrandStrategyEvent("crop_failure", "Crop Failure",
                "Poor weather and local failures have damaged the food supply.", Long.MIN_VALUE, Long.MAX_VALUE,
                0.0, 1.0, 25, 1.0, List.of(), List.of(
                option("release_reserves", "Release the reserves", "Use stored food to prevent unrest.", 1.4,
                        StrategyEffect.resource(ResourceType.FOOD, -180), StrategyEffect.stability(0.03)),
                option("ration", "Impose strict rationing", "Save food at the cost of public confidence.", 1.0,
                        StrategyEffect.resource(ResourceType.FOOD, -60), StrategyEffect.stability(-0.06))
        )));

        putEvent(new GrandStrategyEvent("new_ore_vein", "A New Ore Vein",
                "Prospectors report a promising mineral deposit near an existing settlement.", Long.MIN_VALUE, Long.MAX_VALUE,
                0.0, 1.0, 30, 1.0, List.of(), List.of(
                option("exploit_iron", "Exploit it immediately", "Prioritise useful metal production.", 1.3,
                        StrategyEffect.resource(ResourceType.IRON, 180), StrategyEffect.resource(ResourceType.COAL, 80)),
                option("study_deposit", "Study the deposit", "Use it to improve extraction knowledge.", 0.8,
                        StrategyEffect.research(90), StrategyEffect.resource(ResourceType.IRON, 70))
        )));

        putEvent(new GrandStrategyEvent("merchant_caravan", "Foreign Merchants Arrive",
                "A group of merchants from a neighbouring country asks for protected access to your markets.", Long.MIN_VALUE, Long.MAX_VALUE,
                0.0, 1.0, 20, 1.2, List.of(), List.of(
                option("welcome_merchants", "Welcome them", "Trade improves wealth and relations.", 1.5,
                        StrategyEffect.resource(ResourceType.GOLD, 70), StrategyEffect.relationNearest(15)),
                option("tax_heavily", "Tax them heavily", "Take the revenue now, even if relations suffer.", 0.8,
                        StrategyEffect.resource(ResourceType.GOLD, 130), StrategyEffect.relationNearest(-12))
        )));

        putEvent(new GrandStrategyEvent("factional_crisis", "Factional Crisis",
                "Rival elites are openly contesting the direction of the state.", Long.MIN_VALUE, Long.MAX_VALUE,
                0.0, 0.58, 25, 1.5, List.of(), List.of(
                option("compromise", "Broker a compromise", "Spend political capital to restore confidence.", 1.5,
                        StrategyEffect.pp(-25), StrategyEffect.stability(0.12)),
                option("back_strongman", "Back a strong ruler", "Centralise authority and accept the political risk.", 0.8,
                        StrategyEffect.government("Monarchy"), StrategyEffect.pp(20), StrategyEffect.stability(-0.03)),
                option("open_assembly", "Open a general assembly", "Recast the crisis as a constitutional settlement.", 0.9,
                        StrategyEffect.government("Council Republic"), StrategyEffect.stability(0.07))
        )));

        putEvent(new GrandStrategyEvent("migration_wave", "New Families Seek Land",
                "Families from beyond the frontier ask permission to settle inside the country.", Long.MIN_VALUE, Long.MAX_VALUE,
                0.0, 1.0, 30, 1.0, List.of(), List.of(
                option("accept_settlers", "Grant them land", "Population rises, but feeding everyone requires reserves.", 1.3,
                        StrategyEffect.population(8), StrategyEffect.resource(ResourceType.FOOD, -120)),
                option("turn_away", "Turn them away", "Avoid immediate pressure on the food supply.", 0.7,
                        StrategyEffect.stability(-0.02))
        )));

        putEvent(new GrandStrategyEvent("border_incident", "Border Incident",
                "Armed groups have clashed near the frontier with the nearest neighbouring country.", Long.MIN_VALUE, Long.MAX_VALUE,
                0.0, 1.0, 35, 0.8, List.of(), List.of(
                option("deescalate", "De-escalate", "Use diplomacy to keep the incident contained.", 1.2,
                        StrategyEffect.pp(-10), StrategyEffect.relationNearest(12)),
                option("demand_concessions", "Demand concessions", "Turn the incident into a test of resolve.", 0.8,
                        StrategyEffect.pp(15), StrategyEffect.relationNearest(-25), StrategyEffect.stability(0.02))
        )));

        putEvent(new GrandStrategyEvent("reform_movement", "Movement for Reform",
                "Administrators and urban groups are demanding changes to the political order.", -3500, Long.MAX_VALUE,
                0.35, 1.0, 40, 0.75, List.of(), List.of(
                option("controlled_reform", "Permit controlled reform", "Create a council-based government and gain legitimacy.", 1.1,
                        StrategyEffect.government("Council Republic"), StrategyEffect.stability(0.08), StrategyEffect.pp(-15)),
                option("preserve_order", "Preserve the existing order", "Reject the movement and rely on the current institutions.", 1.0,
                        StrategyEffect.pp(20), StrategyEffect.stability(-0.07))
        )));

        putEvent(new GrandStrategyEvent("river_flood", "The River Changes Course",
                "Floodwaters threaten farms and settlements, but they may also enrich the fields.", -3900, 1000,
                0.0, 1.0, 25, 1.0, List.of("uruk", "ur", "eridu", "susa", "egypt"), List.of(
                option("mobilise_dikes", "Mobilise workers for dikes", "Spend resources to protect settled land.", 1.4,
                        StrategyEffect.resource(ResourceType.WOOD, -80), StrategyEffect.resource(ResourceType.STONE, -60), StrategyEffect.stability(0.05)),
                option("accept_flood", "Let the flood run", "Accept disruption in exchange for fertile soil afterwards.", 0.8,
                        StrategyEffect.stability(-0.04), StrategyEffect.resource(ResourceType.FOOD, 260))
        )));

        putEvent(new GrandStrategyEvent("innovator", "An Innovator at Court",
                "A talented organiser proposes unfamiliar methods that could increase the state's capabilities.", Long.MIN_VALUE, Long.MAX_VALUE,
                0.0, 1.0, 30, 0.8, List.of(), List.of(
                option("fund_innovation", "Fund the work", "Commit political support to experimentation.", 1.2,
                        StrategyEffect.pp(-15), StrategyEffect.research(120)),
                option("practical_application", "Demand immediate application", "Turn the idea toward production instead.", 0.9,
                        StrategyEffect.factories(1), StrategyEffect.research(35))
        )));
    }

    private static GrandStrategyEvent.EventOption option(String id, String label, String description,
                                                          double aiWeight, StrategyEffect... effects) {
        return new GrandStrategyEvent.EventOption(id, label, description, aiWeight, List.of(effects));
    }

    private static void putEvent(GrandStrategyEvent event) {
        DataManager.getEvents().put(event.getId(), event);
    }
}



