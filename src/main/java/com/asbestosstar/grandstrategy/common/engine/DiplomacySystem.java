package com.asbestosstar.grandstrategy.common.engine;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.DataManager;
import com.asbestosstar.grandstrategy.common.data.DiplomaticWarGoal;
import com.asbestosstar.grandstrategy.common.data.Ideology;
import com.asbestosstar.grandstrategy.common.data.Leader;
import com.asbestosstar.grandstrategy.common.data.Providence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Server-authoritative bilateral diplomacy and AI diplomacy.
 *
 * The persistent state lives on Civilisation so it automatically travels through the
 * existing Gson save/network snapshots. This class contains only game rules: no client,
 * loader, or optional-mod APIs are referenced here.
 */
public final class DiplomacySystem {
    public static final String IMPROVE = "IMPROVE";
    public static final String INSULT = "INSULT";
    public static final String GUARANTEE = "GUARANTEE";
    public static final String CANCEL_GUARANTEE = "CANCEL_GUARANTEE";
    public static final String ALLIANCE = "ALLIANCE";
    public static final String CANCEL_ALLIANCE = "CANCEL_ALLIANCE";
    public static final String DEFENSIVE_PACT = "DEFENSIVE_PACT";
    public static final String CANCEL_DEFENSIVE_PACT = "CANCEL_DEFENSIVE_PACT";
    public static final String MILITARY_ACCESS = "MILITARY_ACCESS";
    public static final String CANCEL_MILITARY_ACCESS = "CANCEL_MILITARY_ACCESS";
    public static final String RESEARCH_AGREEMENT = "RESEARCH_AGREEMENT";
    public static final String CANCEL_RESEARCH_AGREEMENT = "CANCEL_RESEARCH_AGREEMENT";
    public static final String JOIN_FACTION = "JOIN_FACTION";
    public static final String LEAVE_FACTION = "LEAVE_FACTION";
    public static final String ROYAL_WEDDING = "ROYAL_WEDDING";
    public static final String JUSTIFY_TERRITORY = "JUSTIFY_TERRITORY";
    public static final String JUSTIFY_PUPPET = "JUSTIFY_PUPPET";
    public static final String DECLARE_WAR = "DECLARE_WAR";
    public static final String SPECIAL_MILITARY_OPERATION = "SPECIAL_MILITARY_OPERATION";
    public static final String ACCEPT_OFFER = "ACCEPT_OFFER";
    public static final String REJECT_OFFER = "REJECT_OFFER";

    public static final double TERRITORY_GOAL_PP = 15.0;
    public static final double PUPPET_GOAL_PP = 30.0;
    public static final double SMO_PP = 20.0;

    private static long aiSerial;

    private DiplomacySystem() { }

    /** Handles the compact client protocol action: ACTION or ACTION:argument. */
    public static boolean handleAction(String actorId, String targetId, String encodedAction, long currentYear) {
        Civilisation actor = DataManager.getCivilisations().get(actorId);
        Civilisation target = DataManager.getCivilisations().get(targetId);
        if (actor == null || target == null || actor == target || !actor.isActive() || !target.isActive()
                || encodedAction == null || encodedAction.isBlank()) return false;

        String action = encodedAction;
        String argument = null;
        int separator = encodedAction.indexOf(':');
        if (separator >= 0) {
            action = encodedAction.substring(0, separator);
            argument = encodedAction.substring(separator + 1);
        }

        return switch (action) {
            case IMPROVE -> improveRelations(actor, target);
            case INSULT -> insult(actor, target);
            case GUARANTEE -> guarantee(actor, target, true);
            case CANCEL_GUARANTEE -> guarantee(actor, target, false);
            case ALLIANCE, DEFENSIVE_PACT, MILITARY_ACCESS, RESEARCH_AGREEMENT,
                    JOIN_FACTION, ROYAL_WEDDING -> propose(actor, target, action);
            case CANCEL_ALLIANCE -> cancelAlliance(actor, target);
            case CANCEL_DEFENSIVE_PACT -> cancelDefensivePact(actor, target);
            case CANCEL_MILITARY_ACCESS -> cancelMilitaryAccess(actor, target);
            case CANCEL_RESEARCH_AGREEMENT -> cancelResearchAgreement(actor, target);
            case LEAVE_FACTION -> leaveFaction(actor);
            case JUSTIFY_TERRITORY -> justifyTerritory(actor, target, argument, currentYear);
            case JUSTIFY_PUPPET -> justifyPuppet(actor, target, currentYear);
            case DECLARE_WAR -> declareJustifiedWar(actor, target);
            case SPECIAL_MILITARY_OPERATION -> launchSpecialMilitaryOperation(actor, target, argument);
            case ACCEPT_OFFER -> acceptOffer(actor, target);
            case REJECT_OFFER -> rejectOffer(actor, target);
            default -> false;
        };
    }

