package com.asbestosstar.grandstrategy.common.network;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.FactoryRecipe;
import com.asbestosstar.grandstrategy.common.data.FactoryType;
import com.asbestosstar.grandstrategy.common.data.Ideology;
import com.asbestosstar.grandstrategy.common.data.Leader;
import com.asbestosstar.grandstrategy.common.data.MinecraftItemRegistry;
import com.asbestosstar.grandstrategy.common.data.Religion;
import com.asbestosstar.grandstrategy.common.data.Technology;
import com.asbestosstar.grandstrategy.common.data.Providence;
import com.asbestosstar.grandstrategy.common.data.DataManager;
import com.asbestosstar.grandstrategy.common.data.FocusTree;
import com.asbestosstar.grandstrategy.common.data.GrandStrategyEvent;
import com.asbestosstar.grandstrategy.common.data.VillagerJob;
import com.asbestosstar.grandstrategy.common.engine.FocusAndEventSystem;
import com.asbestosstar.grandstrategy.common.engine.ResearchSystem;
import com.asbestosstar.grandstrategy.common.engine.PlayerCountryService;
import com.asbestosstar.grandstrategy.common.engine.StrategyEngine;
import com.asbestosstar.grandstrategy.common.engine.WarSystem;
import com.asbestosstar.grandstrategy.common.world.PhysicalVillagerSystem;
import com.asbestosstar.grandstrategy.common.world.WorldMapTracker;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Loader-independent multiplayer bridge.
 *
 * Client -> server uses a vanilla level-0 /grandstrategy command. Server -> client
 * uses ordinary system messages which are intercepted and cancelled by the client
 * Mixin before they reach chat. Only clients which explicitly subscribe receive
 * these messages, so unmodded players on a server do not see protocol traffic.
 *
 * This deliberately avoids Fabric/Forge/NeoForge networking registration APIs.
 */
public final class NetworkManager {
    private static final NetworkManager INSTANCE = new NetworkManager();
    private static final Gson GSON = new GsonBuilder().create();
    private static final String COMMAND = "grandstrategy";
    private static final String SYNC_PREFIX = "[grandstrategy:net:v1]:";
    private static final int CHUNK_CHARS = 12_000;
    private static final int PERIODIC_SYNC_TICKS = 40;
    private static final int MAX_PARTS = 4096;

    private final List<Consumer<Packet>> packetListeners = new ArrayList<>();
    private final Map<UUID, Integer> subscribedPlayers = new HashMap<>();
    private final Map<Long, IncomingMessage> incomingMessages = new HashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    private MinecraftServer registeredServer;
    private long serverTicks;

    private NetworkManager() {
    }

    public static NetworkManager getInstance() {
        return INSTANCE;
    }

    /** Kept for old common-code callers; real multiplayer state uses full snapshots. */
    public void sendPacket(Packet packet) {
        if (packet == null) return;
        synchronized (packetListeners) {
            for (Consumer<Packet> listener : packetListeners) {
                listener.accept(packet);
            }
        }
    }

    public void registerListener(Consumer<Packet> listener) {
        if (listener == null) return;
        synchronized (packetListeners) {
            packetListeners.add(listener);
        }
    }

    /** Called on the Minecraft server thread from the existing server Mixin. */
    public synchronized void serverTick(MinecraftServer server) {
        if (server == null) return;
        ensureCommandRegistered(server);
        serverTicks++;

        // Snapshot JSON contains every physical worker and is encoded on the server
        // thread. At large populations halve the periodic snapshot frequency; explicit
        // UI actions still trigger immediate snapshots, so controls stay responsive.
        int syncInterval = PhysicalVillagerSystem.getInstance().workerCount() > 96
                ? PERIODIC_SYNC_TICKS * 2 : PERIODIC_SYNC_TICKS;
        if (serverTicks % syncInterval != 0) return;

        subscribedPlayers.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
        for (UUID uuid : List.copyOf(subscribedPlayers.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) sendSnapshot(player, false);
        }
    }

