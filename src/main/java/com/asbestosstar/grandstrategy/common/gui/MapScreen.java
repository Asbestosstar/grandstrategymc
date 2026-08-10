package com.asbestosstar.grandstrategy.common.gui;

import com.asbestosstar.grandstrategy.common.data.City;
import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.Providence;
import com.asbestosstar.grandstrategy.common.data.ResourceType;
import com.asbestosstar.grandstrategy.common.world.PhysicalVillagerSystem;
import com.asbestosstar.grandstrategy.common.world.WorldMapTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.Locale;

/**
 * Main Grand Strategy screen.
 *
 * The map uses actual Minecraft Overworld X/Z coordinates. Only chunks recorded by
 * WorldMapTracker are rendered; unexplored Minecraft terrain does not appear.
 */
public class MapScreen extends StrategyScreen {
    private static final double MIN_BLOCKS_PER_PIXEL = 0.40;
    private static final double MAX_BLOCKS_PER_PIXEL = 2_048.0;

    private static final int UNKNOWN = 0xFF101820;
    private static final int WATER = 0xFF214C70;
    private static final int COAST = 0xFF6D8050;
    private static final int LAND = 0xFF42633F;
    private static final int HIGHLAND = 0xFF746C58;
    private static final int MAP_BORDER = 0xFF8998A6;

    private final List<MarkerBounds> countryMarkers = new ArrayList<>();
    private String statusMessage = "";

    private double centreBlockX;
    private double centreBlockZ;
    private double blocksPerPixel = 4.0;
    private boolean viewInitialised;
    private boolean draggingMap;
    private boolean awaitingSoldierDestination;
    /** Null, FARM or FACTORY while the next/continued map clicks designate districts. */
    private String workZonePlacementType;

    private int mapX;
    private int mapY;
    private int mapWidth;
    private int mapHeight;

    public MapScreen() {
        this(null);
    }

    public MapScreen(Screen parent) {
        super("Grand Strategy", parent);
    }

    /** Opens the map directly in a district-placement mode, used by the factory manager. */
    public MapScreen(Screen parent, String placementType) {
        super("Grand Strategy", parent);
        this.workZonePlacementType = placementType;
        if (placementType != null && placementType.startsWith("FACTORY:")) {
            this.statusMessage = "Factory district mode: click discovered owned/uncolonised land; right-click to finish.";
        }
    }

    private MapScreen(Screen parent, double centreBlockX, double centreBlockZ,
                      double blocksPerPixel, boolean viewInitialised, String placementType, String statusMessage) {
        super("Grand Strategy", parent);
        this.centreBlockX = centreBlockX;
        this.centreBlockZ = centreBlockZ;
        this.blocksPerPixel = blocksPerPixel;
        this.viewInitialised = viewInitialised;
        this.workZonePlacementType = placementType;
        this.statusMessage = statusMessage == null ? "" : statusMessage;
    }

