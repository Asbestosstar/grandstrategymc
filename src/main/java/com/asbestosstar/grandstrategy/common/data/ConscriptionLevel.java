package com.asbestosstar.grandstrategy.common.data;

/** Determines what share of the villager population is assigned as soldiers. */
public enum ConscriptionLevel {
	VOLUNTEER_ONLY("Volunteer Only", 0.02), LIMITED_CONSCRIPTION("Limited Conscription", 0.10),
	EXTENSIVE_CONSCRIPTION("Extensive Conscription", 0.20), SERVICE_BY_REQUIREMENT("Service by Requirement", 0.35),
	TOTAL_MOBILISATION("Total Mobilisation", 0.50);

	private final String displayName;
	private final double soldierShare;

	ConscriptionLevel(String displayName, double soldierShare) {
		this.displayName = displayName;
		this.soldierShare = soldierShare;
	}

	public String getDisplayName() {
		return displayName;
	}

	public double getSoldierShare() {
		return soldierShare;
	}

	public ConscriptionLevel next() {
		ConscriptionLevel[] values = values();
		return values[(ordinal() + 1) % values.length];
	}
}