    public synchronized void onServerStopped(MinecraftServer server) {
        if (server == registeredServer) {
            registeredServer = null;
            serverTicks = 0L;
            subscribedPlayers.clear();
        }
    }

    private void ensureCommandRegistered(MinecraftServer server) {
        if (registeredServer == server) return;
        registeredServer = server;
        serverTicks = 0L;
        subscribedPlayers.clear();

        var dispatcher = server.getCommands().getDispatcher();
        if (dispatcher.getRoot().getChild(COMMAND) != null) return;

        dispatcher.register(Commands.literal(COMMAND)
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    handleServerRequest(server, player, encodeAction(new Action("REQUEST", null, null)));
                    return 1;
                })
                // Cheat/operator-only population command. The ordinary /grandstrategy
                // payload bridge remains permission level 0 for multiplayer GUI actions.
                .then(Commands.literal("population")
                        .requires(Commands.hasPermission(new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER)))
                        .then(Commands.literal("add")
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 10_000))
                                        .executes(context -> {
                                            ServerPlayer player = context.getSource().getPlayerOrException();
                                            String ign = player.getName().getString();
                                            Civilisation civilisation = PlayerCountryService.findForPlayer(
                                                    player.getUUID().toString(), ign);
                                            if (civilisation == null || !civilisation.isActive()) {
                                                player.sendSystemMessage(Component.literal(
                                                        "You need an active Grand Strategy country first."));
                                                return 0;
                                            }
                                            int amount = IntegerArgumentType.getInteger(context, "amount");
                                            civilisation.addPopulationRandomised(amount);
                                            PhysicalVillagerSystem.getInstance().requestImmediateReconcile();
                                            StrategyEngine.getInstance().requestSave();
                                            player.sendSystemMessage(Component.literal(
                                                    "Added " + amount + " population to " + civilisation.getName()
                                                            + ". New population: " + civilisation.getPopulation()));
                                            return amount;
                                        }))))
                // Cheat/operator-only historical-time jump. This advances Grand
                // Strategy time, not vanilla Minecraft daytime.
                .then(Commands.literal("time")
                        .requires(Commands.hasPermission(new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER)))
                        .then(Commands.literal("add")
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 1_000_000))
                                        .then(Commands.literal("days")
                                                .executes(context -> executeCheatTimeAdvance(
                                                        context.getSource().getPlayerOrException(),
                                                        IntegerArgumentType.getInteger(context, "amount"), 1L, "days")))
                                        .then(Commands.literal("day")
                                                .executes(context -> executeCheatTimeAdvance(
                                                        context.getSource().getPlayerOrException(),
                                                        IntegerArgumentType.getInteger(context, "amount"), 1L, "days")))
                                        .then(Commands.literal("months")
                                                .executes(context -> executeCheatTimeAdvance(
                                                        context.getSource().getPlayerOrException(),
                                                        IntegerArgumentType.getInteger(context, "amount"), 30L, "months")))
                                        .then(Commands.literal("month")
                                                .executes(context -> executeCheatTimeAdvance(
                                                        context.getSource().getPlayerOrException(),
                                                        IntegerArgumentType.getInteger(context, "amount"), 30L, "months")))
                                        .then(Commands.literal("years")
                                                .executes(context -> executeCheatTimeAdvance(
                                                        context.getSource().getPlayerOrException(),
                                                        IntegerArgumentType.getInteger(context, "amount"), 365L, "years")))
                                        .then(Commands.literal("year")
                                                .executes(context -> executeCheatTimeAdvance(
                                                        context.getSource().getPlayerOrException(),
                                                        IntegerArgumentType.getInteger(context, "amount"), 365L, "years"))))))
                .then(Commands.argument("payload", StringArgumentType.greedyString())
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            handleServerRequest(server, player,
                                    StringArgumentType.getString(context, "payload"));
                            return 1;
                        })));
    }

    private int executeCheatTimeAdvance(ServerPlayer player, int amount, long daysPerUnit, String unitName) {
        if (player == null || amount <= 0 || daysPerUnit <= 0L) return 0;

        long activeBefore = DataManager.getCivilisations().values().stream()
                .filter(Civilisation::isActive)
                .count();

        long requestedDays = Math.multiplyExact((long) amount, daysPerUnit);
        StrategyEngine engine = StrategyEngine.getInstance();
        long advancedDays = engine.cheatAdvanceHistoricalDays(requestedDays);

        if (advancedDays <= 0L) {
            player.sendSystemMessage(Component.literal(
                    "Grand Strategy time is already at " + engine.getTimeline().getFormattedYear() + "."));
            return 0;
        }

        long activeAfter = DataManager.getCivilisations().values().stream()
                .filter(Civilisation::isActive)
                .count();
        long activated = Math.max(0L, activeAfter - activeBefore);

        PhysicalVillagerSystem.getInstance().requestImmediateReconcile();
        engine.requestSave();

        String jumpDescription = advancedDays == requestedDays
                ? amount + " " + unitName
                : advancedDays + " GS days (requested " + amount + " " + unitName
                        + "; clamped at the timeline endpoint)";

        player.sendSystemMessage(Component.literal(
                "Advanced Grand Strategy time by " + jumpDescription
                        + " to " + engine.getTimeline().getFormattedYear()
                        + ", day " + (engine.getTimeline().getCurrentDay() + 1)
                        + ". Activated " + activated + " historical civilisation"
                        + (activated == 1L ? "" : "s") + "."));
        return 1;
    }

    private void handleServerRequest(MinecraftServer server, ServerPlayer player, String encodedAction) {
        if (player == null) return;
        subscribedPlayers.putIfAbsent(player.getUUID(), -1);

        Action action;
        try {
            action = decodeAction(encodedAction);
        } catch (RuntimeException e) {
            return;
        }
        if (action == null || action.type == null) return;

        if ("REQUEST".equals(action.type)) {
            sendSnapshot(player, true);
            return;
        }

        boolean queued = StrategyEngine.getInstance().enqueueWorldAction(() -> {
            executeServerAction(server, player, action);
            StrategyEngine.getInstance().requestSave();
            sendSnapshot(player, false);
        });
        if (!queued) sendSnapshot(player, false);
    }

    private void executeServerAction(MinecraftServer server, ServerPlayer player, Action action) {
        String ign = player.getName().getString();
        String uuid = player.getUUID().toString();
        Civilisation own = PlayerCountryService.findForPlayer(uuid, ign);

        switch (action.type) {
            case "CREATE" -> {
                if (own != null) return;
                Integer x = null;
                Integer z = null;
                if (Level.OVERWORLD.equals(player.level().dimension())) {
                    x = floorToInt(player.getX());
                    z = floorToInt(player.getZ());
                }
                PlayerCountryService.createCountry(
                        ign, uuid,
                        StrategyEngine.getInstance().getTimeline().getCurrentYear(),
                        x, z);
            }
            case "IMPROVE" -> {
                if (own != null) PlayerCountryService.improveRelations(own.getId(), action.target);
            }
            case "WAR" -> {
                if (own != null) PlayerCountryService.declareWar(own.getId(), action.target);
            }
            case "PEACE_PROPOSE" -> {
                if (own != null && action.target != null && action.value != null) {
                    WarSystem.getInstance().proposePeace(own.getId(), action.target, action.value);
                }
            }
            case "PEACE_ACCEPT" -> {
                if (own != null && action.target != null) {
                    WarSystem.getInstance().acceptPeace(own.getId(), action.target);
                }
            }
            case "PEACE_REJECT" -> {
                if (own != null && action.target != null) {
                    WarSystem.getInstance().rejectPeace(own.getId(), action.target);
                }
            }
            case "SOLDIER_AUTO" -> {
                if (own != null) own.setSoldierControlAutomatic();
            }
            case "SOLDIER_MOVE" -> {
                if (own != null) {
                    Integer x = parseCoordinate(action.target);
                    Integer z = parseCoordinate(action.value);
                    if (x != null && z != null
                            && WorldMapTracker.getInstance().snapshot().isDiscoveredBlock(x, z)) {
                        own.setSoldierManualOrder(x, z);
                    }
                }
            }
            case "WORK_ZONE" -> {
                if (own != null && action.target != null && action.value != null) {
                    String[] coordinates = action.value.split(",", 2);
                    if (coordinates.length == 2) {
                        Integer x = parseCoordinate(coordinates[0]);
                        Integer z = parseCoordinate(coordinates[1]);
                        if (x != null && z != null) {
                            PhysicalVillagerSystem.getInstance()
                                    .designateWorkZone(own.getId(), action.target, x, z);
                        }
                    }
                }
            }
            case "TECH_START" -> {
                if (own != null && action.target != null
                        && ResearchSystem.canStart(own, DataManager.getTechnologies().get(action.target))) {
                    own.startTechnology(action.target);
                }
            }
            case "PRODUCTION_QUEUE" -> {
                if (own != null && action.target != null) {
                    Integer amount = parseCoordinate(action.value);
                    FactoryRecipe recipe = DataManager.getFactoryRecipes().get(action.target);
                    if (amount != null && amount > 0 && recipe != null
                            && ResearchSystem.recipeAvailable(own, recipe)) {
                        own.queueProduction(recipe.getId(), Math.min(100000, amount));
                    }
                }
            }
            case "PRODUCTION_CANCEL" -> {
                if (own != null && action.target != null) {
                    try { own.cancelProduction(Long.parseLong(action.target)); } catch (NumberFormatException ignored) { }
                }
            }
            case "FACTORY_CONVERT" -> {
                if (own != null && action.target != null && action.value != null) {
                    PhysicalVillagerSystem.getInstance().convertFactoryZone(own.getId(), action.target, action.value);
                }
            }
            case "CONSCRIPT" -> {
                if (own != null) own.cycleConscriptionLevel();
            }
            case "GOVERNMENT" -> {
                if (own != null) own.cycleGovernment();
            }
            case "AUTO_ASSIGN" -> {
                if (own != null) own.autoAssignJobs();
            }
            case "REASSIGN_TO" -> {
                if (own != null) own.reassignOneTo(parseJob(action.value));
            }
            case "REASSIGN_FROM" -> {
                if (own != null) own.reassignOneFrom(parseJob(action.value));
            }
            case "SPIRIT" -> {
                if (own != null && action.value != null) own.toggleNationalSpirit(action.value);
            }
            case "FOCUS" -> {
                if (own != null && action.target != null) FocusAndEventSystem.startFocus(own, action.target);
            }
            case "EVENT" -> {
                if (own != null && action.target != null && action.value != null) {
                    FocusAndEventSystem.resolveEvent(own, action.target, action.value);
                }
            }
            default -> { }
        }
    }

    private static Integer parseCoordinate(String value) {
        if (value == null) return null;
        try {
            int coordinate = Integer.parseInt(value);
            return Math.max(-29_999_984, Math.min(29_999_984, coordinate));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static VillagerJob parseJob(String value) {
        if (value == null) return null;
        try {
            return VillagerJob.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void sendSnapshot(ServerPlayer player, boolean forceFullMap) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        int knownTiles = subscribedPlayers.getOrDefault(uuid, -1);
        int currentTileCount = WorldMapTracker.getInstance().discoveredTileCount();
        boolean fullMap = forceFullMap || knownTiles < 0 || knownTiles > currentTileCount;

        SyncState state = captureServerState(fullMap, knownTiles);
        String encoded;
        try {
            encoded = compressToBase64(GSON.toJson(state));
        } catch (IOException e) {
            System.err.println("Could not encode Grand Strategy multiplayer snapshot.");
            return;
        }

        long id = sequence.incrementAndGet();
        int total = Math.max(1, (encoded.length() + CHUNK_CHARS - 1) / CHUNK_CHARS);
        for (int part = 0; part < total; part++) {
            int from = part * CHUNK_CHARS;
            int to = Math.min(encoded.length(), from + CHUNK_CHARS);
            String text = SYNC_PREFIX + id + ":" + part + ":" + total + ":" + encoded.substring(from, to);
            player.sendSystemMessage(Component.literal(text));
        }
        subscribedPlayers.put(uuid, currentTileCount);
    }

    private SyncState captureServerState(boolean fullMap, int knownTiles) {
        SyncState state = new SyncState();
        state.currentYear = StrategyEngine.getInstance().getTimeline().getCurrentYear();
        state.currentDay = StrategyEngine.getInstance().getTimeline().getCurrentDay();
        state.gsDaysPerMinecraftDay = StrategyEngine.getInstance().getTimeline().getGsDaysPerMinecraftDay();

        state.civilisations = DataManager.getCivilisations().values().stream()
                .filter(Civilisation::isActive)
                .sorted((a, b) -> String.valueOf(a.getId()).compareTo(String.valueOf(b.getId())))
                .toList();

        state.providences = DataManager.getProvidences().values().stream()
                .filter(Providence::isEstablished)
                .sorted((a, b) -> String.valueOf(a.getId()).compareTo(String.valueOf(b.getId())))
                .toList();

        // Definitions are server-owned too. Syncing them means a dedicated server
        // can use custom JSON focus trees/events without requiring clients to have
        // an identical local data directory.
        state.focusTrees = DataManager.getFocusTrees().values().stream()
                .sorted((a, b) -> String.valueOf(a.getCivilisationId()).compareTo(String.valueOf(b.getCivilisationId())))
                .toList();
        state.events = DataManager.getEvents().values().stream()
                .sorted((a, b) -> String.valueOf(a.getId()).compareTo(String.valueOf(b.getId())))
                .toList();
        state.technologies = DataManager.getTechnologies().values().stream()
                .filter(ResearchSystem::technologyExistsInCurrentModSet)
                .sorted((a, b) -> String.valueOf(a.getId()).compareTo(String.valueOf(b.getId())))
                .toList();
        state.factoryTypes = DataManager.getFactoryTypes().values().stream()
                .filter(type -> type.getRequiredItemIds().stream().allMatch(MinecraftItemRegistry::itemExists))
                .sorted((a, b) -> String.valueOf(a.getId()).compareTo(String.valueOf(b.getId())))
                .toList();
        state.factoryRecipes = DataManager.getFactoryRecipes().values().stream()
                .filter(recipe -> recipe.getRequiredItemIds().stream().allMatch(MinecraftItemRegistry::itemExists))
                .sorted((a, b) -> String.valueOf(a.getId()).compareTo(String.valueOf(b.getId())))
                .toList();
        state.religions = DataManager.getReligions().values().stream()
                .sorted((a, b) -> String.valueOf(a.getId()).compareTo(String.valueOf(b.getId())))
                .toList();
        state.ideologies = DataManager.getIdeologies().values().stream()
                .sorted((a, b) -> String.valueOf(a.getId()).compareTo(String.valueOf(b.getId())))
                .toList();
        state.leaders = DataManager.getLeaders().values().stream()
                .sorted((a, b) -> String.valueOf(a.getId()).compareTo(String.valueOf(b.getId())))
                .toList();
        state.wars = WarSystem.getInstance().snapshot();
        state.villagers = PhysicalVillagerSystem.getInstance().snapshotMapMarkers();
        state.workZones = PhysicalVillagerSystem.getInstance().snapshotWorkZones();

        WorldMapTracker tracker = WorldMapTracker.getInstance();
        WorldMapTracker.Snapshot map = tracker.snapshot();
        state.fullMap = fullMap;
        if (fullMap) {
            state.tiles = map.tiles();
        } else {
            List<WorldMapTracker.MapTile> delta = tracker.discoveredTilesSince(Math.max(0, knownTiles));
            state.tiles = delta.isEmpty() ? null : delta;
        }
        state.mapTileCount = tracker.discoveredTileCount();
        state.projectionOriginSet = map.projectionOriginSet();
        state.projectionOriginBlockX = map.projectionOriginBlockX();
        state.projectionOriginBlockZ = map.projectionOriginBlockZ();
        state.minChunkX = map.minChunkX();
        state.maxChunkX = map.maxChunkX();
        state.minChunkZ = map.minChunkZ();
        state.maxChunkZ = map.maxChunkZ();
        return state;
    }

    // --------------------------- client API ---------------------------

    public boolean requestSync() {
        return sendClientAction(new Action("REQUEST", null, null));
    }

    public boolean requestCreateCountry() {
        return sendClientAction(new Action("CREATE", null, null));
    }

    public boolean requestImproveRelations(String targetId) {
        return sendClientAction(new Action("IMPROVE", targetId, null));
    }

    public boolean requestDeclareWar(String targetId) {
        return sendClientAction(new Action("WAR", targetId, null));
    }

    public boolean requestPeaceProposal(String targetId, String encodedTerms) {
        return sendClientAction(new Action("PEACE_PROPOSE", targetId, encodedTerms));
    }

    public boolean requestPeaceAccept(String targetId) {
        return sendClientAction(new Action("PEACE_ACCEPT", targetId, null));
    }

    public boolean requestPeaceReject(String targetId) {
        return sendClientAction(new Action("PEACE_REJECT", targetId, null));
    }

    public boolean requestSoldierAutomatic() {
        return sendClientAction(new Action("SOLDIER_AUTO", null, null));
    }

    public boolean requestSoldierMove(int blockX, int blockZ) {
        return sendClientAction(new Action("SOLDIER_MOVE", Integer.toString(blockX), Integer.toString(blockZ)));
    }

    public boolean requestWorkZone(String type, int blockX, int blockZ) {
        if (type == null) return false;
        return sendClientAction(new Action("WORK_ZONE", type,
                Integer.toString(blockX) + "," + Integer.toString(blockZ)));
    }

    public boolean requestStartTechnology(String technologyId) {
        return sendClientAction(new Action("TECH_START", technologyId, null));
    }

    public boolean requestQueueProduction(String recipeId, int amount) {
        return sendClientAction(new Action("PRODUCTION_QUEUE", recipeId, Integer.toString(Math.max(1, amount))));
    }

    public boolean requestCancelProduction(long serial) {
        return sendClientAction(new Action("PRODUCTION_CANCEL", Long.toString(serial), null));
    }

    public boolean requestFactoryConversion(String zoneId, String factoryTypeId) {
        return sendClientAction(new Action("FACTORY_CONVERT", zoneId, factoryTypeId));
    }

    public boolean requestCycleConscription() {
        return sendClientAction(new Action("CONSCRIPT", null, null));
    }

    public boolean requestCycleGovernment() {
        return sendClientAction(new Action("GOVERNMENT", null, null));
    }

    public boolean requestAutoAssign() {
        return sendClientAction(new Action("AUTO_ASSIGN", null, null));
    }

    public boolean requestReassignTo(VillagerJob job) {
        return sendClientAction(new Action("REASSIGN_TO", null, job == null ? null : job.name()));
    }

    public boolean requestReassignFrom(VillagerJob job) {
        return sendClientAction(new Action("REASSIGN_FROM", null, job == null ? null : job.name()));
    }

    public boolean requestToggleSpirit(String spiritId) {
        return sendClientAction(new Action("SPIRIT", null, spiritId));
    }

    public boolean requestStartFocus(String focusId) {
        return sendClientAction(new Action("FOCUS", focusId, null));
    }

    public boolean requestResolveEvent(String eventId, String optionId) {
        return sendClientAction(new Action("EVENT", eventId, optionId));
    }

    private boolean sendClientAction(Action action) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getConnection() == null) return false;
        try {
            minecraft.getConnection().sendCommand(COMMAND + " " + encodeAction(action));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Called by ClientPacketListenerMixin. Returning true means this is private
     * protocol traffic and the normal chat handler must be cancelled.
     */
    public synchronized boolean handleClientSystemMessage(String message) {
        if (message == null || !message.startsWith(SYNC_PREFIX)) return false;

        String rest = message.substring(SYNC_PREFIX.length());
        String[] header = rest.split(":", 4);
        if (header.length != 4) return true;

        try {
            long id = Long.parseLong(header[0]);
            int part = Integer.parseInt(header[1]);
            int total = Integer.parseInt(header[2]);
            if (total <= 0 || total > MAX_PARTS || part < 0 || part >= total) return true;

            IncomingMessage incoming = incomingMessages.computeIfAbsent(id,
                    ignored -> new IncomingMessage(total));
            if (incoming.parts.length != total) {
                incomingMessages.remove(id);
                return true;
            }
            incoming.parts[part] = header[3];

            if (incoming.isComplete()) {
                incomingMessages.remove(id);
                StringBuilder encoded = new StringBuilder();
                for (String chunk : incoming.parts) encoded.append(chunk);
                String json = decompressFromBase64(encoded.toString());
                SyncState state = GSON.fromJson(json, SyncState.class);
                StrategyClientState.getInstance().apply(state);
            }
        } catch (RuntimeException | IOException ignored) {
            // Malformed protocol traffic is swallowed rather than shown as chat.
        }
        return true;
    }

    private static String encodeAction(Action action) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                GSON.toJson(action).getBytes(StandardCharsets.UTF_8));
    }

    private static Action decodeAction(String encoded) {
        byte[] bytes = Base64.getUrlDecoder().decode(encoded);
        return GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Action.class);
    }

    private static String compressToBase64(String value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }

    private static String decompressFromBase64(String value) throws IOException {
        byte[] compressed = Base64.getUrlDecoder().decode(value);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int floorToInt(double value) {
        int whole = (int) value;
        return value < whole ? whole - 1 : whole;
    }

    private record Action(String type, String target, String value) { }

    private static final class IncomingMessage {
        final String[] parts;
        IncomingMessage(int count) { this.parts = new String[count]; }
        boolean isComplete() {
            for (String part : parts) if (part == null) return false;
            return true;
        }
    }

    /** Gson DTO intentionally package-visible for StrategyClientState. */
    static final class SyncState {
        long currentYear;
        int currentDay;
        double gsDaysPerMinecraftDay;
        List<Civilisation> civilisations = List.of();
        List<Providence> providences = List.of();
        List<FocusTree> focusTrees = List.of();
        List<GrandStrategyEvent> events = List.of();
        List<Technology> technologies = List.of();
        List<FactoryType> factoryTypes = List.of();
        List<FactoryRecipe> factoryRecipes = List.of();
        List<Religion> religions = List.of();
        List<Ideology> ideologies = List.of();
        List<Leader> leaders = List.of();
        List<WarSystem.WarState> wars = List.of();
        List<PhysicalVillagerSystem.VillagerMapMarker> villagers = List.of();
        List<PhysicalVillagerSystem.WorkZoneMapMarker> workZones = List.of();
        List<WorldMapTracker.MapTile> tiles;
        boolean fullMap;
        int mapTileCount;
        boolean projectionOriginSet;
        int projectionOriginBlockX;
        int projectionOriginBlockZ;
        int minChunkX;
        int maxChunkX;
        int minChunkZ;
        int maxChunkZ;
    }
}




