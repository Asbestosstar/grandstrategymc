package com.asbestosstar.grandstrategy.common.engine;

import com.asbestosstar.grandstrategy.common.data.City;
import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.DataManager;
import com.asbestosstar.grandstrategy.common.data.Ideology;
import com.asbestosstar.grandstrategy.common.data.Leader;
import com.asbestosstar.grandstrategy.common.data.Providence;
import com.asbestosstar.grandstrategy.common.data.Religion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Religion, ideology, cohesion, conversion and social-conflict simulation. */
public final class SocietySystem {
    private static final Set<String> ANNOUNCED_RELIGIONS = new HashSet<>();
    private static long stepCounter;

    private SocietySystem() { }

    public static synchronized void resetRuntimeState() {
        ANNOUNCED_RELIGIONS.clear();
        stepCounter = 0L;
    }

    public static synchronized Set<String> snapshotEmergedReligionIds() {
        return Set.copyOf(ANNOUNCED_RELIGIONS);
    }

    public static synchronized void restoreEmergedReligionIds(java.util.Collection<String> ids) {
        ANNOUNCED_RELIGIONS.clear();
        if (ids != null) for (String id : ids) if (id != null && !id.isBlank()) ANNOUNCED_RELIGIONS.add(id);
    }

    /** Used only when migrating old saves that predate persistent religion events. */
    public static synchronized void markReligionsThroughYearAsAlreadyEmerged(long currentYear) {
        for (Religion religion : DataManager.getReligions().values()) {
            if (religion != null && religion.getId() != null && religionAvailable(religion.getId(), currentYear))
                ANNOUNCED_RELIGIONS.add(religion.getId());
        }
    }

    public static void tick(long currentYear) {
        stepCounter++;
        announceNewReligions(currentYear);
        for (Civilisation civilisation : DataManager.getCivilisations().values()) {
            if (civilisation == null || !civilisation.isActive()) continue;
            civilisation.tickCivilWarCooldown();
            applyLeaderAndStateSnaps(civilisation, currentYear);
            driftReligion(civilisation, currentYear);
            driftIdeology(civilisation, currentYear);
            applyCohesion(civilisation);
            CivilWarSystem.maybeTrigger(civilisation, currentYear);
        }
        if (stepCounter % 60L == 0L) applyReligiousAndIdeologicalDiplomacy();
    }

    public static boolean religionAvailable(String religionId, long currentYear) {
        if (religionId == null || religionId.isBlank()) return false;
        Religion religion = DataManager.findReligion(religionId);
        if (religion == null || currentYear < religion.getCreationYear()) return false;
        String parent = religion.getParentId();
        return parent == null || parent.isBlank() || religionAvailable(parent, currentYear);
    }

    public static boolean ideologyAvailable(Civilisation civilisation, String ideologyId, long currentYear) {
        if (ideologyId == null || ideologyId.isBlank()) return false;
        Ideology ideology = DataManager.findIdeology(ideologyId);
        return ResearchSystem.ideologyAvailable(civilisation, ideology, currentYear);
    }

    private static void announceNewReligions(long currentYear) {
        List<Religion> religions = DataManager.getReligions().values().stream()
                .filter(religion -> religion != null && religion.getId() != null)
                .filter(religion -> religionAvailable(religion.getId(), currentYear))
                .sorted(Comparator.comparingLong(Religion::getCreationYear))
                .toList();
        for (Religion religion : religions) {
            if (!ANNOUNCED_RELIGIONS.add(religion.getId())) continue;
            City origin = findOriginCity(religion);
            triggerReligionEmergenceEvent(religion, origin);
            if (religion.getCreationYear() != Long.MIN_VALUE) {
                System.out.println("[GrandStrategy][Religion] " + religion.getName()
                        + " emerged" + (origin == null ? "" : " in " + origin.getName())
                        + " in " + formatYear(currentYear) + ".");
            }
        }
    }

    /**
     * Historical religion creation is a real city event when one of the
     * religion's configured origin cities exists.  The city keeps its own name
     * regardless of the country currently controlling it; the controller is
     * merely the population that receives the first substantial conversion
     * pressure.  If none of the configured cities exists, the religion still
     * becomes globally available at its historical date so alternate-history
     * worlds cannot permanently deadlock the religion tree.
     */
    private static void triggerReligionEmergenceEvent(Religion religion, City origin) {
        if (religion == null || origin == null || !religion.isPopulationAllowed()) return;
        Civilisation controller = DataManager.findCivilisation(origin.getControllerId());
        if (controller == null || !controller.isActive()) return;
        controller.shiftPopulationReligionToward(religion.getId(), 0.18);
        controller.addReligiousExtremism(2.0);
        System.out.println("[GrandStrategy][ReligionEvent] " + origin.getName()
                + " became the origin centre of " + religion.getName()
                + "; first converts appeared in " + controller.getName() + ".");
    }

