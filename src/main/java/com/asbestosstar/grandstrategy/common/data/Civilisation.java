package com.asbestosstar.grandstrategy.common.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Definition and mutable world state for a civilisation.
 *
 * The strategic record for a civilisation. Population and job counts are the
 * persistent strategy representation; PhysicalVillagerSystem materialises those
 * units as real vanilla Villager entities whenever the civilisation's supply
 * capital area is loaded on the authoritative server.
 */
public class Civilisation {
    public static final int STARTING_POPULATION = 25;

    private String id;
    private String name;
    private String defaultLeaderId;
    private String ideology;
    /** Government in effect when this civilisation was first defined. */
    private String defaultGovernment;
    /** State religion/policy. "secular" is valid here but not for ordinary people/leaders. */
    private String religion;
    /** Optional religion adopted as soon as that religion historically exists. */
    private String snapStateReligionId;
    /** Population religion shares, normalised to 1.0. */
    private Map<String, Double> populationReligions = new LinkedHashMap<>();
    private double religiousExtremism = 25.0;

    /** Population ideology/sect shares, normalised to 1.0. */
    private Map<String, Double> ideologySupport = new LinkedHashMap<>();
    private double ideologicalExtremism = 25.0;
    private String snapIdeologyId;

    /** Historical/demographic starting-size multiplier (e.g. Greenland < China). */
    private double startingPopulationModifier = 1.0;
    /** Country-specific names assigned to newly colonised cities, independent of country ownership. */
    private List<String> defaultCityNames = new ArrayList<>();
    private int nextDefaultCityNameIndex;

    /** Research queue/state. Definitions live in DataManager. */
    private List<String> completedTechnologyIds = new ArrayList<>();
    private String activeTechnologyId;
    private double activeTechnologyProgress;

    /** Player-requested industrial production orders. */
    private List<ProductionOrder> productionQueue = new ArrayList<>();
    private long productionOrderSerial;

    /** Prevents repeated internal-war checks from firing every simulation step. */
    private int civilWarCooldownSteps;

    private List<String> startingProvidences;

    private volatile double stability = 1.0;
    private volatile boolean active;
    // A collapsed civilisation is permanently retired from this world state.
    // This prevents historical start-date logic from reactivating it on the next tick.
    private boolean collapsed;
    private boolean playerCreated;
    // True after this civilisation has successfully founded/colonised its first
    // real world providence. This is distinct from merely being active: an active
    // historical civilisation may still be waiting for enough discovered land for
    // its first settlement. Once true it never becomes false, preventing a defeated
    // country from being silently re-founded in unrelated neutral territory.
    private boolean homelandEstablished;
    private String founderIgn;
    private String founderUuid;
    private long startYear = Long.MAX_VALUE;
    private Integer mapXPercent;
    private Integer mapYPercent;
    // Stable Minecraft Overworld anchor once this civilisation has been placed on discovered land.
    private Integer worldMapBlockX;
    private Integer worldMapBlockZ;
    // Opaque ARGB colour used for this country's strategic borders.
    // baseBorderColourArgb is the country's own identity colour; government changes
    // derive a new visible shade from it without making every country of one
    // government share the same colour.
    private Integer baseBorderColourArgb;
    private Integer borderColourArgb;
    private String borderColourGovernment;

    private int population;
    private double politicalPower;
    private Map<String, Double> resources = new LinkedHashMap<>();
    private Map<String, Integer> villagerJobs = new LinkedHashMap<>();
    private String conscriptionLevel = ConscriptionLevel.LIMITED_CONSCRIPTION.name();
    private List<String> nationalSpiritIds = new ArrayList<>();
    private Map<String, Integer> diplomaticRelations = new HashMap<>();

    // Per-country focus/event state. Definitions live in DataManager; only the
    // mutable choices/progress are persisted in the world save.
    private String activeFocusId;
    private double activeFocusProgress;
    private List<String> completedFocusIds = new ArrayList<>();
    private String pendingEventId;
    private int eventCooldownSteps;
    private long eventSerial;
    private List<String> eventHistory = new ArrayList<>();

    private int factories;
    private int roadSegments;
    private double factoryConstructionProgress;
    private double roadConstructionProgress;
    private double populationGrowthAccumulator;
    private double researchPoints;

    // Physical army control. AUTO lets server-side soldiers seek wartime objectives;
    // MANUAL makes them move toward a map-issued Overworld X/Z order.
    private String soldierControlMode = "AUTO";
    private Integer soldierOrderBlockX;
    private Integer soldierOrderBlockZ;

    // Peace-conference subject relationship. Null means fully sovereign.
    private String overlordCivilisationId;

    public Civilisation() {
        normaliseAfterLoad();
    }

    /** Backwards-compatible constructor used by older data packs. */
    public Civilisation(String id, String name, String defaultLeaderId, String ideology,
                        String religion, List<String> startingProvidences) {
        this(id, name, defaultLeaderId, ideology, religion, startingProvidences,
                Long.MAX_VALUE, null, null);
    }

    public Civilisation(String id, String name, String defaultLeaderId, String ideology,
                        String religion, List<String> startingProvidences,
                        long startYear, Integer mapXPercent, Integer mapYPercent) {
        this.id = id;
        this.name = name;
        this.defaultLeaderId = defaultLeaderId;
        this.ideology = ideology;
        this.defaultGovernment = ideology;
        this.religion = religion;
        this.startingProvidences = startingProvidences == null
                ? new ArrayList<>() : new ArrayList<>(startingProvidences);
        this.startYear = startYear;
        this.mapXPercent = mapXPercent;
        this.mapYPercent = mapYPercent;
        normaliseAfterLoad();
    }