    @Override
    protected void init() {
        beginIconLayout();
        StrategyClientContext.requestSync();
        layoutMap();
        ensureInitialView();

        Civilisation playerCountry = StrategyClientContext.currentPlayerCountry();

        int x = 6;
        int y = 6;
        // PP and population remain numerical because they are status readouts as
        // well as buttons. Compact navigation/action controls below use icons.
        String pp = playerCountry == null ? "PP --" : "PP " + (int) playerCountry.getPoliticalPower();
        this.addRenderableWidget(Button.builder(Component.literal(pp), button ->
                        this.minecraft.setScreen(new EconomyScreen(this, StrategyClientContext.currentPlayerCountry())))
                .bounds(x, y, 70, 20).build());
        x += 72;

        String pop = playerCountry == null ? "Pop --" : "Pop " + playerCountry.getPopulation();
        this.addRenderableWidget(Button.builder(Component.literal(pop), button ->
                        this.minecraft.setScreen(new EconomyScreen(this, StrategyClientContext.currentPlayerCountry())))
                .bounds(x, y, 72, 20).build());
        x += 74;

        int navY = y;
        int navX = x + 4;
        if (playerCountry == null) {
            String ign = StrategyClientContext.currentIgn();
            this.addRenderableWidget(Button.builder(Component.literal("Create Country: " + ign), button -> {
                        boolean queued = StrategyClientContext.requestCreateCountry();
                        statusMessage = queued
                                ? "Creating country at your server-authoritative Minecraft position..."
                                : "Not connected to a Grand Strategy server.";
                        if (queued) scheduleRefresh();
                    }).bounds(navX, navY, 178, 20).build());
            navX += 182;
        } else {
            addIconButton(UiIcon.ECONOMY, "Economy & population", navX, navY, button ->
                    this.minecraft.setScreen(new EconomyScreen(this, playerCountry)));
            navX += 24;
        }

        addIconButton(UiIcon.DIPLOMACY, "Diplomacy", navX, navY, button ->
                this.minecraft.setScreen(new DiplomacyScreen(this)));
        navX += 24;

        String spiritsTip = playerCountry == null
                ? "National spirits (0)"
                : "National spirits (" + playerCountry.getNationalSpiritIds().size() + ")";
        addIconButton(UiIcon.SPIRITS, spiritsTip, navX, navY, button ->
                this.minecraft.setScreen(new NationalSpiritsScreen(this)));
        navX += 24;

        addIconButton(UiIcon.FOCUS, "National focus tree", navX, navY, button ->
                this.minecraft.setScreen(new FocusTreeScreen(this)));
        navX += 24;

        boolean hasPendingEvent = playerCountry != null && playerCountry.hasPendingEvent();
        String eventTip = hasPendingEvent
                ? "Events - decision waiting"
                : "Events & decisions";
        addIconButton(UiIcon.EVENTS, eventTip, navX, navY, 20, 20,
                hasPendingEvent ? WARNING_TEXT : 0xFFD7DEE7,
                hasPendingEvent ? 0xFFFFFFB0 : 0xFFFFFFFF,
                hasPendingEvent ? 0xFFE0B422 : 0,
                button -> this.minecraft.setScreen(new EventsScreen(this)));
        navX += 24;

        if (playerCountry != null) {
            addIconButton(UiIcon.FARM_ZONE,
                    "Designate farm district - click owned or uncolonised land", navX, navY, button -> {
                        awaitingSoldierDestination = false;
                        workZonePlacementType = "FARM".equals(workZonePlacementType) ? null : "FARM";
                        statusMessage = workZonePlacementType == null
                                ? "Farm-zone placement cancelled."
                                : "Farm-zone mode: click owned or uncolonised discovered land; right-click to finish.";
                    });
            navX += 24;
            addIconButton(UiIcon.FACTORY_ZONE,
                    "Factories, districts & production", navX, navY, button ->
                            this.minecraft.setScreen(new FactoryScreen(this)));
            navX += 24;
        }

        // Army controls stay on their own row, but are now icon-only. Hovering
        // explains the command without consuming horizontal space.
        if (playerCountry != null) {
            int armyY = 30;
            addIconButton(UiIcon.ARMY_AUTO, "Army automatic control", 6, armyY, button -> {
                boolean queued = StrategyClientContext.requestSoldierAutomatic();
                awaitingSoldierDestination = false;
                workZonePlacementType = null;
                statusMessage = queued
                        ? "Army switched to automatic wartime movement and combat."
                        : "Not connected to a Grand Strategy server.";
                if (queued) scheduleRefresh();
            });
            addIconButton(UiIcon.MOVE_ARMY, "Move army - then click the map", 30, armyY, button -> {
                awaitingSoldierDestination = true;
                workZonePlacementType = null;
                statusMessage = "Click a discovered point on the map to order your soldiers there.";
            });
        }

        // Loader-independent vanilla map navigation controls. These are deliberately
        // only 20 px wide so the map header remains uncluttered at small GUI scales.
        int controlsWidth = 20 * 4 + 4 * 3;
        int controlsX = Math.max(navX + 4, this.width - 6 - controlsWidth);
        addIconButton(UiIcon.ZOOM_OUT, "Zoom out", controlsX, navY, button ->
                zoomAtMapCentre(1.35));
        controlsX += 24;
        addIconButton(UiIcon.ZOOM_IN, "Zoom in", controlsX, navY, button ->
                zoomAtMapCentre(1.0 / 1.35));
        controlsX += 24;
        addIconButton(UiIcon.PLAYER, "Centre on player", controlsX, navY, button ->
                centreOnPlayer());
        controlsX += 24;
        addIconButton(UiIcon.FIT, "Fit discovered map", controlsX, navY, button ->
                fitToDiscovered());
    }

    private void layoutMap() {
        mapX = 18;
        mapY = 102;
        mapWidth = Math.max(120, this.width - 36);
        mapHeight = Math.max(90, this.height - 128);
    }

    private void ensureInitialView() {
        if (viewInitialised) return;
        fitToDiscovered();
        viewInitialised = true;
    }

    private void fitToDiscovered() {
        WorldMapTracker.Snapshot snapshot = StrategyClientContext.mapSnapshot();
        if (snapshot.isEmpty()) {
            Integer playerX = StrategyClientContext.currentPlayerBlockX();
            Integer playerZ = StrategyClientContext.currentPlayerBlockZ();
            centreBlockX = playerX == null ? 0.0 : playerX;
            centreBlockZ = playerZ == null ? 0.0 : playerZ;
            blocksPerPixel = 2.0;
            viewInitialised = true;
            return;
        }

        double spanX = Math.max(64.0, snapshot.maxBlockX() - snapshot.minBlockX());
        double spanZ = Math.max(64.0, snapshot.maxBlockZ() - snapshot.minBlockZ());
        centreBlockX = (snapshot.minBlockX() + snapshot.maxBlockX()) * 0.5;
        centreBlockZ = (snapshot.minBlockZ() + snapshot.maxBlockZ()) * 0.5;
        double usableWidth = Math.max(40.0, mapWidth - 24.0);
        double usableHeight = Math.max(40.0, mapHeight - 24.0);
        blocksPerPixel = clamp(Math.max(spanX / usableWidth, spanZ / usableHeight) * 1.08,
                MIN_BLOCKS_PER_PIXEL, MAX_BLOCKS_PER_PIXEL);
        viewInitialised = true;
    }

