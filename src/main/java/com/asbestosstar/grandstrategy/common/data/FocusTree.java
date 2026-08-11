package com.asbestosstar.grandstrategy.common.data;

import java.util.ArrayList;
import java.util.List;

/**
 * Data-driven national focus tree. Completion state belongs to each
 * civilisation.
 */
public class FocusTree {
	private String civilisationId;
	private List<FocusNode> nodes = new ArrayList<>();

	public FocusTree() {
	}

	public FocusTree(String civilisationId) {
		this.civilisationId = civilisationId;
	}

	public FocusTree(String civilisationId, List<FocusNode> nodes) {
		this.civilisationId = civilisationId;
		this.nodes = nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);
	}

	public String getCivilisationId() {
		return civilisationId;
	}

	public List<FocusNode> getNodes() {
		return nodes == null ? List.of() : List.copyOf(nodes);
	}

	public FocusNode getNode(String id) {
		if (id == null || nodes == null)
			return null;
		for (FocusNode node : nodes) {
			if (node != null && id.equals(node.id))
				return node;
		}
		return null;
	}

	public static class FocusNode {
		private String id;
		private String title;
		private String description;
		private List<String> prerequisites = new ArrayList<>();
		private List<String> mutuallyExclusive = new ArrayList<>();
		/**
		 * One strategy step is one real second at the default 20-tick step interval.
		 */
		private int durationSteps = 45;
		private double politicalPowerCost = 10.0;
		private double aiWeight = 1.0;
		private List<StrategyEffect> effects = new ArrayList<>();
		// Kept only for backwards compatibility with very early JSON definitions.
		@Deprecated
		private boolean completed;

		public FocusNode() {
		}

		public FocusNode(String id, String title, String description) {
			this.id = id;
			this.title = title;
			this.description = description;
		}

		public FocusNode(String id, String title, String description, int durationSteps, double politicalPowerCost,
				double aiWeight, List<String> prerequisites, List<String> mutuallyExclusive,
				List<StrategyEffect> effects) {
			this.id = id;
			this.title = title;
			this.description = description;
			this.durationSteps = durationSteps;
			this.politicalPowerCost = politicalPowerCost;
			this.aiWeight = aiWeight;
			this.prerequisites = prerequisites == null ? new ArrayList<>() : new ArrayList<>(prerequisites);
			this.mutuallyExclusive = mutuallyExclusive == null ? new ArrayList<>() : new ArrayList<>(mutuallyExclusive);
			this.effects = effects == null ? new ArrayList<>() : new ArrayList<>(effects);
		}

		public String getId() {
			return id;
		}

		public String getTitle() {
			return title;
		}

		public String getDescription() {
			return description;
		}

		public List<String> getPrerequisites() {
			return prerequisites == null ? List.of() : List.copyOf(prerequisites);
		}

		public List<String> getMutuallyExclusive() {
			return mutuallyExclusive == null ? List.of() : List.copyOf(mutuallyExclusive);
		}

		public int getDurationSteps() {
			return Math.max(1, durationSteps);
		}

		public double getPoliticalPowerCost() {
			return Math.max(0.0, politicalPowerCost);
		}

		public double getAiWeight() {
			return Math.max(0.0, aiWeight);
		}

		public List<StrategyEffect> getEffects() {
			return effects == null ? List.of() : List.copyOf(effects);
		}

		@Deprecated
		public boolean isCompleted() {
			return completed;
		}

		@Deprecated
		public void setCompleted(boolean completed) {
			this.completed = completed;
		}
	}
}
