package com.asbestosstar.grandstrategy.common.data;

/**
 * Represents a troop type in the Grand Strategy mod.
 * Following British English standards.
 */
public class TroopType {
    private String id;
    private String name;
    private double attack;
    private double defence;
    private String baseMobType; // e.g., "minecraft:creeper"

    public TroopType() {}

    public TroopType(String id, String name, double attack, double defence, String baseMobType) {
        this.id = id;
        this.name = name;
        this.attack = attack;
        this.defence = defence;
        this.baseMobType = baseMobType;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public double getAttack() { return attack; }
    public double getDefence() { return defence; }
    public String getBaseMobType() { return baseMobType; }
}