    public synchronized void normaliseAfterLoad() {
        if (startingProvidences == null) startingProvidences = new ArrayList<>();
        if (populationReligions == null) populationReligions = new LinkedHashMap<>();
        if (ideologySupport == null) ideologySupport = new LinkedHashMap<>();
        if (defaultCityNames == null) defaultCityNames = new ArrayList<>();
        if (completedTechnologyIds == null) completedTechnologyIds = new ArrayList<>();
        if (productionQueue == null) productionQueue = new ArrayList<>();
        if (resources == null) resources = new LinkedHashMap<>();
        if (villagerJobs == null) villagerJobs = new LinkedHashMap<>();
        if (nationalSpiritIds == null) nationalSpiritIds = new ArrayList<>();
        if (diplomaticRelations == null) diplomaticRelations = new HashMap<>();
        if (completedFocusIds == null) completedFocusIds = new ArrayList<>();
        if (eventHistory == null) eventHistory = new ArrayList<>();
        if (ideology == null || ideology.isBlank()) ideology = "independent";
        if (defaultGovernment == null || defaultGovernment.isBlank()) defaultGovernment = ideology;
        if (religion == null || religion.isBlank() || "neutral".equalsIgnoreCase(religion)) religion = "secular";
        startingPopulationModifier = Math.max(0.05, Math.min(20.0, startingPopulationModifier));
        religiousExtremism = clamp(religiousExtremism, 0.0, 100.0);
        ideologicalExtremism = clamp(ideologicalExtremism, 0.0, 100.0);
        nextDefaultCityNameIndex = Math.max(0, nextDefaultCityNameIndex);
        activeTechnologyProgress = Math.max(0.0, activeTechnologyProgress);
        productionOrderSerial = Math.max(0L, productionOrderSerial);
        civilWarCooldownSteps = Math.max(0, civilWarCooldownSteps);

        if (baseBorderColourArgb == null || (baseBorderColourArgb >>> 24) == 0) {
            // Older saves only had borderColourArgb. Preserve a valid old colour as
            // the identity colour; WorldStateStore's v5 migration repairs duplicate
            // legacy colours where necessary.
            baseBorderColourArgb = borderColourArgb != null && (borderColourArgb >>> 24) != 0
                    ? opaque(borderColourArgb)
                    : defaultBorderColourForId(id);
        } else {
            baseBorderColourArgb = opaque(baseBorderColourArgb);
        }

        if (borderColourArgb == null || (borderColourArgb >>> 24) == 0) {
            borderColourArgb = colourForGovernment(baseBorderColourArgb, defaultGovernment, ideology);
        } else {
            borderColourArgb = opaque(borderColourArgb);
        }
        if (borderColourGovernment == null || borderColourGovernment.isBlank()) {
            borderColourGovernment = ideology;
        }
        if (conscriptionLevel == null || conscriptionLevel.isBlank()) {
            conscriptionLevel = ConscriptionLevel.LIMITED_CONSCRIPTION.name();
        }
        if (overlordCivilisationId != null && (overlordCivilisationId.isBlank() || overlordCivilisationId.equals(id))) {
            overlordCivilisationId = null;
        }
        if (!"MANUAL".equalsIgnoreCase(soldierControlMode)) {
            soldierControlMode = "AUTO";
            soldierOrderBlockX = null;
            soldierOrderBlockZ = null;
        } else if (soldierOrderBlockX == null || soldierOrderBlockZ == null) {
            soldierControlMode = "AUTO";
        }

        // v6.24 migration: older saves did not persist homelandEstablished.
        // Runtime-created permanent providences use world_providence_* IDs, and a
        // player-created country necessarily had a real homeland before it could be
        // saved. Preserve that history so a conquered old-save country cannot be
        // mistaken for a never-settled civilisation and given a replacement city.
        if (!homelandEstablished) {
            if (playerCreated && (active || collapsed)) {
                homelandEstablished = true;
            } else {
                for (String providenceId : startingProvidences) {
                    if (providenceId != null && providenceId.startsWith("world_providence_")) {
                        homelandEstablished = true;
                        break;
                    }
                }
            }
        }

        for (ResourceType resource : ResourceType.values()) {
            resources.putIfAbsent(resource.name(), 0.0);
        }

        // v6.6 migration: mining is one profession. Preserve old saves by folding
        // every legacy specialised miner bucket into the single MINER count.
        int legacyMiners = Math.max(0, villagerJobs.getOrDefault("STONE_MINER", 0))
                + Math.max(0, villagerJobs.getOrDefault("IRON_MINER", 0))
                + Math.max(0, villagerJobs.getOrDefault("COAL_MINER", 0))
                + Math.max(0, villagerJobs.getOrDefault("GOLD_MINER", 0));
        if (legacyMiners > 0) {
            villagerJobs.merge(VillagerJob.MINER.name(), legacyMiners, Integer::sum);
        }
        villagerJobs.remove("STONE_MINER");
        villagerJobs.remove("IRON_MINER");
        villagerJobs.remove("COAL_MINER");
        villagerJobs.remove("GOLD_MINER");

        for (VillagerJob job : VillagerJob.values()) {
            villagerJobs.putIfAbsent(job.name(), 0);
        }

        stability = clamp(stability, 0.0, 1.0);
        population = Math.max(0, population);
        politicalPower = Math.max(0.0, politicalPower);
        factories = Math.max(0, factories);
        roadSegments = Math.max(0, roadSegments);
        factoryConstructionProgress = Math.max(0.0, factoryConstructionProgress);
        roadConstructionProgress = Math.max(0.0, roadConstructionProgress);
        populationGrowthAccumulator = Math.max(0.0, populationGrowthAccumulator);
        researchPoints = Math.max(0.0, researchPoints);
        normalisePopulationReligionShares();
        normaliseIdeologyShares();
        productionQueue.removeIf(order -> order == null || order.getRecipeId() == null || order.isComplete());
        activeFocusProgress = Math.max(0.0, activeFocusProgress);
        eventCooldownSteps = Math.max(0, eventCooldownSteps);
        eventSerial = Math.max(0L, eventSerial);

        normaliseJobTotals();
    }

    /** Starts a historical/potential civilisation when its start date is reached. */
    public synchronized boolean startIfDue(long currentYear) {
        if (active || collapsed || playerCreated || currentYear < startYear) {
            return false;
        }
        active = true;
        initialiseStartingEconomy(currentYear);
        return true;
    }

    /** Starts a new player country with the required initial population of 25 villagers. */
    public synchronized void initialisePlayerCountry(String ign, long currentYear) {
        initialisePlayerCountry(ign, null, currentYear);
    }

    public synchronized void initialisePlayerCountry(String ign, String uuid, long currentYear) {
        playerCreated = true;
        founderIgn = ign;
        founderUuid = uuid;
        active = true;
        startYear = currentYear;
        stability = 1.0;
        initialiseStartingEconomy(currentYear);
    }

    /** Used by future liberation/restoration mechanics: later restorations begin larger. */
    public synchronized void initialiseRestoredCountry(long currentYear) {
        collapsed = false;
        active = true;
        startYear = currentYear;
        if (population <= 0) initialiseStartingEconomy(currentYear);
    }

    private void initialiseStartingEconomy(long currentYear) {
        if (population <= 0) {
            population = startingPopulationForYear(currentYear);
        }
        initialiseSocialComposition();

        // A new civilisation begins with people and empty supply chests, not a
        // magically-created strategic stockpile. Every physical resource must be
        // gathered, mined, harvested, crafted, traded or awarded later in play.
        politicalPower = 0.0;
        for (ResourceType resource : ResourceType.values()) {
            resources.put(resource.name(), 0.0);
        }

        if (totalAssignedVillagers() == 0) {
            autoAssignJobs();
        } else {
            normaliseJobTotals();
            enforceConscription();
        }
    }

    /** Historical population scaling: roughly doubles every 2,500 years after 4000 BCE. */
    public synchronized int startingPopulationForYear(long year) {
        double elapsed = Math.max(0.0, year + 4_000.0);
        double eraScale = Math.pow(2.0, elapsed / 2_500.0);
        int result = (int) Math.round(STARTING_POPULATION * startingPopulationModifier * eraScale);
        return Math.max(2, Math.min(2_048, result));
    }

    private void initialiseSocialComposition() {
        if (populationReligions.isEmpty()) {
            if ("secular".equalsIgnoreCase(religion)) {
                populationReligions.put("irreligion", 0.55);
                populationReligions.put("atheism", 0.15);
                populationReligions.put("local_cult", 0.30);
            } else {
                populationReligions.put(religion, 0.85);
                populationReligions.put("irreligion", 0.15);
            }
        }
        if (ideologySupport.isEmpty()) {
            ideologySupport.put(ideology, 0.85);
            ideologySupport.put("nonaligned", 0.15);
        }
        normalisePopulationReligionShares();
        normaliseIdeologyShares();
    }

