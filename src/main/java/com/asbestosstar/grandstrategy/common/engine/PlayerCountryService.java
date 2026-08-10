package com.asbestosstar.grandstrategy.common.engine;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.DataManager;
import com.asbestosstar.grandstrategy.common.data.Leader;
import com.asbestosstar.grandstrategy.common.data.Providence;
import com.asbestosstar.grandstrategy.common.world.WorldMapTracker;
import com.asbestosstar.grandstrategy.common.world.PhysicalVillagerSystem;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Server-thread operations for player-created countries. */
public final class PlayerCountryService {
    private PlayerCountryService() {
    }

    public static Civilisation findForIgn(String ign) {
        if (ign == null || ign.isBlank()) return null;
        for (Civilisation civilisation : DataManager.getCivilisations().values()) {
            if (isLivePlayerCountry(civilisation)
                    && civilisation.getFounderIgn() != null
                    && civilisation.getFounderIgn().equalsIgnoreCase(ign)) {
                return civilisation;
            }
        }
        return null;
    }

    /** Preferred multiplayer ownership lookup. Old saves without UUIDs fall back to IGN. */
    public static Civilisation findForPlayer(String uuid, String ign) {
        if (uuid != null && !uuid.isBlank()) {
            for (Civilisation civilisation : DataManager.getCivilisations().values()) {
                if (isLivePlayerCountry(civilisation)
                        && uuid.equalsIgnoreCase(civilisation.getFounderUuid())) {
                    return civilisation;
                }
            }
        }
        return findForIgn(ign);
    }


    /**
     * A collapsed/zero-population player country is historical state, not current
     * player ownership. Ignoring it here lets the same player found a replacement
     * civilisation after destruction without deleting the old country's history.
     */
    private static boolean isLivePlayerCountry(Civilisation civilisation) {
        return civilisation != null
                && civilisation.isPlayerCreated()
                && civilisation.isActive()
                && !civilisation.isCollapsed()
                && civilisation.getPopulation() > 0;
    }

    /** Backwards-compatible overload. */
    public static synchronized Civilisation createCountry(String ign, long currentYear) {
        return createCountry(ign, null, currentYear, null, null);
    }

    /** Backwards-compatible overload. */
    public static synchronized Civilisation createCountry(
            String ign, long currentYear, Integer playerBlockX, Integer playerBlockZ) {
        return createCountry(ign, null, currentYear, playerBlockX, playerBlockZ);
    }

    /**
     * Creates a player country by colonising an already-existing geographic
     * providence. The providence and command-post city exist before the country;
     * founding merely takes an uncolonised command post and gives the city a real
     * name.
     */
    public static synchronized Civilisation createCountry(
            String ign, String uuid, long currentYear, Integer playerBlockX, Integer playerBlockZ) {
        String safeIgn = sanitiseVisibleName(ign);
        Civilisation existing = findForPlayer(uuid, safeIgn);
        if (existing != null) return existing;

        // Make sure newly discovered land has already been divided into permanent
        // providences before choosing the founding location.
        ProvidenceSystem.update(WorldMapTracker.getInstance().snapshot());

        Providence homeland = null;
        if (playerBlockX != null && playerBlockZ != null) {
            Providence containing = ProvidenceSystem.providenceContainingBlock(playerBlockX, playerBlockZ);
            if (ProvidenceSystem.isAvailableForNewCountry(containing)) homeland = containing;
        }
        if (homeland == null && playerBlockX != null && playerBlockZ != null) {
            homeland = ProvidenceSystem.nearestUncolonisedProvidence(playerBlockX, playerBlockZ);
        }
        if (homeland == null) return null;

        String baseId = "player_" + slugify(safeIgn);
        String id = baseId;
        int suffix = 2;
        while (DataManager.getCivilisations().containsKey(id)) id = baseId + "_" + suffix++;
        String leaderId = id + "_founder";

        Civilisation civilisation = new Civilisation(
                id, safeIgn, leaderId, "nonaligned", "secular", List.of(homeland.getId()),
                currentYear, 50, 50);
        civilisation.setDefaultCityNames(List.of(safeIgn, safeIgn + " City", safeIgn + " Port", safeIgn + " Heights", safeIgn + " Junction"));
        civilisation.initialisePlayerCountry(safeIgn, uuid, currentYear);
        if (playerBlockX != null && playerBlockZ != null) {
            civilisation.setWorldMapPosition(playerBlockX, playerBlockZ);
        }

        DataManager.getLeaders().put(leaderId,
                new Leader(leaderId, safeIgn, false, List.of("Founder")));
        DataManager.getCivilisations().put(id, civilisation);

        ProvidenceSystem.coloniseProvidence(homeland, civilisation, civilisation.nextDefaultCityName(), true);

        if (playerBlockX != null && playerBlockZ != null) {
            WorldMapTracker.getInstance().assignPlayerCountryLocation(civilisation, playerBlockX, playerBlockZ);
        }

        PhysicalVillagerSystem.getInstance().requestImmediateReconcile();
        System.out.println("Created player country " + civilisation.getName()
                + " [" + civilisation.getId() + "] with " + civilisation.getPopulation()
                + " villagers by colonising existing providence "
                + homeland.getId() + " and city " + homeland.getCity().getName() + ".");
        return civilisation;
    }

    public static boolean improveRelations(String sourceId, String targetId) {
        Civilisation source = DataManager.getCivilisations().get(sourceId);
        Civilisation target = DataManager.getCivilisations().get(targetId);
        if (source == null || target == null || source == target
                || !source.isActive() || !target.isActive()) return false;
        if (!source.spendPoliticalPower(10.0)) return false;
        source.modifyRelation(targetId, 10);
        target.modifyRelation(sourceId, 5);
        return true;
    }

    public static boolean declareWar(String sourceId, String targetId) {
        Civilisation source = DataManager.getCivilisations().get(sourceId);
        Civilisation target = DataManager.getCivilisations().get(targetId);
        if (source == null || target == null || source == target
                || !source.isActive() || !target.isActive() || source.isPuppet()) return false;
        if (WarSystem.getInstance().areAtWar(sourceId, targetId)) return false;
        if (!source.spendPoliticalPower(25.0)) return false;
        source.modifyRelation(targetId, -50);
        target.modifyRelation(sourceId, -75);
        return WarSystem.getInstance().declareWar(sourceId, targetId);
    }

    private static String sanitiseVisibleName(String ign) {
        if (ign == null || ign.isBlank()) return "Player";
        String trimmed = ign.trim();
        return trimmed.length() <= 32 ? trimmed : trimmed.substring(0, 32);
    }

    private static String slugify(String value) {
        String normalised = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalised.isBlank() ? "player" : normalised;
    }
}



