package com.asbestosstar.grandstrategy.common.data;

import java.util.ArrayList;
import java.util.List;

/** Data-driven physical factory/district type. */
public class FactoryType {
    private String id;
    private String name;
    private String description;
    private List<String> capabilities = new ArrayList<>();
    private List<String> requiredTechnologyIds = new ArrayList<>();
    private List<String> requiredItemIds = new ArrayList<>();
    private boolean starter;

    public FactoryType() { }

    public FactoryType(String id, String name, String description,
                       List<String> capabilities, List<String> requiredTechnologyIds,
                       List<String> requiredItemIds, boolean starter) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.capabilities = capabilities == null ? new ArrayList<>() : new ArrayList<>(capabilities);
        this.requiredTechnologyIds = requiredTechnologyIds == null ? new ArrayList<>() : new ArrayList<>(requiredTechnologyIds);
        this.requiredItemIds = requiredItemIds == null ? new ArrayList<>() : new ArrayList<>(requiredItemIds);
        this.starter = starter;
        normaliseAfterLoad();
    }

    public void normaliseAfterLoad() {
        if (capabilities == null) capabilities = new ArrayList<>();
        if (requiredTechnologyIds == null) requiredTechnologyIds = new ArrayList<>();
        if (requiredItemIds == null) requiredItemIds = new ArrayList<>();
        if (name == null || name.isBlank()) name = id == null ? "Factory" : id;
        if (description == null) description = "";
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public List<String> getCapabilities() { return capabilities == null ? List.of() : List.copyOf(capabilities); }
    public List<String> getRequiredTechnologyIds() { return requiredTechnologyIds == null ? List.of() : List.copyOf(requiredTechnologyIds); }
    public List<String> getRequiredItemIds() { return requiredItemIds == null ? List.of() : List.copyOf(requiredItemIds); }
    public boolean isStarter() { return starter; }
    public boolean hasCapability(String capability) {
        if (capability == null || capabilities == null) return false;
        return capabilities.stream().anyMatch(value -> capability.equalsIgnoreCase(value));
    }
}

