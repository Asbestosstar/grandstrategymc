package com.asbestosstar.grandstrategy.common.data;

/**
 * Save/network-friendly justified objective against another civilisation.
 *
 * Only ids and primitive values are stored here so the goal survives loader changes,
 * multiplayer sync, and optional-mod combinations without linking to loader APIs.
 */
public final class DiplomaticWarGoal {
    public static final String TERRITORY = "TERRITORY";
    public static final String PUPPET = "PUPPET";

    private String targetCivilisationId;
    private String type;
    private String territoryId;
    private long justifiedYear;

    public DiplomaticWarGoal() { }

    public DiplomaticWarGoal(String targetCivilisationId, String type, String territoryId, long justifiedYear) {
        this.targetCivilisationId = targetCivilisationId;
        this.type = type;
        this.territoryId = territoryId;
        this.justifiedYear = justifiedYear;
    }

    public String getTargetCivilisationId() { return targetCivilisationId; }
    public String getType() { return type; }
    public String getTerritoryId() { return territoryId; }
    public long getJustifiedYear() { return justifiedYear; }

    public boolean isTerritoryGoal() { return TERRITORY.equals(type); }
    public boolean isPuppetGoal() { return PUPPET.equals(type); }

    public DiplomaticWarGoal copy() {
        return new DiplomaticWarGoal(targetCivilisationId, type, territoryId, justifiedYear);
    }
}
