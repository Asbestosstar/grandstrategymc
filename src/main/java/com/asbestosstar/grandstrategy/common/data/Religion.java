package com.asbestosstar.grandstrategy.common.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Religion/sect definition.
 *
 * A religion may be a broad family or a sect of another religion. Availability is
 * historical and can be announced by an origin-city event. State secularism is a
 * policy pseudo-religion; atheism/irreligion are ordinary population/leader options.
 */
public class Religion {
    private String id;
    private String name;
    private String familyId;
    private String parentId;
    private String fallbackId;
    private long creationYear = Long.MIN_VALUE;
    private List<String> originCityNames = new ArrayList<>();
    private Map<String, Double> modifiers = new LinkedHashMap<>();
    private boolean stateOnly;
    private boolean populationAllowed = true;
    private boolean leaderAllowed = true;
    private boolean nonviolent;
    private double militaryMoraleModifier;
    private double rainModifier;
    private double cropGrowthModifier;
    private double researchModifier;

    public Religion() { }

    /** Backwards-compatible constructor used by old JSON/default code. */
    public Religion(String id, String name, Map<String, Double> modifiers) {
        this(id, name, id, null, null, Long.MIN_VALUE, List.of(), modifiers,
                false, true, true, false, 0.0, 0.0, 0.0, 0.0);
    }

    public Religion(String id, String name, String familyId, String parentId,
                    String fallbackId, long creationYear, List<String> originCityNames,
                    Map<String, Double> modifiers, boolean stateOnly,
                    boolean populationAllowed, boolean leaderAllowed, boolean nonviolent,
                    double militaryMoraleModifier, double rainModifier,
                    double cropGrowthModifier, double researchModifier) {
        this.id = id;
        this.name = name;
        this.familyId = familyId;
        this.parentId = parentId;
        this.fallbackId = fallbackId;
        this.creationYear = creationYear;
        this.originCityNames = originCityNames == null ? new ArrayList<>() : new ArrayList<>(originCityNames);
        this.modifiers = modifiers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(modifiers);
        this.stateOnly = stateOnly;
        this.populationAllowed = populationAllowed;
        this.leaderAllowed = leaderAllowed;
        this.nonviolent = nonviolent;
        this.militaryMoraleModifier = militaryMoraleModifier;
        this.rainModifier = rainModifier;
        this.cropGrowthModifier = cropGrowthModifier;
        this.researchModifier = researchModifier;
        normaliseAfterLoad();
    }

    public void normaliseAfterLoad() {
        if (originCityNames == null) originCityNames = new ArrayList<>();
        if (modifiers == null) modifiers = new LinkedHashMap<>();
        if (name == null || name.isBlank()) name = id == null ? "Religion" : id;
        if (familyId == null || familyId.isBlank()) familyId = id;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getFamilyId() { return familyId; }
    public String getParentId() { return parentId; }
    public String getFallbackId() { return fallbackId; }
    public long getCreationYear() { return creationYear; }
    public List<String> getOriginCityNames() { return originCityNames == null ? List.of() : List.copyOf(originCityNames); }
    public Map<String, Double> getModifiers() { return modifiers == null ? Map.of() : Map.copyOf(modifiers); }
    public boolean isStateOnly() { return stateOnly; }
    public boolean isPopulationAllowed() { return populationAllowed && !stateOnly; }
    public boolean isLeaderAllowed() { return leaderAllowed && !stateOnly; }
    public boolean isNonviolent() { return nonviolent; }
    public double getMilitaryMoraleModifier() { return militaryMoraleModifier; }
    public double getRainModifier() { return rainModifier; }
    public double getCropGrowthModifier() { return cropGrowthModifier; }
    public double getResearchModifier() { return researchModifier; }
}

