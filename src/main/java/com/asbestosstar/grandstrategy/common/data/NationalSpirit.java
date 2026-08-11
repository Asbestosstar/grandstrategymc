package com.asbestosstar.grandstrategy.common.data;

/** Editable national spirits available to player-created countries. */
public enum NationalSpirit {
    AGRARIAN_TRADITION("agrarian_tradition", "Agrarian Tradition", "+25% food production and population growth"),
    INDUSTRIAL_DRIVE("industrial_drive", "Industrial Drive", "+25% factory construction"),
    MINING_CULTURE("mining_culture", "Mining Culture", "+20% stone, iron, coal and gold production"),
    ROAD_BUILDERS("road_builders", "Road Builders", "+30% road construction"),
    MARTIAL_SOCIETY("martial_society", "Martial Society", "+15% military organisation"),
    CIVIC_ADMINISTRATION("civic_administration", "Civic Administration", "+30% political power gain"),
    SCIENTIFIC_CULTURE("scientific_culture", "Scientific Culture", "+25% research output");

    private final String id;
    private final String displayName;
    private final String description;

    NationalSpirit(String id, String displayName, String description) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public static NationalSpirit byId(String id) {
        if (id == null) {
            return null;
        }
        for (NationalSpirit spirit : values()) {
            if (spirit.id.equals(id)) {
                return spirit;
            }
        }
        return null;
    }
}




