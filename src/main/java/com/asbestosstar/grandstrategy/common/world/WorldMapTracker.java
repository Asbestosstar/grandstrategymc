package com.asbestosstar.grandstrategy.common.world;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.DataManager;
import com.asbestosstar.grandstrategy.common.engine.ProvidenceSystem;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistent, loader-independent discovery map for the Minecraft Overworld.
 *
 * The strategic map never attempts to represent Minecraft's full +/-30,000,000
 * block coordinate range. A chunk only becomes part of this map after it has
 * been loaded around an Overworld player. Each discovered chunk is sampled once
 * and represented by a small terrain tile.
 *
 * Historical civilisation coordinates are not literal Earth coordinates. They
 * are a topology: civilisations which historically occupied nearby parts of
 * Earth use nearby canonical coordinates. The topology is projected around the
 * first explored area of this Minecraft save, then active civilisations are
 * anchored to nearby discovered land. This keeps Uruk next to Ur, for example,
 * without pretending that a random Minecraft seed has Earth's geography.
 */
public final class WorldMapTracker {
	public static final int CHUNK_SIZE = 16;

	private static final WorldMapTracker INSTANCE = new WorldMapTracker();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int STATE_VERSION = 1;
	private static final String STATE_FILE = "discovered_map.json";

	// We discover only chunks already loaded around the player; getChunkNow never
	// generates terrain just to satisfy the strategy map.
	private static final int DISCOVERY_RADIUS_CHUNKS = 10;
	// Physical Grand Strategy villagers also explore. A two-chunk sight radius
	// keeps
	// their discoveries local to where they actually travel instead of revealing a
	// player-sized ten-chunk circle around every worker.
	private static final int VILLAGER_DISCOVERY_RADIUS_CHUNKS = 2;
	private static final int DISCOVERY_INTERVAL_TICKS = 20;
	private static final int AUTO_SAVE_INTERVAL_TICKS = 6_000;

	// One canonical historical-map percentage point equals this many Minecraft
	// blocks. 20 keeps ancient neighbours close while leaving regional clusters
	// (e.g. Australia) distinctly separated and reachable by exploration.
	private static final int HISTORICAL_BLOCKS_PER_PERCENT = 20;
	// Capital placement deliberately leaves a buffer between countries so every
	// civilisation has room to expand, while still clustering new starts near an
	// existing neighbour instead of scattering them across the discovered map.
	private static final int MIN_COUNTRY_SPACING_CHUNKS = 16;
	private static final int RELAXED_MIN_COUNTRY_SPACING_CHUNKS = 12;
	private static final int PREFERRED_COUNTRY_SPACING_CHUNKS = 28;
	private static final int MAX_COMPANY_DISTANCE_CHUNKS = 52;

	private final Map<Long, MapTile> discoveredTiles = new HashMap<>();
	private final List<MapTile> discoveryOrder = new ArrayList<>();
	private volatile Snapshot publishedSnapshot = Snapshot.empty();

	private Path worldRoot;
	private boolean running;
	private boolean dirty;
	private long ticks;
	private boolean projectionOriginSet;
	private int projectionOriginBlockX;
	private int projectionOriginBlockZ;

	private WorldMapTracker() {
	}

	public static WorldMapTracker getInstance() {
		return INSTANCE;
	}

	public synchronized void start(Path worldRoot) {
		stop();
		this.worldRoot = worldRoot.toAbsolutePath().normalize();
		this.running = true;
		this.dirty = false;
		this.ticks = 0L;
		this.projectionOriginSet = false;
		this.projectionOriginBlockX = 0;
		this.projectionOriginBlockZ = 0;
		this.discoveredTiles.clear();
		this.discoveryOrder.clear();
		load();
		publishSnapshot();
	}

	public synchronized void stop() {
		if (running && worldRoot != null && dirty) {
			save();
		}
		running = false;
		dirty = false;
		ticks = 0L;
		worldRoot = null;
		discoveredTiles.clear();
		discoveryOrder.clear();
		projectionOriginSet = false;
		publishedSnapshot = Snapshot.empty();
	}

