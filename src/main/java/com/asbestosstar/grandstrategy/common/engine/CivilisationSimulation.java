package com.asbestosstar.grandstrategy.common.engine;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.DataManager;
import com.asbestosstar.grandstrategy.common.data.NationalSpirit;
import com.asbestosstar.grandstrategy.common.data.ResourceType;
import com.asbestosstar.grandstrategy.common.data.VillagerJob;

/**
 * Strategy bookkeeping executed once per second on the server thread.
 *
 * Farming, logging, mining, road construction and factory construction are no
 * longer generated here from abstract job counters. They are performed by real
 * Villager entities in PhysicalVillagerSystem and materialised in the world.
 * This layer handles government/research/population bookkeeping and consumes the
 * physical chest-backed food and supply stockpiles.
 */
public final class CivilisationSimulation {
    private static final double BASE_BIRTH_PROGRESS_PER_PERSON_PER_STEP = 0.00020;
    private static final double BIRTH_MIN_FOOD_EQUIVALENT_PER_PERSON = 2.0;
    private static final double BIRTH_POPULATION_ACCELERATION_REFERENCE = 100.0;

    private CivilisationSimulation() {
    }

    /**
     * Legacy convenience tick. Historical activation is intentionally NOT done
     * here because StrategyEngine owns the persisted five-Minecraft-day gate.
     */
    public static void tickAll(long currentYear) {
        tickActiveEconomies();
    }

    /** Checked every server tick so high prehistoric calendar rates do not skip start dates. */
    public static void activateDueCivilisations(long currentYear) {
        for (Civilisation civilisation : DataManager.getCivilisations().values()) {
            if (civilisation.startIfDue(currentYear)) {
                ProvidenceSystem.assignStartingProvidence(civilisation);
                System.out.println("Civilisation " + civilisation.getName()
                        + " started/restored in " + currentYear + " with "
                        + civilisation.getPopulation() + " physical villagers queued"
                        + " (population modifier " + civilisation.getStartingPopulationModifier() + ").");
            }
        }
    }

    /** Economy remains a once-per-second strategy step rather than running 20x faster. */
    public static void tickActiveEconomies() {
        for (Civilisation civilisation : DataManager.getCivilisations().values()) {
            if (civilisation.isActive()) tickCivilisation(civilisation);
        }
    }

    private static void tickCivilisation(Civilisation civilisation) {
        civilisation.enforceConscription();

        double administration = civilisation.hasNationalSpirit(NationalSpirit.CIVIC_ADMINISTRATION) ? 1.30 : 1.0;

        // Food is no longer drained here as an abstract per-second number. Every
        // physical Grand Strategy villager now has an individual meal schedule and
        // consumes real bread/wheat from command-post chests in PhysicalVillagerSystem.
        // The numerical FOOD/SUPPLIES values below mirror those physical inventories
        // and are used only to decide whether the country has enough reserve for births.

        double supplyUse = civilisation.getJobCount(VillagerJob.SOLDIER) * 0.055
                + ProvidenceSystem.ownedProvidences(civilisation.getId()).size() * 0.08;
        if (!civilisation.consumeResource(ResourceType.SUPPLIES, supplyUse)) {
            civilisation.setStability(civilisation.getStability() - 0.002);
        }

        double ppGain = (0.035
                + civilisation.getJobCount(VillagerJob.ADMINISTRATOR) * 0.008)
                * administration * Math.max(0.25, civilisation.getStability());
        civilisation.addPoliticalPower(ppGain);

        // Research is now produced by physical researchers at factory workstations
        // in PhysicalVillagerSystem. Keeping an abstract per-job output here would
        // double-count research and reward idle/unloaded researchers.

        int population = civilisation.getPopulation();
        // Bread is made from three wheat in the factory loop, so count it as three
        // raw-food equivalents when evaluating whether the country can support more
        // births. A reserve of roughly two food-equivalents per existing villager is
        // required before population growth can advance at all.
        double foodEquivalent = civilisation.getResource(ResourceType.FOOD)
                + civilisation.getResource(ResourceType.SUPPLIES) * 3.0;
        double requiredReserve = population * BIRTH_MIN_FOOD_EQUIVALENT_PER_PERSON;
        if (population > 1 && foodEquivalent >= requiredReserve) {
            double spiritMultiplier = civilisation.hasNationalSpirit(NationalSpirit.AGRARIAN_TRADITION)
                    ? 1.25 : 1.0;

            // Births are population-driven: twice as many people already produce
            // more than twice as much aggregate birth progress, but the acceleration
            // is deliberately capped so large test populations cannot explode in a
            // handful of strategy steps.
            double populationAcceleration = 1.0 + Math.min(2.0,
                    Math.max(0.0, population - Civilisation.STARTING_POPULATION)
                            / BIRTH_POPULATION_ACCELERATION_REFERENCE);
            double foodSurplusMultiplier = Math.min(1.75, Math.max(1.0,
                    foodEquivalent / Math.max(requiredReserve, 1.0)));
            civilisation.addPopulationGrowthProgress(
                    population * BASE_BIRTH_PROGRESS_PER_PERSON_PER_STEP
                            * populationAcceleration * foodSurplusMultiplier * spiritMultiplier);
            int births = civilisation.consumeWholeBirths();
            if (births > 0) {
                // Each newborn rolls a random civilian profession. The physical
                // population reconciler then materialises the added villagers.
                civilisation.addBirthPopulationRandomised(births);
            }
        }
    }
}