    /** Rebuilds all job assignments while preserving population and conscription level. */
    public synchronized void autoAssignJobs() {
        for (VillagerJob job : VillagerJob.values()) {
            villagerJobs.put(job.name(), 0);
        }
        if (population <= 0) {
            return;
        }

        int soldiers = desiredSoldierCount();
        villagerJobs.put(VillagerJob.SOLDIER.name(), soldiers);
        int civilians = Math.max(0, population - soldiers);

        Map<VillagerJob, Double> shares = new LinkedHashMap<>();
        shares.put(VillagerJob.FARMER, 0.22);
        shares.put(VillagerJob.LUMBERJACK, 0.14);
        shares.put(VillagerJob.MINER, 0.30);
        shares.put(VillagerJob.FACTORY_BUILDER, 0.12);
        shares.put(VillagerJob.ROAD_BUILDER, 0.10);
        shares.put(VillagerJob.RESEARCHER, 0.07);
        shares.put(VillagerJob.ADMINISTRATOR, 0.05);

        int assigned = soldiers;
        for (Map.Entry<VillagerJob, Double> entry : shares.entrySet()) {
            int count = (int) Math.floor(civilians * entry.getValue());
            villagerJobs.put(entry.getKey().name(), count);
            assigned += count;
        }
        if (assigned < population) {
            villagerJobs.merge(VillagerJob.FARMER.name(), population - assigned, Integer::sum);
        }
        normaliseJobTotals();
    }

    public synchronized boolean reassignOneTo(VillagerJob target) {
        if (target == null || target == VillagerJob.SOLDIER || population <= 0) {
            return false;
        }

        VillagerJob source = largestCivilianJobExcept(target);
        if (source == null || getJobCount(source) <= 0) {
            return false;
        }
        villagerJobs.put(source.name(), getJobCount(source) - 1);
        villagerJobs.put(target.name(), getJobCount(target) + 1);
        return true;
    }

    public synchronized boolean reassignOneFrom(VillagerJob source) {
        if (source == null || source == VillagerJob.SOLDIER || getJobCount(source) <= 0) {
            return false;
        }
        VillagerJob target = source == VillagerJob.FARMER
                ? VillagerJob.ADMINISTRATOR : VillagerJob.FARMER;
        villagerJobs.put(source.name(), getJobCount(source) - 1);
        villagerJobs.put(target.name(), getJobCount(target) + 1);
        return true;
    }

    private VillagerJob largestCivilianJobExcept(VillagerJob excluded) {
        VillagerJob best = null;
        int bestCount = -1;
        for (VillagerJob job : VillagerJob.values()) {
            if (job == VillagerJob.SOLDIER || job == excluded) continue;
            int count = getJobCount(job);
            if (count > bestCount) {
                best = job;
                bestCount = count;
            }
        }
        return best;
    }

    public synchronized void enforceConscription() {
        int desired = desiredSoldierCount();
        int current = getJobCount(VillagerJob.SOLDIER);

        while (current < desired) {
            VillagerJob source = largestCivilianJobExcept(VillagerJob.SOLDIER);
            if (source == null || getJobCount(source) <= 0) break;
            villagerJobs.put(source.name(), getJobCount(source) - 1);
            current++;
            villagerJobs.put(VillagerJob.SOLDIER.name(), current);
        }

        while (current > desired) {
            current--;
            villagerJobs.put(VillagerJob.SOLDIER.name(), current);
            villagerJobs.put(VillagerJob.FARMER.name(), getJobCount(VillagerJob.FARMER) + 1);
        }
        normaliseJobTotals();
    }

    private int desiredSoldierCount() {
        return Math.max(0, Math.min(population,
                (int) Math.round(population * getConscriptionLevel().getSoldierShare())));
    }

    private void normaliseJobTotals() {
        int total = totalAssignedVillagers();
        if (total < population) {
            villagerJobs.merge(VillagerJob.FARMER.name(), population - total, Integer::sum);
        } else if (total > population) {
            int excess = total - population;
            for (VillagerJob job : VillagerJob.values()) {
                if (excess <= 0) break;
                int current = Math.max(0, villagerJobs.getOrDefault(job.name(), 0));
                int remove = Math.min(current, excess);
                villagerJobs.put(job.name(), current - remove);
                excess -= remove;
            }
        }
    }

    public synchronized int totalAssignedVillagers() {
        int total = 0;
        for (VillagerJob job : VillagerJob.values()) {
            total += Math.max(0, villagerJobs.getOrDefault(job.name(), 0));
        }
        return total;
    }

    public synchronized void addPopulation(int amount) {
        if (amount <= 0) return;
        population += amount;
        villagerJobs.merge(VillagerJob.FARMER.name(), amount, Integer::sum);
        enforceConscription();
    }

    /**
     * Cheat/debug population growth with deliberately varied professions.
     *
     * The operator population command uses this method so a large test population
     * does not appear as one giant farmer cohort. For additions of seven or more,
     * every civilian profession is represented at least once; the remainder is
     * weighted randomly toward the jobs that keep a settlement functioning.
     * Conscription is applied afterwards, so the configured soldier share remains
     * authoritative.
     */
    public synchronized void addPopulationRandomised(int amount) {
        if (amount <= 0) return;

        population += amount;

        List<VillagerJob> civilianJobs = new ArrayList<>(List.of(
                VillagerJob.FARMER,
                VillagerJob.LUMBERJACK,
                VillagerJob.MINER,
                VillagerJob.FACTORY_BUILDER,
                VillagerJob.ROAD_BUILDER,
                VillagerJob.RESEARCHER,
                VillagerJob.ADMINISTRATOR));
        Collections.shuffle(civilianJobs);

        int guaranteed = Math.min(amount, civilianJobs.size());
        for (int i = 0; i < guaranteed; i++) {
            villagerJobs.merge(civilianJobs.get(i).name(), 1, Integer::sum);
        }

        for (int i = guaranteed; i < amount; i++) {
            VillagerJob job = randomCivilianJob();
            villagerJobs.merge(job.name(), 1, Integer::sum);
        }

        enforceConscription();
    }

    /**
     * Natural births each roll an independent civilian profession. Unlike the cheat
     * population command, births do not force one of every job into a cohort; this
     * keeps a stream of single births genuinely random while still using the same
     * settlement-supporting weights. Conscription may subsequently move the required
     * share into the soldier bucket.
     */
    public synchronized void addBirthPopulationRandomised(int amount) {
        if (amount <= 0) return;
        population += amount;
        for (int i = 0; i < amount; i++) {
            VillagerJob job = randomCivilianJob();
            villagerJobs.merge(job.name(), 1, Integer::sum);
        }
        enforceConscription();
    }