    private void centreOnPlayer() {
        Integer playerX = StrategyClientContext.currentPlayerBlockX();
        Integer playerZ = StrategyClientContext.currentPlayerBlockZ();
        if (playerX != null && playerZ != null) {
            centreBlockX = playerX;
            centreBlockZ = playerZ;
            viewInitialised = true;
            statusMessage = "Centred on player X=" + playerX + " Z=" + playerZ + ".";
        } else {
            statusMessage = "Player marker is available on the Overworld map.";
        }
    }

    @Override
    protected void renderCustom(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        layoutMap();
        ensureInitialView();

        String year = StrategyClientContext.formattedYear();
        double gsRate = StrategyClientContext.gsDaysPerMinecraftDay();
        Civilisation player = StrategyClientContext.currentPlayerCountry();
        String countryName = player == null ? "No country created"
                : player.getName() + " [" + player.getId() + "] | Army " + player.getSoldierControlMode();
        graphics.text(this.font, "Grand Strategy | " + year + " | " + formatRate(gsRate) + " | " + countryName,
                18, 80, TEXT, true);
        if (player != null && player.hasPendingEvent()) {
            graphics.text(this.font, "EVENT DECISION WAITING - open Events to choose an outcome",
                    18, 91, WARNING_TEXT, true);
        }

        graphics.fill(mapX, mapY, mapX + mapWidth, mapY + mapHeight, UNKNOWN);
        graphics.outline(mapX, mapY, mapWidth, mapHeight, MAP_BORDER);

        WorldMapTracker.Snapshot snapshot = StrategyClientContext.mapSnapshot();
        renderDiscoveredTerrain(graphics, snapshot);
        renderProvidenceTerritories(graphics, snapshot);
        renderWorkZones(graphics, player);
        renderWorkZonePlacementPreview(graphics, mouseX, mouseY);
        renderVillagers(graphics, snapshot);
        renderCountries(graphics, snapshot, player);
        renderProvidenceCities(graphics, snapshot);
        renderPlayerMarker(graphics);
        renderArmyOrder(graphics, snapshot, player);

        int infoY = mapY + 5;
        String discovery = snapshot.isEmpty()
                ? "No Overworld land discovered yet"
                : "Discovered " + snapshot.tiles().size() + " chunks";
        graphics.text(this.font,
                discovery + " | centre X=" + (int) centreBlockX + " Z=" + (int) centreBlockZ
                        + " | " + formatScale(),
                mapX + 6, infoY, 0xFFE4EBF2, true);
        graphics.text(this.font,
                "Drag map to pan | wheel or +/- zoom | Farm/Factory icons designate districts | Move Army then click map = order",
                mapX + 6, infoY + 11, 0xFFC8D2DC, true);

        int legendY = mapY + mapHeight - 12;
        graphics.text(this.font,
                "Grey line = providence   shaded box = designated district   person = worker   square = command post   cross = player",
                mapX + 6, legendY, 0xFFE6EDF3, true);

        if (!statusMessage.isBlank()) {
            graphics.text(this.font, statusMessage, 220, 58,
                    statusMessage.toLowerCase(Locale.ROOT).contains("not running") ? BAD_TEXT : WARNING_TEXT,
                    true);
        }
    }

    private void renderDiscoveredTerrain(GuiGraphicsExtractor graphics, WorldMapTracker.Snapshot snapshot) {
        if (snapshot.isEmpty()) return;

        double viewMinX = centreBlockX - mapWidth * blocksPerPixel * 0.5;
        double viewMaxX = centreBlockX + mapWidth * blocksPerPixel * 0.5;
        double viewMinZ = centreBlockZ - mapHeight * blocksPerPixel * 0.5;
        double viewMaxZ = centreBlockZ + mapHeight * blocksPerPixel * 0.5;

        for (WorldMapTracker.MapTile tile : snapshot.tiles()) {
            double tileMinX = tile.minBlockX();
            double tileMaxX = tileMinX + WorldMapTracker.CHUNK_SIZE;
            double tileMinZ = tile.minBlockZ();
            double tileMaxZ = tileMinZ + WorldMapTracker.CHUNK_SIZE;
            if (tileMaxX < viewMinX || tileMinX > viewMaxX
                    || tileMaxZ < viewMinZ || tileMinZ > viewMaxZ) {
                continue;
            }

            int x1 = clampInt(worldToScreenX(tileMinX), mapX, mapX + mapWidth);
            int x2 = clampInt(worldToScreenX(tileMaxX), mapX, mapX + mapWidth);
            int y1 = clampInt(worldToScreenY(tileMinZ), mapY, mapY + mapHeight);
            int y2 = clampInt(worldToScreenY(tileMaxZ), mapY, mapY + mapHeight);
            if (x2 <= x1) x2 = Math.min(mapX + mapWidth, x1 + 1);
            if (y2 <= y1) y2 = Math.min(mapY + mapHeight, y1 + 1);
            if (x2 <= mapX || y2 <= mapY || x1 >= mapX + mapWidth || y1 >= mapY + mapHeight) {
                continue;
            }
            graphics.fill(x1, y1, x2, y2, terrainColour(tile.terrain()));
        }
    }