    public static void tick(long currentYear) {
        aiSerial++;
        for (Civilisation civilisation : DataManager.getCivilisations().values()) {
            if (civilisation == null || !civilisation.isActive() || civilisation.isPlayerCreated()) continue;
            civilisation.tickDiplomacyAiCooldown();
            if (civilisation.getDiplomacyAiCooldownSteps() > 0) continue;
            performAiDiplomacy(civilisation, currentYear);
        }
    }

    private static boolean improveRelations(Civilisation actor, Civilisation target) {
        if (WarSystem.getInstance().areAtWar(actor.getId(), target.getId())) return false;
        if (!actor.spendPoliticalPower(10.0)) return false;
        actor.modifyRelation(target.getId(), 10);
        target.modifyRelation(actor.getId(), 5);
        return true;
    }

    private static boolean insult(Civilisation actor, Civilisation target) {
        if (!actor.spendPoliticalPower(2.0)) return false;
        actor.modifyRelation(target.getId(), -10);
        target.modifyRelation(actor.getId(), -20);
        return true;
    }

    private static boolean guarantee(Civilisation actor, Civilisation target, boolean enabled) {
        if (actor.isPuppet()) return false;
        if (enabled && !actor.guarantees(target.getId()) && !actor.spendPoliticalPower(12.0)) return false;
        boolean changed = actor.setGuarantee(target.getId(), enabled);
        if (changed) target.modifyRelation(actor.getId(), enabled ? 8 : -4);
        return changed;
    }

    private static boolean cancelAlliance(Civilisation actor, Civilisation target) {
        boolean a = actor.setAlliance(target.getId(), false);
        boolean b = target.setAlliance(actor.getId(), false);
        if (a || b) {
            actor.modifyRelation(target.getId(), -15);
            target.modifyRelation(actor.getId(), -15);
        }
        return a || b;
    }

    private static boolean cancelDefensivePact(Civilisation actor, Civilisation target) {
        boolean a = actor.setDefensivePact(target.getId(), false);
        boolean b = target.setDefensivePact(actor.getId(), false);
        return a || b;
    }

    private static boolean cancelMilitaryAccess(Civilisation actor, Civilisation target) {
        // Actor is the country that previously requested access; target granted it.
        return target.setMilitaryAccessGrantedTo(actor.getId(), false);
    }

    private static boolean cancelResearchAgreement(Civilisation actor, Civilisation target) {
        boolean a = actor.setResearchAgreement(target.getId(), false);
        boolean b = target.setResearchAgreement(actor.getId(), false);
        return a || b;
    }

    private static boolean leaveFaction(Civilisation actor) {
        if (actor.getFactionId() == null) return false;
        actor.setFactionId(null);
        return true;
    }

    private static boolean propose(Civilisation actor, Civilisation target, String action) {
        if (WarSystem.getInstance().areAtWar(actor.getId(), target.getId())) return false;
        if (!proposalPreconditions(actor, target, action)) return false;

        double cost = proposalCost(action);
        if (cost > 0.0 && !actor.spendPoliticalPower(cost)) return false;

        if (target.isPlayerCreated()) {
            target.setPendingDiplomaticOffer(actor.getId(), action);
            return true;
        }

        if (!aiAccepts(target, actor, action)) {
            actor.modifyRelation(target.getId(), -2);
            return true; // Proposal was valid and delivered, even though the AI declined it.
        }
        return applyAcceptedProposal(actor, target, action);
    }

