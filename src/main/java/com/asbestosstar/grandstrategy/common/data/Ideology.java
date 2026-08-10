package com.asbestosstar.grandstrategy.common.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ideology family/sect definition with historical fallbacks and religion compatibility. */
public class Ideology {
    private String id;
    private String name;
    private String familyId;
    private String parentId;
    private String fallbackId;
    private long availableYear = Long.MIN_VALUE;
    private List<String> requiredTechnologyIds = new ArrayList<>();
    private Map<String, Double> modifiers = new LinkedHashMap<>();
    /** Religion id/family -> diplomatic compatibility from -1 to +1. */
    private Map<String, Double> religionCompatibility = new LinkedHashMap<>();
    private boolean nonAligned;

    public Ideology() { }

    public Ideology(String id, String name, String familyId, String parentId,
                    String fallbackId, long availableYear,
                    List<String> requiredTechnologyIds,
                    Map<String, Double> modifiers,
                    Map<String, Double> religionCompatibility,
                    boolean nonAligned) {
        this.id = id;
        this.name = name;
        this.familyId = familyId;
        this.parentId = parentId;
        this.fallbackId = fallbackId;
        this.availableYear = availableYear;
        this.requiredTechnologyIds = requiredTechnologyIds == null ? new ArrayList<>() : new ArrayList<>(requiredTechnologyIds);
        this.modifiers = modifiers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(modifiers);
        this.religionCompatibility = religionCompatibility == null ? new LinkedHashMap<>() : new LinkedHashMap<>(religionCompatibility);
        this.nonAligned = nonAligned;
        normaliseAfterLoad();
    }

    public void normaliseAfterLoad() {
        if (requiredTechnologyIds == null) requiredTechnologyIds = new ArrayList<>();
        if (modifiers == null) modifiers = new LinkedHashMap<>();
        if (religionCompatibility == null) religionCompatibility = new LinkedHashMap<>();
        if (name == null || name.isBlank()) name = id == null ? "Ideology" : id;
        if (familyId == null || familyId.isBlank()) familyId = id;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getFamilyId() { return familyId; }
    public String getParentId() { return parentId; }
    public String getFallbackId() { return fallbackId; }
    public long getAvailableYear() { return availableYear; }
    public List<String> getRequiredTechnologyIds() { return requiredTechnologyIds == null ? List.of() : List.copyOf(requiredTechnologyIds); }
    public Map<String, Double> getModifiers() { return modifiers == null ? Map.of() : Map.copyOf(modifiers); }
    public Map<String, Double> getReligionCompatibility() { return religionCompatibility == null ? Map.of() : Map.copyOf(religionCompatibility); }
    public boolean isNonAligned() { return nonAligned; }
}