    private void renderProvidenceTerritories(GuiGraphicsExtractor graphics, WorldMapTracker.Snapshot snapshot) {
        if (snapshot.isEmpty()) return;

        // Providence geometry is permanent and visible even when nobody has
        // colonised it. Country colour is applied per controlled chunk rather than
        // per providence, so several countries can visibly occupy one providence.
        Map<Long, Providence> byChunk = new HashMap<>();
        for (Providence providence : StrategyClientContext.providences()) {
            if (providence == null || !providence.isEstablished()) continue;
            for (long key : providence.getTerritoryChunkKeys()) byChunk.put(key, providence);
        }

        for (Map.Entry<Long, Providence> entry : byChunk.entrySet()) {
            long key = entry.getKey();
            Providence providence = entry.getValue();
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            double tileMinX = (double) chunkX * WorldMapTracker.CHUNK_SIZE;
            double tileMinZ = (double) chunkZ * WorldMapTracker.CHUNK_SIZE;
            int x1 = worldToScreenX(tileMinX);
            int x2 = worldToScreenX(tileMinX + WorldMapTracker.CHUNK_SIZE);
            int y1 = worldToScreenY(tileMinZ);
            int y2 = worldToScreenY(tileMinZ + WorldMapTracker.CHUNK_SIZE);
            if (x2 < mapX || x1 > mapX + mapWidth || y2 < mapY || y1 > mapY + mapHeight) continue;

            x1 = clampInt(x1, mapX, mapX + mapWidth);
            x2 = clampInt(x2, mapX, mapX + mapWidth);
            y1 = clampInt(y1, mapY, mapY + mapHeight);
            y2 = clampInt(y2, mapY, mapY + mapHeight);
            if (x2 <= x1) x2 = Math.min(mapX + mapWidth, x1 + 1);
            if (y2 <= y1) y2 = Math.min(mapY + mapHeight, y1 + 1);

            String controllerId = providence.getTerritoryController(key);
            Civilisation controller = StrategyClientContext.getCivilisation(controllerId);
            if (controller != null && controller.isActive()) {
                int border = controller.getBorderColourArgb();
                int tint = 0x52000000 | (border & 0x00FFFFFF);
                graphics.fill(x1, y1, x2, y2, tint);
            }

            drawTerritoryBorderSide(graphics, byChunk, providence, controllerId, chunkX, chunkZ, -1, 0,
                    x1, y1, x1 + 2, y2);
            drawTerritoryBorderSide(graphics, byChunk, providence, controllerId, chunkX, chunkZ, 1, 0,
                    x2 - 2, y1, x2, y2);
            drawTerritoryBorderSide(graphics, byChunk, providence, controllerId, chunkX, chunkZ, 0, -1,
                    x1, y1, x2, y1 + 2);
            drawTerritoryBorderSide(graphics, byChunk, providence, controllerId, chunkX, chunkZ, 0, 1,
                    x1, y2 - 2, x2, y2);
        }
    }

    private void drawTerritoryBorderSide(GuiGraphicsExtractor graphics, Map<Long, Providence> byChunk,
                                         Providence providence, String controllerId, int chunkX, int chunkZ,
                                         int dx, int dz, int x1, int y1, int x2, int y2) {
        long neighbourKey = WorldMapTracker.chunkKey(chunkX + dx, chunkZ + dz);
        Providence neighbour = byChunk.get(neighbourKey);
        boolean providenceBoundary = neighbour == null || !Objects.equals(neighbour.getId(), providence.getId());
        if (providenceBoundary) {
            graphics.fill(x1, y1, x2, y2, 0xFF9AA4AE);
            return;
        }

        String neighbourController = neighbour.getTerritoryController(neighbourKey);
        if (!Objects.equals(controllerId, neighbourController) && controllerId != null) {
            Civilisation controller = StrategyClientContext.getCivilisation(controllerId);
            if (controller != null && controller.isActive()) {
                graphics.fill(x1, y1, x2, y2, controller.getBorderColourArgb());
            }
        }
    }

