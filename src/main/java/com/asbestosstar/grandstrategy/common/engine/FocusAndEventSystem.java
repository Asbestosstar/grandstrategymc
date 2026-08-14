package com.asbestosstar.grandstrategy.common.engine;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.ConscriptionLevel;
import com.asbestosstar.grandstrategy.common.data.DataManager;
import com.asbestosstar.grandstrategy.common.data.FocusTree;
import com.asbestosstar.grandstrategy.common.data.GrandStrategyEvent;
import com.asbestosstar.grandstrategy.common.data.NationalSpirit;
import com.asbestosstar.grandstrategy.common.data.ResourceType;
import com.asbestosstar.grandstrategy.common.data.StrategyEffect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Server-authoritative national focus and event simulation.
 *
 * Focus progress deliberately uses bounded strategy steps rather than historical
 * calendar days. Otherwise a 100-years-per-Minecraft-day prehistoric clock would
 * complete an entire focus tree in seconds. Random events also occur in strategy
 * time, while their trigger years still use the GS historical timeline.
 */
public final class FocusAndEventSystem {
    private static final double EVENT_CHANCE_PER_STRATEGY_STEP = 1.0 / 100.0;
    private static final int EVENT_COOLDOWN_STEPS = 75;

    private FocusAndEventSystem() {
    }

    /** Called once per strategy step on the Minecraft server thread. */
    public static void tick(long currentYear) {
        for (Civilisation civilisation : DataManager.getCivilisations().values()) {
            if (!civilisation.isActive()) continue;
            tickFocus(civilisation);
            tickEvents(civilisation, currentYear);
        }
    }

    public static FocusTree treeFor(Civilisation civilisation) {
        if (civilisation == null) return null;
        FocusTree specific = DataManager.getFocusTrees().get(civilisation.getId());
        return specific != null ? specific : DataManager.getFocusTrees().get("generic");
    }

    public static boolean canStartFocus(Civilisation civilisation, FocusTree.FocusNode node) {
        if (civilisation == null || node == null || !civilisation.isActive()) return false;
        if (civilisation.getActiveFocusId() != null || civilisation.hasCompletedFocus(node.getId())) return false;
        if (civilisation.getPoliticalPower() + 1.0e-9 < node.getPoliticalPowerCost()) return false;
        for (String prerequisite : node.getPrerequisites()) {
            if (!civilisation.hasCompletedFocus(prerequisite)) return false;
        }
        for (String excluded : node.getMutuallyExclusive()) {
            if (civilisation.hasCompletedFocus(excluded)) return false;
        }
        return true;
    }

    /** Used by the multiplayer request handler. */
    public static boolean startFocus(Civilisation civilisation, String focusId) {
        FocusTree tree = treeFor(civilisation);
        FocusTree.FocusNode node = tree == null ? null : tree.getNode(focusId);
        return canStartFocus(civilisation, node)
                && civilisation.beginFocus(node.getId(), node.getPoliticalPowerCost());
    }

    private static void tickFocus(Civilisation civilisation) {
        FocusTree tree = treeFor(civilisation);
        if (tree == null || tree.getNodes().isEmpty()) return;

        String activeId = civilisation.getActiveFocusId();
        if (activeId == null) {
            if (!civilisation.isPlayerCreated()) {
                FocusTree.FocusNode choice = weightedFocusChoice(civilisation, tree);
                if (choice != null) startFocus(civilisation, choice.getId());
            }
            return;
        }

        FocusTree.FocusNode active = tree.getNode(activeId);
        if (active == null) {
            civilisation.cancelActiveFocus();
            return;
        }

        if (civilisation.advanceActiveFocus(1.0) + 1.0e-9 < active.getDurationSteps()) return;

        if (civilisation.completeFocus(active.getId())) {
            applyEffects(civilisation, active.getEffects());
            System.out.println("Grand Strategy focus completed: " + civilisation.getName()
                    + " -> " + active.getTitle());
        }
    }

    private static FocusTree.FocusNode weightedFocusChoice(Civilisation civilisation, FocusTree tree) {
        List<FocusTree.FocusNode> candidates = tree.getNodes().stream()
                .filter(node -> canStartFocus(civilisation, node))
                .toList();
        if (candidates.isEmpty()) return null;
        return weighted(candidates, FocusTree.FocusNode::getAiWeight);
    }

    private static void tickEvents(Civilisation civilisation, long currentYear) {
        civilisation.tickEventCooldown();
        if (civilisation.hasPendingEvent() || civilisation.getEventCooldownSteps() > 0) return;
        if (DataManager.getEvents().isEmpty()) return;
        if (ThreadLocalRandom.current().nextDouble() >= EVENT_CHANCE_PER_STRATEGY_STEP) return;

        List<GrandStrategyEvent> candidates = DataManager.getEvents().values().stream()
                .filter(event -> event != null && event.canTrigger(civilisation, currentYear))
                .filter(event -> !recentlyResolved(civilisation, event.getId()))
                .sorted(Comparator.comparing(GrandStrategyEvent::getId))
                .toList();
        if (candidates.isEmpty()) return;

        GrandStrategyEvent event = weighted(candidates, GrandStrategyEvent::getWeight);
        if (event == null || !civilisation.queueEvent(event.getId())) return;

        System.out.println("Grand Strategy event: " + civilisation.getName() + " -> " + event.getTitle());
        if (!civilisation.isPlayerCreated()) {
            GrandStrategyEvent.EventOption option = weighted(event.getOptions(),
                    GrandStrategyEvent.EventOption::getAiWeight);
            if (option != null) resolveEvent(civilisation, event.getId(), option.getId());
        }
    }

