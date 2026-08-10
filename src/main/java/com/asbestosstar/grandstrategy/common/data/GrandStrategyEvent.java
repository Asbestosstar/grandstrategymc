package com.asbestosstar.grandstrategy.common.data;

import java.util.ArrayList;
import java.util.List;

/** A random or conditional strategy event with player/AI choices. */
public class GrandStrategyEvent {
    private String id;
    private String title;
    private String description;
    private long minYear = Long.MIN_VALUE;
    private long maxYear = Long.MAX_VALUE;
    private double minStability;
    private double maxStability = 1.0;
    private int minPopulation;
    private List<String> civilisationIds = new ArrayList<>();
    private double weight = 1.0;
    private List<EventOption> options = new ArrayList<>();

    public GrandStrategyEvent() {
    }

    public GrandStrategyEvent(String id, String title, String description,
                              long minYear, long maxYear,
                              double minStability, double maxStability,
                              int minPopulation, double weight,
                              List<String> civilisationIds,
                              List<EventOption> options) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.minYear = minYear;
        this.maxYear = maxYear;
        this.minStability = minStability;
        this.maxStability = maxStability;
        this.minPopulation = minPopulation;
        this.weight = weight;
        this.civilisationIds = civilisationIds == null ? new ArrayList<>() : new ArrayList<>(civilisationIds);
        this.options = options == null ? new ArrayList<>() : new ArrayList<>(options);
    }

    public boolean canTrigger(Civilisation civilisation, long year) {
        if (civilisation == null || !civilisation.isActive()) return false;
        if (year < minYear || year > maxYear) return false;
        if (civilisation.getStability() < minStability || civilisation.getStability() > maxStability) return false;
        if (civilisation.getPopulation() < minPopulation) return false;
        return civilisationIds == null || civilisationIds.isEmpty()
                || civilisationIds.contains(civilisation.getId());
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public long getMinYear() { return minYear; }
    public long getMaxYear() { return maxYear; }
    public double getWeight() { return Math.max(0.0, weight); }
    public List<EventOption> getOptions() { return options == null ? List.of() : List.copyOf(options); }

    public EventOption getOption(String optionId) {
        if (optionId == null || options == null) return null;
        for (EventOption option : options) {
            if (option != null && optionId.equals(option.id)) return option;
        }
        return null;
    }

    public static final class EventOption {
        private String id;
        private String label;
        private String description;
        private double aiWeight = 1.0;
        private List<StrategyEffect> effects = new ArrayList<>();

        public EventOption() {
        }

        public EventOption(String id, String label, String description,
                           double aiWeight, List<StrategyEffect> effects) {
            this.id = id;
            this.label = label;
            this.description = description;
            this.aiWeight = aiWeight;
            this.effects = effects == null ? new ArrayList<>() : new ArrayList<>(effects);
        }

        public String getId() { return id; }
        public String getLabel() { return label; }
        public String getDescription() { return description; }
        public double getAiWeight() { return Math.max(0.0, aiWeight); }
        public List<StrategyEffect> getEffects() { return effects == null ? List.of() : List.copyOf(effects); }
    }
}



