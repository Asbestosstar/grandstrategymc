package com.asbestosstar.grandstrategy.common.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Permanent geographic providence on the discovered Minecraft map.
 *
 * A providence exists independently of colonisation. Its territoryChunkKeys are
 * geography; territoryControllers stores who physically controls each individual
 * chunk. The city/command-post owner is separate, so several countries can retain
 * territory in one providence even after another country captures its city.
 */
public class Providence {
    private String id;
    private String name;
    /** Legal/controller owner of the city command post; null means uncolonised. */
    private volatile String ownerId;
    private volatile double resistanceLevel;
    private double development;

    private boolean established;
    private int centreBlockX;
    private int centreBlockZ;
    private List<Long> territoryChunkKeys = new ArrayList<>();
    /** Permanent peacetime/legal owner of each geographic chunk. */
    private Map<Long, String> territoryOwners = new LinkedHashMap<>();
    /** Current jurisdiction. During war this may temporarily differ from territoryOwners. */
    private Map<Long, String> territoryControllers = new LinkedHashMap<>();
    private City city;
    private String landmassId;

    /** Legacy v1-v7 field retained only for save migration. */
    private String countrysideControllerId;
    private String supplySourceCityId;
    private double supplyLevel;

    public Providence() {
        normaliseAfterLoad();
    }

    public Providence(String id, String name, String ownerId, double resistanceLevel, double development) {
        this.id = id;
        this.name = name;
        this.ownerId = ownerId;
        this.resistanceLevel = resistanceLevel;
        this.development = development;
        this.countrysideControllerId = ownerId;
        normaliseAfterLoad();
    }

    public Providence(String id, String name, String ownerId, double resistanceLevel, double development,
                      City city) {
        this(id, name, ownerId, resistanceLevel, development);
        this.city = city;
        if (city != null && city.getControllerId() == null) city.setControllerId(ownerId);
    }

    public synchronized void normaliseAfterLoad() {
        if (territoryChunkKeys == null) territoryChunkKeys = new ArrayList<>();
        if (territoryOwners == null) territoryOwners = new LinkedHashMap<>();
        if (territoryControllers == null) territoryControllers = new LinkedHashMap<>();
        resistanceLevel = clamp(resistanceLevel, 0.0, 1.0);
        development = Math.max(0.0, development);
        supplyLevel = clamp(supplyLevel, 0.0, 1.0);

        // Old saves represented all countryside with one controller. Preserve that
        // real territorial history by converting it into per-chunk claims once.
        if (territoryControllers.isEmpty()) {
            String legacy = countrysideControllerId;
            if ((legacy == null || legacy.isBlank()) && ownerId != null && !ownerId.isBlank()) legacy = ownerId;
            if (legacy != null && !legacy.isBlank()) {
                for (Long key : territoryChunkKeys) if (key != null) territoryControllers.put(key, legacy);
            }
        }
        // v6.16 and older had only one per-chunk map. Treat that saved controller as
        // the legal owner when migrating; wartime jurisdiction did not yet exist.
        if (territoryOwners.isEmpty() && !territoryControllers.isEmpty()) {
            territoryOwners.putAll(territoryControllers);
        }
        territoryOwners.keySet().removeIf(key -> key == null || !territoryChunkKeys.contains(key));
        territoryControllers.keySet().removeIf(key -> key == null || !territoryChunkKeys.contains(key));

        // Any legally-owned chunk needs a jurisdiction entry. Unowned chunks remain absent.
        for (Map.Entry<Long, String> entry : territoryOwners.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                territoryControllers.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }

        if (city != null && city.getControllerId() == null && ownerId != null) city.setControllerId(ownerId);
        if (city != null && !established) established = true;
    }

    public synchronized void establish(int centreBlockX, int centreBlockZ, List<Long> chunkKeys,
                                       City city, String landmassId) {
        this.centreBlockX = centreBlockX;
        this.centreBlockZ = centreBlockZ;
        this.territoryChunkKeys = chunkKeys == null ? new ArrayList<>() : new ArrayList<>(chunkKeys);
        this.city = city;
        this.landmassId = landmassId;
        this.established = true;
        if (territoryOwners == null) territoryOwners = new LinkedHashMap<>();
        if (territoryControllers == null) territoryControllers = new LinkedHashMap<>();
        territoryOwners.keySet().removeIf(key -> !this.territoryChunkKeys.contains(key));
        territoryControllers.keySet().removeIf(key -> !this.territoryChunkKeys.contains(key));
        if (this.city != null && this.city.getControllerId() == null && ownerId != null) {
            this.city.setControllerId(ownerId);
        }
    }