    private static boolean acceptOffer(Civilisation receiver, Civilisation source) {
        String action = receiver.getPendingDiplomaticOfferFrom(source.getId());
        if (action == null) return false;
        receiver.clearPendingDiplomaticOfferFrom(source.getId());
        if (!proposalPreconditions(source, receiver, action)) return false;
        return applyAcceptedProposal(source, receiver, action);
    }

    private static boolean rejectOffer(Civilisation receiver, Civilisation source) {
        String action = receiver.getPendingDiplomaticOfferFrom(source.getId());
        if (action == null) return false;
        receiver.clearPendingDiplomaticOfferFrom(source.getId());
        receiver.modifyRelation(source.getId(), -3);
        source.modifyRelation(receiver.getId(), -2);
        return true;
    }

    private static boolean proposalPreconditions(Civilisation source, Civilisation target, String action) {
        if (source == null || target == null || source == target || source.isPuppet()) return false;
        return switch (action) {
            case ALLIANCE -> !source.isAlliedWith(target.getId()) && !source.isInFactionWith(target.getId());
            case DEFENSIVE_PACT -> !source.hasDefensivePactWith(target.getId());
            case MILITARY_ACCESS -> !target.grantsMilitaryAccessTo(source.getId());
            case RESEARCH_AGREEMENT -> !source.hasResearchAgreementWith(target.getId());
            case JOIN_FACTION -> !source.isInFactionWith(target.getId());
            case ROYAL_WEDDING -> source.isMonarchyGovernment() && target.isMonarchyGovernment()
                    && !source.hasRoyalMarriageWith(target.getId())
                    && source.getRoyalChildren() > 0 && target.getRoyalChildren() > 0;
            default -> false;
        };
    }

    private static double proposalCost(String action) {
        return switch (action) {
            case ALLIANCE -> 10.0;
            case DEFENSIVE_PACT -> 8.0;
            case MILITARY_ACCESS -> 3.0;
            case RESEARCH_AGREEMENT -> 8.0;
            case JOIN_FACTION -> 15.0;
            case ROYAL_WEDDING -> 12.0;
            default -> 0.0;
        };
    }