    private static City findOriginCity(Religion religion) {
        if (religion == null || religion.getOriginCityNames().isEmpty()) return null;
        for (String wanted : religion.getOriginCityNames()) {
            for (Providence providence : DataManager.getProvidences().values()) {
                City city = providence == null ? null : providence.getCity();
                if (city != null && wanted.equalsIgnoreCase(city.getName())) return city;
            }
        }
        return null;
    }

    private static void applyLeaderAndStateSnaps(Civilisation civilisation, long currentYear) {
        Leader leader = DataManager.findLeader(civilisation.getDefaultLeaderId());
        if (leader != null) {
            leader.normaliseAfterLoad();
            String snap = leader.getSnapReligionId();
            Religion religion = DataManager.findReligion(snap);
            if (snap != null && !snap.equals(leader.getReligionId())
                    && religion != null && religion.isLeaderAllowed()
                    && religionAvailable(snap, currentYear)) {
                leader.setReligionId(snap);
                System.out.println("[GrandStrategy][Religion] " + leader.getName()
                        + " adopted " + religion.getName() + ".");
            }
            String ideologySnap = leader.getSnapIdeologyId();
            if (ideologySnap != null && !ideologySnap.equals(leader.getIdeologyId())
                    && ideologyAvailable(civilisation, ideologySnap, currentYear)) {
                leader.setIdeologyId(ideologySnap);
            }
        }

        String stateSnap = civilisation.getSnapStateReligionId();
        Religion state = DataManager.findReligion(stateSnap);
        if (stateSnap != null && !stateSnap.equals(civilisation.getStateReligionId())
                && state != null && religionAvailable(stateSnap, currentYear)) {
            civilisation.setStateReligionId(stateSnap);
        }

        // Leader and confessional state normally pull one another toward alignment.
        // A secular state is deliberately exempt: it may have a strongly religious
        // leader (ClioAite is the canonical example) without ceasing to be secular.
        if (leader != null && stepCounter % 300L == 0L) {
            String stateReligion = civilisation.getStateReligionId();
            String leaderReligion = leader.getReligionId();
            if (stateReligion != null && !"secular".equalsIgnoreCase(stateReligion)
                    && leaderReligion != null && !stateReligion.equals(leaderReligion)) {
                double statePressure = civilisation.getReligiousExtremism();
                double leaderPressure = leader.getReligiousExtremism();
                if (statePressure >= leaderPressure + 12.0) {
                    Religion stateDefinition = DataManager.findReligion(stateReligion);
                    if (stateDefinition != null && stateDefinition.isLeaderAllowed()) leader.setReligionId(stateReligion);
                } else if (leaderPressure >= statePressure + 12.0) {
                    Religion leaderDefinition = DataManager.findReligion(leaderReligion);
                    if (leaderDefinition != null && religionAvailable(leaderReligion, currentYear))
                        civilisation.setStateReligionId(leaderReligion);
                }
            }
        }
    }

    private static void driftReligion(Civilisation civilisation, long currentYear) {
        double extremism = civilisation.getReligiousExtremism() / 100.0;
        String state = civilisation.getStateReligionId();
        if (state != null && !"secular".equalsIgnoreCase(state) && religionAvailable(state, currentYear)) {
            civilisation.shiftPopulationReligionToward(state, 0.00020 + 0.00030 * extremism);
        }

        Leader leader = DataManager.findLeader(civilisation.getDefaultLeaderId());
        if (leader != null && leader.getReligionId() != null
                && religionAvailable(leader.getReligionId(), currentYear)) {
            civilisation.shiftPopulationReligionToward(leader.getReligionId(), 0.00010 + 0.00018 * extremism);
        }

        Civilisation neighbour = nearestOtherCountry(civilisation);
        if (neighbour != null) {
            String neighbourReligion = neighbour.getPopulationPluralityReligion();
            Religion definition = DataManager.findReligion(neighbourReligion);
            if (definition != null && definition.isPopulationAllowed()
                    && religionAvailable(neighbourReligion, currentYear)) {
                civilisation.shiftPopulationReligionToward(neighbourReligion, 0.000035);
            }
        }

        // Local cults continually regenerate slightly in fragmented societies and
        // represent city-level cults/historical practices without forcing a state
        // religion change.
        double fragmentation = fragmentation(civilisation.getPopulationReligions());
        if (fragmentation > 0.35 && religionAvailable("local_cult", currentYear)) {
            civilisation.shiftPopulationReligionToward("local_cult", 0.000025 * fragmentation);
        }
    }

