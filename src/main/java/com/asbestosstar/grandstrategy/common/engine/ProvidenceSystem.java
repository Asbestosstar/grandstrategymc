package com.asbestosstar.grandstrategy.common.engine;

import com.asbestosstar.grandstrategy.common.data.City;
import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.DataManager;
import com.asbestosstar.grandstrategy.common.data.Providence;
import com.asbestosstar.grandstrategy.common.world.WorldMapTracker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Server-authoritative permanent providence geography and territorial-control model.
 *
 * Discovered land is partitioned into providences whether anybody has colonised it
 * or not. Every providence has exactly one city/command post. Countries control
 * individual chunks inside a providence; owning the command post is separate from
 * those territorial claims, allowing several countries to retain land in the same
 * providence and creating persistent border disputes.
 */
public final class ProvidenceSystem {
    /** 8x8 Minecraft chunks (128x128 blocks) per deterministic geographic cell. */
    public static final int PROVIDENCE_CELL_CHUNKS = 8;
    public static final int CONSTRUCTION_CLAIM_RADIUS_CHUNKS = 1;
    public static final double ADMIN_CAPTURE_THRESHOLD = 0.50;
    private static final double SUPPLY_DISTANCE_SCALE_CHUNKS = 12.0;
    private static final int LAND_DISPUTE_RELATION_PENALTY = 12;

    private static final Set<String> ACTIVE_LAND_DISPUTES = new HashSet<>();

    private ProvidenceSystem() {
    }

    public static synchronized void resetRuntimeState() {
        ACTIVE_LAND_DISPUTES.clear();
    }

    /** Must run on the server thread after discovery has produced a current snapshot. */
    public static synchronized boolean update(WorldMapTracker.Snapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return false;

        boolean changed = false;
        Map<Long, String> landmassByChunk = computeLandmasses(snapshot);
        changed |= ensureGeographicProvidences(snapshot, landmassByChunk);

        // Only a civilisation which has NEVER successfully established its first
        // homeland may receive an automatic uncolonised providence here. Previously
        // every active country with no command post was treated as newly spawned, so
        // capturing its final city during a war caused it to materialise a replacement
        // city somewhere it had never controlled. A country which has ever had a
        // homeland must recover by military/political means or be capitulated; it is
        // never silently re-founded by this maintenance pass.
        List<Civilisation> active = DataManager.getCivilisations().values().stream()
                .filter(Civilisation::isActive)
                .filter(Civilisation::hasWorldMapPosition)
                .sorted(Comparator.comparing(Civilisation::getId, Comparator.nullsLast(String::compareTo)))
                .toList();
        for (Civilisation civilisation : active) {
            if (hasCommandPostProvidence(civilisation.getId())) {
                civilisation.markHomelandEstablished();
                continue;
            }
            if (!civilisation.hasEstablishedHomeland()) {
                changed |= assignStartingProvidence(civilisation, snapshot);
            }
        }

        changed |= refreshLandmassAndSupply(landmassByChunk);
        refreshLandDisputes();
        return changed;
    }

