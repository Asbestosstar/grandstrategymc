package com.asbestosstar.grandstrategy.common.engine;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.DataManager;
import com.asbestosstar.grandstrategy.common.data.Providence;
import com.asbestosstar.grandstrategy.common.world.PhysicalVillagerSystem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Server-authoritative warfare, occupation and negotiated peace.
 *
 * Wars never end merely because a timer reaches 100%. Physical combat and city
 * occupation change the war score. Normally a war ends after both belligerents
 * accept the same peace proposal; total defeat (zero population or loss of every
 * controlled command post) instead causes immediate capitulation. Historical/AI
 * countries use the same proposal/acceptance path as player countries when the war
 * has not already ended through total defeat.
 */
public final class WarSystem {
    public static final String PEACE_WHITE = "WHITE_PEACE";
    public static final String PEACE_TERRITORY = "TERRITORY";
    public static final String PEACE_PUPPET = "PUPPET";
    public static final String PEACE_TERRITORY_AND_PUPPET = "TERRITORY_AND_PUPPET";

    public static final double TERRITORY_SCORE_REQUIRED = 25.0;
    public static final double PUPPET_SCORE_REQUIRED = 65.0;
    public static final double COMBINATION_SCORE_REQUIRED = 85.0;

    private static final int AI_NEGOTIATION_MIN_TICKS = 240;
    private static final int AI_NEGOTIATION_INTERVAL_TICKS = 120;
    private static final double CITY_OCCUPATION_SCORE = 18.0;
    private static final double CITY_LIBERATION_SCORE = 12.0;

    private static final WarSystem INSTANCE = new WarSystem();
    private final List<War> activeWars = new ArrayList<>();

    private WarSystem() {
    }

    public static WarSystem getInstance() {
        return INSTANCE;
    }

    public synchronized boolean declareWar(String attackerId, String defenderId) {
        if (attackerId == null || defenderId == null || attackerId.equals(defenderId)) return false;
        Civilisation attacker = DataManager.getCivilisations().get(attackerId);
        Civilisation defender = DataManager.getCivilisations().get(defenderId);
        if (attacker == null || defender == null || !attacker.isActive() || !defender.isActive()) return false;
        if (attacker.isPuppet()) return false;
        if (activeWars.stream().anyMatch(war -> war.matchesEitherWay(attackerId, defenderId))) return false;

        War war = new War(attackerId, defenderId);
        war.targetProvidenceId = chooseObjective(defenderId);
        activeWars.add(war);
        attacker.modifyRelation(defenderId, -60);
        defender.modifyRelation(attackerId, -60);
        System.out.println("War declared between " + attackerId + " and " + defenderId
                + ". It continues until peace is accepted or one side suffers total defeat.");
        return true;
    }

    /** Called once per strategy step. No automatic war-progress or territorial transfer occurs here. */
    public synchronized void tick() {
        // Migration/failsafe for saves made before v6.24: an already-established
        // country which is at war and has lost every command post must not be
        // granted a replacement neutral city by the settlement system.
        resolveCommandPostDefeats();
        for (War war : activeWars) {
            if (war.over) continue;
            war.ageTicks++;
            maintainObjective(war);
            processPeace(war);
            if (!war.over && war.pendingPeace == null) maybeOpenAiNegotiation(war);
            if (!war.over) processPeace(war);
        }
        activeWars.removeIf(War::isOver);
    }

    public synchronized void reset() {
        activeWars.clear();
    }