    private VillagerJob randomCivilianJob() {
        // Broad enough to look organic, while still favouring food/resources over
        // administration.  Each newly added villager rolls independently.
        double roll = java.util.concurrent.ThreadLocalRandom.current().nextDouble();
        if (roll < 0.20) return VillagerJob.FARMER;
        if (roll < 0.37) return VillagerJob.LUMBERJACK;
        if (roll < 0.57) return VillagerJob.MINER;
        if (roll < 0.72) return VillagerJob.FACTORY_BUILDER;
        if (roll < 0.83) return VillagerJob.ROAD_BUILDER;
        if (roll < 0.92) return VillagerJob.RESEARCHER;
        return VillagerJob.ADMINISTRATOR;
    }

    public synchronized boolean removePopulation(int amount) {
        if (amount <= 0 || population <= 0) return false;
        int remove = Math.min(amount, population);
        population -= remove;
        normaliseJobTotals();
        enforceConscription();
        return true;
    }
    /** Exact population reset used only when an existing society splits in a civil war. */
    public synchronized void setPopulationForInternalSplit(int newPopulation) {
        population = Math.max(0, newPopulation);
        for (VillagerJob job : VillagerJob.values()) villagerJobs.put(job.name(), 0);
        if (population > 0) autoAssignJobs();
    }

    /** Removes a physical casualty from its actual job bucket before conscription is rebalanced. */
    public synchronized boolean removePopulationFromJob(VillagerJob job) {
        if (population <= 0) return false;
        if (job != null && getJobCount(job) > 0) {
            villagerJobs.put(job.name(), getJobCount(job) - 1);
        } else {
            for (VillagerJob fallback : VillagerJob.values()) {
                if (getJobCount(fallback) > 0) {
                    villagerJobs.put(fallback.name(), getJobCount(fallback) - 1);
                    break;
                }
            }
        }
        population--;
        normaliseJobTotals();
        enforceConscription();
        return true;
    }

    public synchronized double getResource(ResourceType type) {
        return Math.max(0.0, resources.getOrDefault(type.name(), 0.0));
    }

    public synchronized void addResource(ResourceType type, double amount) {
        if (type == null || amount == 0.0) return;
        resources.put(type.name(), Math.max(0.0, getResource(type) + amount));
    }

    /** Replaces the strategy ledger value. Physical supply depots use this after
     * counting the matching vanilla items in their real Minecraft chests. */
    public synchronized void setResource(ResourceType type, double amount) {
        if (type == null) return;
        resources.put(type.name(), Math.max(0.0, amount));
    }

    public synchronized boolean consumeResource(ResourceType type, double amount) {
        if (amount <= 0.0) return true;
        double current = getResource(type);
        if (current + 1.0e-9 < amount) return false;
        resources.put(type.name(), current - amount);
        return true;
    }

    public synchronized boolean consumeResources(Map<ResourceType, Double> costs) {
        for (Map.Entry<ResourceType, Double> entry : costs.entrySet()) {
            if (getResource(entry.getKey()) < entry.getValue()) return false;
        }
        for (Map.Entry<ResourceType, Double> entry : costs.entrySet()) {
            consumeResource(entry.getKey(), entry.getValue());
        }
        return true;
    }

    public synchronized int getJobCount(VillagerJob job) {
        return Math.max(0, villagerJobs.getOrDefault(job.name(), 0));
    }

    public synchronized Map<VillagerJob, Integer> getJobSnapshot() {
        Map<VillagerJob, Integer> snapshot = new LinkedHashMap<>();
        for (VillagerJob job : VillagerJob.values()) snapshot.put(job, getJobCount(job));
        return Collections.unmodifiableMap(snapshot);
    }

    public synchronized Map<ResourceType, Double> getResourceSnapshot() {
        Map<ResourceType, Double> snapshot = new LinkedHashMap<>();
        for (ResourceType type : ResourceType.values()) snapshot.put(type, getResource(type));
        return Collections.unmodifiableMap(snapshot);
    }

    public synchronized void addPoliticalPower(double amount) {
        politicalPower = Math.max(0.0, Math.min(99999.0, politicalPower + amount));
    }

    public synchronized boolean spendPoliticalPower(double amount) {
        if (amount <= 0.0) return true;
        if (politicalPower + 1.0e-9 < amount) return false;
        politicalPower -= amount;
        return true;
    }

    public synchronized void addFactoryProgress(double amount) {
        factoryConstructionProgress = Math.max(0.0, factoryConstructionProgress + amount);
    }

    public synchronized void addRoadProgress(double amount) {
        roadConstructionProgress = Math.max(0.0, roadConstructionProgress + amount);
    }

    public synchronized boolean completeFactoryIfReady() {
        if (factoryConstructionProgress < 100.0) return false;
        Map<ResourceType, Double> costs = Map.of(
                ResourceType.WOOD, 80.0,
                ResourceType.STONE, 120.0,
                ResourceType.IRON, 40.0);
        if (!consumeResources(costs)) {
            factoryConstructionProgress = Math.min(factoryConstructionProgress, 99.9);
            return false;
        }
        factoryConstructionProgress -= 100.0;
        factories++;
        return true;
    }

    public synchronized boolean completeRoadIfReady() {
        if (roadConstructionProgress < 100.0) return false;
        // Road progress is awarded only when ROAD_BUILDER villagers actually place
        // dirt-path/gravel blocks in the Minecraft world. Do not charge a second
        // abstract material bill here.
        roadConstructionProgress -= 100.0;
        roadSegments++;
        return true;
    }

    /** Called only after a physical factory shell, crafting table and furnace exist. */
    public synchronized void registerPhysicalFactory() {
        factories++;
        factoryConstructionProgress = Math.max(0.0, factoryConstructionProgress - 100.0);
    }

    public synchronized void addPopulationGrowthProgress(double amount) {
        populationGrowthAccumulator = Math.max(0.0, populationGrowthAccumulator + amount);
    }

    public synchronized int consumeWholeBirths() {
        int births = (int) Math.floor(populationGrowthAccumulator);
        if (births > 0) populationGrowthAccumulator -= births;
        return births;
    }

    public synchronized void addResearchPoints(double amount) {
        researchPoints = Math.max(0.0, researchPoints + amount);
    }

    public synchronized double consumeResearchPoints(double amount) {
        if (amount <= 0.0) return 0.0;
        double used = Math.min(researchPoints, amount);
        researchPoints -= used;
        return used;
    }

    // -------------------------- Technology / production --------------------------

    public synchronized List<String> getCompletedTechnologyIds() {
        return completedTechnologyIds == null ? List.of() : List.copyOf(completedTechnologyIds);
    }

    public synchronized boolean hasTechnology(String technologyId) {
        return technologyId != null && completedTechnologyIds != null && completedTechnologyIds.contains(technologyId);
    }

    public synchronized String getActiveTechnologyId() { return activeTechnologyId; }
    public synchronized double getActiveTechnologyProgress() { return Math.max(0.0, activeTechnologyProgress); }

    public synchronized boolean startTechnology(String technologyId) {
        if (technologyId == null || technologyId.isBlank() || hasTechnology(technologyId)) return false;
        if (technologyId.equals(activeTechnologyId)) return true;
        activeTechnologyId = technologyId;
        activeTechnologyProgress = 0.0;
        return true;
    }

    public synchronized void clearActiveTechnology() {
        activeTechnologyId = null;
        activeTechnologyProgress = 0.0;
    }