	/** Called from the common MinecraftServer Mixin on the server tick thread. */
	public synchronized void tick(MinecraftServer server) {
		if (!running || server == null) {
			return;
		}

		ticks++;
		if (ticks % DISCOVERY_INTERVAL_TICKS == 0) {
			ServerLevel overworld = server.overworld();
			if (overworld != null) {
				boolean changed = discoverAroundPlayers(overworld);
				changed |= discoverAroundVillagers(overworld);
				changed |= anchorActiveCivilisations();
				if (changed) {
					dirty = true;
					publishSnapshot();
				}

				// Providences are formed only from discovered Minecraft land. The
				// territorial system therefore runs after discovery/placement has
				// published the current snapshot.
				if (ProvidenceSystem.update(publishedSnapshot)) {
					dirty = true;
				}
			}
		}

		if (dirty && ticks % AUTO_SAVE_INTERVAL_TICKS == 0) {
			save();
		}
	}

	private boolean discoverAroundPlayers(ServerLevel overworld) {
		boolean changed = false;
		List<ServerPlayer> players = overworld.players();
		for (ServerPlayer player : players) {
			int playerBlockX = floorToInt(player.getX());
			int playerBlockZ = floorToInt(player.getZ());

			if (!projectionOriginSet) {
				projectionOriginBlockX = alignToChunkCentre(playerBlockX);
				projectionOriginBlockZ = alignToChunkCentre(playerBlockZ);
				projectionOriginSet = true;
				changed = true;
			}

			int playerChunkX = Math.floorDiv(playerBlockX, CHUNK_SIZE);
			int playerChunkZ = Math.floorDiv(playerBlockZ, CHUNK_SIZE);
			for (int dz = -DISCOVERY_RADIUS_CHUNKS; dz <= DISCOVERY_RADIUS_CHUNKS; dz++) {
				for (int dx = -DISCOVERY_RADIUS_CHUNKS; dx <= DISCOVERY_RADIUS_CHUNKS; dx++) {
					// Circular discovery radius looks more like natural exploration
					// than stamping a square into the map.
					if (dx * dx + dz * dz > DISCOVERY_RADIUS_CHUNKS * DISCOVERY_RADIUS_CHUNKS) {
						continue;
					}

					int chunkX = playerChunkX + dx;
					int chunkZ = playerChunkZ + dz;
					long key = chunkKey(chunkX, chunkZ);
					if (discoveredTiles.containsKey(key)) {
						continue;
					}

					// Do not cause world generation merely to draw a strategy map.
					if (overworld.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
						continue;
					}

					MapTile tile = sampleTile(overworld, chunkX, chunkZ);
					discoveredTiles.put(key, tile);
					discoveryOrder.add(tile);
					changed = true;
				}
			}
		}
		return changed;
	}

	private boolean discoverAroundVillagers(ServerLevel overworld) {
		boolean changed = false;
		// Large settlements frequently have dozens of workers in the same chunk.
		// Discover once per occupied centre chunk instead of rechecking the same 5x5
		// neighbourhood for every individual person.
		Set<Long> visitedCentres = new HashSet<>();
		for (PhysicalVillagerSystem.VillagerDiscoveryMarker marker : PhysicalVillagerSystem.getInstance()
				.snapshotDiscoveryMarkers()) {
			if (marker == null)
				continue;
			if (!projectionOriginSet) {
				projectionOriginBlockX = alignToChunkCentre(marker.blockX());
				projectionOriginBlockZ = alignToChunkCentre(marker.blockZ());
				projectionOriginSet = true;
				changed = true;
			}
			int chunkX = Math.floorDiv(marker.blockX(), CHUNK_SIZE);
			int chunkZ = Math.floorDiv(marker.blockZ(), CHUNK_SIZE);
			if (!visitedCentres.add(chunkKey(chunkX, chunkZ)))
				continue;
			changed |= discoverLoadedChunksAround(overworld, marker.blockX(), marker.blockZ(),
					VILLAGER_DISCOVERY_RADIUS_CHUNKS);
		}
		return changed;
	}