    /**
     * Hard end-state for a civilisation whose physical population reaches zero.
     *
     * If the country is at war it automatically capitulates. The enemy with the
     * strongest current battlefield position becomes the capitulation victor and
     * receives the defeated country's remaining established territory. All wars
     * involving the defeated country then end immediately because there is no
     * population left to continue fighting or negotiate a treaty.
     *
     * If the country is not at war, it is destroyed and its remaining territory
     * becomes unowned rather than leaving ghost borders on the strategy map.
     */
    public synchronized boolean resolveZeroPopulation(Civilisation civilisation) {
        if (civilisation == null || !civilisation.isActive() || civilisation.getPopulation() > 0) {
            return false;
        }

        War capitulationWar = null;
        String victorId = null;
        double bestEnemyAdvantage = Double.NEGATIVE_INFINITY;

        for (War war : activeWars) {
            if (war.over || !war.involves(civilisation.getId())) continue;
            String enemyId = war.opponentOf(civilisation.getId());
            Civilisation enemy = DataManager.getCivilisations().get(enemyId);
            if (enemy == null || !enemy.isActive()) continue;

            // Positive means the enemy was already winning. If several countries
            // are fighting the doomed civilisation, the strongest battlefield
            // claimant receives the capitulation. Ties are deterministic.
            double enemyAdvantage = -war.scoreFor(civilisation.getId());
            if (capitulationWar == null
                    || enemyAdvantage > bestEnemyAdvantage + 1.0e-9
                    || (Math.abs(enemyAdvantage - bestEnemyAdvantage) <= 1.0e-9
                        && enemyId.compareTo(victorId) < 0)) {
                capitulationWar = war;
                victorId = enemyId;
                bestEnemyAdvantage = enemyAdvantage;
            }
        }

        if (victorId != null) {
            for (Providence providence : DataManager.getProvidences().values()) {
                if (providence == null || !providence.isEstablished()) continue;
                providence.transferTerritoryClaims(civilisation.getId(), victorId);
                if (Objects.equals(civilisation.getId(), providence.getOwnerId())) {
                    transferProvidence(providence, victorId);
                }
            }

            for (War war : activeWars) {
                if (war.involves(civilisation.getId())) {
                    war.over = true;
                    war.pendingPeace = null;
                }
            }

            Civilisation victor = DataManager.getCivilisations().get(victorId);
            if (victor != null) victor.modifyRelation(civilisation.getId(), -30);
            civilisation.modifyRelation(victorId, -100);

            if (civilisation.collapse()) {
                System.out.println("Civilisation " + civilisation.getName()
                        + " reached zero population and automatically capitulated to "
                        + (victor == null ? victorId : victor.getName()) + ".");
            }
        } else {
            for (Providence providence : DataManager.getProvidences().values()) {
                if (providence == null || !providence.isEstablished()) continue;
                providence.clearTerritoryClaims(civilisation.getId());
                if (Objects.equals(civilisation.getId(), providence.getOwnerId())) {
                    providence.setOwnerId(null);
                    if (providence.getCity() != null) {
                        providence.getCity().setControllerId(null);
                        providence.getCity().setNationalCapital(false);
                        providence.getCity().setSupplyCapital(false);
                    }
                }
            }

            if (civilisation.collapse()) {
                System.out.println("Civilisation " + civilisation.getName()
                        + " reached zero population and was destroyed.");
            }
        }

        activeWars.removeIf(War::isOver);
        return true;
    }

    /** Physical soldiers use this to distinguish wartime enemies from neutral villagers. */
    public synchronized boolean areAtWar(String firstCivilisationId, String secondCivilisationId) {
        return findWar(firstCivilisationId, secondCivilisationId) != null;
    }

    /**
     * Failsafe scan for countries which were already left cityless by an older save
     * or by an unusual command-post transition. Only established countries currently
     * involved in a war are eligible; a newly activated civilisation still waiting
     * for its first discovered-land settlement is deliberately ignored.
     */
    private void resolveCommandPostDefeats() {
        List<String> candidates = DataManager.getCivilisations().values().stream()
                .filter(Objects::nonNull)
                .filter(Civilisation::isActive)
                .filter(Civilisation::hasEstablishedHomeland)
                .filter(civilisation -> !hasControlledCommandPost(civilisation.getId()))
                .filter(civilisation -> activeWars.stream()
                        .anyMatch(war -> !war.over && war.involves(civilisation.getId())))
                .map(Civilisation::getId)
                .toList();
        for (String civilisationId : candidates) {
            resolveCommandPostDefeat(civilisationId, null);
        }
    }

    /**
     * Capitulates a country after its last controlled city command post is gone.
     * preferredVictorId is used for the soldier that physically captured the final
     * beacon; migration/failsafe calls choose the strongest current enemy instead.
     */
    private boolean resolveCommandPostDefeat(String defeatedId, String preferredVictorId) {
        if (defeatedId == null || defeatedId.isBlank()) return false;
        Civilisation defeated = DataManager.getCivilisations().get(defeatedId);
        if (defeated == null || !defeated.isActive() || !defeated.hasEstablishedHomeland()) return false;
        if (hasControlledCommandPost(defeatedId)) return false;

        String victorId = null;
        if (preferredVictorId != null && !preferredVictorId.isBlank()) {
            Civilisation preferred = DataManager.getCivilisations().get(preferredVictorId);
            if (preferred != null && preferred.isActive()
                    && findWar(defeatedId, preferredVictorId) != null) {
                victorId = preferredVictorId;
            }
        }

        // For an old/broken save with no direct final-capture event, choose the
        // enemy with the strongest battlefield advantage, matching zero-population
        // capitulation semantics.
        if (victorId == null) {
            double bestEnemyAdvantage = Double.NEGATIVE_INFINITY;
            for (War candidate : activeWars) {
                if (candidate.over || !candidate.involves(defeatedId)) continue;
                String enemyId = candidate.opponentOf(defeatedId);
                Civilisation enemy = DataManager.getCivilisations().get(enemyId);
                if (enemy == null || !enemy.isActive()) continue;
                double enemyAdvantage = -candidate.scoreFor(defeatedId);
                if (victorId == null
                        || enemyAdvantage > bestEnemyAdvantage + 1.0e-9
                        || (Math.abs(enemyAdvantage - bestEnemyAdvantage) <= 1.0e-9
                            && enemyId.compareTo(victorId) < 0)) {
                    victorId = enemyId;
                    bestEnemyAdvantage = enemyAdvantage;
                }
            }
        }
        if (victorId == null) return false;

        // Total wartime capitulation is final rather than temporary occupation.
        // Transfer the defeated country's remaining legal chunk claims and any
        // malformed/legacy province ownership to the capitulation victor.
        for (Providence providence : DataManager.getProvidences().values()) {
            if (providence == null || !providence.isEstablished()) continue;
            providence.transferTerritoryClaims(defeatedId, victorId);
            if (Objects.equals(defeatedId, providence.getOwnerId())) {
                transferProvidence(providence, victorId);
            }
        }

        for (War candidate : activeWars) {
            if (candidate.involves(defeatedId)) {
                candidate.over = true;
                candidate.pendingPeace = null;
            }
        }

        Civilisation victor = DataManager.getCivilisations().get(victorId);
        if (defeated.getPopulation() > 0) {
            defeated.removePopulation(defeated.getPopulation());
        }
        defeated.modifyRelation(victorId, -100);
        if (victor != null) victor.modifyRelation(defeatedId, -30);
        boolean collapsed = defeated.collapse();

        activeWars.removeIf(War::isOver);
        StrategyEngine.getInstance().requestSave();
        if (collapsed) {
            System.out.println("Civilisation " + defeated.getName()
                    + " lost its final command post and capitulated to "
                    + (victor == null ? victorId : victor.getName()) + ".");
        }
        return collapsed;
    }