    public synchronized void addTechnologyProgress(double amount) {
        if (activeTechnologyId == null || amount <= 0.0) return;
        activeTechnologyProgress = Math.max(0.0, activeTechnologyProgress + amount);
    }

    public synchronized boolean completeTechnology(String technologyId) {
        if (technologyId == null || technologyId.isBlank()) return false;
        if (completedTechnologyIds == null) completedTechnologyIds = new ArrayList<>();
        if (completedTechnologyIds.contains(technologyId)) {
            if (technologyId.equals(activeTechnologyId)) clearActiveTechnology();
            return false;
        }
        completedTechnologyIds.add(technologyId);
        if (technologyId.equals(activeTechnologyId)) clearActiveTechnology();
        return true;
    }

    public synchronized List<ProductionOrder> getProductionQueue() {
        return productionQueue == null ? List.of() : List.copyOf(productionQueue);
    }

    public synchronized ProductionOrder queueProduction(String recipeId, int amount) {
        if (recipeId == null || recipeId.isBlank() || amount <= 0) return null;
        if (productionQueue == null) productionQueue = new ArrayList<>();
        ProductionOrder order = new ProductionOrder(++productionOrderSerial, recipeId, Math.min(100_000, amount));
        productionQueue.add(order);
        return order;
    }

    public synchronized boolean cancelProduction(long serial) {
        return productionQueue != null && productionQueue.removeIf(order -> order != null && order.getSerial() == serial);
    }

    public synchronized ProductionOrder firstProductionOrderForRecipeIds(List<String> allowedRecipeIds) {
        if (productionQueue == null || allowedRecipeIds == null || allowedRecipeIds.isEmpty()) return null;
        productionQueue.removeIf(order -> order == null || order.isComplete());
        for (ProductionOrder order : productionQueue) {
            if (!order.isPaused() && order.getRemaining() > 0 && allowedRecipeIds.contains(order.getRecipeId())) return order;
        }
        return null;
    }

    public synchronized void recordProduction(long serial, int amount) {
        if (productionQueue == null || amount <= 0) return;
        for (ProductionOrder order : productionQueue) {
            if (order != null && order.getSerial() == serial) {
                order.addCompleted(amount);
                break;
            }
        }
        productionQueue.removeIf(order -> order == null || order.isComplete());
    }

    /** Direct strategic awards used by completed focuses/events. */
    public synchronized void addFactories(int amount) {
        factories = Math.max(0, factories + amount);
    }

    public synchronized void addRoadSegments(int amount) {
        roadSegments = Math.max(0, roadSegments + amount);
    }

    public synchronized boolean addNationalSpiritId(String spiritId) {
        if (NationalSpirit.byId(spiritId) == null || nationalSpiritIds.contains(spiritId)) return false;
        nationalSpiritIds.add(spiritId);
        return true;
    }

    public synchronized boolean removeNationalSpiritId(String spiritId) {
        return spiritId != null && nationalSpiritIds.remove(spiritId);
    }

    public synchronized boolean toggleNationalSpirit(String spiritId) {
        NationalSpirit spirit = NationalSpirit.byId(spiritId);
        if (spirit == null || !playerCreated) return false;
        if (nationalSpiritIds.contains(spiritId)) {
            nationalSpiritIds.remove(spiritId);
        } else {
            nationalSpiritIds.add(spiritId);
        }
        return true;
    }

    public synchronized boolean hasNationalSpirit(NationalSpirit spirit) {
        return spirit != null && nationalSpiritIds.contains(spirit.getId());
    }

    public synchronized List<String> getNationalSpiritIds() {
        return List.copyOf(nationalSpiritIds);
    }

    // -------------------------- Focus state --------------------------

    public synchronized String getActiveFocusId() { return activeFocusId; }
    public synchronized double getActiveFocusProgress() { return Math.max(0.0, activeFocusProgress); }
    public synchronized List<String> getCompletedFocusIds() { return List.copyOf(completedFocusIds); }
    public synchronized boolean hasCompletedFocus(String focusId) {
        return focusId != null && completedFocusIds.contains(focusId);
    }

    public synchronized boolean beginFocus(String focusId, double politicalPowerCost) {
        if (!active || focusId == null || focusId.isBlank() || activeFocusId != null
                || hasCompletedFocus(focusId)) return false;
        if (!spendPoliticalPower(Math.max(0.0, politicalPowerCost))) return false;
        activeFocusId = focusId;
        activeFocusProgress = 0.0;
        return true;
    }

    public synchronized double advanceActiveFocus(double amount) {
        if (activeFocusId == null || amount <= 0.0) return activeFocusProgress;
        activeFocusProgress += amount;
        return activeFocusProgress;
    }

    public synchronized boolean completeFocus(String focusId) {
        if (focusId == null || !focusId.equals(activeFocusId)) return false;
        if (!completedFocusIds.contains(focusId)) completedFocusIds.add(focusId);
        activeFocusId = null;
        activeFocusProgress = 0.0;
        return true;
    }

    public synchronized void cancelActiveFocus() {
        activeFocusId = null;
        activeFocusProgress = 0.0;
    }

    // -------------------------- Event state --------------------------

    public synchronized String getPendingEventId() { return pendingEventId; }
    public synchronized boolean hasPendingEvent() { return pendingEventId != null && !pendingEventId.isBlank(); }
    public synchronized int getEventCooldownSteps() { return Math.max(0, eventCooldownSteps); }
    public synchronized long getEventSerial() { return Math.max(0L, eventSerial); }
    public synchronized List<String> getEventHistory() { return List.copyOf(eventHistory); }

    public synchronized boolean queueEvent(String eventId) {
        if (!active || hasPendingEvent() || eventId == null || eventId.isBlank()) return false;
        pendingEventId = eventId;
        eventSerial++;
        return true;
    }

    public synchronized boolean resolvePendingEvent(String eventId, String optionId, int cooldownSteps) {
        if (eventId == null || !eventId.equals(pendingEventId)) return false;
        String historyEntry = eventId + ":" + (optionId == null ? "?" : optionId);
        eventHistory.add(historyEntry);
        if (eventHistory.size() > 64) eventHistory.remove(0);
        pendingEventId = null;
        eventCooldownSteps = Math.max(1, cooldownSteps);
        return true;
    }

    public synchronized void tickEventCooldown() {
        if (eventCooldownSteps > 0) eventCooldownSteps--;
    }

    public synchronized int getRelation(String otherCivilisationId) {
        return Math.max(-100, Math.min(100,
                diplomaticRelations.getOrDefault(otherCivilisationId, 0)));
    }

    public synchronized void modifyRelation(String otherCivilisationId, int delta) {
        if (otherCivilisationId == null || otherCivilisationId.equals(id)) return;
        diplomaticRelations.put(otherCivilisationId,
                Math.max(-100, Math.min(100, getRelation(otherCivilisationId) + delta)));
    }

