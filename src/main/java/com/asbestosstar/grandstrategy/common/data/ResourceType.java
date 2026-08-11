package com.asbestosstar.grandstrategy.common.data;

/** Strategy-level resources produced by a civilisation. */
public enum ResourceType {
    FOOD("Food"),
    WOOD("Wood"),
    STONE("Stone"),
    IRON("Iron"),
    COAL("Coal"),
    GOLD("Gold"),
    COPPER("Copper"),
    REDSTONE("Redstone"),
    LAPIS("Lapis"),
    EMERALD("Emerald"),
    DIAMOND("Diamond"),
    SUPPLIES("Supplies");

    private final String displayName;

    ResourceType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}




