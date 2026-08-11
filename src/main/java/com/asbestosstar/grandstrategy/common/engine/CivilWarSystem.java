package com.asbestosstar.grandstrategy.common.engine;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.DataManager;
import com.asbestosstar.grandstrategy.common.data.Leader;
import com.asbestosstar.grandstrategy.common.data.Providence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Creates a real breakaway country when religious/ideological fragmentation
 * becomes extreme.
 */
public final class CivilWarSystem {
	private CivilWarSystem() {
	}

	public static void maybeTrigger(Civilisation parent, long currentYear) {
		if (parent == null || !parent.isActive() || parent.getCivilWarCooldownSteps() > 0)
			return;
		if (parent.getPopulation() < 12 || parent.getStability() > 0.14)
			return;

		double religionFragmentation = SocietySystem.fragmentation(parent.getPopulationReligions());
		double ideologyFragmentation = SocietySystem.fragmentation(parent.getIdeologySupport());
		Leader leader = DataManager.findLeader(parent.getDefaultLeaderId());
		String leaderReligion = leader == null ? null : leader.getReligionId();
		String leaderIdeology = leader == null ? null : leader.getIdeologyId();
		boolean religionCrisis = parent.getReligiousExtremism() >= 70.0 && (religionFragmentation >= 0.42
				|| SocietySystem.religionSimilarity(parent.getStateReligionId(),
						parent.getPopulationPluralityReligion()) < 0.25
				|| (leaderReligion != null
						&& SocietySystem.religionSimilarity(leaderReligion, parent.getStateReligionId()) < 0.20));
		boolean ideologyCrisis = parent.getIdeologicalExtremism() >= 70.0 && (ideologyFragmentation >= 0.42
				|| SocietySystem.ideologySimilarity(parent.getIdeology(),
						parent.getPopulationPluralityIdeology()) < 0.25
				|| (leaderIdeology != null
						&& SocietySystem.ideologySimilarity(leaderIdeology, parent.getIdeology()) < 0.20));
		if (!religionCrisis && !ideologyCrisis)
			return;

		List<Providence> provinces = ProvidenceSystem.ownedProvidences(parent.getId()).stream()
				.filter(providence -> providence != null && providence.isEstablished() && providence.getCity() != null)
				.sorted(Comparator.comparing(Providence::getId)).toList();
		if (provinces.size() < 2) {
			// One-city civil conflict cannot be geographically split yet; model it
			// as violent unrest and retry only after a long cooldown.
			parent.setStability(Math.max(0.02, parent.getStability() - 0.02));
			parent.setCivilWarCooldownSteps(1_200);
			System.out.println("[GrandStrategy][CivilWar] Severe internal fighting in " + parent.getName()
					+ " could not form a territorial breakaway because it controls only one city.");
			return;
		}

		String rebelReligion = religionCrisis ? parent.getPopulationPluralityReligion() : parent.getStateReligionId();
		String rebelIdeology = ideologyCrisis ? parent.getPopulationPluralityIdeology() : parent.getIdeology();
		String baseId = parent.getId() + "_civil_war";
		int suffix = 1;
		String rebelId = baseId + "_" + suffix;
		while (DataManager.getCivilisations().containsKey(rebelId))
			rebelId = baseId + "_" + (++suffix);

		Providence rebelCapital = provinces.get(provinces.size() - 1);
		String rebelLeaderId = rebelId + "_leader";
		Leader rebelLeader = new Leader(rebelLeaderId, parent.getName() + " Opposition Council", false,
				List.of("Civil War Leader"), rebelReligion, null, rebelIdeology, null,
				Math.max(55.0, parent.getReligiousExtremism()), Math.max(55.0, parent.getIdeologicalExtremism()));
		DataManager.getLeaders().put(rebelLeaderId, rebelLeader);
		Civilisation rebel = new Civilisation(rebelId, parent.getName() + " Opposition", rebelLeaderId, rebelIdeology,
				rebelReligion, List.of(rebelCapital.getId()), currentYear, parent.getMapXPercent(),
				parent.getMapYPercent());
		rebel.setStartingPopulationModifier(parent.getStartingPopulationModifier());
		rebel.setDefaultCityNames(parent.getDefaultCityNames());
		rebel.startIfDue(currentYear);

		int rebelPopulation = Math.max(4, parent.getPopulation() / 3);
		parent.removePopulation(rebelPopulation);
		rebel.setPopulationForInternalSplit(rebelPopulation);
		rebel.setStability(0.45);
		parent.setCivilWarCooldownSteps(3_600);
		rebel.setCivilWarCooldownSteps(3_600);

		List<Providence> transferred = new ArrayList<>();
		for (int i = provinces.size() / 2; i < provinces.size(); i++) {
			Providence providence = provinces.get(i);
			providence.transferTerritoryClaims(parent.getId(), rebelId);
			providence.captureCommandPost(rebelId);
			providence.setOwnerId(rebelId);
			if (providence.getCity() != null) {
				providence.getCity().setControllerId(rebelId);
				providence.getCity().setNationalCapital(providence == rebelCapital);
				providence.getCity().setSupplyCapital(providence == rebelCapital);
			}
			transferred.add(providence);
		}
		if (rebelCapital.getCity() != null) {
			rebel.setWorldMapPosition(rebelCapital.getCity().getBlockX(), rebelCapital.getCity().getBlockZ());
			rebel.markHomelandEstablished();
		}
		DataManager.getCivilisations().put(rebelId, rebel);
		WarSystem.getInstance().declareWar(rebelId, parent.getId());
		System.out.println("[GrandStrategy][CivilWar] " + parent.getName() + " fractured: " + rebel.getName()
				+ " controls " + transferred.size() + " cities." + " Trigger="
				+ (religionCrisis ? "religion" : "ideology") + ".");
	}
}