    private void renderProvidenceCities(GuiGraphicsExtractor graphics, WorldMapTracker.Snapshot snapshot) {
        for (Providence providence : StrategyClientContext.providences()) {
            if (providence == null || !providence.isEstablished() || providence.getCity() == null) continue;
            City city = providence.getCity();
            if (!snapshot.isDiscoveredBlock(city.getBlockX(), city.getBlockZ())) continue;
            int cx = worldToScreenX(city.getBlockX());
            int cy = worldToScreenY(city.getBlockZ());
            if (!insideMap(cx, cy)) continue;

            Civilisation controller = StrategyClientContext.getCivilisation(city.getControllerId());
            Civilisation owner = StrategyClientContext.getCivilisation(providence.getOwnerId());
            int colour = controller != null ? controller.getBorderColourArgb()
                    : owner != null ? owner.getBorderColourArgb() : 0xFFDDDDDD;
            int radius = city.isNationalCapital() ? 4 : 3;
            graphics.fill(cx - radius, cy - radius, cx + radius + 1, cy + radius + 1, colour);
            graphics.outline(cx - radius - 1, cy - radius - 1, radius * 2 + 3, radius * 2 + 3, 0xFF111111);
            if (city.isSupplyCapital()) {
                graphics.outline(cx - radius - 3, cy - radius - 3, radius * 2 + 7, radius * 2 + 7, 0xFFFFFFFF);
            }

            if (blocksPerPixel <= 10.0) {
                String label = city.getName() + " [CP]" + (city.isSupplyCapital() ? " [SUPPLY]" : "");
                if (cx + 7 + this.font.width(label) < mapX + mapWidth) {
                    graphics.text(this.font, label, cx + 7, cy - 4, 0xFFF1F3F5, true);
                }
            }
        }
    }