    /** Fraction of geographic chunks in this providence physically controlled by a country. */
    public synchronized double territoryControlShare(String civilisationId) {
        if (civilisationId == null || civilisationId.isBlank() || territoryChunkKeys.isEmpty()) return 0.0;
        int controlled = 0;
        for (Long key : territoryChunkKeys) {
            if (key != null && Objects.equals(civilisationId, territoryControllers.get(key))) controlled++;
        }
        return (double) controlled / (double) territoryChunkKeys.size();
    }

    /** Backwards-compatible alias; city ownership is intentionally not part of the percentage. */
    public synchronized double controlShare(String civilisationId) {
        return territoryControlShare(civilisationId);
    }

    public synchronized String getTerritoryController(long chunkKey) {
        return territoryControllers.get(chunkKey);
    }

    /** Permanent legal owner, independent of temporary wartime jurisdiction. */
    public synchronized String getTerritoryOwner(long chunkKey) {
        return territoryOwners.get(chunkKey);
    }

    /**
     * Changes current chunk jurisdiction without changing legal ownership. Used only
     * by physical wartime occupation and recapture.
     */
    public synchronized boolean setWartimeTerritoryController(long chunkKey, String civilisationId) {
        if (civilisationId == null || civilisationId.isBlank() || !territoryChunkKeys.contains(chunkKey)) return false;
        String before = territoryControllers.get(chunkKey);
        if (Objects.equals(before, civilisationId)) return false;
        territoryControllers.put(chunkKey, civilisationId);
        return true;
    }

    /** Restores temporary jurisdiction to the permanent legal owner for one chunk. */
    public synchronized boolean restoreTerritoryJurisdiction(long chunkKey) {
        if (!territoryChunkKeys.contains(chunkKey)) return false;
        String legalOwner = territoryOwners.get(chunkKey);
        String before = territoryControllers.get(chunkKey);
        if (Objects.equals(before, legalOwner)) return false;
        if (legalOwner == null || legalOwner.isBlank()) territoryControllers.remove(chunkKey);
        else territoryControllers.put(chunkKey, legalOwner);
        return true;
    }

    /**
     * Ends only occupation between these two belligerents. Jurisdiction belonging to
     * an unrelated simultaneous war is deliberately left untouched.
     */
    public synchronized boolean restoreTerritoryJurisdictionBetween(String firstId, String secondId) {
        if (firstId == null || secondId == null) return false;
        boolean changed = false;
        for (Long key : territoryChunkKeys) {
            if (key == null) continue;
            String legal = territoryOwners.get(key);
            String current = territoryControllers.get(key);
            boolean opposedOccupation = (Objects.equals(legal, firstId) && Objects.equals(current, secondId))
                    || (Objects.equals(legal, secondId) && Objects.equals(current, firstId));
            if (!opposedOccupation) continue;
            if (legal == null || legal.isBlank()) territoryControllers.remove(key);
            else territoryControllers.put(key, legal);
            changed = true;
        }
        return changed;
    }

    /** True when this country currently administers land legally owned by somebody else. */
    public synchronized boolean hasWartimeOccupationBy(String civilisationId) {
        if (civilisationId == null || civilisationId.isBlank()) return false;
        for (Long key : territoryChunkKeys) {
            if (key == null) continue;
            if (Objects.equals(civilisationId, territoryControllers.get(key))
                    && !Objects.equals(civilisationId, territoryOwners.get(key))) return true;
        }
        return false;
    }

    /** Claims an unclaimed geographic chunk. Existing foreign territory is never erased here. */
    public synchronized boolean claimTerritoryChunk(long chunkKey, String civilisationId) {
        if (civilisationId == null || civilisationId.isBlank() || !territoryChunkKeys.contains(chunkKey)) return false;
        String currentOwner = territoryOwners.get(chunkKey);
        if (Objects.equals(currentOwner, civilisationId)) return false;
        if (currentOwner != null && !currentOwner.isBlank()) return false;
        territoryOwners.put(chunkKey, civilisationId);
        territoryControllers.put(chunkKey, civilisationId);
        return true;
    }

    /** Starting/abandoned providence colonisation: claim all currently unclaimed chunks only. */
    public synchronized boolean claimAllUnclaimedTerritory(String civilisationId) {
        if (civilisationId == null || civilisationId.isBlank()) return false;
        boolean changed = false;
        for (Long key : territoryChunkKeys) {
            if (key == null) continue;
            String currentOwner = territoryOwners.get(key);
            if (currentOwner == null || currentOwner.isBlank()) {
                territoryOwners.put(key, civilisationId);
                territoryControllers.put(key, civilisationId);
                changed = true;
            }
        }
        return changed;
    }