    public synchronized ConscriptionLevel getConscriptionLevel() {
        try {
            return ConscriptionLevel.valueOf(conscriptionLevel.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ignored) {
            conscriptionLevel = ConscriptionLevel.LIMITED_CONSCRIPTION.name();
            return ConscriptionLevel.LIMITED_CONSCRIPTION;
        }
    }

    public synchronized void setConscriptionLevel(ConscriptionLevel level) {
        if (level == null) return;
        conscriptionLevel = level.name();
        enforceConscription();
    }

    public synchronized void cycleConscriptionLevel() {
        setConscriptionLevel(getConscriptionLevel().next());
    }

    public synchronized String getSoldierControlMode() {
        return "MANUAL".equalsIgnoreCase(soldierControlMode) ? "MANUAL" : "AUTO";
    }

    public synchronized boolean isSoldierControlAutomatic() {
        return !"MANUAL".equalsIgnoreCase(soldierControlMode);
    }

    public synchronized boolean hasSoldierOrder() {
        return !isSoldierControlAutomatic() && soldierOrderBlockX != null && soldierOrderBlockZ != null;
    }

    public synchronized int getSoldierOrderBlockX() {
        return soldierOrderBlockX == null ? 0 : soldierOrderBlockX;
    }

    public synchronized int getSoldierOrderBlockZ() {
        return soldierOrderBlockZ == null ? 0 : soldierOrderBlockZ;
    }

    public synchronized void setSoldierControlAutomatic() {
        soldierControlMode = "AUTO";
        soldierOrderBlockX = null;
        soldierOrderBlockZ = null;
    }

    public synchronized void setSoldierManualOrder(int blockX, int blockZ) {
        soldierControlMode = "MANUAL";
        soldierOrderBlockX = blockX;
        soldierOrderBlockZ = blockZ;
    }

    public synchronized boolean isPuppet() {
        return overlordCivilisationId != null && !overlordCivilisationId.isBlank();
    }

    public synchronized String getOverlordCivilisationId() {
        return isPuppet() ? overlordCivilisationId : null;
    }

    public synchronized void setOverlordCivilisationId(String overlordId) {
        if (overlordId == null || overlordId.isBlank() || overlordId.equals(id)) {
            overlordCivilisationId = null;
        } else {
            overlordCivilisationId = overlordId;
            // A puppet cannot independently maintain a manual expeditionary order.
            soldierControlMode = "AUTO";
            soldierOrderBlockX = null;
            soldierOrderBlockZ = null;
        }
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDefaultLeaderId() { return defaultLeaderId; }
    public String getIdeology() { return ideology; }
    public String getGovernment() { return ideology; }
    public String getDefaultGovernment() { return defaultGovernment; }
    public synchronized String getReligion() { return religion; }
    public synchronized String getStateReligionId() { return religion; }
    public synchronized String getSnapStateReligionId() { return snapStateReligionId; }
    public synchronized void setStateReligionId(String religionId) {
        if (religionId != null && !religionId.isBlank()) religion = religionId;
    }
    public synchronized void setSnapStateReligionId(String religionId) { snapStateReligionId = religionId; }
    public synchronized double getReligiousExtremism() { return clamp(religiousExtremism, 0.0, 100.0); }
    public synchronized void setReligiousExtremism(double value) { religiousExtremism = clamp(value, 0.0, 100.0); }
    public synchronized void addReligiousExtremism(double delta) { setReligiousExtremism(religiousExtremism + delta); }
    public synchronized Map<String, Double> getPopulationReligions() { return Map.copyOf(populationReligions); }
    public synchronized double getPopulationReligionShare(String religionId) {
        return religionId == null ? 0.0 : Math.max(0.0, populationReligions.getOrDefault(religionId, 0.0));
    }
    public synchronized String getPopulationPluralityReligion() {
        return populationReligions.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("irreligion");
    }
    public synchronized void setPopulationReligionShare(String religionId, double share) {
        if (religionId == null || religionId.isBlank()) return;
        populationReligions.put(religionId, Math.max(0.0, share));
        normalisePopulationReligionShares();
    }
    public synchronized void shiftPopulationReligionToward(String religionId, double amount) {
        if (religionId == null || religionId.isBlank() || amount <= 0.0) return;
        normalisePopulationReligionShares();
        double target = populationReligions.getOrDefault(religionId, 0.0);
        double move = Math.min(Math.max(0.0, amount), Math.max(0.0, 1.0 - target));
        if (move <= 0.0) return;
        double otherTotal = Math.max(1.0e-9, 1.0 - target);
        for (String key : new ArrayList<>(populationReligions.keySet())) {
            if (religionId.equals(key)) continue;
            double share = populationReligions.getOrDefault(key, 0.0);
            populationReligions.put(key, Math.max(0.0, share - move * (share / otherTotal)));
        }
        populationReligions.put(religionId, target + move);
        normalisePopulationReligionShares();
    }
    public synchronized double getReligiousPopulationShare() {
        double total = 0.0;
        for (Map.Entry<String, Double> entry : populationReligions.entrySet()) {
            String key = entry.getKey();
            if ("atheism".equalsIgnoreCase(key) || "irreligion".equalsIgnoreCase(key) || "secular".equalsIgnoreCase(key)) continue;
            total += Math.max(0.0, entry.getValue());
        }
        return clamp(total, 0.0, 1.0);
    }
    /** Moves population share toward irreligion (negative) or the state religion (positive). */
    public synchronized void adjustPopulationReligiosity(double delta) {
        if (Math.abs(delta) < 1.0e-9) return;
        double change = Math.min(0.25, Math.abs(delta) / 100.0);
        if (delta < 0.0) {
            double religious = getReligiousPopulationShare();
            if (religious <= 0.0) return;
            double take = Math.min(religious, change);
            for (String key : new ArrayList<>(populationReligions.keySet())) {
                if ("atheism".equalsIgnoreCase(key) || "irreligion".equalsIgnoreCase(key) || "secular".equalsIgnoreCase(key)) continue;
                double share = populationReligions.getOrDefault(key, 0.0);
                double remove = take * (share / religious);
                populationReligions.put(key, Math.max(0.0, share - remove));
            }
            populationReligions.merge("irreligion", take, Double::sum);
        } else {
            String target = "secular".equalsIgnoreCase(religion) ? "irreligion" : religion;
            double source = populationReligions.getOrDefault("irreligion", 0.0) + populationReligions.getOrDefault("atheism", 0.0);
            double take = Math.min(source, change);
            if (take > 0.0) {
                double fromIrreligion = Math.min(take, populationReligions.getOrDefault("irreligion", 0.0));
                populationReligions.put("irreligion", Math.max(0.0, populationReligions.getOrDefault("irreligion", 0.0) - fromIrreligion));
                double rest = take - fromIrreligion;
                if (rest > 0) populationReligions.put("atheism", Math.max(0.0, populationReligions.getOrDefault("atheism", 0.0) - rest));
                populationReligions.merge(target, take, Double::sum);
            }
        }
        normalisePopulationReligionShares();
    }

    public synchronized Map<String, Double> getIdeologySupport() { return Map.copyOf(ideologySupport); }
    public synchronized double getIdeologicalExtremism() { return clamp(ideologicalExtremism, 0.0, 100.0); }
    public synchronized void setIdeologicalExtremism(double value) { ideologicalExtremism = clamp(value, 0.0, 100.0); }
    public synchronized void addIdeologicalExtremism(double delta) { setIdeologicalExtremism(ideologicalExtremism + delta); }
    public synchronized String getPopulationPluralityIdeology() {
        return ideologySupport.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("nonaligned");
    }
    public synchronized void setIdeologySupport(String ideologyId, double share) {
        if (ideologyId == null || ideologyId.isBlank()) return;
        ideologySupport.put(ideologyId, Math.max(0.0, share));
        normaliseIdeologyShares();
    }
    public synchronized void shiftIdeologySupportToward(String ideologyId, double amount) {
        if (ideologyId == null || ideologyId.isBlank() || amount <= 0.0) return;
        normaliseIdeologyShares();
        double target = ideologySupport.getOrDefault(ideologyId, 0.0);
        double move = Math.min(Math.max(0.0, amount), Math.max(0.0, 1.0 - target));
        if (move <= 0.0) return;
        double otherTotal = Math.max(1.0e-9, 1.0 - target);
        for (String key : new ArrayList<>(ideologySupport.keySet())) {
            if (ideologyId.equals(key)) continue;
            double share = ideologySupport.getOrDefault(key, 0.0);
            ideologySupport.put(key, Math.max(0.0, share - move * (share / otherTotal)));
        }
        ideologySupport.put(ideologyId, target + move);
        normaliseIdeologyShares();
    }
    public synchronized String getSnapIdeologyId() { return snapIdeologyId; }
    public synchronized void setSnapIdeologyId(String ideologyId) { snapIdeologyId = ideologyId; }

    public synchronized double getStartingPopulationModifier() { return startingPopulationModifier; }
    public synchronized void setStartingPopulationModifier(double modifier) {
        startingPopulationModifier = Math.max(0.05, Math.min(20.0, modifier));
    }
    public synchronized List<String> getDefaultCityNames() { return List.copyOf(defaultCityNames); }
    public synchronized void setDefaultCityNames(List<String> names) {
        defaultCityNames = names == null ? new ArrayList<>() : new ArrayList<>(names.stream().filter(name -> name != null && !name.isBlank()).toList());
        nextDefaultCityNameIndex = Math.min(nextDefaultCityNameIndex, defaultCityNames.size());
    }
    public synchronized String nextDefaultCityName() {
        if (defaultCityNames == null || defaultCityNames.isEmpty()) return name;
        String chosen = defaultCityNames.get(Math.floorMod(nextDefaultCityNameIndex, defaultCityNames.size()));
        nextDefaultCityNameIndex++;
        return chosen;
    }
    public synchronized int getCivilWarCooldownSteps() { return civilWarCooldownSteps; }
    public synchronized void setCivilWarCooldownSteps(int steps) { civilWarCooldownSteps = Math.max(0, steps); }
    public synchronized void tickCivilWarCooldown() { if (civilWarCooldownSteps > 0) civilWarCooldownSteps--; }

    public List<String> getStartingProvidences() { return startingProvidences == null ? List.of() : List.copyOf(startingProvidences); }
    public synchronized void addStartingProvidenceId(String providenceId) {
        if (providenceId == null || providenceId.isBlank()) return;
        if (startingProvidences == null) startingProvidences = new ArrayList<>();
        if (!startingProvidences.contains(providenceId)) startingProvidences.add(providenceId);
    }

    /** Replaces the start templates; used by save migration and data-driven rules. */
    public synchronized void replaceStartingProvidences(List<String> providenceIds) {
        startingProvidences = providenceIds == null ? new ArrayList<>() : new ArrayList<>(providenceIds);
    }

    /**
     * Changes government and immediately derives a new country colour. The colour
     * remains based on this country's own base colour, so two monarchies or two
     * republics do not become visually identical.
     */
    public synchronized void setGovernment(String government) {
        if (government == null || government.isBlank()) return;
        String next = government.trim();
        for (Ideology definition : DataManager.getIdeologies().values()) {
            if (definition == null) continue;
            if (next.equalsIgnoreCase(definition.getId()) || next.equalsIgnoreCase(definition.getName())) {
                next = definition.getId();
                break;
            }
        }
        if (defaultGovernment == null || defaultGovernment.isBlank()) {
            defaultGovernment = ideology == null || ideology.isBlank() ? next : ideology;
        }
        ideology = next;
        refreshGovernmentColour();
    }

    /** Backwards-compatible terminology for older code/data. */
    public synchronized void setIdeology(String ideology) {
        setGovernment(ideology);
    }

    /** Re-defines the built-in/default government during a save-format migration. */
    public synchronized void redefineDefaultGovernment(String government) {
        if (government == null || government.isBlank()) return;
        defaultGovernment = government.trim();
        ideology = defaultGovernment;
        refreshGovernmentColour();
    }

    /** Save-migration hook for civilisations whose historical start date changed. */
    public synchronized void redefineHistoricalStartYear(long historicalStartYear) {
        this.startYear = historicalStartYear;
    }

    /** Replaces the colony city-name sequence without renaming existing cities. */
    public synchronized void redefineDefaultCityNames(List<String> cityNames) {
        defaultCityNames = cityNames == null ? new ArrayList<>() : new ArrayList<>(cityNames);
        if (nextDefaultCityNameIndex < 0) nextDefaultCityNameIndex = 0;
    }

    /** Simple testable government transition used by the Politics screen. */
    public synchronized void cycleGovernment() {
        List<String> governments = List.of(
                "Independent", "Council Republic", "Monarchy", "Theocracy",
                "Oligarchy", "Technocracy", "Republic");
        int index = governments.indexOf(ideology);
        setGovernment(governments.get(index < 0 ? 0 : (index + 1) % governments.size()));
    }
    public double getStability() { return stability; }
    public boolean isActive() { return active; }
    public boolean isCollapsed() { return collapsed; }

    /**
     * Permanently retires this civilisation from the current world. Returns true
     * only for the first real active -> collapsed transition so callers can log
     * the event exactly once.
     */
    public synchronized boolean collapse() {
        if (collapsed || !active) return false;
        collapsed = true;
        active = false;
        soldierControlMode = "AUTO";
        soldierOrderBlockX = null;
        soldierOrderBlockZ = null;
        activeFocusId = null;
        pendingEventId = null;
        return true;
    }

    public boolean hasEstablishedHomeland() { return homelandEstablished; }

    /** Marks that this country has had its one-time initial homeland. Never cleared. */
    public synchronized void markHomelandEstablished() {
        homelandEstablished = true;
    }

    public boolean isPlayerCreated() { return playerCreated; }
    public String getFounderIgn() { return founderIgn; }
    public String getFounderUuid() { return founderUuid; }
    public long getStartYear() { return startYear; }
    public int getPopulation() { return Math.max(0, population); }
    public double getPoliticalPower() { return Math.max(0.0, politicalPower); }
    public int getFactories() { return Math.max(0, factories); }
    public int getRoadSegments() { return Math.max(0, roadSegments); }
    public double getFactoryConstructionProgress() { return Math.max(0.0, factoryConstructionProgress); }
    public double getRoadConstructionProgress() { return Math.max(0.0, roadConstructionProgress); }
    public double getPopulationGrowthAccumulator() { return Math.max(0.0, populationGrowthAccumulator); }
    public double getResearchPoints() { return Math.max(0.0, researchPoints); }

    public int getMapXPercent() {
        if (mapXPercent != null) return clampInt(mapXPercent, 5, 95);
        return 8 + Math.floorMod(id == null ? 0 : id.hashCode(), 84);
    }

    public int getMapYPercent() {
        if (mapYPercent != null) return clampInt(mapYPercent, 8, 92);
        int hash = id == null ? 0 : Integer.rotateRight(id.hashCode(), 11);
        return 10 + Math.floorMod(hash, 80);
    }

    public synchronized int getBorderColourArgb() {
        if (baseBorderColourArgb == null || (baseBorderColourArgb >>> 24) == 0) {
            baseBorderColourArgb = defaultBorderColourForId(id);
        }
        if (borderColourArgb == null || (borderColourArgb >>> 24) == 0
                || !sameGovernment(borderColourGovernment, ideology)) {
            refreshGovernmentColour();
        }
        return borderColourArgb;
    }

    public synchronized int getDefaultBorderColourArgb() {
        if (baseBorderColourArgb == null || (baseBorderColourArgb >>> 24) == 0) {
            baseBorderColourArgb = defaultBorderColourForId(id);
        }
        return baseBorderColourArgb;
    }

    /** Sets this country's identity/default colour, not a global government colour. */
    public synchronized void setBorderColourArgb(int colour) {
        baseBorderColourArgb = opaque(colour);
        refreshGovernmentColour();
    }

    /** Used by state migration to repair the old same-colour country bug. */
    public synchronized void restoreDefaultCountryColour() {
        baseBorderColourArgb = defaultBorderColourForId(id);
        refreshGovernmentColour();
    }

    private void refreshGovernmentColour() {
        int base = baseBorderColourArgb == null
                ? defaultBorderColourForId(id) : opaque(baseBorderColourArgb);
        borderColourArgb = colourForGovernment(base, defaultGovernment, ideology);
        borderColourGovernment = ideology;
    }

    /** Built-ins have deliberately separated colours; custom/player countries use a vivid ID-derived hue. */
    public static int defaultBorderColourForId(String id) {
        String key = id == null ? "" : id.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "uruk" -> 0xFFE67E22;       // orange
            case "eridu" -> 0xFF3498DB;      // blue
            case "susa" -> 0xFF9B59B6;       // purple
            case "ur" -> 0xFF2ECC71;         // green
            case "egypt" -> 0xFFF1C40F;      // gold
            case "indus" -> 0xFF1ABC9C;      // teal
            case "minoan" -> 0xFFE74C3C;     // red
            case "caral" -> 0xFFEC407A;      // pink
            case "cliosoffice" -> 0xFF00BCD4; // cyan
            default -> generatedVividColour(key);
        };
    }

    public static boolean hasBuiltInDefaultColour(String id) {
        if (id == null) return false;
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "uruk", "eridu", "susa", "ur", "egypt", "indus",
                    "minoan", "caral", "cliosoffice" -> true;
            default -> false;
        };
    }

