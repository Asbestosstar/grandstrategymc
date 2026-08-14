package com.asbestosstar.grandstrategy.common.data;

/**
 * Physical equipment progression for Grand Strategy villagers.
 *
 * HAND means the worker is visibly unarmed. The remaining tiers map to the
 * corresponding tool for that worker's profession: axe for forestry, pickaxe
 * for mining/building, hoe for farming and sword for soldiers.
 *
 * STEEL is an optional registry-id backed tier. If the providing mod is absent,
 * the progression code skips it rather than creating a dependency.
 */
public enum WorkerToolTier {
    HAND("Hand", 1.00, 0.0),
    WOOD("Wood", 1.15, 8.0),
    STONE("Stone", 1.35, 28.0),
    IRON("Iron", 1.70, 75.0),
    STEEL("Steel", 1.95, 125.0),
    DIAMOND("Diamond", 2.20, 185.0);

    private final String displayName;
    private final double workMultiplier;
    private final double requiredExperience;

    WorkerToolTier(String displayName, double workMultiplier, double requiredExperience) {
        this.displayName = displayName;
        this.workMultiplier = workMultiplier;
        this.requiredExperience = requiredExperience;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double getWorkMultiplier() {
        return workMultiplier;
    }

    public double getRequiredExperience() {
        return requiredExperience;
    }

    public WorkerToolTier next() {
        int next = ordinal() + 1;
        return next >= values().length ? this : values()[next];
    }
}




