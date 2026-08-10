package com.asbestosstar.grandstrategy.common.data;

/**
 * The strategy task assigned to a villager population unit.
 *
 * Every strategy population unit belongs to exactly one task bucket, and the
 * server-side PhysicalVillagerSystem gives the corresponding real Minecraft
 * villager that job and its appropriate physical tool.
 */
public enum VillagerJob {
    FARMER("Farming"),
    LUMBERJACK("Lumber"),
    MINER("Mining"),
    FACTORY_BUILDER("Factory construction"),
    ROAD_BUILDER("Road construction"),
    RESEARCHER("Research"),
    ADMINISTRATOR("Administration"),
    SOLDIER("Soldiers");

    private final String displayName;

    VillagerJob(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}