    public synchronized void clearTerritoryClaims(String civilisationId) {
        if (civilisationId == null) return;
        for (Long key : new ArrayList<>(territoryChunkKeys)) {
            if (!Objects.equals(civilisationId, territoryOwners.get(key))) continue;
            territoryOwners.remove(key);
            if (Objects.equals(civilisationId, territoryControllers.get(key))) territoryControllers.remove(key);
        }
        // A destroyed country cannot keep temporary jurisdiction over somebody else's land.
        for (Long key : new ArrayList<>(territoryControllers.keySet())) {
            if (!Objects.equals(civilisationId, territoryControllers.get(key))) continue;
            String legal = territoryOwners.get(key);
            if (legal == null || legal.isBlank()) territoryControllers.remove(key);
            else territoryControllers.put(key, legal);
        }
    }

    /** Permanent transfer used by a signed treaty or capitulation. */
    public synchronized void transferTerritoryClaims(String fromCivilisationId, String toCivilisationId) {
        if (fromCivilisationId == null) return;
        for (Long key : territoryChunkKeys) {
            if (key == null || !Objects.equals(fromCivilisationId, territoryOwners.get(key))) continue;
            if (toCivilisationId == null || toCivilisationId.isBlank()) {
                territoryOwners.remove(key);
                territoryControllers.remove(key);
            } else {
                territoryOwners.put(key, toCivilisationId);
                territoryControllers.put(key, toCivilisationId);
            }
        }
        // Do not leave the transferred-away country temporarily administering foreign land.
        for (Long key : new ArrayList<>(territoryControllers.keySet())) {
            if (!Objects.equals(fromCivilisationId, territoryControllers.get(key))) continue;
            String legal = territoryOwners.get(key);
            if (legal == null || legal.isBlank()) territoryControllers.remove(key);
            else territoryControllers.put(key, legal);
        }
    }

    public synchronized Set<String> getTerritoryControllers() {
        Set<String> result = new LinkedHashSet<>();
        for (String controller : territoryControllers.values()) {
            if (controller != null && !controller.isBlank()) result.add(controller);
        }
        return Set.copyOf(result);
    }

    public synchronized Map<Long, String> getTerritoryControllerMap() {
        return Map.copyOf(territoryControllers);
    }

    public synchronized Map<Long, String> getTerritoryOwnerMap() {
        return Map.copyOf(territoryOwners);
    }

    public synchronized boolean isUncolonised() {
        return ownerId == null || ownerId.isBlank();
    }

    /** Capturing the command post changes the city holder, not anybody's chunk claims. */
    public synchronized boolean captureCommandPost(String controllerId) {
        String before = ownerId;
        ownerId = controllerId;
        if (city != null) city.setControllerId(controllerId);
        if (!Objects.equals(before, controllerId) && before != null) {
            resistanceLevel = Math.max(resistanceLevel, 0.35);
        }
        return !Objects.equals(before, controllerId);
    }

    /** Old API: countryside capture now means claiming only currently unclaimed chunks. */
    public synchronized void captureCountryside(String controllerId) {
        claimAllUnclaimedTerritory(controllerId);
        countrysideControllerId = controllerId;
    }

    /** Old API: city capture maps directly to command-post capture. */
    public synchronized void captureCity(String controllerId) {
        captureCommandPost(controllerId);
    }

    /** Ownership no longer auto-flips from a weighted city/countryside calculation. */
    public synchronized boolean resolveOwnershipFromControl() {
        return false;
    }

    public synchronized void setSupply(String sourceCityId, double level) {
        this.supplySourceCityId = sourceCityId;
        this.supplyLevel = clamp(level, 0.0, 1.0);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getOwnerId() { return ownerId; }
    public double getResistanceLevel() { return resistanceLevel; }
    public double getDevelopment() { return development; }
    public boolean isEstablished() { return established; }
    public int getCentreBlockX() { return centreBlockX; }
    public int getCentreBlockZ() { return centreBlockZ; }
    public synchronized List<Long> getTerritoryChunkKeys() { return List.copyOf(territoryChunkKeys); }
    public City getCity() { return city; }
    public String getLandmassId() { return landmassId; }
    public String getCountrysideControllerId() { return countrysideControllerId; }
    public String getSupplySourceCityId() { return supplySourceCityId; }
    public double getSupplyLevel() { return supplyLevel; }

    public synchronized void setName(String name) {
        if (name != null && !name.isBlank()) this.name = name;
    }

    public synchronized void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
        if (city != null && city.getControllerId() == null && ownerId != null) city.setControllerId(ownerId);
    }

    public void setResistanceLevel(double resistanceLevel) {
        this.resistanceLevel = clamp(resistanceLevel, 0.0, 1.0);
    }

    public void setDevelopment(double development) {
        this.development = Math.max(0.0, development);
    }

    public void setLandmassId(String landmassId) { this.landmassId = landmassId; }
    public void setCountrysideControllerId(String controllerId) { this.countrysideControllerId = controllerId; }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public String toString() {
        return "Providence{id='" + id + "', name='" + name + "', owner='" + ownerId
                + "', city='" + (city == null ? "" : city.getName()) + "'}";
    }
}