    private void renderWorkZones(GuiGraphicsExtractor graphics, Civilisation player) {
        if (player == null) return;
        for (PhysicalVillagerSystem.WorkZoneMapMarker zone : StrategyClientContext.workZones()) {
            if (zone == null || !Objects.equals(player.getId(), zone.civilisationId())) continue;
            int x1 = worldToScreenX(zone.minX());
            int x2 = worldToScreenX(zone.maxX() + 1.0);
            int y1 = worldToScreenY(zone.minZ());
            int y2 = worldToScreenY(zone.maxZ() + 1.0);
            if (x2 < mapX || x1 > mapX + mapWidth || y2 < mapY || y1 > mapY + mapHeight) continue;
            x1 = clampInt(x1, mapX, mapX + mapWidth);
            x2 = clampInt(x2, mapX, mapX + mapWidth);
            y1 = clampInt(y1, mapY, mapY + mapHeight);
            y2 = clampInt(y2, mapY, mapY + mapHeight);
            if (x2 <= x1) x2 = Math.min(mapX + mapWidth, x1 + 2);
            if (y2 <= y1) y2 = Math.min(mapY + mapHeight, y1 + 2);

            boolean farm = "FARM".equals(zone.type());
            int fill = farm ? 0x5538C95B : 0x557D8791;
            int border = farm ? 0xFF8BF59D : 0xFFFFC45C;
            graphics.fill(x1, y1, x2, y2, fill);
            graphics.outline(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1), border);
            if (blocksPerPixel <= 5.0) {
                String label = farm ? "FARM" : "FACTORY";
                if (zone.assignedWorkerUuid() != null) label += " *";
                graphics.text(this.font, label, x1 + 2, y1 + 2, border, true);
            }
        }
    }

    private void renderWorkZonePlacementPreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (workZonePlacementType == null || !insideMap(mouseX, mouseY)) return;
        int worldX = (int) Math.round(screenToWorldX(mouseX));
        int worldZ = (int) Math.round(screenToWorldZ(mouseY));
        boolean farmPlacement = workZonePlacementType.startsWith("FARM");
        int width = farmPlacement ? 52 : 11;
        int depth = farmPlacement ? 52 : 9;
        int minX = worldX - width / 2;
        int maxX = minX + width;
        int minZ = worldZ - depth / 2;
        int maxZ = minZ + depth;
        int x1 = clampInt(worldToScreenX(minX), mapX, mapX + mapWidth);
        int x2 = clampInt(worldToScreenX(maxX), mapX, mapX + mapWidth);
        int y1 = clampInt(worldToScreenY(minZ), mapY, mapY + mapHeight);
        int y2 = clampInt(worldToScreenY(maxZ), mapY, mapY + mapHeight);
        if (x2 <= x1) x2 = Math.min(mapX + mapWidth, x1 + 2);
        if (y2 <= y1) y2 = Math.min(mapY + mapHeight, y1 + 2);
        int colour = farmPlacement ? 0xFF8BF59D : 0xFFFFC45C;
        graphics.outline(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1), colour);
    }

    private void renderVillagers(GuiGraphicsExtractor graphics, WorldMapTracker.Snapshot snapshot) {
        if (snapshot.isEmpty()) return;
        Set<Long> usedIconCentres = new HashSet<>();
        for (PhysicalVillagerSystem.VillagerMapMarker marker : StrategyClientContext.villagers()) {
            if (marker == null || !snapshot.isDiscoveredBlock(marker.blockX(), marker.blockZ())) continue;
            int projectedX = worldToScreenX(marker.blockX());
            int projectedY = worldToScreenY(marker.blockZ());
            if (!insideMap(projectedX, projectedY)) continue;

            int x = projectedX;
            int y = projectedY;
            // When many people collapse onto the same few pixels at wide zoom, find
            // the nearest unused icon centre in a square spiral. This intentionally
            // trades a few pixels of exactness for the user's requirement that every
            // physical person has a visible map icon instead of being hidden under
            // the first worker drawn at that position.
            for (int candidate = 0; candidate < 1024; candidate++) {
                int[] offset = markerSpiralOffset(candidate);
                int candidateX = clampInt(projectedX + offset[0] * 4, mapX + 3, mapX + mapWidth - 3);
                int candidateY = clampInt(projectedY + offset[1] * 5, mapY + 4, mapY + mapHeight - 4);
                long centreKey = (((long) candidateX) << 32) ^ (candidateY & 0xffffffffL);
                if (usedIconCentres.add(centreKey)) {
                    x = candidateX;
                    y = candidateY;
                    break;
                }
            }

            Civilisation civilisation = StrategyClientContext.getCivilisation(marker.civilisationId());
            int baseColour = civilisation == null ? 0xFFE6EDF3 : civilisation.getBorderColourArgb();
            int colour = variantMarkerColour(baseColour, marker.appearanceVariant());
            boolean soldier = "SOLDIER".equals(marker.job());

            drawHumanoidMapIcon(graphics, x, y, colour, marker.appearanceVariant());
            if (soldier) graphics.outline(x - 3, y - 4, 7, 9, 0xFFFFFFFF);
        }
    }

    /** Square-spiral offsets keep people projected onto the same map pixel individually visible. */
    private int[] markerSpiralOffset(int index) {
        if (index <= 0) return new int[] {0, 0};
        int ring = (int) Math.ceil((Math.sqrt(index + 1.0) - 1.0) / 2.0);
        int side = ring * 2;
        int maxIndex = (2 * ring + 1) * (2 * ring + 1) - 1;
        int delta = maxIndex - index;
        int x;
        int y;
        if (delta < side) {
            x = ring - delta;
            y = ring;
        } else if (delta < side * 2) {
            x = -ring;
            y = ring - (delta - side);
        } else if (delta < side * 3) {
            x = -ring + (delta - side * 2);
            y = -ring;
        } else {
            x = ring;
            y = -ring + (delta - side * 3);
        }
        return new int[] {x, y};
    }

    private void drawHumanoidMapIcon(GuiGraphicsExtractor graphics, int x, int y, int colour, int variant) {
        // Dark silhouette backing makes every individual readable over land/water.
        graphics.fill(x - 2, y - 3, x + 3, y + 4, 0xD0101010);
        // Head.
        graphics.fill(x - 1, y - 2, x + 2, y, colour);
        // Torso, slightly varied so appearance variants remain visible on the map.
        int variantKind = Math.floorMod(variant, 3);
        if (variantKind == 0) graphics.fill(x - 1, y, x + 2, y + 2, colour);
        else if (variantKind == 1) graphics.fill(x - 2, y, x + 3, y + 1, colour);
        else {
            graphics.fill(x - 1, y, x + 2, y + 2, colour);
            graphics.fill(x - 2, y, x - 1, y + 1, colour);
        }
        // Two legs.
        graphics.fill(x - 1, y + 2, x, y + 4, colour);
        graphics.fill(x + 1, y + 2, x + 2, y + 4, colour);
    }

    private int variantMarkerColour(int baseColour, int variant) {
        int[][] tweaks = new int[][] {
                {0, 0, 0}, {20, 8, -10}, {-12, 20, 10}, {18, -8, 22},
                {-20, 10, 24}, {24, 24, -12}, {-8, -8, 28}
        };
        int[] tweak = tweaks[Math.floorMod(variant, tweaks.length)];
        int a = (baseColour >>> 24) & 0xFF;
        int r = clampColour(((baseColour >>> 16) & 0xFF) + tweak[0]);
        int g = clampColour(((baseColour >>> 8) & 0xFF) + tweak[1]);
        int b = clampColour((baseColour & 0xFF) + tweak[2]);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private int clampColour(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private void renderCountries(GuiGraphicsExtractor graphics, WorldMapTracker.Snapshot snapshot,
                                 Civilisation player) {
        countryMarkers.clear();
        List<Civilisation> activeCivilisations = StrategyClientContext.civilisations().stream()
                .filter(Civilisation::isActive)
                .filter(Civilisation::hasWorldMapPosition)
                .sorted(Comparator.comparing(Civilisation::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        for (Civilisation civilisation : activeCivilisations) {
            double worldX = civilisation.getWorldMapBlockX();
            double worldZ = civilisation.getWorldMapBlockZ();

            // Civilisations never disclose unexplored territory. They become visible
            // only after their Minecraft anchor chunk has actually been discovered.
            if (!snapshot.isDiscoveredBlock(worldX, worldZ)) {
                continue;
            }

            int cx = worldToScreenX(worldX);
            int cy = worldToScreenY(worldZ);
            if (!insideMap(cx, cy)) continue;

            boolean own = player != null && player.getId().equals(civilisation.getId());
            int colour = civilisation.getBorderColourArgb();

            graphics.fill(cx - 3, cy - 3, cx + 4, cy + 4, colour);
            graphics.outline(cx - 4, cy - 4, 9, 9, own ? 0xFFFFD54F : 0xFF111111);

            String label = civilisation.getName();
            int labelWidth = Math.max(12, this.font.width(label));
            if (cx + 7 + labelWidth < mapX + mapWidth) {
                graphics.text(this.font, label, cx + 6, cy - 4, TEXT, true);
            }
            countryMarkers.add(new MarkerBounds(
                    cx - 5, cy - 6,
                    Math.min(mapX + mapWidth, cx + 8 + labelWidth), cy + 8,
                    civilisation.getId()));
        }
    }

    private void renderPlayerMarker(GuiGraphicsExtractor graphics) {
        Integer playerX = StrategyClientContext.currentPlayerBlockX();
        Integer playerZ = StrategyClientContext.currentPlayerBlockZ();
        if (playerX == null || playerZ == null) return;

        int px = worldToScreenX(playerX);
        int py = worldToScreenY(playerZ);
        if (!insideMap(px, py)) return;

        // A high-contrast cross remains legible over both water and land tiles.
        graphics.fill(px - 1, py - 5, px + 2, py + 6, 0xFFFFFFFF);
        graphics.fill(px - 5, py - 1, px + 6, py + 2, 0xFFFFFFFF);
        graphics.outline(px - 6, py - 6, 13, 13, 0xFF101010);
        if (px + 10 + this.font.width("YOU") < mapX + mapWidth) {
            graphics.text(this.font, "YOU", px + 9, py - 4, 0xFFFFFFFF, true);
        }
    }

    private void renderArmyOrder(GuiGraphicsExtractor graphics, WorldMapTracker.Snapshot snapshot,
                                 Civilisation player) {
        if (player == null || !player.hasSoldierOrder()) return;
        int worldX = player.getSoldierOrderBlockX();
        int worldZ = player.getSoldierOrderBlockZ();
        if (!snapshot.isDiscoveredBlock(worldX, worldZ)) return;
        int x = worldToScreenX(worldX);
        int y = worldToScreenY(worldZ);
        if (!insideMap(x, y)) return;
        graphics.fill(x - 5, y - 1, x + 6, y + 2, 0xFFFFD54F);
        graphics.fill(x - 1, y - 5, x + 2, y + 6, 0xFFFFD54F);
        graphics.outline(x - 6, y - 6, 13, 13, 0xFF101010);
        if (x + 10 + this.font.width("ARMY") < mapX + mapWidth) {
            graphics.text(this.font, "ARMY", x + 9, y - 4, 0xFFFFD54F, true);
        }
    }

    private int terrainColour(WorldMapTracker.Terrain terrain) {
        if (terrain == null) return LAND;
        return switch (terrain) {
            case WATER -> WATER;
            case COASTAL -> COAST;
            case LAND -> LAND;
            case HIGHLAND -> HIGHLAND;
        };
    }

    private int worldToScreenX(double worldX) {
        return (int) Math.round(mapX + mapWidth * 0.5 + (worldX - centreBlockX) / blocksPerPixel);
    }

    private int worldToScreenY(double worldZ) {
        return (int) Math.round(mapY + mapHeight * 0.5 + (worldZ - centreBlockZ) / blocksPerPixel);
    }

    private double screenToWorldX(double screenX) {
        return centreBlockX + (screenX - (mapX + mapWidth * 0.5)) * blocksPerPixel;
    }

    private double screenToWorldZ(double screenY) {
        return centreBlockZ + (screenY - (mapY + mapHeight * 0.5)) * blocksPerPixel;
    }

    private boolean clientCanDesignateZone(String civilisationId, String type, int blockX, int blockZ) {
        if (civilisationId == null || type == null) return false;
        boolean farm = type.startsWith("FARM");
        int width = farm ? 52 : 11;
        int depth = farm ? 52 : 9;
        int minX = blockX - width / 2;
        int maxX = minX + width - 1;
        int minZ = blockZ - depth / 2;
        int maxZ = minZ + depth - 1;
        WorldMapTracker.Snapshot snapshot = StrategyClientContext.mapSnapshot();
        int minChunkX = Math.floorDiv(minX, WorldMapTracker.CHUNK_SIZE);
        int maxChunkX = Math.floorDiv(maxX, WorldMapTracker.CHUNK_SIZE);
        int minChunkZ = Math.floorDiv(minZ, WorldMapTracker.CHUNK_SIZE);
        int maxChunkZ = Math.floorDiv(maxZ, WorldMapTracker.CHUNK_SIZE);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                int centreX = chunkX * WorldMapTracker.CHUNK_SIZE + WorldMapTracker.CHUNK_SIZE / 2;
                int centreZ = chunkZ * WorldMapTracker.CHUNK_SIZE + WorldMapTracker.CHUNK_SIZE / 2;
                if (!snapshot.isDiscoveredBlock(centreX, centreZ)) return false;
                long key = WorldMapTracker.chunkKey(chunkX, chunkZ);
                Providence containing = null;
                for (Providence providence : StrategyClientContext.providences()) {
                    if (providence != null && providence.getTerritoryChunkKeys().contains(key)) {
                        containing = providence;
                        break;
                    }
                }
                if (containing == null) return false;
                String owner = containing.getTerritoryOwner(key);
                if (owner != null && !owner.isBlank() && !Objects.equals(owner, civilisationId)) return false;
            }
        }
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }

        if (event.button() == 0) {
            if (workZonePlacementType != null && insideMap(event.x(), event.y())) {
                int worldX = (int) Math.round(screenToWorldX(event.x()));
                int worldZ = (int) Math.round(screenToWorldZ(event.y()));
                Civilisation own = StrategyClientContext.currentPlayerCountry();
                if (own == null || !clientCanDesignateZone(own.getId(), workZonePlacementType, worldX, worldZ)) {
                    statusMessage = "Districts may be placed only on discovered land you own or land that is still uncolonised.";
                    return true;
                }
                boolean queued = StrategyClientContext.requestWorkZone(workZonePlacementType, worldX, worldZ);
                statusMessage = queued
                        ? workZonePlacementType + " district requested at X=" + worldX + " Z=" + worldZ
                            + ". Click again to place another; right-click to finish."
                        : "Could not send the district request.";
                // Keep placement mode active so several districts can be laid out quickly.
                return true;
            }

            if (awaitingSoldierDestination && insideMap(event.x(), event.y())) {
                int worldX = (int) Math.round(screenToWorldX(event.x()));
                int worldZ = (int) Math.round(screenToWorldZ(event.y()));
                boolean queued = StrategyClientContext.requestSoldierMove(worldX, worldZ);
                statusMessage = queued
                        ? "Manual army order sent to X=" + worldX + " Z=" + worldZ + "."
                        : "Could not send the army order.";
                awaitingSoldierDestination = false;
                if (queued) scheduleRefresh();
                return true;
            }

            for (MarkerBounds marker : countryMarkers) {
                if (marker.contains(event.x(), event.y())) {
                    Civilisation civilisation = StrategyClientContext.getCivilisation(marker.civilisationId());
                    if (civilisation != null && civilisation.isActive()) {
                        this.minecraft.setScreen(new CountryScreen(this, civilisation.getId()));
                        return true;
                    }
                }
            }

            if (insideMap(event.x(), event.y())) {
                draggingMap = true;
                return true;
            }
        }
        if (event.button() == 1 && workZonePlacementType != null) {
            workZonePlacementType = null;
            statusMessage = "District placement finished.";
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingMap && event.button() == 0) {
            centreBlockX -= dragX * blocksPerPixel;
            centreBlockZ -= dragY * blocksPerPixel;
            viewInitialised = true;
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingMap && event.button() == 0) {
            draggingMap = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!insideMap(mouseX, mouseY) || verticalAmount == 0.0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        double beforeX = screenToWorldX(mouseX);
        double beforeZ = screenToWorldZ(mouseY);
        double factor = Math.pow(1.22, -verticalAmount);
        blocksPerPixel = clamp(blocksPerPixel * factor, MIN_BLOCKS_PER_PIXEL, MAX_BLOCKS_PER_PIXEL);
        double afterX = screenToWorldX(mouseX);
        double afterZ = screenToWorldZ(mouseY);
        centreBlockX += beforeX - afterX;
        centreBlockZ += beforeZ - afterZ;
        viewInitialised = true;
        return true;
    }

    private void zoomAtMapCentre(double factor) {
        blocksPerPixel = clamp(blocksPerPixel * factor, MIN_BLOCKS_PER_PIXEL, MAX_BLOCKS_PER_PIXEL);
        viewInitialised = true;
    }

    private boolean insideMap(double x, double y) {
        return x >= mapX && x <= mapX + mapWidth && y >= mapY && y <= mapY + mapHeight;
    }

    private String formatScale() {
        if (blocksPerPixel < 1.0) {
            return String.format(Locale.ROOT, "%.2f blocks/px", blocksPerPixel);
        }
        return String.format(Locale.ROOT, "%.1f blocks/px", blocksPerPixel);
    }

    @Override
    protected StrategyScreen recreate() {
        return new MapScreen(getParentScreen(), centreBlockX, centreBlockZ, blocksPerPixel, viewInitialised, workZonePlacementType, statusMessage);
    }

    private static String formatRate(double gsDaysPerMinecraftDay) {
        if (gsDaysPerMinecraftDay >= 365.0) {
            return String.format(Locale.ROOT, "%.1f GS years/MC day", gsDaysPerMinecraftDay / 365.0);
        }
        return String.format(Locale.ROOT, "%.1f GS days/MC day", gsDaysPerMinecraftDay);
    }

    private static String formatResource(Civilisation civilisation, ResourceType type) {
        if (civilisation == null) return "--";
        double value = civilisation.getResource(type);
        if (value >= 1000.0) return String.format(Locale.ROOT, "%.1fk", value / 1000.0);
        return Integer.toString((int) value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record MarkerBounds(int x1, int y1, int x2, int y2, String civilisationId) {
        boolean contains(double x, double y) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2;
        }
    }
}