    private static void driftIdeology(Civilisation civilisation, long currentYear) {
        double extremism = civilisation.getIdeologicalExtremism() / 100.0;
        if (ideologyAvailable(civilisation, civilisation.getIdeology(), currentYear)) {
            civilisation.shiftIdeologySupportToward(civilisation.getIdeology(), 0.00018 + 0.00028 * extremism);
        } else {
            Ideology current = DataManager.findIdeology(civilisation.getIdeology());
            String fallback = current == null ? "nonaligned" : current.getFallbackId();
            if (fallback != null && ideologyAvailable(civilisation, fallback, currentYear)) {
                civilisation.setIdeology(fallback);
                civilisation.shiftIdeologySupportToward(fallback, 0.10);
            }
        }

        Civilisation neighbour = nearestOtherCountry(civilisation);
        if (neighbour != null && ideologyAvailable(civilisation, neighbour.getIdeology(), currentYear)) {
            civilisation.shiftIdeologySupportToward(neighbour.getIdeology(), 0.000025);
        }
    }

    private static void applyCohesion(Civilisation civilisation) {
        Map<String, Double> religions = civilisation.getPopulationReligions();
        String pluralityReligion = civilisation.getPopulationPluralityReligion();
        String stateReligion = civilisation.getStateReligionId();
        Leader leader = DataManager.findLeader(civilisation.getDefaultLeaderId());
        String leaderReligion = leader == null ? null : leader.getReligionId();

        double statePeople = religionSimilarity(stateReligion, pluralityReligion);
        double leaderState = religionSimilarity(leaderReligion, stateReligion);
        double leaderPeople = religionSimilarity(leaderReligion, pluralityReligion);
        double religiousAlignment = (statePeople * 0.50 + leaderState * 0.20 + leaderPeople * 0.30);
        double religiousFragmentation = fragmentation(religions);
        double religiousIntensity = Math.pow(civilisation.getReligiousExtremism() / 100.0, 1.35);
        double religiousDelta = religiousIntensity
                * ((religiousAlignment - 0.48) * 0.00115 - religiousFragmentation * 0.00050);

        Map<String, Double> ideologies = civilisation.getIdeologySupport();
        String pluralityIdeology = civilisation.getPopulationPluralityIdeology();
        String stateIdeology = civilisation.getIdeology();
        String leaderIdeology = leader == null ? null : leader.getIdeologyId();
        double ideologyStatePeople = ideologySimilarity(stateIdeology, pluralityIdeology);
        double ideologyLeaderState = leaderIdeology == null ? ideologyStatePeople : ideologySimilarity(leaderIdeology, stateIdeology);
        double ideologyLeaderPeople = leaderIdeology == null ? ideologyStatePeople : ideologySimilarity(leaderIdeology, pluralityIdeology);
        double ideologyAlignment = ideologyStatePeople * 0.55 + ideologyLeaderState * 0.20 + ideologyLeaderPeople * 0.25;
        double ideologyFragmentation = fragmentation(ideologies);
        double ideologyIntensity = Math.pow(civilisation.getIdeologicalExtremism() / 100.0, 1.35);
        double ideologyDelta = ideologyIntensity
                * ((ideologyAlignment - 0.48) * 0.00100 - ideologyFragmentation * 0.00045);

        // Ideologies may explicitly favour or oppose a religion/religion family.
        // This makes, for example, an extreme anti-religious ideology governing a
        // highly religious population an additional cohesion problem, while a
        // compatible secular/nonreligious population slightly reinforces it.
        Ideology ideologyDefinition = DataManager.findIdeology(stateIdeology);
        double ideologyReligion = ideologyReligionCompatibility(ideologyDefinition, pluralityReligion);
        ideologyDelta += ideologyIntensity * ideologyReligion * 0.00035;

        civilisation.setStability(civilisation.getStability() + religiousDelta + ideologyDelta);
    }

    /** 0=identical sect, partial=same family, 0=different; secular/nonreligious have limited compatibility. */
    public static double religionSimilarity(String firstId, String secondId) {
        if (firstId == null || secondId == null) return 0.0;
        if (firstId.equalsIgnoreCase(secondId)) return 1.0;
        Religion first = DataManager.findReligion(firstId);
        Religion second = DataManager.findReligion(secondId);
        if (first == null || second == null) return 0.0;
        if (Objects.equals(first.getFamilyId(), second.getFamilyId())) return 0.68;
        boolean firstNon = "nonreligious".equals(first.getFamilyId()) || "secular".equals(first.getFamilyId());
        boolean secondNon = "nonreligious".equals(second.getFamilyId()) || "secular".equals(second.getFamilyId());
        if (firstNon && secondNon) return 0.55;
        return 0.0;
    }

    public static double ideologyReligionCompatibility(Ideology ideology, String religionId) {
        if (ideology == null || religionId == null) return 0.0;
        Religion religion = DataManager.findReligion(religionId);
        Double exact = ideology.getReligionCompatibility().get(religionId);
        if (exact != null) return Math.max(-1.0, Math.min(1.0, exact));
        if (religion != null) {
            Double family = ideology.getReligionCompatibility().get(religion.getFamilyId());
            if (family != null) return Math.max(-1.0, Math.min(1.0, family));
        }
        return 0.0;
    }