    private static boolean recentlyResolved(Civilisation civilisation, String eventId) {
        if (eventId == null) return false;
        List<String> history = civilisation.getEventHistory();
        int from = Math.max(0, history.size() - 4);
        for (int i = from; i < history.size(); i++) {
            if (history.get(i).startsWith(eventId + ":")) return true;
        }
        return false;
    }

    /** Used by a player's event-choice request and by AI event resolution. */
    public static boolean resolveEvent(Civilisation civilisation, String eventId, String optionId) {
        if (civilisation == null || eventId == null || optionId == null
                || !eventId.equals(civilisation.getPendingEventId())) return false;
        GrandStrategyEvent event = DataManager.getEvents().get(eventId);
        GrandStrategyEvent.EventOption option = event == null ? null : event.getOption(optionId);
        if (option == null) return false;

        applyEffects(civilisation, option.getEffects());
        return civilisation.resolvePendingEvent(eventId, optionId, EVENT_COOLDOWN_STEPS);
    }

    public static void applyEffects(Civilisation civilisation, List<StrategyEffect> effects) {
        if (civilisation == null || effects == null) return;
        for (StrategyEffect effect : effects) applyEffect(civilisation, effect);
    }

    private static void applyEffect(Civilisation civilisation, StrategyEffect effect) {
        if (effect == null || effect.getType() == null) return;
        switch (effect.getType()) {
            case RESOURCE -> {
                ResourceType type = enumValue(ResourceType.class, effect.getKey());
                if (type != null) civilisation.addResource(type, effect.getAmount());
            }
            case POLITICAL_POWER -> civilisation.addPoliticalPower(effect.getAmount());
            case STABILITY -> civilisation.setStability(civilisation.getStability() + effect.getAmount());
            case RESEARCH -> civilisation.addResearchPoints(effect.getAmount());
            case POPULATION -> {
                int amount = (int) Math.round(effect.getAmount());
                if (amount > 0) civilisation.addPopulation(amount);
                else if (amount < 0) civilisation.removePopulation(-amount);
            }
            case FACTORIES -> civilisation.addFactories((int) Math.round(effect.getAmount()));
            case ROADS -> civilisation.addRoadSegments((int) Math.round(effect.getAmount()));
            case NATIONAL_SPIRIT -> civilisation.addNationalSpiritId(effect.getKey());
            case REMOVE_NATIONAL_SPIRIT -> civilisation.removeNationalSpiritId(effect.getKey());
            case GOVERNMENT -> civilisation.setGovernment(effect.getKey());
            case CONSCRIPTION -> {
                ConscriptionLevel level = enumValue(ConscriptionLevel.class, effect.getKey());
                if (level != null) civilisation.setConscriptionLevel(level);
            }
            case RELATION_NEAREST -> {
                Civilisation neighbour = nearestActiveCivilisation(civilisation);
                if (neighbour != null) {
                    int delta = (int) Math.round(effect.getAmount());
                    civilisation.modifyRelation(neighbour.getId(), delta);
                    neighbour.modifyRelation(civilisation.getId(), delta / 2);
                }
            }
        }
    }

    private static Civilisation nearestActiveCivilisation(Civilisation source) {
        List<Civilisation> candidates = new ArrayList<>();
        for (Civilisation candidate : DataManager.getCivilisations().values()) {
            if (candidate != source && candidate.isActive()) candidates.add(candidate);
        }
        if (candidates.isEmpty()) return null;
        if (!source.hasWorldMapPosition()) return candidates.get(0);

        Civilisation best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Civilisation candidate : candidates) {
            if (!candidate.hasWorldMapPosition()) continue;
            long dx = (long) candidate.getWorldMapBlockX() - source.getWorldMapBlockX();
            long dz = (long) candidate.getWorldMapBlockZ() - source.getWorldMapBlockZ();
            long distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best != null ? best : candidates.get(0);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null) return null;
        try {
            return Enum.valueOf(type, value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static <T> T weighted(List<T> values, java.util.function.ToDoubleFunction<T> weightFunction) {
        if (values == null || values.isEmpty()) return null;
        double total = 0.0;
        for (T value : values) total += Math.max(0.0, weightFunction.applyAsDouble(value));
        if (total <= 0.0) return values.get(ThreadLocalRandom.current().nextInt(values.size()));
        double roll = ThreadLocalRandom.current().nextDouble(total);
        for (T value : values) {
            roll -= Math.max(0.0, weightFunction.applyAsDouble(value));
            if (roll <= 0.0) return value;
        }
        return values.get(values.size() - 1);
    }
}




