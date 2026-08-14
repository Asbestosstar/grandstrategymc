package com.asbestosstar.grandstrategy.common.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A player-queueable factory product. Ingredients are Minecraft item ids. */
public class FactoryRecipe {
    private String id;
    private String name;
    private String outputItemId;
    private int outputCount = 1;
    private Map<String, Integer> ingredients = new LinkedHashMap<>();
    private List<String> factoryTypeIds = new ArrayList<>();
    private List<String> requiredTechnologyIds = new ArrayList<>();
    private List<String> requiredItemIds = new ArrayList<>();
    private String capability = "CRAFTING";

    public FactoryRecipe() { }

    public FactoryRecipe(String id, String name, String outputItemId, int outputCount,
                         Map<String, Integer> ingredients, List<String> factoryTypeIds,
                         List<String> requiredTechnologyIds, List<String> requiredItemIds,
                         String capability) {
        this.id = id;
        this.name = name;
        this.outputItemId = outputItemId;
        this.outputCount = outputCount;
        this.ingredients = ingredients == null ? new LinkedHashMap<>() : new LinkedHashMap<>(ingredients);
        this.factoryTypeIds = factoryTypeIds == null ? new ArrayList<>() : new ArrayList<>(factoryTypeIds);
        this.requiredTechnologyIds = requiredTechnologyIds == null ? new ArrayList<>() : new ArrayList<>(requiredTechnologyIds);
        this.requiredItemIds = requiredItemIds == null ? new ArrayList<>() : new ArrayList<>(requiredItemIds);
        this.capability = capability;
        normaliseAfterLoad();
    }

    public void normaliseAfterLoad() {
        if (ingredients == null) ingredients = new LinkedHashMap<>();
        if (factoryTypeIds == null) factoryTypeIds = new ArrayList<>();
        if (requiredTechnologyIds == null) requiredTechnologyIds = new ArrayList<>();
        if (requiredItemIds == null) requiredItemIds = new ArrayList<>();
        if (name == null || name.isBlank()) name = id == null ? "Product" : id;
        if (capability == null || capability.isBlank()) capability = "CRAFTING";
        outputCount = Math.max(1, outputCount);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getOutputItemId() { return outputItemId; }
    public int getOutputCount() { return Math.max(1, outputCount); }
    public Map<String, Integer> getIngredients() { return ingredients == null ? Map.of() : Map.copyOf(ingredients); }
    public List<String> getFactoryTypeIds() { return factoryTypeIds == null ? List.of() : List.copyOf(factoryTypeIds); }
    public List<String> getRequiredTechnologyIds() { return requiredTechnologyIds == null ? List.of() : List.copyOf(requiredTechnologyIds); }
    public List<String> getRequiredItemIds() { return requiredItemIds == null ? List.of() : List.copyOf(requiredItemIds); }
    public String getCapability() { return capability; }
}