    private static int generatedVividColour(String key) {
        int hash = key == null ? 0x5F7A8A : key.hashCode();
        double hue = Math.floorMod(hash, 360) / 60.0;
        double saturation = 0.68;
        double value = 0.90;
        int sector = (int) Math.floor(hue) % 6;
        double f = hue - Math.floor(hue);
        double p = value * (1.0 - saturation);
        double q = value * (1.0 - f * saturation);
        double t = value * (1.0 - (1.0 - f) * saturation);
        double r;
        double g;
        double b;
        switch (sector) {
            case 0 -> { r = value; g = t; b = p; }
            case 1 -> { r = q; g = value; b = p; }
            case 2 -> { r = p; g = value; b = t; }
            case 3 -> { r = p; g = q; b = value; }
            case 4 -> { r = t; g = p; b = value; }
            default -> { r = value; g = p; b = q; }
        }
        return 0xFF000000
                | ((int) Math.round(r * 255.0) << 16)
                | ((int) Math.round(g * 255.0) << 8)
                | (int) Math.round(b * 255.0);
    }

    private static int colourForGovernment(int base, String defaultGovernment, String government) {
        base = opaque(base);
        if (sameGovernment(defaultGovernment, government)) return base;

        String key = government == null ? "independent" : government.toLowerCase(Locale.ROOT);
        int hash = key.hashCode();
        int tintR = 48 + Math.floorMod(hash, 176);
        int tintG = 48 + Math.floorMod(Integer.rotateLeft(hash, 11), 176);
        int tintB = 48 + Math.floorMod(Integer.rotateLeft(hash, 22), 176);
        int baseR = (base >>> 16) & 0xFF;
        int baseG = (base >>> 8) & 0xFF;
        int baseB = base & 0xFF;

        // 75% country identity + 25% government tint. A government change is
        // obvious, but the country remains recognisable and unique.
        int r = (baseR * 3 + tintR) / 4;
        int g = (baseG * 3 + tintG) / 4;
        int b = (baseB * 3 + tintB) / 4;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static boolean sameGovernment(String a, String b) {
        if (a == null || b == null) return a == null && b == null;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private static int opaque(int colour) {
        return 0xFF000000 | (colour & 0x00FFFFFF);
    }

    public boolean hasWorldMapPosition() {
        return worldMapBlockX != null && worldMapBlockZ != null;
    }

    public int getWorldMapBlockX() {
        return worldMapBlockX == null ? 0 : worldMapBlockX;
    }

    public int getWorldMapBlockZ() {
        return worldMapBlockZ == null ? 0 : worldMapBlockZ;
    }

    public synchronized void setWorldMapPosition(int blockX, int blockZ) {
        this.worldMapBlockX = blockX;
        this.worldMapBlockZ = blockZ;
    }

    private synchronized void normalisePopulationReligionShares() {
        if (populationReligions == null) populationReligions = new LinkedHashMap<>();
        populationReligions.entrySet().removeIf(entry -> entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue() <= 0.0);
        double total = populationReligions.values().stream().mapToDouble(value -> Math.max(0.0, value)).sum();
        if (total <= 1.0e-9) {
            populationReligions.put("irreligion", 1.0);
            return;
        }
        for (String key : new ArrayList<>(populationReligions.keySet())) {
            populationReligions.put(key, Math.max(0.0, populationReligions.get(key)) / total);
        }
    }

    private synchronized void normaliseIdeologyShares() {
        if (ideologySupport == null) ideologySupport = new LinkedHashMap<>();
        ideologySupport.entrySet().removeIf(entry -> entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue() <= 0.0);
        double total = ideologySupport.values().stream().mapToDouble(value -> Math.max(0.0, value)).sum();
        if (total <= 1.0e-9) {
            ideologySupport.put(ideology == null ? "nonaligned" : ideology, 1.0);
            return;
        }
        for (String key : new ArrayList<>(ideologySupport.keySet())) {
            ideologySupport.put(key, Math.max(0.0, ideologySupport.get(key)) / total);
        }
    }

    public void setStability(double stability) {
        this.stability = clamp(stability, 0.0, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public String toString() {
        return "Civilisation{id='" + id + "', name='" + name + "', active=" + active + "}";
    }
}