    private boolean hasControlledCommandPost(String civilisationId) {
        if (civilisationId == null || civilisationId.isBlank()) return false;
        for (Providence providence : DataManager.getProvidences().values()) {
            if (providence == null || !providence.isEstablished() || providence.getCity() == null) continue;
            if (Objects.equals(civilisationId, providence.getCity().getControllerId())) return true;
        }
        return false;
    }

    /**
     * Adds war score for a physical casualty. Positive score favours the original
     * attacker; negative score favours the defender. Soldiers count more than
     * civilian workers because destroying an army matters more to a peace conference.
     */
    public synchronized void reportCasualty(String killerCivilisationId, String victimCivilisationId,
                                             boolean victimWasSoldier) {
        War war = findWar(killerCivilisationId, victimCivilisationId);
        if (war == null) return;
        double value = victimWasSoldier ? 2.5 : 0.75;
        if (Objects.equals(killerCivilisationId, war.attackerId)) {
            war.attackerCasualtyScore += value;
        } else {
            war.defenderCasualtyScore += value;
        }
        war.recalculateScore();
    }

    /**
     * A physical soldier crossing a wartime border changes the current jurisdiction
     * of that Minecraft chunk. Legal ownership is kept separately by Providence, so
     * walking across a border is occupation rather than permanent annexation. If the
     * opposing side later walks a soldier back onto the chunk, jurisdiction flips back.
     */
    public synchronized void reportArmyPosition(String civilisationId, double blockX, double blockZ) {
        if (civilisationId == null || civilisationId.isBlank()) return;
        int x = (int) Math.floor(blockX);
        int z = (int) Math.floor(blockZ);
        Providence providence = ProvidenceSystem.providenceContainingBlock(x, z);
        if (providence == null || !providence.isEstablished()) return;

        int chunkX = Math.floorDiv(x, 16);
        int chunkZ = Math.floorDiv(z, 16);
        long chunkKey = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);

        // A city chunk is not captured merely because troops have entered it. The
        // attacker must first take the physical beacon command post. This keeps the
        // city's visible territorial jurisdiction tied to the military objective
        // rather than letting a soldier colour the city as occupied while the enemy
        // still controls its command post.
        if (providence.getCity() != null) {
            int cityChunkX = Math.floorDiv(providence.getCity().getBlockX(), 16);
            int cityChunkZ = Math.floorDiv(providence.getCity().getBlockZ(), 16);
            if (chunkX == cityChunkX && chunkZ == cityChunkZ
                    && !Objects.equals(civilisationId, providence.getCity().getControllerId())) {
                return;
            }
        }

        String currentController = providence.getTerritoryController(chunkKey);
        if (currentController == null || currentController.isBlank()
                || Objects.equals(currentController, civilisationId)) return;

        War war = findWar(civilisationId, currentController);
        if (war == null) return;