	/**
	 * Adds only already-loaded chunks; villager exploration never generates
	 * terrain.
	 */
	private boolean discoverLoadedChunksAround(ServerLevel overworld, int blockX, int blockZ, int radiusChunks) {
		boolean changed = false;
		int centreChunkX = Math.floorDiv(blockX, CHUNK_SIZE);
		int centreChunkZ = Math.floorDiv(blockZ, CHUNK_SIZE);
		for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
			for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
				if (dx * dx + dz * dz > radiusChunks * radiusChunks)
					continue;
				int chunkX = centreChunkX + dx;
				int chunkZ = centreChunkZ + dz;
				long key = chunkKey(chunkX, chunkZ);
				if (discoveredTiles.containsKey(key))
					continue;
				if (overworld.getChunkSource().getChunkNow(chunkX, chunkZ) == null)
					continue;
				MapTile tile = sampleTile(overworld, chunkX, chunkZ);
				discoveredTiles.put(key, tile);
				discoveryOrder.add(tile);
				changed = true;
			}
		}
		return changed;
	}

	private static MapTile sampleTile(ServerLevel level, int chunkX, int chunkZ) {
		int blockX = chunkX * CHUNK_SIZE + CHUNK_SIZE / 2;
		int blockZ = chunkZ * CHUNK_SIZE + CHUNK_SIZE / 2;
		int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
		BlockPos surface = new BlockPos(blockX, surfaceY - 1, blockZ);
		boolean fluid = !level.getFluidState(surface).isEmpty();
		int seaLevel = level.getSeaLevel();

		Terrain terrain;
		if (fluid) {
			terrain = Terrain.WATER;
		} else if (surfaceY >= seaLevel + 42) {
			terrain = Terrain.HIGHLAND;
		} else if (surfaceY <= seaLevel + 4) {
			terrain = Terrain.COASTAL;
		} else {
			terrain = Terrain.LAND;
		}

		return new MapTile(chunkX, chunkZ, terrain, surfaceY);
	}

	/**
	 * Gives active historical countries stable Minecraft positions on discovered
	 * land. A country is not anchored until land near its canonical historical
	 * target has actually been discovered, so the map never reveals unexplored
	 * Minecraft terrain merely because a civilisation exists.
	 */
	private boolean anchorActiveCivilisations() {
		if (!projectionOriginSet || discoveredTiles.isEmpty()) {
			return false;
		}

		boolean changed = false;
		List<Civilisation> positioned = DataManager.getCivilisations().values().stream().filter(Civilisation::isActive)
				.filter(Civilisation::hasWorldMapPosition)
				.sorted((a, b) -> String.valueOf(a.getId()).compareTo(String.valueOf(b.getId()))).toList();

		Set<Long> occupiedChunks = new HashSet<>();
		for (Civilisation civilisation : positioned) {
			occupiedChunks.add(chunkKey(Math.floorDiv(civilisation.getWorldMapBlockX(), CHUNK_SIZE),
					Math.floorDiv(civilisation.getWorldMapBlockZ(), CHUNK_SIZE)));
		}

		// Start-year then ID ordering is deterministic across dedicated servers.
		List<Civilisation> waiting = DataManager.getCivilisations().values().stream().filter(Civilisation::isActive)
				.filter(civilisation -> !civilisation.hasWorldMapPosition()).sorted((a, b) -> {
					int byStart = Long.compare(a.getStartYear(), b.getStartYear());
					if (byStart != 0)
						return byStart;
					return String.valueOf(a.getId()).compareTo(String.valueOf(b.getId()));
				}).toList();

		List<Civilisation> mutablePositioned = new ArrayList<>(positioned);
		for (Civilisation civilisation : waiting) {
			Civilisation neighbour = chooseExistingNeighbour(civilisation, mutablePositioned);
			MapTile chosen;

			if (neighbour != null) {
				chosen = bestCompanyTile(civilisation, neighbour, occupiedChunks, MIN_COUNTRY_SPACING_CHUNKS);
				if (chosen == null) {
					chosen = bestCompanyTile(civilisation, neighbour, occupiedChunks,
							RELAXED_MIN_COUNTRY_SPACING_CHUNKS);
				}
			} else {
				chosen = nearestHistoricalTileWithRoom(civilisation, occupiedChunks, MIN_COUNTRY_SPACING_CHUNKS);
				if (chosen == null) {
					chosen = nearestHistoricalTileWithRoom(civilisation, occupiedChunks,
							RELAXED_MIN_COUNTRY_SPACING_CHUNKS);
				}
			}

			// Do not crowd a civilisation merely to force a marker onto the map.
			// It remains active and will be placed after players discover enough
			// suitable land around the existing cluster.
			if (chosen == null) {
				continue;
			}

			civilisation.setWorldMapPosition(chosen.centreBlockX(), chosen.centreBlockZ());
			occupiedChunks.add(chunkKey(chosen.chunkX(), chosen.chunkZ()));
			mutablePositioned.add(civilisation);
			changed = true;
		}
		return changed;
	}

	/**
	 * Prefer the already-positioned country that is closest in the historical
	 * topology. Player-created countries participate as ordinary neighbours, so a
	 * lone player tends to receive new historical company as it appears.
	 */
	private Civilisation chooseExistingNeighbour(Civilisation newcomer, List<Civilisation> positioned) {
		Civilisation best = null;
		long bestScore = Long.MAX_VALUE;
		for (Civilisation candidate : positioned) {
			if (candidate == newcomer || !candidate.isActive() || !candidate.hasWorldMapPosition())
				continue;

			long dx = (long) newcomer.getMapXPercent() - candidate.getMapXPercent();
			long dz = (long) newcomer.getMapYPercent() - candidate.getMapYPercent();
			long topologyDistance = dx * dx + dz * dz;

			// A stable tiny tie-breaker keeps placement identical after reloads.
			long tie = Integer.toUnsignedLong(String.valueOf(candidate.getId()).hashCode()) & 0xffffL;
			long score = topologyDistance * 65_536L + tie;
			if (score < bestScore) {
				bestScore = score;
				best = candidate;
			}
		}
		return best;
	}

	private MapTile bestCompanyTile(Civilisation civilisation, Civilisation neighbour, Set<Long> occupiedChunks,
			int minimumSpacingChunks) {
		int neighbourChunkX = Math.floorDiv(neighbour.getWorldMapBlockX(), CHUNK_SIZE);
		int neighbourChunkZ = Math.floorDiv(neighbour.getWorldMapBlockZ(), CHUNK_SIZE);
		int historicalX = historicalTargetBlockX(civilisation);
		int historicalZ = historicalTargetBlockZ(civilisation);

		MapTile best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		for (MapTile tile : discoveredTiles.values()) {
			if (!tile.terrain().isLand())
				continue;
			if (occupiedChunks.contains(chunkKey(tile.chunkX(), tile.chunkZ())))
				continue;

			double parentDistance = chunkDistance(tile.chunkX(), tile.chunkZ(), neighbourChunkX, neighbourChunkZ);
			if (parentDistance < minimumSpacingChunks || parentDistance > MAX_COMPANY_DISTANCE_CHUNKS) {
				continue;
			}

			double nearestOther = nearestCapitalDistanceChunks(tile, occupiedChunks);
			if (nearestOther < minimumSpacingChunks)
				continue;

			double preferredPenalty = parentDistance - PREFERRED_COUNTRY_SPACING_CHUNKS;
			preferredPenalty *= preferredPenalty;
			double historicalDx = (tile.centreBlockX() - historicalX) / (double) CHUNK_SIZE;
			double historicalDz = (tile.centreBlockZ() - historicalZ) / (double) CHUNK_SIZE;
			double historicalPenalty = historicalDx * historicalDx + historicalDz * historicalDz;

			// The company ring is the primary rule. Historical topology is a
			// secondary nudge that decides which side of the neighbour is best.
			double score = preferredPenalty * 8.0 + historicalPenalty * 0.20
					- Math.min(nearestOther, PREFERRED_COUNTRY_SPACING_CHUNKS) * 0.10;
			if (score < bestScore) {
				bestScore = score;
				best = tile;
			}
		}
		return best;
	}

	private MapTile nearestHistoricalTileWithRoom(Civilisation civilisation, Set<Long> occupiedChunks,
			int minimumSpacingChunks) {
		int targetX = historicalTargetBlockX(civilisation);
		int targetZ = historicalTargetBlockZ(civilisation);
		MapTile best = null;
		long bestDistance = Long.MAX_VALUE;

		for (MapTile tile : discoveredTiles.values()) {
			if (!tile.terrain().isLand())
				continue;
			if (occupiedChunks.contains(chunkKey(tile.chunkX(), tile.chunkZ())))
				continue;
			if (nearestCapitalDistanceChunks(tile, occupiedChunks) < minimumSpacingChunks)
				continue;

			long dx = (long) tile.centreBlockX() - targetX;
			long dz = (long) tile.centreBlockZ() - targetZ;
			long distance = dx * dx + dz * dz;
			if (distance < bestDistance) {
				bestDistance = distance;
				best = tile;
			}
		}
		return best;
	}

	private static double chunkDistance(int ax, int az, int bx, int bz) {
		long dx = (long) ax - bx;
		long dz = (long) az - bz;
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static double nearestCapitalDistanceChunks(MapTile tile, Set<Long> occupiedChunks) {
		if (occupiedChunks.isEmpty())
			return Double.POSITIVE_INFINITY;
		double nearest = Double.POSITIVE_INFINITY;
		for (long key : occupiedChunks) {
			int chunkX = (int) (key >> 32);
			int chunkZ = (int) key;
			nearest = Math.min(nearest, chunkDistance(tile.chunkX(), tile.chunkZ(), chunkX, chunkZ));
		}
		return nearest;
	}

	public Snapshot snapshot() {
		return publishedSnapshot;
	}

	/** Monotonic discovery cursor used for efficient multiplayer map deltas. */
	public synchronized int discoveredTileCount() {
		return discoveryOrder.size();
	}

	/** Returns only chunks discovered after the supplied client cursor. */
	public synchronized List<MapTile> discoveredTilesSince(int knownCount) {
		int from = Math.max(0, Math.min(knownCount, discoveryOrder.size()));
		return List.copyOf(discoveryOrder.subList(from, discoveryOrder.size()));
	}

	public synchronized int historicalTargetBlockX(Civilisation civilisation) {
		if (!projectionOriginSet || civilisation == null)
			return 0;
		return projectionOriginBlockX + (civilisation.getMapXPercent() - 50) * HISTORICAL_BLOCKS_PER_PERCENT;
	}

	public synchronized int historicalTargetBlockZ(Civilisation civilisation) {
		if (!projectionOriginSet || civilisation == null)
			return 0;
		return projectionOriginBlockZ + (civilisation.getMapYPercent() - 50) * HISTORICAL_BLOCKS_PER_PERCENT;
	}

	public synchronized boolean hasProjectionOrigin() {
		return projectionOriginSet;
	}

	/**
	 * Assigns the player's newly-created country to the player's actual MC
	 * location.
	 */
	public synchronized void assignPlayerCountryLocation(Civilisation civilisation, int blockX, int blockZ) {
		if (civilisation == null)
			return;
		civilisation.setWorldMapPosition(blockX, blockZ);
		if (!projectionOriginSet) {
			projectionOriginBlockX = alignToChunkCentre(blockX);
			projectionOriginBlockZ = alignToChunkCentre(blockZ);
			projectionOriginSet = true;
		}
		dirty = true;
		publishSnapshot();
	}

	private void publishSnapshot() {
		if (discoveredTiles.isEmpty()) {
			publishedSnapshot = new Snapshot(List.of(), Set.of(), projectionOriginSet, projectionOriginBlockX,
					projectionOriginBlockZ, 0, 0, 0, 0);
			return;
		}

		List<MapTile> tiles = new ArrayList<>(discoveredTiles.values());
		tiles.sort((a, b) -> {
			int byZ = Integer.compare(a.chunkZ(), b.chunkZ());
			return byZ != 0 ? byZ : Integer.compare(a.chunkX(), b.chunkX());
		});
		Set<Long> keys = new HashSet<>(discoveredTiles.keySet());

		int minChunkX = Integer.MAX_VALUE;
		int maxChunkX = Integer.MIN_VALUE;
		int minChunkZ = Integer.MAX_VALUE;
		int maxChunkZ = Integer.MIN_VALUE;
		for (MapTile tile : tiles) {
			minChunkX = Math.min(minChunkX, tile.chunkX());
			maxChunkX = Math.max(maxChunkX, tile.chunkX());
			minChunkZ = Math.min(minChunkZ, tile.chunkZ());
			maxChunkZ = Math.max(maxChunkZ, tile.chunkZ());
		}

		publishedSnapshot = new Snapshot(Collections.unmodifiableList(tiles), Collections.unmodifiableSet(keys),
				projectionOriginSet, projectionOriginBlockX, projectionOriginBlockZ, minChunkX, maxChunkX, minChunkZ,
				maxChunkZ);
	}

	private void load() {
		if (worldRoot == null)
			return;
		Path file = stateDirectory(worldRoot).resolve(STATE_FILE);
		if (!Files.isRegularFile(file))
			return;

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			PersistedState state = GSON.fromJson(reader, PersistedState.class);
			if (state == null || state.version > STATE_VERSION)
				return;

			projectionOriginSet = state.projectionOriginSet;
			projectionOriginBlockX = state.projectionOriginBlockX;
			projectionOriginBlockZ = state.projectionOriginBlockZ;
			if (state.tiles != null) {
				for (MapTile tile : state.tiles) {
					if (tile != null && tile.terrain() != null) {
						discoveredTiles.put(chunkKey(tile.chunkX(), tile.chunkZ()), tile);
						discoveryOrder.add(tile);
					}
				}
			}
		} catch (IOException | JsonParseException e) {
			System.err.println("Could not load Grand Strategy discovered map from " + file);
			e.printStackTrace();
		}
	}

	private void save() {
		if (worldRoot == null)
			return;
		Path directory = stateDirectory(worldRoot);
		Path file = directory.resolve(STATE_FILE);
		Path temporary = directory.resolve(STATE_FILE + ".tmp");

		try {
			Files.createDirectories(directory);
			PersistedState state = new PersistedState();
			state.version = STATE_VERSION;
			state.projectionOriginSet = projectionOriginSet;
			state.projectionOriginBlockX = projectionOriginBlockX;
			state.projectionOriginBlockZ = projectionOriginBlockZ;
			state.tiles = new ArrayList<>(discoveryOrder);

			try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
				GSON.toJson(state, writer);
			}
			try {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
			dirty = false;
		} catch (IOException e) {
			System.err.println("Could not save Grand Strategy discovered map to " + file);
			e.printStackTrace();
		}
	}

	private static Path stateDirectory(Path worldRoot) {
		return worldRoot.toAbsolutePath().normalize().resolve("grandstrategy").resolve("state");
	}

	private static int floorToInt(double value) {
		int whole = (int) value;
		return value < whole ? whole - 1 : whole;
	}

	private static int alignToChunkCentre(int block) {
		return Math.floorDiv(block, CHUNK_SIZE) * CHUNK_SIZE + CHUNK_SIZE / 2;
	}

	public static long chunkKey(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
	}

	public enum Terrain {
		WATER, COASTAL, LAND, HIGHLAND;

		public boolean isLand() {
			return this != WATER;
		}
	}

	public record MapTile(int chunkX, int chunkZ, Terrain terrain, int surfaceY) {
		public int minBlockX() {
			return chunkX * CHUNK_SIZE;
		}

		public int minBlockZ() {
			return chunkZ * CHUNK_SIZE;
		}

		public int centreBlockX() {
			return minBlockX() + CHUNK_SIZE / 2;
		}

		public int centreBlockZ() {
			return minBlockZ() + CHUNK_SIZE / 2;
		}
	}

	/** Immutable server-published view used by the integrated client GUI. */
	public record Snapshot(List<MapTile> tiles, Set<Long> discoveredChunkKeys, boolean projectionOriginSet,
			int projectionOriginBlockX, int projectionOriginBlockZ, int minChunkX, int maxChunkX, int minChunkZ,
			int maxChunkZ) {

		public static Snapshot empty() {
			return new Snapshot(List.of(), Set.of(), false, 0, 0, 0, 0, 0, 0);
		}

		public boolean isEmpty() {
			return tiles.isEmpty();
		}

		public boolean isDiscoveredBlock(double blockX, double blockZ) {
			int chunkX = Math.floorDiv(floorToInt(blockX), CHUNK_SIZE);
			int chunkZ = Math.floorDiv(floorToInt(blockZ), CHUNK_SIZE);
			return discoveredChunkKeys.contains(chunkKey(chunkX, chunkZ));
		}

		public double minBlockX() {
			return (double) minChunkX * CHUNK_SIZE;
		}

		public double maxBlockX() {
			return (double) (maxChunkX + 1) * CHUNK_SIZE;
		}

		public double minBlockZ() {
			return (double) minChunkZ * CHUNK_SIZE;
		}

		public double maxBlockZ() {
			return (double) (maxChunkZ + 1) * CHUNK_SIZE;
		}
	}

	private static final class PersistedState {
		int version = STATE_VERSION;
		boolean projectionOriginSet;
		int projectionOriginBlockX;
		int projectionOriginBlockZ;
		List<MapTile> tiles = new ArrayList<>();
	}
}
