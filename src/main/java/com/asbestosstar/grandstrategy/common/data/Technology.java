package com.asbestosstar.grandstrategy.common.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data-driven technology definition used by the research and industry systems.
 */
public class Technology {
	private String id;
	private String name;
	private String description;
	private long baseYear;
	private long backwaterYear = Long.MAX_VALUE;
	private double baseResearchSeconds = 180.0;
	private List<String> prerequisites = new ArrayList<>();
	/** Every listed item id must exist for this technology to be present. */
	private List<String> requiredItemIds = new ArrayList<>();
	/** When non-empty, at least one of these item ids must exist. */
	private List<String> anyRequiredItemIds = new ArrayList<>();
	private List<String> unlockFactoryTypeIds = new ArrayList<>();
	private List<String> unlockRecipeIds = new ArrayList<>();
	/**
	 * Job name -> highest tool tier enabled by this technology. "*" applies to all
	 * jobs.
	 */
	private Map<String, String> unlockToolTiers = new LinkedHashMap<>();
	/**
	 * Applied once on completion. Negative values weaken organised
	 * religion/extremism.
	 */
	private double religiousExtremismDelta;
	private double populationReligiosityDelta;
	private double ideologicalExtremismDelta;
	private double stabilityDelta;

	public Technology() {
	}

	public Technology(String id, String name, String description, long baseYear, long backwaterYear,
			double baseResearchSeconds, List<String> prerequisites, List<String> requiredItemIds,
			List<String> anyRequiredItemIds, List<String> unlockFactoryTypeIds, List<String> unlockRecipeIds,
			Map<String, String> unlockToolTiers, double religiousExtremismDelta, double populationReligiosityDelta,
			double ideologicalExtremismDelta, double stabilityDelta) {
		this.id = id;
		this.name = name;
		this.description = description;
		this.baseYear = baseYear;
		this.backwaterYear = backwaterYear;
		this.baseResearchSeconds = baseResearchSeconds;
		this.prerequisites = prerequisites == null ? new ArrayList<>() : new ArrayList<>(prerequisites);
		this.requiredItemIds = requiredItemIds == null ? new ArrayList<>() : new ArrayList<>(requiredItemIds);
		this.anyRequiredItemIds = anyRequiredItemIds == null ? new ArrayList<>() : new ArrayList<>(anyRequiredItemIds);
		this.unlockFactoryTypeIds = unlockFactoryTypeIds == null ? new ArrayList<>()
				: new ArrayList<>(unlockFactoryTypeIds);
		this.unlockRecipeIds = unlockRecipeIds == null ? new ArrayList<>() : new ArrayList<>(unlockRecipeIds);
		this.unlockToolTiers = unlockToolTiers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(unlockToolTiers);
		this.religiousExtremismDelta = religiousExtremismDelta;
		this.populationReligiosityDelta = populationReligiosityDelta;
		this.ideologicalExtremismDelta = ideologicalExtremismDelta;
		this.stabilityDelta = stabilityDelta;
		normaliseAfterLoad();
	}

	public void normaliseAfterLoad() {
		if (prerequisites == null)
			prerequisites = new ArrayList<>();
		if (requiredItemIds == null)
			requiredItemIds = new ArrayList<>();
		if (anyRequiredItemIds == null)
			anyRequiredItemIds = new ArrayList<>();
		if (unlockFactoryTypeIds == null)
			unlockFactoryTypeIds = new ArrayList<>();
		if (unlockRecipeIds == null)
			unlockRecipeIds = new ArrayList<>();
		if (unlockToolTiers == null)
			unlockToolTiers = new LinkedHashMap<>();
		if (name == null || name.isBlank())
			name = id == null ? "Technology" : id;
		if (description == null)
			description = "";
		baseResearchSeconds = Math.max(5.0, baseResearchSeconds);
	}

	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public long getBaseYear() {
		return baseYear;
	}

	public long getBackwaterYear() {
		return backwaterYear;
	}

	public double getBaseResearchSeconds() {
		return Math.max(5.0, baseResearchSeconds);
	}

	public List<String> getPrerequisites() {
		return prerequisites == null ? List.of() : List.copyOf(prerequisites);
	}

	public List<String> getRequiredItemIds() {
		return requiredItemIds == null ? List.of() : List.copyOf(requiredItemIds);
	}

	public List<String> getAnyRequiredItemIds() {
		return anyRequiredItemIds == null ? List.of() : List.copyOf(anyRequiredItemIds);
	}

	public List<String> getUnlockFactoryTypeIds() {
		return unlockFactoryTypeIds == null ? List.of() : List.copyOf(unlockFactoryTypeIds);
	}

	public List<String> getUnlockRecipeIds() {
		return unlockRecipeIds == null ? List.of() : List.copyOf(unlockRecipeIds);
	}

	public Map<String, String> getUnlockToolTiers() {
		return unlockToolTiers == null ? Map.of() : Map.copyOf(unlockToolTiers);
	}

	public double getReligiousExtremismDelta() {
		return religiousExtremismDelta;
	}

	public double getPopulationReligiosityDelta() {
		return populationReligiosityDelta;
	}

	public double getIdeologicalExtremismDelta() {
		return ideologicalExtremismDelta;
	}

	public double getStabilityDelta() {
		return stabilityDelta;
	}
}