    public static double ideologySimilarity(String firstId, String secondId) {
        if (firstId == null || secondId == null) return 0.0;
        if (firstId.equalsIgnoreCase(secondId)) return 1.0;
        Ideology first = DataManager.findIdeology(firstId);
        Ideology second = DataManager.findIdeology(secondId);
        if (first == null || second == null) return 0.0;
        if (Objects.equals(first.getFamilyId(), second.getFamilyId())) return 0.70;
        if (first.isNonAligned() || second.isNonAligned()) return 0.25;
        return 0.0;
    }

    public static double fragmentation(Map<String, Double> shares) {
        if (shares == null || shares.isEmpty()) return 0.0;
        double sumSquares = 0.0;
        for (double share : shares.values()) sumSquares += Math.max(0.0, share) * Math.max(0.0, share);
        return Math.max(0.0, Math.min(1.0, 1.0 - sumSquares));
    }

    private static void applyReligiousAndIdeologicalDiplomacy() {
        List<Civilisation> active = DataManager.getCivilisations().values().stream()
                .filter(Civilisation::isActive).toList();
        for (int i = 0; i < active.size(); i++) {
            Civilisation a = active.get(i);
            for (int j = i + 1; j < active.size(); j++) {
                Civilisation b = active.get(j);
                double religiousSimilarity = religionSimilarity(a.getStateReligionId(), b.getStateReligionId());
                double religiousIntensity = (a.getReligiousExtremism() + b.getReligiousExtremism()) / 200.0;
                double ideologySimilarity = ideologySimilarity(a.getIdeology(), b.getIdeology());
                double ideologyIntensity = (a.getIdeologicalExtremism() + b.getIdeologicalExtremism()) / 200.0;
                double score = (religiousSimilarity - 0.45) * religiousIntensity
                        + (ideologySimilarity - 0.45) * ideologyIntensity;
                int delta = score > 0.20 ? 1 : score < -0.25 ? -1 : 0;
                if (delta != 0) {
                    a.modifyRelation(b.getId(), delta);
                    b.modifyRelation(a.getId(), delta);
                }

                // Extremely zealous states with incompatible religions/ideologies
                // may eventually turn persistent hostility into a war. This is rare,
                // relation-gated and never forces a puppet to start an independent war.
                if (delta < 0 && !a.isPuppet() && !b.isPuppet()
                        && a.getRelation(b.getId()) <= -90
                        && religiousSimilarity < 0.20
                        && Math.max(a.getReligiousExtremism(), b.getReligiousExtremism()) >= 90.0
                        && stepCounter % 600L == 0L) {
                    Civilisation attacker = a.getReligiousExtremism() >= b.getReligiousExtremism() ? a : b;
                    Civilisation defender = attacker == a ? b : a;
                    WarSystem.getInstance().declareWar(attacker.getId(), defender.getId());
                }
            }
        }
    }

    public static double militaryMoraleMultiplier(Civilisation civilisation) {
        if (civilisation == null) return 1.0;
        Religion state = DataManager.findReligion(civilisation.getStateReligionId());
        double base = state == null ? 0.0 : state.getMilitaryMoraleModifier();
        double extremism = civilisation.getReligiousExtremism() / 100.0;
        if (state != null && state.isNonviolent()) base = Math.min(base, -0.10);
        return Math.max(0.65, Math.min(1.40, 1.0 + base * (0.40 + 0.60 * extremism)));
    }

    public static double cropGrowthMultiplier(Civilisation civilisation) {
        if (civilisation == null) return 1.0;
        Religion state = DataManager.findReligion(civilisation.getStateReligionId());
        if (state == null) return 1.0;
        double extremism = civilisation.getReligiousExtremism() / 100.0;
        double bonus = state.getCropGrowthModifier() + state.getRainModifier() * 0.5;
        return Math.max(0.75, Math.min(1.50, 1.0 + bonus * (0.35 + 0.65 * extremism)));
    }

    private static Civilisation nearestOtherCountry(Civilisation civilisation) {
        if (civilisation == null || !civilisation.hasWorldMapPosition()) return null;
        Civilisation best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Civilisation other : DataManager.getCivilisations().values()) {
            if (other == null || other == civilisation || !other.isActive() || !other.hasWorldMapPosition()) continue;
            double dx = other.getWorldMapBlockX() - civilisation.getWorldMapBlockX();
            double dz = other.getWorldMapBlockZ() - civilisation.getWorldMapBlockZ();
            double distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = other;
            }
        }
        return best;
    }

    private static String formatYear(long year) {
        return year < 0 ? Math.abs(year) + " BCE" : year + " CE";
    }
}