    private static boolean applyAcceptedProposal(Civilisation source, Civilisation target, String action) {
        boolean changed;
        switch (action) {
            case ALLIANCE -> {
                changed = source.setAlliance(target.getId(), true) | target.setAlliance(source.getId(), true);
                source.modifyRelation(target.getId(), 18);
                target.modifyRelation(source.getId(), 18);
                return changed;
            }
            case DEFENSIVE_PACT -> {
                changed = source.setDefensivePact(target.getId(), true) | target.setDefensivePact(source.getId(), true);
                source.modifyRelation(target.getId(), 10);
                target.modifyRelation(source.getId(), 10);
                return changed;
            }
            case MILITARY_ACCESS -> {
                changed = target.setMilitaryAccessGrantedTo(source.getId(), true);
                if (changed) target.modifyRelation(source.getId(), 3);
                return changed;
            }
            case RESEARCH_AGREEMENT -> {
                changed = source.setResearchAgreement(target.getId(), true) | target.setResearchAgreement(source.getId(), true);
                source.modifyRelation(target.getId(), 8);
                target.modifyRelation(source.getId(), 8);
                return changed;
            }
            case JOIN_FACTION -> {
                String faction = target.getFactionId();
                if (faction == null) {
                    faction = sanitiseFactionName(target.getName()) + " Pact";
                    target.setFactionId(faction);
                }
                source.setFactionId(faction);
                source.modifyRelation(target.getId(), 15);
                target.modifyRelation(source.getId(), 15);
                return true;
            }
            case ROYAL_WEDDING -> {
                if (!source.consumeRoyalChild() || !target.consumeRoyalChild()) return false;
                source.setRoyalMarriage(target.getId(), true);
                target.setRoyalMarriage(source.getId(), true);
                source.modifyRelation(target.getId(), 35);
                target.modifyRelation(source.getId(), 35);
                source.setStability(source.getStability() + 0.01);
                target.setStability(target.getStability() + 0.01);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static String sanitiseFactionName(String name) {
        if (name == null || name.isBlank()) return "International";
        return name.length() <= 28 ? name : name.substring(0, 28);
    }

    private static boolean justifyTerritory(Civilisation actor, Civilisation target,
                                            String providenceId, long currentYear) {
        if (!canPrepareConflict(actor, target)) return false;
        Providence providence = DataManager.getProvidences().get(providenceId);
        if (providence == null || !providence.isEstablished()
                || !Objects.equals(target.getId(), providence.getOwnerId())) return false;
        if (!actor.spendPoliticalPower(TERRITORY_GOAL_PP)) return false;
        actor.setWarGoal(new DiplomaticWarGoal(target.getId(), DiplomaticWarGoal.TERRITORY,
                providence.getId(), currentYear));
        target.modifyRelation(actor.getId(), -8);
        return true;
    }

    private static boolean justifyPuppet(Civilisation actor, Civilisation target, long currentYear) {
        if (!canPrepareConflict(actor, target)) return false;
        if (!actor.spendPoliticalPower(PUPPET_GOAL_PP)) return false;
        actor.setWarGoal(new DiplomaticWarGoal(target.getId(), DiplomaticWarGoal.PUPPET, null, currentYear));
        target.modifyRelation(actor.getId(), -12);
        return true;
    }

    private static boolean canPrepareConflict(Civilisation actor, Civilisation target) {
        return actor != null && target != null && actor != target && actor.isActive() && target.isActive()
                && !actor.isPuppet() && !actor.isAlliedWith(target.getId())
                && !actor.isInFactionWith(target.getId())
                && !WarSystem.getInstance().areAtWar(actor.getId(), target.getId());
    }

    private static boolean declareJustifiedWar(Civilisation actor, Civilisation target) {
        if (!canPrepareConflict(actor, target)) return false;
        DiplomaticWarGoal goal = actor.getWarGoalAgainst(target.getId());
        if (goal == null) return false;
        boolean declared = WarSystem.getInstance().declareWar(actor.getId(), target.getId(),
                goal.getType(), goal.getTerritoryId(), false);
        if (!declared) return false;
        actor.clearWarGoalAgainst(target.getId());
        actor.modifyRelation(target.getId(), -45);
        target.modifyRelation(actor.getId(), -65);
        expandConventionalWar(actor, target);
        return true;
    }

    private static boolean launchSpecialMilitaryOperation(Civilisation actor, Civilisation target,
                                                          String requestedTerritoryId) {
        if (!canPrepareConflict(actor, target) || !canLaunchSpecialMilitaryOperation(actor, target)) return false;
        Providence objective = chooseSmoObjective(actor, target, requestedTerritoryId);
        if (objective == null) return false;
        if (!actor.spendPoliticalPower(SMO_PP)) return false;

        boolean started = WarSystem.getInstance().declareWar(actor.getId(), target.getId(),
                DiplomaticWarGoal.TERRITORY, objective.getId(), true);
        if (!started) return false;
        actor.modifyRelation(target.getId(), -35);
        target.modifyRelation(actor.getId(), -50);

        boolean alliedIntervention = expandDefenderCoalition(target, actor);
        if (alliedIntervention) WarSystem.getInstance().markEscalated(actor.getId(), target.getId());
        return true;
    }

    public static boolean canLaunchSpecialMilitaryOperation(Civilisation actor, Civilisation target) {
        return actor != null && target != null
                && actor.hasTechnology("special_military_operations")
                && !isCorporatist(actor) && isCorporatist(target)
                && !actor.isPuppet();
    }

    public static boolean isCorporatist(Civilisation civilisation) {
        if (civilisation == null) return false;
        Ideology definition = DataManager.findIdeology(civilisation.getIdeology());
        String id = civilisation.getIdeology() == null ? "" : civilisation.getIdeology().toLowerCase(Locale.ROOT);
        String family = definition == null || definition.getFamilyId() == null
                ? "" : definition.getFamilyId().toLowerCase(Locale.ROOT);
        return id.contains("corporat") || family.contains("corporat");
    }

    /**
     * Limited-operation territorial rule. Resistance is used as the existing save's
     * local-population signal: highly resistant territory is less supportive of its
     * current owner. Bilateral relations provide a smaller affinity component.
     */
    public static boolean isSmoTerritoryEligible(String attackerId, String defenderId, Providence providence) {
        Civilisation attacker = DataManager.getCivilisations().get(attackerId);
        Civilisation defender = DataManager.getCivilisations().get(defenderId);
        if (attacker == null || defender == null || providence == null || !providence.isEstablished()
                || !Objects.equals(defenderId, providence.getOwnerId())) return false;
        double ownerSupport = 100.0 - providence.getResistanceLevel() * 100.0;
        double attackerSupport = 35.0 + providence.getResistanceLevel() * 70.0
                + attacker.getRelation(defenderId) * 0.08;
        if (providence.territoryControlShare(attackerId) > 0.20) attackerSupport += 20.0;
        return attackerSupport > ownerSupport;
    }

    private static Providence chooseSmoObjective(Civilisation attacker, Civilisation target, String requestedId) {
        Providence requested = requestedId == null ? null : DataManager.getProvidences().get(requestedId);
        if (requested != null && isSmoTerritoryEligible(attacker.getId(), target.getId(), requested)) return requested;
        return ProvidenceSystem.ownedProvidences(target.getId()).stream()
                .filter(providence -> isSmoTerritoryEligible(attacker.getId(), target.getId(), providence))
                .sorted(Comparator.comparingDouble(Providence::getResistanceLevel).reversed()
                        .thenComparing(Providence::getDevelopment, Comparator.reverseOrder()))
                .findFirst().orElse(null);
    }

    private static void expandConventionalWar(Civilisation attacker, Civilisation defender) {
        expandDefenderCoalition(defender, attacker);
        for (Civilisation partner : offensivePartnersOf(attacker, defender.getId())) {
            WarSystem.getInstance().declareWar(partner.getId(), defender.getId(), "ALLY_SUPPORT", null, false);
        }
    }

    /** Only full alliances/factions join an offensive war; defensive pacts and guarantees do not. */
    private static Set<Civilisation> offensivePartnersOf(Civilisation attacker, String defenderId) {
        Set<Civilisation> result = new LinkedHashSet<>();
        if (attacker == null) return result;
        for (Civilisation candidate : DataManager.getCivilisations().values()) {
            if (candidate == null || !candidate.isActive() || candidate == attacker
                    || Objects.equals(candidate.getId(), defenderId)) continue;
            if (attacker.isAlliedWith(candidate.getId()) || attacker.isInFactionWith(candidate.getId())) {
                result.add(candidate);
            }
        }
        return result;
    }

    /** Returns true when at least one third country joins against the initiator. */
    private static boolean expandDefenderCoalition(Civilisation defender, Civilisation aggressor) {
        boolean joined = false;
        for (Civilisation partner : defensivePartnersOf(defender, aggressor.getId())) {
            if (WarSystem.getInstance().declareWar(partner.getId(), aggressor.getId(),
                    "DEFEND_PARTNER", null, false)) {
                partner.modifyRelation(aggressor.getId(), -45);
                joined = true;
            }
        }
        return joined;
    }

    private static Set<Civilisation> defensivePartnersOf(Civilisation defended, String aggressorId) {
        Set<Civilisation> result = new LinkedHashSet<>();
        if (defended == null) return result;
        for (Civilisation candidate : DataManager.getCivilisations().values()) {
            if (candidate == null || !candidate.isActive() || candidate == defended
                    || Objects.equals(candidate.getId(), aggressorId)) continue;
            boolean obligated = defended.isAlliedWith(candidate.getId())
                    || defended.hasDefensivePactWith(candidate.getId())
                    || defended.isInFactionWith(candidate.getId())
                    || candidate.guarantees(defended.getId());
            if (obligated) result.add(candidate);
        }
        return result;
    }

    private static boolean aiAccepts(Civilisation receiver, Civilisation proposer, String action) {
        int relation = receiver.getRelation(proposer.getId());
        int friendliness = leaderFriendliness(receiver);
        int aggression = leaderAggression(receiver);
        int sameGovernment = sameIdeologyFamily(receiver, proposer) ? 15 : 0;
        int commonEnemy = WarSystem.getInstance().shareCommonEnemy(receiver.getId(), proposer.getId()) ? 25 : 0;
        int score = relation + friendliness + sameGovernment + commonEnemy - Math.max(0, aggression / 3);
        int threshold = switch (action) {
            case ALLIANCE -> 60;
            case DEFENSIVE_PACT -> 35;
            case MILITARY_ACCESS -> 15;
            case RESEARCH_AGREEMENT -> 25;
            case JOIN_FACTION -> 55;
            case ROYAL_WEDDING -> 45;
            default -> 1000;
        };
        return score >= threshold;
    }

    private static boolean sameIdeologyFamily(Civilisation a, Civilisation b) {
        if (a == null || b == null) return false;
        Ideology ia = DataManager.findIdeology(a.getIdeology());
        Ideology ib = DataManager.findIdeology(b.getIdeology());
        String fa = ia == null ? a.getIdeology() : ia.getFamilyId();
        String fb = ib == null ? b.getIdeology() : ib.getFamilyId();
        return fa != null && fb != null && fa.equalsIgnoreCase(fb);
    }

    public static int leaderAggression(Civilisation civilisation) {
        Leader leader = civilisation == null ? null : DataManager.findLeader(civilisation.getDefaultLeaderId());
        int score = 5 + Math.floorMod(civilisation == null || civilisation.getId() == null
                ? 0 : civilisation.getId().hashCode(), 21) - 10;
        if (leader != null) {
            for (String trait : leader.getTraits()) {
                String t = trait == null ? "" : trait.toLowerCase(Locale.ROOT);
                if (t.contains("warmonger") || t.contains("conqueror")) score += 45;
                if (t.contains("unifier") || t.contains("strategist") || t.contains("militar")) score += 22;
                if (t.contains("aggressive") || t.contains("hawk")) score += 30;
                if (t.contains("diplomat") || t.contains("merciful") || t.contains("peace")) score -= 30;
                if (t.contains("trader") || t.contains("builder") || t.contains("irrigator")) score -= 12;
            }
        }
        String ideology = civilisation == null || civilisation.getIdeology() == null
                ? "" : civilisation.getIdeology().toLowerCase(Locale.ROOT);
        if (ideology.contains("fasc") || ideology.contains("ultranational")) score += 20;
        return Math.max(-50, Math.min(100, score));
    }

    public static int leaderFriendliness(Civilisation civilisation) {
        Leader leader = civilisation == null ? null : DataManager.findLeader(civilisation.getDefaultLeaderId());
        int score = 5;
        if (leader != null) {
            for (String trait : leader.getTraits()) {
                String t = trait == null ? "" : trait.toLowerCase(Locale.ROOT);
                if (t.contains("diplomat") || t.contains("trader") || t.contains("merciful")) score += 30;
                if (t.contains("builder") || t.contains("mariner") || t.contains("planner")) score += 12;
                if (t.contains("warmonger") || t.contains("conqueror")) score -= 30;
            }
        }
        return Math.max(-50, Math.min(80, score));
    }

    public static String personalityDescription(Civilisation civilisation) {
        int aggression = leaderAggression(civilisation);
        int friendliness = leaderFriendliness(civilisation);
        if (aggression >= 55) return "War-minded";
        if (friendliness >= 35) return "Conciliatory";
        if (aggression >= 25) return "Assertive";
        if (friendliness >= 15) return "Co-operative";
        return "Pragmatic";
    }

    private static void performAiDiplomacy(Civilisation ai, long currentYear) {
        List<Civilisation> candidates = new ArrayList<>(DataManager.getCivilisations().values().stream()
                .filter(c -> c != null && c.isActive() && c != ai && !c.isCollapsed())
                .toList());
        if (candidates.isEmpty()) {
            ai.setDiplomacyAiCooldownSteps(90);
            return;
        }
        candidates.sort(Comparator.comparing(Civilisation::getId));
        Civilisation target = candidates.get(Math.floorMod((int) (aiSerial + ai.getId().hashCode()), candidates.size()));
        if (WarSystem.getInstance().areAtWar(ai.getId(), target.getId())) {
            ai.setDiplomacyAiCooldownSteps(45);
            return;
        }

        int relation = ai.getRelation(target.getId());
        int aggression = leaderAggression(ai);
        int friendliness = leaderFriendliness(ai);
        int roll = Math.floorMod((int) (aiSerial * 31L + ai.getId().hashCode() * 17L + target.getId().hashCode()), 100);

        DiplomaticWarGoal goal = ai.getWarGoalAgainst(target.getId());
        if (goal != null && aggression >= 25 && relation < -20 && roll < 55) {
            declareJustifiedWar(ai, target);
            ai.setDiplomacyAiCooldownSteps(80);
            return;
        }

        if (aggression >= 45 && relation < -30 && roll < 40 && canPrepareConflict(ai, target)) {
            if (canLaunchSpecialMilitaryOperation(ai, target)) {
                Providence objective = chooseSmoObjective(ai, target, null);
                if (objective != null && launchSpecialMilitaryOperation(ai, target, objective.getId())) {
                    ai.setDiplomacyAiCooldownSteps(100);
                    return;
                }
            }
            Providence objective = ProvidenceSystem.ownedProvidences(target.getId()).stream()
                    .max(Comparator.comparingDouble(Providence::getDevelopment)).orElse(null);
            if (objective != null) {
                if (aggression >= 70 && ai.getPoliticalPower() >= PUPPET_GOAL_PP) justifyPuppet(ai, target, currentYear);
                else justifyTerritory(ai, target, objective.getId(), currentYear);
                ai.setDiplomacyAiCooldownSteps(35);
                return;
            }
        }

        if (ai.isMonarchyGovernment() && target.isMonarchyGovernment()
                && relation >= 45 && !ai.hasRoyalMarriageWith(target.getId()) && roll < 18) {
            propose(ai, target, ROYAL_WEDDING);
        } else if (relation >= 65 && friendliness >= 15 && !ai.isInFactionWith(target.getId()) && roll < 22) {
            propose(ai, target, JOIN_FACTION);
        } else if (relation >= 55 && friendliness >= 15 && !ai.isAlliedWith(target.getId()) && roll < 30) {
            propose(ai, target, ALLIANCE);
        } else if (relation >= 40 && friendliness >= 25 && !ai.guarantees(target.getId()) && roll < 28) {
            guarantee(ai, target, true);
        } else if (relation >= 35 && !ai.hasResearchAgreementWith(target.getId()) && roll < 45) {
            propose(ai, target, RESEARCH_AGREEMENT);
        } else if (relation >= 25 && !ai.hasDefensivePactWith(target.getId()) && roll < 52) {
            propose(ai, target, DEFENSIVE_PACT);
        } else if (WarSystem.getInstance().shareCommonEnemy(ai.getId(), target.getId())
                && !target.grantsMilitaryAccessTo(ai.getId()) && roll < 60) {
            propose(ai, target, MILITARY_ACCESS);
        } else if (relation < -45 && aggression > friendliness && roll < 35) {
            insult(ai, target);
        } else if (relation < 60 && friendliness >= aggression / 3 && roll < 60) {
            improveRelations(ai, target);
        }
        ai.setDiplomacyAiCooldownSteps(55 + Math.floorMod(ai.getId().hashCode() + (int) aiSerial, 70));
    }

    public static String governmentCountryName(Civilisation civilisation) {
        if (civilisation == null) return "Unknown country";
        Ideology ideology = DataManager.findIdeology(civilisation.getIdeology());
        String family = ideology == null || ideology.getFamilyId() == null
                ? "" : ideology.getFamilyId().toLowerCase(Locale.ROOT);
        String id = civilisation.getIdeology() == null ? "" : civilisation.getIdeology().toLowerCase(Locale.ROOT);
        String name = civilisation.getName();
        if (family.contains("democrat") || family.contains("republic") || id.contains("republic")) {
            return "Republic of " + name;
        }
        if (family.contains("socialist") || id.contains("commun")) return "People's Republic of " + name;
        if (family.contains("monarch") || id.contains("monarch") || id.contains("palatial")) return "Kingdom of " + name;
        if (family.contains("theocrat") || id.contains("theocra") || id.contains("sacred")) return "Holy State of " + name;
        if (family.contains("corporat") || id.contains("corporat")) return "Corporate State of " + name;
        if (family.contains("ultranational") || id.contains("fasc")) return "State of " + name;
        return name;
    }
}