    /**
     * Every discovered land chunk belongs to a deterministic permanent providence.
     * Existing/legacy providences are preserved and take precedence; only genuinely
     * unassigned discovered land is filled by the neutral grid.
     */
    private static boolean ensureGeographicProvidences(WorldMapTracker.Snapshot snapshot,
                                                        Map<Long, String> landmassByChunk) {
        Map<Long, Providence> existingByChunk = new HashMap<>();
        for (Providence providence : DataManager.getProvidences().values()) {
            if (providence == null || !providence.isEstablished()) continue;
            for (long key : providence.getTerritoryChunkKeys()) existingByChunk.putIfAbsent(key, providence);
        }

        Map<String, List<WorldMapTracker.MapTile>> byCell = new LinkedHashMap<>();
        for (WorldMapTracker.MapTile tile : snapshot.tiles()) {
            if (tile == null || tile.terrain() == null || !tile.terrain().isLand()) continue;
            long key = WorldMapTracker.chunkKey(tile.chunkX(), tile.chunkZ());
            if (existingByChunk.containsKey(key)) continue;
            int cellX = Math.floorDiv(tile.chunkX(), PROVIDENCE_CELL_CHUNKS);
            int cellZ = Math.floorDiv(tile.chunkZ(), PROVIDENCE_CELL_CHUNKS);
            byCell.computeIfAbsent(gridProvidenceId(cellX, cellZ), ignored -> new ArrayList<>()).add(tile);
        }

        boolean changed = false;
        for (Map.Entry<String, List<WorldMapTracker.MapTile>> entry : byCell.entrySet()) {
            String id = entry.getKey();
            List<WorldMapTracker.MapTile> additions = entry.getValue();
            if (additions.isEmpty()) continue;

            Providence providence = DataManager.getProvidences().get(id);
            if (providence == null) {
                int[] cell = parseGridProvidenceId(id);
                WorldMapTracker.MapTile cityTile = chooseNeutralCityTile(additions, cell[0], cell[1]);
                if (cityTile == null) continue;
                String cityName = "Uncolonised City " + cell[0] + "," + cell[1];
                City city = new City(id + "_city", cityName,
                        cityTile.centreBlockX(), cityTile.centreBlockZ(), null, false, false);
                List<Long> chunks = additions.stream()
                        .map(tile -> WorldMapTracker.chunkKey(tile.chunkX(), tile.chunkZ()))
                        .distinct().toList();
                String landmass = landmassByChunk.get(WorldMapTracker.chunkKey(cityTile.chunkX(), cityTile.chunkZ()));
                providence = new Providence(id, "Providence " + cell[0] + "," + cell[1], null, 0.0, 1.0, city);
                providence.establish(cityTile.centreBlockX(), cityTile.centreBlockZ(), chunks, city, landmass);
                DataManager.getProvidences().put(id, providence);
                changed = true;
            } else {
                List<Long> combined = new ArrayList<>(providence.getTerritoryChunkKeys());
                Set<Long> seen = new HashSet<>(combined);
                for (WorldMapTracker.MapTile tile : additions) {
                    long key = WorldMapTracker.chunkKey(tile.chunkX(), tile.chunkZ());
                    if (seen.add(key)) combined.add(key);
                }
                if (combined.size() != providence.getTerritoryChunkKeys().size()) {
                    City city = providence.getCity();
                    if (city == null) {
                        int[] cell = parseGridProvidenceId(id);
                        WorldMapTracker.MapTile cityTile = chooseNeutralCityTile(additions, cell[0], cell[1]);
                        if (cityTile != null) {
                            city = new City(id + "_city", "Uncolonised City " + cell[0] + "," + cell[1],
                                    cityTile.centreBlockX(), cityTile.centreBlockZ(), providence.getOwnerId(), false, false);
                        }
                    }
                    if (city != null) {
                        providence.establish(providence.getCentreBlockX(), providence.getCentreBlockZ(), combined,
                                city, providence.getLandmassId());
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    private static String gridProvidenceId(int cellX, int cellZ) {
        return "world_providence_" + signedToken(cellX) + "_" + signedToken(cellZ);
    }

    private static String signedToken(int value) {
        return value < 0 ? "n" + Math.abs((long) value) : "p" + value;
    }

    private static int parseSignedToken(String token) {
        if (token.startsWith("n")) return -Integer.parseInt(token.substring(1));
        if (token.startsWith("p")) return Integer.parseInt(token.substring(1));
        return Integer.parseInt(token);
    }

    private static int[] parseGridProvidenceId(String id) {
        String prefix = "world_providence_";
        String body = id.startsWith(prefix) ? id.substring(prefix.length()) : id;
        int split = body.indexOf('_');
        if (split < 0) return new int[]{0, 0};
        return new int[]{parseSignedToken(body.substring(0, split)), parseSignedToken(body.substring(split + 1))};
    }

    private static WorldMapTracker.MapTile chooseNeutralCityTile(List<WorldMapTracker.MapTile> tiles,
                                                                  int cellX, int cellZ) {
        double centreChunkX = cellX * (double) PROVIDENCE_CELL_CHUNKS + (PROVIDENCE_CELL_CHUNKS - 1) * 0.5;
        double centreChunkZ = cellZ * (double) PROVIDENCE_CELL_CHUNKS + (PROVIDENCE_CELL_CHUNKS - 1) * 0.5;
        return tiles.stream().min(Comparator.comparingDouble(tile -> {
            double dx = tile.chunkX() - centreChunkX;
            double dz = tile.chunkZ() - centreChunkZ;
            return dx * dx + dz * dz;
        })).orElse(null);
    }

    /** Public activation hook used immediately when a historical civilisation starts. */
    public static synchronized boolean assignStartingProvidence(Civilisation civilisation) {
        return assignStartingProvidence(civilisation, WorldMapTracker.getInstance().snapshot());
    }

    private static boolean assignStartingProvidence(Civilisation civilisation, WorldMapTracker.Snapshot snapshot) {
        if (civilisation == null || !civilisation.isActive() || snapshot == null || snapshot.isEmpty()) return false;
        if (hasCommandPostProvidence(civilisation.getId())) return false;

        Providence chosen = null;
        if (civilisation.hasWorldMapPosition()) {
            long anchorKey = WorldMapTracker.chunkKey(
                    Math.floorDiv(civilisation.getWorldMapBlockX(), WorldMapTracker.CHUNK_SIZE),
                    Math.floorDiv(civilisation.getWorldMapBlockZ(), WorldMapTracker.CHUNK_SIZE));
            Providence atAnchor = providenceContainingChunk(anchorKey);
            if (isAvailableForNewCountry(atAnchor)) chosen = atAnchor;
        }

        // Respect an old/template starting ID only if it now refers to real unowned geography.
        if (chosen == null) {
            for (String id : civilisation.getStartingProvidences()) {
                Providence candidate = DataManager.getProvidences().get(id);
                if (isAvailableForNewCountry(candidate)) {
                    chosen = candidate;
                    break;
                }
            }
        }

        if (chosen == null) {
            int x = civilisation.hasWorldMapPosition() ? civilisation.getWorldMapBlockX() : 0;
            int z = civilisation.hasWorldMapPosition() ? civilisation.getWorldMapBlockZ() : 0;
            chosen = nearestUncolonisedProvidence(x, z);
        }
        if (chosen == null) return false;

        coloniseProvidence(chosen, civilisation, civilisation.nextDefaultCityName(), true);
        return true;
    }

    public static synchronized Providence nearestUncolonisedProvidence(int blockX, int blockZ) {
        Providence best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Providence providence : DataManager.getProvidences().values()) {
            if (!isAvailableForNewCountry(providence) || providence.getCity() == null) continue;
            double distance = distanceBlocks(blockX, blockZ,
                    providence.getCity().getBlockX(), providence.getCity().getBlockZ());
            if (distance < bestDistance) {
                best = providence;
                bestDistance = distance;
            }
        }
        return best;
    }

    public static boolean isAvailableForNewCountry(Providence providence) {
        if (providence == null || !providence.isEstablished() || providence.getCity() == null) return false;
        String owner = providence.getOwnerId();
        String cityOwner = providence.getCity().getControllerId();
        return (owner == null || owner.isBlank()) && (cityOwner == null || cityOwner.isBlank());
    }

    /**
     * Founding a country takes an existing providence and gives its existing city a
     * real country/city name. The geographic providence itself is never recreated.
     */
    public static synchronized void coloniseProvidence(Providence providence, Civilisation civilisation,
                                                        String cityName, boolean nationalCapital) {
        if (providence == null || civilisation == null || providence.getCity() == null) return;
        City city = providence.getCity();
        city.setName(cityName == null || cityName.isBlank() ? civilisation.nextDefaultCityName() : cityName);
        city.setControllerId(civilisation.getId());
        city.setNationalCapital(nationalCapital);
        city.setSupplyCapital(nationalCapital);
        providence.setOwnerId(civilisation.getId());
        providence.setName(city.getName() + " Providence");
        providence.claimAllUnclaimedTerritory(civilisation.getId());
        providence.setResistanceLevel(0.0);
        civilisation.replaceStartingProvidences(List.of(providence.getId()));
        civilisation.markHomelandEstablished();
        if (!civilisation.hasWorldMapPosition()) {
            civilisation.setWorldMapPosition(city.getBlockX(), city.getBlockZ());
        }
    }

    private static boolean hasCommandPostProvidence(String civilisationId) {
        if (civilisationId == null) return false;
        for (Providence providence : DataManager.getProvidences().values()) {
            if (providence != null && providence.isEstablished() && providence.getCity() != null
                    && Objects.equals(civilisationId, providence.getCity().getControllerId())) return true;
        }
        return false;
    }

    /** Farm/factory construction claims nearby unclaimed chunks inside the same providence. */
    public static synchronized boolean claimConstructionArea(String civilisationId, int blockX, int blockZ) {
        if (civilisationId == null || civilisationId.isBlank()) return false;
        int centreChunkX = Math.floorDiv(blockX, WorldMapTracker.CHUNK_SIZE);
        int centreChunkZ = Math.floorDiv(blockZ, WorldMapTracker.CHUNK_SIZE);
        Providence providence = providenceContainingChunk(WorldMapTracker.chunkKey(centreChunkX, centreChunkZ));
        if (providence == null) return false;

        boolean changed = false;
        for (int dz = -CONSTRUCTION_CLAIM_RADIUS_CHUNKS; dz <= CONSTRUCTION_CLAIM_RADIUS_CHUNKS; dz++) {
            for (int dx = -CONSTRUCTION_CLAIM_RADIUS_CHUNKS; dx <= CONSTRUCTION_CLAIM_RADIUS_CHUNKS; dx++) {
                long key = WorldMapTracker.chunkKey(centreChunkX + dx, centreChunkZ + dz);
                changed |= providence.claimTerritoryChunk(key, civilisationId);
            }
        }
        return changed;
    }

    /** Administrator may take a command post after controlling strictly more than half the geography. */
    public static synchronized Providence administratorCaptureTarget(String civilisationId, double blockX, double blockZ) {
        if (civilisationId == null) return null;
        Providence best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Providence providence : DataManager.getProvidences().values()) {
            if (providence == null || !providence.isEstablished() || providence.getCity() == null) continue;
            if (Objects.equals(civilisationId, providence.getCity().getControllerId())) continue;
            if (providence.territoryControlShare(civilisationId) <= ADMIN_CAPTURE_THRESHOLD) continue;
            String controller = providence.getCity().getControllerId();
            if (controller != null && WarSystem.getInstance().areAtWar(civilisationId, controller)) continue;
            double dx = providence.getCity().getBlockX() - blockX;
            double dz = providence.getCity().getBlockZ() - blockZ;
            double distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                best = providence;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Nearest enemy city command post that this country is legally at war with. */
    public static synchronized Providence wartimeCommandPostTarget(String civilisationId, double blockX, double blockZ) {
        if (civilisationId == null) return null;
        Providence best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Providence providence : DataManager.getProvidences().values()) {
            if (providence == null || !providence.isEstablished() || providence.getCity() == null) continue;
            String controller = providence.getCity().getControllerId();
            if (!WarSystem.getInstance().areAtWar(civilisationId, controller)) continue;
            double dx = providence.getCity().getBlockX() - blockX;
            double dz = providence.getCity().getBlockZ() - blockZ;
            double distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                best = providence;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Changes only command-post/city ownership; territorial chunk claims remain untouched. */
    public static synchronized boolean captureCommandPost(Providence providence, String civilisationId) {
        if (providence == null || civilisationId == null || civilisationId.isBlank()) return false;
        String previous = providence.getOwnerId();
        boolean changed = providence.captureCommandPost(civilisationId);
        if (changed && providence.getCity() != null) {
            providence.getCity().setNationalCapital(false);
            providence.getCity().setSupplyCapital(false);
        }
        if (previous != null && !Objects.equals(previous, civilisationId)) {
            Civilisation old = DataManager.getCivilisations().get(previous);
            Civilisation next = DataManager.getCivilisations().get(civilisationId);
            if (old != null) old.modifyRelation(civilisationId, -10);
            if (next != null) next.modifyRelation(previous, -10);
        }
        return changed;
    }

    /** Detect mixed chunk claims and apply one diplomatic penalty when a dispute begins. */
    private static void refreshLandDisputes() {
        Set<String> current = new HashSet<>();
        for (Providence providence : DataManager.getProvidences().values()) {
            if (providence == null || !providence.isEstablished()) continue;
            List<String> controllers = providence.getTerritoryControllers().stream()
                    .filter(id -> {
                        Civilisation civilisation = DataManager.getCivilisations().get(id);
                        return civilisation != null && civilisation.isActive();
                    })
                    .sorted().toList();
            for (int i = 0; i < controllers.size(); i++) {
                for (int j = i + 1; j < controllers.size(); j++) {
                    String a = controllers.get(i);
                    String b = controllers.get(j);
                    if (landDisputeExempt(a, b)) continue;
                    String key = providence.getId() + "|" + a + "|" + b;
                    current.add(key);
                    if (ACTIVE_LAND_DISPUTES.add(key)) {
                        Civilisation first = DataManager.getCivilisations().get(a);
                        Civilisation second = DataManager.getCivilisations().get(b);
                        if (first != null) first.modifyRelation(b, -LAND_DISPUTE_RELATION_PENALTY);
                        if (second != null) second.modifyRelation(a, -LAND_DISPUTE_RELATION_PENALTY);
                        System.out.println("Land dispute in " + providence.getName() + " between " + a + " and " + b + ".");
                    }
                }
            }
        }
        ACTIVE_LAND_DISPUTES.retainAll(current);
    }

    private static boolean landDisputeExempt(String firstId, String secondId) {
        if (firstId == null || secondId == null || firstId.equals(secondId)) return true;
        Civilisation first = DataManager.getCivilisations().get(firstId);
        Civilisation second = DataManager.getCivilisations().get(secondId);
        if (first == null || second == null) return true;
        boolean sameBloc = Objects.equals(first.getOverlordCivilisationId(), secondId)
                || Objects.equals(second.getOverlordCivilisationId(), firstId)
                || (first.getOverlordCivilisationId() != null
                    && Objects.equals(first.getOverlordCivilisationId(), second.getOverlordCivilisationId()));
        boolean allianceEquivalent = first.getRelation(secondId) >= 75 && second.getRelation(firstId) >= 75;
        return sameBloc || allianceEquivalent
                || WarSystem.getInstance().areAtWar(firstId, secondId)
                || WarSystem.getInstance().shareCommonEnemy(firstId, secondId);
    }

    private static boolean refreshLandmassAndSupply(Map<Long, String> landmassByChunk) {
        boolean changed = false;
        for (Providence providence : DataManager.getProvidences().values()) {
            if (providence == null || !providence.isEstablished() || providence.getCity() == null) continue;
            City city = providence.getCity();
            long cityChunk = WorldMapTracker.chunkKey(
                    Math.floorDiv(city.getBlockX(), WorldMapTracker.CHUNK_SIZE),
                    Math.floorDiv(city.getBlockZ(), WorldMapTracker.CHUNK_SIZE));
            String landmass = landmassByChunk.get(cityChunk);
            if (!Objects.equals(landmass, providence.getLandmassId())) {
                providence.setLandmassId(landmass);
                changed = true;
            }
        }

        for (Civilisation civilisation : DataManager.getCivilisations().values()) {
            if (!civilisation.isActive()) continue;
            List<Providence> owned = ownedProvidences(civilisation.getId()).stream()
                    .filter(p -> p.getCity() != null).toList();
            if (owned.isEmpty()) continue;

            Map<String, List<Providence>> byLandmass = new LinkedHashMap<>();
            for (Providence providence : owned) {
                String key = providence.getLandmassId();
                if (key == null) key = "unknown:" + providence.getId();
                byLandmass.computeIfAbsent(key, ignored -> new ArrayList<>()).add(providence);
            }

            for (List<Providence> landmassProvidences : byLandmass.values()) {
                Providence capitalProvince = chooseSupplyCapital(civilisation, landmassProvidences);
                City capital = capitalProvince.getCity();
                for (Providence providence : landmassProvidences) {
                    City city = providence.getCity();
                    boolean shouldCapital = providence == capitalProvince;
                    if (city.isSupplyCapital() != shouldCapital) {
                        city.setSupplyCapital(shouldCapital);
                        changed = true;
                    }
                    double distanceChunks = distanceBlocks(city.getBlockX(), city.getBlockZ(),
                            capital.getBlockX(), capital.getBlockZ()) / WorldMapTracker.CHUNK_SIZE;
                    double roadBonus = 1.0 + Math.min(0.50, civilisation.getRoadSegments() * 0.01);
                    double level = Math.min(1.0, roadBonus / (1.0 + distanceChunks / SUPPLY_DISTANCE_SCALE_CHUNKS));
                    if (!Objects.equals(capital.getControllerId(), civilisation.getId())) level *= 0.10;
                    if (!Objects.equals(city.getControllerId(), civilisation.getId())) level *= 0.35;
                    providence.setSupply(capital.getId(), level);
                }
            }
        }
        return changed;
    }

    private static Providence chooseSupplyCapital(Civilisation civilisation, List<Providence> providences) {
        Providence national = providences.stream()
                .filter(p -> p.getCity() != null && p.getCity().isNationalCapital()
                        && Objects.equals(civilisation.getId(), p.getCity().getControllerId()))
                .findFirst().orElse(null);
        if (national != null) return national;
        return providences.stream()
                .max(Comparator.comparingDouble(Providence::getDevelopment)
                        .thenComparing(Providence::getId))
                .orElse(providences.get(0));
    }

    public static List<Providence> ownedProvidences(String civilisationId) {
        if (civilisationId == null) return List.of();
        return DataManager.getProvidences().values().stream()
                .filter(Providence::isEstablished)
                .filter(p -> Objects.equals(civilisationId, p.getOwnerId()))
                .sorted(Comparator.comparing(Providence::getId))
                .toList();
    }

    public static List<Providence> territoriallyPresentProvidences(String civilisationId) {
        if (civilisationId == null) return List.of();
        return DataManager.getProvidences().values().stream()
                .filter(Providence::isEstablished)
                .filter(p -> p.territoryControlShare(civilisationId) > 0.0)
                .sorted(Comparator.comparing(Providence::getId)).toList();
    }

    public static Providence providenceContainingChunk(long chunkKey) {
        for (Providence providence : DataManager.getProvidences().values()) {
            if (providence != null && providence.isEstablished() && providence.getTerritoryChunkKeys().contains(chunkKey)) {
                return providence;
            }
        }
        return null;
    }

    public static Providence providenceContainingBlock(int blockX, int blockZ) {
        return providenceContainingChunk(WorldMapTracker.chunkKey(
                Math.floorDiv(blockX, WorldMapTracker.CHUNK_SIZE),
                Math.floorDiv(blockZ, WorldMapTracker.CHUNK_SIZE)));
    }

    /** Compatibility API: claiming countryside never steals somebody else's chunks. */
    public static boolean captureCountryside(Providence providence, String attackerId) {
        return providence != null && providence.claimAllUnclaimedTerritory(attackerId);
    }

    /** Compatibility API: city capture is command-post capture and keeps all chunk claims. */
    public static boolean captureCity(Providence providence, String attackerId) {
        return captureCommandPost(providence, attackerId);
    }

    private static Map<Long, String> computeLandmasses(WorldMapTracker.Snapshot snapshot) {
        Map<Long, WorldMapTracker.MapTile> land = new HashMap<>();
        for (WorldMapTracker.MapTile tile : snapshot.tiles()) {
            if (tile != null && tile.terrain() != null && tile.terrain().isLand()) {
                land.put(WorldMapTracker.chunkKey(tile.chunkX(), tile.chunkZ()), tile);
            }
        }

        Map<Long, String> result = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        for (Map.Entry<Long, WorldMapTracker.MapTile> entry : land.entrySet()) {
            long seedKey = entry.getKey();
            if (!visited.add(seedKey)) continue;
            ArrayDeque<WorldMapTracker.MapTile> queue = new ArrayDeque<>();
            queue.add(entry.getValue());
            List<Long> component = new ArrayList<>();
            long canonical = seedKey;

            while (!queue.isEmpty()) {
                WorldMapTracker.MapTile tile = queue.removeFirst();
                long key = WorldMapTracker.chunkKey(tile.chunkX(), tile.chunkZ());
                component.add(key);
                if (Long.compareUnsigned(key, canonical) < 0) canonical = key;
                int[][] neighbours = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] d : neighbours) {
                    long neighbourKey = WorldMapTracker.chunkKey(tile.chunkX() + d[0], tile.chunkZ() + d[1]);
                    WorldMapTracker.MapTile neighbour = land.get(neighbourKey);
                    if (neighbour != null && visited.add(neighbourKey)) queue.addLast(neighbour);
                }
            }
            String id = "landmass_" + Long.toUnsignedString(canonical, 16);
            for (long key : component) result.put(key, id);
        }
        return result;
    }

    private static double distanceBlocks(int ax, int az, int bx, int bz) {
        long dx = (long) ax - bx;
        long dz = (long) az - bz;
        return Math.sqrt(dx * dx + dz * dz);
    }
}


