package com.asbestosstar.grandstrategy.common.data;

/**
 * Data-driven effect used by focus nodes and events.
 *
 * The fields are intentionally simple so server/world JSON files can add new
 * outcomes without Java code. "key" is used by typed effects such as RESOURCE,
 * GOVERNMENT, NATIONAL_SPIRIT and CONSCRIPTION.
 */
public class StrategyEffect {
    public enum Type {
        RESOURCE,
        POLITICAL_POWER,
        STABILITY,
        RESEARCH,
        POPULATION,
        FACTORIES,
        ROADS,
        NATIONAL_SPIRIT,
        REMOVE_NATIONAL_SPIRIT,
        GOVERNMENT,
        CONSCRIPTION,
        RELATION_NEAREST
    }

    private Type type;
    private String key;
    private double amount;

    public StrategyEffect() {
    }

    public StrategyEffect(Type type, String key, double amount) {
        this.type = type;
        this.key = key;
        this.amount = amount;
    }

    public static StrategyEffect resource(ResourceType type, double amount) {
        return new StrategyEffect(Type.RESOURCE, type == null ? null : type.name(), amount);
    }

    public static StrategyEffect pp(double amount) {
        return new StrategyEffect(Type.POLITICAL_POWER, null, amount);
    }

    public static StrategyEffect stability(double amount) {
        return new StrategyEffect(Type.STABILITY, null, amount);
    }

    public static StrategyEffect research(double amount) {
        return new StrategyEffect(Type.RESEARCH, null, amount);
    }

    public static StrategyEffect population(int amount) {
        return new StrategyEffect(Type.POPULATION, null, amount);
    }

    public static StrategyEffect factories(int amount) {
        return new StrategyEffect(Type.FACTORIES, null, amount);
    }

    public static StrategyEffect roads(int amount) {
        return new StrategyEffect(Type.ROADS, null, amount);
    }

    public static StrategyEffect spirit(NationalSpirit spirit) {
        return new StrategyEffect(Type.NATIONAL_SPIRIT,
                spirit == null ? null : spirit.getId(), 1.0);
    }

    public static StrategyEffect removeSpirit(NationalSpirit spirit) {
        return new StrategyEffect(Type.REMOVE_NATIONAL_SPIRIT,
                spirit == null ? null : spirit.getId(), 1.0);
    }

    public static StrategyEffect government(String government) {
        return new StrategyEffect(Type.GOVERNMENT, government, 0.0);
    }

    public static StrategyEffect conscription(ConscriptionLevel level) {
        return new StrategyEffect(Type.CONSCRIPTION, level == null ? null : level.name(), 0.0);
    }

    public static StrategyEffect relationNearest(int amount) {
        return new StrategyEffect(Type.RELATION_NEAREST, null, amount);
    }

    public Type getType() { return type; }
    public String getKey() { return key; }
    public double getAmount() { return amount; }
}