        if (providence.setWartimeTerritoryController(chunkKey, civilisationId)) {
            StrategyEngine.getInstance().requestSave();
        }
    }

    /** Adds occupation score only after a real command-post beacon was broken. */
    public synchronized void reportCommandPostCapture(String capturerId, String previousControllerId,
                                                       String providenceId) {
        War war = findWar(capturerId, previousControllerId);
        if (war == null || providenceId == null) return;
        Set<String> occupied = Objects.equals(capturerId, war.attackerId)
                ? war.attackerOccupiedProvidences : war.defenderOccupiedProvidences;
        Set<String> enemyOccupied = Objects.equals(capturerId, war.attackerId)
                ? war.defenderOccupiedProvidences : war.attackerOccupiedProvidences;
        enemyOccupied.remove(providenceId);
        Providence providence = DataManager.getProvidences().get(providenceId);
        if (providence != null && providence.getCity() != null) {
            int cityChunkX = Math.floorDiv(providence.getCity().getBlockX(), 16);
            int cityChunkZ = Math.floorDiv(providence.getCity().getBlockZ(), 16);
            long cityChunkKey = ((long) cityChunkX << 32) ^ (cityChunkZ & 0xffffffffL);
            // Capturing the beacon immediately captures the city chunk's temporary
            // wartime jurisdiction as well. Permanent legal ownership is unchanged
            // until a peace treaty cedes the territory.
            providence.setWartimeTerritoryController(cityChunkKey, capturerId);
        }

        if (occupied.add(providenceId)) {
            if (Objects.equals(capturerId, war.attackerId)) war.attackerOccupationScore += CITY_OCCUPATION_SCORE;
            else war.defenderOccupationScore += CITY_OCCUPATION_SCORE;
            war.targetProvidenceId = providenceId;
            war.recalculateScore();
            StrategyEngine.getInstance().requestSave();
            System.out.println(capturerId + " broke and captured command post " + providenceId
                    + "; war score is now " + (int) Math.round(war.warScore) + ".");
        }

        // The final city is a hard defeat condition. A country that has already
        // established itself may not conjure a new homeland after its last command
        // post falls. The army which physically captures the final beacon receives
        // the capitulation unless the state was already gone.
        resolveCommandPostDefeat(previousControllerId, capturerId);
    }

    /** True when both countries are currently fighting at least one common enemy. */
    public synchronized boolean shareCommonEnemy(String firstId, String secondId) {
        if (firstId == null || secondId == null || firstId.equals(secondId)) return false;
        Set<String> firstEnemies = new LinkedHashSet<>();
        Set<String> secondEnemies = new LinkedHashSet<>();
        for (War war : activeWars) {
            if (war.over) continue;
            if (war.involves(firstId)) firstEnemies.add(war.opponentOf(firstId));
            if (war.involves(secondId)) secondEnemies.add(war.opponentOf(secondId));
        }
        firstEnemies.retainAll(secondEnemies);
        firstEnemies.remove(null);
        return !firstEnemies.isEmpty();
    }

    /** Proposes a treaty. The proposer is considered to have accepted its own proposal. */
    public synchronized boolean proposePeace(String proposerId, String opponentId, String encodedTerms) {
        War war = findWar(proposerId, opponentId);
        if (war == null || encodedTerms == null) return false;

        String type = encodedTerms;
        String requestedTerritory = null;
        int separator = encodedTerms.indexOf(':');
        if (separator >= 0) {
            type = encodedTerms.substring(0, separator);
            requestedTerritory = encodedTerms.substring(separator + 1);
        }
        if (!validPeaceType(type)) return false;

        String winnerId = war.winnerId();
        String loserId = war.loserId();
        double advantage = Math.abs(war.warScore);
        if (!PEACE_WHITE.equals(type)) {
            if (winnerId == null || loserId == null || !termUnlocked(type, advantage)) return false;
        }

        String territoryId = null;
        if (PEACE_TERRITORY.equals(type) || PEACE_TERRITORY_AND_PUPPET.equals(type)) {
            territoryId = validCessionTerritory(loserId, requestedTerritory)
                    ? requestedTerritory : recommendedTerritory(war);
            if (territoryId == null) return false;
        }

        PeaceProposal proposal = new PeaceProposal();
        proposal.type = type;
        proposal.proposedBy = proposerId;
        proposal.winnerId = winnerId;
        proposal.loserId = loserId;
        proposal.territoryId = territoryId;
        proposal.acceptedByAttacker = Objects.equals(proposerId, war.attackerId);
        proposal.acceptedByDefender = Objects.equals(proposerId, war.defenderId);
        war.pendingPeace = proposal;
        return true;
    }

    public synchronized boolean acceptPeace(String civilisationId, String opponentId) {
        War war = findWar(civilisationId, opponentId);
        if (war == null || war.pendingPeace == null) return false;
        if (Objects.equals(civilisationId, war.attackerId)) war.pendingPeace.acceptedByAttacker = true;
        else if (Objects.equals(civilisationId, war.defenderId)) war.pendingPeace.acceptedByDefender = true;
        else return false;
        processPeace(war);
        return true;
    }

    public synchronized boolean rejectPeace(String civilisationId, String opponentId) {
        War war = findWar(civilisationId, opponentId);
        if (war == null || war.pendingPeace == null || !war.involves(civilisationId)) return false;
        war.pendingPeace = null;
        return true;
    }

    public synchronized List<WarState> snapshot() {
        return activeWars.stream().filter(war -> !war.over).map(this::snapshotOf).toList();
    }

    public synchronized void restore(List<WarState> states) {
        activeWars.clear();
        if (states == null) return;

        for (WarState state : states) {
            if (state == null || state.attackerId == null || state.defenderId == null) continue;
            if (!DataManager.getCivilisations().containsKey(state.attackerId)
                    || !DataManager.getCivilisations().containsKey(state.defenderId)) continue;
            War war = new War(state.attackerId, state.defenderId);
            // V1-v5 saves stored a 0..1 auto-progress field. Preserve it only as a
            // modest starting score; never resume the old timer-driven capture logic.
            war.warScore = state.warScore;
            if (Math.abs(war.warScore) < 0.0001 && state.attackerProgress > 0.0) {
                war.warScore = clamp(state.attackerProgress * 35.0, -100.0, 100.0);
            }
            war.attackerCasualtyScore = Math.max(0.0, state.attackerCasualtyScore);
            war.defenderCasualtyScore = Math.max(0.0, state.defenderCasualtyScore);
            war.attackerOccupationScore = Math.max(0.0, state.attackerOccupationScore);
            war.defenderOccupationScore = Math.max(0.0, state.defenderOccupationScore);
            war.targetProvidenceId = state.targetProvidenceId;
            war.ageTicks = Math.max(0, state.ageTicks);
            if (state.attackerOccupiedProvidences != null) {
                war.attackerOccupiedProvidences.addAll(state.attackerOccupiedProvidences);
            }
            if (state.defenderOccupiedProvidences != null) {
                war.defenderOccupiedProvidences.addAll(state.defenderOccupiedProvidences);
            }
            war.pendingPeace = copyProposal(state.pendingPeace);
            war.recalculateScorePreservingLegacy(state.warScore);
            activeWars.add(war);
        }
    }

    private WarState snapshotOf(War war) {
        WarState state = new WarState();
        state.attackerId = war.attackerId;
        state.defenderId = war.defenderId;
        state.warScore = war.warScore;
        // Retained for backwards compatibility with older save readers.
        state.attackerProgress = clamp((war.warScore + 100.0) / 200.0, 0.0, 1.0);
        state.targetProvidenceId = war.targetProvidenceId;
        state.attackerCasualtyScore = war.attackerCasualtyScore;
        state.defenderCasualtyScore = war.defenderCasualtyScore;
        state.attackerOccupationScore = war.attackerOccupationScore;
        state.defenderOccupationScore = war.defenderOccupationScore;
        state.attackerOccupiedProvidences = new ArrayList<>(war.attackerOccupiedProvidences);
        state.defenderOccupiedProvidences = new ArrayList<>(war.defenderOccupiedProvidences);
        state.ageTicks = war.ageTicks;
        state.pendingPeace = copyProposal(war.pendingPeace);
        state.recommendedTerritoryId = recommendedTerritory(war);
        return state;
    }

    private void processPeace(War war) {
        if (war.pendingPeace == null || war.over) return;
        autoAcceptIfAi(war, war.attackerId);
        autoAcceptIfAi(war, war.defenderId);
        PeaceProposal proposal = war.pendingPeace;
        if (proposal != null && proposal.acceptedByAttacker && proposal.acceptedByDefender) {
            applyPeace(war, proposal);
        }
    }

    private void autoAcceptIfAi(War war, String civilisationId) {
        PeaceProposal proposal = war.pendingPeace;
        if (proposal == null) return;
        boolean alreadyAccepted = Objects.equals(civilisationId, war.attackerId)
                ? proposal.acceptedByAttacker : proposal.acceptedByDefender;
        if (alreadyAccepted) return;
        Civilisation civilisation = DataManager.getCivilisations().get(civilisationId);
        if (civilisation == null || civilisation.isPlayerCreated()) return;

        boolean accept;
        if (PEACE_WHITE.equals(proposal.type)) {
            // An AI accepts white peace when it is not decisively winning.
            accept = war.scoreFor(civilisationId) <= 12.0 || war.ageTicks >= 900;
        } else {
            double required = requiredScore(proposal.type);
            // If this AI is the winner and receives its gains, acceptance is obvious.
            // If it is the loser, it concedes only when the battlefield score justifies it.
            accept = Objects.equals(civilisationId, proposal.winnerId)
                    || (Objects.equals(civilisationId, proposal.loserId)
                        && -war.scoreFor(civilisationId) >= required);
        }
        if (accept) {
            if (Objects.equals(civilisationId, war.attackerId)) proposal.acceptedByAttacker = true;
            else proposal.acceptedByDefender = true;
        }
    }

    private void maybeOpenAiNegotiation(War war) {
        if (war.ageTicks < AI_NEGOTIATION_MIN_TICKS
                || war.ageTicks % AI_NEGOTIATION_INTERVAL_TICKS != 0) return;
        Civilisation attacker = DataManager.getCivilisations().get(war.attackerId);
        Civilisation defender = DataManager.getCivilisations().get(war.defenderId);
        if (attacker == null || defender == null) return;

        double advantage = Math.abs(war.warScore);
        String type;
        if (advantage >= COMBINATION_SCORE_REQUIRED) type = PEACE_TERRITORY_AND_PUPPET;
        else if (advantage >= PUPPET_SCORE_REQUIRED) type = PEACE_PUPPET;
        else if (advantage >= TERRITORY_SCORE_REQUIRED) type = PEACE_TERRITORY;
        else if (war.ageTicks >= 600 && advantage <= 12.0) type = PEACE_WHITE;
        else return;

        String winner = war.winnerId();
        String loser = war.loserId();
        String proposer;
        if (PEACE_WHITE.equals(type)) {
            proposer = !attacker.isPlayerCreated() ? attacker.getId()
                    : (!defender.isPlayerCreated() ? defender.getId() : null);
        } else if (winner != null && loser != null) {
            Civilisation winnerCiv = DataManager.getCivilisations().get(winner);
            Civilisation loserCiv = DataManager.getCivilisations().get(loser);
            // Prefer the losing AI offering terms; otherwise the winning AI demands them.
            proposer = loserCiv != null && !loserCiv.isPlayerCreated() ? loser
                    : (winnerCiv != null && !winnerCiv.isPlayerCreated() ? winner : null);
        } else {
            proposer = null;
        }
        if (proposer == null) return;
        String opponent = war.opponentOf(proposer);
        String territory = recommendedTerritory(war);
        String encoded = type;
        if ((PEACE_TERRITORY.equals(type) || PEACE_TERRITORY_AND_PUPPET.equals(type))
                && territory != null) encoded += ":" + territory;
        proposePeace(proposer, opponent, encoded);
    }

    private void applyPeace(War war, PeaceProposal proposal) {
        String summary = proposal.type;
        if (PEACE_TERRITORY.equals(proposal.type)
                || PEACE_TERRITORY_AND_PUPPET.equals(proposal.type)) {
            Providence ceded = DataManager.getProvidences().get(proposal.territoryId);
            if (ceded != null && Objects.equals(ceded.getOwnerId(), proposal.loserId)) {
                // The treaty makes the loser's legal chunk ownership in the ceded
                // providence permanent for the winner. Other countries' claims remain.
                ceded.transferTerritoryClaims(proposal.loserId, proposal.winnerId);
                transferProvidence(ceded, proposal.winnerId);
                summary += " territory=" + ceded.getId();
            }
        }
        if (PEACE_PUPPET.equals(proposal.type)
                || PEACE_TERRITORY_AND_PUPPET.equals(proposal.type)) {
            Civilisation loser = DataManager.getCivilisations().get(proposal.loserId);
            if (loser != null) loser.setOverlordCivilisationId(proposal.winnerId);
            summary += " puppet=" + proposal.loserId + "->" + proposal.winnerId;
        }

        // Every non-ceded chunk occupied by these two belligerents returns to its
        // permanent legal owner. A ceded chunk already had its legal ownership
        // transferred above, so restoring jurisdiction keeps it with the treaty winner.
        for (Providence providence : DataManager.getProvidences().values()) {
            if (providence == null || !providence.isEstablished()) continue;
            providence.restoreTerritoryJurisdictionBetween(war.attackerId, war.defenderId);

            // Command-post occupation remains province-level and is restored through
            // the existing legal province owner unless the treaty transferred it.
            String owner = providence.getOwnerId();
            if (!Objects.equals(owner, war.attackerId) && !Objects.equals(owner, war.defenderId)) continue;
            providence.setCountrysideControllerId(owner);
            if (providence.getCity() != null) providence.getCity().setControllerId(owner);
        }

        Civilisation attacker = DataManager.getCivilisations().get(war.attackerId);
        Civilisation defender = DataManager.getCivilisations().get(war.defenderId);
        if (attacker != null) attacker.modifyRelation(war.defenderId, -20);
        if (defender != null) defender.modifyRelation(war.attackerId, -20);
        war.over = true;
        war.pendingPeace = null;
        System.out.println("Peace treaty accepted by both " + war.attackerId + " and "
                + war.defenderId + ": " + summary + ".");
    }

    private static void transferProvidence(Providence providence, String newOwnerId) {
        // Peace/capitulation transfers the command post. Third-party and local chunk
        // claims remain exactly where they were unless the defeated country itself
        // ceased to exist and its claims were explicitly transferred above.
        String previousOwnerId = providence.getOwnerId();

        // Population belongs to the land as well as the flag. Because the existing
        // save format stores population nationally rather than per-providence, divide
        // the old owner's remaining population across its remaining legally-owned
        // providences at the moment each one is ceded. Sequential capitulation then
        // transfers the entire surviving population by the final providence.
        if (previousOwnerId != null && newOwnerId != null
                && !Objects.equals(previousOwnerId, newOwnerId)) {
            Civilisation previous = DataManager.getCivilisations().get(previousOwnerId);
            Civilisation next = DataManager.getCivilisations().get(newOwnerId);
            if (previous != null && next != null && previous.getPopulation() > 0) {
                int remainingProvidences = Math.max(1, ProvidenceSystem.ownedProvidences(previousOwnerId).size());
                int populationToTransfer = remainingProvidences <= 1
                        ? previous.getPopulation()
                        : previous.getPopulation() / remainingProvidences;
                populationToTransfer = Math.min(populationToTransfer, previous.getPopulation());
                if (populationToTransfer > 0) {
                    previous.removePopulation(populationToTransfer);
                    next.addPopulationRandomised(populationToTransfer);
                    String cityId = providence.getCity() == null ? null : providence.getCity().getId();
                    int physical = PhysicalVillagerSystem.getInstance().transferResidentsForProvidence(
                            previousOwnerId, newOwnerId, cityId, populationToTransfer);
                    PhysicalVillagerSystem.getInstance().requestImmediateReconcile();
                    System.out.println("Providence " + providence.getName() + " transferred "
                            + populationToTransfer + " population from " + previousOwnerId + " to "
                            + newOwnerId + " (" + physical + " already materialised residents changed allegiance).");
                }
            }
        }

        providence.setOwnerId(newOwnerId);
        if (providence.getCity() != null) providence.getCity().setControllerId(newOwnerId);
        providence.setResistanceLevel(Math.max(providence.getResistanceLevel(), 0.45));
    }

    private String recommendedTerritory(War war) {
        String loser = war.loserId();
        String winner = war.winnerId();
        if (loser == null || winner == null) return null;
        Set<String> winnerOccupations = Objects.equals(winner, war.attackerId)
                ? war.attackerOccupiedProvidences : war.defenderOccupiedProvidences;
        return DataManager.getProvidences().values().stream()
                .filter(Providence::isEstablished)
                .filter(providence -> Objects.equals(loser, providence.getOwnerId()))
                .sorted(Comparator
                        .comparing((Providence p) -> winnerOccupations.contains(p.getId())
                                || p.hasWartimeOccupationBy(winner)).reversed()
                        .thenComparing(Providence::getDevelopment, Comparator.reverseOrder())
                        .thenComparing(Providence::getId))
                .map(Providence::getId)
                .findFirst().orElse(null);
    }

    private static boolean validCessionTerritory(String loserId, String providenceId) {
        if (loserId == null || providenceId == null || providenceId.isBlank()) return false;
        Providence providence = DataManager.getProvidences().get(providenceId);
        return providence != null && providence.isEstablished()
                && Objects.equals(loserId, providence.getOwnerId());
    }

    private void maintainObjective(War war) {
        Providence target = war.targetProvidenceId == null ? null
                : DataManager.getProvidences().get(war.targetProvidenceId);
        String expectedOwner = war.warScore >= 0.0 ? war.defenderId : war.attackerId;
        if (target == null || !target.isEstablished() || !Objects.equals(target.getOwnerId(), expectedOwner)) {
            war.targetProvidenceId = chooseObjective(expectedOwner);
        }
    }

    private static String chooseObjective(String defenderId) {
        return DataManager.getProvidences().values().stream()
                .filter(Providence::isEstablished)
                .filter(providence -> Objects.equals(defenderId, providence.getOwnerId()))
                .sorted(Comparator
                        .comparing((Providence p) -> p.getCity() != null && p.getCity().isSupplyCapital())
                        .reversed()
                        .thenComparing(Providence::getDevelopment, Comparator.reverseOrder())
                        .thenComparing(Providence::getId))
                .map(Providence::getId)
                .findFirst().orElse(null);
    }

    private War findWar(String firstCivilisationId, String secondCivilisationId) {
        if (firstCivilisationId == null || secondCivilisationId == null
                || firstCivilisationId.equals(secondCivilisationId)) return null;
        for (War war : activeWars) {
            if (!war.over && war.matchesEitherWay(firstCivilisationId, secondCivilisationId)) return war;
        }
        return null;
    }

    private static boolean validPeaceType(String type) {
        return PEACE_WHITE.equals(type) || PEACE_TERRITORY.equals(type)
                || PEACE_PUPPET.equals(type) || PEACE_TERRITORY_AND_PUPPET.equals(type);
    }

    private static boolean termUnlocked(String type, double advantage) {
        return advantage + 1.0e-9 >= requiredScore(type);
    }

    private static double requiredScore(String type) {
        if (PEACE_TERRITORY.equals(type)) return TERRITORY_SCORE_REQUIRED;
        if (PEACE_PUPPET.equals(type)) return PUPPET_SCORE_REQUIRED;
        if (PEACE_TERRITORY_AND_PUPPET.equals(type)) return COMBINATION_SCORE_REQUIRED;
        return 0.0;
    }

    private static PeaceProposal copyProposal(PeaceProposal source) {
        if (source == null) return null;
        PeaceProposal copy = new PeaceProposal();
        copy.type = source.type;
        copy.proposedBy = source.proposedBy;
        copy.winnerId = source.winnerId;
        copy.loserId = source.loserId;
        copy.territoryId = source.territoryId;
        copy.acceptedByAttacker = source.acceptedByAttacker;
        copy.acceptedByDefender = source.acceptedByDefender;
        return copy;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class War {
        final String attackerId;
        final String defenderId;
        double warScore;
        double attackerCasualtyScore;
        double defenderCasualtyScore;
        double attackerOccupationScore;
        double defenderOccupationScore;
        String targetProvidenceId;
        final Set<String> attackerOccupiedProvidences = new LinkedHashSet<>();
        final Set<String> defenderOccupiedProvidences = new LinkedHashSet<>();
        int ageTicks;
        PeaceProposal pendingPeace;
        boolean over;

        War(String attackerId, String defenderId) {
            this.attackerId = attackerId;
            this.defenderId = defenderId;
        }

        boolean matchesEitherWay(String first, String second) {
            return (attackerId.equals(first) && defenderId.equals(second))
                    || (attackerId.equals(second) && defenderId.equals(first));
        }

        boolean involves(String civilisationId) {
            return attackerId.equals(civilisationId) || defenderId.equals(civilisationId);
        }

        String opponentOf(String civilisationId) {
            if (attackerId.equals(civilisationId)) return defenderId;
            if (defenderId.equals(civilisationId)) return attackerId;
            return null;
        }

        String winnerId() {
            if (warScore > 0.5) return attackerId;
            if (warScore < -0.5) return defenderId;
            return null;
        }

        String loserId() {
            if (warScore > 0.5) return defenderId;
            if (warScore < -0.5) return attackerId;
            return null;
        }

        double scoreFor(String civilisationId) {
            if (attackerId.equals(civilisationId)) return warScore;
            if (defenderId.equals(civilisationId)) return -warScore;
            return 0.0;
        }

        void recalculateScore() {
            warScore = clamp((attackerCasualtyScore + attackerOccupationScore)
                    - (defenderCasualtyScore + defenderOccupationScore), -100.0, 100.0);
        }

        void recalculateScorePreservingLegacy(double legacyScore) {
            double derived = (attackerCasualtyScore + attackerOccupationScore)
                    - (defenderCasualtyScore + defenderOccupationScore);
            if (Math.abs(derived) > 0.0001) warScore = clamp(derived, -100.0, 100.0);
            else warScore = clamp(legacyScore == 0.0 ? warScore : legacyScore, -100.0, 100.0);
        }

        boolean isOver() { return over; }
    }

    /** Gson-friendly persisted/network war state. Positive warScore favours attacker. */
    public static final class WarState {
        public String attackerId;
        public String defenderId;
        public double warScore;
        public double attackerCasualtyScore;
        public double defenderCasualtyScore;
        public double attackerOccupationScore;
        public double defenderOccupationScore;
        public String targetProvidenceId;
        public List<String> attackerOccupiedProvidences = new ArrayList<>();
        public List<String> defenderOccupiedProvidences = new ArrayList<>();
        public int ageTicks;
        public PeaceProposal pendingPeace;
        public String recommendedTerritoryId;

        // Legacy v1-v5 save fields; ignored for new simulation except migration.
        public double attackerProgress;
        public boolean countrysideCaptured;
        public boolean cityCaptured;

        public WarState() {
        }

        /** Backwards-compatible constructor used by old code/tests. */
        public WarState(String attackerId, String defenderId, double attackerProgress) {
            this.attackerId = attackerId;
            this.defenderId = defenderId;
            this.attackerProgress = attackerProgress;
        }

        /** Backwards-compatible constructor used by v3-v5 state. */
        public WarState(String attackerId, String defenderId, double attackerProgress,
                        String targetProvidenceId, boolean countrysideCaptured, boolean cityCaptured) {
            this(attackerId, defenderId, attackerProgress);
            this.targetProvidenceId = targetProvidenceId;
            this.countrysideCaptured = countrysideCaptured;
            this.cityCaptured = cityCaptured;
        }

        public boolean involves(String civilisationId) {
            return civilisationId != null
                    && (civilisationId.equals(attackerId) || civilisationId.equals(defenderId));
        }

        public String opponentOf(String civilisationId) {
            if (civilisationId == null) return null;
            if (civilisationId.equals(attackerId)) return defenderId;
            if (civilisationId.equals(defenderId)) return attackerId;
            return null;
        }

        public double scoreFor(String civilisationId) {
            if (civilisationId == null) return 0.0;
            if (civilisationId.equals(attackerId)) return warScore;
            if (civilisationId.equals(defenderId)) return -warScore;
            return 0.0;
        }
    }

    /** Gson-friendly two-party treaty proposal. */
    public static final class PeaceProposal {
        public String type;
        public String proposedBy;
        public String winnerId;
        public String loserId;
        public String territoryId;
        public boolean acceptedByAttacker;
        public boolean acceptedByDefender;

        public PeaceProposal() {
        }
    }
}



