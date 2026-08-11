package com.asbestosstar.grandstrategy.common.world;

import com.asbestosstar.grandstrategy.common.data.City;
import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.DataManager;
import com.asbestosstar.grandstrategy.common.data.FactoryRecipe;
import com.asbestosstar.grandstrategy.common.data.FactoryType;
import com.asbestosstar.grandstrategy.common.data.MinecraftItemRegistry;
import com.asbestosstar.grandstrategy.common.data.ProductionOrder;
import com.asbestosstar.grandstrategy.common.data.Providence;
import com.asbestosstar.grandstrategy.common.data.ResourceType;
import com.asbestosstar.grandstrategy.common.data.VillagerJob;
import com.asbestosstar.grandstrategy.common.data.WorkerToolTier;
import com.asbestosstar.grandstrategy.common.ai.CivilisationTrafficManager;
import com.asbestosstar.grandstrategy.common.ai.EscapeSnapshot;
import com.asbestosstar.grandstrategy.common.ai.NavigationFailure;
import com.asbestosstar.grandstrategy.common.ai.NavigationSnapshot;
import com.asbestosstar.grandstrategy.common.ai.PlannerResult;
import com.asbestosstar.grandstrategy.common.ai.WorkerBrainState;
import com.asbestosstar.grandstrategy.common.ai.WorkerIntent;
import com.asbestosstar.grandstrategy.common.ai.WorkerPlannerService;
import com.asbestosstar.grandstrategy.common.engine.ProvidenceSystem;
import com.asbestosstar.grandstrategy.common.engine.ResearchSystem;
import com.asbestosstar.grandstrategy.common.engine.SocietySystem;
import com.asbestosstar.grandstrategy.common.engine.WarSystem;
import com.asbestosstar.grandstrategy.common.world.WorldMapTracker;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Server-authoritative physical population layer.
 *
 * A Grand Strategy population point is now represented by a real vanilla
 * PathfinderMob entity whenever the country's home/supply-capital chunk is
 * loaded. Villagers path to real work sites, change blocks in the Overworld and
 * carry their output back to ordinary vanilla chests at supply capitals. The
 * chest contents are the physical backing for the civilisation resource ledger:
 * players can take from or add to those chests and the strategy economy follows
 * the actual inventory.
 *
 * No loader events or loader networking are used here. This class is ticked
 * only from the MinecraftServer Mixin through WorldSessionManager, so the same
 * logic is authoritative in single-player, LAN and dedicated servers.
 */
public final class PhysicalVillagerSystem {
	private static final PhysicalVillagerSystem INSTANCE = new PhysicalVillagerSystem();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final String STATE_FILE = "physical_villagers.json";
	private static final int STATE_VERSION = 14;
	private static final int WORKER_RECONCILE_TICKS = 20;
	private static final int DEPOT_RECONCILE_TICKS = 20;
	private static final int SAVE_INTERVAL_TICKS = 6_000;
	private static final int WORK_THINK_INTERVAL_TICKS = 10;
	private static final int RESOURCE_SYNC_INTERVAL_TICKS = 20;
	// Physical people spawn well-fed and receive a long initial grace period before
	// hunger can interrupt their work. Three full Minecraft days pass before a new
	// worker's first meal can become due, with another day of deterministic spread
	// so a whole settlement never becomes hungry at once. After that, normal meals
	// are only once per Minecraft day. Starvation also has a much longer grace.
	private static final long FIRST_MEAL_GRACE_TICKS = 72_000L;
	private static final long FIRST_MEAL_SPREAD_TICKS = 24_000L;
	private static final long MEAL_INTERVAL_TICKS = 24_000L;
	private static final int MEAL_RETRY_TICKS = 200;
	private static final long STARVATION_GRACE_TICKS = 24_000L;
	private static final int DEPOT_CHESTS_PER_CAPITAL = 8;
	private static final int CARRY_LIMIT = 16;
	// Miners are bulk extractors rather than couriers. Civilian workers keep the
	// compact 16-item hand-carry limit, while each miner can hold eight full stacks
	// of processed mine output before returning to storage.
	private static final int MINER_CARRY_LIMIT = 512;
	// A miner may still return early after a long completely idle spell, but only
	// when it is already carrying a substantial fraction of its 512-item capacity.
	private static final int MINER_PARTIAL_RETURN_MIN_LOAD = 384;
	private static final int MINER_PARTIAL_RETURN_IDLE_TICKS = 600;
	private static final int TREE_SEARCH_RADIUS = 96;
	private static final int FARM_RADIUS = 12;
	// Permanent per-worker build zones. Every farmer now owns a genuinely large
	// 52x52 field (2,704 cells). The field is still exclusive to that worker, so
	// increasing food capacity does not reintroduce farmer clumping or competing
	// edits. A three-block district gap keeps neighbouring farms distinct.
	private static final int FARMER_ZONE_WIDTH = 52;
	private static final int FARMER_ZONE_DEPTH = 52;
	private static final int FARMER_ZONE_SPACING = 55;
	private static final int FARMER_ZONE_COLUMNS = 4;
	private static final int FARM_IRRIGATION_SPAN = 9;
	private static final int FACTORY_WORK_ZONE_WIDTH = 11;
	private static final int FACTORY_WORK_ZONE_DEPTH = 9;
	private static final int FACTORY_WORK_ZONE_SPACING_X = 13;
	private static final int FACTORY_WORK_ZONE_SPACING_Z = 11;
	private static final int FACTORY_WORK_ZONE_COLUMNS = 4;
	// Factories are intentionally compact starter workshops. The old 7x5x3 shell
	// required 43 stone and 64 plank-equivalent wood before the first tool could be
	// crafted, creating a circular early-game bottleneck for hand miners. A 5x4
	// two-block-high workshop still has a real floor, walls, crafting table and
	// furnace, but reaches useful production much sooner.
	private static final int FACTORY_BUILD_WIDTH = 5;
	private static final int FACTORY_BUILD_DEPTH = 4;
	private static final int FACTORY_WALL_HEIGHT = 2;
	private static final int FACTORY_SHELL_STONE_COST = FACTORY_BUILD_WIDTH * FACTORY_BUILD_DEPTH;
	private static final int FACTORY_SHELL_WOOD_COST = (2 * (FACTORY_BUILD_WIDTH + FACTORY_BUILD_DEPTH) - 4)
			* FACTORY_WALL_HEIGHT;
	private static final int FACTORY_CRAFTING_TABLE_WOOD_COST = 4;
	private static final int FACTORY_CORE_WOOD_COST = 6;
	private static final int FACTORY_FURNACE_STONE_COST = 8;
	private static final int FACTORY_TOTAL_STONE_COST = FACTORY_SHELL_STONE_COST + FACTORY_FURNACE_STONE_COST;
	private static final int FACTORY_TOTAL_WOOD_COST = FACTORY_SHELL_WOOD_COST + FACTORY_CRAFTING_TABLE_WOOD_COST;
	// Early settlements can establish a complete wooden workshop without stone.
	// Its floor and first wall course are all oak planks, with a crafting table and
	// a barrel core. It is intentionally made from normal flammable wood blocks.
	private static final int WOOD_FACTORY_WALL_HEIGHT = 1;
	private static final int WOOD_FACTORY_FLOOR_WOOD_COST = FACTORY_BUILD_WIDTH * FACTORY_BUILD_DEPTH;
	private static final int WOOD_FACTORY_WALL_WOOD_COST = (2 * (FACTORY_BUILD_WIDTH + FACTORY_BUILD_DEPTH) - 4)
			* WOOD_FACTORY_WALL_HEIGHT;
	private static final int WOOD_FACTORY_TOTAL_WOOD_COST = WOOD_FACTORY_FLOOR_WOOD_COST + WOOD_FACTORY_WALL_WOOD_COST
			+ FACTORY_CRAFTING_TABLE_WOOD_COST + FACTORY_CORE_WOOD_COST;
	// Upgrading the wooden workshop retains its first wall course/crafting
	// table/core.
	private static final int FACTORY_UPGRADE_WOOD_COST = (2 * (FACTORY_BUILD_WIDTH + FACTORY_BUILD_DEPTH) - 4)
			* (FACTORY_WALL_HEIGHT - WOOD_FACTORY_WALL_HEIGHT);
	private static final int FACTORY_PRODUCTION_COOLDOWN_TICKS = 20;
	private static final int FACTORY_TORCH_BATCH = 4;
	private static final int ADMIN_TORCH_CARRY_BATCH = 8;
	private static final int ADMIN_LIGHTING_GRID_SPACING = 7;
	private static final int ADMIN_LIGHTING_GRID_SIDE = 15;
	private static final int ADMIN_LIGHTING_SEARCH_ATTEMPTS = 96;
	private static final int ADMIN_TORCH_MIN_SEPARATION = 5;
	private static final int ADMIN_MAX_BLOCK_LIGHT = 7;
	private static final int CASUALTY_CONFIRM_TICKS = 40;
	private static final double WORK_DROP_SEARCH_RADIUS = 24.0;
	// Work output is an interaction target, not a block the worker must stand on.
	// A slightly generous reach prevents farmers from stopping on the edge of a
	// planted row while an item entity sits one block inside the crop collision.
	private static final double WORK_DROP_PICKUP_REACH = 3.6;
	private static final int BONE_MEAL_CARRY_BATCH = 12;
	private static final double SOLDIER_BONE_SEARCH_RADIUS = 18.0;
	private static final int NAVIGATION_STUCK_CHECK_TICKS = 20;
	private static final int NAVIGATION_STUCK_CHECK_LIMIT = 5;
	private static final int OBSTACLE_BREAK_STUCK_THRESHOLD = 1;
	// Non-road workers first ask a road builder for help. If they remain genuinely
	// trapped for several stuck checks they may excavate a two-block-high
	// horizontal/
	// upward escape opening themselves, but they never dig the floor beneath them.
	private static final int SELF_ESCAPE_DIG_STUCK_THRESHOLD = 3;
	private static final long OBSTACLE_BREAK_COOLDOWN_TICKS = 8L;
	// Only one worker at a time may modify the same navigation obstruction. This
	// turns terrain clearing into shared infrastructure instead of a crowd of
	// villagers independently excavating the same wall/floor.
	private static final long OBSTACLE_CLAIM_TTL_TICKS = 40L;
	private static final long TRAVEL_ASSIST_REQUEST_TTL_TICKS = 1_200L;
	private static final int MAX_TRAVEL_ASSIST_REQUESTS = 128;
	private static final double LUMBERJACK_CHOP_REACH = 4.75;
	private static final int LUMBERJACK_VERTICAL_CHOP_REACH = 7;
	private static final int WORKER_OVERHEAD_CLEARANCE = 5;
	private static final int MAX_TREE_LOG_BLOCKS = 128;
	private static final int MAX_TREE_LEAF_BLOCKS = 192;
	private static final int CRAFTING_TABLE_SEARCH_RADIUS = 64;
	private static final int FARM_ZONE_OFFSET = 48;
	private static final int FORESTRY_ZONE_OFFSET = 34;
	private static final int MINE_ZONE_OFFSET = 30;
	private static final int FACTORY_ZONE_OFFSET = 30;
	private static final int FACTORY_ZONE_SPACING_X = 12;
	private static final int FACTORY_ZONE_SPACING_Z = 10;
	private static final int FACTORY_STONE_PICKUP_BATCH = 64;
	private static final int FACTORY_WOOD_PICKUP_BATCH = 64;
	private static final int RETURN_WITH_PARTIAL_LOAD_TICKS = 60;
	private static final int FARM_WATER_RECHECK_TICKS = 200;
	private static final int FARM_WATER_SOURCE_SEARCH_RADIUS = 64;
	private static final int NON_MINER_MINE_EXCLUSION_RADIUS = 5;
	// Dedicated Grand Strategy humanoids are not vanilla villagers anymore, so the
	// old 0.55 navigation multiplier made them visibly crawl. A normal worker now
	// walks at roughly player-like travel pace; constructed roads and soldiers keep
	// a noticeable but controlled bonus rather than requiring workers to sprint
	// everywhere.
	private static final double NORMAL_WALK_SPEED = 1.10;
	private static final double ROAD_WALK_SPEED = 1.35;
	// Soldiers deliberately move faster than civilian workers so armies can close
	// distance and reach wartime objectives without looking like ordinary
	// labourers.
	private static final double SOLDIER_WALK_SPEED = 1.45;
	private static final double SOLDIER_ROAD_WALK_SPEED = 1.70;
	private static final int NAVIGATION_REASSERT_TICKS = 10;
	// Dedicated GS bodies have no vanilla goal system to restart locomotion.
	private static final int HUMANOID_NAV_HEARTBEAT_TICKS = 10;
	// Absolute liveness guarantees. These sit above normal pathfinding: if the AI
	// claims a humanoid should be travelling but its body has not moved,
	// increasingly
	// strong recovery is applied. No movement/planner state may wait forever.
	private static final int LIVENESS_SOFT_RECOVERY_TICKS = 40; // 2 seconds
	private static final int LIVENESS_HARD_RECOVERY_TICKS = 100; // 5 seconds
	private static final int LIVENESS_FULL_RESET_TICKS = 200; // 10 seconds
	private static final int PLANNER_REQUEST_TIMEOUT_TICKS = 80; // 4 seconds
	// Console diagnostics are deliberately throttled per worker so a bad route is
	// visible without turning one trapped crowd into thousands of log lines.
	private static final long NAVIGATION_DEBUG_LOG_INTERVAL_TICKS = 40L;
	// Depot records are normally authoritative. If a worker cannot find a chest we
	// perform a bounded physical rescan near its home command post and permanently
	// repair the cache. This is failure-only and throttled, not a per-tick world
	// scan.
	private static final long DEPOT_RECOVERY_SCAN_INTERVAL_TICKS = 100L;
	private static final int DEPOT_RECOVERY_SCAN_RADIUS = 22;
	private static final double LIVENESS_MOVEMENT_EPSILON_SQ = 0.09;
	private static final double LIVENESS_NUDGE_SPEED = 0.16;
	private static final int NAVIGATION_OBSTACLE_SCAN_TICKS = 10;
	// Hierarchical AI planner. Long-distance movement is planned on immutable
	// coarse
	// world snapshots off-thread; vanilla PathNavigation executes only local
	// waypoints.
	private static final int STRATEGIC_ROUTE_THRESHOLD = 24;
	private static final int STRATEGIC_ROUTE_CELL_SIZE = 4;
	private static final int STRATEGIC_ROUTE_MAX_SEGMENT = 224;
	private static final int STRATEGIC_ROUTE_CORRIDOR_MARGIN = 28;
	private static final int STRATEGIC_ROUTE_MAX_CELLS_PER_AXIS = 96;
	// Capturing a route snapshot touches live chunks and heightmaps and therefore
	// MUST
	// happen on the Minecraft server thread. Bound that synchronous work per tick;
	// the
	// background planner can consume snapshots much faster than the main thread
	// should
	// be allowed to manufacture them during a 100+ worker route burst.
	private static final int MAX_NAVIGATION_SNAPSHOT_CAPTURES_PER_TICK = 3;
	private static final int MAX_ESCAPE_SNAPSHOT_CAPTURES_PER_TICK = 2;
	// Above this population, Grand Strategy services ordinary worker AI in rotating
	// cohorts. Vanilla PathNavigation/entity physics continue every Minecraft tick;
	// only GS decision/inspection work is budgeted. This prevents O(population)
	// heavy
	// server-thread logic from consuming the full 50 ms tick budget.
	private static final int FULL_RATE_WORKER_LIMIT = 96;
	private static final int TARGET_WORKER_SERVICES_PER_TICK = 36;
	private static final int MAX_WORKER_SERVICE_STRIDE = 8;
	private static final int ESCAPE_SNAPSHOT_RADIUS_XZ = 10;
	private static final int ESCAPE_SNAPSHOT_BELOW = 4;
	private static final int ESCAPE_SNAPSHOT_ABOVE = 10;
	private static final int ESCAPE_REQUEST_FAILURE_THRESHOLD = 3;
	private static final int SOLDIER_DECISION_INTERVAL_TICKS = 4;
	// Recovery must remain extremely cheap: v6.21 searched hundreds/thousands of
	// height-map cells per trapped villager, which could put the whole server many
	// ticks behind. Water/pit recovery is now a local O(1) scramble toward the
	// worker's existing destination, with at most a few immediate block checks.
	private static final int WATER_ESCAPE_SEARCH_RADIUS = 5;
	private static final int WATER_ESCAPE_RESCAN_TICKS = 20;
	private static final int WATER_ESCAPE_CARVE_AFTER_TICKS = 30;
	private static final double WATER_SWIM_ASSIST = 0.14;
	private static final double WATER_UPWARD_ASSIST = 0.16;
	private static final double WATER_BANK_UPWARD_ASSIST = 0.42;
	private static final int EMERGENCY_CLIMB_TICKS = 50;
	private static final int EMERGENCY_CARVE_INTERVAL_TICKS = 8;
	private static final double EMERGENCY_CLIMB_HORIZONTAL_ASSIST = 0.11;
	private static final double EMERGENCY_CLIMB_UPWARD_ASSIST = 0.22;
	private static final double MINE_STAIR_WALK_SPEED = 1.55;
	private static final int MINE_STAIR_LOOKAHEAD_STEPS = 7;
	private static final double SOLDIER_ENGAGE_RANGE = 64.0;
	// Player-created countries using Army Auto scan farther for wartime country
	// units.
	// Their overflow soldiers also receive strategic pursuit/advance orders below,
	// so
	// a large player army does not return to garrison merely because the enemy is
	// outside the ordinary local combat radius.
	private static final double SOLDIER_PLAYER_AUTO_ENGAGE_RANGE = 128.0;
	private static final int SOLDIER_PLAYER_STRATEGIC_PURSUERS_PER_ENEMY = 4;
	private static final int SOLDIER_PLAYER_SUPPORT_RING_SLOTS = 16;
	private static final int SOLDIER_PLAYER_SUPPORT_RING_BASE_RADIUS = 18;
	private static final int SOLDIER_PLAYER_SUPPORT_RING_STEP = 5;
	private static final double SOLDIER_MONSTER_ENGAGE_RANGE = 64.0;
	private static final double SOLDIER_MANUAL_ENGAGE_RANGE = 16.0;
	private static final double SOLDIER_MELEE_RANGE = 3.25;
	private static final double SOLDIER_TARGET_MEMORY_RANGE = 96.0;
	private static final double SOLDIER_URGENT_MONSTER_RANGE = 32.0;
	// Automatic armies treat enemy command posts as their primary strategic
	// objective. Coordination limits stop the entire army dogpiling one beacon or
	// scattering across every enemy city at once. Soldiers assigned to an assault
	// only break off to fight enemy-country units that are close enough to block
	// their advance.
	private static final int SOLDIER_MAX_COMMAND_POST_ASSIGNEES = 6;
	private static final int SOLDIER_MAX_SIMULTANEOUS_CITY_OBJECTIVES = 2;
	private static final double SOLDIER_COMMAND_POST_BLOCKER_RANGE = 10.0;
	private static final long SOLDIER_ATTACK_COOLDOWN_TICKS = 10L;
	private static final String WORK_DROP_TAG = "grandstrategy_work_drop";

	private final Map<String, WorkerRecord> workers = new LinkedHashMap<>();
	private final Map<String, DepotRecord> depots = new LinkedHashMap<>();
	// Player-designated farm/factory districts selected directly from the strategy
	// map.
	// They are persistent independent locations; eligible workers claim an
	// unassigned
	// district only when they do not already have a profession district of their
	// own.
	private final Map<String, DesignatedWorkZoneRecord> designatedWorkZones = new LinkedHashMap<>();
	private final Map<String, EnumMap<ResourceType, Double>> lastStrategyValues = new HashMap<>();
	private final Map<String, EnumMap<ResourceType, Double>> fractionalLedger = new HashMap<>();
	// Minecraft 26.1.2 no longer exposes Entity#getTags() in the mappings used by
	// this project. Track Grand Strategy work drops directly by entity UUID instead
	// of depending on scoreboard-tag reads.
	private final Map<UUID, String> workDropOwners = new HashMap<>();
	// A physical drop can be claimed by only one worker at a time. Without this,
	// several villagers all select the same nearest item and visibly clump.
	private final Map<UUID, String> workDropClaims = new HashMap<>();
	// Short-lived cooperative claims for physical navigation work. A claimed block
	// is altered by one worker while nearby workers wait/repath through the same
	// opening rather than all digging competing holes.
	private final Map<String, ObstacleClaim> obstacleClaims = new HashMap<>();
	// Workers that encounter hard terrain can ask the road workforce for help. This
	// is intentionally transient: it coordinates live villagers without bloating
	// the
	// save file or resurrecting obsolete requests after a restart.
	private final Map<String, TravelAssistRequest> travelAssistRequests = new LinkedHashMap<>();

	// Runtime-only scratch/caches. They are rebuilt on the authoritative server
	// thread
	// and never enter the save file. The city index replaces hundreds of repeated
	// full
	// providence scans per tick, while the entity cache collapses repeated combat
	// UUID
	// lookups to at most one ServerLevel lookup per entity per tick.
	private final List<WorkerRecord> workerTickScratch = new ArrayList<>();
	private final Map<String, City> cityLookupCache = new HashMap<>();
	private final Map<String, Entity> entityLookupCache = new HashMap<>();
	private long cityLookupCacheTick = Long.MIN_VALUE;
	private int navigationSnapshotsThisTick;
	private int escapeSnapshotsThisTick;

	private Path worldRoot;
	private boolean running;
	private boolean dirty;
	private long ticks;

	private PhysicalVillagerSystem() {
	}

	public static PhysicalVillagerSystem getInstance() {
		return INSTANCE;
	}

	public synchronized void start(Path worldRoot) {
		stop();
		this.worldRoot = worldRoot.toAbsolutePath().normalize();
		this.running = true;
		this.dirty = false;
		this.ticks = 0L;
		this.workers.clear();
		this.depots.clear();
		this.designatedWorkZones.clear();
		this.lastStrategyValues.clear();
		this.fractionalLedger.clear();
		this.workDropOwners.clear();
		this.workDropClaims.clear();
		this.obstacleClaims.clear();
		this.travelAssistRequests.clear();
		this.workerTickScratch.clear();
		this.cityLookupCache.clear();
		this.entityLookupCache.clear();
		this.cityLookupCacheTick = Long.MIN_VALUE;
		this.navigationSnapshotsThisTick = 0;
		this.escapeSnapshotsThisTick = 0;
		CivilisationTrafficManager.getInstance().clear();
		WorkerPlannerService.getInstance().start();
		load();
	}

	public synchronized void stop() {
		if (running && worldRoot != null && dirty)
			save();
		running = false;
		dirty = false;
		ticks = 0L;
		worldRoot = null;
		workers.clear();
		depots.clear();
		designatedWorkZones.clear();
		lastStrategyValues.clear();
		fractionalLedger.clear();
		workDropOwners.clear();
		workDropClaims.clear();
		obstacleClaims.clear();
		travelAssistRequests.clear();
		workerTickScratch.clear();
		cityLookupCache.clear();
		entityLookupCache.clear();
		cityLookupCacheTick = Long.MIN_VALUE;
		navigationSnapshotsThisTick = 0;
		escapeSnapshotsThisTick = 0;
		WorkerPlannerService.getInstance().stop();
		CivilisationTrafficManager.getInstance().clear();
	}

	public synchronized void tick(MinecraftServer server) {
		if (!running || server == null)
			return;
		ServerLevel level = server.overworld();
		if (level == null)
			return;

		ticks++;
		navigationSnapshotsThisTick = 0;
		escapeSnapshotsThisTick = 0;
		entityLookupCache.clear();

		if (ticks % DEPOT_RECONCILE_TICKS == 0) {
			ensureSupplyDepots(level);
		}
		if (ticks % WORKER_RECONCILE_TICKS == 0) {
			reconcileWorkers(level);
		}
		if (ticks % RESOURCE_SYNC_INTERVAL_TICKS == 0) {
			synchroniseAllResourceLedgers(level);
		}

		// Do not make every physical person execute the entire GS decision pipeline on
		// every Minecraft tick. PathfinderMob navigation/physics still advance every
		// tick in vanilla; these rotating cohorts only budget Grand Strategy's own
		// inspections, block scans, liveness checks and profession decisions.
		int serviceStride = workerServiceStride();
		workerTickScratch.clear();
		workerTickScratch.addAll(workers.values());

		// One malformed worker must never prevent the rest of the civilisation from
		// ticking. Earlier builds executed the loop as one failure domain, so a
		// repeatable RuntimeException in a single worker could make an entire job
		// progression appear frozen. Isolate each body and self-heal its transient AI.
		for (WorkerRecord record : workerTickScratch) {
			if (!shouldServiceWorker(record, serviceStride))
				continue;
			try {
				tickWorker(level, record);
				record.consecutiveTickErrors = 0;
			} catch (RuntimeException error) {
				recoverWorkerAfterTickFailure(level, record, error);
			}
		}

		if (dirty && ticks % SAVE_INTERVAL_TICKS == 0)
			save();
	}

	/**
	 * Number of rotating cohorts needed to keep ordinary GS worker service bounded.
	 */
	private int workerServiceStride() {
		int population = workers.size();
		if (population <= FULL_RATE_WORKER_LIMIT)
			return 1;
		int stride = (population + TARGET_WORKER_SERVICES_PER_TICK - 1) / TARGET_WORKER_SERVICES_PER_TICK;
		return clampInt(stride, 2, MAX_WORKER_SERVICE_STRIDE);
	}

	private boolean shouldServiceWorker(WorkerRecord record, int stride) {
		if (record == null)
			return false;
		// These modes apply direct motion or recover a body and therefore remain
		// genuinely real-time even when the ordinary population is cohort-scheduled.
		if (record.mineTransitDirection != 0 || record.navigationAssistTicks > 0 || record.waterTicks > 0
				|| record.hasWaterEscapeTarget || record.minerShaftRecoveryActive || record.nonMinerMineAvoidanceActive
				|| record.needsNavigationRehydrate || record.consecutiveTickErrors > 0
				|| "work_drop".equals(record.targetKind)) {
			return true;
		}
		if (stride <= 1)
			return true;
		// Army decisions already have their own four-tick stagger. At high population
		// call soldiers exactly on that slot rather than combining two unrelated
		// moduli (which would turn a 4-tick decision cadence into 12/20+ ticks).
		if (parseJob(record.job) == VillagerJob.SOLDIER) {
			return Math.floorMod((int) ticks, SOLDIER_DECISION_INTERVAL_TICKS) == Math.floorMod(record.assignmentIndex,
					SOLDIER_DECISION_INTERVAL_TICKS);
		}
		int salt = record.assignmentIndex * 31 + (record.civilisationId == null ? 0 : record.civilisationId.hashCode())
				+ (record.uuid == null ? 0 : record.uuid.hashCode());
		return Math.floorMod((int) ticks, stride) == Math.floorMod(salt, stride);
	}

	/**
	 * Called after creating a player country so the first physical population does
	 * not have to wait for the normal discovery/providence interval.
	 */
	public synchronized void requestImmediateReconcile() {
		ticks = Math.max(ticks, WORKER_RECONCILE_TICKS - 1L);
	}

	/** Current physical population, used only for cheap throughput budgeting. */
	public synchronized int workerCount() {
		return workers.size();
	}

	/**
	 * Lightweight server-authoritative positions for the strategy map and discovery
	 * system. Positions come from the last real entity tick; they are never
	 * invented from strategy population counters.
	 */
	public synchronized List<VillagerMapMarker> snapshotMapMarkers() {
		List<VillagerMapMarker> markers = new ArrayList<>();
		for (WorkerRecord record : workers.values()) {
			if (record == null || record.uuid == null || record.civilisationId == null)
				continue;
			Civilisation civilisation = DataManager.getCivilisations().get(record.civilisationId);
			if (civilisation == null || !civilisation.isActive())
				continue;
			if (record.missingTicks >= CASUALTY_CONFIRM_TICKS)
				continue;
			VillagerJob job = parseJob(record.job);
			markers.add(new VillagerMapMarker(record.uuid, record.civilisationId, job == null ? "" : job.name(),
					record.appearanceVariant, record.assignmentIndex, toolTier(record).name(),
					describeWorkerStatus(record), carriedTotal(record), record.lastX, record.lastY, record.lastZ));
		}
		return List.copyOf(markers);
	}

	/**
	 * Minimal position-only snapshot used by world discovery. Unlike
	 * snapshotMapMarkers this does not build job/tool/status strings for every
	 * person once per second.
	 */
	public synchronized List<VillagerDiscoveryMarker> snapshotDiscoveryMarkers() {
		List<VillagerDiscoveryMarker> markers = new ArrayList<>(workers.size());
		for (WorkerRecord record : workers.values()) {
			if (record == null || record.uuid == null || record.civilisationId == null)
				continue;
			Civilisation civilisation = DataManager.getCivilisations().get(record.civilisationId);
			if (civilisation == null || !civilisation.isActive())
				continue;
			if (record.missingTicks >= CASUALTY_CONFIRM_TICKS)
				continue;
			markers.add(new VillagerDiscoveryMarker(record.lastX, record.lastZ));
		}
		return List.copyOf(markers);
	}

	/**
	 * Persistent map-selected farm/factory districts sent to clients for rendering.
	 */
	public synchronized List<WorkZoneMapMarker> snapshotWorkZones() {
		synchroniseAutomaticWorkZoneMarkers();
		cleanupDesignatedZoneAssignments();
		List<WorkZoneMapMarker> result = new ArrayList<>();
		for (DesignatedWorkZoneRecord zone : designatedWorkZones.values()) {
			if (zone == null || zone.id == null || zone.civilisationId == null || zone.type == null)
				continue;
			Civilisation civilisation = DataManager.getCivilisations().get(zone.civilisationId);
			if (civilisation == null || !civilisation.isActive())
				continue;
			result.add(new WorkZoneMapMarker(zone.id, zone.civilisationId, zone.type, zone.minX, zone.maxX, zone.minZ,
					zone.maxZ, zone.assignedWorkerUuid, zone.factoryTypeId));
		}
		return List.copyOf(result);
	}

	/**
	 * Creates a profession district centred on the clicked map coordinate. Every
	 * touched chunk must be discovered land that is either legally owned by the
	 * requesting civilisation or still unclaimed. Selecting neutral land does not
	 * annex it immediately; normal farm/factory construction claims it physically.
	 */
	public synchronized boolean designateWorkZone(String civilisationId, String type, int blockX, int blockZ) {
		if (civilisationId == null || civilisationId.isBlank() || type == null)
			return false;
		Civilisation civilisation = DataManager.getCivilisations().get(civilisationId);
		if (civilisation == null || !civilisation.isActive())
			return false;
		String rawType = type.trim();
		String[] typeParts = rawType.split(":", 2);
		String normalisedType = typeParts[0].toUpperCase(Locale.ROOT);
		String factoryTypeId = typeParts.length > 1 ? typeParts[1].toLowerCase(Locale.ROOT) : "wooden_factory";
		int width;
		int depth;
		if ("FARM".equals(normalisedType)) {
			width = FARMER_ZONE_WIDTH;
			depth = FARMER_ZONE_DEPTH;
		} else if ("FACTORY".equals(normalisedType)) {
			if (!ResearchSystem.factoryTypeAvailable(civilisation, factoryTypeId))
				return false;
			width = FACTORY_WORK_ZONE_WIDTH;
			depth = FACTORY_WORK_ZONE_DEPTH;
		} else {
			return false;
		}

		int minX = blockX - width / 2;
		int maxX = minX + width - 1;
		int minZ = blockZ - depth / 2;
		int maxZ = minZ + depth - 1;
		if (!zoneIsEligibleForDesignation(civilisationId, minX, maxX, minZ, maxZ))
			return false;
		if (zoneOverlapsExistingReservation(civilisationId, minX, maxX, minZ, maxZ))
			return false;

		String id = civilisationId + "|" + normalisedType + "|" + minX + "|" + minZ;
		if (designatedWorkZones.containsKey(id))
			return false;
		DesignatedWorkZoneRecord zone = new DesignatedWorkZoneRecord();
		zone.id = id;
		zone.civilisationId = civilisationId;
		zone.type = normalisedType;
		if ("FACTORY".equals(normalisedType))
			zone.factoryTypeId = factoryTypeId;
		zone.minX = minX;
		zone.maxX = maxX;
		zone.minZ = minZ;
		zone.maxZ = maxZ;
		designatedWorkZones.put(id, zone);
		dirty = true;
		return true;
	}

	/**
	 * Every worker-owned farm/factory district is a real strategic-map zone, even
	 * when the AI selected it automatically rather than the player clicking the
	 * map. Bounds make the id stable across saves and worker replacement. If the
	 * original worker dies, the visible district stays available for another
	 * matching worker.
	 */
	private void synchroniseAutomaticWorkZoneMarkers() {
		for (WorkerRecord worker : workers.values()) {
			if (worker == null || worker.uuid == null || worker.civilisationId == null)
				continue;
			VillagerJob job = parseJob(worker.job);
			if (job == VillagerJob.FARMER && worker.hasFarmerZone) {
				if (worker.farmerDesignatedZoneId == null
						|| !designatedWorkZones.containsKey(worker.farmerDesignatedZoneId)) {
					registerAutomaticWorkZone(worker, "FARM", worker.farmerZoneMinX, worker.farmerZoneMaxX,
							worker.farmerZoneMinZ, worker.farmerZoneMaxZ);
				}
			} else if (job == VillagerJob.FACTORY_BUILDER && worker.hasFactoryZone) {
				if (worker.factoryDesignatedZoneId == null
						|| !designatedWorkZones.containsKey(worker.factoryDesignatedZoneId)) {
					registerAutomaticWorkZone(worker, "FACTORY", worker.factoryZoneMinX, worker.factoryZoneMaxX,
							worker.factoryZoneMinZ, worker.factoryZoneMaxZ);
				}
			}
		}
	}

	private String registerAutomaticWorkZone(WorkerRecord worker, String type, int minX, int maxX, int minZ, int maxZ) {
		if (worker == null || worker.uuid == null || worker.civilisationId == null || type == null)
			return null;
		String normalisedType = type.toUpperCase(Locale.ROOT);
		String id = worker.civilisationId + "|AUTO|" + normalisedType + "|" + minX + "|" + minZ;
		DesignatedWorkZoneRecord zone = designatedWorkZones.get(id);
		if (zone == null) {
			zone = new DesignatedWorkZoneRecord();
			zone.id = id;
			zone.civilisationId = worker.civilisationId;
			zone.type = normalisedType;
			if ("FACTORY".equals(normalisedType))
				zone.factoryTypeId = normalisedFactoryType(worker.factoryTypeId);
			zone.minX = minX;
			zone.maxX = maxX;
			zone.minZ = minZ;
			zone.maxZ = maxZ;
			designatedWorkZones.put(id, zone);
			dirty = true;
		}
		if (!Objects.equals(zone.assignedWorkerUuid, worker.uuid)) {
			zone.assignedWorkerUuid = worker.uuid;
			dirty = true;
		}
		if ("FACTORY".equals(normalisedType)) {
			String wanted = normalisedFactoryType(worker.factoryTypeId);
			if (!Objects.equals(zone.factoryTypeId, wanted)) {
				zone.factoryTypeId = wanted;
				dirty = true;
			}
		}
		if ("FARM".equals(normalisedType))
			worker.farmerDesignatedZoneId = id;
		if ("FACTORY".equals(normalisedType))
			worker.factoryDesignatedZoneId = id;
		return id;
	}

	/**
	 * Converts an existing owned factory district to another researched factory
	 * type.
	 */
	public synchronized boolean convertFactoryZone(String civilisationId, String zoneId, String targetFactoryTypeId) {
		Civilisation civilisation = DataManager.getCivilisations().get(civilisationId);
		DesignatedWorkZoneRecord zone = designatedWorkZones.get(zoneId);
		String target = normalisedFactoryType(targetFactoryTypeId);
		if (civilisation == null || zone == null || !"FACTORY".equals(zone.type)
				|| !Objects.equals(civilisationId, zone.civilisationId)
				|| !ResearchSystem.factoryTypeAvailable(civilisation, target))
			return false;
		zone.factoryTypeId = target;
		if (zone.assignedWorkerUuid != null) {
			WorkerRecord worker = workers.get(zone.assignedWorkerUuid);
			if (worker != null)
				worker.factoryTypeId = target;
		}
		dirty = true;
		return true;
	}

	private static String normalisedFactoryType(String id) {
		return id == null || id.isBlank() ? "wooden_factory" : id.trim().toLowerCase(Locale.ROOT);
	}

	private boolean zoneIsEligibleForDesignation(String civilisationId, int minX, int maxX, int minZ, int maxZ) {
		WorldMapTracker.Snapshot snapshot = WorldMapTracker.getInstance().snapshot();
		int minChunkX = Math.floorDiv(minX, WorldMapTracker.CHUNK_SIZE);
		int maxChunkX = Math.floorDiv(maxX, WorldMapTracker.CHUNK_SIZE);
		int minChunkZ = Math.floorDiv(minZ, WorldMapTracker.CHUNK_SIZE);
		int maxChunkZ = Math.floorDiv(maxZ, WorldMapTracker.CHUNK_SIZE);
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				int centreX = chunkX * WorldMapTracker.CHUNK_SIZE + WorldMapTracker.CHUNK_SIZE / 2;
				int centreZ = chunkZ * WorldMapTracker.CHUNK_SIZE + WorldMapTracker.CHUNK_SIZE / 2;
				if (!snapshot.isDiscoveredBlock(centreX, centreZ))
					return false;
				long key = WorldMapTracker.chunkKey(chunkX, chunkZ);
				Providence providence = providenceContainingChunk(key);
				if (providence == null)
					return false;
				String owner = providence.getTerritoryOwner(key);
				if (owner != null && !owner.isBlank() && !Objects.equals(owner, civilisationId))
					return false;
			}
		}
		return true;
	}

	private Providence providenceContainingChunk(long chunkKey) {
		for (Providence providence : DataManager.getProvidences().values()) {
			if (providence != null && providence.isEstablished()
					&& providence.getTerritoryChunkKeys().contains(chunkKey))
				return providence;
		}
		return null;
	}

	private boolean zoneOverlapsExistingReservation(String civilisationId, int minX, int maxX, int minZ, int maxZ) {
		for (DesignatedWorkZoneRecord zone : designatedWorkZones.values()) {
			if (zone == null || !Objects.equals(civilisationId, zone.civilisationId))
				continue;
			if (rectanglesOverlap(minX, maxX, minZ, maxZ, zone.minX, zone.maxX, zone.minZ, zone.maxZ))
				return true;
		}
		for (WorkerRecord worker : workers.values()) {
			if (worker == null || !Objects.equals(civilisationId, worker.civilisationId))
				continue;
			if (worker.hasFarmerZone && rectanglesOverlap(minX, maxX, minZ, maxZ, worker.farmerZoneMinX,
					worker.farmerZoneMaxX, worker.farmerZoneMinZ, worker.farmerZoneMaxZ))
				return true;
			if (worker.hasFactoryZone && rectanglesOverlap(minX, maxX, minZ, maxZ, worker.factoryZoneMinX,
					worker.factoryZoneMaxX, worker.factoryZoneMinZ, worker.factoryZoneMaxZ))
				return true;
		}
		return false;
	}

	private void cleanupDesignatedZoneAssignments() {
		for (DesignatedWorkZoneRecord zone : designatedWorkZones.values()) {
			if (zone == null || zone.assignedWorkerUuid == null)
				continue;
			WorkerRecord worker = workers.get(zone.assignedWorkerUuid);
			VillagerJob required = "FARM".equals(zone.type) ? VillagerJob.FARMER : VillagerJob.FACTORY_BUILDER;
			if (worker == null || !Objects.equals(worker.civilisationId, zone.civilisationId)
					|| parseJob(worker.job) != required) {
				zone.assignedWorkerUuid = null;
				dirty = true;
			}
		}
	}

	private void ensureSupplyDepots(ServerLevel level) {
		Set<String> stillValid = new HashSet<>();
		for (Civilisation civilisation : DataManager.getCivilisations().values()) {
			if (!civilisation.isActive())
				continue;
			for (Providence providence : ProvidenceSystem.ownedProvidences(civilisation.getId())) {
				City city = providence.getCity();
				// Every controlled command post maintains physical storage. Supply-
				// capital flags still determine worker home/supply routing, but raw
				// materials can be stored and withdrawn at any national command post.
				if (city == null)
					continue;
				if (!Objects.equals(city.getControllerId(), civilisation.getId()))
					continue;

				String key = depotKey(civilisation.getId(), city.getId());
				stillValid.add(key);
				DepotRecord depot = depots.computeIfAbsent(key,
						ignored -> new DepotRecord(civilisation.getId(), city.getId()));
				if (depot.chests == null)
					depot.chests = new ArrayList<>();

				if (!isChunkLoaded(level, city.getBlockX(), city.getBlockZ()))
					continue;
				if (depot.chests.size() < DEPOT_CHESTS_PER_CAPITAL) {
					createDepotChests(level, civilisation, city, depot);
				} else {
					repairMissingDepotChests(level, depot);
				}

				// First physicalisation of an old/brand-new save: put its existing
				// numerical stockpile into real chests once per civilisation, then
				// chests take over. Adding a later overseas supply capital must not
				// duplicate the whole national stockpile.
				boolean countryAlreadyPhysicalised = depots.values().stream()
						.anyMatch(existing -> Objects.equals(existing.civilisationId, civilisation.getId())
								&& existing.initialisedStockpile);
				if (!countryAlreadyPhysicalised && countUsableChestSlots(level, civilisation.getId()) > 0) {
					materialiseInitialStockpile(level, civilisation);
					depot.initialisedStockpile = true;
					dirty = true;
				} else if (countryAlreadyPhysicalised && !depot.initialisedStockpile) {
					depot.initialisedStockpile = true;
					dirty = true;
				}
			}
		}

		// Do not delete a captured command post's chest blocks. We only stop
		// treating them as national storage when that city is no longer controlled.
		for (Map.Entry<String, DepotRecord> entry : depots.entrySet()) {
			if (!stillValid.contains(entry.getKey()))
				entry.getValue().active = false;
			else
				entry.getValue().active = true;
		}
	}

	private void createDepotChests(ServerLevel level, Civilisation civilisation, City city, DepotRecord depot) {
		int[][] offsets = { { 3, 0 }, { 5, 0 }, { 7, 0 }, { 9, 0 }, { 3, 2 }, { 5, 2 }, { 7, 2 }, { 9, 2 }, { -3, 0 },
				{ -5, 0 }, { -7, 0 }, { -9, 0 }, { -3, 2 }, { -5, 2 }, { -7, 2 }, { -9, 2 } };

		// Storage ownership is horizontal: one X/Z column may contain at most one
		// automatically placed chest. Heightmap changes caused by an existing chest
		// must never make the next reconcile place another chest on top of it.
		Set<String> occupiedColumns = new HashSet<>();
		for (StoredPos pos : depot.chests)
			occupiedColumns.add(pos.x + ":" + pos.z);

		for (int[] offset : offsets) {
			if (depot.chests.size() >= DEPOT_CHESTS_PER_CAPITAL)
				break;
			int x = city.getBlockX() + offset[0];
			int z = city.getBlockZ() + offset[1];
			String columnKey = x + ":" + z;
			if (occupiedColumns.contains(columnKey) || !isChunkLoaded(level, x, z))
				continue;

			BlockPos existingChest = existingDepotContainerInColumn(level, x, z);
			if (existingChest != null) {
				depot.chests.add(new StoredPos(existingChest.getX(), existingChest.getY(), existingChest.getZ()));
				occupiedColumns.add(columnKey);
				dirty = true;
				continue;
			}

			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockPos pos = new BlockPos(x, y, z);
			BlockState state = level.getBlockState(pos);
			if (!state.isAir())
				continue;
			level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
			if (isContainer(level, pos)) {
				depot.chests.add(new StoredPos(x, y, z));
				occupiedColumns.add(columnKey);
				dirty = true;
			}
		}
	}

	private BlockPos existingDepotContainerInColumn(ServerLevel level, int x, int z) {
		if (level == null || !isChunkLoaded(level, x, z))
			return null;
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 2;
		int bottom = top - 24;
		BlockPos lowest = null;
		for (int y = top; y >= bottom; y--) {
			BlockPos pos = new BlockPos(x, y, z);
			BlockState state = level.getBlockState(pos);
			if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL)) {
				lowest = pos;
			}
		}
		return lowest;
	}

	private void repairMissingDepotChests(ServerLevel level, DepotRecord depot) {
		if (ticks - depot.lastRepairTick < 600)
			return;
		depot.lastRepairTick = ticks;
		boolean changed = false;
		LinkedHashMap<String, StoredPos> uniqueColumns = new LinkedHashMap<>();
		for (StoredPos stored : new ArrayList<>(depot.chests)) {
			if (stored == null || !isChunkLoaded(level, stored.x, stored.z)) {
				if (stored != null)
					uniqueColumns.putIfAbsent(stored.x + ":" + stored.z, stored);
				continue;
			}
			String column = stored.x + ":" + stored.z;
			if (uniqueColumns.containsKey(column)) {
				// Old saves may already contain stacked depot records. Keep only one
				// registry entry for the X/Z column; existing blocks are left alone.
				changed = true;
				continue;
			}
			BlockPos physical = existingDepotContainerInColumn(level, stored.x, stored.z);
			if (physical != null) {
				StoredPos adopted = new StoredPos(physical.getX(), physical.getY(), physical.getZ());
				uniqueColumns.put(column, adopted);
				if (stored.y != physical.getY())
					changed = true;
				continue;
			}
			BlockPos pos = stored.toBlockPos();
			if (level.getBlockState(pos).isAir()) {
				level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
				changed = true;
			}
			if (isContainer(level, pos))
				uniqueColumns.put(column, stored);
		}
		if (uniqueColumns.size() != depot.chests.size() || changed) {
			depot.chests = new ArrayList<>(uniqueColumns.values());
			dirty = true;
		}
	}

	private void materialiseInitialStockpile(ServerLevel level, Civilisation civilisation) {
		EnumMap<ResourceType, Double> remainders = fractionalLedger.computeIfAbsent(civilisation.getId(),
				ignored -> new EnumMap<>(ResourceType.class));
		EnumMap<ResourceType, Double> last = lastStrategyValues.computeIfAbsent(civilisation.getId(),
				ignored -> new EnumMap<>(ResourceType.class));

		for (ResourceType type : ResourceType.values()) {
			double value = civilisation.getResource(type);
			int whole = (int) Math.floor(value);
			if (whole > 0)
				insertResourceItems(level, civilisation.getId(), type, whole);
			double remainder = value - whole;
			remainders.put(type, remainder);
			int chestCount = countResourceItems(level, civilisation.getId(), type);
			civilisation.setResource(type, chestCount + remainder);
			last.put(type, chestCount + remainder);
		}
	}

	private void synchroniseAllResourceLedgers(ServerLevel level) {
		for (Civilisation civilisation : DataManager.getCivilisations().values()) {
			if (!civilisation.isActive())
				continue;
			if (countUsableChestSlots(level, civilisation.getId()) <= 0)
				continue;
			synchroniseResourceLedger(level, civilisation);
		}
	}

	/**
	 * Reconciles numerical effects/consumption with chest items without losing
	 * fractional strategy costs. Physical chest changes (including player theft or
	 * deposits) are then reflected back into Civilisation.resources.
	 */
	private void synchroniseResourceLedger(ServerLevel level, Civilisation civilisation) {
		EnumMap<ResourceType, Double> last = lastStrategyValues.computeIfAbsent(civilisation.getId(),
				ignored -> new EnumMap<>(ResourceType.class));
		EnumMap<ResourceType, Double> fractions = fractionalLedger.computeIfAbsent(civilisation.getId(),
				ignored -> new EnumMap<>(ResourceType.class));

		for (ResourceType type : ResourceType.values()) {
			double currentStrategy = civilisation.getResource(type);
			double previous = last.getOrDefault(type, currentStrategy);
			double pending = fractions.getOrDefault(type, currentStrategy - Math.floor(currentStrategy));
			double externalDelta = currentStrategy - previous;
			pending += externalDelta;

			int wholeDelta = pending >= 1.0 ? (int) Math.floor(pending)
					: pending <= -1.0 ? (int) Math.ceil(pending) : 0;
			if (wholeDelta > 0) {
				int inserted = insertResourceItems(level, civilisation.getId(), type, wholeDelta);
				pending -= inserted;
			} else if (wholeDelta < 0) {
				int requested = -wholeDelta;
				int removed = removeResourceItems(level, civilisation.getId(), type, requested);
				pending += removed;
			}

			int physical = countResourceItems(level, civilisation.getId(), type);
			double visible = Math.max(0.0, physical + pending);
			civilisation.setResource(type, visible);
			last.put(type, visible);
			fractions.put(type, pending);
		}
	}

	private void reconcileWorkers(ServerLevel level) {
		for (Civilisation civilisation : DataManager.getCivilisations().values()) {
			if (!civilisation.isActive() || civilisation.getPopulation() <= 0)
				continue;
			City home = primarySupplyCapital(civilisation.getId());
			if (home == null)
				continue;
			// A birth may materialise anywhere on loaded, permanently owned national
			// territory. Do not require the capital chunk itself to be loaded if some
			// other owned part of the country is currently ticking.
			if (!hasLoadedOwnedTerritory(level, civilisation.getId())
					&& !isChunkLoaded(level, home.getBlockX(), home.getBlockZ()))
				continue;

			List<WorkerRecord> civWorkers = workers.values().stream()
					.filter(record -> civilisation.getId().equals(record.civilisationId))
					.sorted(Comparator.comparing(record -> record.uuid == null ? "" : record.uuid)).toList();

			// Population can increase through GS reproduction. Spawn exactly the
			// missing number of physical villagers. New people are distributed across
			// safe, loaded, permanently owned territory rather than always appearing
			// beside the national supply capital.
			int missing = civilisation.getPopulation() - civWorkers.size();
			for (int i = 0; i < missing; i++) {
				WorkerRecord record = new WorkerRecord();
				record.civilisationId = civilisation.getId();
				record.homeCityId = home.getId();
				record.job = VillagerJob.FARMER.name();
				record.toolTier = WorkerToolTier.HAND.name();
				record.assignmentIndex = civWorkers.size() + i;
				spawnWorker(level, civilisation, home, record);
			}

			// If strategy population fell, remove surplus entities cleanly.
			if (missing < 0) {
				int remove = -missing;
				List<WorkerRecord> newest = new ArrayList<>(civWorkers);
				newest.sort(Comparator.comparingInt((WorkerRecord r) -> r.assignmentIndex).reversed());
				for (WorkerRecord record : newest) {
					if (remove-- <= 0)
						break;
					discardWorkerEntity(level, record);
					workers.remove(record.uuid);
					dirty = true;
				}
			}

			reconcileWorkerJobs(civilisation);
			ensureAutomaticProfessionDistricts(level, civilisation, home);
		}
	}

	private void reconcileWorkerJobs(Civilisation civilisation) {
		List<WorkerRecord> civWorkers = workers.values().stream()
				.filter(record -> civilisation.getId().equals(record.civilisationId))
				.sorted(Comparator.comparingInt(record -> record.assignmentIndex)).toList();
		if (civWorkers.isEmpty())
			return;

		EnumMap<VillagerJob, Integer> desired = new EnumMap<>(VillagerJob.class);
		EnumMap<VillagerJob, Integer> actual = new EnumMap<>(VillagerJob.class);
		for (VillagerJob job : VillagerJob.values()) {
			desired.put(job, civilisation.getJobCount(job));
			actual.put(job, 0);
		}
		for (WorkerRecord record : civWorkers) {
			VillagerJob job = parseJob(record.job);
			if (job == VillagerJob.MINER && !VillagerJob.MINER.name().equals(record.job)) {
				record.job = VillagerJob.MINER.name();
				dirty = true;
			}
			actual.put(job, actual.get(job) + 1);
		}

		List<VillagerJob> deficits = new ArrayList<>();
		for (VillagerJob job : VillagerJob.values()) {
			int count = Math.max(0, desired.get(job) - actual.get(job));
			for (int i = 0; i < count; i++)
				deficits.add(job);
		}
		if (deficits.isEmpty())
			return;

		int deficitIndex = 0;
		for (WorkerRecord record : civWorkers) {
			if (deficitIndex >= deficits.size())
				break;
			VillagerJob current = parseJob(record.job);
			if (actual.get(current) <= desired.get(current))
				continue;
			actual.put(current, actual.get(current) - 1);
			VillagerJob target = deficits.get(deficitIndex++);
			if (current != target)
				prepareWorkerForProfessionChange(record, current, target);
			record.job = target.name();
			record.targetKind = null;
			record.targetX = record.targetY = record.targetZ = 0;
			clearMoveTarget(record);
			ensureBrain(record).clearGoal();
			// Never delete materials merely because a worker was reassigned. They
			// finish by returning anything already carried to the depot. A new
			// profession also starts without magically transforming the old tool.
			if (carriedTotal(record) > 0)
				record.forceDeposit = true;
			record.toolTier = WorkerToolTier.HAND.name();
			record.preparedToolTier = null;
			record.clearedFactoryKey = null;
			dirty = true;
		}
	}

	/**
	 * Profession changes are hard boundaries between worker behaviours. Old
	 * profession targets/district ownership must not leak into the new role: that
	 * was the main reason a worker could visually appear to be doing another job.
	 * Physical items are not deleted here; the tick loop returns orphaned buckets,
	 * factory products and construction materials to storage before new work
	 * starts.
	 */
	private void prepareWorkerForProfessionChange(WorkerRecord record, VillagerJob oldJob, VillagerJob newJob) {
		if (record == null || oldJob == newJob)
			return;
		releaseDesignatedZonesForWorker(record);

		record.targetKind = null;
		record.targetX = record.targetY = record.targetZ = 0;
		clearMoveTarget(record);
		ensureBrain(record).clearGoal();
		record.navigationAssistTicks = 0;
		record.stuckChecks = 0;
		record.combatTargetUuid = null;
		record.commandPostTargetProvidenceId = null;
		record.bootstrapToolCrafting = false;

		// District ownership belongs only to the matching profession. The map zone
		// itself remains persistent and unassigned so another suitable worker can
		// immediately inherit it.
		if (newJob != VillagerJob.FARMER) {
			record.hasFarmerZone = false;
			record.farmerDesignatedZoneId = null;
			record.farmerWaterKnown = false;
			record.farmerNoWorkCells = 0;
			record.farmerWaitingForCrops = false;
			record.lastFarmerCropGrowthCheckTick = 0L;
			record.hasFarmerWaterSourceTarget = false;
			record.lastFarmWaterCheckTick = 0L;
		}
		if (newJob != VillagerJob.FACTORY_BUILDER) {
			record.hasFactoryZone = false;
			record.factoryDesignatedZoneId = null;
			record.factoryBuilt = false;
			record.woodenFactoryBuilt = false;
			record.factoryCoreInitialised = false;
			record.factoryGroundInitialised = false;
			record.factoryGroundY = 0;
			record.clearedFactoryKey = null;
			record.expandingDepot = false;
		}
		if (newJob != VillagerJob.ADMINISTRATOR) {
			record.hasAdminTorchTarget = false;
		}
		if (newJob != VillagerJob.ROAD_BUILDER) {
			record.roadRouteIndex = 0;
			record.roadRouteStep = 0;
		}
		if (newJob != VillagerJob.MINER) {
			record.forceDeposit = carriedTotal(record) > 0;
			// If the old miner is physically underground, keep both its private lane
			// reservation and inMine state long enough for tickNonMinerMineAvoidance()
			// to escort it out. A miner reassigned on the surface releases immediately.
			if (record.inMine || record.mineTransitDirection != 0) {
				record.nonMinerMineAvoidanceActive = true;
			} else {
				record.mineLaneIndex = -1;
			}
		}
		dirty = true;
	}

	/**
	 * Farmers and factory builders never wait for somebody to queue a district.
	 * Player-designated zones are claimed first; otherwise an automatic persistent
	 * district is allocated immediately during population/job reconciliation.
	 */
	private void ensureAutomaticProfessionDistricts(ServerLevel level, Civilisation civilisation, City home) {
		if (level == null || civilisation == null || home == null)
			return;
		for (WorkerRecord record : workers.values()) {
			if (record == null || !Objects.equals(civilisation.getId(), record.civilisationId))
				continue;
			VillagerJob job = parseJob(record.job);
			if (job == VillagerJob.FARMER && !record.hasFarmerZone) {
				ensureFarmerZone(level, home, record);
			} else if (job == VillagerJob.FACTORY_BUILDER && !record.hasFactoryZone) {
				ensureFactoryZone(level, home, record);
			}
		}
	}

	private void spawnWorker(ServerLevel level, Civilisation civilisation, City home, WorkerRecord record) {
		SpawnLocation location = findTerritorySpawnLocation(level, civilisation, home, record.assignmentIndex);
		BlockPos spawn = location.pos();
		City assignedHome = location.home() == null ? home : location.home();
		if (assignedHome != null)
			record.homeCityId = assignedHome.getId();

		PathfinderMob villager = GrandStrategyHumanoidEntity.spawn(level, spawn);
		if (villager == null)
			return;

		villager.setPersistenceRequired();
		ensureWorkerAppearance(record);
		// Grand Strategy owns collection of its tagged work drops so vanilla villager
		// food-sharing AI cannot swallow wheat into an unrelated hidden inventory.
		villager.setCanPickUpLoot(false);
		villager.addTag("grandstrategy_worker");
		villager.addTag("gs_civ_" + sanitiseTag(civilisation.getId()));
		villager.setCustomName(
				Component.literal(civilisation.getName() + " | " + parseJob(record.job).getDisplayName()));
		villager.setCustomNameVisible(true);

		record.uuid = villager.getUUID().toString();
		record.lastX = spawn.getX();
		record.lastY = spawn.getY();
		record.lastZ = spawn.getZ();
		if (record.carrying == null)
			record.carrying = new LinkedHashMap<>();
		if (record.workMaterials == null)
			record.workMaterials = new LinkedHashMap<>();
		if (record.toolTier == null)
			record.toolTier = WorkerToolTier.HAND.name();
		if (record.nextMealTick <= 0L)
			record.nextMealTick = initialMealTick(record);
		workers.put(record.uuid, record);
		equipWorker(villager, record);
		dirty = true;
	}

	/**
	 * Chooses a safe physical birth point from any currently loaded chunk that the
	 * civilisation permanently owns. Temporary wartime jurisdiction is deliberately
	 * excluded so newborn civilians do not appear on a just-occupied front line.
	 * Selection is deterministic but spread across the country's owned chunks.
	 */
	private SpawnLocation findTerritorySpawnLocation(ServerLevel level, Civilisation civilisation, City fallbackHome,
			int index) {
		List<TerritorySpawnChunk> candidates = new ArrayList<>();
		String civilisationId = civilisation.getId();
		for (Providence providence : DataManager.getProvidences().values()) {
			if (providence == null || !providence.isEstablished())
				continue;
			City localCity = providence.getCity();
			City localHome = localCity != null && Objects.equals(localCity.getControllerId(), civilisationId)
					? localCity
					: fallbackHome;
			for (Map.Entry<Long, String> entry : providence.getTerritoryOwnerMap().entrySet()) {
				if (!Objects.equals(civilisationId, entry.getValue()))
					continue;
				long key = entry.getKey();
				int chunkX = (int) (key >> 32);
				int chunkZ = (int) key;
				int centreX = chunkX * 16 + 8;
				int centreZ = chunkZ * 16 + 8;
				if (!isChunkLoaded(level, centreX, centreZ))
					continue;
				candidates.add(new TerritorySpawnChunk(key, localHome));
			}
		}

		if (!candidates.isEmpty()) {
			candidates.sort(Comparator.comparingLong(TerritorySpawnChunk::chunkKey));
			int seed = 31 * index + (civilisationId == null ? 0 : civilisationId.hashCode());
			int start = Math.floorMod(seed, candidates.size());
			int chunkAttempts = Math.min(candidates.size(), 24);
			for (int attempt = 0; attempt < chunkAttempts; attempt++) {
				TerritorySpawnChunk candidate = candidates.get((start + attempt * 7) % candidates.size());
				int chunkX = (int) (candidate.chunkKey() >> 32);
				int chunkZ = (int) candidate.chunkKey();
				int baseX = chunkX * 16;
				int baseZ = chunkZ * 16;

				// Try several positions inside the selected owned chunk. The arithmetic
				// deliberately spreads successive births instead of stacking them at the
				// chunk centre. All probes stay away from the very edge of the chunk.
				for (int probe = 0; probe < 8; probe++) {
					int localSeed = seed * 1103515245 + attempt * 977 + probe * 131;
					int localX = 2 + Math.floorMod(localSeed, 12);
					int localZ = 2 + Math.floorMod(localSeed / 17, 12);
					int x = baseX + localX;
					int z = baseZ + localZ;
					BlockPos safe = safeSpawnSurface(level, candidate.home(), x, z);
					if (safe != null)
						return new SpawnLocation(safe, candidate.home());
				}
			}
		}

		// If no owned chunk is currently suitable, preserve the bounded safe-capital
		// fallback rather than creating or force-loading terrain solely for a birth.
		return new SpawnLocation(findSpawnPos(level, fallbackHome, index), fallbackHome);
	}

	private boolean hasLoadedOwnedTerritory(ServerLevel level, String civilisationId) {
		for (Providence providence : DataManager.getProvidences().values()) {
			if (providence == null || !providence.isEstablished())
				continue;
			for (Map.Entry<Long, String> entry : providence.getTerritoryOwnerMap().entrySet()) {
				if (!Objects.equals(civilisationId, entry.getValue()))
					continue;
				long key = entry.getKey();
				int chunkX = (int) (key >> 32);
				int chunkZ = (int) key;
				if (isChunkLoaded(level, chunkX * 16 + 8, chunkZ * 16 + 8))
					return true;
			}
		}
		return false;
	}

	private void registerWorkerCasualty(ServerLevel level, WorkerRecord record, BlockPos deathPos) {
		if (record == null)
			return;
		Civilisation civilisation = DataManager.getCivilisations().get(record.civilisationId);
		if (deathPos == null)
			deathPos = new BlockPos(record.lastX, record.lastY, record.lastZ);

		// Anything the worker was physically carrying is dropped where the worker
		// died; it is not returned to the national ledger from nowhere.
		if (record.carrying != null) {
			for (ResourceType type : ResourceType.values()) {
				int amount = record.carrying.getOrDefault(type.name(), 0);
				if (amount > 0)
					spawnResourceDrop(level, record.civilisationId, type, amount, deathPos);
			}
		}
		if (record.workMaterials != null) {
			for (ResourceType type : ResourceType.values()) {
				int amount = record.workMaterials.getOrDefault(type.name(), 0);
				if (amount <= 0)
					continue;
				if (type == ResourceType.WOOD) {
					spawnSpecificDrop(level, record.civilisationId, Items.OAK_PLANKS, amount, deathPos);
				} else {
					spawnResourceDrop(level, record.civilisationId, type, amount, deathPos);
				}
			}
		}
		if (record.adminTorches > 0) {
			spawnOrdinaryItemDrop(level, Items.TORCH, record.adminTorches, deathPos);
		}
		if (record.farmerHasWaterBucket) {
			spawnOrdinaryItemDrop(level, Items.WATER_BUCKET, 1, deathPos);
		} else if (record.farmerHasBucket) {
			spawnOrdinaryItemDrop(level, Items.BUCKET, 1, deathPos);
		}
		if (civilisation != null) {
			civilisation.removePopulationFromJob(parseJob(record.job));
		}
		if (record.uuid != null) {
			workers.remove(record.uuid);
			workDropClaims.values().removeIf(record.uuid::equals);
		}
		dirty = true;
	}

	private BlockPos findSpawnPos(ServerLevel level, City city, int index) {
		int ring = 2 + index / 16;
		int slot = index % 16;
		int dx;
		int dz;
		if (slot < 4) {
			dx = -ring + slot * Math.max(1, ring);
			dz = -ring;
		} else if (slot < 8) {
			dx = ring;
			dz = -ring + (slot - 4) * Math.max(1, ring);
		} else if (slot < 12) {
			dx = ring - (slot - 8) * Math.max(1, ring);
			dz = ring;
		} else {
			dx = -ring;
			dz = ring - (slot - 12) * Math.max(1, ring);
		}
		int preferredX = city.getBlockX() + dx;
		int preferredZ = city.getBlockZ() + dz;

		BlockPos preferred = safeSpawnSurface(level, city, preferredX, preferredZ);
		if (preferred != null)
			return preferred;

		// Do not materialise births/cheat population directly into ponds, caves or
		// mine openings. Probe a small deterministic set around the preferred point;
		// this is bounded even when hundreds of villagers are reconciled at once.
		int[][] spawnOffsets = new int[][] { { 1, 0 }, { 1, 1 }, { 0, 1 }, { -1, 1 }, { -1, 0 }, { -1, -1 }, { 0, -1 },
				{ 1, -1 } };
		for (int attempt = 1; attempt <= 32; attempt++) {
			int radius = 1 + (attempt - 1) / 8;
			int direction = Math.floorMod(index * 3 + attempt, 8);
			int x = preferredX + spawnOffsets[direction][0] * radius;
			int z = preferredZ + spawnOffsets[direction][1] * radius;
			BlockPos candidate = safeSpawnSurface(level, city, x, z);
			if (candidate != null)
				return candidate;
		}

		// Last bounded fallback: favour the command-post neighbourhood rather than
		// accepting an unsafe water/hole coordinate.
		for (int radius = 2; radius <= 6; radius += 2) {
			for (int direction = 0; direction < 8; direction++) {
				int x = city.getBlockX() + spawnOffsets[direction][0] * radius;
				int z = city.getBlockZ() + spawnOffsets[direction][1] * radius;
				BlockPos candidate = safeSpawnSurface(level, city, x, z);
				if (candidate != null)
					return candidate;
			}
		}

		// Preserve the old deterministic fallback only if the local terrain truly
		// contains no safe candidate. Water recovery can still handle this rare case.
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, preferredX, preferredZ);
		return new BlockPos(preferredX, y, preferredZ);
	}

	private BlockPos safeSpawnSurface(ServerLevel level, City city, int x, int z) {
		if (city != null) {
			int mineX = city.getBlockX() - MINE_ZONE_OFFSET;
			if (Math.abs(x - mineX) <= 2) {
				for (int depthGroup = 0; depthGroup < 4; depthGroup++) {
					int mineZ = city.getBlockZ() + (depthGroup * 4 - 6);
					if (Math.abs(z - mineZ) <= 2)
						return null;
				}
			}
		}
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos feet = new BlockPos(x, y, z);
		return isWorkerStandable(level, feet) ? feet : null;
	}

	private PathfinderMob replaceLegacyPhysicalBody(ServerLevel level, WorkerRecord record, PathfinderMob oldBody) {
		if (level == null || record == null || oldBody == null)
			return null;
		BlockPos pos = oldBody.blockPosition();
		String oldUuid = record.uuid;
		oldBody.discard();

		GrandStrategyHumanoidEntity humanoid = GrandStrategyHumanoidEntity.spawn(level, pos);
		if (humanoid == null)
			return null;
		humanoid.setPersistenceRequired();
		humanoid.setCanPickUpLoot(false);
		humanoid.addTag("grandstrategy_worker");
		humanoid.addTag("gs_civ_" + sanitiseTag(record.civilisationId));

		Civilisation civilisation = DataManager.getCivilisations().get(record.civilisationId);
		VillagerJob job = parseJob(record.job);
		if (civilisation != null) {
			humanoid.setCustomName(Component
					.literal(civilisation.getName() + " | " + (job == null ? "Worker" : job.getDisplayName())));
			humanoid.setCustomNameVisible(true);
		}

		String newUuid = humanoid.getUUID().toString();
		record.uuid = newUuid;
		record.lastX = floor(humanoid.getX());
		record.lastY = floor(humanoid.getY());
		record.lastZ = floor(humanoid.getZ());
		record.missingTicks = 0;

		if (oldUuid != null) {
			workers.remove(oldUuid);
			workDropClaims.values().removeIf(oldUuid::equals);
			obstacleClaims.entrySet().removeIf(entry -> oldUuid.equals(entry.getValue().workerUuid()));
			for (TravelAssistRequest request : travelAssistRequests.values()) {
				if (request != null && oldUuid.equals(request.assigneeUuid))
					request.assigneeUuid = null;
			}
			for (DesignatedWorkZoneRecord zone : designatedWorkZones.values()) {
				if (zone != null && oldUuid.equals(zone.assignedWorkerUuid))
					zone.assignedWorkerUuid = newUuid;
			}
			WorkerPlannerService.getInstance().forget(oldUuid);
		}
		// The reloaded backing HUSK had a completely different navigator. A task may
		// still be persistent, but its old Path object can never be reused by this new
		// dedicated humanoid body.
		resetSessionTransientStateAfterLoad(record);
		workers.put(newUuid, record);
		rehydrateWorkerNavigation(level, humanoid, record);
		equipWorker(humanoid, record);
		dirty = true;
		return humanoid;
	}

	private void tickWorker(ServerLevel level, WorkerRecord record) {
		if (record == null || record.uuid == null)
			return;
		UUID uuid;
		try {
			uuid = UUID.fromString(record.uuid);
		} catch (IllegalArgumentException e) {
			return;
		}

		Entity entity = level.getEntity(uuid);
		entityLookupCache.put(record.uuid, entity);
		if (entity instanceof PathfinderMob deadVillager && !deadVillager.isAlive()) {
			registerWorkerCasualty(level, record, deadVillager.blockPosition());
			return;
		}
		// Saves from v6.37 and earlier contain vanilla Villager bodies. The dedicated
		// GS body deliberately serialises through a vanilla humanoid backing type for
		// cross-loader rendering, so a world reload can also materialise that backing
		// type as a vanilla mob. In either case replace it immediately while keeping
		// the strategy worker record, job, inventory, district and brain intact.
		if (entity instanceof PathfinderMob legacyBody && !(legacyBody instanceof GrandStrategyHumanoidEntity)) {
			PathfinderMob replacementBody = replaceLegacyPhysicalBody(level, record, legacyBody);
			if (replacementBody == null)
				return;
			entity = replacementBody;
			entityLookupCache.put(record.uuid, replacementBody);
		}
		if (!(entity instanceof PathfinderMob villager)) {
			// Missing workers in a still-loaded chunk are casualties, not free respawns.
			if (isChunkLoaded(level, record.lastX, record.lastZ)) {
				record.missingTicks++;
				if (record.missingTicks >= CASUALTY_CONFIRM_TICKS) {
					registerWorkerCasualty(level, record, new BlockPos(record.lastX, record.lastY, record.lastZ));
				}
			}
			return;
		}

		record.missingTicks = 0;
		record.lastX = floor(villager.getX());
		record.lastY = floor(villager.getY());
		record.lastZ = floor(villager.getZ());
		if (record.carrying == null)
			record.carrying = new LinkedHashMap<>();
		if (record.workMaterials == null)
			record.workMaterials = new LinkedHashMap<>();

		Civilisation civilisation = DataManager.getCivilisations().get(record.civilisationId);
		if (civilisation == null || !civilisation.isActive()) {
			// A capitulated/collapsed country must not leave immortal inert GS
			// villagers behind. Loaded remnants are retired cleanly without being
			// counted as fresh combat casualties; unloaded records remain until the
			// entity's chunk is available and can then be removed safely.
			villager.discard();
			workers.remove(record.uuid);
			workDropClaims.values().removeIf(record.uuid::equals);
			dirty = true;
			return;
		}

		if (record.mineTransitDirection != 0) {
			tickMineShaftTransit(villager, record);
			return;
		}
		villager.setNoGravity(false);

		City home = cityById(record.homeCityId);
		if (home == null)
			return;

		VillagerJob job = parseJob(record.job);

		// A saved/reloaded miner can occasionally retain an underground work flag
		// after its physical body has been left part-way down its dedicated shaft. In
		// that
		// state the generic navigator tries to walk directly from (for example) Y=58
		// to the -48 mining horizon, which can never succeed. Detect a body that is
		// physically inside its own shaft column and resume the dedicated vertical
		// transit before any ordinary WorkerBrain/pathfinding code is allowed to run.
		if (job == VillagerJob.MINER && repairInterruptedMinerShaftTransit(level, home, villager, record)) {
			tickMineShaftTransit(villager, record);
			return;
		}
		// A miner that has lost its underground state while outside the one-block
		// shaft must not be handed to generic surface PathNavigation. Recover by
		// physically cutting/walking a short horizontal corridor back to its own
		// shaft, then use the normal vertical shaft transit. This catches the log
		// pattern where a miner at Y~55-60 was being routed toward Y~74 surface
		// waypoints and repeatedly classified as CLIFF_UP.
		if (job == VillagerJob.MINER && tickMinerUndergroundShaftRecovery(level, home, villager, record)) {
			return;
		}

		ensureBrain(record);
		applyCompletedWorkerPlan(level, villager, record);
		suppressVanillaWorkerMovement(villager);
		if (record.needsNavigationRehydrate) {
			rehydrateWorkerNavigation(level, villager, record);
		}
		// A farmer never has a legitimate target-less movement goal: farm cells,
		// irrigation, water, storage and work drops all carry an explicit targetKind.
		// If one exists anyway it is orphaned execution state from an older/stale
		// planner leg. Drop it immediately instead of letting the watchdog "recover"
		// that obsolete coordinate forever.
		discardOrphanedFarmerGoal(villager, record);

		// Interaction tasks do not require the body to stand on the exact target
		// block. Settle a satisfied local leg before the anti-freeze watchdog runs;
		// otherwise a farmer already within hoe/harvest range, or a worker beside a
		// chest/workstation, can be labelled "Recovering movement" and repeatedly
		// nudged away from the place where its profession can already act.
		settleSatisfiedLocalMovement(level, villager, record);
		enforceWorkerLiveness(level, villager, record);

		if (record.nextMealTick <= 0L) {
			record.nextMealTick = initialMealTick(record);
			dirty = true;
		}

		// Water escape remains more urgent than eating; a hungry villager first gets
		// out of immediate drowning/pathfinding trouble, then resumes its meal task.

		// v6.21 persisted a radius-scanned terrain-escape target. Discard it lazily
		// on load so an existing save immediately stops running the expensive rescue
		// routine that could stall the server.
		if (record.hasTerrainEscapeTarget) {
			record.hasTerrainEscapeTarget = false;
			record.terrainEscapeTicks = 0;
			record.lastTerrainEscapeSearchTick = 0L;
			dirty = true;
		}

		// Water recovery is now a constant-cost local scramble. It deliberately uses
		// the worker's real assignment direction instead of searching a wide heightmap.
		if (isWorkerInFluid(level, villager)) {
			tickWaterEscape(level, civilisation, home, villager, record);
			return;
		}
		record.hasWaterEscapeTarget = false;
		record.waterTicks = 0;

		// Mine shafts and their underground access tunnels are workplaces, not
		// generic shortcuts. Water escape stays higher priority; once dry, a
		// non-miner approaching a shaft is diverted, and an accidental underground
		// visitor returns to the surface without forgetting its real WorkerBrain goal.
		if (job != VillagerJob.MINER && tickNonMinerMineAvoidance(level, home, villager, record)) {
			return;
		}

		if (ticks >= record.nextMealTick && tickMeal(level, civilisation, home, villager, record)) {
			return;
		}

		// A dry worker that repeatedly fails to advance gets a short physical climb
		// assist. This is not teleportation: collision still applies and only the
		// immediate one-block step/headroom may be cleared.
		if (record.navigationAssistTicks > 0 && record.hasMoveTarget) {
			tickEmergencyClimb(level, villager, record,
					new BlockPos(record.moveTargetX, record.moveTargetY, record.moveTargetZ));
			return;
		}

		maintainNavigation(level, villager, record);
		equipWorker(villager, record);

		// A profession change never deletes physical items that belonged to the old
		// task. Return them first, then begin the new profession. This also prevents
		// the Economy table/world model showing a farmer carrying factory stock or a
		// miner still carrying an irrigation bucket.
		if (job != VillagerJob.FARMER && returnOrphanedFarmerBucket(level, civilisation, villager, record)) {
			return;
		}
		if (job != VillagerJob.FACTORY_BUILDER && record.factoryProduct != null
				&& depositFactoryProduct(level, civilisation.getId(), villager, record)) {
			return;
		}
		if (job != VillagerJob.FACTORY_BUILDER && workMaterialTotal(record) > 0
				&& !isWoodenToolBootstrapMaterial(record, job)
				&& returnOrphanedWorkMaterials(level, civilisation, villager, record)) {
			return;
		}

		// Vanilla PathfinderMob brain tasks are not allowed to keep an unrelated walk
		// path when Grand Strategy has no active movement command. We still use
		// vanilla PathNavigation as the local movement motor, but GS owns intent.
		if (!record.hasMoveTarget && !villager.getNavigation().isDone()) {
			villager.getNavigation().stop();
		}

		// Combat is a real-time responsibility, not a half-second production task.
		// Soldiers evaluate/chase/strike every server tick so they cannot stand beside
		// a zombie or wartime enemy waiting for their staggered work-think slot. Bone
		// meal/bones are handled inside the soldier state machine so wartime orders
		// always outrank scavenging.
		if (job == VillagerJob.SOLDIER) {
			tickSoldier(level, civilisation, home, villager, record);
			return;
		}

		// Once a producer is already collecting a specific physical output stack,
		// service that transaction every server tick rather than only on the slower
		// profession think cadence. This removes the visible pause where a farmer is
		// standing beside harvested wheat while its pickup state waits for the next
		// work slot. New drop searches still happen on the staggered think cadence.
		if ("work_drop".equals(record.targetKind) && professionCollectsWorkDrops(job)
				&& carriedTotal(record) < carryLimitFor(job) && collectNearestWorkDrop(level, villager, record)) {
			return;
		}

		int thinkInterval = Math.max(3,
				(int) Math.round(WORK_THINK_INTERVAL_TICKS / toolTier(record).getWorkMultiplier()));
		// Worker servicing may be cohort-scheduled at high population. Use an elapsed
		// deadline rather than an exact global modulo so a 3/4/5-tick service stride
		// cannot accidentally starve a 7- or 9-tick profession cadence forever.
		if (record.nextProfessionThinkTick <= 0L) {
			int salt = record.assignmentIndex * 31
					+ (record.civilisationId == null ? 0 : record.civilisationId.hashCode());
			record.nextProfessionThinkTick = ticks + Math.floorMod(salt, thinkInterval);
		}
		if (ticks < record.nextProfessionThinkTick)
			return;
		do {
			record.nextProfessionThinkTick += thinkInterval;
		} while (record.nextProfessionThinkTick <= ticks);

		int carried = carriedTotal(record);
		if (job == VillagerJob.MINER) {
			// Do not send a miner home with one cobblestone simply because three
			// seconds elapsed between pickups. Only a nearly-full load that has been
			// idle for thirty seconds is allowed to return early.
			if (carried >= MINER_PARTIAL_RETURN_MIN_LOAD
					&& ticks - record.lastPickupTick >= MINER_PARTIAL_RETURN_IDLE_TICKS) {
				record.forceDeposit = true;
			}
		} else if (carried > 0 && ticks - record.lastPickupTick >= RETURN_WITH_PARTIAL_LOAD_TICKS) {
			record.forceDeposit = true;
		}
		if (carried > 0 && (record.forceDeposit || carried >= carryLimitFor(job))
				&& !(job == VillagerJob.MINER && record.inMine)) {
			if (returnToDepot(level, civilisation, home, villager, record))
				return;
		}

		// Workers now collect only output that belongs to their profession. The old
		// general-logistics rule let farmers chase cobblestone, researchers haul logs,
		// etc., which made professions look interchangeable and often distracted them
		// from their actual assignment.
		if (carriedTotal(record) < carryLimitFor(job) && professionCollectsWorkDrops(job)
				&& collectNearestWorkDrop(level, villager, record)) {
			return;
		}

		if (maybeUpgradeTool(level, civilisation, home, record, villager))
			return;

		switch (job) {
		case FARMER -> tickFarmer(level, civilisation, home, villager, record);
		case LUMBERJACK -> tickLumberjack(level, civilisation, home, villager, record);
		case MINER -> tickMiner(level, civilisation, home, villager, record);
		case ROAD_BUILDER -> tickRoadBuilder(level, civilisation, home, villager, record);
		case FACTORY_BUILDER -> tickFactoryBuilder(level, civilisation, home, villager, record);
		case RESEARCHER, ADMINISTRATOR -> tickCityWorker(level, civilisation, home, villager, record);
		case SOLDIER -> {
		} // handled every tick above
		}
	}

	private boolean returnOrphanedFarmerBucket(ServerLevel level, Civilisation civilisation, PathfinderMob villager,
			WorkerRecord record) {
		if (record == null || (!record.farmerHasWaterBucket && !record.farmerHasBucket))
			return false;
		Item item = record.farmerHasWaterBucket ? Items.WATER_BUCKET : Items.BUCKET;
		BlockPos chest = nearestDepotChestAcceptingItem(level, civilisation.getId(), item, villager.blockPosition(),
				record);
		if (chest == null)
			return true;
		if (walkToDepotChest(level, villager, chest, record, 3.2))
			return true;
		BlockEntity blockEntity = level.getBlockEntity(chest);
		if (!(blockEntity instanceof Container container))
			return true;
		int inserted = insertSpecificItems(container, item, 1);
		if (inserted > 0) {
			record.farmerHasWaterBucket = false;
			record.farmerHasBucket = false;
			record.hasFarmerWaterSourceTarget = false;
			clearMoveTarget(record);
			updateCarriedDisplay(villager, record);
			dirty = true;
		}
		return true;
	}

	private boolean returnOrphanedWorkMaterials(ServerLevel level, Civilisation civilisation, PathfinderMob villager,
			WorkerRecord record) {
		if (record == null || record.workMaterials == null || workMaterialTotal(record) <= 0)
			return false;
		for (ResourceType type : ResourceType.values()) {
			int amount = Math.max(0, record.workMaterials.getOrDefault(type.name(), 0));
			if (amount <= 0)
				continue;
			Item item = type == ResourceType.WOOD ? Items.OAK_PLANKS : itemFor(type);
			if (item == null) {
				record.workMaterials.put(type.name(), 0);
				dirty = true;
				continue;
			}
			BlockPos chest = nearestDepotChestAcceptingItem(level, civilisation.getId(), item, villager.blockPosition(),
					record);
			if (chest == null)
				return true;
			if (walkToDepotChest(level, villager, chest, record, 3.2))
				return true;
			BlockEntity blockEntity = level.getBlockEntity(chest);
			if (!(blockEntity instanceof Container container))
				return true;
			int inserted = insertSpecificItems(container, item, amount);
			if (inserted > 0) {
				record.workMaterials.put(type.name(), amount - inserted);
				clearMoveTarget(record);
				updateCarriedDisplay(villager, record);
				dirty = true;
			}
			return true;
		}
		return false;
	}

	private long initialMealTick(WorkerRecord record) {
		int seed = record == null ? 0 : record.assignmentIndex;
		if (record != null && record.uuid != null)
			seed = 31 * seed + record.uuid.hashCode();
		long spread = Math.floorMod(seed, (int) FIRST_MEAL_SPREAD_TICKS);
		return ticks + FIRST_MEAL_GRACE_TICKS + spread;
	}

	/**
	 * Individual chest-backed eating. Bread is preferred because factories make it;
	 * raw wheat is a fallback so a young settlement can still feed itself before
	 * its first factory is complete. No abstract resource is deducted here:
	 * physically removing the item from the chest is picked up by the normal
	 * resource-ledger reconciliation.
	 */
	private boolean tickMeal(ServerLevel level, Civilisation civilisation, City home, PathfinderMob villager,
			WorkerRecord record) {
		if (record.hungrySinceTick <= 0L) {
			record.hungrySinceTick = ticks;
			dirty = true;
		}

		if (ticks - record.hungrySinceTick >= STARVATION_GRACE_TICKS) {
			// Starvation is a real population loss, not just an abstract stability
			// modifier. The standard casualty path drops anything the worker carried.
			BlockPos pos = villager.blockPosition();
			villager.discard();
			registerWorkerCasualty(level, record, pos);
			return true;
		}

		// Re-use a valid meal chest instead of scanning storage every tick.
		BlockPos chest = record.hasMealTarget ? new BlockPos(record.mealTargetX, record.mealTargetY, record.mealTargetZ)
				: null;
		if (chest != null && (!mealChestHasFood(level, chest) || !depotChestHasReachableInteraction(level, chest,
				villager.blockPosition(), record.assignmentIndex))) {
			record.hasMealTarget = false;
			finishLocalTaskMovement(villager, record);
			chest = null;
		}

		if (chest == null) {
			int phase = Math.floorMod(record.assignmentIndex, MEAL_RETRY_TICKS);
			if (ticks % MEAL_RETRY_TICKS != phase)
				return false;
			chest = nearestMealChest(level, civilisation.getId(), villager.blockPosition(), record);
			if (chest == null) {
				// A marching army can be ticking while every home depot chunk is
				// unloaded. In that case consume one ration from the persisted
				// national ledger; the normal reconciliation removes the matching
				// physical item the next time a depot is loaded. This prevents a
				// front-line soldier from starving merely because its home chest is
				// outside the current chunk-loading radius.
				boolean distributedMeal = civilisation.consumeResource(ResourceType.SUPPLIES, 1.0)
						|| civilisation.consumeResource(ResourceType.FOOD, 1.0);
				if (distributedMeal) {
					villager.swing(InteractionHand.MAIN_HAND);
					record.hungrySinceTick = 0L;
					record.nextMealTick = ticks + MEAL_INTERVAL_TICKS;
					dirty = true;
					return true;
				}

				// Food shortage blocks births through the strategy reserve check and
				// gradually damages social stability while villagers remain hungry.
				civilisation.setStability(civilisation.getStability() - 0.001);
				return false;
			}
			record.hasMealTarget = true;
			record.mealTargetX = chest.getX();
			record.mealTargetY = chest.getY();
			record.mealTargetZ = chest.getZ();
			dirty = true;
		}

		if (walkToDepotChest(level, villager, chest, record, 2.75))
			return true;

		BlockEntity blockEntity = level.getBlockEntity(chest);
		if (!(blockEntity instanceof Container container)) {
			record.hasMealTarget = false;
			clearMoveTarget(record);
			return true;
		}

		int eaten = removeSpecificItems(container, List.of(Items.BREAD), 1);
		if (eaten <= 0)
			eaten = withdrawMaterialUnits(container, ResourceType.FOOD, 1);
		if (eaten <= 0) {
			record.hasMealTarget = false;
			clearMoveTarget(record);
			return true;
		}

		villager.swing(InteractionHand.MAIN_HAND);
		record.hungrySinceTick = 0L;
		record.nextMealTick = ticks + MEAL_INTERVAL_TICKS;
		record.hasMealTarget = false;
		clearMoveTarget(record);
		dirty = true;
		return true;
	}

	private boolean mealChestHasFood(ServerLevel level, BlockPos pos) {
		if (pos == null || !isChunkLoaded(level, pos.getX(), pos.getZ()))
			return false;
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (!(blockEntity instanceof Container container))
			return false;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.isEmpty())
				continue;
			if (stack.getItem() == Items.BREAD || resourceForItem(stack.getItem()) == ResourceType.FOOD) {
				return true;
			}
		}
		return false;
	}

	private BlockPos nearestMealChest(ServerLevel level, String civilisationId, BlockPos from, WorkerRecord record) {
		List<BlockPos> candidates = new ArrayList<>();
		for (DepotRecord depot : depots.values()) {
			if (!depot.active || !Objects.equals(civilisationId, depot.civilisationId) || depot.chests == null)
				continue;
			for (StoredPos stored : depot.chests) {
				if (!isChunkLoaded(level, stored.x, stored.z))
					continue;
				BlockPos pos = stored.toBlockPos();
				if (mealChestHasFood(level, pos)
						&& depotChestHasReachableInteraction(level, pos, from, record.assignmentIndex)) {
					candidates.add(pos);
				}
			}
		}
		if (candidates.isEmpty()) {
			BlockPos bread = nearestNearbyCivilisationChest(level, civilisationId, from, record, null, Items.BREAD, 1,
					null);
			if (bread != null)
				return bread;
			return nearestNearbyCivilisationChest(level, civilisationId, from, record, ResourceType.FOOD, null, 0,
					null);
		}
		return selectDistributedNearbyChest(candidates, from, record.assignmentIndex);
	}

	/**
	 * Reassigns materialised residents when a providence changes permanent legal
	 * ownership. WarSystem separately transfers the strategic population count;
	 * this method ensures existing physical villagers survive the conquest and
	 * belong to the new country rather than being deleted when their former state
	 * collapses.
	 */
	public synchronized int transferResidentsForProvidence(String previousCivilisationId, String newCivilisationId,
			String cityId, int requested) {
		if (requested <= 0 || previousCivilisationId == null || newCivilisationId == null
				|| previousCivilisationId.equals(newCivilisationId))
			return 0;

		List<WorkerRecord> candidates = workers.values().stream().filter(Objects::nonNull)
				.filter(record -> Objects.equals(previousCivilisationId, record.civilisationId))
				.sorted(Comparator.comparing((WorkerRecord record) -> !Objects.equals(cityId, record.homeCityId))
						.thenComparingInt(record -> record.assignmentIndex)
						.thenComparing(record -> record.uuid == null ? "" : record.uuid))
				.limit(requested).toList();

		int nextAssignment = workers.values().stream()
				.filter(record -> record != null && Objects.equals(newCivilisationId, record.civilisationId))
				.mapToInt(record -> record.assignmentIndex).max().orElse(-1) + 1;

		for (WorkerRecord record : candidates) {
			record.civilisationId = newCivilisationId;
			record.homeCityId = cityId;
			record.assignmentIndex = nextAssignment++;
			record.combatTargetUuid = null;
			record.commandPostTargetProvidenceId = null;
			record.targetKind = null;
			record.targetX = record.targetY = record.targetZ = 0;
			clearMoveTarget(record);
			record.navigationAssistTicks = 0;
			record.stuckChecks = 0;
			record.hasWaterEscapeTarget = false;
			record.waterTicks = 0;
			releaseDesignatedZonesForWorker(record);
			record.hasFarmerZone = false;
			record.hasFactoryZone = false;
			record.factoryBuilt = false;
			record.woodenFactoryBuilt = false;
			record.factoryCoreInitialised = false;
			record.factoryGroundInitialised = false;
			record.factoryGroundY = 0;
			record.clearedFactoryKey = null;
			record.preparedToolTier = null;
			record.factoryProduct = null;
			record.factoryProductCount = 0;
			record.expandingDepot = false;
			record.hasAdminTorchTarget = false;
			record.roadRouteIndex = 0;
			record.roadRouteStep = 0;
			record.inMine = false;
			record.mineTransitDirection = 0;
			record.forceDeposit = carriedTotal(record) > 0;
			record.hasMealTarget = false;
			// Preserve the villager's hunger age across conquest; changing flags does
			// not magically feed the population.
		}
		if (!candidates.isEmpty())
			dirty = true;
		return candidates.size();
	}

	private void tickFarmer(ServerLevel level, Civilisation civilisation, City home, PathfinderMob villager,
			WorkerRecord record) {
		WorkZone zone = ensureFarmerZone(level, home, record);
		int zoneCentreX = (zone.minX() + zone.maxX()) / 2;
		int zoneCentreZ = (zone.minZ() + zone.maxZ()) / 2;
		if (!isChunkLoaded(level, zoneCentreX, zoneCentreZ)) {
			clearMoveTarget(record);
			return;
		}

		// Bone meal is a physical shared supply. Soldiers salvage bones/bone meal
		// and return it to command-post storage; farmers fetch a small batch only when
		// their own field contains immature crops. This avoids pointless depot trips
		// when a field is empty, mature, or already waiting on another task.
		if (tickFarmerBoneMealSupply(level, civilisation, villager, record, zone))
			return;

		// A farmer first tries to make its permanent field genuinely irrigated.
		// Natural water is preferred. If the chosen/designated field is dry, the
		// farmer can fetch a water bucket, fetch an empty bucket and fill it from a
		// loaded natural source, or craft an empty bucket from three stored iron
		// ingots and then fetch water. No water is created from nothing.
		if (tickFarmerIrrigation(level, civilisation, home, villager, record, zone))
			return;

		// Once a complete pass over the field finds nothing to plant, prepare or
		// harvest, the farmer genuinely waits instead of endlessly pacing across all
		// whole owned field. Periodically rescan it; as soon as a crop is mature (or
		// another cell needs work), resume at that exact cell.
		if (record.farmerWaitingForCrops) {
			if (ticks - record.lastFarmerCropGrowthCheckTick < 40L) {
				clearTarget(record);
				finishLocalTaskMovement(villager, record);
				return;
			}
			record.lastFarmerCropGrowthCheckTick = ticks;
			int readyOffset = findReadyFarmerCellOffset(level, zone, record.workCounter);
			if (readyOffset < 0) {
				clearTarget(record);
				finishLocalTaskMovement(villager, record);
				return;
			}
			record.workCounter += readyOffset;
			record.farmerNoWorkCells = 0;
			record.farmerWaitingForCrops = false;
		}

		BlockPos plot = farmPlot(level, zone, record.workCounter);
		setTarget(record, "farm", plot);
		if (!near(villager, plot, 2.6)) {
			moveTo(level, villager, plot, record);
			return;
		}

		BlockPos ground = plot.below();
		BlockState cropState = level.getBlockState(plot);
		BlockState groundState = level.getBlockState(ground);

		boolean worked = false;
		boolean harvestedOutput = false;
		if (groundState.is(Blocks.WATER) || !level.getFluidState(ground).isEmpty()) {
			// Water cells are part of the farm infrastructure, not failed plots.
			// Count them as settled cells when deciding whether a complete pass found
			// anything to do; otherwise one irrigation cell would prevent the explicit
			// "waiting for crops" state from ever being reached.
			noteFarmerNoWorkCell(record, zone);
			record.workCounter++;
			clearTarget(record);
			finishLocalTaskMovement(villager, record);
			return;
		}
		if (!groundState.is(Blocks.FARMLAND)) {
			// The farmer owns this entire zone until death. Clear only its own plot,
			// then progressively use every cell in the zone rather than competing
			// with another farmer for a globally selected target.
			if (!cropState.isAir() && factoryBlockCanBeCleared(level, plot, cropState)) {
				level.destroyBlock(plot, false);
			}
			level.setBlockAndUpdate(ground, Blocks.FARMLAND.defaultBlockState());
			level.setBlockAndUpdate(plot, Blocks.WHEAT.defaultBlockState());
			worked = true;
		} else if (cropState.getBlock() instanceof CropBlock crop && crop.isMaxAge(cropState)) {
			level.destroyBlock(plot, false);
			level.setBlockAndUpdate(plot, Blocks.WHEAT.defaultBlockState());
			ItemEntity harvested = spawnResourceDrop(level, record.civilisationId, ResourceType.FOOD, 3, plot);
			if (harvested != null) {
				// The farmer that produced this stack gets first refusal. Keeping the
				// UUID prevents nearest-item selection from changing every think tick
				// when several harvested stacks are lying in adjacent rows.
				record.workDropTargetUuid = harvested.getUUID().toString();
				workDropClaims.put(harvested.getUUID(), record.uuid);
				harvestedOutput = true;
			}
			worked = true;
		} else if (cropState.isAir()) {
			level.setBlockAndUpdate(plot, Blocks.WHEAT.defaultBlockState());
			worked = true;
		} else if (cropState.getBlock() instanceof CropBlock crop) {
			if (record.boneMeal > 0) {
				// Use one real bone-meal item at a time. CropBlock.performBonemeal uses
				// vanilla growth rules/randomness; the farmer does not force maturity.
				crop.performBonemeal(level, level.getRandom(), plot, cropState);
				record.boneMeal--;
				villager.swing(InteractionHand.MAIN_HAND);
				updateCarriedDisplay(villager, record);
				worked = true;
				dirty = true;
			} else {
				// Some religions/cults have agricultural or rain-growth modifiers.
				// They create a small extra vanilla growth opportunity rather than
				// magically setting the crop to mature; extremism scales the effect.
				double growth = SocietySystem.cropGrowthMultiplier(civilisation);
				double chance = Math.min(0.35, Math.max(0.0, growth - 1.0) * 0.30);
				if (chance > 0.0 && level.getRandom().nextDouble() < chance) {
					crop.performBonemeal(level, level.getRandom(), plot, cropState);
					worked = true;
				}
			}
		}

		record.workCounter++;
		if (harvestedOutput) {
			// Keep the freshly produced stack as the durable next interaction so the
			// per-tick pickup path above takes over immediately on the next tick.
			setTarget(record, "work_drop", plot);
		} else {
			clearTarget(record);
		}
		finishLocalTaskMovement(villager, record);
		if (worked) {
			record.farmerNoWorkCells = 0;
			record.farmerWaitingForCrops = false;
			ProvidenceSystem.claimConstructionArea(civilisation.getId(), plot.getX(), plot.getZ());
			successfulWork(record, 1.0);
		} else {
			noteFarmerNoWorkCell(record, zone);
		}
	}

	private boolean tickFarmerBoneMealSupply(ServerLevel level, Civilisation civilisation, PathfinderMob villager,
			WorkerRecord record, WorkZone zone) {
		// Harvesting, planting and repairing the field outrank fertilising it. Bone
		// meal is used only when the current pass otherwise has nothing actionable
		// except immature crops.
		if (findReadyFarmerCellOffset(level, zone, record.workCounter) >= 0)
			return false;

		int immatureOffset = findImmatureFarmerCellOffset(level, zone, record.workCounter);
		if (immatureOffset < 0) {
			if ("bone_meal_supply".equals(record.targetKind)) {
				clearTarget(record);
				finishLocalTaskMovement(villager, record);
			}
			return false;
		}

		// If this farmer already has bone meal, point the ordinary farm cursor at an
		// immature crop and let tickFarmer perform the physical application.
		if (record.boneMeal > 0) {
			record.workCounter += immatureOffset;
			record.farmerWaitingForCrops = false;
			record.farmerNoWorkCells = 0;
			return false;
		}

		BlockPos chest = "bone_meal_supply".equals(record.targetKind) ? targetPos(record) : null;
		if (chest != null) {
			BlockEntity existing = level.getBlockEntity(chest);
			if (!(existing instanceof Container container) || countSpecificItems(container, Items.BONE_MEAL) <= 0) {
				clearTarget(record);
				finishLocalTaskMovement(villager, record);
				chest = null;
			}
		}
		if (chest == null) {
			chest = nearestDepotChestWithSpecificItem(level, civilisation.getId(), Items.BONE_MEAL,
					villager.blockPosition(), record);
			if (chest != null)
				setTarget(record, "bone_meal_supply", chest);
		}
		if (chest == null)
			return false;
		if (walkToDepotChest(level, villager, chest, record, 3.2))
			return true;

		BlockEntity blockEntity = level.getBlockEntity(chest);
		if (!(blockEntity instanceof Container container)) {
			clearTarget(record);
			finishLocalTaskMovement(villager, record);
			return false;
		}
		int wanted = Math.max(0, BONE_MEAL_CARRY_BATCH - record.boneMeal);
		int removed = removeSpecificItems(container, List.of(Items.BONE_MEAL), wanted);
		if (removed > 0) {
			record.boneMeal += removed;
			record.lastPickupTick = ticks;
			record.farmerWaitingForCrops = false;
			record.farmerNoWorkCells = 0;
			record.workCounter += immatureOffset;
			villager.swing(InteractionHand.MAIN_HAND);
			updateCarriedDisplay(villager, record);
			dirty = true;
		}
		clearTarget(record);
		finishLocalTaskMovement(villager, record);
		return removed > 0;
	}

	private int findImmatureFarmerCellOffset(ServerLevel level, WorkZone zone, int startCounter) {
		int width = Math.max(1, zone.maxX() - zone.minX() + 1);
		int depth = Math.max(1, zone.maxZ() - zone.minZ() + 1);
		int cells = width * depth;
		for (int offset = 0; offset < cells; offset++) {
			BlockPos plot = farmPlot(level, zone, startCounter + offset);
			BlockState state = level.getBlockState(plot);
			if (state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state))
				return offset;
		}
		return -1;
	}

	private void noteFarmerNoWorkCell(WorkerRecord record, WorkZone zone) {
		if (record == null || zone == null)
			return;
		int width = Math.max(1, zone.maxX() - zone.minX() + 1);
		int depth = Math.max(1, zone.maxZ() - zone.minZ() + 1);
		int cells = width * depth;
		record.farmerNoWorkCells = Math.min(cells, record.farmerNoWorkCells + 1);
		if (record.farmerNoWorkCells >= cells) {
			record.farmerWaitingForCrops = true;
			record.lastFarmerCropGrowthCheckTick = ticks;
		}
	}

	private int findReadyFarmerCellOffset(ServerLevel level, WorkZone zone, int startCounter) {
		int width = Math.max(1, zone.maxX() - zone.minX() + 1);
		int depth = Math.max(1, zone.maxZ() - zone.minZ() + 1);
		int cells = width * depth;
		for (int offset = 0; offset < cells; offset++) {
			BlockPos plot = farmPlot(level, zone, startCounter + offset);
			BlockPos ground = plot.below();
			BlockState groundState = level.getBlockState(ground);
			if (groundState.is(Blocks.WATER) || !level.getFluidState(ground).isEmpty())
				continue;
			if (!groundState.is(Blocks.FARMLAND))
				return offset;

			BlockState cropState = level.getBlockState(plot);
			if (cropState.isAir())
				return offset;
			if (cropState.getBlock() instanceof CropBlock crop && crop.isMaxAge(cropState))
				return offset;
			if (!(cropState.getBlock() instanceof CropBlock) && factoryBlockCanBeCleared(level, plot, cropState))
				return offset;
		}
		return -1;
	}

	private WorkZone ensureFarmerZone(ServerLevel level, City home, WorkerRecord record) {
		// v11 migration: older automatic farms were 9x9/18x18 and packed much closer
		// together, so simply expanding their stored rectangles would make neighbouring
		// 52x52 farms overlap. Re-lay automatic districts using the worker's persistent
		// zone index and the new wider spacing. Player-designated legacy rectangles
		// are respected; newly designated farms use the larger dimensions above.
		migrateAutomaticFarmerZoneIfNeeded(home, record);
		if (!record.hasFarmerZone) {
			WorkZone designated = claimDesignatedWorkZone(record, "FARM");
			if (designated != null)
				return designated;
			int index = nextFreeZoneIndex(record.civilisationId, true);
			WorkZone fallback = null;
			int fallbackIndex = index;
			// Search several of the normal deterministic district slots and prefer
			// the first non-overlapping one that already has surface water within
			// farmland hydration distance. If none is wet, preserve the first valid
			// dry slot and let the bucket-irrigation state machine improve it later.
			for (int attempt = 0; attempt < 48; attempt++, index++) {
				WorkZone candidate = automaticFarmerZone(home, index);
				if (zoneOverlapsAnotherWorker(record, candidate.minX(), candidate.maxX(), candidate.minZ(),
						candidate.maxZ()))
					continue;
				if (fallback == null) {
					fallback = candidate;
					fallbackIndex = index;
				}
				if (farmZoneHasWater(level, candidate)) {
					fallback = candidate;
					fallbackIndex = index;
					break;
				}
			}
			if (fallback == null) {
				fallback = automaticFarmerZone(home, index);
				fallbackIndex = index;
			}
			record.farmerZoneMinX = fallback.minX();
			record.farmerZoneMaxX = fallback.maxX();
			record.farmerZoneMinZ = fallback.minZ();
			record.farmerZoneMaxZ = fallback.maxZ();
			record.farmerZoneIndex = fallbackIndex;
			record.hasFarmerZone = true;
			record.farmerWaterKnown = false;
			record.farmerNoWorkCells = 0;
			record.farmerWaitingForCrops = false;
			record.lastFarmerCropGrowthCheckTick = 0L;
			record.lastFarmWaterCheckTick = 0L;
			registerAutomaticWorkZone(record, "FARM", record.farmerZoneMinX, record.farmerZoneMaxX,
					record.farmerZoneMinZ, record.farmerZoneMaxZ);
			dirty = true;
		}
		return new WorkZone(record.farmerZoneMinX, record.farmerZoneMaxX, record.farmerZoneMinZ, record.farmerZoneMaxZ);
	}

	private void migrateAutomaticFarmerZoneIfNeeded(City home, WorkerRecord record) {
		if (home == null || record == null || !record.hasFarmerZone)
			return;
		int width = record.farmerZoneMaxX - record.farmerZoneMinX + 1;
		int depth = record.farmerZoneMaxZ - record.farmerZoneMinZ + 1;
		if (width >= FARMER_ZONE_WIDTH && depth >= FARMER_ZONE_DEPTH)
			return;

		// A negative index identifies a player-designated zone. Do not silently move
		// a rectangle the player deliberately placed on the strategic map.
		if (record.farmerZoneIndex < 0)
			return;
		if (record.farmerDesignatedZoneId != null && !record.farmerDesignatedZoneId.contains("|AUTO|FARM|"))
			return;

		String oldZoneId = record.farmerDesignatedZoneId;
		if (oldZoneId != null) {
			DesignatedWorkZoneRecord old = designatedWorkZones.get(oldZoneId);
			if (old != null && Objects.equals(old.assignedWorkerUuid, record.uuid)) {
				designatedWorkZones.remove(oldZoneId);
			}
		}

		WorkZone expanded = automaticFarmerZone(home, record.farmerZoneIndex);
		record.farmerZoneMinX = expanded.minX();
		record.farmerZoneMaxX = expanded.maxX();
		record.farmerZoneMinZ = expanded.minZ();
		record.farmerZoneMaxZ = expanded.maxZ();
		record.farmerDesignatedZoneId = null;
		record.farmerWaterKnown = false;
		record.lastFarmWaterCheckTick = 0L;
		record.farmerNoWorkCells = 0;
		record.farmerWaitingForCrops = false;
		record.lastFarmerCropGrowthCheckTick = 0L;
		record.workCounter = 0;
		clearTarget(record);
		clearMoveTarget(record);
		WorkerBrainState brain = ensureBrain(record);
		WorkerPlannerService.getInstance().forget(record.uuid);
		brain.planGeneration++;
		brain.clearGoal();
		brain.routeRequestPending = false;
		brain.escapeRequestPending = false;
		brain.route.clear();
		brain.routeIndex = 0;
		brain.escapeRoute.clear();
		brain.escapeIndex = 0;
		brain.escaping = false;
		registerAutomaticWorkZone(record, "FARM", record.farmerZoneMinX, record.farmerZoneMaxX, record.farmerZoneMinZ,
				record.farmerZoneMaxZ);
		System.out.println("[GrandStrategy][Farm] expanded automatic farm worker=" + record.uuid + " person="
				+ record.assignmentIndex + " old=" + width + "x" + depth + " new=" + FARMER_ZONE_WIDTH + "x"
				+ FARMER_ZONE_DEPTH + " zone=" + record.farmerZoneMinX + "," + record.farmerZoneMinZ + ".."
				+ record.farmerZoneMaxX + "," + record.farmerZoneMaxZ);
		dirty = true;
	}

	private WorkZone automaticFarmerZone(City home, int index) {
		int column = Math.floorMod(index, FARMER_ZONE_COLUMNS);
		int row = Math.max(0, index / FARMER_ZONE_COLUMNS);
		int centreX = home.getBlockX() + FARM_ZONE_OFFSET + column * FARMER_ZONE_SPACING;
		int rowBand = row == 0 ? 0 : ((row + 1) / 2) * (row % 2 == 1 ? 1 : -1);
		int centreZ = home.getBlockZ() + rowBand * FARMER_ZONE_SPACING;
		int minX = centreX - FARMER_ZONE_WIDTH / 2;
		int minZ = centreZ - FARMER_ZONE_DEPTH / 2;
		return new WorkZone(minX, minX + FARMER_ZONE_WIDTH - 1, minZ, minZ + FARMER_ZONE_DEPTH - 1);
	}

	/**
	 * Returns true when the farmer is spending this tick obtaining or placing
	 * irrigation. Natural water is preferred; manufactured buckets merely move
	 * existing water from a loaded source to the farm.
	 */
	private boolean tickFarmerIrrigation(ServerLevel level, Civilisation civilisation, City home,
			PathfinderMob villager, WorkerRecord record, WorkZone zone) {
		if (ticks - record.lastFarmWaterCheckTick >= FARM_WATER_RECHECK_TICKS || record.lastFarmWaterCheckTick <= 0L) {
			record.farmerWaterKnown = farmZoneHasWater(level, zone);
			record.lastFarmWaterCheckTick = ticks;
			if (record.farmerWaterKnown)
				record.hasFarmerWaterSourceTarget = false;
			dirty = true;
		}
		if (record.farmerWaterKnown)
			return false;

		BlockPos irrigation = farmIrrigationPosition(level, zone);
		if (irrigation == null)
			return false;

		if (record.farmerHasWaterBucket) {
			if (!near(villager, irrigation, 3.2)) {
				BlockPos approach = surfacePos(level, irrigation.getX() + 2, irrigation.getZ());
				setTarget(record, "farm_irrigation", irrigation);
				moveTo(level, villager, approach, record);
				return true;
			}
			BlockState state = level.getBlockState(irrigation);
			if (level.getBlockEntity(irrigation) != null || state.is(Blocks.BEDROCK) || state.is(Blocks.BEACON)
					|| state.is(Blocks.CHEST)) {
				return false;
			}
			BlockPos crop = irrigation.above();
			if (!level.getBlockState(crop).isAir() && level.getBlockEntity(crop) == null) {
				level.destroyBlock(crop, false);
			}
			level.setBlockAndUpdate(irrigation, Blocks.WATER.defaultBlockState());
			record.farmerHasWaterBucket = false;
			record.farmerHasBucket = true;
			// A large farm needs several water points. Re-evaluate actual coverage
			// after every placement instead of declaring the whole field irrigated
			// after the first bucket.
			record.farmerWaterKnown = farmZoneHasWater(level, zone);
			record.lastFarmWaterCheckTick = ticks;
			record.hasFarmerWaterSourceTarget = false;
			ProvidenceSystem.claimConstructionArea(civilisation.getId(), irrigation.getX(), irrigation.getZ());
			villager.swing(InteractionHand.MAIN_HAND);
			updateCarriedDisplay(villager, record);
			clearTarget(record);
			finishLocalTaskMovement(villager, record);
			successfulWork(record, 0.5);
			dirty = true;
			return true;
		}

		if (record.farmerHasBucket) {
			BlockPos source = record.hasFarmerWaterSourceTarget
					? new BlockPos(record.farmerWaterSourceX, record.farmerWaterSourceY, record.farmerWaterSourceZ)
					: null;
			if (source == null || !level.getBlockState(source).is(Blocks.WATER)) {
				record.hasFarmerWaterSourceTarget = false;
				if (ticks - record.lastFarmerWaterSourceSearchTick >= 100L
						|| record.lastFarmerWaterSourceSearchTick <= 0L) {
					source = findFarmerWaterSource(level, zone, home, villager.blockPosition());
					record.lastFarmerWaterSourceSearchTick = ticks;
					if (source != null) {
						record.hasFarmerWaterSourceTarget = true;
						record.farmerWaterSourceX = source.getX();
						record.farmerWaterSourceY = source.getY();
						record.farmerWaterSourceZ = source.getZ();
					}
					dirty = true;
				}
			}
			if (source == null)
				return false;
			setTarget(record, "fetch_farm_water", source);
			if (!near(villager, source, 3.0)) {
				BlockPos approach = surfacePos(level, source.getX(), source.getZ());
				moveTo(level, villager, approach, record);
				return true;
			}
			if (!level.getBlockState(source).is(Blocks.WATER)) {
				record.hasFarmerWaterSourceTarget = false;
				clearTarget(record);
				finishLocalTaskMovement(villager, record);
				return true;
			}
			// Mimic filling a vanilla bucket by physically moving one source block.
			level.setBlockAndUpdate(source, Blocks.AIR.defaultBlockState());
			record.farmerHasBucket = false;
			record.farmerHasWaterBucket = true;
			record.hasFarmerWaterSourceTarget = false;
			villager.swing(InteractionHand.MAIN_HAND);
			updateCarriedDisplay(villager, record);
			clearTarget(record);
			finishLocalTaskMovement(villager, record);
			dirty = true;
			return true;
		}

		// Prefer an already-filled bucket, then a reusable empty bucket, then spend
		// three actual depot iron ingots to make a bucket. The farmer walks to the
		// chest it withdraws from; nothing is taken from unloaded storage remotely.
		Item wanted = Items.WATER_BUCKET;
		int wantedCount = 1;
		BlockPos chest = nearestDepotChestWithItemCount(level, civilisation.getId(), wanted, 1,
				villager.blockPosition(), record);
		if (chest == null) {
			wanted = Items.BUCKET;
			chest = nearestDepotChestWithItemCount(level, civilisation.getId(), wanted, 1, villager.blockPosition(),
					record);
		}
		if (chest == null) {
			wanted = Items.IRON_INGOT;
			wantedCount = 3;
			chest = nearestDepotChestWithItemCount(level, civilisation.getId(), wanted, 3, villager.blockPosition(),
					record);
		}
		if (chest == null)
			return false;
		setTarget(record, "farm_bucket_supply", chest);
		if (walkToDepotChest(level, villager, chest, record, 3.2))
			return true;
		BlockEntity blockEntity = level.getBlockEntity(chest);
		if (!(blockEntity instanceof Container container)) {
			clearTarget(record);
			return true;
		}
		int removed = removeSpecificItems(container, List.of(wanted), wantedCount);
		if (removed == wantedCount) {
			if (wanted == Items.WATER_BUCKET)
				record.farmerHasWaterBucket = true;
			else
				record.farmerHasBucket = true;
			villager.swing(InteractionHand.MAIN_HAND);
			updateCarriedDisplay(villager, record);
			dirty = true;
		}
		clearTarget(record);
		finishLocalTaskMovement(villager, record);
		return true;
	}

	private int fillFarmHydrationMask(ServerLevel level, WorkZone zone, boolean[] hydrated) {
		if (level == null || zone == null || hydrated == null)
			return 0;
		int width = zone.maxX() - zone.minX() + 1;
		int depth = zone.maxZ() - zone.minZ() + 1;
		int totalCells = Math.max(1, width * depth);
		if (hydrated.length < totalCells)
			return 0;
		int hydratedCount = 0;

		// Work backwards from actual nearby water sources and mark every farm cell
		// within vanilla's four-block horizontal hydration radius.
		for (int z = zone.minZ() - 4; z <= zone.maxZ() + 4; z++) {
			for (int x = zone.minX() - 4; x <= zone.maxX() + 4; x++) {
				if (!isChunkLoaded(level, x, z))
					continue;
				if (surfaceWaterAt(level, x, z) == null)
					continue;
				int minFarmX = Math.max(zone.minX(), x - 4);
				int maxFarmX = Math.min(zone.maxX(), x + 4);
				int minFarmZ = Math.max(zone.minZ(), z - 4);
				int maxFarmZ = Math.min(zone.maxZ(), z + 4);
				for (int fz = minFarmZ; fz <= maxFarmZ; fz++) {
					for (int fx = minFarmX; fx <= maxFarmX; fx++) {
						int index = (fz - zone.minZ()) * width + (fx - zone.minX());
						if (!hydrated[index]) {
							hydrated[index] = true;
							hydratedCount++;
						}
					}
				}
			}
		}
		return hydratedCount;
	}

	private boolean farmZoneHasWater(ServerLevel level, WorkZone zone) {
		if (level == null || zone == null)
			return false;
		int width = zone.maxX() - zone.minX() + 1;
		int depth = zone.maxZ() - zone.minZ() + 1;
		int totalCells = Math.max(1, width * depth);
		boolean[] hydrated = new boolean[totalCells];
		int hydratedCount = fillFarmHydrationMask(level, zone, hydrated);
		// Leave a little tolerance for uneven terrain/edge infrastructure, but one
		// pond touching a corner can no longer make a huge field count as irrigated.
		return hydratedCount * 100 >= totalCells * 90;
	}

	private BlockPos farmIrrigationPosition(ServerLevel level, WorkZone zone) {
		int width = zone.maxX() - zone.minX() + 1;
		int depth = zone.maxZ() - zone.minZ() + 1;
		int totalCells = Math.max(1, width * depth);
		boolean[] hydrated = new boolean[totalCells];
		fillFarmHydrationMask(level, zone, hydrated);

		BlockPos best = null;
		int bestScore = 0;

		// Split the owned rectangle into chunks no wider/deeper than nine blocks.
		// The centre of each chunk is within four blocks of every cell in it, exactly
		// matching vanilla farmland hydration. A normal 52x52 farm uses up to a
		// 6x6 grid (36 sources), while existing ponds/rivers satisfy covered regions.
		for (int z0 = zone.minZ(); z0 <= zone.maxZ(); z0 += FARM_IRRIGATION_SPAN) {
			int z1 = Math.min(zone.maxZ(), z0 + FARM_IRRIGATION_SPAN - 1);
			int z = (z0 + z1) / 2;
			for (int x0 = zone.minX(); x0 <= zone.maxX(); x0 += FARM_IRRIGATION_SPAN) {
				int x1 = Math.min(zone.maxX(), x0 + FARM_IRRIGATION_SPAN - 1);
				int x = (x0 + x1) / 2;
				if (!isChunkLoaded(level, x, z))
					continue;

				int score = 0;
				int minFarmX = Math.max(zone.minX(), x - 4);
				int maxFarmX = Math.min(zone.maxX(), x + 4);
				int minFarmZ = Math.max(zone.minZ(), z - 4);
				int maxFarmZ = Math.min(zone.maxZ(), z + 4);
				for (int fz = minFarmZ; fz <= maxFarmZ; fz++) {
					for (int fx = minFarmX; fx <= maxFarmX; fx++) {
						int index = (fz - zone.minZ()) * width + (fx - zone.minX());
						if (!hydrated[index])
							score++;
					}
				}
				if (score <= bestScore)
					continue;

				int cell = (z - zone.minZ()) * width + (x - zone.minX());
				BlockPos ground = farmPlot(level, zone, cell).below();
				BlockState state = level.getBlockState(ground);
				if (state.is(Blocks.WATER) || !level.getFluidState(ground).isEmpty())
					continue;
				if (level.getBlockEntity(ground) != null)
					continue;
				if (state.is(Blocks.BEDROCK) || state.is(Blocks.BEACON) || state.is(Blocks.CHEST))
					continue;
				best = ground;
				bestScore = score;
			}
		}
		return best;
	}

	private BlockPos findFarmerWaterSource(ServerLevel level, WorkZone zone, City home, BlockPos from) {
		int centreX = (zone.minX() + zone.maxX()) / 2;
		int centreZ = (zone.minZ() + zone.maxZ()) / 2;
		BlockPos best = findSurfaceWaterInSquare(level, centreX, centreZ, 24, from);
		if (best != null)
			return best;
		return findSurfaceWaterInSquare(level, home.getBlockX(), home.getBlockZ(), FARM_WATER_SOURCE_SEARCH_RADIUS,
				from);
	}

	private BlockPos findSurfaceWaterInSquare(ServerLevel level, int centreX, int centreZ, int radius, BlockPos from) {
		BlockPos best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		// Search every second column at long range; real ponds/rivers contain many
		// cells, while the nearby 24-block pass catches small sources accurately.
		int step = radius <= 24 ? 1 : 2;
		for (int z = centreZ - radius; z <= centreZ + radius; z += step) {
			for (int x = centreX - radius; x <= centreX + radius; x += step) {
				if (!isChunkLoaded(level, x, z))
					continue;
				BlockPos water = surfaceWaterAt(level, x, z);
				if (water == null)
					continue;
				double distance = distanceSquared(from, water);
				if (distance < bestDistance) {
					bestDistance = distance;
					best = water;
				}
			}
		}
		return best;
	}

	private BlockPos surfaceWaterAt(ServerLevel level, int x, int z) {
		int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
		for (int dy = 1; dy >= -6; dy--) {
			BlockPos pos = new BlockPos(x, y + dy, z);
			if (level.getBlockState(pos).is(Blocks.WATER))
				return pos;
		}
		return null;
	}

	private BlockPos farmPlot(ServerLevel level, WorkZone zone, int workCounter) {
		int width = zone.maxX() - zone.minX() + 1;
		int depth = zone.maxZ() - zone.minZ() + 1;
		int n = Math.floorMod(workCounter, Math.max(1, width * depth));
		int row = n / width;
		int column = n % width;
		// Walk the field in a serpentine pattern. On a large farm, resetting from the
		// far end of every row back to minX wastes more time travelling than farming.
		if ((row & 1) != 0)
			column = width - 1 - column;
		int x = zone.minX() + column;
		int z = zone.minZ() + row;

		// MOTION_BLOCKING_NO_LEAVES still treats logs as terrain. If a tree occupies a
		// farm cell, using the raw heightmap therefore targets the top of its trunk and
		// makes the farmer try to path onto the tree. Resolve the actual ground below
		// ordinary tree material/air instead. The farmer will then approach the base
		// and remove only the individual blocking log like any other worker; it does
		// not gain the lumberjack's connected-tree felling/replanting abilities.
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		int minY = y - 24;
		while (y > minY) {
			BlockPos surface = new BlockPos(x, y, z);
			BlockState state = level.getBlockState(surface);
			if (!state.isAir() && !isLog(state) && !isLeaves(state) && !isNaturalPathClutter(state)) {
				return surface.above();
			}
			y--;
		}
		return new BlockPos(x, y + 1, z);
	}

	private void tickLumberjack(ServerLevel level, Civilisation civilisation, City home, PathfinderMob villager,
			WorkerRecord record) {
		BlockPos rememberedTree = "tree".equals(record.targetKind) ? targetPos(record) : null;
		if (rememberedTree != null && validTreeBase(level, rememberedTree)) {
			// A tree is useful work, not a permanent prison. If several independent
			// path/planner failures have accumulated while trying to reach this same
			// trunk, temporarily mark the trunk itself as failed and pick another tree.
			// The tree remains in the world and may be retried later or by another worker.
			int episodes = recentNavigationFailureEpisodes(ensureBrain(record), villager.blockPosition(), 6, 240L);
			if (episodes >= 3) {
				ensureBrain(record).rememberFailure(rememberedTree.getX(), rememberedTree.getY(), rememberedTree.getZ(),
						NavigationFailure.PATH_NOT_FOUND, ticks);
				logNavigationIssue(record, villager, rememberedTree, "abandon-unreachable-tree",
						NavigationFailure.PATH_NOT_FOUND);
				clearTarget(record);
				finishLocalTaskMovement(villager, record);
				rememberedTree = null;
			}
		}
		BlockPos target = validTreeBase(level, rememberedTree) ? rememberedTree
				: findTreeBase(level, villager.blockPosition(), home, record);
		if (target == null) {
			// Managed forestry: if the surrounding biome has been exhausted, start
			// a new oak plot rather than abandoning the job.
			BlockPos plant = forestryPlot(level, home, record.assignmentIndex, record.workCounter);
			if (!near(villager, plant, 2.6)) {
				moveTo(level, villager, plant, record);
			} else if (level.getBlockState(plant).isAir() && isSoil(level.getBlockState(plant.below()))) {
				level.setBlockAndUpdate(plant, Blocks.OAK_SAPLING.defaultBlockState());
				successfulWork(record, 0.35);
				record.workCounter++;
				finishLocalTaskMovement(villager, record);
			} else {
				// Never stare forever at one unusable forestry cell. Skip it and let
				// the next work cycle select another managed-forest position.
				record.workCounter++;
				clearTarget(record);
				finishLocalTaskMovement(villager, record);
				dirty = true;
			}
			return;
		}

		setTarget(record, "tree", target);

		// Do not require vanilla pathfinding to reach the exact base block. Dense
		// foliage and hillside trees often make that position impossible to path to
		// even though the worker is already standing beside the trunk. If any trunk
		// section is within ordinary Minecraft interaction reach, fell the tree.
		if (!canReachTree(level, villager, target, LUMBERJACK_CHOP_REACH)) {
			BlockPos approach = lumberjackApproachPosition(level, villager, target);
			moveTo(level, villager, approach == null ? target : approach, record);
			// Lumberjacks aggressively cut foliage/logs directly between themselves
			// and their claimed tree rather than waiting for the generic stuck timer.
			tryBreakNavigationObstacle(level, villager, target, record, false);
			return;
		}

		villager.swing(InteractionHand.MAIN_HAND);
		int logs = fellTree(level, target);
		if (logs > 0) {
			spawnResourceDrop(level, record.civilisationId, ResourceType.WOOD, logs, target);
			successfulWork(record, logs * 0.8);
			record.workCounter++;
		}
		clearTarget(record);
		finishLocalTaskMovement(villager, record);
	}

	private boolean canReachTree(ServerLevel level, PathfinderMob villager, BlockPos base, double reach) {
		// Do not use a spherical player-style reach check here. A villager standing
		// immediately beside a trunk should be able to chop upward through a canopy
		// instead of becoming useless because the next remaining log is several
		// blocks above its head. Horizontal reach remains deliberately modest.
		double reachSquared = reach * reach;
		double vx = villager.getX();
		double vz = villager.getZ();
		int feetY = villager.blockPosition().getY();
		for (int y = 0; y < 24; y++) {
			BlockPos pos = base.above(y);
			if (!isLog(level.getBlockState(pos))) {
				if (y > 0)
					break;
				continue;
			}
			double dx = pos.getX() + 0.5 - vx;
			double dz = pos.getZ() + 0.5 - vz;
			if (dx * dx + dz * dz > reachSquared)
				continue;
			int vertical = pos.getY() - feetY;
			if (vertical >= -2 && vertical <= LUMBERJACK_VERTICAL_CHOP_REACH)
				return true;
		}
		return false;
	}

	private BlockPos lumberjackApproachPosition(ServerLevel level, PathfinderMob villager, BlockPos treeBase) {
		BlockPos best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (int radius = 1; radius <= 3; radius++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					if (Math.abs(dx) != radius && Math.abs(dz) != radius)
						continue;
					int x = treeBase.getX() + dx;
					int z = treeBase.getZ() + dz;
					int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
					BlockPos feet = new BlockPos(x, y, z);
					if (!isWorkerStandable(level, feet))
						continue;
					double distance = villager.distanceToSqr(x + 0.5, y, z + 0.5);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = feet;
					}
				}
			}
			if (best != null)
				return best;
		}
		return null;
	}

	private boolean isWorkerStandable(ServerLevel level, BlockPos feet) {
		if (feet == null)
			return false;
		if (!level.getFluidState(feet).isEmpty())
			return false;
		BlockState feetState = level.getBlockState(feet);
		BlockState headState = level.getBlockState(feet.above());
		// Leaf litter, grass, ferns and similar zero/low-collision natural clutter
		// must not turn an otherwise flat surface into an unusable navigation cell.
		// The worker clears it as it approaches, so treat it as temporary body-space.
		if (!feetState.isAir() && !isNaturalPathClutter(feetState))
			return false;
		if (!headState.isAir() && !isNaturalPathClutter(headState))
			return false;
		BlockState ground = level.getBlockState(feet.below());
		// If clutter itself is below the proposed feet position, the heightmap has
		// selected a point one block too high. Callers should lower the target to the
		// clutter block rather than allowing the humanoid to hover above it.
		if (isNaturalPathClutter(ground))
			return false;
		return !ground.isAir() && level.getFluidState(feet.below()).isEmpty();
	}

	private BlockPos findTreeBase(ServerLevel level, BlockPos around, City home, WorkerRecord record) {
		// Prefer the managed forestry district, but do not make it the only place a
		// lumberjack is willing to work. The old implementation sampled X/Z every
		// three blocks and searched only eight blocks down from the heightmap, so a
		// perfectly ordinary one-block trunk could be skipped forever.
		BlockPos forestryCentre = surfacePos(level, home.getBlockX(), home.getBlockZ() - FORESTRY_ZONE_OFFSET);
		BlockPos preferred = findTreeBaseInArea(level, around, forestryCentre, 48, record);
		if (preferred != null)
			return preferred;
		BlockPos cityCentre = surfacePos(level, home.getBlockX(), home.getBlockZ());
		return findTreeBaseInArea(level, around, cityCentre, TREE_SEARCH_RADIUS, record);
	}

	private BlockPos findTreeBaseInArea(ServerLevel level, BlockPos around, BlockPos centre, int radius,
			WorkerRecord record) {
		// Search outward from the worker so the larger radius does not turn every
		// lumberjack think-cycle into a full 190x190 area scan. Coordinates are
		// still constrained to the requested forestry/city search area, and every
		// X/Z column on each ring is examined so one-block trunks cannot be skipped.
		int centreDistance = Math.max(Math.abs(around.getX() - centre.getX()), Math.abs(around.getZ() - centre.getZ()));
		int maxRing = radius + centreDistance;
		for (int ring = 0; ring <= maxRing; ring++) {
			if (ring == 0) {
				BlockPos found = treeBaseInColumn(level, around.getX(), around.getZ(), centre, radius, record);
				if (found != null)
					return found;
				continue;
			}

			for (int dx = -ring; dx <= ring; dx++) {
				BlockPos found = treeBaseInColumn(level, around.getX() + dx, around.getZ() - ring, centre, radius,
						record);
				if (found != null)
					return found;
				found = treeBaseInColumn(level, around.getX() + dx, around.getZ() + ring, centre, radius, record);
				if (found != null)
					return found;
			}
			for (int dz = -ring + 1; dz <= ring - 1; dz++) {
				BlockPos found = treeBaseInColumn(level, around.getX() - ring, around.getZ() + dz, centre, radius,
						record);
				if (found != null)
					return found;
				found = treeBaseInColumn(level, around.getX() + ring, around.getZ() + dz, centre, radius, record);
				if (found != null)
					return found;
			}
		}
		return null;
	}

	private BlockPos treeBaseInColumn(ServerLevel level, int x, int z, BlockPos centre, int radius,
			WorkerRecord record) {
		if (Math.abs(x - centre.getX()) > radius || Math.abs(z - centre.getZ()) > radius)
			return null;
		if (!isChunkLoaded(level, x, z))
			return null;

		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		for (int scan = 0; scan < 40; scan++) {
			BlockPos pos = new BlockPos(x, top - scan, z);
			if (!isLog(level.getBlockState(pos)))
				continue;
			if (validTreeBase(level, pos) && !treeClaimedByOther(record, pos)
					&& !recentNavigationFailureNear(record, pos, 2, 240L))
				return pos;
		}
		return null;
	}

	private BlockPos surfacePos(ServerLevel level, int x, int z) {
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		return new BlockPos(x, y, z);
	}

	private boolean treeClaimedByOther(WorkerRecord worker, BlockPos pos) {
		if (worker == null || pos == null)
			return false;
		for (WorkerRecord other : workers.values()) {
			if (other == worker || !Objects.equals(worker.civilisationId, other.civilisationId))
				continue;
			if (parseJob(other.job) != VillagerJob.LUMBERJACK || !"tree".equals(other.targetKind))
				continue;
			if (other.targetX == pos.getX() && other.targetY == pos.getY() && other.targetZ == pos.getZ())
				return true;
		}
		return false;
	}

	private boolean validTreeBase(ServerLevel level, BlockPos pos) {
		if (pos == null || !isChunkLoaded(level, pos.getX(), pos.getZ()))
			return false;
		BlockState state = level.getBlockState(pos);
		if (!isLog(state))
			return false;
		return isSoil(level.getBlockState(pos.below()));
	}

	private int fellTree(ServerLevel level, BlockPos base) {
		BlockState trunk = level.getBlockState(base);
		if (!isLog(trunk))
			return 0;
		BlockState sapling = saplingFor(trunk);

		// Discover the connected trunk before changing any blocks. This handles
		// 2x2 trunks and branching vanilla trees instead of deleting only one
		// vertical column and leaving most of the tree floating.
		List<BlockPos> queue = new ArrayList<>();
		Set<BlockPos> logs = new HashSet<>();
		queue.add(base);
		for (int index = 0; index < queue.size() && logs.size() < MAX_TREE_LOG_BLOCKS; index++) {
			BlockPos pos = queue.get(index);
			if (logs.contains(pos) || !isLog(level.getBlockState(pos)))
				continue;
			if (Math.abs(pos.getX() - base.getX()) > 6 || Math.abs(pos.getZ() - base.getZ()) > 6
					|| pos.getY() < base.getY() - 1 || pos.getY() > base.getY() + 24)
				continue;
			logs.add(pos);
			queue.add(pos.offset(1, 0, 0));
			queue.add(pos.offset(-1, 0, 0));
			queue.add(pos.offset(0, 1, 0));
			queue.add(pos.offset(0, -1, 0));
			queue.add(pos.offset(0, 0, 1));
			queue.add(pos.offset(0, 0, -1));
		}

		if (logs.isEmpty())
			return 0;
		for (BlockPos pos : logs)
			level.destroyBlock(pos, false);

		// Clear nearby canopy as part of felling. Apart from making the work visible,
		// this prevents the remaining leaf volume from immediately trapping the
		// lumberjack and neighbouring workers after the trunk disappears.
		Set<BlockPos> leaves = new HashSet<>();
		outer: for (BlockPos log : logs) {
			for (int dy = -2; dy <= 3; dy++) {
				for (int dz = -3; dz <= 3; dz++) {
					for (int dx = -3; dx <= 3; dx++) {
						BlockPos leaf = log.offset(dx, dy, dz);
						if (isLeaves(level.getBlockState(leaf))) {
							leaves.add(leaf);
							if (leaves.size() >= MAX_TREE_LEAF_BLOCKS)
								break outer;
						}
					}
				}
			}
		}
		for (BlockPos leaf : leaves)
			level.destroyBlock(leaf, false);

		if (level.getBlockState(base).isAir() && isSoil(level.getBlockState(base.below()))) {
			level.setBlockAndUpdate(base, sapling);
		}
		return logs.size();
	}

	private BlockPos forestryPlot(ServerLevel level, City home, int workerIndex, int workCounter) {
		int radius = 18;
		int angleSlot = Math.floorMod(workerIndex * 7 + workCounter, 32);
		double angle = angleSlot * (Math.PI * 2.0 / 32.0);
		int distance = 14 + Math.floorMod(workerIndex + workCounter, radius - 8);
		int x = home.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
		int z = home.getBlockZ() - FORESTRY_ZONE_OFFSET + (int) Math.round(Math.sin(angle) * distance);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		return new BlockPos(x, y, z);
	}

	private void tickMiner(ServerLevel level, Civilisation civilisation, City home, PathfinderMob villager,
			WorkerRecord record) {
		int minerOrdinal = ensureMinerLaneIndex(record);
		int targetY = miningLevel(minerOrdinal);
		if (!record.inMine) {
			BlockPos entrance = mineEntrance(level, home, minerOrdinal);
			// Every miner owns a dedicated shaft and strip-mine lane. There is no
			// shared-shaft admission queue: all miners may enter, leave and extract
			// resources concurrently regardless of how many miners the civilisation has.
			if (!near(villager, entrance, 1.5)) {
				moveTo(level, villager, entrance, record);
				return;
			}
			BlockPos underground = mineStart(level, home, minerOrdinal, targetY);
			prepareMineAccess(level, entrance, underground, record);
			if (record.forceDeposit || carriedTotal(record) >= MINER_CARRY_LIMIT) {
				// Building a very deep staircase can itself produce a full bulk load.
				// Stay on the surface and let the normal depot transaction empty it
				// before the miner makes its first descent.
				clearMoveTarget(record);
				return;
			}
			beginMineStairTransit(villager, record, entrance, targetY, targetY, -1);
			record.mineProgress = Math.max(0, record.mineProgress);
			record.targetKind = null;
			dirty = true;
			return;
		}

		if (carriedTotal(record) >= MINER_CARRY_LIMIT || record.forceDeposit) {
			BlockPos entrance = mineEntrance(level, home, minerOrdinal);
			BlockPos bottom = mineStairBottom(entrance, targetY);
			if (!near(villager, bottom, 2.2)) {
				moveTo(level, villager, bottom, record);
				return;
			}
			beginMineStairTransit(villager, record, entrance, targetY, entrance.getY(), 1);
			return;
		}

		// A miner is one general profession. Before advancing the strip mine it
		// checks the surrounding tunnel/cave for genuinely exposed ore. This is
		// intentionally not an X-ray search: an ore must have an air face before
		// the miner can divert to it. Newly exposed vein blocks therefore become
		// valid targets naturally as the vein is opened.
		BlockPos visibleOre = findVisibleOre(level, villager.blockPosition(), 12, 7);
		if (visibleOre != null) {
			if (!near(villager, visibleOre, 5.5)) {
				BlockPos approach = findOreApproach(level, visibleOre, villager.blockPosition());
				if (approach != null) {
					setTarget(record, "ore", visibleOre);
					moveTo(level, villager, approach, record);
					return;
				}
			}
			if (near(villager, visibleOre, 5.5)) {
				villager.swing(InteractionHand.MAIN_HAND);
				int processed = mineOreVein(level, visibleOre, record, 64);
				if (processed > 0) {
					successfulWork(record, Math.max(1.0, processed * 0.20));
				}
				clearTarget(record);
				finishLocalTaskMovement(villager, record);
				return;
			}
		}

		BlockPos face = mineFace(level, home, minerOrdinal, targetY, record.mineProgress);
		carveSafeCell(level, new BlockPos(face.getX() - directionX(minerOrdinal), face.getY(),
				face.getZ() - directionZ(minerOrdinal)), record);

		if (!near(villager, face, 3.2)) {
			setTarget(record, "strip_mine", face);
			moveTo(level, villager, face, record);
			return;
		}

		// Advance a high-throughput 3x3 strip-mine face. The miner processes every
		// natural mine material in the face, not just ore and the two blocks directly
		// in front of its head. Ores become their processed strategic resource and
		// ordinary rock/aggregate becomes bulk STONE supply.
		villager.swing(InteractionHand.MAIN_HAND);
		int minedThisPass = 0;
		for (int dz = -1; dz <= 1 && carriedTotal(record) < MINER_CARRY_LIMIT; dz++) {
			for (int dy = 0; dy <= 2 && carriedTotal(record) < MINER_CARRY_LIMIT; dy++) {
				minedThisPass += mineStripBlock(level, face.offset(0, dy, dz), record);
			}
		}
		record.mineProgress++;
		clearTarget(record);
		finishLocalTaskMovement(villager, record);
		successfulWork(record, 0.8);
	}

	private void prepareMineAccess(ServerLevel level, BlockPos entrance, BlockPos underground, WorkerRecord record) {
		if (level == null || entrance == null || underground == null || record == null)
			return;
		int targetY = underground.getY();
		int depth = Math.max(0, entrance.getY() - targetY);

		// Carve a real one-block-wide descending staircase instead of a vertical hole.
		// Each horizontal block west is one block lower, leaving the block below the
		// miner as the tread. Three blocks of headroom make the route comfortable for
		// normal PathfinderMob running in both directions; gravity always remains on.
		for (int step = 0; step <= depth; step++) {
			BlockPos feet = entrance.offset(-step, -step, 0);
			clearMineAccessBlock(level, feet, record);
			clearMineAccessBlock(level, feet.above(), record);
			clearMineAccessBlock(level, feet.above(2), record);
			ensureMineStairFloor(level, feet.below());
		}

		BlockPos stairBottom = mineStairBottom(entrance, targetY);
		BlockPos cursor = stairBottom;
		carveSafeCell(level, cursor, record);
		clearMineAccessBlock(level, cursor.above(2), record);

		// Connect the staircase bottom to this miner's unique strip-mine lane with a
		// three-block-high access passage. The lane remains private and unlimited.
		int stepX = Integer.compare(underground.getX(), cursor.getX());
		while (cursor.getX() != underground.getX()) {
			cursor = cursor.offset(stepX, 0, 0);
			carveSafeCell(level, cursor, record);
			clearMineAccessBlock(level, cursor.above(2), record);
		}
		int stepZ = Integer.compare(underground.getZ(), cursor.getZ());
		while (cursor.getZ() != underground.getZ()) {
			cursor = cursor.offset(0, 0, stepZ);
			carveSafeCell(level, cursor, record);
			clearMineAccessBlock(level, cursor.above(2), record);
		}
	}

	private void ensureMineStairFloor(ServerLevel level, BlockPos floor) {
		if (level == null || floor == null || !isChunkLoaded(level, floor.getX(), floor.getZ()))
			return;
		BlockState state = level.getBlockState(floor);
		if (state.isAir() || !state.getFluidState().isEmpty()) {
			// A staircase may cross an existing cave/ravine. Bridge only the missing
			// tread so the miner can genuinely run the route instead of levitating.
			level.setBlockAndUpdate(floor, Blocks.COBBLESTONE.defaultBlockState());
			dirty = true;
		}
	}

	private BlockPos mineStairBottom(BlockPos entrance, int targetY) {
		int depth = Math.max(0, entrance.getY() - targetY);
		return entrance.offset(-depth, -depth, 0);
	}

	private BlockPos mineStairPointAtY(BlockPos entrance, int targetY, int y) {
		int clampedY = clampInt(y, targetY, entrance.getY());
		int step = entrance.getY() - clampedY;
		return entrance.offset(-step, -step, 0);
	}

	private void beginMineStairTransit(PathfinderMob villager, WorkerRecord record, BlockPos entrance, int bottomY,
			int targetY, int direction) {
		villager.getNavigation().stop();
		villager.setNoGravity(false);
		clearMoveTarget(record);
		record.mineTransitDirection = direction < 0 ? -1 : 1;
		record.mineTransitTargetY = targetY;
		record.mineTransitBottomY = bottomY;
		record.mineTransitX = entrance.getX();
		record.mineTransitZ = entrance.getZ();
		record.mineTransitEntranceY = entrance.getY();
		dirty = true;
	}

	private void tickMineShaftTransit(PathfinderMob villager, WorkerRecord record) {
		// The field name remains mineTransitDirection for save compatibility, but
		// transit is now entirely ordinary gravity/path navigation on a carved stair.
		villager.setNoGravity(false);
		int entranceY = record.mineTransitEntranceY;
		if (entranceY == 0) {
			// Legacy v11 transit state has no stored entrance height. Cancel it and let
			// the normal miner recovery code join the new staircase physically.
			record.mineTransitDirection = 0;
			record.minerShaftRecoveryActive = true;
			villager.getNavigation().stop();
			dirty = true;
			return;
		}

		BlockPos entrance = new BlockPos(record.mineTransitX, entranceY, record.mineTransitZ);
		int horizonY = Math.min(record.mineTransitBottomY, entranceY);
		int destinationY = record.mineTransitTargetY;
		BlockPos destination = mineStairPointAtY(entrance, horizonY, destinationY);
		if (near(villager, destination, 1.45)) {
			villager.getNavigation().stop();
			villager.setNoGravity(false);
			if (record.mineTransitDirection < 0) {
				record.inMine = true;
			} else {
				record.inMine = false;
				record.forceDeposit = true;
			}
			record.mineTransitDirection = 0;
			record.minerShaftRecoveryActive = false;
			dirty = true;
			return;
		}

		int depth = Math.max(0, entranceY - horizonY);
		int currentStep = clampInt(entrance.getX() - villager.blockPosition().getX(), 0, depth);
		int nextStep = record.mineTransitDirection < 0 ? Math.min(depth, currentStep + MINE_STAIR_LOOKAHEAD_STEPS)
				: Math.max(0, currentStep - MINE_STAIR_LOOKAHEAD_STEPS);
		BlockPos next = entrance.offset(-nextStep, -nextStep, 0);
		villager.getNavigation().moveTo(next.getX() + 0.5, next.getY(), next.getZ() + 0.5, MINE_STAIR_WALK_SPEED);
	}

	/**
	 * One miner profession uses several strip-mining horizons so a normal group of
	 * miners covers the ore distributions from shallow coal/copper through deep
	 * redstone/diamond without exposing separate mining jobs to the player.
	 */
	private int miningLevel(int minerOrdinal) {
		return switch (Math.floorMod(minerOrdinal, 4)) {
		case 0 -> 48;
		case 1 -> 16;
		case 2 -> -16;
		default -> -48;
		};
	}

	/**
	 * Recovers a miner whose physical body is underground but whose persistent
	 * inMine/transit state has become inconsistent. Generic surface navigation is
	 * forbidden in this state: it produces impossible uphill waypoints and lets a
	 * miner wander away from the shaft through caves. Recovery remains fully
	 * physical. The miner cuts at most one safe natural two-block-high step per
	 * tick toward its assigned shaft column, then uses the existing vertical shaft
	 * transit.
	 */
	private boolean tickMinerUndergroundShaftRecovery(ServerLevel level, City home, PathfinderMob villager,
			WorkerRecord record) {
		if (level == null || home == null || villager == null || record == null)
			return false;
		if (record.mineTransitDirection != 0)
			return false;

		int minerOrdinal = ensureMinerLaneIndex(record);
		int workY = miningLevel(minerOrdinal);
		BlockPos entrance = mineEntrance(level, home, minerOrdinal);
		BlockPos here = villager.blockPosition();
		if (!isChunkLoaded(level, here.getX(), here.getZ()))
			return false;
		int localSurfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, here.getX(), here.getZ());

		boolean belowEntrance = here.getY() <= entrance.getY() - 3;
		boolean belowLocalSurface = here.getY() <= localSurfaceY - 4;
		WorkerBrainState brain = ensureBrain(record);
		boolean genericUpwardLeg = record.hasMoveTarget && record.moveTargetY >= here.getY() + 6
				&& (record.targetKind == null || !targetKindMatchesProfession(VillagerJob.MINER, record.targetKind));
		boolean wrongVerticalBand = Math.abs(here.getY() - workY) > 6;
		boolean inconsistent = !record.inMine || genericUpwardLeg || record.minerShaftRecoveryActive
				|| (record.forceDeposit && wrongVerticalBand);
		boolean genuinelyUnderground = belowEntrance && (belowLocalSurface || record.forceDeposit || genericUpwardLeg
				|| record.minerShaftRecoveryActive || !record.inMine);
		if (!genuinelyUnderground || !inconsistent) {
			record.minerShaftRecoveryActive = false;
			return false;
		}

		if (!record.minerShaftRecoveryActive) {
			record.minerShaftRecoveryActive = true;
			logNavigationIssue(record, villager, entrance, "miner-underground-state-repair",
					NavigationFailure.PATH_NOT_FOUND);
		}

		WorkerPlannerService.getInstance().forget(record.uuid);
		brain.planGeneration++;
		brain.routeRequestPending = false;
		brain.escapeRequestPending = false;
		brain.route.clear();
		brain.routeIndex = 0;
		brain.escapeRoute.clear();
		brain.escapeIndex = 0;
		brain.escaping = false;
		brain.clearGoal();
		clearTarget(record);
		clearMoveTarget(record);
		record.inMine = true;
		record.stuckChecks = 0;
		record.navigationAssistTicks = 0;
		villager.getNavigation().stop();
		villager.setNoGravity(false);

		// Ensure the new staircase exists, then join it at approximately the miner's
		// current elevation. Old vertical-shaft saves therefore migrate physically by
		// cutting a short horizontal connector to the stair instead of levitating.
		BlockPos underground = mineStart(level, home, minerOrdinal, workY);
		prepareMineAccess(level, entrance, underground, record);
		BlockPos stairJoin = mineStairPointAtY(entrance, workY, here.getY());
		double horizontalToStair = Math.sqrt(horizontalDistanceSquared(here, stairJoin));
		if (horizontalToStair > 1.55) {
			int rawDx = Integer.compare(stairJoin.getX(), here.getX());
			int rawDz = Integer.compare(stairJoin.getZ(), here.getZ());
			int dx = rawDx;
			int dz = rawDz;
			if (dx != 0 && dz != 0) {
				if (Math.abs(stairJoin.getX() - here.getX()) >= Math.abs(stairJoin.getZ() - here.getZ()))
					dz = 0;
				else
					dx = 0;
			}
			BlockPos next = here.offset(dx, 0, dz);
			boolean prepared = prepareMinerRecoveryCell(level, villager, next, record);
			if (!prepared) {
				int altDx = dx != 0 ? 0 : rawDx;
				int altDz = dz != 0 ? 0 : rawDz;
				if (altDx != 0 || altDz != 0) {
					BlockPos alternate = here.offset(altDx, 0, altDz);
					if (prepareMinerRecoveryCell(level, villager, alternate, record)) {
						next = alternate;
						prepared = true;
					}
				}
			}
			if (prepared && isSafeNavigationFeet(level, record, next)) {
				record.hasMoveTarget = true;
				record.moveTargetX = next.getX();
				record.moveTargetY = next.getY();
				record.moveTargetZ = next.getZ();
				issueNavigation(level, villager, next);
				nudgeWorkerToward(villager, record, next);
			}
			dirty = true;
			return true;
		}

		boolean leaving = record.forceDeposit || carriedTotal(record) >= MINER_CARRY_LIMIT;
		int destinationY = leaving ? entrance.getY() : workY;
		beginMineStairTransit(villager, record, entrance, workY, destinationY, leaving ? 1 : -1);
		logNavigationIssue(record, villager, mineStairPointAtY(entrance, workY, destinationY),
				leaving ? "miner-recovery-leaving-stairs" : "miner-recovery-entering-stairs", NavigationFailure.NONE);
		dirty = true;
		return true;
	}

	/**
	 * Clears only natural/minable material for the emergency horizontal mine
	 * return.
	 */
	private boolean prepareMinerRecoveryCell(ServerLevel level, PathfinderMob villager, BlockPos feet,
			WorkerRecord record) {
		if (level == null || villager == null || feet == null || record == null)
			return false;
		if (!isChunkLoaded(level, feet.getX(), feet.getZ()))
			return false;
		boolean changed = false;
		for (BlockPos pos : new BlockPos[] { feet, feet.above() }) {
			BlockState state = level.getBlockState(pos);
			if (state.isAir())
				continue;
			if (level.getBlockEntity(pos) != null)
				return false;
			ResourceType recovered = resourceForMineBlock(state);
			boolean natural = isWorkerBreakableObstacle(state) || recovered != null;
			if (!natural || state.is(Blocks.BEDROCK) || state.is(Blocks.BEACON) || state.is(Blocks.CHEST)
					|| state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.FURNACE) || state.is(Blocks.BARREL))
				return false;
			if (!claimObstacle(record, pos))
				return false;
			if (!level.destroyBlock(pos, false)) {
				obstacleClaims.remove(obstacleKey(pos));
				return false;
			}
			obstacleClaims.remove(obstacleKey(pos));
			if (recovered == null)
				recovered = obstacleResource(state);
			if (recovered != null)
				carryMinedResource(level, record, recovered, 1, pos);
			changed = true;
		}
		if (changed) {
			villager.swing(InteractionHand.MAIN_HAND);
			record.lastObstacleBreakTick = ticks;
			dirty = true;
		}
		return level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir();
	}

	/**
	 * Repairs the specific stale-save/runtime state where a miner body is
	 * physically part-way down its assigned dedicated shaft but
	 * mineTransitDirection has been lost. Ordinary PathNavigation must never be
	 * asked to solve a 40-120 block vertical mine transition. The worker is simply
	 * re-centred and the existing physical shaft transit resumes; no teleportation
	 * is used.
	 */
	private boolean repairInterruptedMinerShaftTransit(ServerLevel level, City home, PathfinderMob villager,
			WorkerRecord record) {
		if (level == null || home == null || villager == null || record == null)
			return false;
		if (record.mineTransitDirection != 0)
			return false;

		int minerOrdinal = ensureMinerLaneIndex(record);
		int targetY = miningLevel(minerOrdinal);
		BlockPos entrance = mineEntrance(level, home, minerOrdinal);
		BlockPos here = villager.blockPosition();
		boolean betweenSurfaceAndHorizon = here.getY() < entrance.getY() - 2 && here.getY() > targetY + 1;
		if (!betweenSurfaceAndHorizon) {
			if (record.inMine && Math.abs(here.getY() - targetY) > 6 && here.getY() >= entrance.getY() - 3) {
				record.inMine = false;
				clearTarget(record);
				clearMoveTarget(record);
				ensureBrain(record).clearGoal();
				record.stuckChecks = 0;
				dirty = true;
			} else if (!record.inMine && Math.abs(here.getY() - targetY) <= 4 && here.getY() < entrance.getY() - 4) {
				record.inMine = true;
				clearMoveTarget(record);
				dirty = true;
			}
			return false;
		}

		BlockPos stairPoint = mineStairPointAtY(entrance, targetY, here.getY());
		double stairDistanceSq = horizontalDistanceSquared(here, stairPoint);
		if (stairDistanceSq <= 4.5 * 4.5) {
			WorkerBrainState brain = ensureBrain(record);
			brain.clearGoal();
			clearTarget(record);
			clearMoveTarget(record);
			record.stuckChecks = 0;
			record.navigationAssistTicks = 0;
			boolean leaving = record.forceDeposit || carriedTotal(record) >= MINER_CARRY_LIMIT;
			int destinationY = leaving ? entrance.getY() : targetY;
			record.inMine = !leaving;
			prepareMineAccess(level, entrance, mineStart(level, home, minerOrdinal, targetY), record);
			beginMineStairTransit(villager, record, entrance, targetY, destinationY, leaving ? 1 : -1);
			logNavigationIssue(record, villager, mineStairPointAtY(entrance, targetY, destinationY),
					leaving ? "repair-mine-stairs-exit" : "repair-mine-stairs-entry", NavigationFailure.NONE);
			dirty = true;
			return true;
		}

		// A v11 miner may still be standing in the old vertical shaft. Mark it for
		// physical staircase recovery; the next recovery stage cuts sideways at the
		// current Y and joins the new walkable route.
		double oldShaftDistanceSq = horizontalDistanceSquared(here, entrance);
		if (oldShaftDistanceSq <= 4.5 * 4.5) {
			record.minerShaftRecoveryActive = true;
			dirty = true;
		}
		return false;
	}

	private BlockPos mineEntrance(ServerLevel level, City home, int index) {
		// Every miner gets a permanent, unique one-block shaft. The first four keep
		// the legacy depth-group coordinates so existing worlds migrate gently; all
		// later miners expand symmetrically outward in four-block increments. There
		// is therefore no practical miner-capacity limit and no mine queue.
		int x = home.getBlockX() - MINE_ZONE_OFFSET;
		int z = home.getBlockZ() + mineShaftZOffset(index);

		// Never read the heightmap from the excavated shaft column itself. Once a
		// one-block shaft is open, MOTION_BLOCKING_NO_LEAVES sees the stone at the
		// bottom of that hole and the apparent "surface entrance" collapses from
		// roughly Y=70 to the mining horizon. That was why loaded miners at Y=55-61
		// were marked inMine=false and tried to walk directly uphill to depot chests.
		// A small median of the surrounding surface is stable after excavation and is
		// also resistant to one nearby ravine/tree trunk.
		int y = robustLocalSurfaceY(level, x, z);
		return new BlockPos(x, y, z);
	}

	private int mineShaftZOffset(int minerOrdinal) {
		int ordinal = Math.max(0, minerOrdinal);
		if (ordinal < 4)
			return ordinal * 4 - 6; // legacy: -6, -2, +2, +6
		int extra = ordinal - 4;
		int ring = extra / 2;
		int magnitude = 10 + ring * 4;
		return (extra & 1) == 0 ? magnitude : -magnitude;
	}

	private int mineLaneZOffset(int minerOrdinal) {
		int ordinal = Math.max(0, minerOrdinal);
		// Preserve the old first sixteen strip-mine lanes so existing tunnels and
		// mineProgress continue exactly where they were before the queue removal.
		if (ordinal < 16)
			return (ordinal - 8) * 3;
		int extra = ordinal - 16;
		int ring = extra / 2;
		return (extra & 1) == 0 ? 24 + ring * 3 : -27 - ring * 3;
	}

	private int mineEntranceCount(City home) {
		if (home == null)
			return 4;
		int minerCount = 0;
		int highestReservedLane = -1;
		for (WorkerRecord worker : workers.values()) {
			if (!Objects.equals(worker.homeCityId, home.getId()))
				continue;
			if (parseJob(worker.job) == VillagerJob.MINER)
				minerCount++;
			highestReservedLane = Math.max(highestReservedLane, worker.mineLaneIndex);
		}
		// Keep the four legacy shaft locations protected in old worlds even when a
		// civilisation currently has fewer than four miners. A temporarily reserved
		// lane (for a reassigned worker still climbing out) stays protected as well.
		return Math.max(4, Math.max(minerCount, highestReservedLane + 1));
	}

	private int robustLocalSurfaceY(ServerLevel level, int centreX, int centreZ) {
		List<Integer> heights = new ArrayList<>();
		for (int radius = 1; radius <= 2; radius++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					if (Math.abs(dx) != radius && Math.abs(dz) != radius)
						continue;
					int x = centreX + dx;
					int z = centreZ + dz;
					if (!isChunkLoaded(level, x, z))
						continue;
					heights.add(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
				}
			}
		}
		if (heights.isEmpty()) {
			return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centreX, centreZ);
		}
		heights.sort(Integer::compareTo);
		int middle = heights.size() / 2;
		if ((heights.size() & 1) == 1)
			return heights.get(middle);
		return (heights.get(middle - 1) + heights.get(middle)) / 2;
	}

	private boolean tickNonMinerMineAvoidance(ServerLevel level, City home, PathfinderMob villager,
			WorkerRecord record) {
		if (level == null || home == null || villager == null || record == null)
			return false;
		BlockPos here = villager.blockPosition();
		BlockPos nearest = nearestMineEntrance(level, home, here);
		if (nearest == null) {
			clearNonMinerMineAvoidance(record);
			return false;
		}
		double horizontal = Math.sqrt(horizontalDistanceSquared(here, nearest));

		// "Leaving mine" is reserved for somebody who is actually in the dedicated
		// shaft/tunnel system (or who was just reassigned while record.inMine was
		// true). The old broad X/Z/Y rectangle could classify a road builder or
		// factory worker standing in a natural ravine as being inside the mine and
		// trap that profession in the recovery state forever.
		boolean actuallyUndergroundMine = record.inMine || record.nonMinerMineAvoidanceActive
				|| isInsidePhysicalMineAccess(level, home, here);
		if (actuallyUndergroundMine) {
			WorkerBrainState brain = ensureBrain(record);
			if (!record.nonMinerMineAvoidanceActive) {
				if (brain.route != null)
					brain.route.clear();
				brain.routeIndex = 0;
				brain.routeRequestPending = false;
				brain.planGeneration++;
				record.nonMinerMineAvoidanceActive = true;
				record.nonMinerMineAvoidanceTicks = 0;
				dirty = true;
			}
			record.nonMinerMineAvoidanceTicks++;

			int nearestOrdinal = nearestMineEntranceOrdinal(level, home, here);
			if (nearestOrdinal < 0)
				nearestOrdinal = closestMiningDepthGroup(here.getY());
			BlockPos entrance = mineEntrance(level, home, nearestOrdinal);
			int workY = miningLevel(nearestOrdinal);
			BlockPos stairJoin = mineStairPointAtY(entrance, workY, here.getY());
			if (near(villager, stairJoin, 2.3)) {
				beginMineStairTransit(villager, record, entrance, workY, entrance.getY(), 1);
			} else {
				setLocalMoveTarget(level, villager, record, stairJoin);
			}
			return true;
		}

		// If a stale save/runtime flag says "Leaving mine" while the humanoid is
		// plainly not in mine geometry, discard it immediately and resume the real
		// profession objective instead of freezing that worker.
		if (record.nonMinerMineAvoidanceActive || record.inMine) {
			clearNonMinerMineAvoidance(record);
		}

		// Surface workers merely detour around an open shaft. Do NOT set the
		// persistent mine-recovery flag here; proximity to a shaft is not the same as
		// having fallen into the mine and should never change the visible activity to
		// "Leaving mine".
		if (horizontal <= NON_MINER_MINE_EXCLUSION_RADIUS + 0.5 && here.getY() >= nearest.getY() - 1) {
			int awayX = Integer.compare(here.getX(), nearest.getX());
			int awayZ = Integer.compare(here.getZ(), nearest.getZ());
			if (awayX == 0 && awayZ == 0)
				awayX = 1;
			int x = nearest.getX() + awayX * (NON_MINER_MINE_EXCLUSION_RADIUS + 2);
			int z = nearest.getZ() + awayZ * (NON_MINER_MINE_EXCLUSION_RADIUS + 2);
			BlockPos detour = surfacePos(level, x, z);
			if (!isWorkerStandable(level, detour)) {
				detour = surfacePos(level, nearest.getX() + NON_MINER_MINE_EXCLUSION_RADIUS + 2, nearest.getZ());
			}
			ensureBrain(record).rememberFailure(nearest.getX(), nearest.getY(), nearest.getZ(),
					NavigationFailure.PATH_NOT_FOUND, ticks);
			setLocalMoveTarget(level, villager, record, detour);
			return true;
		}
		return false;
	}

	private boolean isInsidePhysicalMineAccess(ServerLevel level, City home, BlockPos here) {
		if (level == null || home == null || here == null)
			return false;
		for (int ordinal = 0; ordinal < mineEntranceCount(home); ordinal++) {
			BlockPos entrance = mineEntrance(level, home, ordinal);
			int workY = miningLevel(ordinal);
			if (here.getY() >= entrance.getY() - 1 || here.getY() < workY - 2)
				continue;
			BlockPos stair = mineStairPointAtY(entrance, workY, here.getY());
			if (horizontalDistanceSquared(here, stair) <= 2.5 * 2.5)
				return true;
		}
		return false;
	}

	private void clearNonMinerMineAvoidance(WorkerRecord record) {
		if (record == null)
			return;
		record.nonMinerMineAvoidanceActive = false;
		record.nonMinerMineAvoidanceTicks = 0;
		record.inMine = false;
		if (record.mineTransitDirection != 0)
			record.mineTransitDirection = 0;
		if (parseJob(record.job) != VillagerJob.MINER)
			record.mineLaneIndex = -1;
		record.needsNavigationRehydrate = true;
		dirty = true;
	}

	private BlockPos nearestMineEntrance(ServerLevel level, City home, BlockPos from) {
		int ordinal = nearestMineEntranceOrdinal(level, home, from);
		return ordinal < 0 ? null : mineEntrance(level, home, ordinal);
	}

	private int nearestMineEntranceOrdinal(ServerLevel level, City home, BlockPos from) {
		if (level == null || home == null || from == null)
			return -1;
		int bestOrdinal = -1;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (int ordinal = 0; ordinal < mineEntranceCount(home); ordinal++) {
			BlockPos entrance = mineEntrance(level, home, ordinal);
			BlockPos comparison = from.getY() < entrance.getY() - 2
					? mineStairPointAtY(entrance, miningLevel(ordinal), from.getY())
					: entrance;
			double distance = horizontalDistanceSquared(from, comparison);
			if (distance < bestDistance) {
				bestDistance = distance;
				bestOrdinal = ordinal;
			}
		}
		return bestOrdinal;
	}

	private int closestMiningDepthGroup(int y) {
		int bestGroup = 0;
		int bestDelta = Integer.MAX_VALUE;
		for (int group = 0; group < 4; group++) {
			int delta = Math.abs(y - miningLevel(group));
			if (delta < bestDelta) {
				bestDelta = delta;
				bestGroup = group;
			}
		}
		return bestGroup;
	}

	private boolean isMineSurfaceExclusion(WorkerRecord record, int x, int z) {
		if (record == null)
			return false;
		City home = cityById(record.homeCityId);
		if (home == null)
			return false;
		int shaftX = home.getBlockX() - MINE_ZONE_OFFSET;
		int radiusSq = NON_MINER_MINE_EXCLUSION_RADIUS * NON_MINER_MINE_EXCLUSION_RADIUS;
		for (int ordinal = 0; ordinal < mineEntranceCount(home); ordinal++) {
			int shaftZ = home.getBlockZ() + mineShaftZOffset(ordinal);
			int dx = x - shaftX;
			int dz = z - shaftZ;
			if (dx * dx + dz * dz <= radiusSq)
				return true;
		}
		return false;
	}

	private BlockPos mineStart(ServerLevel level, City home, int index, int y) {
		// The work lane begins just beyond the bottom of the miner's private staircase.
		// Z remains unique/non-wrapping, while X automatically accounts for how deep
		// this particular mining horizon is below the local surface.
		BlockPos entrance = mineEntrance(level, home, index);
		BlockPos bottom = mineStairBottom(entrance, y);
		return new BlockPos(bottom.getX() - 4, y, home.getBlockZ() + mineLaneZOffset(index));
	}

	private BlockPos mineFace(ServerLevel level, City home, int index, int y, int progress) {
		BlockPos start = mineStart(level, home, index, y);
		return new BlockPos(start.getX() - progress, y, start.getZ());
	}

	private int directionX(int index) {
		return -1;
	}

	private int directionZ(int index) {
		return 0;
	}

	private void carveSafeCell(ServerLevel level, BlockPos pos, WorkerRecord record) {
		if (!isChunkLoaded(level, pos.getX(), pos.getZ()))
			return;
		clearMineAccessBlock(level, pos, record);
		clearMineAccessBlock(level, pos.above(), record);
	}

	private void clearMineAccessBlock(ServerLevel level, BlockPos pos, WorkerRecord record) {
		if (!isChunkLoaded(level, pos.getX(), pos.getZ()))
			return;
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || isProtectedMineBlock(level, pos, state))
			return;
		ResourceType resource = resourceForMineBlock(state);
		if (resource == null && !isWorkerBreakableObstacle(state) && state.getFluidState().isEmpty())
			return;
		if (level.destroyBlock(pos, false) && resource != null) {
			carryMinedResource(level, record, resource, 1, pos);
		}
	}

	/**
	 * Finds the nearest exposed ore around a miner. Buried ore is deliberately
	 * ignored.
	 */
	private BlockPos findVisibleOre(ServerLevel level, BlockPos around, int horizontalRadius, int verticalRadius) {
		BlockPos best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
			for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
				for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
					if (dx * dx + dz * dz > horizontalRadius * horizontalRadius)
						continue;
					BlockPos pos = around.offset(dx, dy, dz);
					if (!isChunkLoaded(level, pos.getX(), pos.getZ()))
						continue;
					BlockState state = level.getBlockState(pos);
					if (!isAnyOre(state) || !isOreExposed(level, pos))
						continue;
					double distance = distanceSquared(around, pos);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = pos;
					}
				}
			}
		}
		return best;
	}

	private boolean isOreExposed(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos.offset(1, 0, 0)).isAir() || level.getBlockState(pos.offset(-1, 0, 0)).isAir()
				|| level.getBlockState(pos.offset(0, 1, 0)).isAir() || level.getBlockState(pos.offset(0, -1, 0)).isAir()
				|| level.getBlockState(pos.offset(0, 0, 1)).isAir()
				|| level.getBlockState(pos.offset(0, 0, -1)).isAir();
	}

	private BlockPos findOreApproach(ServerLevel level, BlockPos ore, BlockPos from) {
		BlockPos best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (int dy = -2; dy <= 1; dy++) {
			for (int dz = -3; dz <= 3; dz++) {
				for (int dx = -3; dx <= 3; dx++) {
					BlockPos stand = ore.offset(dx, dy, dz);
					if (!isChunkLoaded(level, stand.getX(), stand.getZ()))
						continue;
					if (!level.getBlockState(stand).isAir() || !level.getBlockState(stand.above()).isAir())
						continue;
					if (level.getBlockState(stand.below()).isAir())
						continue;
					if (distanceSquared(stand, ore) > 30.25)
						continue; // within mining reach
					double distance = distanceSquared(from, stand);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = stand;
					}
				}
			}
		}
		return best;
	}

	private int mineOreVein(ServerLevel level, BlockPos start, WorkerRecord record, int maxBlocks) {
		if (level == null || start == null || record == null || maxBlocks <= 0)
			return 0;
		ResourceType targetResource = resourceForOre(level.getBlockState(start));
		if (targetResource == null)
			return 0;
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		Set<BlockPos> visited = new HashSet<>();
		queue.add(start);
		int processed = 0;
		while (!queue.isEmpty() && processed < maxBlocks && carriedTotal(record) < MINER_CARRY_LIMIT) {
			BlockPos pos = queue.removeFirst();
			if (!visited.add(pos))
				continue;
			BlockState state = level.getBlockState(pos);
			if (resourceForOre(state) != targetResource)
				continue;
			if (mineOreBlock(level, pos, record) <= 0)
				continue;
			processed++;
			queue.add(pos.offset(1, 0, 0));
			queue.add(pos.offset(-1, 0, 0));
			queue.add(pos.offset(0, 1, 0));
			queue.add(pos.offset(0, -1, 0));
			queue.add(pos.offset(0, 0, 1));
			queue.add(pos.offset(0, 0, -1));
		}
		return processed;
	}

	private int mineOreBlock(ServerLevel level, BlockPos pos, WorkerRecord record) {
		BlockState state = level.getBlockState(pos);
		ResourceType resource = resourceForOre(state);
		if (resource == null || isProtectedMineBlock(level, pos, state))
			return 0;
		if (!level.destroyBlock(pos, false))
			return 0;
		carryMinedResource(level, record, resource, 1, pos);
		return 1;
	}

	private int mineStripBlock(ServerLevel level, BlockPos pos, WorkerRecord record) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || isProtectedMineBlock(level, pos, state))
			return 0;
		ResourceType resource = resourceForMineBlock(state);
		if (resource == null)
			return 0;
		if (!level.destroyBlock(pos, false))
			return 0;
		carryMinedResource(level, record, resource, 1, pos);
		return 1;
	}

	private ResourceType resourceForMineBlock(BlockState state) {
		ResourceType ore = resourceForOre(state);
		if (ore != null)
			return ore;
		if (isStone(state) || state.is(Blocks.GRANITE) || state.is(Blocks.DIORITE) || state.is(Blocks.ANDESITE)
				|| state.is(Blocks.CALCITE) || state.is(Blocks.DRIPSTONE_BLOCK) || state.is(Blocks.GRAVEL)
				|| state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.DIRT)
				|| state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.CLAY)) {
			return ResourceType.STONE;
		}
		return null;
	}

	private boolean isProtectedMineBlock(ServerLevel level, BlockPos pos, BlockState state) {
		if (state == null || state.isAir())
			return false;
		if (state.is(Blocks.BEDROCK) || state.is(Blocks.BEACON) || state.is(Blocks.CHEST)
				|| state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL) || state.is(Blocks.CRAFTING_TABLE)
				|| state.is(Blocks.FURNACE))
			return true;
		return level != null && pos != null && level.getBlockEntity(pos) != null;
	}

	private void carryMinedResource(ServerLevel level, WorkerRecord record, ResourceType resource, int amount,
			BlockPos pos) {
		if (record == null || resource == null || amount <= 0)
			return;
		int room = Math.max(0, MINER_CARRY_LIMIT - carriedTotal(record));
		int take = Math.min(room, amount);
		if (take > 0) {
			carry(record, resource, take);
			record.lastPickupTick = ticks;
		}
		int overflow = amount - take;
		if (overflow > 0 && level != null && pos != null) {
			spawnResourceDrop(level, record.civilisationId, resource, overflow, pos);
		}
		if (carriedTotal(record) >= MINER_CARRY_LIMIT)
			record.forceDeposit = true;
		dirty = true;
	}

	private void tickRoadBuilder(ServerLevel level, Civilisation civilisation, City home, PathfinderMob villager,
			WorkerRecord record) {
		// Before extending the planned road network, help a countryman who has become
		// blocked by a hard natural wall. One road builder claims the request, makes
		// the safe opening, and everyone else can then reuse the corridor.
		if (tickTravelAssistRequest(level, civilisation, villager, record))
			return;

		List<BlockPos> endpoints = roadNetworkEndpoints(level, civilisation.getId(), home);
		if (endpoints.isEmpty()) {
			int homeY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, home.getBlockX(), home.getBlockZ());
			logNavigationIssue(record, villager, new BlockPos(home.getBlockX(), homeY, home.getBlockZ()),
					"road-network-no-endpoints", NavigationFailure.PATH_NOT_FOUND);
			finishLocalTaskMovement(villager, record);
			return;
		}

		int ordinal = jobOrdinal(record, VillagerJob.ROAD_BUILDER);
		int routeIndex = Math.floorMod(record.roadRouteIndex + ordinal, endpoints.size());
		BlockPos endpoint = endpoints.get(routeIndex);

		int dx = endpoint.getX() - home.getBlockX();
		int dz = endpoint.getZ() - home.getBlockZ();
		int length = Math.max(1, Math.max(Math.abs(dx), Math.abs(dz)));
		int step = Math.max(0, Math.min(record.roadRouteStep, length));
		double t = step / (double) length;
		int x = home.getBlockX() + (int) Math.round(dx * t);
		int z = home.getBlockZ() + (int) Math.round(dz * t);

		// A farmer/factory zone belongs exclusively to its worker until death.
		// Roads terminate at zone entrances and never overwrite blocks inside those
		// reserved areas.
		if (isInsideReservedWorkZone(civilisation.getId(), x, z)) {
			record.roadRouteStep++;
			if (record.roadRouteStep > length) {
				record.roadRouteStep = 0;
				record.roadRouteIndex++;
			}
			finishLocalTaskMovement(villager, record);
			dirty = true;
			return;
		}

		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		BlockPos road = new BlockPos(x, y, z);
		BlockPos stand = road.above();
		if (!near(villager, stand, 3.0)) {
			moveTo(level, villager, stand, record);
			return;
		}

		BlockState current = level.getBlockState(road);
		if (!isRoad(current)) {
			BlockState replacement = (current.is(Blocks.DIRT) || current.is(Blocks.GRASS_BLOCK))
					? Blocks.DIRT_PATH.defaultBlockState()
					: Blocks.GRAVEL.defaultBlockState();
			level.setBlockAndUpdate(road, replacement);
			if (!level.getBlockState(stand).isAir())
				level.destroyBlock(stand, false);
			civilisation.addRoadProgress(100.0 / Math.max(1.0, length));
			while (civilisation.completeRoadIfReady()) {
				// One strategy segment represents completed physical connectivity.
			}
			successfulWork(record, 1.0);
		}

		record.roadRouteStep++;
		if (record.roadRouteStep > length) {
			record.roadRouteStep = 0;
			record.roadRouteIndex++;
		}
		record.buildProgress++;
		// This road cell is complete. Do not leave the preceding stand position alive
		// until the next staggered profession tick; that stale local leg was another
		// source of road-worker vibration and bogus recovery status.
		finishLocalTaskMovement(villager, record);
		dirty = true;
	}

	private List<BlockPos> roadNetworkEndpoints(ServerLevel level, String civilisationId, City home) {
		List<BlockPos> endpoints = new ArrayList<>();

		// 1) Every persistent farmer/factory zone gets a road entrance.
		for (WorkerRecord worker : workers.values()) {
			if (!Objects.equals(civilisationId, worker.civilisationId))
				continue;
			if (worker.hasFarmerZone) {
				endpoints.add(zoneRoadEntrance(level, home, new WorkZone(worker.farmerZoneMinX, worker.farmerZoneMaxX,
						worker.farmerZoneMinZ, worker.farmerZoneMaxZ)));
			}
			if (worker.hasFactoryZone) {
				endpoints.add(zoneRoadEntrance(level, home, new WorkZone(worker.factoryZoneMinX, worker.factoryZoneMaxX,
						worker.factoryZoneMinZ, worker.factoryZoneMaxZ)));
			}
		}

		// 2) A capital-to-command-post spine connects every city held by the country.
		for (Providence providence : DataManager.getProvidences().values()) {
			if (providence == null || !providence.isEstablished())
				continue;
			City city = providence.getCity();
			if (city == null || city.getId() == null || city.getId().equals(home.getId()))
				continue;
			if (!Objects.equals(city.getControllerId(), civilisationId))
				continue;
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, city.getBlockX(), city.getBlockZ());
			endpoints.add(new BlockPos(city.getBlockX(), y, city.getBlockZ()));
		}

		// 3) Roads also run toward the four cardinal extremes of physically
		// controlled territory, providing a practical connection from the command
		// post network to the country's borders.
		endpoints.addAll(countryBorderAnchors(level, civilisationId, home));

		Map<String, BlockPos> unique = new LinkedHashMap<>();
		for (BlockPos endpoint : endpoints) {
			if (endpoint == null)
				continue;
			String key = endpoint.getX() + ":" + endpoint.getZ();
			unique.putIfAbsent(key, endpoint);
		}
		List<BlockPos> result = new ArrayList<>(unique.values());
		result.sort(Comparator
				.comparingDouble(pos -> (pos.getX() - home.getBlockX()) * (double) (pos.getX() - home.getBlockX())
						+ (pos.getZ() - home.getBlockZ()) * (double) (pos.getZ() - home.getBlockZ())));
		return result;
	}

	private BlockPos zoneRoadEntrance(ServerLevel level, City home, WorkZone zone) {
		int centreX = (zone.minX() + zone.maxX()) / 2;
		int centreZ = (zone.minZ() + zone.maxZ()) / 2;
		int dx = centreX - home.getBlockX();
		int dz = centreZ - home.getBlockZ();
		int x = centreX;
		int z = centreZ;
		if (Math.abs(dx) >= Math.abs(dz))
			x = dx >= 0 ? zone.minX() - 1 : zone.maxX() + 1;
		else
			z = dz >= 0 ? zone.minZ() - 1 : zone.maxZ() + 1;
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		return new BlockPos(x, y, z);
	}

	private boolean isInsideReservedWorkZone(String civilisationId, int x, int z) {
		// Lifetime reservations are physical, not merely national: a road builder
		// from any country also avoids placing blocks inside another living worker's
		// farm/factory parcel.
		for (WorkerRecord worker : workers.values()) {
			if (worker.hasFarmerZone && x >= worker.farmerZoneMinX && x <= worker.farmerZoneMaxX
					&& z >= worker.farmerZoneMinZ && z <= worker.farmerZoneMaxZ)
				return true;
			if (worker.hasFactoryZone && x >= worker.factoryZoneMinX && x <= worker.factoryZoneMaxX
					&& z >= worker.factoryZoneMinZ && z <= worker.factoryZoneMaxZ)
				return true;
		}
		return false;
	}

	private List<BlockPos> countryBorderAnchors(ServerLevel level, String civilisationId, City home) {
		long northKey = Long.MIN_VALUE, southKey = Long.MIN_VALUE, eastKey = Long.MIN_VALUE, westKey = Long.MIN_VALUE;
		int north = Integer.MIN_VALUE, south = Integer.MAX_VALUE, east = Integer.MIN_VALUE, west = Integer.MAX_VALUE;

		for (Providence providence : DataManager.getProvidences().values()) {
			if (providence == null || !providence.isEstablished())
				continue;
			for (Map.Entry<Long, String> entry : providence.getTerritoryControllerMap().entrySet()) {
				if (!Objects.equals(civilisationId, entry.getValue()))
					continue;
				long key = entry.getKey();
				int chunkX = (int) (key >> 32);
				int chunkZ = (int) key;
				if (chunkZ > north) {
					north = chunkZ;
					northKey = key;
				}
				if (chunkZ < south) {
					south = chunkZ;
					southKey = key;
				}
				if (chunkX > east) {
					east = chunkX;
					eastKey = key;
				}
				if (chunkX < west) {
					west = chunkX;
					westKey = key;
				}
			}
		}

		List<BlockPos> result = new ArrayList<>();
		for (long key : new long[] { northKey, southKey, eastKey, westKey }) {
			if (key == Long.MIN_VALUE)
				continue;
			int chunkX = (int) (key >> 32);
			int chunkZ = (int) key;
			int x = chunkX * WorldMapTracker.CHUNK_SIZE + WorldMapTracker.CHUNK_SIZE / 2;
			int z = chunkZ * WorldMapTracker.CHUNK_SIZE + WorldMapTracker.CHUNK_SIZE / 2;
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			result.add(new BlockPos(x, y, z));
		}
		return result;
	}

	/** 0 farm, 1 forestry, 2 mine, 3 factory district. */
	private BlockPos districtAnchor(ServerLevel level, City home, int route) {
		int x = home.getBlockX();
		int z = home.getBlockZ();
		switch (Math.floorMod(route, 4)) {
		case 0 -> x += FARM_ZONE_OFFSET;
		case 1 -> z -= FORESTRY_ZONE_OFFSET;
		case 2 -> x -= MINE_ZONE_OFFSET;
		case 3 -> z += FACTORY_ZONE_OFFSET;
		default -> {
		}
		}
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		return new BlockPos(x, y, z);
	}

	private int ensureMinerLaneIndex(WorkerRecord record) {
		if (record == null)
			return 0;
		if (record.mineLaneIndex >= 0)
			return record.mineLaneIndex;

		Set<Integer> used = new HashSet<>();
		for (WorkerRecord other : workers.values()) {
			if (other == record || !Objects.equals(record.homeCityId, other.homeCityId))
				continue;
			if (other.mineLaneIndex >= 0)
				used.add(other.mineLaneIndex);
		}

		// During v10->v11 migration, prefer this miner's former ordinal within its
		// own home city's miners so the existing first-sixteen strip lane is preserved
		// whenever possible. New miners then take the next genuinely free private lane.
		List<WorkerRecord> homeMiners = workers.values().stream()
				.filter(other -> Objects.equals(record.homeCityId, other.homeCityId))
				.filter(other -> parseJob(other.job) == VillagerJob.MINER)
				.sorted(Comparator.comparingInt(other -> other.assignmentIndex)).toList();
		int preferred = Math.max(0, homeMiners.indexOf(record));
		int lane = preferred;
		if (used.contains(lane)) {
			lane = 0;
			while (used.contains(lane))
				lane++;
		}
		record.mineLaneIndex = lane;
		dirty = true;
		return lane;
	}

	private int jobOrdinal(WorkerRecord record, VillagerJob job) {
		List<WorkerRecord> sameJob = workers.values().stream()
				.filter(other -> Objects.equals(record.civilisationId, other.civilisationId))
				.filter(other -> parseJob(other.job) == job)
				.sorted(Comparator.comparingInt(other -> other.assignmentIndex)).toList();
		for (int i = 0; i < sameJob.size(); i++)
			if (sameJob.get(i) == record)
				return i;
		return Math.max(0, record.assignmentIndex);
	}

	private int physicalJobCount(String civilisationId, VillagerJob job) {
		int count = 0;
		for (WorkerRecord worker : workers.values()) {
			if (Objects.equals(civilisationId, worker.civilisationId) && parseJob(worker.job) == job)
				count++;
		}
		return Math.max(1, count);
	}

	private void tickFactoryBuilder(ServerLevel level, Civilisation civilisation, City home, PathfinderMob villager,
			WorkerRecord record) {
		WorkZone zone = ensureFactoryZone(level, home, record);
		int zoneCentreX = (zone.minX() + zone.maxX()) / 2;
		int zoneCentreZ = (zone.minZ() + zone.maxZ()) / 2;
		if (!isChunkLoaded(level, zoneCentreX, zoneCentreZ)) {
			clearMoveTarget(record);
			return;
		}
		BlockPos origin = factoryOriginForZone(level, zone, record);
		ProvidenceSystem.claimConstructionArea(civilisation.getId(), origin.getX(), origin.getZ());
		int groundY = origin.getY();
		List<BlockPos> fullTemplate = factoryTemplate(origin.getX(), groundY, origin.getZ());
		List<BlockPos> woodTemplate = woodFactoryTemplate(origin.getX(), groundY, origin.getZ());
		BlockPos craftingSite = new BlockPos(origin.getX() + 1, groundY + 1, origin.getZ() + 1);
		BlockPos furnaceSite = new BlockPos(origin.getX() + 3, groundY + 1, origin.getZ() + 1);
		BlockPos blastSite = new BlockPos(origin.getX() + 3, groundY + 1, origin.getZ() + 2);
		BlockPos coreSite = factoryCoreSite(origin);
		String factoryType = normalisedFactoryType(record.factoryTypeId);

		// Deliver anything already manufactured before evaluating the structure. A
		// broken core disables future production but must not delete a tool/product
		// the worker was already physically carrying.
		if (record.factoryProduct != null && depositFactoryProduct(level, civilisation.getId(), villager, record))
			return;

		// v6.38.8 introduces an explicit barrel core. Existing full factories from
		// older saves are migrated once by installing the core at their known site;
		// after that, removing/burning that block is a real factory-destruction event.
		if (record.factoryBuilt && !record.factoryCoreInitialised) {
			BlockState coreState = level.getBlockState(coreSite);
			if (!coreState.is(Blocks.BARREL)) {
				if (!coreState.isAir() && factoryBlockCanBeCleared(level, coreSite, coreState)) {
					level.destroyBlock(coreSite, false);
				}
				if (level.getBlockState(coreSite).isAir()) {
					level.setBlockAndUpdate(coreSite, Blocks.BARREL.defaultBlockState());
				}
			}
			record.factoryCoreInitialised = level.getBlockState(coreSite).is(Blocks.BARREL);
			dirty = true;
		}

		if ((record.factoryBuilt || record.woodenFactoryBuilt) && record.factoryCoreInitialised
				&& !level.getBlockState(coreSite).is(Blocks.BARREL)) {
			destroyFactoryAfterCoreLoss(level, civilisation, record, fullTemplate, craftingSite, furnaceSite,
					blastSite);
			return;
		}

		if (!record.factoryBuilt && !record.woodenFactoryBuilt) {
			if (factoryIsComplete(level, fullTemplate, groundY, craftingSite, furnaceSite, blastSite, coreSite,
					factoryType)) {
				record.factoryBuilt = true;
				record.factoryCoreInitialised = true;
				record.factorySequence = Math.max(1, record.factorySequence);
				dirty = true;
			} else if (woodFactoryIsComplete(level, woodTemplate, craftingSite, coreSite)) {
				record.woodenFactoryBuilt = true;
				record.factoryCoreInitialised = true;
				dirty = true;
			}
		}

		// The worker owns this factory zone until death. Clearing is deliberately
		// limited to the compact workshop footprint and one-block working margin.
		String clearKey = record.civilisationId + ":factory-zone:" + record.factoryZoneIndex;
		if (!clearKey.equals(record.clearedFactoryKey) && !record.factoryBuilt && !record.woodenFactoryBuilt) {
			if (clearFactoryZone(level, villager, record, zone, origin, fullTemplate))
				return;
			record.clearedFactoryKey = clearKey;
			finishLocalTaskMovement(villager, record);
			dirty = true;
		}

		// First factory tier: no stone at all. Eleven-ish logs are enough to make
		// the flammable plank floor/walls, crafting table and barrel core. This
		// workshop can make tools/bread/torches immediately, but has no furnace.
		if (!record.factoryBuilt && !record.woodenFactoryBuilt) {
			if (fetchWorkMaterial(level, civilisation.getId(), villager, record, ResourceType.WOOD,
					WOOD_FACTORY_TOTAL_WOOD_COST, WOOD_FACTORY_TOTAL_WOOD_COST))
				return;
			if (!near(villager, origin, 4.8)) {
				moveTo(level, villager, origin, record);
				return;
			}
			if (!consumeWorkMaterial(record, ResourceType.WOOD, WOOD_FACTORY_TOTAL_WOOD_COST))
				return;
			buildWoodFactoryInstantly(level, woodTemplate, craftingSite, coreSite);
			record.woodenFactoryBuilt = true;
			record.factoryCoreInitialised = true;
			record.buildProgress = 0;
			villager.swing(InteractionHand.MAIN_HAND);
			successfulWork(record, 5.0);
			updateCarriedDisplay(villager, record);
			finishLocalTaskMovement(villager, record);
			dirty = true;
			return;
		}

		// Fire can consume ordinary plank blocks without necessarily taking the core
		// first. A damaged wooden workshop becomes unavailable until its builder
		// repairs/rebuilds the wooden shell.
		if (record.woodenFactoryBuilt && !record.factoryBuilt
				&& !woodFactoryIsComplete(level, woodTemplate, craftingSite, coreSite)) {
			record.woodenFactoryBuilt = false;
			record.clearedFactoryKey = clearKey;
			dirty = true;
			return;
		}

		if (record.woodenFactoryBuilt && !record.factoryBuilt) {
			// Tool demand wins over upgrading the building: the entire point of the
			// wooden tier is to bootstrap productive stone/iron tools immediately.
			ToolDemand urgentTool = nextToolDemand(level, civilisation.getId());
			if (urgentTool != null && craftFactoryTool(level, civilisation, villager, record, craftingSite, blastSite,
					false, urgentTool)) {
				return;
			}

			// Once enough real stone and additional wall wood exist, upgrade the same
			// site to the durable cobblestone-floor factory with a furnace. If the
			// materials are not yet available, keep operating the wooden workshop.
			int storedStone = countStoredMaterialUnits(level, civilisation.getId(), ResourceType.STONE);
			int storedWood = countStoredMaterialUnits(level, civilisation.getId(), ResourceType.WOOD);
			boolean maySmelt = ResearchSystem.factoryTypeAvailable(civilisation,
					normalisedFactoryType(record.factoryTypeId))
					&& !"wooden_factory".equals(normalisedFactoryType(record.factoryTypeId));
			if (maySmelt && storedStone >= FACTORY_TOTAL_STONE_COST && storedWood >= FACTORY_UPGRADE_WOOD_COST) {
				if (fetchWorkMaterial(level, civilisation.getId(), villager, record, ResourceType.STONE,
						FACTORY_TOTAL_STONE_COST, FACTORY_TOTAL_STONE_COST))
					return;
				if (fetchWorkMaterial(level, civilisation.getId(), villager, record, ResourceType.WOOD,
						FACTORY_UPGRADE_WOOD_COST, FACTORY_UPGRADE_WOOD_COST))
					return;
				if (!near(villager, origin, 4.8)) {
					moveTo(level, villager, origin, record);
					return;
				}
				if (!consumeWorkMaterial(record, ResourceType.STONE, FACTORY_TOTAL_STONE_COST))
					return;
				if (!consumeWorkMaterial(record, ResourceType.WOOD, FACTORY_UPGRADE_WOOD_COST)) {
					addWorkMaterial(record, ResourceType.STONE, FACTORY_TOTAL_STONE_COST);
					return;
				}
				buildFactoryInstantly(level, fullTemplate, groundY, craftingSite, furnaceSite, blastSite, coreSite,
						factoryType);
				civilisation.registerPhysicalFactory();
				record.factoryBuilt = true;
				record.woodenFactoryBuilt = false;
				record.factoryCoreInitialised = true;
				record.factorySequence = Math.max(1, record.factorySequence);
				villager.swing(InteractionHand.MAIN_HAND);
				successfulWork(record, 8.0);
				updateCarriedDisplay(villager, record);
				finishLocalTaskMovement(villager, record);
				dirty = true;
				return;
			}

			tickFactoryProduction(level, civilisation, home, villager, record, craftingSite, furnaceSite, blastSite,
					false, false);
			return;
		}

		if (record.factoryBuilt && factoryIsComplete(level, fullTemplate, groundY, craftingSite, furnaceSite, blastSite,
				coreSite, factoryType)) {
			boolean furnaceEnabled = !"wooden_factory".equals(factoryType)
					&& ResearchSystem.factoryTypeAvailable(civilisation, factoryType);
			boolean blastEnabled = factoryRequiresBlastStation(factoryType)
					&& level.getBlockState(blastSite).is(Blocks.BLAST_FURNACE)
					&& ResearchSystem.factoryTypeAvailable(civilisation, factoryType);
			tickFactoryProduction(level, civilisation, home, villager, record, craftingSite, furnaceSite, blastSite,
					furnaceEnabled, blastEnabled);
			return;
		}

		// A full factory whose core survived but whose shell/furnace was damaged is
		// repaired in place. Core loss is handled above and is the only event that
		// actually destroys/de-registers the factory.
		if (record.factoryBuilt) {
			int stoneNeeded = FACTORY_TOTAL_STONE_COST;
			int woodNeeded = FACTORY_TOTAL_WOOD_COST;
			if (fetchWorkMaterial(level, civilisation.getId(), villager, record, ResourceType.STONE, stoneNeeded,
					stoneNeeded))
				return;
			if (fetchWorkMaterial(level, civilisation.getId(), villager, record, ResourceType.WOOD, woodNeeded,
					woodNeeded))
				return;
			if (!near(villager, origin, 4.8)) {
				moveTo(level, villager, origin, record);
				return;
			}
			if (!consumeWorkMaterial(record, ResourceType.STONE, stoneNeeded))
				return;
			if (!consumeWorkMaterial(record, ResourceType.WOOD, woodNeeded)) {
				addWorkMaterial(record, ResourceType.STONE, stoneNeeded);
				return;
			}
			buildFactoryInstantly(level, fullTemplate, groundY, craftingSite, furnaceSite, blastSite, coreSite,
					factoryType);
			record.factoryCoreInitialised = true;
			villager.swing(InteractionHand.MAIN_HAND);
			successfulWork(record, 4.0);
			updateCarriedDisplay(villager, record);
			clearMoveTarget(record);
			dirty = true;
		}
	}

	private WorkZone ensureFactoryZone(ServerLevel level, City home, WorkerRecord record) {
		if (!record.hasFactoryZone) {
			WorkZone designated = claimDesignatedWorkZone(record, "FACTORY");
			if (designated != null)
				return designated;
			int index = nextFreeZoneIndex(record.civilisationId, false);
			while (true) {
				int column = Math.floorMod(index, FACTORY_WORK_ZONE_COLUMNS);
				int row = Math.max(0, index / FACTORY_WORK_ZONE_COLUMNS);
				int centreX = home.getBlockX() + (column - 1) * FACTORY_WORK_ZONE_SPACING_X;
				int centreZ = home.getBlockZ() + FACTORY_ZONE_OFFSET + row * FACTORY_WORK_ZONE_SPACING_Z;
				int minX = centreX - FACTORY_WORK_ZONE_WIDTH / 2;
				int maxX = minX + FACTORY_WORK_ZONE_WIDTH - 1;
				int minZ = centreZ - FACTORY_WORK_ZONE_DEPTH / 2;
				int maxZ = minZ + FACTORY_WORK_ZONE_DEPTH - 1;
				if (!zoneOverlapsAnotherWorker(record, minX, maxX, minZ, maxZ)) {
					record.factoryZoneMinX = minX;
					record.factoryZoneMaxX = maxX;
					record.factoryZoneMinZ = minZ;
					record.factoryZoneMaxZ = maxZ;
					record.factoryZoneIndex = index;
					record.hasFactoryZone = true;
					record.factoryGroundInitialised = false;
					record.factoryGroundY = 0;
					registerAutomaticWorkZone(record, "FACTORY", record.factoryZoneMinX, record.factoryZoneMaxX,
							record.factoryZoneMinZ, record.factoryZoneMaxZ);
					dirty = true;
					break;
				}
				index++;
			}
		}
		return new WorkZone(record.factoryZoneMinX, record.factoryZoneMaxX, record.factoryZoneMinZ,
				record.factoryZoneMaxZ);
	}

	private WorkZone claimDesignatedWorkZone(WorkerRecord record, String type) {
		if (record == null || record.uuid == null || type == null)
			return null;
		cleanupDesignatedZoneAssignments();
		List<DesignatedWorkZoneRecord> candidates = designatedWorkZones.values().stream()
				.filter(zone -> zone != null && Objects.equals(record.civilisationId, zone.civilisationId)
						&& type.equals(zone.type)
						&& (zone.assignedWorkerUuid == null || zone.assignedWorkerUuid.equals(record.uuid)))
				.sorted(Comparator.comparing(zone -> String.valueOf(zone.id))).toList();
		for (DesignatedWorkZoneRecord zone : candidates) {
			if (zone.assignedWorkerUuid != null && !zone.assignedWorkerUuid.equals(record.uuid))
				continue;
			zone.assignedWorkerUuid = record.uuid;
			if ("FARM".equals(type)) {
				record.farmerZoneMinX = zone.minX;
				record.farmerZoneMaxX = zone.maxX;
				record.farmerZoneMinZ = zone.minZ;
				record.farmerZoneMaxZ = zone.maxZ;
				record.farmerZoneIndex = -1;
				record.hasFarmerZone = true;
				record.farmerDesignatedZoneId = zone.id;
				record.farmerWaterKnown = false;
				record.farmerNoWorkCells = 0;
				record.farmerWaitingForCrops = false;
				record.lastFarmerCropGrowthCheckTick = 0L;
				record.lastFarmWaterCheckTick = 0L;
			} else {
				record.factoryZoneMinX = zone.minX;
				record.factoryZoneMaxX = zone.maxX;
				record.factoryZoneMinZ = zone.minZ;
				record.factoryZoneMaxZ = zone.maxZ;
				record.factoryZoneIndex = -1;
				record.hasFactoryZone = true;
				record.factoryGroundInitialised = false;
				record.factoryGroundY = 0;
				record.factoryDesignatedZoneId = zone.id;
				record.factoryTypeId = normalisedFactoryType(zone.factoryTypeId);
			}
			dirty = true;
			return new WorkZone(zone.minX, zone.maxX, zone.minZ, zone.maxZ);
		}
		return null;
	}

	private void releaseDesignatedZonesForWorker(WorkerRecord record) {
		if (record == null || record.uuid == null)
			return;
		for (DesignatedWorkZoneRecord zone : designatedWorkZones.values()) {
			if (zone != null && Objects.equals(zone.assignedWorkerUuid, record.uuid)) {
				zone.assignedWorkerUuid = null;
				dirty = true;
			}
		}
		if (record.farmerDesignatedZoneId != null) {
			record.hasFarmerZone = false;
			record.farmerDesignatedZoneId = null;
			record.farmerNoWorkCells = 0;
			record.farmerWaitingForCrops = false;
			record.lastFarmerCropGrowthCheckTick = 0L;
		}
		if (record.factoryDesignatedZoneId != null) {
			record.hasFactoryZone = false;
			record.factoryDesignatedZoneId = null;
			record.factoryBuilt = false;
			record.woodenFactoryBuilt = false;
			record.factoryCoreInitialised = false;
			record.factoryGroundInitialised = false;
			record.factoryGroundY = 0;
			record.clearedFactoryKey = null;
		}
	}

	private boolean zoneOverlapsAnotherWorker(WorkerRecord owner, int minX, int maxX, int minZ, int maxZ) {
		for (DesignatedWorkZoneRecord zone : designatedWorkZones.values()) {
			if (zone == null || !Objects.equals(owner.civilisationId, zone.civilisationId))
				continue;
			// The owner's own already-claimed designated district is intentionally the
			// same rectangle; this method is used only while looking for an automatic
			// fallback, so every designated rectangle remains reserved here.
			if (rectanglesOverlap(minX, maxX, minZ, maxZ, zone.minX, zone.maxX, zone.minZ, zone.maxZ))
				return true;
		}
		for (WorkerRecord other : workers.values()) {
			if (other == owner)
				continue;
			if (other.hasFarmerZone && rectanglesOverlap(minX, maxX, minZ, maxZ, other.farmerZoneMinX,
					other.farmerZoneMaxX, other.farmerZoneMinZ, other.farmerZoneMaxZ))
				return true;
			if (other.hasFactoryZone && rectanglesOverlap(minX, maxX, minZ, maxZ, other.factoryZoneMinX,
					other.factoryZoneMaxX, other.factoryZoneMinZ, other.factoryZoneMaxZ))
				return true;
		}
		return false;
	}

	private boolean rectanglesOverlap(int minX, int maxX, int minZ, int maxZ, int otherMinX, int otherMaxX,
			int otherMinZ, int otherMaxZ) {
		return minX <= otherMaxX && maxX >= otherMinX && minZ <= otherMaxZ && maxZ >= otherMinZ;
	}

	private int nextFreeZoneIndex(String civilisationId, boolean farmerZone) {
		Set<Integer> used = new HashSet<>();
		for (WorkerRecord other : workers.values()) {
			if (!Objects.equals(civilisationId, other.civilisationId))
				continue;
			if (farmerZone && other.hasFarmerZone)
				used.add(Math.max(0, other.farmerZoneIndex));
			if (!farmerZone && other.hasFactoryZone)
				used.add(Math.max(0, other.factoryZoneIndex));
		}
		int candidate = 0;
		while (used.contains(candidate))
			candidate++;
		return candidate;
	}

	private BlockPos factoryOriginForZone(ServerLevel level, WorkZone zone, WorkerRecord record) {
		int x = zone.minX() + Math.max(0, (zone.maxX() - zone.minX() + 1 - FACTORY_BUILD_WIDTH) / 2);
		int z = zone.minZ() + Math.max(0, (zone.maxZ() - zone.minZ() + 1 - FACTORY_BUILD_DEPTH) / 2);

		// Never derive the factory floor from the live heightmap after construction.
		// The factory itself raises that heightmap, which previously made the next
		// work cycle see its roof as "ground" and build another workshop above it.
		if (record != null && record.factoryGroundInitialised) {
			return new BlockPos(x, record.factoryGroundY, z);
		}

		int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		int recoveredGround = Integer.MIN_VALUE;

		// Old saves do not have factoryGroundY. Recover an existing workshop from its
		// fixed internal stations, preferring the lowest matching core/station so an
		// already-stacked factory collapses back to its original ground level.
		int scanTop = surfaceY + 2;
		int scanBottom = surfaceY - 64;
		int[][] stations = { { x + 2, z + 2, 1 }, // barrel core
				{ x + 1, z + 1, 2 }, // crafting table
				{ x + 3, z + 1, 3 } // furnace
		};
		for (int y = scanTop; y >= scanBottom; y--) {
			for (int[] station : stations) {
				BlockState state = level.getBlockState(new BlockPos(station[0], y, station[1]));
				boolean matches = station[2] == 1 ? state.is(Blocks.BARREL)
						: station[2] == 2 ? state.is(Blocks.CRAFTING_TABLE) : state.is(Blocks.FURNACE);
				if (matches)
					recoveredGround = Math.min(recoveredGround == Integer.MIN_VALUE ? y - 1 : recoveredGround, y - 1);
			}
		}

		int groundY = recoveredGround == Integer.MIN_VALUE ? surfaceY : recoveredGround;
		if (record != null) {
			record.factoryGroundY = groundY;
			record.factoryGroundInitialised = true;
			dirty = true;
		}
		return new BlockPos(x, groundY, z);
	}

	private boolean factoryIsComplete(ServerLevel level, List<BlockPos> template, int groundY, BlockPos craftingSite,
			BlockPos furnaceSite, BlockPos blastSite, BlockPos coreSite, String factoryType) {
		for (BlockPos pos : template) {
			boolean floor = pos.getY() == groundY;
			if (!factoryTemplateBlockCorrect(level.getBlockState(pos), floor))
				return false;
		}
		boolean basicStations = level.getBlockState(craftingSite).is(Blocks.CRAFTING_TABLE)
				&& level.getBlockState(furnaceSite).is(Blocks.FURNACE)
				&& level.getBlockState(coreSite).is(Blocks.BARREL);
		if (!basicStations)
			return false;
		return !factoryRequiresBlastStation(factoryType) || level.getBlockState(blastSite).is(Blocks.BLAST_FURNACE);
	}

	private void buildFactoryInstantly(ServerLevel level, List<BlockPos> template, int groundY, BlockPos craftingSite,
			BlockPos furnaceSite, BlockPos blastSite, BlockPos coreSite, String factoryType) {
		for (BlockPos pos : template) {
			boolean floor = pos.getY() == groundY;
			level.setBlockAndUpdate(pos,
					floor ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState());
		}
		level.setBlockAndUpdate(craftingSite, Blocks.CRAFTING_TABLE.defaultBlockState());
		level.setBlockAndUpdate(furnaceSite, Blocks.FURNACE.defaultBlockState());
		if (factoryRequiresBlastStation(factoryType)) {
			level.setBlockAndUpdate(blastSite, Blocks.BLAST_FURNACE.defaultBlockState());
		} else if (level.getBlockState(blastSite).is(Blocks.BLAST_FURNACE)) {
			level.setBlockAndUpdate(blastSite, Blocks.AIR.defaultBlockState());
		}
		level.setBlockAndUpdate(coreSite, Blocks.BARREL.defaultBlockState());
	}

	private boolean factoryRequiresBlastStation(String factoryTypeId) {
		String id = normalisedFactoryType(factoryTypeId);
		FactoryType type = DataManager.getFactoryTypes().get(id);
		return type != null && (type.hasCapability("BLASTING") || type.hasCapability("STEEL"));
	}

	private BlockPos factoryCoreSite(BlockPos origin) {
		return new BlockPos(origin.getX() + 2, origin.getY() + 1, origin.getZ() + 2);
	}

	private List<BlockPos> woodFactoryTemplate(int x0, int y0, int z0) {
		List<BlockPos> blocks = new ArrayList<>();
		for (int x = 0; x < FACTORY_BUILD_WIDTH; x++) {
			for (int z = 0; z < FACTORY_BUILD_DEPTH; z++) {
				blocks.add(new BlockPos(x0 + x, y0, z0 + z));
			}
		}
		for (int x = 0; x < FACTORY_BUILD_WIDTH; x++) {
			blocks.add(new BlockPos(x0 + x, y0 + 1, z0));
			blocks.add(new BlockPos(x0 + x, y0 + 1, z0 + FACTORY_BUILD_DEPTH - 1));
		}
		for (int z = 1; z < FACTORY_BUILD_DEPTH - 1; z++) {
			blocks.add(new BlockPos(x0, y0 + 1, z0 + z));
			blocks.add(new BlockPos(x0 + FACTORY_BUILD_WIDTH - 1, y0 + 1, z0 + z));
		}
		return blocks;
	}

	private boolean woodFactoryIsComplete(ServerLevel level, List<BlockPos> template, BlockPos craftingSite,
			BlockPos coreSite) {
		for (BlockPos pos : template) {
			if (!level.getBlockState(pos).is(Blocks.OAK_PLANKS))
				return false;
		}
		return level.getBlockState(craftingSite).is(Blocks.CRAFTING_TABLE)
				&& level.getBlockState(coreSite).is(Blocks.BARREL);
	}

	private void buildWoodFactoryInstantly(ServerLevel level, List<BlockPos> template, BlockPos craftingSite,
			BlockPos coreSite) {
		for (BlockPos pos : template) {
			level.setBlockAndUpdate(pos, Blocks.OAK_PLANKS.defaultBlockState());
		}
		level.setBlockAndUpdate(craftingSite, Blocks.CRAFTING_TABLE.defaultBlockState());
		level.setBlockAndUpdate(coreSite, Blocks.BARREL.defaultBlockState());
	}

	private void destroyFactoryAfterCoreLoss(ServerLevel level, Civilisation civilisation, WorkerRecord record,
			List<BlockPos> fullTemplate, BlockPos craftingSite, BlockPos furnaceSite, BlockPos blastSite) {
		// Core loss makes the factory cease to exist functionally. Remove only the
		// known factory blocks from its own footprint, leaving unrelated player blocks
		// untouched. A replacement builder can later reconstruct the site.
		for (BlockPos pos : fullTemplate) {
			BlockState state = level.getBlockState(pos);
			if (state.is(Blocks.OAK_PLANKS) || state.is(Blocks.COBBLESTONE)) {
				level.destroyBlock(pos, false);
			}
		}
		if (level.getBlockState(craftingSite).is(Blocks.CRAFTING_TABLE))
			level.destroyBlock(craftingSite, false);
		if (level.getBlockState(furnaceSite).is(Blocks.FURNACE))
			level.destroyBlock(furnaceSite, false);
		if (level.getBlockState(blastSite).is(Blocks.BLAST_FURNACE))
			level.destroyBlock(blastSite, false);
		if (record.factoryBuilt && civilisation != null)
			civilisation.addFactories(-1);
		record.factoryBuilt = false;
		record.woodenFactoryBuilt = false;
		record.factoryCoreInitialised = false;
		record.factorySequence = 0;
		record.clearedFactoryKey = null;
		clearMoveTarget(record);
		dirty = true;
	}

	/**
	 * A factory builder establishes the crafting-table core before waiting for the
	 * complete shell. This breaks the early-game circular dependency: experienced
	 * hand miners can receive wooden/stone pickaxes while the workshop building is
	 * still being completed from the faster mining those tools enable.
	 */
	private boolean ensureBootstrapWorkshop(ServerLevel level, Civilisation civilisation, PathfinderMob villager,
			WorkerRecord record, BlockPos craftingSite) {
		if (level.getBlockState(craftingSite).is(Blocks.CRAFTING_TABLE))
			return false;
		BlockState existing = level.getBlockState(craftingSite);
		if (!existing.isAir()) {
			if (!factoryBlockCanBeCleared(level, craftingSite, existing))
				return true;
			if (!near(villager, craftingSite, 4.8)) {
				moveTo(level, villager, craftingSite, record);
				return true;
			}
			recoverFactoryClearedBlock(record, existing);
			level.destroyBlock(craftingSite, false);
			villager.swing(InteractionHand.MAIN_HAND);
			dirty = true;
			return true;
		}
		if (fetchWorkMaterial(level, civilisation.getId(), villager, record, ResourceType.WOOD,
				FACTORY_CRAFTING_TABLE_WOOD_COST, FACTORY_CRAFTING_TABLE_WOOD_COST))
			return true;
		if (!near(villager, craftingSite, 4.8)) {
			moveTo(level, villager, craftingSite, record);
			return true;
		}
		if (!consumeWorkMaterial(record, ResourceType.WOOD, FACTORY_CRAFTING_TABLE_WOOD_COST))
			return true;
		level.setBlockAndUpdate(craftingSite, Blocks.CRAFTING_TABLE.defaultBlockState());
		villager.swing(InteractionHand.MAIN_HAND);
		clearMoveTarget(record);
		dirty = true;
		return true;
	}

	private int remainingFactoryStoneCost(ServerLevel level, BlockPos furnaceSite) {
		return FACTORY_TOTAL_STONE_COST
				- (level.getBlockState(furnaceSite).is(Blocks.FURNACE) ? FACTORY_FURNACE_STONE_COST : 0);
	}

	private int remainingFactoryWoodCost(ServerLevel level, BlockPos craftingSite) {
		return FACTORY_TOTAL_WOOD_COST
				- (level.getBlockState(craftingSite).is(Blocks.CRAFTING_TABLE) ? FACTORY_CRAFTING_TABLE_WOOD_COST : 0);
	}

	private void tickFactoryProduction(ServerLevel level, Civilisation civilisation, City home, PathfinderMob villager,
			WorkerRecord record, BlockPos craftingSite, BlockPos furnaceSite, BlockPos blastSite,
			boolean furnaceAvailable, boolean blastAvailable) {
		if (depositFactoryProduct(level, civilisation.getId(), villager, record))
			return;
		if (tryQueuedFactoryProduction(level, civilisation, villager, record, craftingSite, furnaceSite, blastSite,
				furnaceAvailable, blastAvailable))
			return;

		// If every depot chest is packed, production first expands storage. The
		// factory worker withdraws real wood, crafts a chest, walks back to the
		// command-post depot and places it there.
		if (record.expandingDepot || depotNeedsExpansion(level, civilisation.getId())) {
			record.expandingDepot = true;
			if (fetchWorkMaterial(level, civilisation.getId(), villager, record, ResourceType.WOOD, 8, 8))
				return;
			if (!near(villager, craftingSite, 3.0)) {
				moveTo(level, villager, craftingSite, record);
				return;
			}
			if (!consumeWorkMaterial(record, ResourceType.WOOD, 8))
				return;
			record.factoryProduct = "CHEST";
			record.factoryProductCount = 1;
			updateCarriedDisplay(villager, record);
			villager.swing(InteractionHand.MAIN_HAND);
			finishLocalTaskMovement(villager, record);
			dirty = true;
			return;
		}

		ToolDemand toolDemand = nextToolDemand(level, civilisation.getId());
		if (toolDemand != null) {
			if (craftFactoryTool(level, civilisation, villager, record, craftingSite, blastSite, blastAvailable,
					toolDemand))
				return;
		}

		// Keep a practical lighting stockpile for administrators. One coal/charcoal
		// plus one plank-equivalent wood unit produces four real torches, matching
		// the vanilla recipe's output. The finished torches are returned to a
		// command-post chest before an administrator can use them.
		int administrators = (int) workers.values().stream()
				.filter(worker -> Objects.equals(civilisation.getId(), worker.civilisationId))
				.filter(worker -> parseJob(worker.job) == VillagerJob.ADMINISTRATOR).count();
		int torchTarget = Math.max(16, administrators * 16);
		int torches = countSpecificItems(level, civilisation.getId(), Items.TORCH)
				+ pendingFactoryProductCount(civilisation.getId(), Items.TORCH);
		if (torches < torchTarget && countResourceItems(level, civilisation.getId(), ResourceType.COAL) > 0
				&& countResourceItems(level, civilisation.getId(), ResourceType.WOOD) > 0) {
			if (fetchWorkMaterial(level, civilisation.getId(), villager, record, ResourceType.COAL, 1, 4))
				return;
			if (fetchWorkMaterial(level, civilisation.getId(), villager, record, ResourceType.WOOD, 1, 8))
				return;
			if (!near(villager, craftingSite, 3.0)) {
				moveTo(level, villager, craftingSite, record);
				return;
			}
			if (!consumeWorkMaterial(record, ResourceType.COAL, 1))
				return;
			if (!consumeWorkMaterial(record, ResourceType.WOOD, 1)) {
				addWorkMaterial(record, ResourceType.COAL, 1);
				return;
			}
			record.factoryProduct = "TORCH";
			record.factoryProductCount = FACTORY_TORCH_BATCH;
			updateCarriedDisplay(villager, record);
			villager.swing(InteractionHand.MAIN_HAND);
			finishLocalTaskMovement(villager, record);
			successfulWork(record, 0.8);
			dirty = true;
			return;
		}

		// Food security is the next standing priority. Three wheat from the command
		// post becomes one real bread item at the crafting table.
		int wheat = countResourceItems(level, civilisation.getId(), ResourceType.FOOD);
		int bread = countSpecificItems(level, civilisation.getId(), Items.BREAD);
		if (wheat >= 3 && bread < Math.max(8, civilisation.getPopulation() * 2)) {
			if (fetchWorkMaterial(level, civilisation.getId(), villager, record, ResourceType.FOOD, 3, 12))
				return;
			if (!near(villager, craftingSite, 3.0)) {
				moveTo(level, villager, craftingSite, record);
				return;
			}
			if (!consumeWorkMaterial(record, ResourceType.FOOD, 3))
				return;
			record.factoryProduct = "BREAD";
			record.factoryProductCount = 1;
			updateCarriedDisplay(villager, record);
			villager.swing(InteractionHand.MAIN_HAND);
			finishLocalTaskMovement(villager, record);
			successfulWork(record, 0.8);
			dirty = true;
			return;
		}

		// When fuel is scarce, use the furnace to turn stored wood into charcoal.
		int fuel = countResourceItems(level, civilisation.getId(), ResourceType.COAL);
		if (furnaceAvailable && fuel < 32 && countResourceItems(level, civilisation.getId(), ResourceType.WOOD) > 8) {
			if (fetchWorkMaterial(level, civilisation.getId(), villager, record, ResourceType.WOOD, 4, 16))
				return;
			if (!near(villager, furnaceSite, 3.0)) {
				moveTo(level, villager, furnaceSite, record);
				return;
			}
			if (!consumeWorkMaterial(record, ResourceType.WOOD, 4))
				return;
			record.factoryProduct = "CHARCOAL";
			record.factoryProductCount = 1;
			updateCarriedDisplay(villager, record);
			villager.swing(InteractionHand.MAIN_HAND);
			finishLocalTaskMovement(villager, record);
			successfulWork(record, 0.8);
			dirty = true;
			return;
		}

		// No urgent conversion is required. Work at a workstation instead of
		// wandering back into town and clumping with other villagers.
		BlockPos station = furnaceAvailable && (record.assignmentIndex & 1) != 0 ? furnaceSite : craftingSite;
		if (!near(villager, station, 2.4)) {
			moveTo(level, villager, station, record);
		} else {
			finishLocalTaskMovement(villager, record);
		}
	}

	/**
	 * Executes the player's industrial queue. Each available factory worker pulls
	 * the earliest order compatible with its factory type, so parallel factories
	 * automatically share production without per-building micromanagement.
	 */
	private boolean tryQueuedFactoryProduction(ServerLevel level, Civilisation civilisation, PathfinderMob villager,
			WorkerRecord record, BlockPos craftingSite, BlockPos furnaceSite, BlockPos blastSite,
			boolean furnaceAvailable, boolean blastAvailable) {
		String factoryType = normalisedFactoryType(record.factoryTypeId);
		List<FactoryRecipe> recipes = ResearchSystem.recipesForFactoryTypes(civilisation, List.of(factoryType));
		List<String> ids = recipes.stream().map(FactoryRecipe::getId).toList();
		ProductionOrder order = selectProductionOrder(civilisation, ids);
		if (order == null)
			return false;
		FactoryRecipe recipe = DataManager.getFactoryRecipes().get(order.getRecipeId());
		if (recipe == null)
			return false;

		String capability = recipe.getCapability() == null ? "" : recipe.getCapability();
		if ("SMELTING".equalsIgnoreCase(capability) && !furnaceAvailable)
			return false;
		if (("BLASTING".equalsIgnoreCase(capability) || "STEEL".equalsIgnoreCase(capability)) && !blastAvailable)
			return false;

		ResourceType first = null, second = null;
		int firstCount = 0, secondCount = 0;
		String id = recipe.getId();
		switch (id) {
		case "chest" -> {
			first = ResourceType.WOOD;
			firstCount = 8;
		}
		case "bread" -> {
			first = ResourceType.FOOD;
			firstCount = 3;
		}
		case "torch" -> {
			first = ResourceType.COAL;
			firstCount = 1;
			second = ResourceType.WOOD;
			secondCount = 1;
		}
		case "wooden_pickaxe" -> {
			first = ResourceType.WOOD;
			firstCount = 4;
		}
		case "stone_pickaxe" -> {
			first = ResourceType.STONE;
			firstCount = 3;
			second = ResourceType.WOOD;
			secondCount = 1;
		}
		case "iron_pickaxe" -> {
			first = ResourceType.IRON;
			firstCount = 3;
			second = ResourceType.WOOD;
			secondCount = 1;
		}
		case "charcoal" -> {
			first = ResourceType.WOOD;
			firstCount = 4;
		}
		case "drenough_coke" -> {
			first = ResourceType.COAL;
			firstCount = 1;
		}
		case "drenough_steel_ingot" -> {
			first = ResourceType.IRON;
			firstCount = 1;
			second = ResourceType.COAL;
			secondCount = 1;
		}
		default -> {
			return false;
		}
		}

		if (fetchWorkMaterial(level, civilisation.getId(), villager, record, first, firstCount,
				Math.max(8, firstCount)))
			return true;
		if (second != null && fetchWorkMaterial(level, civilisation.getId(), villager, record, second, secondCount,
				Math.max(8, secondCount)))
			return true;

		BlockPos station = ("BLASTING".equalsIgnoreCase(capability) || "STEEL".equalsIgnoreCase(capability)) ? blastSite
				: "SMELTING".equalsIgnoreCase(capability) ? furnaceSite : craftingSite;
		if (station == null)
			return false;
		if (!near(villager, station, 3.0)) {
			moveTo(level, villager, station, record);
			return true;
		}
		if (!consumeWorkMaterial(record, first, firstCount))
			return true;
		if (second != null && !consumeWorkMaterial(record, second, secondCount)) {
			addWorkMaterial(record, first, firstCount);
			return true;
		}

		String token = switch (id) {
		case "chest" -> "CHEST_ITEM";
		case "bread" -> "BREAD";
		case "torch" -> "TORCH";
		case "wooden_pickaxe" -> factoryProductToken(Items.WOODEN_PICKAXE);
		case "stone_pickaxe" -> factoryProductToken(Items.STONE_PICKAXE);
		case "iron_pickaxe" -> factoryProductToken(Items.IRON_PICKAXE);
		case "charcoal" -> "CHARCOAL";
		case "drenough_coke" -> factoryProductToken(MinecraftItemRegistry.item("drenough_forging:coke"));
		case "drenough_steel_ingot" -> factoryProductToken(MinecraftItemRegistry.item("drenough_forging:steel_ingot"));
		default -> null;
		};
		if (token == null)
			return false;

		record.factoryProduct = token;
		record.factoryProductCount = Math.max(1, recipe.getOutputCount());
		civilisation.recordProduction(order.getSerial(), record.factoryProductCount);
		updateCarriedDisplay(villager, record);
		villager.swing(InteractionHand.MAIN_HAND);
		finishLocalTaskMovement(villager, record);
		successfulWork(record, 1.0);
		dirty = true;
		return true;
	}

	/**
	 * Prefer a compatible order whose inputs are already physically present in the
	 * national depot ledger. If none is ready, preserve player queue order and let
	 * the first compatible order wait for incoming supply.
	 */
	private ProductionOrder selectProductionOrder(Civilisation civilisation, List<String> allowedRecipeIds) {
		if (civilisation == null || allowedRecipeIds == null || allowedRecipeIds.isEmpty())
			return null;
		ProductionOrder fallback = null;
		for (ProductionOrder order : civilisation.getProductionQueue()) {
			if (order == null || order.isPaused() || order.isComplete()
					|| !allowedRecipeIds.contains(order.getRecipeId()))
				continue;
			if (fallback == null)
				fallback = order;
			if (queuedRecipeMaterialsAvailable(civilisation, order.getRecipeId()))
				return order;
		}
		return fallback;
	}

	private boolean queuedRecipeMaterialsAvailable(Civilisation civilisation, String recipeId) {
		if (civilisation == null || recipeId == null)
			return false;
		return switch (recipeId) {
		case "chest" -> civilisation.getResource(ResourceType.WOOD) >= 8;
		case "bread" -> civilisation.getResource(ResourceType.FOOD) >= 3;
		case "torch" ->
			civilisation.getResource(ResourceType.COAL) >= 1 && civilisation.getResource(ResourceType.WOOD) >= 1;
		case "wooden_pickaxe" -> civilisation.getResource(ResourceType.WOOD) >= 4;
		case "stone_pickaxe" ->
			civilisation.getResource(ResourceType.STONE) >= 3 && civilisation.getResource(ResourceType.WOOD) >= 1;
		case "iron_pickaxe" ->
			civilisation.getResource(ResourceType.IRON) >= 3 && civilisation.getResource(ResourceType.WOOD) >= 1;
		case "charcoal" -> civilisation.getResource(ResourceType.WOOD) >= 4;
		case "drenough_coke" -> civilisation.getResource(ResourceType.COAL) >= 1;
		case "drenough_steel_ingot" ->
			civilisation.getResource(ResourceType.IRON) >= 1 && civilisation.getResource(ResourceType.COAL) >= 1;
		default -> false;
		};
	}

	private boolean craftFactoryTool(ServerLevel level, Civilisation civilisation, PathfinderMob villager,
			WorkerRecord record, BlockPos craftingSite, BlockPos blastSite, boolean blastAvailable, ToolDemand demand) {
		int headCost = toolHeadCost(demand.job());
		if (headCost <= 0)
			return false;

		String factoryType = normalisedFactoryType(record.factoryTypeId);
		FactoryType type = DataManager.getFactoryTypes().get(factoryType);

		// Wooden workshops may make stone tools, but metal tiers require a durable
		// factory. Steel additionally requires a factory explicitly capable of STEEL.
		if ("wooden_factory".equals(factoryType) && demand.tier().ordinal() >= WorkerToolTier.IRON.ordinal())
			return false;
		if (demand.tier() == WorkerToolTier.STEEL && (type == null || !type.hasCapability("STEEL")))
			return false;

		if (demand.tier() == WorkerToolTier.WOOD) {
			int wood = headCost + 1;
			if (fetchWorkMaterial(level, civilisation.getId(), villager, record, ResourceType.WOOD, wood,
					Math.max(wood, 16)))
				return true;
		} else if (demand.tier() == WorkerToolTier.STEEL) {
			Item steelIngot = MinecraftItemRegistry.item("drenough_forging:steel_ingot");
			if (steelIngot == null)
				return false;

			// Steel tools consume the actual optional-mod ingot. If there is not
			// enough in storage, a blast-capable steel factory first makes a batch
			// from iron + coal and physically deposits those real ingots.
			if (!WorkerToolTier.STEEL.name().equals(record.preparedToolTier)) {
				int stocked = countSpecificItems(level, civilisation.getId(), steelIngot)
						+ pendingFactoryProductCount(civilisation.getId(), steelIngot);
				if (stocked < headCost) {
					if (blastAvailable
							&& produceSteelIngotBatch(level, civilisation, villager, record, blastSite, headCost))
						return true;
					return false;
				}

				BlockPos chest = nearestDepotChestWithSpecificItem(level, civilisation.getId(), steelIngot,
						villager.blockPosition(), record);
				if (chest == null)
					return false;
				if (walkToDepotChest(level, villager, chest, record, 3.2))
					return true;
				BlockEntity blockEntity = level.getBlockEntity(chest);
				if (!(blockEntity instanceof Container container))
					return true;
				if (removeSpecificItems(container, List.of(steelIngot), headCost) < headCost)
					return true;

				// The ingots are now physically withdrawn. preparedToolTier is the
				// durable transaction marker while the worker walks back to the bench.
				record.preparedToolTier = WorkerToolTier.STEEL.name();
				villager.swing(InteractionHand.MAIN_HAND);
				clearMoveTarget(record);
				dirty = true;
				return true;
			}

			if (fetchWorkMaterial(level, civilisation.getId(), villager, record, ResourceType.WOOD, 1, 8))
				return true;
		} else {
			ResourceType headMaterial = switch (demand.tier()) {
			case STONE -> ResourceType.STONE;
			case IRON -> ResourceType.IRON;
			case DIAMOND -> ResourceType.DIAMOND;
			default -> null;
			};
			if (headMaterial == null)
				return false;
			if (fetchWorkMaterial(level, civilisation.getId(), villager, record, headMaterial, headCost,
					Math.max(headCost, 8)))
				return true;
			if (fetchWorkMaterial(level, civilisation.getId(), villager, record, ResourceType.WOOD, 1, 8))
				return true;
		}

		if (!near(villager, craftingSite, 3.0)) {
			moveTo(level, villager, craftingSite, record);
			return true;
		}

		if (demand.tier() == WorkerToolTier.WOOD) {
			if (!consumeWorkMaterial(record, ResourceType.WOOD, headCost + 1))
				return false;
		} else if (demand.tier() == WorkerToolTier.STEEL) {
			if (!WorkerToolTier.STEEL.name().equals(record.preparedToolTier))
				return false;
			if (!consumeWorkMaterial(record, ResourceType.WOOD, 1))
				return false;
			record.preparedToolTier = null;
		} else {
			ResourceType headMaterial = switch (demand.tier()) {
			case STONE -> ResourceType.STONE;
			case IRON -> ResourceType.IRON;
			case DIAMOND -> ResourceType.DIAMOND;
			default -> null;
			};
			if (headMaterial == null || !consumeWorkMaterial(record, headMaterial, headCost))
				return false;
			if (!consumeWorkMaterial(record, ResourceType.WOOD, 1)) {
				addWorkMaterial(record, headMaterial, headCost);
				return false;
			}
		}

		Item product = toolFor(demand.job(), demand.tier());
		String token = factoryProductToken(product);
		if (token == null)
			return false;
		record.factoryProduct = token;
		record.factoryProductCount = 1;
		updateCarriedDisplay(villager, record);
		villager.swing(InteractionHand.MAIN_HAND);
		finishLocalTaskMovement(villager, record);
		successfulWork(record, 1.5);
		dirty = true;
		return true;
	}

	/**
	 * Closed optional-mod production path used by automatic tool provisioning. This
	 * never invokes Dr. Enough Forging code: the output is resolved solely by the
	 * registry id "drenough_forging:steel_ingot".
	 */
	private boolean produceSteelIngotBatch(ServerLevel level, Civilisation civilisation, PathfinderMob villager,
			WorkerRecord record, BlockPos blastSite, int desiredCount) {
		Item steelIngot = MinecraftItemRegistry.item("drenough_forging:steel_ingot");
		if (steelIngot == null || desiredCount <= 0 || blastSite == null
				|| !level.getBlockState(blastSite).is(Blocks.BLAST_FURNACE))
			return false;

		int batch = Math.max(1, desiredCount);
		if (fetchWorkMaterial(level, civilisation.getId(), villager, record, ResourceType.IRON, batch,
				Math.max(batch, 8)))
			return true;
		if (fetchWorkMaterial(level, civilisation.getId(), villager, record, ResourceType.COAL, batch,
				Math.max(batch, 8)))
			return true;

		if (!near(villager, blastSite, 3.0)) {
			moveTo(level, villager, blastSite, record);
			return true;
		}
		if (!consumeWorkMaterial(record, ResourceType.IRON, batch))
			return false;
		if (!consumeWorkMaterial(record, ResourceType.COAL, batch)) {
			addWorkMaterial(record, ResourceType.IRON, batch);
			return false;
		}

		String token = factoryProductToken(steelIngot);
		if (token == null) {
			addWorkMaterial(record, ResourceType.IRON, batch);
			addWorkMaterial(record, ResourceType.COAL, batch);
			return false;
		}
		record.factoryProduct = token;
		record.factoryProductCount = batch;
		updateCarriedDisplay(villager, record);
		villager.swing(InteractionHand.MAIN_HAND);
		finishLocalTaskMovement(villager, record);
		successfulWork(record, 1.8);
		dirty = true;
		return true;
	}

	private ToolDemand nextToolDemand(ServerLevel level, String civilisationId) {
		Civilisation civilisation = DataManager.getCivilisations().get(civilisationId);
		if (civilisation == null)
			return null;

		List<WorkerRecord> candidates = workers.values().stream()
				.filter(worker -> Objects.equals(civilisationId, worker.civilisationId))
				.filter(worker -> supportsTool(parseJob(worker.job))).filter(worker -> {
					VillagerJob job = parseJob(worker.job);
					WorkerToolTier current = toolTier(worker);
					WorkerToolTier next = nextToolTier(civilisation, job, current);
					// Wooden tools are self-crafted directly from depot wood and
					// must never consume factory production capacity.
					return next != current && next != WorkerToolTier.WOOD
							&& worker.workExperience >= next.getRequiredExperience();
				})
				.sorted(Comparator.comparingInt((WorkerRecord worker) -> toolTier(worker).ordinal())
						.thenComparingInt(worker -> toolDemandPriority(parseJob(worker.job)))
						.thenComparingInt(worker -> worker.assignmentIndex))
				.toList();

		for (WorkerRecord worker : candidates) {
			VillagerJob job = parseJob(worker.job);
			WorkerToolTier next = nextToolTier(civilisation, job, toolTier(worker));
			if (next == toolTier(worker) || !ResearchSystem.canUseToolTier(civilisation, job, next))
				continue;
			Item item = toolFor(job, next);
			if (item == null)
				continue;
			if (countSpecificItems(level, civilisationId, item) > 0
					|| pendingFactoryProductCount(civilisationId, item) > 0)
				continue;
			return new ToolDemand(job, next);
		}
		return null;
	}

	private int pendingFactoryProductCount(String civilisationId, Item item) {
		String token = factoryProductToken(item);
		if (token == null)
			return 0;
		int count = 0;
		for (WorkerRecord worker : workers.values()) {
			if (!Objects.equals(civilisationId, worker.civilisationId))
				continue;
			if (token.equals(worker.factoryProduct))
				count += Math.max(0, worker.factoryProductCount);
		}
		return count;
	}

	private int toolDemandPriority(VillagerJob job) {
		// Economic bootstrap comes first: faster miners unlock stone/iron and faster
		// lumberjacks unlock the wood needed by every later workshop. Soldiers still
		// receive upgrades, but they no longer consume the very first tool output
		// while the settlement is trying to establish its production base.
		return switch (job) {
		case MINER -> 0;
		case LUMBERJACK -> 1;
		case FACTORY_BUILDER -> 2;
		case FARMER -> 3;
		case ROAD_BUILDER -> 4;
		case SOLDIER -> 5;
		default -> 10;
		};
	}

	private boolean clearFactoryZone(ServerLevel level, PathfinderMob villager, WorkerRecord record, WorkZone zone,
			BlockPos origin, List<BlockPos> template) {
		Set<BlockPos> shell = new HashSet<>(template);
		int groundY = origin.getY();
		List<BlockPos> candidates = new ArrayList<>();

		// Clear only the compact factory footprint plus a one-block working margin.
		// The district itself remains exclusive, but there is no reason to remove
		// every tree/block from the unused edges before the first workshop can run.
		int clearMinX = Math.max(zone.minX(), origin.getX() - 1);
		int clearMaxX = Math.min(zone.maxX(), origin.getX() + FACTORY_BUILD_WIDTH);
		int clearMinZ = Math.max(zone.minZ(), origin.getZ() - 1);
		int clearMaxZ = Math.min(zone.maxZ(), origin.getZ() + FACTORY_BUILD_DEPTH);
		for (int x = clearMinX; x <= clearMaxX; x++) {
			for (int z = clearMinZ; z <= clearMaxZ; z++) {
				for (int y = groundY; y <= groundY + FACTORY_WALL_HEIGHT + 2; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = level.getBlockState(pos);
					if (state.isAir())
						continue;
					if (shell.contains(pos) && state.is(Blocks.OAK_PLANKS))
						continue;
					if (state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.FURNACE))
						continue;

					boolean shellBlock = shell.contains(pos);
					if (!shellBlock && !isWorkerBreakableObstacle(state))
						continue;
					if (!factoryBlockCanBeCleared(level, pos, state))
						continue;
					candidates.add(pos);
				}
			}
		}

		if (candidates.isEmpty())
			return false;
		candidates.sort(Comparator
				.comparingDouble(pos -> villager.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5)));
		BlockPos nearest = candidates.get(0);
		if (!near(villager, nearest, 4.8)) {
			moveTo(level, villager, nearest, record);
			return true;
		}

		// Once the builder has reached the site it clears several reachable blocks
		// per work action instead of one. This represents purposeful site preparation
		// and prevents dense leaves/grass from delaying a tiny starter workshop for
		// minutes while still requiring the worker to physically reach the site.
		int cleared = 0;
		for (BlockPos pos : candidates) {
			if (cleared >= 6)
				break;
			if (!near(villager, pos, 4.8))
				continue;
			BlockState state = level.getBlockState(pos);
			if (!factoryBlockCanBeCleared(level, pos, state))
				continue;
			recoverFactoryClearedBlock(record, state);
			if (level.destroyBlock(pos, false)) {
				cleared++;
				successfulWork(record, 0.08);
				dirty = true;
			}
		}
		if (cleared > 0) {
			villager.swing(InteractionHand.MAIN_HAND);
			record.lastObstacleBreakTick = ticks;
			updateCarriedDisplay(villager, record);
		}
		return true;
	}

	private boolean factoryTemplateBlockCorrect(BlockState state, boolean floor) {
		return floor ? state.is(Blocks.COBBLESTONE) : state.is(Blocks.OAK_PLANKS);
	}

	private boolean factoryBlockCanBeCleared(ServerLevel level, BlockPos pos, BlockState state) {
		if (state == null || state.isAir() || state.is(Blocks.BEDROCK))
			return false;
		if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.CRAFTING_TABLE)
				|| state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.BARREL))
			return false;
		return level.getBlockEntity(pos) == null;
	}

	private void recoverFactoryClearedBlock(WorkerRecord record, BlockState state) {
		if (state == null || record == null)
			return;
		if (isLog(state)) {
			addWorkMaterial(record, ResourceType.WOOD, 4);
		} else if (isStone(state)) {
			addWorkMaterial(record, ResourceType.STONE, 1);
		}
	}

	private List<BlockPos> factoryTemplate(int x0, int y0, int z0) {
		List<BlockPos> blocks = new ArrayList<>();
		for (int x = 0; x < FACTORY_BUILD_WIDTH; x++) {
			for (int z = 0; z < FACTORY_BUILD_DEPTH; z++) {
				blocks.add(new BlockPos(x0 + x, y0, z0 + z));
			}
		}
		for (int y = 1; y <= FACTORY_WALL_HEIGHT; y++) {
			for (int x = 0; x < FACTORY_BUILD_WIDTH; x++) {
				blocks.add(new BlockPos(x0 + x, y0 + y, z0));
				blocks.add(new BlockPos(x0 + x, y0 + y, z0 + FACTORY_BUILD_DEPTH - 1));
			}
			for (int z = 1; z < FACTORY_BUILD_DEPTH - 1; z++) {
				blocks.add(new BlockPos(x0, y0 + y, z0 + z));
				blocks.add(new BlockPos(x0 + FACTORY_BUILD_WIDTH - 1, y0 + y, z0 + z));
			}
		}
		return blocks;
	}

	private void tickSoldier(ServerLevel level, Civilisation civilisation, City home, PathfinderMob villager,
			WorkerRecord record) {
		// Physical occupation, like combat, is reported from actual soldier positions.
		if (ticks % 20 == Math.floorMod(record.assignmentIndex, 20)) {
			WarSystem.getInstance().reportArmyPosition(civilisation.getId(), villager.getX(), villager.getZ());
		}

		// Target selection and strategic coordination are the expensive part of army
		// AI. Stagger them across four ticks; vanilla navigation continues following
		// the already-issued route between decisions, and melee against a remembered
		// target is still serviced immediately.
		if (ticks % SOLDIER_DECISION_INTERVAL_TICKS != Math.floorMod(record.assignmentIndex,
				SOLDIER_DECISION_INTERVAL_TICKS)) {
			Entity remembered = entityFromUuid(level, record.combatTargetUuid);
			if (remembered != null && validCombatTarget(civilisation, villager, remembered)
					&& villager.distanceToSqr(remembered) <= SOLDIER_MELEE_RANGE * SOLDIER_MELEE_RANGE) {
				villager.getNavigation().stop();
				clearMoveTarget(record);
				strikeCombatTarget(level, civilisation, villager, record, remembered);
			}
			return;
		}

		boolean automatic = civilisation.isSoldierControlAutomatic();

		// Automatic wartime armies are objective-led. A limited number of soldiers
		// are assigned to a limited number of enemy city command posts, and those
		// objectives outrank ordinary enemy units. Only a nearby enemy-country unit
		// that is physically blocking the assault can pull an assigned soldier away
		// from the beacon. Monsters never supersede an active command-post assault.
		Providence commandTarget = automatic
				? coordinatedWartimeCommandPostTarget(level, civilisation, villager, record)
				: null;
		if (commandTarget != null && commandTarget.getCity() != null) {
			BlockPos commandPost = ProvidenceCommandPostSystem.ensureCommandPost(level, commandTarget);
			BlockPos strategicPosition = commandPost;
			if (strategicPosition == null) {
				City targetCity = commandTarget.getCity();
				strategicPosition = formationTarget(level, targetCity.getBlockX(), targetCity.getBlockZ(),
						record.assignmentIndex);
			}

			if (commandPost != null && near(villager, commandPost, 3.8)) {
				villager.getNavigation().stop();
				clearMoveTarget(record);
				record.combatTargetUuid = null;
				villager.swing(InteractionHand.MAIN_HAND);
				if (ProvidenceCommandPostSystem.breakAndCaptureBySoldier(level, commandTarget, civilisation.getId())) {
					successfulWork(record, 3.0);
					record.commandPostTargetProvidenceId = null;
				}
				return;
			}

			Entity blocker = resolveCommandPostBlockingTarget(level, civilisation, villager, record);
			if (blocker != null) {
				double distanceSq = villager.distanceToSqr(blocker);
				faceTarget(villager, blocker);
				if (distanceSq > SOLDIER_MELEE_RANGE * SOLDIER_MELEE_RANGE) {
					moveTo(level, villager, blocker.blockPosition(), record);
				} else {
					villager.getNavigation().stop();
					clearMoveTarget(record);
					strikeCombatTarget(level, civilisation, villager, record, blocker);
				}
				return;
			}

			record.combatTargetUuid = null;
			if (strategicPosition != null) {
				moveTo(level, villager, soldierTravelWaypoint(level, villager, strategicPosition, record), record);
				return;
			}
		} else if (!automatic) {
			record.commandPostTargetProvidenceId = null;
		}

		// Soldiers without a command-post assignment fight enemy-country units first.
		// For a player-created country in Army Auto, wartime strategic movement also
		// outranks monster defence: overflow troops pursue materialised enemy soldiers
		// and otherwise advance around the selected enemy cities instead of returning
		// to their peacetime garrison posts.
		Entity combatTarget = resolveSoldierCombatTarget(level, civilisation, home, villager, record);
		if (combatTarget != null) {
			double distanceSq = villager.distanceToSqr(combatTarget);
			faceTarget(villager, combatTarget);
			if (distanceSq > SOLDIER_MELEE_RANGE * SOLDIER_MELEE_RANGE) {
				moveTo(level, villager, combatTarget.blockPosition(), record);
			} else {
				villager.getNavigation().stop();
				clearMoveTarget(record);
				strikeCombatTarget(level, civilisation, villager, record, combatTarget);
			}
			return;
		}

		record.combatTargetUuid = null;

		if (automatic && civilisation.isPlayerCreated() && hasWartimeStrategicTarget(level, civilisation.getId())) {
			PathfinderMob strategicEnemy = chooseStrategicEnemySoldier(level, civilisation, villager, record);
			if (strategicEnemy != null) {
				record.combatTargetUuid = strategicEnemy.getUUID().toString();
				double distanceSq = villager.distanceToSqr(strategicEnemy);
				faceTarget(villager, strategicEnemy);
				if (distanceSq > SOLDIER_MELEE_RANGE * SOLDIER_MELEE_RANGE) {
					moveTo(level, villager,
							soldierTravelWaypoint(level, villager, strategicEnemy.blockPosition(), record), record);
				} else {
					villager.getNavigation().stop();
					clearMoveTarget(record);
					strikeCombatTarget(level, civilisation, villager, record, strategicEnemy);
				}
				return;
			}

			BlockPos advance = playerAutomaticWartimeAdvanceTarget(level, civilisation, villager, record);
			if (advance != null) {
				if (near(villager, advance, 2.8)) {
					clearMoveTarget(record);
				} else {
					moveTo(level, villager, soldierTravelWaypoint(level, villager, advance, record), record);
				}
				return;
			}
		}

		// Manual armies never receive automatic strategic assignments, but a soldier
		// that the player physically brings to an enemy beacon can still capture it.
		if (!automatic) {
			Providence nearbyCommandTarget = ProvidenceSystem.wartimeCommandPostTarget(civilisation.getId(),
					villager.getX(), villager.getZ());
			if (nearbyCommandTarget != null) {
				BlockPos commandPost = ProvidenceCommandPostSystem.ensureCommandPost(level, nearbyCommandTarget);
				if (commandPost != null && near(villager, commandPost, 3.8)) {
					villager.getNavigation().stop();
					finishLocalTaskMovement(villager, record);
					villager.swing(InteractionHand.MAIN_HAND);
					if (ProvidenceCommandPostSystem.breakAndCaptureBySoldier(level, nearbyCommandTarget,
							civilisation.getId())) {
						successfulWork(record, 3.0);
					}
					return;
				}
			}
		}

		BlockPos destination = null;
		if (!automatic && civilisation.hasSoldierOrder()) {
			destination = formationTarget(level, civilisation.getSoldierOrderBlockX(),
					civilisation.getSoldierOrderBlockZ(), record.assignmentIndex);
		}

		if (destination != null) {
			if (near(villager, destination, 2.8)) {
				successfulWork(record, 0.15);
				clearMoveTarget(record);
			} else {
				moveTo(level, villager, soldierTravelWaypoint(level, villager, destination, record), record);
			}
			return;
		}

		// With no combat, assault, or manual movement order to service, soldiers
		// salvage nearby bones/bone meal. Bones are converted using the vanilla
		// 1 bone -> 3 bone meal crafting ratio, then carried physically back to the
		// command-post depot for farmers to use.
		if (record.boneMeal < BONE_MEAL_CARRY_BATCH && collectNearestSoldierBoneMeal(level, villager, record)) {
			return;
		}
		if (record.boneMeal > 0) {
			record.forceDeposit = true;
			if (returnToDepot(level, civilisation, home, villager, record))
				return;
			if (record.boneMeal <= 0)
				record.forceDeposit = false;
			return;
		}

		// Permanent garrison posts are spread between the town centre and the four
		// work districts. Soldiers no longer all orbit the same small circle.
		BlockPos guard = garrisonPost(level, home, record.assignmentIndex);
		if (near(villager, guard, 2.4)) {
			clearMoveTarget(record);
			if (ticks % 80 == Math.floorMod(record.assignmentIndex, 80)) {
				successfulWork(record, 0.05);
			}
		} else {
			moveTo(level, villager, guard, record);
		}
	}

	private boolean collectNearestSoldierBoneMeal(ServerLevel level, PathfinderMob soldier, WorkerRecord record) {
		ItemEntity drop = retainedSoldierBoneDrop(level, record);
		if (drop == null) {
			AABB box = soldier.getBoundingBox().inflate(SOLDIER_BONE_SEARCH_RADIUS, 6.0, SOLDIER_BONE_SEARCH_RADIUS);
			double bestDistance = Double.POSITIVE_INFINITY;
			for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box, entity -> {
				if (!entity.isAlive())
					return false;
				Item type = entity.getItem().getItem();
				return type == Items.BONE || type == Items.BONE_MEAL;
			})) {
				double distance = soldier.distanceToSqr(item);
				if (distance < bestDistance) {
					bestDistance = distance;
					drop = item;
				}
			}
			if (drop != null)
				record.boneMealDropTargetUuid = drop.getUUID().toString();
		}
		if (drop == null) {
			if ("bone_meal_drop".equals(record.targetKind)) {
				clearTarget(record);
				finishLocalTaskMovement(soldier, record);
			}
			return false;
		}

		if (soldier.distanceToSqr(drop) > 3.2 * 3.2) {
			setTarget(record, "bone_meal_drop", drop.blockPosition());
			BlockPos post = workDropInteractionPost(level, soldier, record, drop.blockPosition());
			moveTo(level, soldier, post == null ? drop.blockPosition() : post, record);
			return true;
		}

		ItemStack stack = drop.getItem();
		Item item = stack.getItem();
		int room = Math.max(0, BONE_MEAL_CARRY_BATCH - record.boneMeal);
		if (room <= 0)
			return false;
		int perItem = item == Items.BONE ? 3 : 1;
		if (room < perItem)
			return false;
		int maxItems = room / perItem;
		int takeItems = Math.min(stack.getCount(), maxItems);
		int gained = takeItems * perItem;
		if (gained <= 0)
			return false;

		record.boneMeal += gained;
		record.lastPickupTick = ticks;
		stack.shrink(takeItems);
		if (stack.isEmpty())
			drop.discard();
		else
			drop.setItem(stack);
		record.boneMealDropTargetUuid = null;
		soldier.swing(InteractionHand.MAIN_HAND);
		updateCarriedDisplay(soldier, record);
		clearTarget(record);
		finishLocalTaskMovement(soldier, record);
		dirty = true;
		return true;
	}

	private ItemEntity retainedSoldierBoneDrop(ServerLevel level, WorkerRecord record) {
		if (level == null || record == null || record.boneMealDropTargetUuid == null)
			return null;
		Entity entity = entityFromUuid(level, record.boneMealDropTargetUuid);
		if (!(entity instanceof ItemEntity item) || !item.isAlive()) {
			record.boneMealDropTargetUuid = null;
			return null;
		}
		Item type = item.getItem().getItem();
		if (type != Items.BONE && type != Items.BONE_MEAL) {
			record.boneMealDropTargetUuid = null;
			return null;
		}
		return item;
	}

	private Entity resolveCommandPostBlockingTarget(ServerLevel level, Civilisation civilisation, PathfinderMob soldier,
			WorkerRecord record) {
		CombatTarget enemy = chooseHostileSoldier(level, civilisation, soldier, record,
				SOLDIER_COMMAND_POST_BLOCKER_RANGE);
		if (enemy == null) {
			record.combatTargetUuid = null;
			return null;
		}
		record.combatTargetUuid = enemy.villager().getUUID().toString();
		return enemy.villager();
	}

	/**
	 * Assigns automatic soldiers to a small number of enemy city objectives.
	 * Existing valid assignments are retained to prevent oscillation. New soldiers
	 * first fill an already active objective, then open another city only when the
	 * existing one reaches its per-command-post cap and the country is still below
	 * the simultaneous city-objective limit. Excess soldiers remain free for field
	 * combat.
	 */
	private Providence coordinatedWartimeCommandPostTarget(ServerLevel level, Civilisation civilisation,
			PathfinderMob soldier, WorkerRecord soldierRecord) {
		if (civilisation == null || soldierRecord == null)
			return null;
		String civilisationId = civilisation.getId();

		Providence existing = providenceById(soldierRecord.commandPostTargetProvidenceId);
		boolean existingValid = validEnemyCommandPostTarget(civilisationId, existing);
		if (!existingValid)
			soldierRecord.commandPostTargetProvidenceId = null;

		Map<String, Integer> assignmentCounts = new HashMap<>();
		Set<String> activeObjectives = new HashSet<>();
		for (WorkerRecord worker : workers.values()) {
			if (worker == soldierRecord || parseJob(worker.job) != VillagerJob.SOLDIER
					|| !Objects.equals(civilisationId, worker.civilisationId)
					|| worker.commandPostTargetProvidenceId == null)
				continue;
			Providence target = providenceById(worker.commandPostTargetProvidenceId);
			if (!validEnemyCommandPostTarget(civilisationId, target)
					|| !(entityFromUuid(level, worker.uuid) instanceof PathfinderMob))
				continue;
			activeObjectives.add(target.getId());
			assignmentCounts.merge(target.getId(), 1, Integer::sum);
		}

		// Retain a previous objective only while doing so still respects the hard
		// live-soldier cap. This matters when chunks unload and later rematerialise:
		// a returning soldier cannot silently turn a six-soldier assault into seven.
		if (existingValid && assignmentCounts.getOrDefault(existing.getId(), 0) < SOLDIER_MAX_COMMAND_POST_ASSIGNEES) {
			return existing;
		}
		if (existingValid)
			soldierRecord.commandPostTargetProvidenceId = null;

		Providence best = null;
		double bestDistance = Double.POSITIVE_INFINITY;

		// Reinforce already selected objectives before widening the offensive.
		for (String providenceId : activeObjectives) {
			if (assignmentCounts.getOrDefault(providenceId, 0) >= SOLDIER_MAX_COMMAND_POST_ASSIGNEES)
				continue;
			Providence candidate = providenceById(providenceId);
			double distance = commandPostDistanceSquared(candidate, soldier);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = candidate;
			}
		}

		// Only open another city front when every current objective is full, or when
		// there is no active objective yet. This deliberately keeps wars concentrated.
		if (best == null && activeObjectives.size() < SOLDIER_MAX_SIMULTANEOUS_CITY_OBJECTIVES) {
			for (Providence candidate : DataManager.getProvidences().values()) {
				if (!validEnemyCommandPostTarget(civilisationId, candidate)
						|| activeObjectives.contains(candidate.getId()))
					continue;
				double distance = commandPostDistanceSquared(candidate, soldier);
				if (distance < bestDistance) {
					bestDistance = distance;
					best = candidate;
				}
			}
		}

		if (best != null) {
			soldierRecord.commandPostTargetProvidenceId = best.getId();
			dirty = true;
		}
		return best;
	}

	private Providence providenceById(String providenceId) {
		if (providenceId == null || providenceId.isBlank())
			return null;
		return DataManager.getProvidences().get(providenceId);
	}

	private boolean validEnemyCommandPostTarget(String civilisationId, Providence providence) {
		if (civilisationId == null || providence == null || !providence.isEstablished() || providence.getCity() == null)
			return false;
		String controller = providence.getCity().getControllerId();
		return controller != null && !Objects.equals(civilisationId, controller)
				&& WarSystem.getInstance().areAtWar(civilisationId, controller);
	}

	private double commandPostDistanceSquared(Providence providence, PathfinderMob soldier) {
		if (providence == null || providence.getCity() == null || soldier == null)
			return Double.POSITIVE_INFINITY;
		double dx = providence.getCity().getBlockX() - soldier.getX();
		double dz = providence.getCity().getBlockZ() - soldier.getZ();
		return dx * dx + dz * dz;
	}

	private Entity resolveSoldierCombatTarget(ServerLevel level, Civilisation civilisation, City home,
			PathfinderMob soldier, WorkerRecord record) {
		Entity remembered = entityFromUuid(level, record.combatTargetUuid);
		boolean rememberedValid = remembered != null && validCombatTarget(civilisation, soldier, remembered)
				&& soldier.distanceToSqr(remembered) <= SOLDIER_TARGET_MEMORY_RANGE * SOLDIER_TARGET_MEMORY_RANGE;

		// Wartime country targets always outrank monsters. Keep a remembered enemy
		// worker/soldier if it is still valid, otherwise actively look for another
		// enemy before considering any vanilla hostile mob. This also means a
		// soldier immediately abandons a remembered monster when an enemy country
		// unit enters engagement range.
		if (rememberedValid && remembered instanceof PathfinderMob) {
			return remembered;
		}

		double enemyEngageRange = civilisation.isSoldierControlAutomatic()
				? (civilisation.isPlayerCreated() ? SOLDIER_PLAYER_AUTO_ENGAGE_RANGE : SOLDIER_ENGAGE_RANGE)
				: SOLDIER_MANUAL_ENGAGE_RANGE;
		CombatTarget enemy = chooseHostileWorker(level, civilisation, soldier, record, enemyEngageRange);
		if (enemy != null) {
			record.combatTargetUuid = enemy.villager().getUUID().toString();
			return enemy.villager();
		}

		// A player-created country in Army Auto stays committed to its wartime
		// offensive when there is a strategic target available. In that state even a
		// nearby monster does not drag an overflow soldier back from the front.
		if (civilisation.isPlayerCreated() && civilisation.isSoldierControlAutomatic()
				&& hasWartimeStrategicTarget(level, civilisation.getId())) {
			record.combatTargetUuid = null;
			return null;
		}

		// With no enemy-country unit or wartime advance available, retain an existing
		// monster target rather than needlessly switching between mobs.
		if (rememberedValid && remembered instanceof Monster) {
			return remembered;
		}
		record.combatTargetUuid = null;

		// Monster defence is fallback work. Close monsters are selected first, then
		// the wider settlement/army radius, but only after wartime country targets.
		Monster monster = chooseHostileMonster(level, soldier, record, SOLDIER_URGENT_MONSTER_RANGE);
		if (monster == null) {
			monster = chooseHostileMonster(level, soldier, record, SOLDIER_MONSTER_ENGAGE_RANGE);
		}
		if (monster != null) {
			record.combatTargetUuid = monster.getUUID().toString();
			return monster;
		}
		return null;
	}

	/**
	 * True when Army Auto has an actual enemy-country objective to advance towards.
	 */
	private boolean hasWartimeStrategicTarget(ServerLevel level, String civilisationId) {
		if (civilisationId == null || civilisationId.isBlank())
			return false;
		for (Providence providence : DataManager.getProvidences().values()) {
			if (validEnemyCommandPostTarget(civilisationId, providence))
				return true;
		}
		for (WorkerRecord candidate : workers.values()) {
			if (candidate == null || candidate.uuid == null || parseJob(candidate.job) != VillagerJob.SOLDIER
					|| Objects.equals(civilisationId, candidate.civilisationId)
					|| !WarSystem.getInstance().areAtWar(civilisationId, candidate.civilisationId))
				continue;
			if (entityFromUuid(level, candidate.uuid) instanceof PathfinderMob)
				return true;
		}
		return false;
	}

	/**
	 * Long-range pursuit for player Army Auto overflow troops. At most four
	 * soldiers strategically chase the same materialised enemy soldier; the rest
	 * keep advancing around the selected city fronts, preventing a 20-vs-1 war from
	 * becoming a single moving enemy versus a mostly stationary player garrison.
	 */
	private PathfinderMob chooseStrategicEnemySoldier(ServerLevel level, Civilisation civilisation,
			PathfinderMob soldier, WorkerRecord soldierRecord) {
		PathfinderMob best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		for (WorkerRecord candidate : workers.values()) {
			if (candidate == soldierRecord || candidate.uuid == null || parseJob(candidate.job) != VillagerJob.SOLDIER
					|| Objects.equals(candidate.civilisationId, soldierRecord.civilisationId)
					|| !WarSystem.getInstance().areAtWar(civilisation.getId(), candidate.civilisationId))
				continue;

			Entity entity = entityFromUuid(level, candidate.uuid);
			if (!(entity instanceof PathfinderMob hostile))
				continue;
			int claims = combatClaimCount(candidate.uuid, soldierRecord);
			if (claims >= SOLDIER_PLAYER_STRATEGIC_PURSUERS_PER_ENEMY)
				continue;
			double score = soldier.distanceToSqr(hostile) + claims * 4096.0;
			if (score < bestScore) {
				bestScore = score;
				best = hostile;
			}
		}
		return best;
	}

	/**
	 * Gives unassigned player Auto troops an offensive front instead of a home
	 * garrison. They stage/patrol in a broad ring around only the command-post
	 * objectives already selected by the capped assault system, so this adds
	 * pressure without opening a large number of simultaneous city targets or
	 * violating the six-soldier beacon cap.
	 */
	private BlockPos playerAutomaticWartimeAdvanceTarget(ServerLevel level, Civilisation civilisation,
			PathfinderMob soldier, WorkerRecord record) {
		List<Providence> fronts = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (WorkerRecord worker : workers.values()) {
			if (worker == null || parseJob(worker.job) != VillagerJob.SOLDIER
					|| !Objects.equals(civilisation.getId(), worker.civilisationId)
					|| worker.commandPostTargetProvidenceId == null)
				continue;
			Providence target = providenceById(worker.commandPostTargetProvidenceId);
			if (validEnemyCommandPostTarget(civilisation.getId(), target) && seen.add(target.getId())) {
				fronts.add(target);
			}
		}

		if (fronts.isEmpty()) {
			Providence nearest = ProvidenceSystem.wartimeCommandPostTarget(civilisation.getId(), soldier.getX(),
					soldier.getZ());
			if (nearest != null)
				fronts.add(nearest);
		}
		if (fronts.isEmpty())
			return null;

		fronts.sort(Comparator.comparingDouble(p -> commandPostDistanceSquared(p, soldier)));
		Providence front = fronts.get(Math.floorMod(record.assignmentIndex, fronts.size()));
		if (front.getCity() == null)
			return null;

		// Rotate the support ring slowly. This makes overflow troops continue to patrol
		// and occupy the surrounding wartime chunks after reaching the front rather
		// than
		// freezing at one staging coordinate for the entire war.
		int phase = (int) Math.floorMod(ticks / 200L, SOLDIER_PLAYER_SUPPORT_RING_SLOTS);
		int slot = Math.floorMod(record.assignmentIndex * 5 + phase, SOLDIER_PLAYER_SUPPORT_RING_SLOTS);
		int ring = Math.floorMod(record.assignmentIndex / SOLDIER_PLAYER_SUPPORT_RING_SLOTS, 3);
		double angle = slot * (Math.PI * 2.0 / SOLDIER_PLAYER_SUPPORT_RING_SLOTS);
		int radius = SOLDIER_PLAYER_SUPPORT_RING_BASE_RADIUS + ring * SOLDIER_PLAYER_SUPPORT_RING_STEP;
		int x = front.getCity().getBlockX() + (int) Math.round(Math.cos(angle) * radius);
		int z = front.getCity().getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		return new BlockPos(x, y, z);
	}

	private Entity entityFromUuid(ServerLevel level, String uuidText) {
		if (level == null || uuidText == null || uuidText.isBlank())
			return null;
		if (entityLookupCache.containsKey(uuidText)) {
			Entity cached = entityLookupCache.get(uuidText);
			return cached != null && cached.isAlive() ? cached : null;
		}
		Entity entity;
		try {
			entity = level.getEntity(UUID.fromString(uuidText));
		} catch (IllegalArgumentException ignored) {
			entityLookupCache.put(uuidText, null);
			return null;
		}
		entityLookupCache.put(uuidText, entity);
		return entity != null && entity.isAlive() ? entity : null;
	}

	private boolean validCombatTarget(Civilisation civilisation, PathfinderMob soldier, Entity entity) {
		if (entity == null || !entity.isAlive() || entity == soldier)
			return false;
		if (entity instanceof Monster)
			return true;
		if (!(entity instanceof PathfinderMob))
			return false;
		WorkerRecord targetRecord = workers.get(entity.getUUID().toString());
		return targetRecord != null && !Objects.equals(targetRecord.civilisationId, civilisation.getId())
				&& WarSystem.getInstance().areAtWar(civilisation.getId(), targetRecord.civilisationId);
	}

	private Monster chooseHostileMonster(ServerLevel level, PathfinderMob soldier, WorkerRecord soldierRecord,
			double range) {
		AABB box = soldier.getBoundingBox().inflate(range, 16.0, range);
		Monster best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		for (Monster monster : level.getEntitiesOfClass(Monster.class, box, Monster::isAlive)) {
			double distance = soldier.distanceToSqr(monster);
			int claims = combatClaimCount(monster.getUUID().toString(), soldierRecord);
			// Strongly prefer an unclaimed monster, but allow several soldiers to help
			// if there are fewer threats than defenders.
			double score = distance + claims * 144.0;
			if (score < bestScore) {
				bestScore = score;
				best = monster;
			}
		}
		return best;
	}

	private CombatTarget chooseHostileSoldier(ServerLevel level, Civilisation civilisation, PathfinderMob soldier,
			WorkerRecord soldierRecord, double range) {
		CombatTarget best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		double maxDistanceSq = range * range;
		for (WorkerRecord candidate : workers.values()) {
			if (candidate == soldierRecord || candidate.uuid == null
					|| Objects.equals(candidate.civilisationId, soldierRecord.civilisationId)
					|| parseJob(candidate.job) != VillagerJob.SOLDIER)
				continue;
			if (!WarSystem.getInstance().areAtWar(civilisation.getId(), candidate.civilisationId))
				continue;

			Entity entity = entityFromUuid(level, candidate.uuid);
			if (!(entity instanceof PathfinderMob hostile))
				continue;
			double distanceSq = soldier.distanceToSqr(hostile);
			if (distanceSq > maxDistanceSq)
				continue;
			int claims = combatClaimCount(candidate.uuid, soldierRecord);
			double score = distanceSq + claims * 196.0;
			if (score < bestScore) {
				bestScore = score;
				best = new CombatTarget(candidate, hostile);
			}
		}
		return best;
	}

	private CombatTarget chooseHostileWorker(ServerLevel level, Civilisation civilisation, PathfinderMob soldier,
			WorkerRecord soldierRecord, double range) {
		CombatTarget best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		double maxDistanceSq = range * range;
		for (WorkerRecord candidate : workers.values()) {
			if (candidate == soldierRecord || candidate.uuid == null
					|| Objects.equals(candidate.civilisationId, soldierRecord.civilisationId))
				continue;
			if (!WarSystem.getInstance().areAtWar(civilisation.getId(), candidate.civilisationId))
				continue;

			Entity entity = entityFromUuid(level, candidate.uuid);
			if (!(entity instanceof PathfinderMob hostile))
				continue;
			double distanceSq = soldier.distanceToSqr(hostile);
			if (distanceSq > maxDistanceSq)
				continue;

			boolean enemySoldier = parseJob(candidate.job) == VillagerJob.SOLDIER;
			// Civilians are legitimate wartime targets only at close range. Soldiers
			// actively seek other soldiers first instead of massacring workers while
			// an enemy army is beside them.
			if (!enemySoldier && distanceSq > SOLDIER_MANUAL_ENGAGE_RANGE * SOLDIER_MANUAL_ENGAGE_RANGE)
				continue;
			int claims = combatClaimCount(candidate.uuid, soldierRecord);
			// Player Army Auto uses a hard field-pursuit cap as well as the command-
			// post cap. Four soldiers are enough to hunt one enemy soldier; additional
			// troops keep advancing across the front instead of forming a 20-vs-1 mob.
			if (civilisation.isPlayerCreated() && civilisation.isSoldierControlAutomatic() && enemySoldier
					&& claims >= SOLDIER_PLAYER_STRATEGIC_PURSUERS_PER_ENEMY)
				continue;
			double score = distanceSq + claims * 196.0 - (enemySoldier ? 2_000.0 : 0.0);
			if (score < bestScore) {
				bestScore = score;
				best = new CombatTarget(candidate, hostile);
			}
		}
		return best;
	}

	private int combatClaimCount(String targetUuid, WorkerRecord excluding) {
		if (targetUuid == null)
			return 0;
		int count = 0;
		for (WorkerRecord worker : workers.values()) {
			if (worker == excluding || parseJob(worker.job) != VillagerJob.SOLDIER)
				continue;
			if (targetUuid.equals(worker.combatTargetUuid))
				count++;
		}
		return count;
	}

	private void strikeCombatTarget(ServerLevel level, Civilisation attackerCivilisation, PathfinderMob attacker,
			WorkerRecord attackerRecord, Entity target) {
		if (ticks - attackerRecord.lastAttackTick < SOLDIER_ATTACK_COOLDOWN_TICKS)
			return;
		attackerRecord.lastAttackTick = ticks;
		attacker.swing(InteractionHand.MAIN_HAND);
		faceTarget(attacker, target);

		float damage = soldierDamage(attackerRecord);
		if (target instanceof Monster monster) {
			float remaining = monster.getHealth() - damage;
			applyVisibleKnockback(attacker, monster);
			if (remaining > 0.0f) {
				monster.setHealth(remaining);
			} else {
				monster.setHealth(0.0f);
				monster.discard();
				attackerRecord.combatTargetUuid = null;
				successfulWork(attackerRecord, 1.25);
			}
			return;
		}

		if (!(target instanceof PathfinderMob victim))
			return;
		WorkerRecord victimRecord = workers.get(victim.getUUID().toString());
		if (victimRecord == null
				|| !WarSystem.getInstance().areAtWar(attackerCivilisation.getId(), victimRecord.civilisationId)) {
			attackerRecord.combatTargetUuid = null;
			return;
		}

		float remaining = victim.getHealth() - damage;
		applyVisibleKnockback(attacker, victim);
		if (remaining > 0.0f) {
			victim.setHealth(remaining);
			return;
		}

		boolean victimWasSoldier = parseJob(victimRecord.job) == VillagerJob.SOLDIER;
		String victimCivilisationId = victimRecord.civilisationId;
		BlockPos deathPos = victim.blockPosition();
		victim.setHealth(0.0f);
		registerWorkerCasualty(level, victimRecord, deathPos);
		victim.discard();
		attackerRecord.combatTargetUuid = null;
		WarSystem.getInstance().reportCasualty(attackerRecord.civilisationId, victimCivilisationId, victimWasSoldier);
		successfulWork(attackerRecord, 2.0);
		dirty = true;
	}

	private void faceTarget(PathfinderMob attacker, Entity target) {
		double dx = target.getX() - attacker.getX();
		double dz = target.getZ() - attacker.getZ();
		if (Math.abs(dx) + Math.abs(dz) < 0.0001)
			return;
		float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
		attacker.setYRot(yaw);
	}

	private void applyVisibleKnockback(PathfinderMob attacker, Entity victim) {
		double dx = victim.getX() - attacker.getX();
		double dz = victim.getZ() - attacker.getZ();
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length < 0.001)
			return;
		victim.setDeltaMovement(victim.getDeltaMovement().add(dx / length * 0.18, 0.10, dz / length * 0.18));
	}

	private float soldierDamage(WorkerRecord record) {
		float base = switch (toolTier(record)) {
		case HAND -> 2.0f;
		case WOOD -> 3.5f;
		case STONE -> 5.0f;
		case IRON -> 6.5f;
		case STEEL -> 7.25f;
		case DIAMOND -> 8.0f;
		};
		Civilisation civilisation = record == null ? null : DataManager.getCivilisations().get(record.civilisationId);
		return (float) (base * SocietySystem.militaryMoraleMultiplier(civilisation));
	}

	private BlockPos garrisonPost(ServerLevel level, City home, int assignmentIndex) {
		int group = Math.floorMod(assignmentIndex, 5);
		int rank = Math.floorMod(assignmentIndex / 5, 5);
		int x = home.getBlockX();
		int z = home.getBlockZ();
		switch (group) {
		case 0 -> x += 7 + rank * 2; // centre/east gate
		case 1 -> {
			x += FARM_ZONE_OFFSET;
			z += (rank - 2) * 3;
		}
		case 2 -> {
			z -= FORESTRY_ZONE_OFFSET;
			x += (rank - 2) * 3;
		}
		case 3 -> {
			x -= MINE_ZONE_OFFSET;
			z += (rank - 2) * 3;
		}
		case 4 -> {
			z += FACTORY_ZONE_OFFSET;
			x += (rank - 2) * 3;
		}
		default -> {
		}
		}
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		return new BlockPos(x, y, z);
	}

	private BlockPos formationTarget(ServerLevel level, int centreX, int centreZ, int assignmentIndex) {
		int column = Math.floorMod(assignmentIndex, 5) - 2;
		int row = Math.floorMod(assignmentIndex / 5, 5) - 2;
		int x = centreX + column * 2;
		int z = centreZ + row * 2;
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		return new BlockPos(x, y, z);
	}

	private BlockPos soldierTravelWaypoint(ServerLevel level, PathfinderMob soldier, BlockPos finalTarget,
			WorkerRecord record) {
		double dx = finalTarget.getX() + 0.5 - soldier.getX();
		double dz = finalTarget.getZ() + 0.5 - soldier.getZ();
		double distance = Math.sqrt(dx * dx + dz * dz);
		if (distance <= 24.0)
			return finalTarget;

		// v6.20/v6.21 recomputed a point 20 blocks ahead from the soldier's *current*
		// position every tick. As the soldier moved by fractions of a block the
		// waypoint kept changing, so moveTo() kept rebuilding the path and soldiers
		// could jitter forever. Keep an existing local leg until it is actually
		// reached (or ceases to make progress toward the final target).
		if (record != null && record.hasMoveTarget) {
			BlockPos existing = new BlockPos(record.moveTargetX, record.moveTargetY, record.moveTargetZ);
			double toExisting = horizontalDistanceSquared(soldier.blockPosition(), existing);
			double existingToFinal = horizontalDistanceSquared(existing, finalTarget);
			double soldierToFinal = horizontalDistanceSquared(soldier.blockPosition(), finalTarget);
			if (toExisting > 9.0 && toExisting <= 900.0 && existingToFinal + 16.0 < soldierToFinal) {
				return existing;
			}
		}

		// Create one stable local leg. A new leg is generated only after the previous
		// one is reached/cleared, rather than continuously sliding ahead of the unit.
		int x = floor(soldier.getX() + dx / distance * 20.0);
		int z = floor(soldier.getZ() + dz / distance * 20.0);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		return new BlockPos(x, y, z);
	}

	private void tickCityWorker(ServerLevel level, Civilisation civilisation, City home, PathfinderMob villager,
			WorkerRecord record) {
		VillagerJob job = parseJob(record.job);
		if (job == VillagerJob.ADMINISTRATOR) {
			Providence commandTarget = ProvidenceSystem.administratorCaptureTarget(civilisation.getId(),
					villager.getX(), villager.getZ());
			if (commandTarget != null) {
				BlockPos commandPost = ProvidenceCommandPostSystem.ensureCommandPost(level, commandTarget);
				if (commandPost != null) {
					if (!near(villager, commandPost, 3.2)) {
						moveTo(level, villager, commandPost, record);
						return;
					}
					villager.getNavigation().stop();
					clearMoveTarget(record);
					villager.swing(InteractionHand.MAIN_HAND);
					if (ProvidenceCommandPostSystem.captureByAdministrator(level, commandTarget,
							civilisation.getId())) {
						successfulWork(record, 2.0);
					}
					return;
				}
			}

			// Settlement lighting is a physical administrator job. Torches must
			// already exist in command-post storage; administrators withdraw a small
			// batch, carry it to under-lit surface positions and place the blocks.
			// No torch is ever created by the administrator itself.
			if (tickAdministratorLighting(level, civilisation, home, villager, record))
				return;

			BlockPos chest = assignedDepotChest(level, civilisation.getId(), record.assignmentIndex);
			BlockPos post = chest == null
					? formationTarget(level, home.getBlockX(), home.getBlockZ(), record.assignmentIndex)
					: chest.offset((Math.floorMod(record.assignmentIndex, 2) == 0 ? 1 : -1), 0, 0);
			if (!near(villager, post, 2.2)) {
				moveTo(level, villager, post, record);
				return;
			}
			finishLocalTaskMovement(villager, record);
			if (ticks % 100 == Math.floorMod(record.assignmentIndex, 100)) {
				int removed = removeResourceItems(level, record.civilisationId, ResourceType.FOOD, 3);
				if (removed == 3) {
					insertResourceItems(level, record.civilisationId, ResourceType.SUPPLIES, 1);
					villager.swing(InteractionHand.MAIN_HAND);
					successfulWork(record, 0.5);
				} else if (removed > 0) {
					insertResourceItems(level, record.civilisationId, ResourceType.FOOD, removed);
				}
			}
			return;
		}

		BlockPos workstation = findResearchWorkstation(level, home, record.assignmentIndex);
		if (workstation == null) {
			BlockPos staging = formationTarget(level, home.getBlockX(), home.getBlockZ() + FACTORY_ZONE_OFFSET,
					record.assignmentIndex);
			if (!near(villager, staging, 2.2))
				moveTo(level, villager, staging, record);
			return;
		}
		BlockPos post = workstation.offset((Math.floorMod(record.assignmentIndex, 2) == 0 ? 1 : -1), 0, 0);
		if (!near(villager, post, 2.2)) {
			moveTo(level, villager, post, record);
			return;
		}
		finishLocalTaskMovement(villager, record);
		if (ticks % 20 == Math.floorMod(record.assignmentIndex, 20)) {
			// ResearchSystem owns the actual time-based progress calculation. A
			// RESEARCHER job increases its speed even if this particular physical
			// worker briefly cannot animate at a workstation, preventing research
			// from freezing because of pathfinding or workstation placement.
			villager.swing(InteractionHand.MAIN_HAND);
			successfulWork(record, 0.20);
		}
	}

	private boolean tickAdministratorLighting(ServerLevel level, Civilisation civilisation, City home,
			PathfinderMob villager, WorkerRecord record) {
		if (level == null || civilisation == null || home == null || villager == null || record == null)
			return false;

		BlockPos target = record.hasAdminTorchTarget
				? new BlockPos(record.adminTorchTargetX, record.adminTorchTargetY, record.adminTorchTargetZ)
				: null;
		if (target == null || !isAdministratorTorchSite(level, target)) {
			record.hasAdminTorchTarget = false;
			target = findAdministratorTorchSite(level, home, record);
			if (target != null) {
				record.hasAdminTorchTarget = true;
				record.adminTorchTargetX = target.getX();
				record.adminTorchTargetY = target.getY();
				record.adminTorchTargetZ = target.getZ();
				dirty = true;
			}
		}

		// If there is nowhere useful to light, administrators resume their other
		// command-post duties without consuming or fetching torches.
		if (target == null)
			return false;

		if (record.adminTorches <= 0) {
			BlockPos chest = nearestDepotChestWithSpecificItem(level, civilisation.getId(), Items.TORCH,
					villager.blockPosition(), record);
			if (chest == null)
				return false;
			if (walkToDepotChest(level, villager, chest, record, 3.2))
				return true;
			BlockEntity blockEntity = level.getBlockEntity(chest);
			if (!(blockEntity instanceof Container container)) {
				clearMoveTarget(record);
				return true;
			}
			int taken = removeSpecificItems(container, List.of(Items.TORCH), ADMIN_TORCH_CARRY_BATCH);
			if (taken <= 0) {
				clearMoveTarget(record);
				return false;
			}
			record.adminTorches += taken;
			updateCarriedDisplay(villager, record);
			villager.swing(InteractionHand.MAIN_HAND);
			finishLocalTaskMovement(villager, record);
			dirty = true;
			return true;
		}

		if (!near(villager, target, 3.2)) {
			moveTo(level, villager, target, record);
			return true;
		}
		finishLocalTaskMovement(villager, record);
		if (!isAdministratorTorchSite(level, target)) {
			record.hasAdminTorchTarget = false;
			dirty = true;
			return true;
		}

		level.setBlockAndUpdate(target, Blocks.TORCH.defaultBlockState());
		record.adminTorches = Math.max(0, record.adminTorches - 1);
		record.hasAdminTorchTarget = false;
		updateCarriedDisplay(villager, record);
		villager.swing(InteractionHand.MAIN_HAND);
		successfulWork(record, 0.35);
		dirty = true;
		return true;
	}

	private BlockPos findAdministratorTorchSite(ServerLevel level, City home, WorkerRecord record) {
		int total = ADMIN_LIGHTING_GRID_SIDE * ADMIN_LIGHTING_GRID_SIDE;
		int centre = ADMIN_LIGHTING_GRID_SIDE / 2;
		for (int attempt = 0; attempt < ADMIN_LIGHTING_SEARCH_ATTEMPTS; attempt++) {
			int index = Math.floorMod(record.adminLightingCursor + record.assignmentIndex * 17 + attempt, total);
			int gx = Math.floorMod(index, ADMIN_LIGHTING_GRID_SIDE) - centre;
			int gz = Math.floorDiv(index, ADMIN_LIGHTING_GRID_SIDE) - centre;
			int x = home.getBlockX() + gx * ADMIN_LIGHTING_GRID_SPACING;
			int z = home.getBlockZ() + gz * ADMIN_LIGHTING_GRID_SPACING;
			if (!isChunkLoaded(level, x, z))
				continue;
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockPos candidate = new BlockPos(x, y, z);
			if (!isAdministratorTorchSite(level, candidate))
				continue;
			record.adminLightingCursor = Math.floorMod(index + 1, total);
			dirty = true;
			return candidate;
		}
		record.adminLightingCursor = Math.floorMod(record.adminLightingCursor + ADMIN_LIGHTING_SEARCH_ATTEMPTS, total);
		return null;
	}

	private boolean isAdministratorTorchSite(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null || !isChunkLoaded(level, pos.getX(), pos.getZ()))
			return false;
		if (!level.getBlockState(pos).isAir())
			return false;
		if (level.getBlockState(pos.below()).isAir())
			return false;
		if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.below()).isEmpty())
			return false;
		if (!Blocks.TORCH.defaultBlockState().canSurvive(level, pos))
			return false;
		if (level.getBrightness(LightLayer.BLOCK, pos) > ADMIN_MAX_BLOCK_LIGHT)
			return false;
		return !hasNearbySettlementTorch(level, pos);
	}

	private boolean hasNearbySettlementTorch(ServerLevel level, BlockPos centre) {
		for (int dy = -2; dy <= 3; dy++) {
			for (int dz = -ADMIN_TORCH_MIN_SEPARATION; dz <= ADMIN_TORCH_MIN_SEPARATION; dz++) {
				for (int dx = -ADMIN_TORCH_MIN_SEPARATION; dx <= ADMIN_TORCH_MIN_SEPARATION; dx++) {
					if (dx * dx + dz * dz > ADMIN_TORCH_MIN_SEPARATION * ADMIN_TORCH_MIN_SEPARATION)
						continue;
					BlockPos check = centre.offset(dx, dy, dz);
					if (level.getBlockState(check).is(Blocks.TORCH) || level.getBlockState(check).is(Blocks.WALL_TORCH))
						return true;
				}
			}
		}
		return false;
	}

	private BlockPos assignedDepotChest(ServerLevel level, String civilisationId, int assignmentIndex) {
		List<BlockPos> positions = new ArrayList<>();
		for (DepotRecord depot : depots.values()) {
			if (!depot.active || !Objects.equals(civilisationId, depot.civilisationId) || depot.chests == null)
				continue;
			for (StoredPos stored : depot.chests) {
				BlockPos pos = stored.toBlockPos();
				if (level.getBlockEntity(pos) instanceof Container)
					positions.add(pos);
			}
		}
		if (positions.isEmpty())
			return null;
		positions.sort((BlockPos a, BlockPos b) -> {
			int byX = Long.compare(a.getX(), b.getX());
			return byX != 0 ? byX : Long.compare(a.getZ(), b.getZ());
		});
		return positions.get(Math.floorMod(assignmentIndex, positions.size()));
	}

	private BlockPos findResearchWorkstation(ServerLevel level, City home, int assignmentIndex) {
		List<BlockPos> stations = new ArrayList<>();
		for (int dz = -CRAFTING_TABLE_SEARCH_RADIUS; dz <= CRAFTING_TABLE_SEARCH_RADIUS; dz += 2) {
			for (int dx = -CRAFTING_TABLE_SEARCH_RADIUS; dx <= CRAFTING_TABLE_SEARCH_RADIUS; dx += 2) {
				int x = home.getBlockX() + dx;
				int z = home.getBlockZ() + dz;
				if (!isChunkLoaded(level, x, z))
					continue;
				int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				for (int dy = -3; dy <= 1; dy++) {
					BlockPos pos = new BlockPos(x, surfaceY + dy, z);
					BlockState state = level.getBlockState(pos);
					if (state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.FURNACE))
						stations.add(pos);
				}
			}
		}
		if (stations.isEmpty())
			return null;
		stations.sort((BlockPos a, BlockPos b) -> {
			int byX = Long.compare(a.getX(), b.getX());
			return byX != 0 ? byX : Long.compare(a.getZ(), b.getZ());
		});
		return stations.get(Math.floorMod(assignmentIndex, stations.size()));
	}

	private boolean returnToDepot(ServerLevel level, Civilisation civilisation, City home, PathfinderMob villager,
			WorkerRecord record) {
		// Keep one chest stable for the duration of a physical deposit trip. Re-running
		// nearest-chest selection from the worker's changing position every tick can
		// alternate between two adjacent chests/posts and look exactly like a frozen
		// villager vibrating in place.
		BlockPos chest = "depot_return".equals(record.targetKind) ? targetPos(record) : null;
		if (chest != null) {
			BlockEntity existingEntity = level.getBlockEntity(chest);
			boolean stillUsable = existingEntity instanceof Container existing && containerCanAccept(existing, record)
					&& depotChestHasReachableInteraction(level, chest, villager.blockPosition(),
							record.assignmentIndex);
			if (!stillUsable) {
				clearTarget(record);
				finishLocalTaskMovement(villager, record);
				chest = null;
			}
		}

		if (chest == null) {
			chest = nearestUsableDepotChest(level, civilisation.getId(), villager.blockPosition(), record);
			// A repaired/older save can occasionally have a perfectly usable physical
			// command-post chest that is not yet present in the cached DepotRecord. Fall
			// back to a bounded real-world scan before ever claiming that storage is full.
			if (chest == null) {
				chest = nearestLocalCommandPostChest(level, home, villager.blockPosition(), record);
			}
			if (chest != null)
				setTarget(record, "depot_return", chest);
		}

		if (chest == null) {
			record.forceDeposit = true;
			record.storageActuallyFull = depotStorageActuallyFull(level, civilisation.getId(), home, record);
			if (record.hasMoveTarget || ensureBrain(record).hasGoal) {
				finishLocalTaskMovement(villager, record);
			} else {
				clearMoveTarget(record);
				clearTarget(record);
			}
			return false;
		}
		record.storageActuallyFull = false;
		if (walkToDepotChest(level, villager, chest, record, 3.2))
			return true;

		BlockEntity blockEntity = level.getBlockEntity(chest);
		if (!(blockEntity instanceof Container container)) {
			record.forceDeposit = true;
			record.storageActuallyFull = false;
			clearTarget(record);
			finishLocalTaskMovement(villager, record);
			return false;
		}

		boolean changed = false;
		for (ResourceType type : ResourceType.values()) {
			int amount = record.carrying.getOrDefault(type.name(), 0);
			if (amount <= 0)
				continue;
			// Deposit into the chest the humanoid actually walked to. A chest is not
			// considered full while even one compatible partial stack or empty slot
			// remains anywhere in the controlled depot.
			int inserted = insertResourceItems(container, type, amount);
			if (inserted > 0) {
				record.carrying.put(type.name(), amount - inserted);
				changed = true;
			}
		}
		if (parseJob(record.job) == VillagerJob.SOLDIER && record.boneMeal > 0) {
			int inserted = insertSpecificItems(container, Items.BONE_MEAL, record.boneMeal);
			if (inserted > 0) {
				record.boneMeal -= inserted;
				changed = true;
			}
		}
		if (changed) {
			villager.swing(InteractionHand.MAIN_HAND);
			record.inMine = false;
			record.storageActuallyFull = false;
			dirty = true;
			updateCarriedDisplay(villager, record);
		}

		// The current chest transaction is complete even if part of the load remains.
		// Clear the chest/post leg before choosing another chest so no stale waypoint
		// survives and no chest-switch oscillation is possible.
		clearTarget(record);
		finishLocalTaskMovement(villager, record);
		if (depotLoadTotal(record) <= 0) {
			record.forceDeposit = false;
			record.storageActuallyFull = false;
		} else {
			record.forceDeposit = true;
			record.storageActuallyFull = depotStorageActuallyFull(level, civilisation.getId(), home, record);
		}
		return depotLoadTotal(record) > 0;
	}

	private void moveTo(ServerLevel level, PathfinderMob villager, BlockPos target, WorkerRecord record) {
		if (target == null || villager == null || record == null)
			return;
		WorkerBrainState brain = ensureBrain(record);
		WorkerIntent intent = deriveIntent(record);
		String goalKind = record.targetKind == null ? intent.name() : record.targetKind;
		brain.setGoal(goalKind, target.getX(), target.getY(), target.getZ(), intent);

		double horizontal = Math.sqrt(horizontalDistanceSquared(villager.blockPosition(), target));
		if (!brain.escaping && horizontal >= STRATEGIC_ROUTE_THRESHOLD) {
			requestStrategicRoute(level, villager, record, brain);
		}

		BlockPos localTarget = currentBrainNavigationTarget(level, villager, record, target);
		setLocalMoveTarget(level, villager, record, localTarget);
	}

	private WorkerBrainState ensureBrain(WorkerRecord record) {
		if (record.brain == null)
			record.brain = new WorkerBrainState();
		if (record.brain.route == null)
			record.brain.route = new ArrayList<>();
		if (record.brain.escapeRoute == null)
			record.brain.escapeRoute = new ArrayList<>();
		if (record.brain.failedLocations == null)
			record.brain.failedLocations = new ArrayList<>();
		return record.brain;
	}

	private WorkerIntent deriveIntent(WorkerRecord record) {
		if (record == null)
			return WorkerIntent.IDLE;
		if (record.hasMealTarget || (record.targetKind != null && record.targetKind.contains("meal"))) {
			return WorkerIntent.SEEK_MEAL;
		}
		if (record.forceDeposit || (record.targetKind != null && record.targetKind.contains("depot"))) {
			return WorkerIntent.RETURN_TO_DEPOT;
		}
		VillagerJob job = parseJob(record.job);
		if (job == VillagerJob.SOLDIER)
			return WorkerIntent.ARMY_ORDER;
		if (job == VillagerJob.FARMER || job == VillagerJob.FACTORY_BUILDER) {
			return WorkerIntent.BUILD_OR_OPERATE_DISTRICT;
		}
		return WorkerIntent.TRAVEL_TO_WORK;
	}

	private void setLocalMoveTarget(ServerLevel level, PathfinderMob villager, WorkerRecord record, BlockPos target) {
		if (target == null)
			return;
		BlockPos navigable = normaliseLocalNavigationTarget(level, villager, record, target);
		if (navigable == null)
			navigable = target;
		boolean changed = !record.hasMoveTarget || record.moveTargetX != navigable.getX()
				|| record.moveTargetY != navigable.getY() || record.moveTargetZ != navigable.getZ();
		record.hasMoveTarget = true;
		record.moveTargetX = navigable.getX();
		record.moveTargetY = navigable.getY();
		record.moveTargetZ = navigable.getZ();
		if (changed) {
			record.stuckChecks = 0;
			record.lastTargetDistanceSquared = -1.0;
		}
		if (changed && !record.hasTerrainEscapeTarget)
			issueNavigation(level, villager, navigable);
	}

	private BlockPos normaliseLocalNavigationTarget(ServerLevel level, PathfinderMob villager, WorkerRecord record,
			BlockPos requested) {
		if (level == null || villager == null || record == null || requested == null)
			return requested;
		requested = lowerTargetPastNaturalClutter(level, requested);
		if (isSafeNavigationFeet(level, record, requested))
			return requested;

		BlockPos best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		boolean miner = parseJob(record.job) == VillagerJob.MINER;
		boolean underground = record.inMine || record.mineTransitDirection != 0 || requested.getY() + 3 < level
				.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, requested.getX(), requested.getZ());
		for (int radius = 1; radius <= 4; radius++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					if (Math.abs(dx) != radius && Math.abs(dz) != radius)
						continue;
					int x = requested.getX() + dx;
					int z = requested.getZ() + dz;
					if (!isChunkLoaded(level, x, z))
						continue;
					if (!miner && isMineSurfaceExclusion(record, x, z))
						continue;
					if (underground) {
						for (int dy = -2; dy <= 2; dy++) {
							BlockPos candidate = new BlockPos(x, requested.getY() + dy, z);
							if (!isSafeNavigationFeet(level, record, candidate))
								continue;
							double score = distanceSquared(candidate, requested)
									+ villager.distanceToSqr(candidate.getX() + 0.5, candidate.getY(),
											candidate.getZ() + 0.5) * 0.02;
							if (score < bestScore) {
								bestScore = score;
								best = candidate;
							}
						}
					} else {
						int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
						BlockPos candidate = lowerTargetPastNaturalClutter(level, new BlockPos(x, y, z));
						if (!isSafeNavigationFeet(level, record, candidate))
							continue;
						double score = distanceSquared(candidate, requested)
								+ Math.abs(candidate.getY() - requested.getY()) * 1.5
								+ villager.distanceToSqr(candidate.getX() + 0.5, candidate.getY(),
										candidate.getZ() + 0.5) * 0.01;
						if (isRoad(level.getBlockState(candidate.below())))
							score -= 2.0;
						if (score < bestScore) {
							bestScore = score;
							best = candidate;
						}
					}
				}
			}
			if (best != null)
				break;
		}
		return best == null ? requested : best;
	}

	private boolean isSafeNavigationFeet(ServerLevel level, WorkerRecord record, BlockPos feet) {
		if (!isWorkerStandable(level, feet))
			return false;
		BlockState support = level.getBlockState(feet.below());
		if (isLog(support) || isLeaves(support))
			return false;
		if (parseJob(record.job) != VillagerJob.MINER && isMineSurfaceExclusion(record, feet.getX(), feet.getZ()))
			return false;
		return true;
	}

	private BlockPos currentBrainNavigationTarget(ServerLevel level, PathfinderMob villager, WorkerRecord record,
			BlockPos requestedGoal) {
		WorkerBrainState brain = ensureBrain(record);
		if (brain.escaping && brain.escapeRoute != null && brain.escapeIndex < brain.escapeRoute.size()) {
			WorkerBrainState.BrainWaypoint waypoint = brain.escapeRoute.get(brain.escapeIndex);
			return new BlockPos(waypoint.x(), waypoint.y(), waypoint.z());
		}
		if (brain.route != null && brain.routeIndex < brain.route.size()) {
			WorkerBrainState.BrainWaypoint waypoint = brain.route.get(brain.routeIndex);
			return new BlockPos(waypoint.x(), waypoint.y(), waypoint.z());
		}

		// While a long-range plan is being computed, make bounded forward progress
		// instead of asking vanilla PathNavigation to solve the entire journey.
		if (requestedGoal != null && horizontalDistanceSquared(villager.blockPosition(),
				requestedGoal) > STRATEGIC_ROUTE_THRESHOLD * (double) STRATEGIC_ROUTE_THRESHOLD) {
			BlockPos here = villager.blockPosition();
			double dx = requestedGoal.getX() - here.getX();
			double dz = requestedGoal.getZ() - here.getZ();
			double length = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
			int x = here.getX() + (int) Math.round(dx / length * 18.0);
			int z = here.getZ() + (int) Math.round(dz / length * 18.0);
			if (isChunkLoaded(level, x, z)) {
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				return new BlockPos(x, y, z);
			}
		}
		return requestedGoal;
	}

	private void logNavigationIssue(WorkerRecord record, PathfinderMob villager, BlockPos target, String stage,
			NavigationFailure failure) {
		if (record == null || villager == null)
			return;
		if (record.lastNavigationDebugLogTick > 0L
				&& ticks - record.lastNavigationDebugLogTick < NAVIGATION_DEBUG_LOG_INTERVAL_TICKS)
			return;
		record.lastNavigationDebugLogTick = ticks;
		WorkerBrainState brain = ensureBrain(record);
		BlockPos at = villager.blockPosition();
		String targetText = target == null ? "none" : target.getX() + "," + target.getY() + "," + target.getZ();
		System.out.println("[GrandStrategy][Navigation] stage=" + stage + " worker=" + String.valueOf(record.uuid)
				+ " person=" + record.assignmentIndex + " civ=" + String.valueOf(record.civilisationId) + " job="
				+ String.valueOf(parseJob(record.job)) + " at=" + at.getX() + "," + at.getY() + "," + at.getZ()
				+ " target=" + targetText + " targetKind=" + String.valueOf(record.targetKind) + " goal="
				+ (brain.hasGoal ? brain.goalX + "," + brain.goalY + "," + brain.goalZ : "none") + " goalKind="
				+ String.valueOf(brain.goalKind) + " inMine=" + record.inMine + " forceDeposit=" + record.forceDeposit
				+ " mineTransit=" + record.mineTransitDirection + " failure="
				+ String.valueOf(failure == null ? NavigationFailure.UNKNOWN : failure) + " routePending="
				+ brain.routeRequestPending + " escapePending=" + brain.escapeRequestPending + " stuckChecks="
				+ record.stuckChecks);
	}

	private void requestStrategicRoute(ServerLevel level, PathfinderMob villager, WorkerRecord record,
			WorkerBrainState brain) {
		if (brain == null || !brain.hasGoal || brain.escaping || brain.routeRequestPending)
			return;
		if (brain.route != null && brain.routeIndex < brain.route.size())
			return;
		WorkerPlannerService planner = WorkerPlannerService.getInstance();
		if (planner.hasPending(record.uuid) || !planner.canAccept(record.uuid))
			return;
		// Snapshot capture is the main-thread half of route planning. Keep a strict
		// per-tick budget so a large settlement cannot synchronously scan dozens of
		// route corridors in one 50 ms server tick. Deferred workers simply retry on
		// their next service slot while their existing local navigation continues.
		if (navigationSnapshotsThisTick >= MAX_NAVIGATION_SNAPSHOT_CAPTURES_PER_TICK)
			return;
		navigationSnapshotsThisTick++;

		NavigationSnapshot snapshot = captureNavigationSnapshot(level, villager.blockPosition(), brain, record);
		BlockPos finalGoal = new BlockPos(brain.goalX, brain.goalY, brain.goalZ);
		if (snapshot == null) {
			logNavigationIssue(record, villager, finalGoal, "strategic-snapshot-unavailable",
					NavigationFailure.UNLOADED_ROUTE);
			return;
		}
		long generation = brain.planGeneration + 1L;
		if (planner.requestRoute(record.uuid, generation, snapshot)) {
			brain.planGeneration = generation;
			brain.routeRequestPending = true;
			brain.lastPlanTick = ticks;
			dirty = true;
		} else {
			logNavigationIssue(record, villager, finalGoal, "planner-request-rejected", NavigationFailure.UNKNOWN);
		}
	}

	private NavigationSnapshot captureNavigationSnapshot(ServerLevel level, BlockPos start, WorkerBrainState brain,
			WorkerRecord record) {
		if (level == null || start == null || brain == null || !brain.hasGoal)
			return null;
		BlockPos finalGoal = new BlockPos(brain.goalX, brain.goalY, brain.goalZ);
		double dx = finalGoal.getX() - start.getX();
		double dz = finalGoal.getZ() - start.getZ();
		double distance = Math.sqrt(dx * dx + dz * dz);
		double segmentScale = distance <= STRATEGIC_ROUTE_MAX_SEGMENT ? 1.0
				: STRATEGIC_ROUTE_MAX_SEGMENT / Math.max(1.0, distance);
		int segmentX = start.getX() + (int) Math.round(dx * segmentScale);
		int segmentZ = start.getZ() + (int) Math.round(dz * segmentScale);
		if (!isChunkLoaded(level, segmentX, segmentZ)) {
			// Stop at the last loaded point along the direction; no planning job may
			// generate/force-load chunks merely to make a path.
			int steps = 12;
			boolean found = false;
			for (int i = steps; i >= 1; i--) {
				double f = segmentScale * i / (double) steps;
				int tx = start.getX() + (int) Math.round(dx * f);
				int tz = start.getZ() + (int) Math.round(dz * f);
				if (isChunkLoaded(level, tx, tz)) {
					segmentX = tx;
					segmentZ = tz;
					found = true;
					break;
				}
			}
			if (!found)
				return null;
		}
		int segmentY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, segmentX, segmentZ);

		int minX = Math.min(start.getX(), segmentX) - STRATEGIC_ROUTE_CORRIDOR_MARGIN;
		int maxX = Math.max(start.getX(), segmentX) + STRATEGIC_ROUTE_CORRIDOR_MARGIN;
		int minZ = Math.min(start.getZ(), segmentZ) - STRATEGIC_ROUTE_CORRIDOR_MARGIN;
		int maxZ = Math.max(start.getZ(), segmentZ) + STRATEGIC_ROUTE_CORRIDOR_MARGIN;
		int cell = STRATEGIC_ROUTE_CELL_SIZE;
		int originX = Math.floorDiv(minX, cell) * cell;
		int originZ = Math.floorDiv(minZ, cell) * cell;
		int width = Math.min(STRATEGIC_ROUTE_MAX_CELLS_PER_AXIS, Math.max(2, Math.floorDiv(maxX - originX, cell) + 2));
		int depth = Math.min(STRATEGIC_ROUTE_MAX_CELLS_PER_AXIS, Math.max(2, Math.floorDiv(maxZ - originZ, cell) + 2));
		int[] heights = new int[width * depth];
		byte[] terrain = new byte[width * depth];
		boolean miner = parseJob(record.job) == VillagerJob.MINER;

		for (int cz = 0; cz < depth; cz++) {
			for (int cx = 0; cx < width; cx++) {
				int wx = originX + cx * cell + cell / 2;
				int wz = originZ + cz * cell + cell / 2;
				int idx = cz * width + cx;
				if (!isChunkLoaded(level, wx, wz)) {
					terrain[idx] = NavigationSnapshot.BLOCKED;
					continue;
				}
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);
				BlockPos feet = lowerTargetPastNaturalClutter(level, new BlockPos(wx, y, wz));
				y = feet.getY();
				heights[idx] = y;
				BlockState below = level.getBlockState(feet.below());
				if (!miner && isMineSurfaceExclusion(record, wx, wz)) {
					terrain[idx] = NavigationSnapshot.BLOCKED;
				} else if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.below()).isEmpty()) {
					terrain[idx] = NavigationSnapshot.WATER;
				} else if (isWorkerStandable(level, feet)) {
					if (isLog(below) || isLeaves(below))
						terrain[idx] = NavigationSnapshot.ROUGH;
					else
						terrain[idx] = isRoad(below) ? NavigationSnapshot.ROAD : NavigationSnapshot.NORMAL;
				} else if (isLeaves(level.getBlockState(feet)) || isLog(level.getBlockState(feet))) {
					terrain[idx] = NavigationSnapshot.ROUGH;
				} else {
					terrain[idx] = NavigationSnapshot.BLOCKED;
				}
			}
		}

		int startCx = clampInt(Math.floorDiv(start.getX() - originX, cell), 0, width - 1);
		int startCz = clampInt(Math.floorDiv(start.getZ() - originZ, cell), 0, depth - 1);
		int goalCx = clampInt(Math.floorDiv(segmentX - originX, cell), 0, width - 1);
		int goalCz = clampInt(Math.floorDiv(segmentZ - originZ, cell), 0, depth - 1);
		terrain[startCz * width + startCx] = NavigationSnapshot.NORMAL;
		terrain[goalCz * width + goalCx] = NavigationSnapshot.NORMAL;

		List<Integer> penalties = new ArrayList<>();
		if (brain.failedLocations != null) {
			for (WorkerBrainState.FailedLocation failed : brain.failedLocations) {
				int fx = Math.floorDiv(failed.x() - originX, cell);
				int fz = Math.floorDiv(failed.z() - originZ, cell);
				if (fx >= 0 && fz >= 0 && fx < width && fz < depth)
					penalties.add(fz * width + fx);
			}
		}
		int[] penaltyArray = penalties.stream().mapToInt(Integer::intValue).toArray();
		return new NavigationSnapshot(originX, originZ, cell, width, depth, heights, terrain, startCx, startCz, goalCx,
				goalCz, segmentX, segmentY, segmentZ, penaltyArray);
	}

	private void applyCompletedWorkerPlan(ServerLevel level, PathfinderMob villager, WorkerRecord record) {
		PlannerResult result = WorkerPlannerService.getInstance().poll(record.uuid);
		if (result == null)
			return;
		WorkerBrainState brain = ensureBrain(record);
		// Receiving any result means that particular asynchronous slot is finished.
		// Clear the pending flags BEFORE generation validation. Otherwise an
		// exception/stale result can leave the nameplate at "Planning route" forever.
		brain.routeRequestPending = false;
		brain.escapeRequestPending = false;
		if (result.generation() != brain.planGeneration) {
			dirty = true;
			return;
		}
		if (!result.success()) {
			brain.rememberFailure(villager.blockPosition().getX(), villager.blockPosition().getY(),
					villager.blockPosition().getZ(), result.failure(), ticks);
			BlockPos failedGoal = brain.hasGoal ? new BlockPos(brain.goalX, brain.goalY, brain.goalZ) : null;
			logNavigationIssue(record, villager, failedGoal,
					result.escape() ? "escape-planner-failed" : "route-planner-failed", result.failure());
			dirty = true;
			return;
		}
		if (result.escape()) {
			brain.escapeRoute = new ArrayList<>(result.waypoints());
			brain.escapeIndex = 0;
			brain.escaping = !brain.escapeRoute.isEmpty();
			if (brain.escaping)
				brain.intent = WorkerIntent.ESCAPE_THEN_RESUME.name();
		} else {
			brain.route = new ArrayList<>(result.waypoints());
			brain.routeIndex = 0;
			brain.consecutiveFailures = 0;
			brain.lastFailure = NavigationFailure.NONE.name();
		}
		BlockPos next = nextBrainWaypoint(brain);
		if (next != null)
			setLocalMoveTarget(level, villager, record, next);
		dirty = true;
	}

	private BlockPos nextBrainWaypoint(WorkerBrainState brain) {
		if (brain == null)
			return null;
		if (brain.escaping && brain.escapeRoute != null && brain.escapeIndex < brain.escapeRoute.size()) {
			WorkerBrainState.BrainWaypoint w = brain.escapeRoute.get(brain.escapeIndex);
			return new BlockPos(w.x(), w.y(), w.z());
		}
		if (brain.route != null && brain.routeIndex < brain.route.size()) {
			WorkerBrainState.BrainWaypoint w = brain.route.get(brain.routeIndex);
			return new BlockPos(w.x(), w.y(), w.z());
		}
		return null;
	}

	private boolean advanceBrainWaypoint(ServerLevel level, PathfinderMob villager, WorkerRecord record) {
		WorkerBrainState brain = ensureBrain(record);
		if (brain.escaping) {
			brain.escapeIndex++;
			if (brain.escapeIndex < brain.escapeRoute.size()) {
				BlockPos next = nextBrainWaypoint(brain);
				setLocalMoveTarget(level, villager, record, next);
				return true;
			}
			brain.escapeRoute.clear();
			brain.escapeIndex = 0;
			brain.escaping = false;
			brain.intent = deriveIntent(record).name();
			brain.route.clear();
			brain.routeIndex = 0;
			brain.routeRequestPending = false;
			if (brain.hasGoal)
				requestStrategicRoute(level, villager, record, brain);
			return false;
		}
		if (brain.route != null && brain.routeIndex < brain.route.size()) {
			brain.routeIndex++;
			if (brain.routeIndex < brain.route.size()) {
				setLocalMoveTarget(level, villager, record, nextBrainWaypoint(brain));
				return true;
			}
			brain.route.clear();
			brain.routeIndex = 0;
			if (brain.hasGoal && horizontalDistanceSquared(villager.blockPosition(), new BlockPos(brain.goalX,
					brain.goalY, brain.goalZ)) > STRATEGIC_ROUTE_THRESHOLD * (double) STRATEGIC_ROUTE_THRESHOLD) {
				requestStrategicRoute(level, villager, record, brain);
			}
		}
		return false;
	}

	private void requestEscapePlan(ServerLevel level, PathfinderMob villager, WorkerRecord record,
			NavigationFailure failure) {
		WorkerBrainState brain = ensureBrain(record);
		WorkerPlannerService planner = WorkerPlannerService.getInstance();
		if (brain.escapeRequestPending || brain.escaping || planner.hasPending(record.uuid)
				|| !planner.canAccept(record.uuid))
			return;
		if (escapeSnapshotsThisTick >= MAX_ESCAPE_SNAPSHOT_CAPTURES_PER_TICK)
			return;
		escapeSnapshotsThisTick++;
		EscapeSnapshot snapshot = captureEscapeSnapshot(level, villager, brain);
		if (snapshot == null)
			return;
		long generation = brain.planGeneration + 1L;
		if (planner.requestEscape(record.uuid, generation, snapshot)) {
			brain.planGeneration = generation;
			brain.escapeRequestPending = true;
			brain.lastPlanTick = ticks;
			brain.rememberFailure(villager.blockPosition().getX(), villager.blockPosition().getY(),
					villager.blockPosition().getZ(), failure, ticks);
			dirty = true;
		}
	}

	private EscapeSnapshot captureEscapeSnapshot(ServerLevel level, PathfinderMob villager, WorkerBrainState brain) {
		BlockPos here = villager.blockPosition();
		int width = ESCAPE_SNAPSHOT_RADIUS_XZ * 2 + 1;
		int depth = width;
		int height = ESCAPE_SNAPSHOT_BELOW + ESCAPE_SNAPSHOT_ABOVE + 1;
		int originX = here.getX() - ESCAPE_SNAPSHOT_RADIUS_XZ;
		int originY = here.getY() - ESCAPE_SNAPSHOT_BELOW;
		int originZ = here.getZ() - ESCAPE_SNAPSHOT_RADIUS_XZ;
		byte[] voxels = new byte[width * height * depth];
		for (int y = 0; y < height; y++) {
			for (int z = 0; z < depth; z++) {
				for (int x = 0; x < width; x++) {
					BlockPos pos = new BlockPos(originX + x, originY + y, originZ + z);
					int idx = (y * depth + z) * width + x;
					if (!isChunkLoaded(level, pos.getX(), pos.getZ())) {
						voxels[idx] = EscapeSnapshot.PROTECTED;
						continue;
					}
					BlockState state = level.getBlockState(pos);
					if (!level.getFluidState(pos).isEmpty()) {
						voxels[idx] = EscapeSnapshot.WATER;
					} else if (state.isAir()) {
						voxels[idx] = EscapeSnapshot.AIR;
					} else if (level.getBlockEntity(pos) != null || !isWorkerBreakableObstacle(state)) {
						voxels[idx] = EscapeSnapshot.PROTECTED;
					} else if (isSoil(state) || isLeaves(state) || isLog(state) || isNaturalPathClutter(state)
							|| state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.SAND)) {
						voxels[idx] = EscapeSnapshot.BREAK_SOFT;
					} else {
						voxels[idx] = EscapeSnapshot.BREAK_HARD;
					}
				}
			}
		}
		int directionX = 1;
		int directionZ = 0;
		if (brain != null && brain.hasGoal) {
			int dx = brain.goalX - here.getX();
			int dz = brain.goalZ - here.getZ();
			if (Math.abs(dx) >= Math.abs(dz)) {
				directionX = Integer.compare(dx, 0);
				directionZ = 0;
			} else {
				directionX = 0;
				directionZ = Integer.compare(dz, 0);
			}
			if (directionX == 0 && directionZ == 0)
				directionX = 1;
		}
		return new EscapeSnapshot(originX, originY, originZ, width, height, depth, voxels, ESCAPE_SNAPSHOT_RADIUS_XZ,
				ESCAPE_SNAPSHOT_BELOW, ESCAPE_SNAPSHOT_RADIUS_XZ, directionX, directionZ);
	}

	private NavigationFailure classifyNavigationFailure(ServerLevel level, PathfinderMob villager, BlockPos target,
			WorkerRecord record) {
		if (isWorkerInFluid(level, villager))
			return NavigationFailure.WATER_TRAP;
		BlockPos here = villager.blockPosition();
		if (!isChunkLoaded(level, target.getX(), target.getZ()))
			return NavigationFailure.UNLOADED_ROUTE;
		if (target.getY() - here.getY() >= 3)
			return NavigationFailure.CLIFF_UP;
		if (here.getY() - target.getY() >= 5)
			return NavigationFailure.CLIFF_DOWN;
		AABB crowdBox = villager.getBoundingBox().inflate(1.4, 0.6, 1.4);
		long neighbours = level.getEntitiesOfClass(GrandStrategyHumanoidEntity.class, crowdBox,
				other -> other != villager && other.isAlive()).stream().limit(4).count();
		if (neighbours >= 2)
			return NavigationFailure.CROWD_BLOCKED;

		int sx = Integer.compare(target.getX(), here.getX());
		int sz = Integer.compare(target.getZ(), here.getZ());
		BlockPos front = here.offset(sx, 0, sz);
		BlockState frontState = level.getBlockState(front);
		if (!frontState.isAir()) {
			if (level.getBlockEntity(front) != null || !isWorkerBreakableObstacle(frontState)) {
				return NavigationFailure.STRUCTURE_BLOCKED;
			}
			return NavigationFailure.BLOCKED_WALL;
		}
		if (villager.getNavigation().isDone())
			return NavigationFailure.PATH_NOT_FOUND;
		return NavigationFailure.NO_PROGRESS;
	}

	private void prepareEscapeWaypoint(ServerLevel level, PathfinderMob villager, WorkerRecord record,
			BlockPos waypoint) {
		WorkerBrainState brain = ensureBrain(record);
		if (!brain.escaping || waypoint == null)
			return;
		BlockPos here = villager.blockPosition();
		if (waypoint.getY() < here.getY())
			return; // never dig down beneath the worker
		BlockPos[] body = { waypoint, waypoint.above() };
		boolean changed = false;
		for (BlockPos pos : body) {
			if (pos.getY() < here.getY())
				continue;
			BlockState state = level.getBlockState(pos);
			if (state.isAir() || !level.getFluidState(pos).isEmpty())
				continue;
			if (level.getBlockEntity(pos) != null || !isWorkerBreakableObstacle(state))
				continue;
			changed |= clearEmergencyBlock(level, record, pos);
		}
		if (changed) {
			villager.swing(InteractionHand.MAIN_HAND);
			record.lastObstacleBreakTick = ticks;
			dirty = true;
		}
	}

	private void issueNavigation(ServerLevel level, PathfinderMob villager, BlockPos target) {
		WorkerRecord record = workers.get(villager.getUUID().toString());
		boolean soldier = record != null && parseJob(record.job) == VillagerJob.SOLDIER;
		boolean road = isRoad(level.getBlockState(villager.blockPosition().below()));

		// Dedicated humanoids use a player-like walking pace. Soldiers use a
		// distinct running pace, with roads providing an additional logistics bonus.
		double speed;
		if (soldier) {
			speed = road ? SOLDIER_ROAD_WALK_SPEED : SOLDIER_WALK_SPEED;
		} else {
			speed = road ? ROAD_WALK_SPEED : NORMAL_WALK_SPEED;
		}
		if (record != null) {
			suppressVanillaWorkerMovement(villager);
			record.lastNavigationIssueTick = ticks;
		}

		// PathNavigation reports "done" when asked to path to the block the humanoid
		// already occupies (and often for an immediately adjacent interaction block).
		// That is success, not PATH_NOT_FOUND. Besides removing misleading log spam,
		// this prevents the anti-freeze system from treating a worker already at its
		// farm/mine/workstation as a navigation casualty.
		BlockPos hereAtIssue = villager.blockPosition();
		if (horizontalDistanceSquared(hereAtIssue, target) <= 1.25 * 1.25
				&& Math.abs(hereAtIssue.getY() - target.getY()) <= 1) {
			villager.getNavigation().stop();
			return;
		}

		villager.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);

		// If vanilla immediately refuses even a short leg, first clear one genuinely
		// local obstruction and retry. This is especially important for road builders:
		// an immediate no-path result previously left stuckChecks at zero, so their
		// hard-terrain grading fallback could never be reached. Other professions get
		// the same nearby tree/foliage clearing but not road-builder hard grading.
		if (record != null && villager.getNavigation().isDone()) {
			boolean cleared = clearImmediateNaturalPathClutter(level, villager, record, target);
			if (!cleared) {
				cleared = tryBreakNavigationObstacle(level, villager, target, record,
						parseJob(record.job) == VillagerJob.ROAD_BUILDER);
			}
			if (cleared) {
				villager.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);
			}
		}

		// If vanilla cannot make a path to the exact local point, try a short
		// standable approach in the same direction immediately rather than leaving
		// the worker motionless until several stuck-check cycles have elapsed.
		if (record != null && villager.getNavigation().isDone()) {
			BlockPos here = villager.blockPosition();
			double dx = target.getX() - here.getX();
			double dz = target.getZ() - here.getZ();
			double length = Math.sqrt(dx * dx + dz * dz);
			if (length > 3.0) {
				double step = Math.min(8.0, length - 1.0);
				int sx = here.getX() + (int) Math.round(dx / Math.max(1.0, length) * step);
				int sz = here.getZ() + (int) Math.round(dz / Math.max(1.0, length) * step);
				int sy = record.inMine ? here.getY()
						: level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);
				BlockPos shortTarget = normaliseLocalNavigationTarget(level, villager, record,
						new BlockPos(sx, sy, sz));
				if (shortTarget != null && !shortTarget.equals(here)) {
					villager.getNavigation().moveTo(shortTarget.getX() + 0.5, shortTarget.getY(),
							shortTarget.getZ() + 0.5, speed);
				}
			}
			if (villager.getNavigation().isDone()) {
				BlockPos hereNow = villager.blockPosition();
				if (horizontalDistanceSquared(hereNow, target) <= 2.2 * 2.2
						&& Math.abs(hereNow.getY() - target.getY()) <= 2) {
					villager.getNavigation().stop();
					return;
				}
				logNavigationIssue(record, villager, target, "local-path-not-found", NavigationFailure.PATH_NOT_FOUND);
			}
		}
	}

	private void suppressVanillaWorkerMovement(PathfinderMob villager) {
		if (villager == null)
			return;

		// Dedicated Grand Strategy humanoids deliberately have no vanilla Brain
		// behaviour to suppress. Do NOT touch Brain memory modules on them: a bare
		// PathfinderMob does not register the Villager WALK_TARGET/PATH memories,
		// and repeatedly trying to erase unregistered memories can throw internally.
		// Catching that exception hid the problem but made every worker pay the
		// exception cost every tick, which could make the entire simulation appear
		// frozen as population increased.
		if (villager instanceof GrandStrategyHumanoidEntity)
			return;

		// Compatibility only for a legacy vanilla body during the brief migration
		// window before it is replaced by GrandStrategyHumanoidEntity.
		try {
			villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			villager.getBrain().eraseMemory(MemoryModuleType.PATH);
		} catch (Throwable ignored) {
			// Legacy compatibility path only. Dedicated humanoids never enter here.
		}
	}

	private boolean isWorkerInFluid(ServerLevel level, PathfinderMob villager) {
		BlockPos feet = villager.blockPosition();
		return !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty();
	}

	private void tickWaterEscape(ServerLevel level, Civilisation civilisation, City home, PathfinderMob villager,
			WorkerRecord record) {
		record.waterTicks++;
		if (!villager.getNavigation().isDone())
			villager.getNavigation().stop();

		// Water recovery has its own short-lived local destination. A worker's real
		// job/army destination may be hundreds of blocks away and on the wrong side
		// of the pond; pushing directly toward that strategic destination can pin a
		// whole group against the same vertical bank. Search only a tiny local area
		// for a genuinely dry standable block, then resume the preserved assignment.
		boolean cachedShoreValid = record.hasWaterEscapeTarget && isDryStandableShore(level,
				new BlockPos(record.waterEscapeX, record.waterEscapeY, record.waterEscapeZ));
		if (!cachedShoreValid)
			record.hasWaterEscapeTarget = false;

		boolean searchSlot = ticks % WATER_ESCAPE_RESCAN_TICKS == Math.floorMod(record.assignmentIndex,
				WATER_ESCAPE_RESCAN_TICKS);
		if (searchSlot && (!record.hasWaterEscapeTarget
				|| ticks - record.lastWaterEscapeSearchTick >= WATER_ESCAPE_RESCAN_TICKS)) {
			BlockPos shore = findLocalDryShore(level, villager.blockPosition(), record);
			if (shore != null) {
				record.hasWaterEscapeTarget = true;
				record.waterEscapeX = shore.getX();
				record.waterEscapeY = shore.getY();
				record.waterEscapeZ = shore.getZ();
			} else {
				record.hasWaterEscapeTarget = false;
			}
			record.lastWaterEscapeSearchTick = ticks;
		}

		if (record.hasWaterEscapeTarget) {
			BlockPos shore = new BlockPos(record.waterEscapeX, record.waterEscapeY, record.waterEscapeZ);
			double horizontalSquared = horizontalDistanceSquared(villager.blockPosition(), shore);
			double upward = shore.getY() > villager.getY() + 0.20 ? WATER_BANK_UPWARD_ASSIST : WATER_UPWARD_ASSIST;

			// Do not ask vanilla ground pathfinding to solve the pond. Direct motion
			// is cheap, deterministic and, with the stronger upward impulse near a
			// one-block bank, behaves like a physical swim/jump onto shore.
			applyEmergencyMotion(villager, shore, WATER_SWIM_ASSIST, upward);

			// If a natural one-block bank still blocks the selected exit, clear only
			// the immediate headroom/tread approach. This is staggered and local, so
			// even a large trapped army cannot trigger a wide terrain scan.
			if (record.waterTicks >= WATER_ESCAPE_CARVE_AFTER_TICKS && horizontalSquared <= 9.0
					&& ticks % EMERGENCY_CARVE_INTERVAL_TICKS == Math.floorMod(record.assignmentIndex,
							EMERGENCY_CARVE_INTERVAL_TICKS)) {
				carveImmediateClimbStep(level, villager, record, shore);
			}
			return;
		}

		// No dry shore is within the five-block local window yet (for example the
		// worker is in the middle of a larger pond). Swim in a spread-out version of
		// the real assignment direction until a shoreline enters that local window.
		BlockPos target = recoveryDirectionTarget(level, civilisation, home, villager, record);
		applyEmergencyMotion(villager, target, WATER_SWIM_ASSIST, WATER_UPWARD_ASSIST);

		if (record.waterTicks >= WATER_ESCAPE_CARVE_AFTER_TICKS && ticks % EMERGENCY_CARVE_INTERVAL_TICKS == Math
				.floorMod(record.assignmentIndex, EMERGENCY_CARVE_INTERVAL_TICKS)) {
			carveImmediateClimbStep(level, villager, record, target);
		}
	}

	/**
	 * Finds a nearby dry block that a villager can actually stand on. This is a
	 * bounded local scan only: at most five blocks horizontally and two vertical
	 * levels around the swimmer, with no heightmap/chunk/ring expansion. Soldiers
	 * are biased toward different compass directions by assignment index so crowds
	 * naturally fan out toward several exits instead of selecting one bank square.
	 */
	private BlockPos findLocalDryShore(ServerLevel level, BlockPos origin, WorkerRecord record) {
		int[][] spreadDirections = new int[][] { { 1, 0 }, { 1, 1 }, { 0, 1 }, { -1, 1 }, { -1, 0 }, { -1, -1 },
				{ 0, -1 }, { 1, -1 } };
		int[] preferred = spreadDirections[Math.floorMod(record.assignmentIndex, spreadDirections.length)];

		BlockPos best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		for (int radius = 1; radius <= WATER_ESCAPE_SEARCH_RADIUS; radius++) {
			boolean foundOnRing = false;
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					if (Math.abs(dx) != radius && Math.abs(dz) != radius)
						continue;

					// A normal pond bank is usually one block above the water feet.
					// Also allow level/downhill exits, but never nominate a two-block
					// cliff as a "shore" that the villager cannot physically climb.
					for (int dy = 1; dy >= -1; dy--) {
						BlockPos candidate = origin.offset(dx, dy, dz);
						if (!isDryStandableShore(level, candidate))
							continue;

						int rise = candidate.getY() - origin.getY();
						double distance = dx * (double) dx + dz * (double) dz;
						double directionalSpread = dx * (double) preferred[0] + dz * (double) preferred[1];
						double score = distance + Math.max(0, rise) * 1.5 - directionalSpread * 0.35;
						if (score < bestScore) {
							bestScore = score;
							best = candidate;
						}
						foundOnRing = true;
					}
				}
			}
			if (foundOnRing)
				break;
		}
		return best;
	}

	private boolean isDryStandableShore(ServerLevel level, BlockPos feet) {
		if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty())
			return false;
		if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir())
			return false;

		BlockPos ground = feet.below();
		if (!level.getFluidState(ground).isEmpty())
			return false;
		if (level.getBlockEntity(ground) != null)
			return false;
		BlockState groundState = level.getBlockState(ground);
		return !groundState.isAir();
	}

	private BlockPos recoveryDirectionTarget(ServerLevel level, Civilisation civilisation, City home,
			PathfinderMob villager, WorkerRecord record) {
		if (record.hasMoveTarget) {
			return new BlockPos(record.moveTargetX, record.moveTargetY, record.moveTargetZ);
		}
		int spreadX = (Math.floorMod(record.assignmentIndex, 5) - 2) * 2;
		int spreadZ = (Math.floorMod(record.assignmentIndex / 5, 5) - 2) * 2;
		int recoveryY = villager.blockPosition().getY() + 8;
		if (parseJob(record.job) == VillagerJob.SOLDIER) {
			if (!civilisation.isSoldierControlAutomatic() && civilisation.hasSoldierOrder()) {
				return new BlockPos(civilisation.getSoldierOrderBlockX() + spreadX, recoveryY,
						civilisation.getSoldierOrderBlockZ() + spreadZ);
			}
			Providence assigned = providenceById(record.commandPostTargetProvidenceId);
			if (assigned != null && assigned.getCity() != null) {
				return new BlockPos(assigned.getCity().getBlockX() + spreadX, recoveryY,
						assigned.getCity().getBlockZ() + spreadZ);
			}
		}
		return new BlockPos(home.getBlockX() + spreadX, recoveryY, home.getBlockZ() + spreadZ);
	}

	private void tickEmergencyClimb(ServerLevel level, PathfinderMob villager, WorkerRecord record, BlockPos target) {
		if (record.navigationAssistTicks <= 0 || target == null)
			return;
		record.navigationAssistTicks--;
		villager.getNavigation().stop();
		applyEmergencyMotion(villager, target, EMERGENCY_CLIMB_HORIZONTAL_ASSIST, EMERGENCY_CLIMB_UPWARD_ASSIST);

		if (ticks % EMERGENCY_CARVE_INTERVAL_TICKS == Math.floorMod(record.assignmentIndex,
				EMERGENCY_CARVE_INTERVAL_TICKS)) {
			carveImmediateClimbStep(level, villager, record, target);
		}

		if (record.navigationAssistTicks <= 0) {
			record.stuckChecks = 0;
			issueNavigation(level, villager, target);
		}
	}

	private void applyEmergencyMotion(PathfinderMob villager, BlockPos target, double horizontalAssist,
			double upwardAssist) {
		double dx = target.getX() + 0.5 - villager.getX();
		double dz = target.getZ() + 0.5 - villager.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal < 0.001) {
			// Deterministic fallback so a villager directly below its target still
			// chooses a wall instead of bobbing vertically in place.
			dx = 1.0;
			dz = 0.0;
			horizontal = 1.0;
		}
		Vec3 motion = villager.getDeltaMovement();
		double assistX = dx / horizontal * horizontalAssist;
		double assistZ = dz / horizontal * horizontalAssist;
		villager.setDeltaMovement(motion.x * 0.45 + assistX, Math.max(motion.y, upwardAssist),
				motion.z * 0.45 + assistZ);
	}

	/**
	 * Creates at most one immediate one-block stair opening. No radius search is
	 * performed. The solid block in front is kept as the tread; only the two blocks
	 * above it are cleared when they are ordinary natural obstacles.
	 */
	private void carveImmediateClimbStep(ServerLevel level, PathfinderMob villager, WorkerRecord record,
			BlockPos target) {
		if (ticks - record.lastObstacleBreakTick < EMERGENCY_CARVE_INTERVAL_TICKS)
			return;
		BlockPos here = villager.blockPosition();
		int primaryX = Integer.compare(target.getX(), here.getX());
		int primaryZ = Integer.compare(target.getZ(), here.getZ());
		if (primaryX != 0 && primaryZ != 0) {
			if (Math.abs(target.getX() - here.getX()) >= Math.abs(target.getZ() - here.getZ()))
				primaryZ = 0;
			else
				primaryX = 0;
		}
		if (primaryX == 0 && primaryZ == 0)
			primaryX = 1;

		int[][] directions = new int[][] { { primaryX, primaryZ }, { -primaryZ, primaryX }, { primaryZ, -primaryX },
				{ -primaryX, -primaryZ } };
		for (int[] direction : directions) {
			int sx = direction[0];
			int sz = direction[1];
			if (sx == 0 && sz == 0)
				continue;
			BlockPos tread = here.offset(sx, 0, sz);
			BlockState treadState = level.getBlockState(tread);
			if (treadState.isAir() || !level.getFluidState(tread).isEmpty())
				continue;

			BlockPos lowerHead = tread.above();
			BlockPos upperHead = tread.above(2);
			if (!canClearEmergencyBlock(level, lowerHead) || !canClearEmergencyBlock(level, upperHead))
				continue;

			boolean changed = clearEmergencyBlock(level, record, lowerHead);
			changed |= clearEmergencyBlock(level, record, upperHead);
			if (changed) {
				villager.swing(InteractionHand.MAIN_HAND);
				record.lastObstacleBreakTick = ticks;
				dirty = true;
			}
			return;
		}
	}

	private boolean canClearEmergencyBlock(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || !level.getFluidState(pos).isEmpty())
			return true;
		return level.getBlockEntity(pos) == null && isWorkerBreakableObstacle(state);
	}

	private boolean clearEmergencyBlock(ServerLevel level, WorkerRecord record, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || !level.getFluidState(pos).isEmpty())
			return false;
		if (level.getBlockEntity(pos) != null || !isWorkerBreakableObstacle(state))
			return false;
		ResourceType recovered = obstacleResource(state);
		if (!level.destroyBlock(pos, false))
			return false;
		if (recovered != null)
			spawnResourceDrop(level, record.civilisationId, recovered, 1, pos);
		return true;
	}

	/**
	 * Rebuilds the live navigator from persistent worker/brain state after reload.
	 */
	private void rehydrateWorkerNavigation(ServerLevel level, PathfinderMob villager, WorkerRecord record) {
		if (level == null || villager == null || record == null)
			return;
		WorkerBrainState brain = ensureBrain(record);
		record.needsNavigationRehydrate = false;

		// A Minecraft Path object is deliberately never persisted. Stop whatever the
		// vanilla backing entity may have loaded and reconstruct the GS-owned leg.
		villager.getNavigation().stop();
		WorkerPlannerService.getInstance().forget(record.uuid);
		brain.routeRequestPending = false;
		brain.escapeRequestPending = false;
		brain.route.clear();
		brain.routeIndex = 0;
		brain.escapeRoute.clear();
		brain.escapeIndex = 0;
		brain.escaping = false;
		brain.planGeneration++;

		record.lastNavigationIssueTick = -HUMANOID_NAV_HEARTBEAT_TICKS;
		record.lastNavigationDebugLogTick = -NAVIGATION_DEBUG_LOG_INTERVAL_TICKS;
		record.lastDepotRecoveryScanTick = -DEPOT_RECOVERY_SCAN_INTERVAL_TICKS;
		record.lastNavigationCheckTick = -NAVIGATION_STUCK_CHECK_TICKS;
		record.lastTargetDistanceSquared = -1.0;
		record.navigationSampleX = floor(villager.getX());
		record.navigationSampleY = floor(villager.getY());
		record.navigationSampleZ = floor(villager.getZ());
		record.stuckChecks = 0;

		BlockPos resume = null;
		if (record.hasMoveTarget) {
			resume = new BlockPos(record.moveTargetX, record.moveTargetY, record.moveTargetZ);
		} else if (brain.hasGoal) {
			BlockPos declared = targetPos(record);
			if (declared != null) {
				// Rebuild from the durable job target rather than relying on a stale
				// intermediate waypoint from the previous process.
				brain.setGoal(record.targetKind, declared.getX(), declared.getY(), declared.getZ(),
						deriveIntent(record));
				requestStrategicRoute(level, villager, record, brain);
				resume = currentBrainNavigationTarget(level, villager, record, declared);
				if (resume != null) {
					record.hasMoveTarget = true;
					record.moveTargetX = resume.getX();
					record.moveTargetY = resume.getY();
					record.moveTargetZ = resume.getZ();
				}
			}
		}

		if (resume != null) {
			BlockPos navigable = normaliseLocalNavigationTarget(level, villager, record, resume);
			if (navigable != null) {
				record.hasMoveTarget = true;
				record.moveTargetX = navigable.getX();
				record.moveTargetY = navigable.getY();
				record.moveTargetZ = navigable.getZ();
				issueNavigation(level, villager, navigable);
			}
		}
		dirty = true;
	}

	private void discardOrphanedFarmerGoal(PathfinderMob villager, WorkerRecord record) {
		if (record == null || parseJob(record.job) != VillagerJob.FARMER || record.targetKind != null)
			return;
		WorkerBrainState brain = ensureBrain(record);
		if (!brain.hasGoal)
			return;
		if (!WorkerIntent.BUILD_OR_OPERATE_DISTRICT.name().equals(brain.intent))
			return;
		BlockPos stale = new BlockPos(brain.goalX, brain.goalY, brain.goalZ);
		logNavigationIssue(record, villager, stale, "discard-orphaned-farmer-goal", NavigationFailure.NO_PROGRESS);
		finishLocalTaskMovement(villager, record);
	}

	/**
	 * Stops only the movement executor when the worker is already close enough to
	 * perform its durable interaction. The profession tick keeps targetKind and
	 * does the actual farming/mining/deposit/crafting action on its next think
	 * slot.
	 */
	private void settleSatisfiedLocalMovement(ServerLevel level, PathfinderMob villager, WorkerRecord record) {
		if (level == null || villager == null || record == null || !record.hasMoveTarget)
			return;
		WorkerBrainState brain = ensureBrain(record);
		BlockPos durable = targetPos(record);
		double range = 0.0;
		String kind = record.targetKind;
		boolean interactionSatisfied = false;
		if (durable != null && kind != null) {
			if ("tree".equals(kind)) {
				// Tree targets use the lumberjack's real reach test rather than a
				// spherical distance. Otherwise a villager 5 blocks horizontally
				// from a trunk could have movement cancelled even though it cannot
				// actually chop that trunk yet.
				interactionSatisfied = canReachTree(level, villager, durable, LUMBERJACK_CHOP_REACH);
			} else {
				range = switch (kind) {
				case "farm" -> 2.6;
				case "farm_irrigation", "farm_bucket_supply", "depot_return" -> 3.2;
				case "fetch_farm_water" -> 3.0;
				case "ore" -> 5.5;
				case "strip_mine" -> 3.2;
				case "work_drop" -> WORK_DROP_PICKUP_REACH;
				case "bone_meal_drop" -> 3.2;
				case "bone_meal_supply" -> 3.2;
				default -> 0.0;
				};
				interactionSatisfied = range > 0.0 && near(villager, durable, range);
			}
		} else if (brain.hasGoal) {
			VillagerJob job = parseJob(record.job);
			WorkerIntent intent = deriveIntent(record);
			// Factory movement with no targetKind is used both for the workshop
			// origin and for its crafting/furnace stations. Use the tighter production
			// radius here; construction itself accepts 4.8 blocks on its profession tick
			// and then explicitly finishes that movement leg. A 4.8 watchdog radius
			// would cancel a crafting-table approach while still too far away to craft.
			if (job == VillagerJob.FACTORY_BUILDER && intent == WorkerIntent.BUILD_OR_OPERATE_DISTRICT) {
				durable = new BlockPos(brain.goalX, brain.goalY, brain.goalZ);
				range = 3.0;
				interactionSatisfied = near(villager, durable, range);
			}
		}
		if (durable == null || !interactionSatisfied)
			return;

		// Crossing the interaction-radius boundary is a complete local movement
		// leg. Use the same hard completion boundary as an actual profession action
		// so an old asynchronous route/recovery assist cannot reinstall a waypoint
		// after the farmer/chest/workstation is already usable. targetKind remains
		// intact; the profession tick consumes the durable interaction next.
		finishLocalTaskMovement(villager, record);
	}

	/**
	 * Absolute anti-freeze watchdog. Normal WorkerBrain/pathfinding remains the
	 * first choice, but a humanoid that is supposed to move is never permitted to
	 * wait on a planner future or dead local Path indefinitely.
	 */
	private void enforceWorkerLiveness(ServerLevel level, PathfinderMob villager, WorkerRecord record) {
		if (level == null || villager == null || record == null)
			return;
		WorkerBrainState brain = ensureBrain(record);

		// Planner computations are advisory. If one stalls (including an optional DAX
		// path), cancel it and let direct local movement continue while a fresh plan is
		// requested later. This prevents a whole profession from queueing behind one
		// wedged asynchronous request.
		if ((brain.routeRequestPending || brain.escapeRequestPending)
				&& ticks - brain.lastPlanTick >= PLANNER_REQUEST_TIMEOUT_TICKS) {
			BlockPos timedOutGoal = brain.hasGoal ? new BlockPos(brain.goalX, brain.goalY, brain.goalZ)
					: activeLivenessTarget(record);
			logNavigationIssue(record, villager, timedOutGoal, "planner-timeout", NavigationFailure.PATH_NOT_FOUND);
			WorkerPlannerService.getInstance().forget(record.uuid);
			brain.planGeneration++;
			brain.routeRequestPending = false;
			brain.escapeRequestPending = false;
			brain.route.clear();
			brain.routeIndex = 0;
			brain.escapeRoute.clear();
			brain.escapeIndex = 0;
			brain.escaping = false;
			record.lastNavigationIssueTick = -HUMANOID_NAV_HEARTBEAT_TICKS;
			dirty = true;
		}

		boolean movementExpected = record.hasMoveTarget || brain.routeRequestPending || brain.escapeRequestPending
				|| record.mineTransitDirection != 0 || record.nonMinerMineAvoidanceActive;
		if (!movementExpected) {
			record.livenessInitialised = false;
			record.livenessRecoveryStage = 0;
			return;
		}

		double x = villager.getX();
		double y = villager.getY();
		double z = villager.getZ();
		if (!record.livenessInitialised) {
			record.livenessInitialised = true;
			record.livenessX = x;
			record.livenessY = y;
			record.livenessZ = z;
			record.lastLivenessMovementTick = ticks;
			record.livenessRecoveryStage = 0;
			return;
		}
		double dx = x - record.livenessX;
		double dy = y - record.livenessY;
		double dz = z - record.livenessZ;
		if (dx * dx + dy * dy + dz * dz >= LIVENESS_MOVEMENT_EPSILON_SQ) {
			record.livenessX = x;
			record.livenessY = y;
			record.livenessZ = z;
			record.lastLivenessMovementTick = ticks;
			record.livenessRecoveryStage = 0;
			return;
		}

		long frozenTicks = ticks - record.lastLivenessMovementTick;
		if (frozenTicks >= LIVENESS_FULL_RESET_TICKS && record.livenessRecoveryStage < 3) {
			record.livenessRecoveryStage = 3;
			// Ten seconds of zero physical progress means the local leg itself is bad.
			// Rebuilding the identical goal forever was the last source of permanently
			// frozen villagers. Drop only transient navigation intent and let the
			// profession recompute a fresh plot/tree/workstation/road leg next tick.
			abandonFrozenLocalLeg(level, villager, record);
			record.lastLivenessMovementTick = ticks;
			record.livenessRecoveryStage = 0;
		} else if (frozenTicks >= LIVENESS_HARD_RECOVERY_TICKS && record.livenessRecoveryStage < 2) {
			record.livenessRecoveryStage = 2;
			rebuildFrozenWorkerNavigation(level, villager, record, false);
		} else if (frozenTicks >= LIVENESS_SOFT_RECOVERY_TICKS && record.livenessRecoveryStage < 1) {
			record.livenessRecoveryStage = 1;
			BlockPos target = activeLivenessTarget(record);
			if (target != null) {
				issueNavigation(level, villager, target);
				nudgeWorkerToward(villager, record, target);
			}
		}
	}

	private BlockPos activeLivenessTarget(WorkerRecord record) {
		if (record == null)
			return null;
		if (record.hasMoveTarget) {
			return new BlockPos(record.moveTargetX, record.moveTargetY, record.moveTargetZ);
		}
		WorkerBrainState brain = ensureBrain(record);
		if (brain.hasGoal)
			return new BlockPos(brain.goalX, brain.goalY, brain.goalZ);
		return targetPos(record);
	}

	private void abandonFrozenLocalLeg(ServerLevel level, PathfinderMob villager, WorkerRecord record) {
		if (record == null || villager == null)
			return;
		WorkerBrainState brain = ensureBrain(record);
		BlockPos failed = activeLivenessTarget(record);
		if (failed != null) {
			logNavigationIssue(record, villager, failed, "abandon-frozen-local-leg", NavigationFailure.NO_PROGRESS);
			brain.rememberFailure(floor(villager.getX()), floor(villager.getY()), floor(villager.getZ()),
					NavigationFailure.NO_PROGRESS, ticks);
		}

		// A blocked farm cell should not monopolise a farmer forever. Skip one cell;
		// the next pass can revisit it after terrain/crops change. Other professions
		// simply recompute their ordinary durable assignment.
		if (parseJob(record.job) == VillagerJob.FARMER && ("farm".equals(record.targetKind)
				|| (record.targetKind == null && WorkerIntent.BUILD_OR_OPERATE_DISTRICT.name().equals(brain.intent)))) {
			record.workCounter++;
			record.farmerNoWorkCells = 0;
			record.farmerWaitingForCrops = false;
		}
		if ("work_drop".equals(record.targetKind))
			releaseClaimedWorkDrops(record);

		WorkerPlannerService.getInstance().forget(record.uuid);
		brain.planGeneration++;
		brain.routeRequestPending = false;
		brain.escapeRequestPending = false;
		brain.route.clear();
		brain.routeIndex = 0;
		brain.escapeRoute.clear();
		brain.escapeIndex = 0;
		brain.escaping = false;
		brain.clearGoal();
		clearTarget(record);
		clearMoveTarget(record);
		record.navigationAssistTicks = 0;
		record.stuckChecks = 0;
		record.lastTargetDistanceSquared = -1.0;
		record.livenessInitialised = false;
		villager.getNavigation().stop();
		dirty = true;
	}

	/**
	 * Strong recovery that discards only transient route execution, never the job.
	 */
	private void rebuildFrozenWorkerNavigation(ServerLevel level, PathfinderMob villager, WorkerRecord record,
			boolean fullReset) {
		WorkerBrainState brain = ensureBrain(record);
		BlockPos durable = targetPos(record);
		if (durable == null && brain.hasGoal) {
			durable = new BlockPos(brain.goalX, brain.goalY, brain.goalZ);
		}
		if (durable == null && record.hasMoveTarget) {
			durable = new BlockPos(record.moveTargetX, record.moveTargetY, record.moveTargetZ);
		}

		WorkerPlannerService.getInstance().forget(record.uuid);
		brain.planGeneration++;
		brain.routeRequestPending = false;
		brain.escapeRequestPending = false;
		brain.route.clear();
		brain.routeIndex = 0;
		brain.escapeRoute.clear();
		brain.escapeIndex = 0;
		brain.escaping = false;
		record.navigationAssistTicks = 0;
		record.stuckChecks = 0;
		record.lastTargetDistanceSquared = -1.0;
		record.lastNavigationCheckTick = -NAVIGATION_STUCK_CHECK_TICKS;
		record.lastNavigationIssueTick = -HUMANOID_NAV_HEARTBEAT_TICKS;
		villager.getNavigation().stop();

		if (durable == null) {
			clearMoveTarget(record);
			if (fullReset)
				brain.clearGoal();
			dirty = true;
			return;
		}

		// On the strongest recovery, rebuild the high-level goal from the durable job
		// target as well. This fixes stale goal/target pairings without touching the
		// worker's profession, district, inventory or actual work assignment.
		if (fullReset) {
			brain.setGoal(record.targetKind == null ? deriveIntent(record).name() : record.targetKind, durable.getX(),
					durable.getY(), durable.getZ(), deriveIntent(record));
		}
		BlockPos local = currentBrainNavigationTarget(level, villager, record, durable);
		local = normaliseLocalNavigationTarget(level, villager, record, local == null ? durable : local);
		if (local == null)
			local = durable;
		record.hasMoveTarget = true;
		record.moveTargetX = local.getX();
		record.moveTargetY = local.getY();
		record.moveTargetZ = local.getZ();
		issueNavigation(level, villager, local);
		nudgeWorkerToward(villager, record, local);
		if (horizontalDistanceSquared(villager.blockPosition(), durable) >= STRATEGIC_ROUTE_THRESHOLD
				* (double) STRATEGIC_ROUTE_THRESHOLD) {
			requestStrategicRoute(level, villager, record, brain);
		}
		dirty = true;
	}

	/**
	 * Equivalent to the small physical impulse the user observed when hitting a
	 * frozen worker, but directed toward the GS waypoint and far too small to
	 * damage or teleport the entity. Collision/gravity still apply normally.
	 */
	private void nudgeWorkerToward(PathfinderMob villager, WorkerRecord record, BlockPos target) {
		if (villager == null || target == null)
			return;
		double dx = target.getX() + 0.5 - villager.getX();
		double dz = target.getZ() + 0.5 - villager.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal < 0.05)
			return;
		double forwardX = dx / horizontal;
		double forwardZ = dz / horizontal;
		// A small deterministic sideways component prevents a cluster of workers
		// sharing the same corridor from all pushing in exactly the same direction.
		// This mimics the beneficial displacement seen when the player hits one,
		// without damage or random teleportation.
		double sideSign = record == null || (record.assignmentIndex & 1) == 0 ? 1.0 : -1.0;
		double side = 0.055 * sideSign;
		double nx = forwardX * LIVENESS_NUDGE_SPEED - forwardZ * side;
		double nz = forwardZ * LIVENESS_NUDGE_SPEED + forwardX * side;
		Vec3 old = villager.getDeltaMovement();
		double upward = target.getY() > villager.getY() + 0.5 ? Math.max(old.y, 0.18) : old.y;
		villager.setDeltaMovement(nx, upward, nz);
	}

	private void recoverWorkerAfterTickFailure(ServerLevel level, WorkerRecord record, RuntimeException error) {
		if (record == null)
			return;
		record.consecutiveTickErrors++;
		WorkerBrainState brain = ensureBrain(record);
		WorkerPlannerService.getInstance().forget(record.uuid);
		brain.planGeneration++;
		brain.routeRequestPending = false;
		brain.escapeRequestPending = false;
		brain.route.clear();
		brain.routeIndex = 0;
		brain.escapeRoute.clear();
		brain.escapeIndex = 0;
		brain.escaping = false;
		record.needsNavigationRehydrate = true;
		record.hasTerrainEscapeTarget = false;
		record.navigationAssistTicks = 0;
		record.stuckChecks = 0;
		record.lastTargetDistanceSquared = -1.0;
		dirty = true;

		// Log at most once every ten seconds for a repeatedly bad worker. The rest of
		// the population continues ticking even while this individual self-recovers.
		if (ticks - record.lastTickErrorLogTick >= 200L || record.lastTickErrorLogTick == 0L) {
			record.lastTickErrorLogTick = ticks;
			System.err.println("Grand Strategy recovered worker " + record.uuid + " after AI tick failure ("
					+ error.getClass().getSimpleName() + "): " + String.valueOf(error.getMessage()));
		}
	}

	private void maintainNavigation(ServerLevel level, PathfinderMob villager, WorkerRecord record) {
		WorkerBrainState brain = ensureBrain(record);
		// A high-level objective must never exist without a local movement leg. Several
		// work/supply branches legitimately clear the local path; older builds could
		// then leave the humanoid standing forever even though WorkerBrain still knew
		// where it was supposed to go. Reconstruct that leg immediately.
		if (!record.hasMoveTarget) {
			if (!brain.hasGoal)
				return;
			// Only resurrect a goal that is still the worker's declared work target.
			// Supply trips and completed work often clear the local leg intentionally;
			// blindly restoring every old brain goal would send a worker back to a
			// chest/tree/plot it had already finished using.
			BlockPos declared = targetPos(record);
			boolean declaredGoal = declared != null && declared.getX() == brain.goalX && declared.getY() == brain.goalY
					&& declared.getZ() == brain.goalZ;
			if (!declaredGoal)
				return;
			BlockPos finalGoal = new BlockPos(brain.goalX, brain.goalY, brain.goalZ);
			requestStrategicRoute(level, villager, record, brain);
			BlockPos resume = currentBrainNavigationTarget(level, villager, record, finalGoal);
			setLocalMoveTarget(level, villager, record, resume);
			if (!record.hasMoveTarget)
				return;
		}
		BlockPos target = new BlockPos(record.moveTargetX, record.moveTargetY, record.moveTargetZ);

		// Decorative ground clutter such as leaf litter is not a strategic obstacle.
		// Remove it immediately from the current/next walking cells rather than
		// waiting for the ordinary stuck detector or a road builder.
		if (ticks % 2L == Math.floorMod(record.assignmentIndex, 2)) {
			clearImmediateNaturalPathClutter(level, villager, record, target);
		}

		if (brain.escaping)
			prepareEscapeWaypoint(level, villager, record, target);

		if (near(villager, target, 2.2)) {
			record.stuckChecks = 0;
			brain.lastProgressTick = ticks;
			brain.lastProgressX = floor(villager.getX());
			brain.lastProgressY = floor(villager.getY());
			brain.lastProgressZ = floor(villager.getZ());

			if (advanceBrainWaypoint(level, villager, record))
				return;

			if (brain.hasGoal) {
				BlockPos finalGoal = new BlockPos(brain.goalX, brain.goalY, brain.goalZ);
				if (near(villager, finalGoal, 2.6)) {
					clearMoveTarget(record);
					brain.clearGoal();
					dirty = true;
					return;
				}
				requestStrategicRoute(level, villager, record, brain);
				BlockPos fallback = currentBrainNavigationTarget(level, villager, record, finalGoal);
				setLocalMoveTarget(level, villager, record, fallback);
				return;
			}
			clearMoveTarget(record);
			return;
		}

		// Vanilla navigation is an executor only. It is allowed to solve the current
		// short segment, but not to decide the worker's long-term objective.
		// Reassert a GS-owned local leg quickly when vanilla navigation has given up.
		// Also reassert after a detected no-progress episode, because a vanilla brain
		// path may otherwise remain active even though it is not the GS destination.
		int reassertTicks = villager instanceof GrandStrategyHumanoidEntity ? HUMANOID_NAV_HEARTBEAT_TICKS
				: NAVIGATION_REASSERT_TICKS;
		if (ticks - record.lastNavigationIssueTick >= reassertTicks
				&& (villager.getNavigation().isDone() || record.stuckChecks > 0)) {
			issueNavigation(level, villager, target);
		}

		// Soft immediate obstructions remain cheap to clear. Hard failures are no
		// longer allowed to erase the worker's actual task; they feed the planner.
		if (!brain.escaping
				&& ticks % NAVIGATION_OBSTACLE_SCAN_TICKS == Math.floorMod(record.assignmentIndex,
						NAVIGATION_OBSTACLE_SCAN_TICKS)
				&& tryBreakNavigationObstacle(level, villager, target, record, false)) {
			issueNavigation(level, villager, target);
			return;
		}

		if (ticks - record.lastNavigationCheckTick < NAVIGATION_STUCK_CHECK_TICKS)
			return;
		record.lastNavigationCheckTick = ticks;
		int x = floor(villager.getX());
		int y = floor(villager.getY());
		int z = floor(villager.getZ());
		double targetDistance = villager.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
		boolean movedBlock = x != record.navigationSampleX || y != record.navigationSampleY
				|| z != record.navigationSampleZ;
		boolean actuallyCloser = record.lastTargetDistanceSquared < 0.0
				|| targetDistance + 0.35 < record.lastTargetDistanceSquared;
		if (!movedBlock || !actuallyCloser) {
			record.stuckChecks++;
		} else {
			record.stuckChecks = 0;
			brain.lastProgressTick = ticks;
			brain.lastProgressX = x;
			brain.lastProgressY = y;
			brain.lastProgressZ = z;
			if (brain.consecutiveFailures > 0)
				brain.consecutiveFailures--;
			CivilisationTrafficManager.getInstance().noteSuccess(record.civilisationId, x, z, ticks);
		}
		record.navigationSampleX = x;
		record.navigationSampleY = y;
		record.navigationSampleZ = z;
		record.lastTargetDistanceSquared = targetDistance;
		if (record.stuckChecks <= 0)
			return;

		NavigationFailure failure = classifyNavigationFailure(level, villager, target, record);

		// A first failure requests a different strategic route and, when appropriate,
		// shared road-builder assistance. The final goal remains untouched.
		if (record.stuckChecks == 1) {
			logNavigationIssue(record, villager, target, "stuck", failure);
			brain.rememberFailure(x, y, z, failure, ticks);
			CivilisationTrafficManager.InfrastructureNeed sharedNeed = CivilisationTrafficManager.getInstance()
					.recordFailure(record.civilisationId, x, y, z, failure, ticks);
			if (failure == NavigationFailure.BLOCKED_WALL || failure == NavigationFailure.CLIFF_UP
					|| failure == NavigationFailure.STRUCTURE_BLOCKED) {
				requestTravelAssistance(record, villager.blockPosition());
			}
			if (sharedNeed != null) {
				requestTravelAssistance(record, new BlockPos(sharedNeed.x(), sharedNeed.y(), sharedNeed.z()));
			}
			if (!brain.escaping) {
				brain.route.clear();
				brain.routeIndex = 0;
				brain.routeRequestPending = false;
				requestStrategicRoute(level, villager, record, brain);
			}
			dirty = true;
		}

		// Replanning can legitimately change the local waypoint and therefore reset
		// record.stuckChecks. The old code consequently allowed a worker to loop
		// forever as "first failure -> replan -> first failure" at the same physical
		// spot. Count recent remembered failures around the body independently of the
		// current waypoint. Repeated episodes escalate to physical local recovery.
		int repeatedEpisodes = recentNavigationFailureEpisodes(brain, villager.blockPosition(), 5, 240L);

		// Physical output on the ground is optional logistics, never a reason to pin a
		// profession forever. After two failures near the same claimed drop, release
		// the reservation and remember the area briefly so this worker chooses another
		// item or returns to its actual profession. The item itself is not deleted.
		if ("work_drop".equals(record.targetKind) && repeatedEpisodes >= 2) {
			logNavigationIssue(record, villager, target, "abandon-unreachable-work-drop", failure);
			releaseClaimedWorkDrops(record);
			clearTarget(record);
			finishLocalTaskMovement(villager, record);
			return;
		}

		// Farm work is dense, local and replaceable. If the same three-block area has
		// failed twice, continuing to detour/nudge around one cell causes the exact
		// "Recovering movement" oscillation visible in a planted field. Skip that one
		// cell and let the farmer service the next cell; it will revisit the skipped
		// location on a later pass.
		if (parseJob(record.job) == VillagerJob.FARMER
				&& WorkerIntent.BUILD_OR_OPERATE_DISTRICT.name().equals(brain.intent) && repeatedEpisodes >= 2) {
			logNavigationIssue(record, villager, target, "skip-unreachable-farm-cell", failure);
			record.workCounter++;
			record.farmerNoWorkCells = 0;
			record.farmerWaitingForCrops = false;
			clearTarget(record);
			finishLocalTaskMovement(villager, record);
			return;
		}

		if (!brain.escaping && repeatedEpisodes >= 2) {
			boolean allowHardTerrain = parseJob(record.job) == VillagerJob.ROAD_BUILDER;
			if (tryBreakNavigationObstacle(level, villager, target, record, allowHardTerrain)) {
				record.stuckChecks = 0;
				issueNavigation(level, villager, target);
				return;
			}

			// NO_PROGRESS is frequently crowding or a bad corner rather than a solid
			// wall. Step sideways to a nearby standable cell while retaining the real
			// WorkerBrain goal; after reaching it the normal route resumes.
			if (failure == NavigationFailure.NO_PROGRESS || failure == NavigationFailure.PATH_NOT_FOUND
					|| failure == NavigationFailure.CLIFF_UP || failure == NavigationFailure.CLIFF_DOWN
					|| failure == NavigationFailure.CROWD_BLOCKED) {
				BlockPos detour = findLocalNavigationDetour(level, villager, record, target);
				if (detour != null) {
					setLocalMoveTarget(level, villager, record, detour);
					nudgeWorkerToward(villager, record, detour);
					return;
				}
			}

			// Existing v6.27 self-rescue remains the last resort for ordinary workers.
			// Trigger it from repeated *episodes* as well as a single uninterrupted
			// stuckChecks run, otherwise a replan can keep postponing it forever.
			if (repeatedEpisodes >= 4 && tryDigNonDownwardEscape(level, villager, target, record)) {
				record.stuckChecks = 0;
				issueNavigation(level, villager, target);
				return;
			}
		}

		// A steep but otherwise simple step still gets a short physical assist.
		if (!brain.escaping && record.stuckChecks >= 2 && target.getY() - y >= 2) {
			record.navigationAssistTicks = EMERGENCY_CLIMB_TICKS;
			villager.getNavigation().stop();
			dirty = true;
			return;
		}

		// After repeated failure, snapshot a small 3-D neighbourhood and let the
		// background escape planner find a route that may go around or excavate
		// horizontally/upward. This is a sub-plan: the original goal is preserved.
		if (!brain.escaping && record.stuckChecks >= ESCAPE_REQUEST_FAILURE_THRESHOLD) {
			requestEscapePlan(level, villager, record, failure);
			if (brain.escapeRequestPending) {
				villager.getNavigation().stop();
				return;
			}
		}

		// While no asynchronous escape result is available, retain the old bounded
		// physical fallbacks as a safety net. They may only modify safe natural blocks.
		if (!brain.escaping && record.stuckChecks >= OBSTACLE_BREAK_STUCK_THRESHOLD
				&& tryBreakNavigationObstacle(level, villager, target, record, true)) {
			record.stuckChecks = Math.max(0, record.stuckChecks - 1);
			issueNavigation(level, villager, target);
			return;
		}
		if (!brain.escaping && record.stuckChecks >= SELF_ESCAPE_DIG_STUCK_THRESHOLD
				&& tryDigNonDownwardEscape(level, villager, target, record)) {
			record.stuckChecks = 0;
			issueNavigation(level, villager, target);
			return;
		}

		// Crucially, reaching the old failure limit no longer clears targetKind or
		// forgets the job. Penalise this location, stop the bad local path and replan.
		if (record.stuckChecks >= NAVIGATION_STUCK_CHECK_LIMIT) {
			villager.getNavigation().stop();
			brain.rememberFailure(x, y, z, failure, ticks);
			brain.route.clear();
			brain.routeIndex = 0;
			brain.routeRequestPending = false;
			clearMoveTarget(record);
			record.stuckChecks = 0;
			if (record.inMine)
				record.mineProgress++;
			if (brain.hasGoal) {
				requestStrategicRoute(level, villager, record, brain);
				BlockPos finalGoal = new BlockPos(brain.goalX, brain.goalY, brain.goalZ);
				BlockPos fallback = currentBrainNavigationTarget(level, villager, record, finalGoal);
				setLocalMoveTarget(level, villager, record, fallback);
			}
			dirty = true;
		}
	}

	private int recentNavigationFailureEpisodes(WorkerBrainState brain, BlockPos around, int radius, long maxAgeTicks) {
		if (brain == null || around == null || brain.failedLocations == null)
			return 0;
		int count = 0;
		int radiusSquared = radius * radius;
		long oldest = Math.max(0L, ticks - Math.max(1L, maxAgeTicks));
		for (WorkerBrainState.FailedLocation failed : brain.failedLocations) {
			if (failed == null || failed.tick() < oldest)
				continue;
			int dx = failed.x() - around.getX();
			int dz = failed.z() - around.getZ();
			if (dx * dx + dz * dz > radiusSquared)
				continue;
			if (Math.abs(failed.y() - around.getY()) > 5)
				continue;
			count++;
		}
		return count;
	}

	/**
	 * Finds a tiny lateral recovery step around a local no-progress/crowding
	 * failure. It deliberately does not alter WorkerBrain's final goal and never
	 * performs a wide terrain search.
	 */
	private BlockPos findLocalNavigationDetour(ServerLevel level, PathfinderMob villager, WorkerRecord record,
			BlockPos finalDirection) {
		if (level == null || villager == null || record == null || finalDirection == null)
			return null;
		BlockPos here = villager.blockPosition();
		BlockPos best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		for (int radius = 1; radius <= 3; radius++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					if (Math.abs(dx) != radius && Math.abs(dz) != radius)
						continue;
					if (dx == 0 && dz == 0)
						continue;
					int x = here.getX() + dx;
					int z = here.getZ() + dz;
					if (!isChunkLoaded(level, x, z))
						continue;
					if (parseJob(record.job) != VillagerJob.MINER && isMineSurfaceExclusion(record, x, z))
						continue;
					int y = record.inMine ? here.getY()
							: level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
					BlockPos candidate = lowerTargetPastNaturalClutter(level, new BlockPos(x, y, z));
					if (!isSafeNavigationFeet(level, record, candidate))
						continue;
					if (horizontalDistanceSquared(candidate, here) < 0.5)
						continue;
					double score = horizontalDistanceSquared(candidate, finalDirection)
							+ Math.abs(candidate.getY() - here.getY()) * 2.0;
					if (isRoad(level.getBlockState(candidate.below())))
						score -= 2.0;
					if (score < bestScore) {
						bestScore = score;
						best = candidate;
					}
				}
			}
			if (best != null)
				break;
		}
		return best;
	}

	private boolean tryBreakNavigationObstacle(ServerLevel level, PathfinderMob villager, BlockPos target,
			WorkerRecord record, boolean allowHardTerrain) {
		if (ticks - record.lastObstacleBreakTick < OBSTACLE_BREAK_COOLDOWN_TICKS)
			return false;

		BlockPos here = villager.blockPosition();
		int stepX = Integer.compare(target.getX(), here.getX());
		int stepZ = Integer.compare(target.getZ(), here.getZ());
		if (stepX == 0 && stepZ == 0)
			return false;

		// Check a short corridor rather than one guessed diagonal block. A path may
		// be blocked on the X side, Z side, at head height, or one block farther
		// ahead. The old two-position test missed all of those common cases.
		List<BlockPos> candidates = new ArrayList<>();
		addObstacleColumn(candidates, here.offset(stepX, 0, stepZ));
		if (stepX != 0 && stepZ != 0) {
			addObstacleColumn(candidates, here.offset(stepX, 0, 0));
			addObstacleColumn(candidates, here.offset(0, 0, stepZ));
		}
		addObstacleColumn(candidates, here.offset(stepX * 2, 0, stepZ * 2));
		// A worker can occasionally be partially embedded in foliage after terrain
		// generation/loading, or stand underneath a low canopy whose upper leaves
		// continually defeat the next path calculation. Clear the whole reachable
		// column above the worker rather than only its immediate head block.
		candidates.add(here);
		for (int y = 1; y <= WORKER_OVERHEAD_CLEARANCE; y++) {
			candidates.add(here.above(y));
		}

		// Every profession is physically capable of chopping a tree that blocks its
		// route. Vanilla pathfinding may stop several blocks short of a trunk, so the
		// old one/two-block probe could leave farmers and other workers permanently
		// "walking" toward an unreachable target. Search a short forward corridor for
		// logs/leaves and remove one ordinary block per action. Only lumberjacks keep
		// the special long reach, connected-tree felling, canopy clearing and
		// replanting.
		addNearbyTreeRouteObstacles(level, candidates, here, stepX, stepZ);

		for (BlockPos pos : candidates) {
			BlockState state = level.getBlockState(pos);
			if (!isWorkerBreakableObstacle(state))
				continue;
			if ((isLog(state) || isLeaves(state))
					&& villager.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 4.5 * 4.5)
				continue;
			boolean soft = isLog(state) || isLeaves(state) || isNaturalPathClutter(state) || state.is(Blocks.SNOW)
					|| state.is(Blocks.SNOW_BLOCK);

			// Generic stuck recovery must never turn every profession into an
			// uncontrolled miner. Non-road workers clear only foliage/snow. Road
			// builders are the one surface profession allowed to grade hard natural
			// terrain, and even they may only clear a safe wall/step with intact
			// support beneath it. Actual miners continue using their dedicated mine
			// tunnel code instead of this navigation fallback.
			if (!soft) {
				if (!allowHardTerrain)
					continue;
				if (parseJob(record.job) != VillagerJob.ROAD_BUILDER) {
					requestTravelAssistance(record, pos);
					continue;
				}
				if (!safeRoadBuilderTerrainClear(level, villager, pos))
					continue;
			}
			if (!claimObstacle(record, pos))
				continue;

			ResourceType recovered = obstacleResource(state);
			if (!level.destroyBlock(pos, false))
				continue;
			obstacleClaims.remove(obstacleKey(pos));
			if (recovered != null) {
				spawnResourceDrop(level, record.civilisationId, recovered, 1, pos);
			}
			villager.swing(InteractionHand.MAIN_HAND);
			record.lastObstacleBreakTick = ticks;
			dirty = true;
			return true;
		}
		return false;
	}

	private void addNearbyTreeRouteObstacles(ServerLevel level, List<BlockPos> candidates, BlockPos here, int stepX,
			int stepZ) {
		if (level == null || candidates == null || here == null)
			return;
		if (stepX == 0 && stepZ == 0)
			return;

		// Keep this deliberately modest. Non-lumberjacks can chop only nearby blocks
		// that are plausibly in the walking corridor, one block at a time through the
		// normal obstacle cooldown. This is route clearing, not a second lumberjack AI.
		final int forwardReach = 4;
		for (int distance = 1; distance <= forwardReach; distance++) {
			BlockPos centre = here.offset(stepX * distance, 0, stepZ * distance);
			addTreeRouteColumn(level, candidates, centre);

			// A* / vanilla paths commonly skirt a trunk by one block. Include that
			// shoulder so a tree whose collision box blocks the chosen corner can be
			// opened without broad area-clearing.
			if (stepX == 0) {
				addTreeRouteColumn(level, candidates, centre.east());
				addTreeRouteColumn(level, candidates, centre.west());
			} else if (stepZ == 0) {
				addTreeRouteColumn(level, candidates, centre.north());
				addTreeRouteColumn(level, candidates, centre.south());
			}
		}
	}

	private void addTreeRouteColumn(ServerLevel level, List<BlockPos> candidates, BlockPos feet) {
		// Include one block below the worker's current feet height for small terrain
		// steps, then only its ordinary body/head reach. Do not scan or fell the whole
		// tree.
		for (int dy = -1; dy <= 2; dy++) {
			BlockPos pos = feet.offset(0, dy, 0);
			BlockState state = level.getBlockState(pos);
			if (isLog(state) || isLeaves(state))
				candidates.add(pos);
		}
	}

	/**
	 * Last-resort self-rescue for a worker that has remained blocked after
	 * requesting shared road-builder help. It cuts only the immediate wall/headroom
	 * at the worker's current height or above. The floor and every lower block are
	 * forbidden, preventing the old behaviour where workers excavated pits under
	 * themselves.
	 */
	private boolean tryDigNonDownwardEscape(ServerLevel level, PathfinderMob villager, BlockPos target,
			WorkerRecord record) {
		if (ticks - record.lastObstacleBreakTick < OBSTACLE_BREAK_COOLDOWN_TICKS)
			return false;
		BlockPos here = villager.blockPosition();
		int stepX = Integer.compare(target.getX(), here.getX());
		int stepZ = Integer.compare(target.getZ(), here.getZ());
		if (stepX != 0 && stepZ != 0) {
			if (Math.abs(target.getX() - here.getX()) >= Math.abs(target.getZ() - here.getZ()))
				stepZ = 0;
			else
				stepX = 0;
		}
		if (stepX == 0 && stepZ == 0) {
			int[][] directions = { { 1, 0 }, { 0, 1 }, { -1, 0 }, { 0, -1 } };
			int[] direction = directions[Math.floorMod(record.assignmentIndex, directions.length)];
			stepX = direction[0];
			stepZ = direction[1];
		}

		int[][] directions = { { stepX, stepZ }, { -stepZ, stepX }, { stepZ, -stepX }, { -stepX, -stepZ } };
		for (int[] direction : directions) {
			int dx = direction[0];
			int dz = direction[1];
			if (dx == 0 && dz == 0)
				continue;

			// Feet first, then head, then one extra overhead block for uneven terrain.
			// There is deliberately no here.below() or negative-Y candidate.
			BlockPos[] candidates = { here.offset(dx, 0, dz), here.offset(dx, 1, dz), here.offset(dx, 2, dz),
					here.above(), here.above(2) };
			for (BlockPos pos : candidates) {
				if (pos.getY() < here.getY())
					continue;
				BlockState state = level.getBlockState(pos);
				if (!isWorkerBreakableObstacle(state))
					continue;
				if (level.getBlockEntity(pos) != null)
					continue;
				if (!safeSelfEscapeTerrainClear(level, villager, pos))
					continue;
				if (!claimObstacle(record, pos))
					continue;

				ResourceType recovered = obstacleResource(state);
				if (!level.destroyBlock(pos, false)) {
					obstacleClaims.remove(obstacleKey(pos));
					continue;
				}
				obstacleClaims.remove(obstacleKey(pos));
				if (recovered != null) {
					spawnResourceDrop(level, record.civilisationId, recovered, 1, pos);
				}
				villager.swing(InteractionHand.MAIN_HAND);
				record.lastObstacleBreakTick = ticks;
				dirty = true;
				return true;
			}
		}
		return false;
	}

	private boolean safeSelfEscapeTerrainClear(ServerLevel level, PathfinderMob villager, BlockPos pos) {
		BlockPos here = villager.blockPosition();
		if (pos.getY() < here.getY() || pos.equals(here.below()))
			return false;

		// Never remove a block another Grand Strategy villager is standing on. Use
		// the already-maintained worker coordinates rather than resolving every UUID
		// to an entity during an escape check; this keeps crowd recovery inexpensive.
		for (WorkerRecord other : workers.values()) {
			if (other == null || other.uuid == null)
				continue;
			if (other.lastX == pos.getX() && other.lastY - 1 == pos.getY() && other.lastZ == pos.getZ())
				return false;
		}
		return true;
	}

	private void requestTravelAssistance(WorkerRecord record, BlockPos obstacle) {
		if (record == null || obstacle == null || record.civilisationId == null)
			return;
		String key = obstacleKey(obstacle);
		TravelAssistRequest existing = travelAssistRequests.get(key);
		if (existing != null && Objects.equals(existing.civilisationId, record.civilisationId)) {
			existing.requestTick = ticks;
			return;
		}

		// Keep the coordination structure bounded even in pathological terrain.
		travelAssistRequests.entrySet()
				.removeIf(entry -> ticks - entry.getValue().requestTick > TRAVEL_ASSIST_REQUEST_TTL_TICKS);
		while (travelAssistRequests.size() >= MAX_TRAVEL_ASSIST_REQUESTS) {
			String oldest = null;
			long oldestTick = Long.MAX_VALUE;
			for (Map.Entry<String, TravelAssistRequest> entry : travelAssistRequests.entrySet()) {
				if (entry.getValue().requestTick < oldestTick) {
					oldestTick = entry.getValue().requestTick;
					oldest = entry.getKey();
				}
			}
			if (oldest == null)
				break;
			travelAssistRequests.remove(oldest);
		}
		travelAssistRequests.put(key, new TravelAssistRequest(record.civilisationId, obstacle.getX(), obstacle.getY(),
				obstacle.getZ(), ticks));
	}

	private boolean tickTravelAssistRequest(ServerLevel level, Civilisation civilisation, PathfinderMob roadBuilder,
			WorkerRecord record) {
		if (level == null || civilisation == null || roadBuilder == null || record == null)
			return false;

		TravelAssistRequest best = null;
		String bestKey = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		List<String> stale = new ArrayList<>();
		for (Map.Entry<String, TravelAssistRequest> entry : travelAssistRequests.entrySet()) {
			TravelAssistRequest request = entry.getValue();
			if (ticks - request.requestTick > TRAVEL_ASSIST_REQUEST_TTL_TICKS) {
				stale.add(entry.getKey());
				continue;
			}
			if (!Objects.equals(civilisation.getId(), request.civilisationId))
				continue;
			if (request.assigneeUuid != null && !Objects.equals(request.assigneeUuid, record.uuid)
					&& ticks - request.assignmentTick <= 200L)
				continue;
			BlockPos obstacle = request.pos();

			// Road builders maintain surface logistics. Underground miner failures
			// must never recruit a road builder into a mine tunnel/ravine: the miner's
			// shaft/recovery system owns those passages. This was visible as road crews
			// at Y~73 repeatedly receiving goals at Y~63 and then oscillating on cliffs.
			if (isChunkLoaded(level, obstacle.getX(), obstacle.getZ())) {
				int surfaceY = robustLocalSurfaceY(level, obstacle.getX(), obstacle.getZ());
				if (obstacle.getY() <= surfaceY - 3) {
					stale.add(entry.getKey());
					continue;
				}
			}

			BlockState state = level.getBlockState(obstacle);
			if (!isWorkerBreakableObstacle(state) || isLog(state) || isLeaves(state) || isNaturalPathClutter(state)
					|| state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK)) {
				stale.add(entry.getKey());
				continue;
			}
			double distance = roadBuilder.distanceToSqr(obstacle.getX() + 0.5, obstacle.getY(), obstacle.getZ() + 0.5);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = request;
				bestKey = entry.getKey();
			}
		}
		for (String key : stale)
			travelAssistRequests.remove(key);
		if (best == null || bestKey == null)
			return false;

		best.assigneeUuid = record.uuid;
		best.assignmentTick = ticks;
		BlockPos obstacle = best.pos();
		if (!near(roadBuilder, obstacle, 4.5)) {
			BlockPos approach = safeObstacleApproach(level, roadBuilder.blockPosition(), obstacle);
			if (approach == null) {
				travelAssistRequests.remove(bestKey);
				return false;
			}
			moveTo(level, roadBuilder, approach, record);
			return true;
		}

		if (!safeRoadBuilderTerrainClear(level, roadBuilder, obstacle) || !claimObstacle(record, obstacle)) {
			travelAssistRequests.remove(bestKey);
			return false;
		}
		BlockState state = level.getBlockState(obstacle);
		ResourceType recovered = obstacleResource(state);
		if (level.destroyBlock(obstacle, false)) {
			if (recovered != null) {
				spawnResourceDrop(level, record.civilisationId, recovered, 1, obstacle);
			}
			roadBuilder.swing(InteractionHand.MAIN_HAND);
			record.lastObstacleBreakTick = ticks;
			successfulWork(record, 0.35);
			dirty = true;
		}
		obstacleClaims.remove(obstacleKey(obstacle));
		travelAssistRequests.remove(bestKey);
		clearMoveTarget(record);
		return true;
	}

	private BlockPos safeObstacleApproach(ServerLevel level, BlockPos from, BlockPos obstacle) {
		BlockPos best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (int dy = -1; dy <= 1; dy++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dx = -2; dx <= 2; dx++) {
					if (dx == 0 && dz == 0)
						continue;
					BlockPos candidate = obstacle.offset(dx, dy, dz);
					if (!isWorkerStandable(level, candidate))
						continue;
					double distance = distanceSquared(from, candidate);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = candidate;
					}
				}
			}
		}
		return best;
	}

	private boolean safeRoadBuilderTerrainClear(ServerLevel level, PathfinderMob villager, BlockPos pos) {
		if (level == null || villager == null || pos == null)
			return false;
		BlockPos here = villager.blockPosition();

		// Never excavate beneath the worker or downhill below its current feet. That
		// was the main way generic stuck recovery could create a pit and then fall
		// into it. Road builders may cut a wall at feet/head level, not a downward
		// trench.
		if (pos.getY() < here.getY() || pos.equals(here) || pos.equals(here.below()))
			return false;

		// If this is the feet-level wall block, require a real dry tread underneath
		// so clearing it leaves a walkable corridor rather than opening a drop/water
		// pocket. Head-height grading does not remove support.
		if (pos.getY() == here.getY()) {
			BlockPos support = pos.below();
			if (!level.getFluidState(support).isEmpty())
				return false;
			BlockState supportState = level.getBlockState(support);
			if (supportState.isAir() || level.getBlockEntity(support) != null)
				return false;
		}

		// Do not break a block that another Grand Strategy villager is standing on
		// or immediately above. This lets nearby workers share the corridor safely.
		for (WorkerRecord other : workers.values()) {
			if (other == null || other.uuid == null)
				continue;
			Entity entity = entityFromUuid(level, other.uuid);
			if (!(entity instanceof PathfinderMob otherVillager) || !otherVillager.isAlive())
				continue;
			BlockPos otherFeet = otherVillager.blockPosition();
			if (otherFeet.equals(pos) || otherFeet.below().equals(pos))
				return false;
		}
		return true;
	}

	private boolean claimObstacle(WorkerRecord record, BlockPos pos) {
		if (record == null || pos == null || record.uuid == null)
			return false;
		String key = obstacleKey(pos);
		ObstacleClaim existing = obstacleClaims.get(key);
		if (existing != null && !Objects.equals(existing.workerUuid(), record.uuid)
				&& ticks - existing.claimTick() <= OBSTACLE_CLAIM_TTL_TICKS) {
			return false;
		}
		obstacleClaims.put(key, new ObstacleClaim(record.uuid, ticks));
		return true;
	}

	private String obstacleKey(BlockPos pos) {
		return pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
	}

	private void addObstacleColumn(List<BlockPos> candidates, BlockPos feet) {
		if (feet == null)
			return;
		// Check from the worker's feet through a useful upward interaction envelope.
		// Head-height blocks are tested first because they actually stop movement,
		// then the higher canopy is cleared so the next navigation pass is not
		// immediately blocked again.
		candidates.add(feet.above());
		candidates.add(feet);
		for (int y = 2; y <= WORKER_OVERHEAD_CLEARANCE; y++) {
			candidates.add(feet.above(y));
		}
	}

	/**
	 * Natural decorative blocks that should never be allowed to become a serious
	 * navigation obstruction. Minecraft 26.x adds several low-profile vegetation
	 * variants (notably leaf litter) whose render/shape can confuse local route
	 * selection even though a person should simply walk through or brush them
	 * aside.
	 *
	 * Description ids are used intentionally here instead of directly referencing
	 * every new Blocks constant so this common-core code remains tolerant of minor
	 * 26.x registry additions across loaders.
	 */
	private boolean isNaturalPathClutter(BlockState state) {
		if (state == null || state.isAir())
			return false;
		String id;
		try {
			id = state.getBlock().getDescriptionId();
		} catch (Throwable ignored) {
			return false;
		}
		if (id == null)
			return false;
		return id.endsWith(".leaf_litter") || id.endsWith(".short_grass") || id.endsWith(".tall_grass")
				|| id.endsWith(".fern") || id.endsWith(".large_fern") || id.endsWith(".dead_bush")
				|| id.endsWith(".short_dry_grass") || id.endsWith(".tall_dry_grass") || id.endsWith(".bush")
				|| id.endsWith(".firefly_bush") || id.endsWith(".wildflowers") || id.endsWith(".pink_petals")
				|| id.endsWith(".hanging_roots") || id.endsWith(".nether_sprouts");
	}

	/**
	 * Lower a heightmap-selected feet position when the heightmap stopped above
	 * harmless ground clutter such as leaf litter.
	 */
	private BlockPos lowerTargetPastNaturalClutter(ServerLevel level, BlockPos feet) {
		if (level == null || feet == null)
			return feet;
		BlockPos adjusted = feet;
		for (int i = 0; i < 2; i++) {
			BlockPos below = adjusted.below();
			if (!isNaturalPathClutter(level.getBlockState(below)))
				break;
			adjusted = below;
		}
		return adjusted;
	}

	/**
	 * Brush harmless vegetation out of the immediate walking envelope. This is
	 * deliberately independent of profession and does not create resource drops.
	 */
	private boolean clearImmediateNaturalPathClutter(ServerLevel level, PathfinderMob humanoid, WorkerRecord record,
			BlockPos target) {
		if (level == null || humanoid == null || record == null || target == null)
			return false;
		BlockPos here = humanoid.blockPosition();
		int stepX = Integer.compare(target.getX(), here.getX());
		int stepZ = Integer.compare(target.getZ(), here.getZ());
		List<BlockPos> candidates = new ArrayList<>();
		candidates.add(here);
		candidates.add(here.above());
		if (stepX != 0 || stepZ != 0) {
			BlockPos next = here.offset(stepX, 0, stepZ);
			candidates.add(next);
			candidates.add(next.above());
			if (stepX != 0 && stepZ != 0) {
				candidates.add(here.offset(stepX, 0, 0));
				candidates.add(here.offset(0, 0, stepZ));
			}
		}

		boolean changed = false;
		int cleared = 0;
		for (BlockPos pos : candidates) {
			if (cleared >= 4)
				break;
			BlockState state = level.getBlockState(pos);
			if (!isNaturalPathClutter(state))
				continue;
			if (level.getBlockEntity(pos) != null)
				continue;
			// No claim is required for decorative clutter: destroyBlock is
			// idempotent on the server thread and simultaneous workers simply see air.
			if (level.destroyBlock(pos, false)) {
				cleared++;
				changed = true;
			}
		}
		if (changed) {
			record.lastObstacleBreakTick = ticks;
			dirty = true;
		}
		return changed;
	}

	private boolean isWorkerBreakableObstacle(BlockState state) {
		if (state == null || state.isAir())
			return false;
		if (isLog(state) || isLeaves(state) || isNaturalPathClutter(state))
			return true;
		return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
				|| state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.PODZOL) || state.is(Blocks.SAND)
				|| state.is(Blocks.RED_SAND) || state.is(Blocks.CLAY) || state.is(Blocks.STONE)
				|| state.is(Blocks.GRANITE) || state.is(Blocks.DIORITE) || state.is(Blocks.ANDESITE)
				|| state.is(Blocks.CALCITE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.TUFF)
				|| state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK);
	}

	private ResourceType obstacleResource(BlockState state) {
		if (isLog(state))
			return ResourceType.WOOD;
		if (isStone(state))
			return ResourceType.STONE;
		return null;
	}

	private boolean isLeaves(BlockState state) {
		return state.is(Blocks.OAK_LEAVES) || state.is(Blocks.BIRCH_LEAVES) || state.is(Blocks.SPRUCE_LEAVES)
				|| state.is(Blocks.JUNGLE_LEAVES) || state.is(Blocks.ACACIA_LEAVES) || state.is(Blocks.DARK_OAK_LEAVES)
				|| state.is(Blocks.MANGROVE_LEAVES) || state.is(Blocks.CHERRY_LEAVES)
				|| state.is(Blocks.PALE_OAK_LEAVES);
	}

	private void clearMoveTarget(WorkerRecord record) {
		record.hasMoveTarget = false;
		record.lastTargetDistanceSquared = -1.0;
	}

	/**
	 * A local interaction has actually finished. Clear both the executor leg and
	 * the transient brain goal so the anti-freeze watchdog cannot keep nudging a
	 * worker toward coordinates it has already serviced.
	 */
	private void finishLocalTaskMovement(PathfinderMob villager, WorkerRecord record) {
		if (record == null)
			return;
		clearMoveTarget(record);
		record.stuckChecks = 0;
		record.navigationAssistTicks = 0;
		record.lastTargetDistanceSquared = -1.0;
		record.lastNavigationCheckTick = ticks;
		record.livenessInitialised = false;
		record.livenessRecoveryStage = 0;
		WorkerBrainState brain = ensureBrain(record);

		// A planner future may still be finishing the leg we just completed. Merely
		// clearing hasGoal is not enough: without bumping the generation that stale
		// result can arrive a tick later and reinstall an obsolete waypoint, producing
		// the visible two-block back-and-forth jitter around farms/factories/chests.
		WorkerPlannerService.getInstance().forget(record.uuid);
		brain.planGeneration++;
		brain.clearGoal();
		brain.routeRequestPending = false;
		brain.escapeRequestPending = false;
		brain.route.clear();
		brain.routeIndex = 0;
		brain.escapeRoute.clear();
		brain.escapeIndex = 0;
		brain.escaping = false;
		if (villager != null)
			villager.getNavigation().stop();
		dirty = true;
	}

	private int carryLimitFor(VillagerJob job) {
		return job == VillagerJob.MINER ? MINER_CARRY_LIMIT : CARRY_LIMIT;
	}

	private boolean professionCollectsWorkDrops(VillagerJob job) {
		return job == VillagerJob.FARMER || job == VillagerJob.LUMBERJACK || job == VillagerJob.MINER;
	}

	private boolean professionAcceptsResource(VillagerJob job, ResourceType type) {
		if (job == null || type == null)
			return false;
		return switch (job) {
		case FARMER -> type == ResourceType.FOOD;
		case LUMBERJACK -> type == ResourceType.WOOD;
		case MINER -> type == ResourceType.STONE || type == ResourceType.IRON || type == ResourceType.COAL
				|| type == ResourceType.GOLD || type == ResourceType.COPPER || type == ResourceType.REDSTONE
				|| type == ResourceType.LAPIS || type == ResourceType.EMERALD || type == ResourceType.DIAMOND;
		default -> false;
		};
	}

	private boolean collectNearestWorkDrop(ServerLevel level, PathfinderMob villager, WorkerRecord record) {
		ItemEntity drop = nearestWorkDrop(level, villager, record);
		if (drop == null) {
			releaseClaimedWorkDrops(record);
			if ("work_drop".equals(record.targetKind)) {
				clearTarget(record);
				finishLocalTaskMovement(villager, record);
			}
			return false;
		}

		record.workDropTargetUuid = drop.getUUID().toString();
		double pickupReachSq = WORK_DROP_PICKUP_REACH * WORK_DROP_PICKUP_REACH;
		if (villager.distanceToSqr(drop) > pickupReachSq) {
			setTarget(record, "work_drop", drop.blockPosition());
			BlockPos post = workDropInteractionPost(level, villager, record, drop.blockPosition());
			moveTo(level, villager, post == null ? drop.blockPosition() : post, record);
			return true;
		}

		ItemStack stack = drop.getItem();
		ResourceType type = resourceForItem(stack.getItem());
		if (type == null) {
			workDropOwners.remove(drop.getUUID());
			workDropClaims.remove(drop.getUUID());
			record.workDropTargetUuid = null;
			drop.discard();
			return true;
		}

		int room = Math.max(0, carryLimitFor(parseJob(record.job)) - carriedTotal(record));
		if (room <= 0) {
			record.forceDeposit = true;
			return false;
		}
		int take = Math.min(room, stack.getCount());
		carry(record, type, take);
		record.lastPickupTick = ticks;
		stack.shrink(take);
		if (stack.isEmpty()) {
			workDropOwners.remove(drop.getUUID());
			workDropClaims.remove(drop.getUUID());
			record.workDropTargetUuid = null;
			drop.discard();
		} else {
			drop.setItem(stack);
			record.workDropTargetUuid = drop.getUUID().toString();
		}
		villager.swing(InteractionHand.MAIN_HAND);
		updateCarriedDisplay(villager, record);
		clearTarget(record);
		finishLocalTaskMovement(villager, record);
		if (carriedTotal(record) >= carryLimitFor(parseJob(record.job)))
			record.forceDeposit = true;
		return true;
	}

	private ItemEntity nearestWorkDrop(ServerLevel level, PathfinderMob villager, WorkerRecord record) {
		AABB box = villager.getBoundingBox().inflate(WORK_DROP_SEARCH_RADIUS, 8.0, WORK_DROP_SEARCH_RADIUS);

		// Keep a producer committed to the same physical stack until it is collected,
		// disappears or proves unreachable. This is particularly important in farms,
		// where several wheat stacks can be separated by only one block.
		if (record.workDropTargetUuid != null) {
			Entity retained = entityFromUuid(level, record.workDropTargetUuid);
			if (retained instanceof ItemEntity item && item.isAlive() && box.intersects(item.getBoundingBox())
					&& Objects.equals(record.civilisationId, workDropOwners.get(item.getUUID()))) {
				ResourceType retainedType = resourceForItem(item.getItem().getItem());
				if (retainedType != null && professionAcceptsResource(parseJob(record.job), retainedType)
						&& !recentNavigationFailureNear(record, item.blockPosition(), 5, 240L)) {
					workDropClaims.put(item.getUUID(), record.uuid);
					return item;
				}
			}
			releaseClaimedWorkDrops(record);
		}

		ItemEntity best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box, entity -> entity.isAlive()
				&& Objects.equals(record.civilisationId, workDropOwners.get(entity.getUUID())))) {
			ResourceType dropType = resourceForItem(item.getItem().getItem());
			if (dropType == null || !professionAcceptsResource(parseJob(record.job), dropType))
				continue;
			// A worker that has just demonstrated it cannot reach this drop should not
			// immediately reserve the exact same item again on the next think tick.
			// Failed-location memory is per worker, so another worker may still collect it.
			if (recentNavigationFailureNear(record, item.blockPosition(), 5, 240L))
				continue;
			String claimant = workDropClaims.get(item.getUUID());
			if (claimant != null && !claimant.equals(record.uuid))
				continue;
			double distance = villager.distanceToSqr(item);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = item;
			}
		}
		if (best != null) {
			workDropClaims.put(best.getUUID(), record.uuid);
			record.workDropTargetUuid = best.getUUID().toString();
		}
		return best;
	}

	private BlockPos workDropInteractionPost(ServerLevel level, PathfinderMob villager, WorkerRecord record,
			BlockPos dropPos) {
		if (level == null || villager == null || record == null || dropPos == null)
			return dropPos;
		BlockPos best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		for (int dz = -2; dz <= 2; dz++) {
			for (int dx = -2; dx <= 2; dx++) {
				int x = dropPos.getX() + dx;
				int z = dropPos.getZ() + dz;
				if (!isChunkLoaded(level, x, z))
					continue;
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				BlockPos candidate = lowerTargetPastNaturalClutter(level, new BlockPos(x, y, z));
				if (!isSafeNavigationFeet(level, record, candidate))
					continue;
				double dropDistance = distanceSquared(candidate, dropPos);
				if (dropDistance > WORK_DROP_PICKUP_REACH * WORK_DROP_PICKUP_REACH)
					continue;
				double score = dropDistance * 3.0
						+ villager.distanceToSqr(candidate.getX() + 0.5, candidate.getY(), candidate.getZ() + 0.5);
				if (score < bestScore) {
					bestScore = score;
					best = candidate;
				}
			}
		}
		return best;
	}

	private boolean recentNavigationFailureNear(WorkerRecord record, BlockPos pos, int radius, long maxAgeTicks) {
		if (record == null || pos == null)
			return false;
		WorkerBrainState brain = ensureBrain(record);
		if (brain.failedLocations == null)
			return false;
		int radiusSquared = radius * radius;
		long oldest = Math.max(0L, ticks - Math.max(1L, maxAgeTicks));
		for (WorkerBrainState.FailedLocation failed : brain.failedLocations) {
			if (failed == null || failed.tick() < oldest)
				continue;
			int dx = failed.x() - pos.getX();
			int dz = failed.z() - pos.getZ();
			if (dx * dx + dz * dz <= radiusSquared && Math.abs(failed.y() - pos.getY()) <= 5)
				return true;
		}
		return false;
	}

	private void releaseClaimedWorkDrops(WorkerRecord record) {
		if (record == null || record.uuid == null)
			return;
		workDropClaims.entrySet().removeIf(entry -> Objects.equals(record.uuid, entry.getValue()));
		record.workDropTargetUuid = null;
	}

	private ItemEntity spawnResourceDrop(ServerLevel level, String civilisationId, ResourceType type, int amount,
			BlockPos pos) {
		if (amount <= 0 || pos == null)
			return null;
		Item item = itemFor(type);
		if (item == null)
			return null;
		ItemEntity first = null;
		int remaining = amount;
		while (remaining > 0) {
			int count = Math.min(remaining, new ItemStack(item).getMaxStackSize());
			ItemEntity drop = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5,
					new ItemStack(item, count));
			drop.addTag(WORK_DROP_TAG);
			drop.addTag(civilisationDropTag(civilisationId));
			workDropOwners.put(drop.getUUID(), civilisationId);
			level.addFreshEntity(drop);
			if (first == null)
				first = drop;
			remaining -= count;
		}
		return first;
	}

	private void spawnSpecificDrop(ServerLevel level, String civilisationId, Item item, int amount, BlockPos pos) {
		if (level == null || item == null || amount <= 0 || pos == null)
			return;
		int remaining = amount;
		int maxStack = new ItemStack(item).getMaxStackSize();
		while (remaining > 0) {
			int count = Math.min(remaining, maxStack);
			ItemEntity drop = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5,
					new ItemStack(item, count));
			drop.addTag(WORK_DROP_TAG);
			drop.addTag(civilisationDropTag(civilisationId));
			workDropOwners.put(drop.getUUID(), civilisationId);
			level.addFreshEntity(drop);
			remaining -= count;
		}
	}

	private void spawnOrdinaryItemDrop(ServerLevel level, Item item, int amount, BlockPos pos) {
		if (level == null || item == null || amount <= 0 || pos == null)
			return;
		int remaining = amount;
		int maxStack = new ItemStack(item).getMaxStackSize();
		while (remaining > 0) {
			int count = Math.min(remaining, maxStack);
			ItemEntity drop = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5,
					new ItemStack(item, count));
			level.addFreshEntity(drop);
			remaining -= count;
		}
	}

	private String civilisationDropTag(String civilisationId) {
		return "gs_drop_" + sanitiseTag(civilisationId);
	}

	private ResourceType resourceForItem(Item item) {
		if (item == Items.WHEAT)
			return ResourceType.FOOD;
		if (isWoodLogItem(item) || isWoodPlankItem(item))
			return ResourceType.WOOD;
		if (item == Items.COBBLESTONE || item == Items.STONE || item == Items.COBBLED_DEEPSLATE) {
			return ResourceType.STONE;
		}
		if (item == Items.IRON_INGOT)
			return ResourceType.IRON;
		if (item == Items.COAL || item == Items.CHARCOAL)
			return ResourceType.COAL;
		if (item == Items.GOLD_INGOT)
			return ResourceType.GOLD;
		if (item == Items.COPPER_INGOT)
			return ResourceType.COPPER;
		if (item == Items.REDSTONE)
			return ResourceType.REDSTONE;
		if (item == Items.LAPIS_LAZULI)
			return ResourceType.LAPIS;
		if (item == Items.EMERALD)
			return ResourceType.EMERALD;
		if (item == Items.DIAMOND)
			return ResourceType.DIAMOND;
		if (item == Items.BREAD)
			return ResourceType.SUPPLIES;
		return null;
	}

	/**
	 * Returns true when the worker's current tick is being used to travel
	 * to/build/use a crafting table. No profession tool can materialise away from
	 * that station.
	 */
	/**
	 * Wooden tools are the bootstrap tier and do not require a factory. A hand-tier
	 * worker that uses a profession tool may walk to any controlled depot
	 * containing real wood, withdraw enough plank-equivalent material for its tool
	 * and craft the wooden tool directly. One stored log supplies four
	 * plank-equivalent units, so a single log is enough for every wooden profession
	 * tool used here.
	 *
	 * Stone, iron, optional steel and diamond upgrades remain factory products:
	 * after the worker has enough experience it collects the already-crafted tool
	 * from a depot chest.
	 */
	private boolean maybeUpgradeTool(ServerLevel level, Civilisation civilisation, City home, WorkerRecord record,
			PathfinderMob villager) {
		VillagerJob job = parseJob(record.job);
		if (!supportsTool(job))
			return false;

		WorkerToolTier current = toolTier(record);
		WorkerToolTier next = nextToolTier(civilisation, job, current);
		if (next == current)
			return false;
		if (!ResearchSystem.canUseToolTier(civilisation, job, next))
			return false;

		// HAND -> WOOD is deliberately independent of factories and experience.
		// This is its own bootstrap transaction rather than generic factory-material
		// hauling. In v6.38.4 the worker could withdraw wood into workMaterials and
		// then the orphan-material guard (which runs earlier in tickWorker) would put
		// that same wood straight back into storage before the tool was crafted.
		// Keep bootstrap wood reserved until the tool is complete, and if there is
		// currently no wood in storage simply continue the worker's normal hand-tier
		// job instead of camping at the depot waiting for material.
		if (current == WorkerToolTier.HAND && next == WorkerToolTier.WOOD) {
			int woodNeeded = toolHeadCost(job) + 1;
			if (woodNeeded <= 0)
				return false;
			record.bootstrapToolCrafting = true;

			int have = workMaterialCount(record, ResourceType.WOOD);
			if (have < woodNeeded) {
				BlockPos chest = nearestDepotChestWithMaterial(level, civilisation.getId(), ResourceType.WOOD,
						villager.blockPosition(), record);
				if (chest == null) {
					// No stock means no depot errand. Lumberjacks can keep cutting by
					// hand and the other professions continue their ordinary work.
					record.bootstrapToolCrafting = have > 0;
					return false;
				}

				if (walkToDepotChest(level, villager, chest, record, 3.2))
					return true;

				BlockEntity blockEntity = level.getBlockEntity(chest);
				if (!(blockEntity instanceof Container container)) {
					clearMoveTarget(record);
					return true;
				}
				int gained = withdrawMaterialUnits(container, ResourceType.WOOD, woodNeeded - have);
				if (gained > 0) {
					addWorkMaterial(record, ResourceType.WOOD, gained);
					villager.swing(InteractionHand.MAIN_HAND);
					updateCarriedDisplay(villager, record);
				}
				clearMoveTarget(record);
				if (workMaterialCount(record, ResourceType.WOOD) < woodNeeded)
					return true;
			}

			if (!consumeWorkMaterial(record, ResourceType.WOOD, woodNeeded))
				return false;

			villager.swing(InteractionHand.MAIN_HAND);
			record.toolTier = WorkerToolTier.WOOD.name();
			record.preparedToolTier = null;
			record.bootstrapToolCrafting = false;
			equipWorker(villager, record);
			clearMoveTarget(record);
			returnBootstrapWoodRemainder(level, civilisation.getId(), villager, record);
			dirty = true;
			return true;
		}

		record.bootstrapToolCrafting = false;
		if (record.workExperience < next.getRequiredExperience())
			return false;

		Item finishedTool = toolFor(job, next);
		if (finishedTool == null)
			return false;
		BlockPos chest = nearestDepotChestWithSpecificItem(level, civilisation.getId(), finishedTool,
				villager.blockPosition(), record);
		if (chest == null)
			return false;
		if (walkToDepotChest(level, villager, chest, record, 3.2))
			return true;

		BlockEntity blockEntity = level.getBlockEntity(chest);
		if (!(blockEntity instanceof Container container))
			return false;
		if (removeSpecificItems(container, List.of(finishedTool), 1) <= 0)
			return false;

		villager.swing(InteractionHand.MAIN_HAND);
		record.toolTier = next.name();
		record.preparedToolTier = null;
		equipWorker(villager, record);
		clearMoveTarget(record);
		dirty = true;
		return true;
	}

	private boolean isWoodenToolBootstrapMaterial(WorkerRecord record, VillagerJob job) {
		if (record == null || job == null || !supportsTool(job))
			return false;
		if (toolTier(record) != WorkerToolTier.HAND)
			return false;
		// Older saves affected by the v6.38.4 loop do not have the new flag, so
		// recognise any wood already held by a hand-tier tool user as legitimate
		// bootstrap material and let maybeUpgradeTool finish the transaction.
		return record.bootstrapToolCrafting || workMaterialCount(record, ResourceType.WOOD) > 0;
	}

	private void returnBootstrapWoodRemainder(ServerLevel level, String civilisationId, PathfinderMob villager,
			WorkerRecord record) {
		int remainder = workMaterialCount(record, ResourceType.WOOD);
		if (remainder <= 0)
			return;
		BlockPos chest = nearestDepotChestAcceptingItem(level, civilisationId, Items.OAK_PLANKS,
				villager.blockPosition(), record);
		if (chest == null || !near(villager, chest, 3.2))
			return;
		BlockEntity blockEntity = level.getBlockEntity(chest);
		if (!(blockEntity instanceof Container container))
			return;
		int inserted = insertSpecificItems(container, Items.OAK_PLANKS, remainder);
		if (inserted > 0) {
			record.workMaterials.put(ResourceType.WOOD.name(), remainder - inserted);
			updateCarriedDisplay(villager, record);
			dirty = true;
		}
	}

	private boolean supportsTool(VillagerJob job) {
		return switch (job) {
		case FARMER, LUMBERJACK, MINER, ROAD_BUILDER, FACTORY_BUILDER, SOLDIER -> true;
		default -> false;
		};
	}

	private int toolHeadCost(VillagerJob job) {
		return switch (job) {
		case FARMER, SOLDIER -> 2;
		case LUMBERJACK, MINER, ROAD_BUILDER, FACTORY_BUILDER -> 3;
		default -> 0;
		};
	}

	private BlockPos findCraftingTable(ServerLevel level, City home, int assignmentIndex) {
		List<BlockPos> tables = new ArrayList<>();
		for (int dz = -CRAFTING_TABLE_SEARCH_RADIUS; dz <= CRAFTING_TABLE_SEARCH_RADIUS; dz++) {
			for (int dx = -CRAFTING_TABLE_SEARCH_RADIUS; dx <= CRAFTING_TABLE_SEARCH_RADIUS; dx++) {
				int x = home.getBlockX() + dx;
				int z = home.getBlockZ() + dz;
				if (!isChunkLoaded(level, x, z))
					continue;
				int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				for (int dy = -2; dy <= 1; dy++) {
					BlockPos pos = new BlockPos(x, surfaceY + dy, z);
					if (level.getBlockState(pos).is(Blocks.CRAFTING_TABLE))
						tables.add(pos);
				}
			}
		}
		if (tables.isEmpty())
			return null;
		tables.sort((BlockPos a, BlockPos b) -> {
			int byX = Long.compare(a.getX(), b.getX());
			return byX != 0 ? byX : Long.compare(a.getZ(), b.getZ());
		});
		return tables.get(Math.floorMod(assignmentIndex, tables.size()));
	}

	private BlockPos findFurnace(ServerLevel level, City home, int assignmentIndex) {
		List<BlockPos> furnaces = new ArrayList<>();
		for (int dz = -CRAFTING_TABLE_SEARCH_RADIUS; dz <= CRAFTING_TABLE_SEARCH_RADIUS; dz++) {
			for (int dx = -CRAFTING_TABLE_SEARCH_RADIUS; dx <= CRAFTING_TABLE_SEARCH_RADIUS; dx++) {
				int x = home.getBlockX() + dx;
				int z = home.getBlockZ() + dz;
				if (!isChunkLoaded(level, x, z))
					continue;
				int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				for (int dy = -2; dy <= 1; dy++) {
					BlockPos pos = new BlockPos(x, surfaceY + dy, z);
					if (level.getBlockState(pos).is(Blocks.FURNACE))
						furnaces.add(pos);
				}
			}
		}
		if (furnaces.isEmpty())
			return null;
		furnaces.sort((BlockPos a, BlockPos b) -> {
			int byX = Long.compare(a.getX(), b.getX());
			return byX != 0 ? byX : Long.compare(a.getZ(), b.getZ());
		});
		return furnaces.get(Math.floorMod(assignmentIndex, furnaces.size()));
	}

	private void equipWorker(PathfinderMob villager, WorkerRecord record) {
		ensureWorkerAppearance(record);
		Item item = toolFor(parseJob(record.job), toolTier(record));
		ItemStack currentMainHand = villager.getItemBySlot(EquipmentSlot.MAINHAND);
		boolean shouldBeEmpty = item == null;
		if ((shouldBeEmpty && !currentMainHand.isEmpty())
				|| (!shouldBeEmpty && (currentMainHand.isEmpty() || currentMainHand.getItem() != item))) {
			villager.setItemSlot(EquipmentSlot.MAINHAND, shouldBeEmpty ? ItemStack.EMPTY : new ItemStack(item));
		}
		updateCarriedDisplay(villager, record);
		Civilisation civilisation = DataManager.getCivilisations().get(record.civilisationId);
		if (civilisation != null) {
			VillagerJob currentJob = parseJob(record.job);
			String role = currentJob == null ? "Worker" : currentJob.getDisplayName();
			String activity = compactWorkerNameplateStatus(record);
			String label = civilisation.getName() + " | " + role + " [" + toolTier(record).getDisplayName() + "]"
					+ (activity.isBlank() ? "" : " | " + activity);
			Component currentName = villager.getCustomName();
			if (currentName == null || !label.equals(currentName.getString())) {
				villager.setCustomName(Component.literal(label));
			}
			// The status nameplate is deliberately always visible. setCustomName is
			// only called when role/tool/activity actually changes, avoiding a stream
			// of redundant entity-metadata packets every server tick.
			if (!villager.isCustomNameVisible())
				villager.setCustomNameVisible(true);
		}
	}

	private void ensureWorkerAppearance(WorkerRecord record) {
		if (record == null)
			return;
		if (record.appearanceVariant < 0) {
			int seed = 31 * Math.max(0, record.assignmentIndex)
					+ (record.civilisationId == null ? 0 : record.civilisationId.hashCode());
			record.appearanceVariant = Math.floorMod(seed, 7);
			dirty = true;
		}
	}

	private Item toolFor(VillagerJob job, WorkerToolTier tier) {
		if (tier == null || tier == WorkerToolTier.HAND)
			return null;
		return switch (job) {
		case LUMBERJACK -> switch (tier) {
		case WOOD -> Items.WOODEN_AXE;
		case STONE -> Items.STONE_AXE;
		case IRON -> Items.IRON_AXE;
		case STEEL -> MinecraftItemRegistry.item("drenough_forging:steel_axe");
		case DIAMOND -> Items.DIAMOND_AXE;
		default -> null;
		};
		case MINER, ROAD_BUILDER, FACTORY_BUILDER -> switch (tier) {
		case WOOD -> Items.WOODEN_PICKAXE;
		case STONE -> Items.STONE_PICKAXE;
		case IRON -> Items.IRON_PICKAXE;
		case STEEL -> MinecraftItemRegistry.item("drenough_forging:steel_pickaxe");
		case DIAMOND -> Items.DIAMOND_PICKAXE;
		default -> null;
		};
		case FARMER -> switch (tier) {
		case WOOD -> Items.WOODEN_HOE;
		case STONE -> Items.STONE_HOE;
		case IRON -> Items.IRON_HOE;
		case STEEL -> MinecraftItemRegistry.item("drenough_forging:steel_hoe");
		case DIAMOND -> Items.DIAMOND_HOE;
		default -> null;
		};
		case SOLDIER -> switch (tier) {
		case WOOD -> Items.WOODEN_SWORD;
		case STONE -> Items.STONE_SWORD;
		case IRON -> Items.IRON_SWORD;
		case STEEL -> MinecraftItemRegistry.item("drenough_forging:steel_sword");
		case DIAMOND -> Items.DIAMOND_SWORD;
		default -> null;
		};
		default -> null;
		};
	}

	/**
	 * Returns the next physical tier that actually exists in the current registry
	 * set. STEEL is skipped when Dr. Enough Forging is absent, so a vanilla-only
	 * world can still move from iron to diamond after researching Advanced Mining.
	 */
	private WorkerToolTier nextToolTier(Civilisation civilisation, VillagerJob job, WorkerToolTier current) {
		if (current == null)
			current = WorkerToolTier.HAND;
		WorkerToolTier[] tiers = WorkerToolTier.values();
		for (int i = current.ordinal() + 1; i < tiers.length; i++) {
			WorkerToolTier candidate = tiers[i];
			if (candidate == WorkerToolTier.STEEL && toolFor(job, candidate) == null)
				continue;
			return candidate;
		}
		return current;
	}

	private void updateCarriedDisplay(PathfinderMob villager, WorkerRecord record) {
		ItemStack display = ItemStack.EMPTY;
		if (record.carrying != null) {
			for (ResourceType type : ResourceType.values()) {
				int amount = record.carrying.getOrDefault(type.name(), 0);
				if (amount <= 0)
					continue;
				Item item = itemFor(type);
				if (item != null)
					display = new ItemStack(item, Math.min(amount, new ItemStack(item).getMaxStackSize()));
				break;
			}
		}
		if (display.isEmpty() && record.boneMeal > 0) {
			display = new ItemStack(Items.BONE_MEAL,
					Math.min(record.boneMeal, new ItemStack(Items.BONE_MEAL).getMaxStackSize()));
		}
		if (display.isEmpty() && record.workMaterials != null) {
			for (ResourceType type : ResourceType.values()) {
				int amount = record.workMaterials.getOrDefault(type.name(), 0);
				if (amount <= 0)
					continue;
				Item item = type == ResourceType.WOOD ? Items.OAK_PLANKS : itemFor(type);
				if (item != null)
					display = new ItemStack(item, Math.min(amount, new ItemStack(item).getMaxStackSize()));
				break;
			}
		}
		if (display.isEmpty() && record.farmerHasWaterBucket) {
			display = new ItemStack(Items.WATER_BUCKET);
		} else if (display.isEmpty() && record.farmerHasBucket) {
			display = new ItemStack(Items.BUCKET);
		}
		if (display.isEmpty() && record.adminTorches > 0) {
			display = new ItemStack(Items.TORCH,
					Math.min(record.adminTorches, new ItemStack(Items.TORCH).getMaxStackSize()));
		}
		if (display.isEmpty() && record.factoryProduct != null && record.factoryProductCount > 0) {
			Item item = "CHEST".equals(record.factoryProduct) ? Items.CHEST : factoryProductItem(record.factoryProduct);
			if (item != null)
				display = new ItemStack(item,
						Math.min(record.factoryProductCount, new ItemStack(item).getMaxStackSize()));
		}
		ItemStack current = villager.getItemBySlot(EquipmentSlot.OFFHAND);
		boolean same = current.isEmpty() && display.isEmpty();
		if (!same && !current.isEmpty() && !display.isEmpty()) {
			same = current.getItem() == display.getItem() && current.getCount() == display.getCount();
		}
		if (!same)
			villager.setItemSlot(EquipmentSlot.OFFHAND, display);
	}

	private void successfulWork(WorkerRecord record, double baseExperience) {
		record.workExperience += baseExperience * toolTier(record).getWorkMultiplier();
		dirty = true;
	}

	private WorkerToolTier toolTier(WorkerRecord record) {
		try {
			return WorkerToolTier.valueOf(record.toolTier == null ? "HAND" : record.toolTier);
		} catch (IllegalArgumentException e) {
			return WorkerToolTier.HAND;
		}
	}

	private VillagerJob parseJob(String name) {
		if (name == null)
			return VillagerJob.FARMER;
		// Preserve workers from pre-v6.6 saves after the four specialised mining
		// professions were replaced by one general MINER profession.
		if ("STONE_MINER".equals(name) || "IRON_MINER".equals(name) || "COAL_MINER".equals(name)
				|| "GOLD_MINER".equals(name)) {
			return VillagerJob.MINER;
		}
		try {
			return VillagerJob.valueOf(name);
		} catch (IllegalArgumentException e) {
			return VillagerJob.FARMER;
		}
	}

	private boolean isProducer(VillagerJob job) {
		return switch (job) {
		case FARMER, LUMBERJACK, MINER -> true;
		default -> false;
		};
	}

	private int workMaterialCount(WorkerRecord record, ResourceType type) {
		if (record == null || type == null || record.workMaterials == null)
			return 0;
		return Math.max(0, record.workMaterials.getOrDefault(type.name(), 0));
	}

	private void addWorkMaterial(WorkerRecord record, ResourceType type, int amount) {
		if (record == null || type == null || amount <= 0)
			return;
		if (record.workMaterials == null)
			record.workMaterials = new LinkedHashMap<>();
		record.workMaterials.merge(type.name(), amount, Integer::sum);
		dirty = true;
	}

	private boolean consumeWorkMaterial(WorkerRecord record, ResourceType type, int amount) {
		if (amount <= 0)
			return true;
		int have = workMaterialCount(record, type);
		if (have < amount)
			return false;
		record.workMaterials.put(type.name(), have - amount);
		dirty = true;
		return true;
	}

	private int workMaterialTotal(WorkerRecord record) {
		if (record == null || record.workMaterials == null)
			return 0;
		int total = 0;
		for (Integer count : record.workMaterials.values()) {
			total += Math.max(0, count == null ? 0 : count);
		}
		return total;
	}

	private boolean registerRecoveredDepotChest(String civilisationId, City city, BlockPos pos) {
		if (civilisationId == null || city == null || pos == null
				|| !Objects.equals(city.getControllerId(), civilisationId))
			return false;
		String key = depotKey(civilisationId, city.getId());
		DepotRecord depot = depots.computeIfAbsent(key, ignored -> new DepotRecord(civilisationId, city.getId()));
		if (depot.chests == null)
			depot.chests = new ArrayList<>();
		depot.active = true;
		boolean known = depot.chests.stream()
				.anyMatch(stored -> stored.x == pos.getX() && stored.y == pos.getY() && stored.z == pos.getZ());
		if (known)
			return false;
		depot.chests.add(new StoredPos(pos.getX(), pos.getY(), pos.getZ()));
		dirty = true;
		return true;
	}

	/**
	 * Failure-only self-healing for legacy/stale depot records. A real chest near
	 * the worker's controlled home command post is registered once and thereafter
	 * all lumber/factory/tool/food code can find it through the normal fast
	 * DepotRecord path.
	 */
	private boolean recoverNearbyDepotChests(ServerLevel level, String civilisationId, WorkerRecord record) {
		if (level == null || civilisationId == null || record == null)
			return false;
		if (record.lastDepotRecoveryScanTick > 0L
				&& ticks - record.lastDepotRecoveryScanTick < DEPOT_RECOVERY_SCAN_INTERVAL_TICKS)
			return false;
		record.lastDepotRecoveryScanTick = ticks;

		City home = cityById(record.homeCityId);
		if (home == null || !Objects.equals(home.getControllerId(), civilisationId)) {
			home = primarySupplyCapital(civilisationId);
		}
		if (home == null || !Objects.equals(home.getControllerId(), civilisationId))
			return false;

		boolean recovered = false;
		for (int dz = -DEPOT_RECOVERY_SCAN_RADIUS; dz <= DEPOT_RECOVERY_SCAN_RADIUS; dz++) {
			for (int dx = -DEPOT_RECOVERY_SCAN_RADIUS; dx <= DEPOT_RECOVERY_SCAN_RADIUS; dx++) {
				int x = home.getBlockX() + dx;
				int z = home.getBlockZ() + dz;
				if (!isChunkLoaded(level, x, z))
					continue;
				int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				for (int dy = -16; dy <= 2; dy++) {
					BlockPos pos = new BlockPos(x, surfaceY + dy, z);
					BlockState state = level.getBlockState(pos);
					if (!state.is(Blocks.CHEST) && !state.is(Blocks.TRAPPED_CHEST))
						continue;
					if (!(level.getBlockEntity(pos) instanceof Container))
						continue;
					recovered |= registerRecoveredDepotChest(civilisationId, home, pos);
				}
			}
		}
		if (recovered) {
			System.out.println("[GrandStrategy][Depot] recovered nearby command-post chest records" + " worker="
					+ String.valueOf(record.uuid) + " civ=" + civilisationId + " city=" + home.getId());
		}
		return recovered;
	}

	/**
	 * Makes resource use visibly physical: the worker first finds a depot chest
	 * that really contains the required material, walks to it, withdraws a useful
	 * batch, and only then returns to its construction/crafting task. Returning
	 * true means this worker tick is occupied by the supply trip (or is waiting for
	 * stock).
	 */
	private boolean fetchWorkMaterial(ServerLevel level, String civilisationId, PathfinderMob villager,
			WorkerRecord record, ResourceType type, int minimumNeeded, int desiredTotal) {
		int have = workMaterialCount(record, type);
		if (have >= minimumNeeded)
			return false;

		BlockPos chest = nearestDepotChestWithMaterial(level, civilisationId, type, villager.blockPosition(), record);
		if (chest == null) {
			// Missing stock is a legitimate wait state, but the worker should not
			// freeze at an arbitrary point on the map. Return to a distributed post
			// around national storage, where it can immediately notice new stock.
			BlockPos depot = assignedDepotChest(level, civilisationId, record.assignmentIndex);
			if (depot != null) {
				BlockPos wait = formationTarget(level, depot.getX(), depot.getZ(), record.assignmentIndex);
				if (!near(villager, wait, 2.2))
					moveTo(level, villager, wait, record);
				else
					clearMoveTarget(record);
			} else {
				clearMoveTarget(record);
			}
			return true;
		}
		if (walkToDepotChest(level, villager, chest, record, 3.2))
			return true;

		BlockEntity blockEntity = level.getBlockEntity(chest);
		if (!(blockEntity instanceof Container container)) {
			clearMoveTarget(record);
			return true;
		}

		int target = Math.max(minimumNeeded, desiredTotal);
		int requested = Math.max(1, target - have);
		int gained = withdrawMaterialUnits(container, type, requested);
		if (gained > 0) {
			addWorkMaterial(record, type, gained);
			villager.swing(InteractionHand.MAIN_HAND);
			updateCarriedDisplay(villager, record);
		}
		clearMoveTarget(record);
		return true;
	}

	private BlockPos nearestDepotChestWithMaterial(ServerLevel level, String civilisationId, ResourceType type,
			BlockPos from, WorkerRecord record) {
		List<BlockPos> candidates = new ArrayList<>();
		for (DepotRecord depot : depots.values()) {
			if (!depot.active || !Objects.equals(civilisationId, depot.civilisationId) || depot.chests == null)
				continue;
			for (StoredPos stored : depot.chests) {
				if (!isChunkLoaded(level, stored.x, stored.z))
					continue;
				BlockPos pos = stored.toBlockPos();
				BlockEntity blockEntity = level.getBlockEntity(pos);
				if (blockEntity instanceof Container container && countMaterialUnits(container, type) > 0
						&& depotChestHasReachableInteraction(level, pos, from, record.assignmentIndex)) {
					candidates.add(pos);
				}
			}
		}
		if (candidates.isEmpty() && recoverNearbyDepotChests(level, civilisationId, record)) {
			for (DepotRecord depot : depots.values()) {
				if (!depot.active || !Objects.equals(civilisationId, depot.civilisationId) || depot.chests == null)
					continue;
				for (StoredPos stored : depot.chests) {
					if (!isChunkLoaded(level, stored.x, stored.z))
						continue;
					BlockPos pos = stored.toBlockPos();
					BlockEntity blockEntity = level.getBlockEntity(pos);
					if (blockEntity instanceof Container container && countMaterialUnits(container, type) > 0
							&& depotChestHasReachableInteraction(level, pos, from, record.assignmentIndex)) {
						candidates.add(pos);
					}
				}
			}
		}
		if (candidates.isEmpty()) {
			return nearestNearbyCivilisationChest(level, civilisationId, from, record, type, null, 0, null);
		}

		// A stale depot registry may still contain one valid but distant chest, which
		// previously prevented the self-healing scan from ever seeing the real chest
		// directly beside the worker. Probe locally only when the registered nearest
		// option is meaningfully far away; a successful probe registers itself once.
		BlockPos registeredNearest = candidates.stream()
				.min(Comparator.comparingDouble(pos -> distanceSquared(from, pos))).orElse(null);
		if (registeredNearest != null && distanceSquared(from, registeredNearest) > 12.0 * 12.0) {
			BlockPos visible = nearestNearbyCivilisationChest(level, civilisationId, from, record, type, null, 0, null);
			if (visible != null && !candidates.contains(visible))
				candidates.add(visible);
		}
		return selectDistributedNearbyChest(candidates, from, record.assignmentIndex);
	}

	private int countMaterialUnits(Container container, ResourceType type) {
		if (container == null || type == null)
			return 0;
		int total = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.isEmpty())
				continue;
			int units = materialUnitsPerItem(stack.getItem(), type);
			if (units > 0)
				total += units * stack.getCount();
		}
		return total;
	}

	private int countStoredMaterialUnits(ServerLevel level, String civilisationId, ResourceType type) {
		int total = 0;
		for (Container container : depotContainers(level, civilisationId)) {
			total += countMaterialUnits(container, type);
		}
		return total;
	}

	private int withdrawMaterialUnits(Container container, ResourceType type, int desiredUnits) {
		if (container == null || type == null || desiredUnits <= 0)
			return 0;
		int gained = 0;

		if (type == ResourceType.WOOD) {
			// Prefer already-cut planks for exact quantities, then process logs into
			// four plank-equivalent units each when more material is required.
			for (int pass = 0; pass < 2 && gained < desiredUnits; pass++) {
				for (int slot = 0; slot < container.getContainerSize() && gained < desiredUnits; slot++) {
					ItemStack stack = container.getItem(slot);
					if (stack.isEmpty())
						continue;
					int units = materialUnitsPerItem(stack.getItem(), type);
					if (units <= 0 || (pass == 0 && units != 1) || (pass == 1 && units == 1))
						continue;
					int needed = desiredUnits - gained;
					int take = Math.min(stack.getCount(), (needed + units - 1) / units);
					if (take <= 0)
						continue;
					stack.shrink(take);
					if (stack.isEmpty())
						container.setItem(slot, ItemStack.EMPTY);
					gained += take * units;
				}
			}
		} else {
			for (int slot = 0; slot < container.getContainerSize() && gained < desiredUnits; slot++) {
				ItemStack stack = container.getItem(slot);
				if (stack.isEmpty() || materialUnitsPerItem(stack.getItem(), type) != 1)
					continue;
				int take = Math.min(stack.getCount(), desiredUnits - gained);
				stack.shrink(take);
				if (stack.isEmpty())
					container.setItem(slot, ItemStack.EMPTY);
				gained += take;
			}
		}

		if (gained > 0) {
			container.setChanged();
			dirty = true;
		}
		return gained;
	}

	private int materialUnitsPerItem(Item item, ResourceType type) {
		if (item == null || type == null)
			return 0;
		if (type == ResourceType.WOOD) {
			if (isWoodLogItem(item))
				return 4;
			if (isWoodPlankItem(item))
				return 1;
			return 0;
		}
		return resourceForItem(item) == type ? 1 : 0;
	}

	private boolean isWoodLogItem(Item item) {
		return item == Items.OAK_LOG || item == Items.BIRCH_LOG || item == Items.SPRUCE_LOG || item == Items.JUNGLE_LOG
				|| item == Items.ACACIA_LOG || item == Items.DARK_OAK_LOG || item == Items.MANGROVE_LOG
				|| item == Items.CHERRY_LOG || item == Items.PALE_OAK_LOG;
	}

	private boolean isWoodPlankItem(Item item) {
		return item == Items.OAK_PLANKS || item == Items.BIRCH_PLANKS || item == Items.SPRUCE_PLANKS
				|| item == Items.JUNGLE_PLANKS || item == Items.ACACIA_PLANKS || item == Items.DARK_OAK_PLANKS
				|| item == Items.MANGROVE_PLANKS || item == Items.CHERRY_PLANKS || item == Items.PALE_OAK_PLANKS;
	}

	private void carry(WorkerRecord record, ResourceType type, int amount) {
		if (amount <= 0)
			return;
		if (record.carrying == null)
			record.carrying = new LinkedHashMap<>();
		record.carrying.merge(type.name(), amount, Integer::sum);
		dirty = true;
	}

	private int carriedTotal(WorkerRecord record) {
		if (record == null || record.carrying == null)
			return 0;
		int total = 0;
		for (Integer count : record.carrying.values())
			total += Math.max(0, count == null ? 0 : count);
		return total;
	}

	private int depotLoadTotal(WorkerRecord record) {
		if (record == null)
			return 0;
		int total = carriedTotal(record);
		// Bone meal is a farmer's consumable work supply, not farm output. Farmers
		// keep it when making ordinary wheat-deposit trips; soldiers are the logistics
		// carrier that returns salvaged bone meal to depot storage.
		if (parseJob(record.job) == VillagerJob.SOLDIER)
			total += Math.max(0, record.boneMeal);
		return total;
	}

	private City primarySupplyCapital(String civilisationId) {
		return ProvidenceSystem.ownedProvidences(civilisationId).stream().map(Providence::getCity)
				.filter(Objects::nonNull).filter(City::isSupplyCapital)
				.filter(city -> Objects.equals(city.getControllerId(), civilisationId))
				.sorted(Comparator.comparing(City::isNationalCapital).reversed().thenComparing(City::getId)).findFirst()
				.orElse(null);
	}

	private City cityById(String cityId) {
		if (cityId == null)
			return null;
		if (cityLookupCacheTick != ticks) {
			cityLookupCache.clear();
			for (Providence providence : DataManager.getProvidences().values()) {
				City city = providence == null ? null : providence.getCity();
				if (city != null && city.getId() != null)
					cityLookupCache.put(city.getId(), city);
			}
			cityLookupCacheTick = ticks;
		}
		return cityLookupCache.get(cityId);
	}

	private int insertResourceItems(Container container, ResourceType type, int amount) {
		if (container == null || amount <= 0)
			return 0;
		Item item = itemFor(type);
		if (item == null)
			return 0;
		int remaining = amount;
		for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.isEmpty()) {
				int put = Math.min(remaining, new ItemStack(item).getMaxStackSize());
				container.setItem(slot, new ItemStack(item, put));
				remaining -= put;
			} else if (stack.getItem() == item && stack.getCount() < stack.getMaxStackSize()) {
				int put = Math.min(remaining, stack.getMaxStackSize() - stack.getCount());
				stack.grow(put);
				remaining -= put;
			}
		}
		if (remaining != amount) {
			container.setChanged();
			dirty = true;
		}
		return amount - remaining;
	}

	private void compactContainerStacks(Container container) {
		if (container == null)
			return;
		boolean changed = false;
		for (int target = 0; target < container.getContainerSize(); target++) {
			ItemStack dst = container.getItem(target);
			if (dst.isEmpty())
				continue;
			int max = dst.getMaxStackSize();
			if (dst.getCount() >= max)
				continue;
			for (int source = target + 1; source < container.getContainerSize() && dst.getCount() < max; source++) {
				ItemStack src = container.getItem(source);
				if (src.isEmpty() || src.getItem() != dst.getItem())
					continue;
				int move = Math.min(max - dst.getCount(), src.getCount());
				if (move <= 0)
					continue;
				dst.grow(move);
				src.shrink(move);
				if (src.isEmpty())
					container.setItem(source, ItemStack.EMPTY);
				changed = true;
			}
		}
		if (changed) {
			container.setChanged();
			dirty = true;
		}
	}

	private boolean containerCanAccept(Container container, WorkerRecord record) {
		if (container == null || record == null)
			return false;
		if (parseJob(record.job) == VillagerJob.SOLDIER && record.boneMeal > 0
				&& containerCanAcceptItem(container, Items.BONE_MEAL))
			return true;
		if (record.carrying == null)
			return false;
		for (ResourceType type : ResourceType.values()) {
			if (record.carrying.getOrDefault(type.name(), 0) <= 0)
				continue;
			Item item = itemFor(type);
			if (item == null)
				continue;
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				ItemStack stack = container.getItem(slot);
				if (stack.isEmpty() || (stack.getItem() == item && stack.getCount() < stack.getMaxStackSize())) {
					return true;
				}
			}
		}
		return false;
	}

	private int insertResourceItems(ServerLevel level, String civilisationId, ResourceType type, int amount) {
		if (amount <= 0)
			return 0;
		Item item = itemFor(type);
		if (item == null)
			return 0;
		int remaining = amount;
		for (Container container : depotContainers(level, civilisationId)) {
			for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
				ItemStack stack = container.getItem(slot);
				if (stack.isEmpty()) {
					int put = Math.min(remaining, 64);
					container.setItem(slot, new ItemStack(item, put));
					remaining -= put;
				} else if (stack.getItem() == item && stack.getCount() < stack.getMaxStackSize()) {
					int put = Math.min(remaining, stack.getMaxStackSize() - stack.getCount());
					stack.grow(put);
					remaining -= put;
				}
			}
			container.setChanged();
			if (remaining <= 0)
				break;
		}
		if (remaining != amount)
			dirty = true;
		return amount - remaining;
	}

	private int removeResourceItems(ServerLevel level, String civilisationId, ResourceType type, int amount) {
		if (amount <= 0)
			return 0;
		int remaining = amount;
		for (Container container : depotContainers(level, civilisationId)) {
			for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
				ItemStack stack = container.getItem(slot);
				if (stack.isEmpty() || resourceForItem(stack.getItem()) != type)
					continue;
				int take = Math.min(remaining, stack.getCount());
				stack.shrink(take);
				if (stack.isEmpty())
					container.setItem(slot, ItemStack.EMPTY);
				remaining -= take;
			}
			container.setChanged();
			if (remaining <= 0)
				break;
		}
		if (remaining != amount)
			dirty = true;
		return amount - remaining;
	}

	private int countResourceItems(ServerLevel level, String civilisationId, ResourceType type) {
		int total = 0;
		for (Container container : depotContainers(level, civilisationId)) {
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				ItemStack stack = container.getItem(slot);
				if (!stack.isEmpty() && resourceForItem(stack.getItem()) == type)
					total += stack.getCount();
			}
		}
		return total;
	}

	private int removeSpecificItems(ServerLevel level, String civilisationId, List<Item> accepted, int amount) {
		if (amount <= 0 || accepted == null || accepted.isEmpty())
			return 0;
		int remaining = amount;
		for (Container container : depotContainers(level, civilisationId)) {
			for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
				ItemStack stack = container.getItem(slot);
				if (stack.isEmpty() || !accepted.contains(stack.getItem()))
					continue;
				int take = Math.min(remaining, stack.getCount());
				stack.shrink(take);
				if (stack.isEmpty())
					container.setItem(slot, ItemStack.EMPTY);
				remaining -= take;
			}
			container.setChanged();
			if (remaining <= 0)
				break;
		}
		if (remaining != amount)
			dirty = true;
		return amount - remaining;
	}

	private int insertSpecificItems(ServerLevel level, String civilisationId, Item item, int amount) {
		if (item == null || amount <= 0)
			return 0;
		int remaining = amount;
		for (Container container : depotContainers(level, civilisationId)) {
			for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
				ItemStack stack = container.getItem(slot);
				if (stack.isEmpty()) {
					int put = Math.min(remaining, new ItemStack(item).getMaxStackSize());
					container.setItem(slot, new ItemStack(item, put));
					remaining -= put;
				} else if (stack.getItem() == item && stack.getCount() < stack.getMaxStackSize()) {
					int put = Math.min(remaining, stack.getMaxStackSize() - stack.getCount());
					stack.grow(put);
					remaining -= put;
				}
			}
			container.setChanged();
			if (remaining <= 0)
				break;
		}
		if (remaining != amount)
			dirty = true;
		return amount - remaining;
	}

	private int countSpecificItems(Container container, Item item) {
		if (container == null || item == null)
			return 0;
		int total = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (!stack.isEmpty() && stack.getItem() == item)
				total += stack.getCount();
		}
		return total;
	}

	private int removeSpecificItems(Container container, List<Item> accepted, int amount) {
		if (container == null || amount <= 0 || accepted == null || accepted.isEmpty())
			return 0;
		int remaining = amount;
		for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.isEmpty() || !accepted.contains(stack.getItem()))
				continue;
			int take = Math.min(remaining, stack.getCount());
			stack.shrink(take);
			if (stack.isEmpty())
				container.setItem(slot, ItemStack.EMPTY);
			remaining -= take;
		}
		if (remaining != amount) {
			container.setChanged();
			dirty = true;
		}
		return amount - remaining;
	}

	private int countSpecificItems(ServerLevel level, String civilisationId, Item item) {
		if (item == null)
			return 0;
		int total = 0;
		for (Container container : depotContainers(level, civilisationId)) {
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				ItemStack stack = container.getItem(slot);
				if (!stack.isEmpty() && stack.getItem() == item)
					total += stack.getCount();
			}
		}
		return total;
	}

	private BlockPos nearestDepotChestWithItemCount(ServerLevel level, String civilisationId, Item item, int required,
			BlockPos from, WorkerRecord record) {
		if (item == null || required <= 0)
			return null;
		BlockPos best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (DepotRecord depot : depots.values()) {
			if (!depot.active || !Objects.equals(civilisationId, depot.civilisationId) || depot.chests == null)
				continue;
			for (StoredPos stored : depot.chests) {
				if (!isChunkLoaded(level, stored.x, stored.z))
					continue;
				BlockPos pos = stored.toBlockPos();
				BlockEntity blockEntity = level.getBlockEntity(pos);
				if (!(blockEntity instanceof Container container))
					continue;
				int count = 0;
				for (int slot = 0; slot < container.getContainerSize(); slot++) {
					ItemStack stack = container.getItem(slot);
					if (!stack.isEmpty() && stack.getItem() == item)
						count += stack.getCount();
				}
				if (count < required)
					continue;
				if (!depotChestHasReachableInteraction(level, pos, from, record.assignmentIndex))
					continue;
				double distance = distanceSquared(from, pos);
				if (distance < bestDistance) {
					bestDistance = distance;
					best = pos;
				}
			}
		}
		if (best == null) {
			best = nearestNearbyCivilisationChest(level, civilisationId, from, record, null, item, required, null);
		}
		return best;
	}

	private BlockPos nearestDepotChestWithSpecificItem(ServerLevel level, String civilisationId, Item item,
			BlockPos from, WorkerRecord record) {
		BlockPos best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (DepotRecord depot : depots.values()) {
			if (!depot.active || !Objects.equals(civilisationId, depot.civilisationId) || depot.chests == null)
				continue;
			for (StoredPos stored : depot.chests) {
				BlockPos pos = stored.toBlockPos();
				if (!isChunkLoaded(level, stored.x, stored.z))
					continue;
				BlockEntity blockEntity = level.getBlockEntity(pos);
				if (!(blockEntity instanceof Container container))
					continue;
				boolean found = false;
				for (int slot = 0; slot < container.getContainerSize(); slot++) {
					ItemStack stack = container.getItem(slot);
					if (!stack.isEmpty() && stack.getItem() == item) {
						found = true;
						break;
					}
				}
				if (!found)
					continue;
				if (!depotChestHasReachableInteraction(level, pos, from, record.assignmentIndex))
					continue;
				double distance = distanceSquared(from, pos);
				if (distance < bestDistance) {
					bestDistance = distance;
					best = pos;
				}
			}
		}
		if (best == null) {
			best = nearestNearbyCivilisationChest(level, civilisationId, from, record, null, item, 1, null);
		}
		return best;
	}

	private boolean containerCanAcceptItem(Container container, Item item) {
		if (container == null || item == null)
			return false;
		int max = new ItemStack(item).getMaxStackSize();
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.isEmpty()
					|| (stack.getItem() == item && stack.getCount() < Math.min(max, stack.getMaxStackSize()))) {
				return true;
			}
		}
		return false;
	}

	private BlockPos nearestDepotChestAcceptingItem(ServerLevel level, String civilisationId, Item item, BlockPos from,
			WorkerRecord record) {
		BlockPos best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (DepotRecord depot : depots.values()) {
			if (!depot.active || !Objects.equals(civilisationId, depot.civilisationId) || depot.chests == null)
				continue;
			for (StoredPos stored : depot.chests) {
				if (!isChunkLoaded(level, stored.x, stored.z))
					continue;
				BlockPos pos = stored.toBlockPos();
				BlockEntity blockEntity = level.getBlockEntity(pos);
				if (!(blockEntity instanceof Container container) || !containerCanAcceptItem(container, item))
					continue;
				if (!depotChestHasReachableInteraction(level, pos, from, record.assignmentIndex))
					continue;
				double distance = distanceSquared(from, pos);
				if (distance < bestDistance) {
					bestDistance = distance;
					best = pos;
				}
			}
		}
		if (best == null && recoverNearbyDepotChests(level, civilisationId, record)) {
			for (DepotRecord depot : depots.values()) {
				if (!depot.active || !Objects.equals(civilisationId, depot.civilisationId) || depot.chests == null)
					continue;
				for (StoredPos stored : depot.chests) {
					if (!isChunkLoaded(level, stored.x, stored.z))
						continue;
					BlockPos pos = stored.toBlockPos();
					BlockEntity blockEntity = level.getBlockEntity(pos);
					if (!(blockEntity instanceof Container container) || !containerCanAcceptItem(container, item))
						continue;
					if (!depotChestHasReachableInteraction(level, pos, from, record.assignmentIndex))
						continue;
					double distance = distanceSquared(from, pos);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = pos;
					}
				}
			}
		}
		if (best == null) {
			best = nearestNearbyCivilisationChest(level, civilisationId, from, record, null, null, 0, item);
		}
		return best;
	}

	private boolean depotNeedsExpansion(ServerLevel level, String civilisationId) {
		boolean any = false;
		for (Container container : depotContainers(level, civilisationId)) {
			any = true;
			compactContainerStacks(container);
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				ItemStack stack = container.getItem(slot);
				// Do not call storage "full" or spend wood on another chest until
				// every existing slot is genuinely saturated.
				if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize())
					return false;
			}
		}
		return any;
	}

	private boolean depositFactoryProduct(ServerLevel level, String civilisationId, PathfinderMob villager,
			WorkerRecord record) {
		if (record.factoryProduct == null || record.factoryProductCount <= 0)
			return false;

		if ("CHEST".equals(record.factoryProduct)) {
			City home = cityById(record.homeCityId);
			if (home == null) {
				record.factoryProduct = null;
				record.factoryProductCount = 0;
				return false;
			}
			BlockPos place = nextDepotExpansionPosition(level, civilisationId, home);
			if (place == null)
				return true;
			if (!near(villager, place, 3.2)) {
				moveTo(level, villager, place, record);
				return true;
			}
			BlockState placementState = level.getBlockState(place);
			if (!placementState.isAir()) {
				// Another worker may have filled this horizontal depot position while
				// we were walking to it. Never consume our chest product and never
				// respond by putting another chest above the occupied column.
				clearMoveTarget(record);
				return true;
			}
			level.setBlockAndUpdate(place, Blocks.CHEST.defaultBlockState());
			if (isContainer(level, place)) {
				DepotRecord depot = depots.get(depotKey(civilisationId, home.getId()));
				if (depot != null) {
					boolean known = depot.chests.stream()
							.anyMatch(pos -> pos.x == place.getX() && pos.y == place.getY() && pos.z == place.getZ());
					if (!known)
						depot.chests.add(new StoredPos(place.getX(), place.getY(), place.getZ()));
				}
				record.factoryProduct = null;
				record.factoryProductCount = 0;
				record.expandingDepot = false;
				updateCarriedDisplay(villager, record);
				villager.swing(InteractionHand.MAIN_HAND);
				clearMoveTarget(record);
				dirty = true;
			}
			return true;
		}

		Item item = factoryProductItem(record.factoryProduct);
		if (item == null) {
			record.factoryProduct = null;
			record.factoryProductCount = 0;
			dirty = true;
			return false;
		}
		BlockPos chest = nearestDepotChestAcceptingItem(level, civilisationId, item, villager.blockPosition(), record);
		if (chest == null)
			return true;
		if (walkToDepotChest(level, villager, chest, record, 3.2))
			return true;
		BlockEntity blockEntity = level.getBlockEntity(chest);
		if (!(blockEntity instanceof Container container))
			return true;
		int inserted = insertSpecificItems(container, item, record.factoryProductCount);
		if (inserted > 0) {
			record.factoryProductCount -= inserted;
			villager.swing(InteractionHand.MAIN_HAND);
			dirty = true;
		}
		if (record.factoryProductCount <= 0) {
			record.factoryProduct = null;
			record.factoryProductCount = 0;
			updateCarriedDisplay(villager, record);
			clearMoveTarget(record);
		}
		return true;
	}

	private int insertSpecificItems(Container container, Item item, int amount) {
		if (container == null || item == null || amount <= 0)
			return 0;
		int remaining = amount;
		for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.isEmpty()) {
				int put = Math.min(remaining, new ItemStack(item).getMaxStackSize());
				container.setItem(slot, new ItemStack(item, put));
				remaining -= put;
			} else if (stack.getItem() == item && stack.getCount() < stack.getMaxStackSize()) {
				int put = Math.min(remaining, stack.getMaxStackSize() - stack.getCount());
				stack.grow(put);
				remaining -= put;
			}
		}
		if (remaining != amount) {
			container.setChanged();
			dirty = true;
		}
		return amount - remaining;
	}

	private BlockPos nextDepotExpansionPosition(ServerLevel level, String civilisationId, City city) {
		DepotRecord depot = depots.get(depotKey(civilisationId, city.getId()));
		if (depot == null)
			return null;
		Set<String> occupied = new HashSet<>();
		for (StoredPos pos : depot.chests)
			occupied.add(pos.x + ":" + pos.z);

		for (int ring = 3; ring <= 20; ring += 2) {
			for (int dz = -ring; dz <= ring; dz += 2) {
				for (int dx = -ring; dx <= ring; dx += 2) {
					if (Math.abs(dx) != ring && Math.abs(dz) != ring)
						continue;
					int x = city.getBlockX() + dx;
					int z = city.getBlockZ() + dz;
					if (occupied.contains(x + ":" + z))
						continue;
					if (!isChunkLoaded(level, x, z))
						continue;
					BlockPos existingChest = existingDepotContainerInColumn(level, x, z);
					if (existingChest != null) {
						depot.chests
								.add(new StoredPos(existingChest.getX(), existingChest.getY(), existingChest.getZ()));
						occupied.add(x + ":" + z);
						dirty = true;
						continue;
					}
					int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
					BlockPos pos = new BlockPos(x, y, z);
					if (level.getBlockState(pos).isAir())
						return pos;
				}
			}
		}
		return null;
	}

	private String factoryProductToken(Item item) {
		if (item == null)
			return null;
		if (item == Items.BREAD)
			return "BREAD";
		if (item == Items.CHARCOAL)
			return "CHARCOAL";
		if (item == Items.TORCH)
			return "TORCH";
		if (item == Items.WOODEN_AXE)
			return "WOODEN_AXE";
		if (item == Items.STONE_AXE)
			return "STONE_AXE";
		if (item == Items.IRON_AXE)
			return "IRON_AXE";
		if (item == Items.DIAMOND_AXE)
			return "DIAMOND_AXE";
		if (item == Items.WOODEN_PICKAXE)
			return "WOODEN_PICKAXE";
		if (item == Items.STONE_PICKAXE)
			return "STONE_PICKAXE";
		if (item == Items.IRON_PICKAXE)
			return "IRON_PICKAXE";
		if (item == Items.DIAMOND_PICKAXE)
			return "DIAMOND_PICKAXE";
		if (item == Items.WOODEN_HOE)
			return "WOODEN_HOE";
		if (item == Items.STONE_HOE)
			return "STONE_HOE";
		if (item == Items.IRON_HOE)
			return "IRON_HOE";
		if (item == Items.DIAMOND_HOE)
			return "DIAMOND_HOE";
		if (item == Items.WOODEN_SWORD)
			return "WOODEN_SWORD";
		if (item == Items.STONE_SWORD)
			return "STONE_SWORD";
		if (item == Items.IRON_SWORD)
			return "IRON_SWORD";
		if (item == Items.DIAMOND_SWORD)
			return "DIAMOND_SWORD";
		String id = MinecraftItemRegistry.itemId(item);
		return id == null || id.isBlank() ? null : "ITEM:" + id;
	}

	private Item factoryProductItem(String token) {
		if (token == null)
			return null;
		return switch (token) {
		case "BREAD" -> Items.BREAD;
		case "CHARCOAL" -> Items.CHARCOAL;
		case "TORCH" -> Items.TORCH;
		case "CHEST" -> Items.CHEST;
		case "CHEST_ITEM" -> Items.CHEST;
		case "WOODEN_AXE" -> Items.WOODEN_AXE;
		case "STONE_AXE" -> Items.STONE_AXE;
		case "IRON_AXE" -> Items.IRON_AXE;
		case "DIAMOND_AXE" -> Items.DIAMOND_AXE;
		case "WOODEN_PICKAXE" -> Items.WOODEN_PICKAXE;
		case "STONE_PICKAXE" -> Items.STONE_PICKAXE;
		case "IRON_PICKAXE" -> Items.IRON_PICKAXE;
		case "DIAMOND_PICKAXE" -> Items.DIAMOND_PICKAXE;
		case "WOODEN_HOE" -> Items.WOODEN_HOE;
		case "STONE_HOE" -> Items.STONE_HOE;
		case "IRON_HOE" -> Items.IRON_HOE;
		case "DIAMOND_HOE" -> Items.DIAMOND_HOE;
		case "WOODEN_SWORD" -> Items.WOODEN_SWORD;
		case "STONE_SWORD" -> Items.STONE_SWORD;
		case "IRON_SWORD" -> Items.IRON_SWORD;
		case "DIAMOND_SWORD" -> Items.DIAMOND_SWORD;
		default -> token.startsWith("ITEM:") ? MinecraftItemRegistry.item(token.substring("ITEM:".length())) : null;
		};
	}

	private int countUsableChestSlots(ServerLevel level, String civilisationId) {
		int slots = 0;
		for (Container container : depotContainers(level, civilisationId)) {
			slots += container.getContainerSize();
		}
		return slots;
	}

	private List<Container> depotContainers(ServerLevel level, String civilisationId) {
		List<Container> result = new ArrayList<>();
		for (DepotRecord depot : depots.values()) {
			if (!depot.active || !Objects.equals(civilisationId, depot.civilisationId) || depot.chests == null)
				continue;
			for (StoredPos stored : depot.chests) {
				if (!isChunkLoaded(level, stored.x, stored.z))
					continue;
				BlockEntity blockEntity = level.getBlockEntity(stored.toBlockPos());
				if (blockEntity instanceof Container container)
					result.add(container);
			}
		}
		return result;
	}

	/**
	 * Gives each worker a deterministic standing position around a depot chest.
	 * Twenty-four positions fit inside the normal 3.2-block interaction radius, so
	 * even when one chest is the only chest containing wood the population does not
	 * all path to the exact same block centre. The worker still interacts with the
	 * real chest; this only distributes the approach/standing positions.
	 */
	private BlockPos depotInteractionPost(ServerLevel level, BlockPos chest, BlockPos from, int assignmentIndex) {
		if (level == null || chest == null)
			return chest;
		List<BlockPos> posts = new ArrayList<>();

		// Search relative to the chest's actual Y, not only the heightmap. On uneven
		// terrain the old surface-only post could be several blocks above/below the
		// chest, so the worker would reach the post but still be outside the 3.2-block
		// interaction radius and visibly bounce around the same spot forever.
		for (int dz = -2; dz <= 2; dz++) {
			for (int dx = -2; dx <= 2; dx++) {
				if (dx == 0 && dz == 0)
					continue;
				int x = chest.getX() + dx;
				int z = chest.getZ() + dz;
				if (!isChunkLoaded(level, x, z))
					continue;

				// Prefer a safe feet position near the chest's own elevation.
				for (int dy = -2; dy <= 2; dy++) {
					BlockPos candidate = new BlockPos(x, chest.getY() + dy, z);
					if (!isWorkerStandable(level, candidate))
						continue;
					if (distanceSquared(candidate, chest) > 3.0 * 3.0)
						continue;
					posts.add(candidate);
				}

				// Heightmap fallback for ordinary flat/slope terrain, still requiring
				// the final standing position to be genuinely within chest reach.
				int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				BlockPos surface = new BlockPos(x, surfaceY, z);
				if (isWorkerStandable(level, surface) && distanceSquared(surface, chest) <= 3.0 * 3.0
						&& !posts.contains(surface)) {
					posts.add(surface);
				}
			}
		}
		if (posts.isEmpty())
			return null;
		BlockPos origin = from == null ? chest : from;
		posts.sort((a, b) -> {
			int byWorkerDistance = Double.compare(distanceSquared(origin, a), distanceSquared(origin, b));
			if (byWorkerDistance != 0)
				return byWorkerDistance;
			int byChestDistance = Double.compare(distanceSquared(a, chest), distanceSquared(b, chest));
			if (byChestDistance != 0)
				return byChestDistance;
			int byZ = Integer.compare(a.getZ(), b.getZ());
			return byZ != 0 ? byZ : Integer.compare(a.getX(), b.getX());
		});

		// Spread a crowd only among posts on the worker's reachable side of the chest.
		// The old assignment-index modulo could deliberately choose the opposite side
		// of a chest stack/wall even when a valid post was one block from the worker.
		double nearestDistance = Math.sqrt(distanceSquared(origin, posts.get(0)));
		int localWindow = 1;
		while (localWindow < posts.size() && localWindow < 4) {
			double distance = Math.sqrt(distanceSquared(origin, posts.get(localWindow)));
			if (distance > nearestDistance + 2.5)
				break;
			localWindow++;
		}
		return posts.get(Math.floorMod(assignmentIndex, localWindow));
	}

	private boolean depotChestHasReachableInteraction(ServerLevel level, BlockPos chest, BlockPos from,
			int assignmentIndex) {
		if (level == null || chest == null || from == null)
			return false;
		if (distanceSquared(from, chest) <= 3.2 * 3.2)
			return true;
		return depotInteractionPost(level, chest, from, assignmentIndex) != null;
	}

	/**
	 * Walks to a real standable interaction post rather than asking PathNavigation
	 * to enter the solid chest block itself. Returns true while travel/recovery
	 * consumes this tick; false means the worker is already close enough to
	 * interact.
	 */
	private boolean walkToDepotChest(ServerLevel level, PathfinderMob villager, BlockPos chest, WorkerRecord record,
			double interactionRange) {
		if (level == null || villager == null || chest == null || record == null)
			return true;
		if (near(villager, chest, interactionRange)) {
			// Arrival completes the movement transaction even though the caller still
			// has to insert/remove items. Cancel any asynchronous planner result now;
			// otherwise it can arrive after the chest interaction and pull the worker
			// back to the old standing post, producing depot-side jitter.
			finishLocalTaskMovement(villager, record);
			return false;
		}

		// Once a worker has chosen a valid standing post for this chest, keep it for
		// the whole transaction. Recomputing the "best" side from the worker's changing
		// position every think tick can alternate between two posts and create the
		// visible one-block jitter beside storage.
		BlockPos post = null;
		if (record.hasMoveTarget) {
			BlockPos existingPost = new BlockPos(record.moveTargetX, record.moveTargetY, record.moveTargetZ);
			if (distanceSquared(existingPost, chest) <= 3.2 * 3.2
					&& isSafeNavigationFeet(level, record, existingPost)) {
				post = existingPost;
			}
		}
		if (post == null) {
			post = depotInteractionPost(level, chest, villager.blockPosition(), record.assignmentIndex);
		}
		if (post == null) {
			logNavigationIssue(record, villager, chest, "depot-no-interaction-post", NavigationFailure.PATH_NOT_FOUND);
			finishLocalTaskMovement(villager, record);
			return true;
		}
		moveTo(level, villager, post, record);
		return true;
	}

	private BlockPos nearestUsableDepotChest(ServerLevel level, String civilisationId, BlockPos from,
			WorkerRecord record) {
		List<BlockPos> candidates = new ArrayList<>();
		for (DepotRecord depot : depots.values()) {
			if (!depot.active || !Objects.equals(civilisationId, depot.civilisationId) || depot.chests == null)
				continue;
			for (StoredPos stored : depot.chests) {
				BlockPos pos = stored.toBlockPos();
				BlockEntity blockEntity = level.getBlockEntity(pos);
				if (blockEntity instanceof Container container) {
					if (!containerCanAccept(container, record))
						compactContainerStacks(container);
					if (containerCanAccept(container, record)
							&& depotChestHasReachableInteraction(level, pos, from, record.assignmentIndex)) {
						candidates.add(pos);
					}
				}
			}
		}
		if (candidates.isEmpty() && recoverNearbyDepotChests(level, civilisationId, record)) {
			for (DepotRecord depot : depots.values()) {
				if (!depot.active || !Objects.equals(civilisationId, depot.civilisationId) || depot.chests == null)
					continue;
				for (StoredPos stored : depot.chests) {
					if (!isChunkLoaded(level, stored.x, stored.z))
						continue;
					BlockPos pos = stored.toBlockPos();
					BlockEntity blockEntity = level.getBlockEntity(pos);
					if (blockEntity instanceof Container container) {
						if (!containerCanAccept(container, record))
							compactContainerStacks(container);
						if (containerCanAccept(container, record)
								&& depotChestHasReachableInteraction(level, pos, from, record.assignmentIndex)) {
							candidates.add(pos);
						}
					}
				}
			}
		}
		if (candidates.isEmpty()) {
			return nearestNearbyCivilisationChest(level, civilisationId, from, record, null, null, 0, null);
		}
		BlockPos registeredNearest = candidates.stream()
				.min(Comparator.comparingDouble(pos -> distanceSquared(from, pos))).orElse(null);
		if (registeredNearest != null && distanceSquared(from, registeredNearest) > 12.0 * 12.0) {
			BlockPos visible = nearestNearbyCivilisationChest(level, civilisationId, from, record, null, null, 0, null);
			if (visible != null && !candidates.contains(visible))
				candidates.add(visible);
		}
		return selectDistributedNearbyChest(candidates, from, record.assignmentIndex);
	}

	/**
	 * Chooses among genuinely nearby equivalent chests. This retains crowd
	 * spreading without sending a worker standing beside one chest to the
	 * third/fourth closest chest across a terrace, wall or stack simply because of
	 * assignmentIndex.
	 */
	private BlockPos selectDistributedNearbyChest(List<BlockPos> candidates, BlockPos from, int assignmentIndex) {
		if (candidates == null || candidates.isEmpty())
			return null;
		BlockPos origin = from == null ? candidates.get(0) : from;
		candidates.sort(Comparator.comparingDouble(pos -> distanceSquared(origin, pos)));
		double nearestDistance = Math.sqrt(distanceSquared(origin, candidates.get(0)));
		int window = 1;
		while (window < candidates.size() && window < 4) {
			double distance = Math.sqrt(distanceSquared(origin, candidates.get(window)));
			if (distance > nearestDistance + 5.0)
				break;
			window++;
		}
		return candidates.get(Math.floorMod(assignmentIndex, window));
	}

	/**
	 * Last-resort physical lookup for a chest the worker can literally see/reach
	 * nearby even if the persisted DepotRecord is stale. The chest is accepted only
	 * when the worker is close to a city command post currently controlled by its
	 * civilisation, so arbitrary wilderness/player chests are not silently adopted
	 * as national stock.
	 *
	 * Exactly one optional requirement mode is used by callers: - material != null:
	 * chest must contain that resource; - requiredItem != null: chest must contain
	 * requiredCount of that item; - acceptedItem != null: chest must have room for
	 * that item; - otherwise: chest must have room for at least one resource the
	 * worker carries.
	 */
	private BlockPos nearestNearbyCivilisationChest(ServerLevel level, String civilisationId, BlockPos from,
			WorkerRecord record, ResourceType material, Item requiredItem, int requiredCount, Item acceptedItem) {
		if (level == null || civilisationId == null || from == null)
			return null;

		City nearbyCity = null;
		double bestCityDistance = 32.0 * 32.0;
		for (Providence providence : DataManager.getProvidences().values()) {
			if (providence == null || providence.getCity() == null)
				continue;
			City city = providence.getCity();
			if (!Objects.equals(civilisationId, city.getControllerId()))
				continue;
			double distance = horizontalDistanceSquared(from,
					new BlockPos(city.getBlockX(), from.getY(), city.getBlockZ()));
			if (distance <= bestCityDistance) {
				bestCityDistance = distance;
				nearbyCity = city;
			}
		}
		if (nearbyCity == null)
			return null;

		BlockPos best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		int radius = 12;
		int minY = from.getY() - 8;
		int maxY = from.getY() + 8;
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				int x = from.getX() + dx;
				int z = from.getZ() + dz;
				if (!isChunkLoaded(level, x, z))
					continue;
				for (int y = minY; y <= maxY; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = level.getBlockState(pos);
					if (!state.is(Blocks.CHEST) && !state.is(Blocks.TRAPPED_CHEST))
						continue;
					BlockEntity blockEntity = level.getBlockEntity(pos);
					if (!(blockEntity instanceof Container container))
						continue;

					boolean usable;
					if (material != null) {
						usable = countMaterialUnits(container, material) > 0;
					} else if (requiredItem != null) {
						int count = 0;
						for (int slot = 0; slot < container.getContainerSize(); slot++) {
							ItemStack stack = container.getItem(slot);
							if (!stack.isEmpty() && stack.getItem() == requiredItem)
								count += stack.getCount();
						}
						usable = count >= Math.max(1, requiredCount);
					} else if (acceptedItem != null) {
						usable = containerCanAcceptItem(container, acceptedItem);
					} else {
						if (record == null)
							continue;
						if (!containerCanAccept(container, record))
							compactContainerStacks(container);
						usable = containerCanAccept(container, record);
					}
					if (!usable)
						continue;
					int assignmentIndex = record == null ? 0 : record.assignmentIndex;
					if (!depotChestHasReachableInteraction(level, pos, from, assignmentIndex))
						continue;

					double distance = distanceSquared(from, pos);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = pos;
					}
				}
			}
		}

		if (best != null && registerRecoveredDepotChest(civilisationId, nearbyCity, best)) {
			System.out.println("[GrandStrategy][Depot] adopted nearby visible chest" + " worker="
					+ (record == null ? "none" : String.valueOf(record.uuid)) + " civ=" + civilisationId + " city="
					+ nearbyCity.getId() + " chest=" + best.getX() + "," + best.getY() + "," + best.getZ());
		}
		return best;
	}

	private BlockPos nearestLocalCommandPostChest(ServerLevel level, City home, BlockPos from, WorkerRecord record) {
		if (level == null || home == null || from == null || record == null)
			return null;
		BlockPos best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		int radius = 10;
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				int x = home.getBlockX() + dx;
				int z = home.getBlockZ() + dz;
				if (!isChunkLoaded(level, x, z))
					continue;
				int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				for (int dy = -12; dy <= 2; dy++) {
					BlockPos pos = new BlockPos(x, surfaceY + dy, z);
					BlockState state = level.getBlockState(pos);
					if (!state.is(Blocks.CHEST) && !state.is(Blocks.TRAPPED_CHEST))
						continue;
					BlockEntity blockEntity = level.getBlockEntity(pos);
					if (!(blockEntity instanceof Container container))
						continue;
					if (!containerCanAccept(container, record))
						compactContainerStacks(container);
					if (!containerCanAccept(container, record))
						continue;
					double distance = distanceSquared(from, pos);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = pos;
					}
				}
			}
		}
		if (best != null)
			registerRecoveredDepotChest(record.civilisationId, home, best);
		return best;
	}

	private boolean depotStorageActuallyFull(ServerLevel level, String civilisationId, City home, WorkerRecord record) {
		if (level == null || record == null || depotLoadTotal(record) <= 0)
			return false;
		boolean foundContainer = false;
		for (Container container : depotContainers(level, civilisationId)) {
			foundContainer = true;
			if (!containerCanAccept(container, record))
				compactContainerStacks(container);
			if (containerCanAccept(container, record))
				return false;
		}
		// Also inspect real command-post storage around the worker's home. This
		// covers repaired/legacy saves where a chest exists physically before its
		// DepotRecord has been rebuilt.
		if (home != null) {
			int radius = 10;
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					int x = home.getBlockX() + dx;
					int z = home.getBlockZ() + dz;
					if (!isChunkLoaded(level, x, z))
						continue;
					int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
					for (int dy = -12; dy <= 2; dy++) {
						BlockPos pos = new BlockPos(x, surfaceY + dy, z);
						BlockState state = level.getBlockState(pos);
						if (!state.is(Blocks.CHEST) && !state.is(Blocks.TRAPPED_CHEST))
							continue;
						BlockEntity blockEntity = level.getBlockEntity(pos);
						if (!(blockEntity instanceof Container container))
							continue;
						foundContainer = true;
						if (!containerCanAccept(container, record))
							compactContainerStacks(container);
						if (containerCanAccept(container, record))
							return false;
					}
				}
			}
		}
		// "Storage full" means real containers were found and every one of them is
		// unable to accept the resources this worker is carrying. Missing/unloaded
		// storage is reported as "Finding storage", not as a false full chest.
		return foundContainer;
	}

	private boolean isContainer(ServerLevel level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof Container;
	}

	private Item itemFor(ResourceType type) {
		return switch (type) {
		case FOOD -> Items.WHEAT;
		case WOOD -> Items.OAK_LOG;
		case STONE -> Items.COBBLESTONE;
		case IRON -> Items.IRON_INGOT;
		case COAL -> Items.COAL;
		case GOLD -> Items.GOLD_INGOT;
		case COPPER -> Items.COPPER_INGOT;
		case REDSTONE -> Items.REDSTONE;
		case LAPIS -> Items.LAPIS_LAZULI;
		case EMERALD -> Items.EMERALD;
		case DIAMOND -> Items.DIAMOND;
		case SUPPLIES -> Items.BREAD;
		};
	}

	private ResourceType resourceForOre(BlockState state) {
		if (state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE))
			return ResourceType.IRON;
		if (state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE))
			return ResourceType.COAL;
		if (state.is(Blocks.GOLD_ORE) || state.is(Blocks.DEEPSLATE_GOLD_ORE))
			return ResourceType.GOLD;
		if (state.is(Blocks.COPPER_ORE) || state.is(Blocks.DEEPSLATE_COPPER_ORE))
			return ResourceType.COPPER;
		if (state.is(Blocks.REDSTONE_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE))
			return ResourceType.REDSTONE;
		if (state.is(Blocks.LAPIS_ORE) || state.is(Blocks.DEEPSLATE_LAPIS_ORE))
			return ResourceType.LAPIS;
		if (state.is(Blocks.EMERALD_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE))
			return ResourceType.EMERALD;
		if (isDiamondOre(state))
			return ResourceType.DIAMOND;
		return null;
	}

	private boolean isAnyOre(BlockState state) {
		return resourceForOre(state) != null;
	}

	private boolean isDiamondOre(BlockState state) {
		return state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE);
	}

	private boolean isStone(BlockState state) {
		return state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.TUFF);
	}

	private boolean isLog(BlockState state) {
		return state.is(Blocks.OAK_LOG) || state.is(Blocks.BIRCH_LOG) || state.is(Blocks.SPRUCE_LOG)
				|| state.is(Blocks.JUNGLE_LOG) || state.is(Blocks.ACACIA_LOG) || state.is(Blocks.DARK_OAK_LOG)
				|| state.is(Blocks.MANGROVE_LOG) || state.is(Blocks.CHERRY_LOG) || state.is(Blocks.PALE_OAK_LOG);
	}

	private BlockState saplingFor(BlockState log) {
		if (log.is(Blocks.BIRCH_LOG))
			return Blocks.BIRCH_SAPLING.defaultBlockState();
		if (log.is(Blocks.SPRUCE_LOG))
			return Blocks.SPRUCE_SAPLING.defaultBlockState();
		if (log.is(Blocks.JUNGLE_LOG))
			return Blocks.JUNGLE_SAPLING.defaultBlockState();
		if (log.is(Blocks.ACACIA_LOG))
			return Blocks.ACACIA_SAPLING.defaultBlockState();
		if (log.is(Blocks.DARK_OAK_LOG))
			return Blocks.DARK_OAK_SAPLING.defaultBlockState();
		if (log.is(Blocks.MANGROVE_LOG))
			return Blocks.MANGROVE_PROPAGULE.defaultBlockState();
		if (log.is(Blocks.CHERRY_LOG))
			return Blocks.CHERRY_SAPLING.defaultBlockState();
		if (log.is(Blocks.PALE_OAK_LOG))
			return Blocks.PALE_OAK_SAPLING.defaultBlockState();
		return Blocks.OAK_SAPLING.defaultBlockState();
	}

	private boolean isSoil(BlockState state) {
		return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
				|| state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.PODZOL) || state.is(Blocks.MUD);
	}

	private boolean isRoad(BlockState state) {
		return state.is(Blocks.DIRT_PATH) || state.is(Blocks.GRAVEL) || state.is(Blocks.STONE_BRICKS);
	}

	private boolean isChunkLoaded(ServerLevel level, int blockX, int blockZ) {
		return level.getChunkSource().getChunkNow(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16)) != null;
	}

	private boolean near(PathfinderMob villager, BlockPos pos, double radius) {
		return villager.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5) <= radius * radius;
	}

	private double distanceSquared(BlockPos a, BlockPos b) {
		long dx = (long) a.getX() - b.getX();
		long dy = (long) a.getY() - b.getY();
		long dz = (long) a.getZ() - b.getZ();
		return dx * dx + dy * dy + dz * dz;
	}

	/**
	 * Squared X/Z distance used for strategic waypoints; vertical terrain is
	 * ignored.
	 */
	private double horizontalDistanceSquared(BlockPos a, BlockPos b) {
		long dx = (long) a.getX() - b.getX();
		long dz = (long) a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	private BlockPos targetPos(WorkerRecord record) {
		if (record.targetKind == null)
			return null;
		return new BlockPos(record.targetX, record.targetY, record.targetZ);
	}

	private void setTarget(WorkerRecord record, String kind, BlockPos pos) {
		record.targetKind = kind;
		record.targetX = pos.getX();
		record.targetY = pos.getY();
		record.targetZ = pos.getZ();
	}

	private void clearTarget(WorkerRecord record) {
		record.targetKind = null;
	}

	private void discardWorkerEntity(ServerLevel level, WorkerRecord record) {
		if (record.uuid == null)
			return;
		try {
			Entity entity = level.getEntity(UUID.fromString(record.uuid));
			if (entity != null)
				entity.discard();
		} catch (IllegalArgumentException ignored) {
		}
	}

	private int floor(double value) {
		return (int) Math.floor(value);
	}

	private double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private int clampInt(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private String sanitiseTag(String value) {
		if (value == null)
			return "unknown";
		return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
	}

	private String depotKey(String civilisationId, String cityId) {
		return civilisationId + "|" + cityId;
	}

	private Path statePath() {
		return worldRoot.resolve("grandstrategy").resolve("state").resolve(STATE_FILE);
	}

	/**
	 * The PhysicalVillagerSystem tick counter is session-local and returns to zero
	 * whenever a world is reopened. Any timestamp or asynchronous "pending" flag
	 * saved from the previous session is therefore invalid on load. Keeping those
	 * values can leave a perfectly valid worker objective with no live navigation
	 * command for many thousands of ticks.
	 */
	private void resetSessionTransientStateAfterLoad(WorkerRecord record) {
		if (record == null)
			return;
		WorkerBrainState brain = ensureBrain(record);

		// Background planner futures never survive WorkerPlannerService.stop().
		// A brain goal is also execution state, not the durable profession assignment.
		// Keeping it across a world close/reopen was producing exactly the frozen-save
		// pattern in the logs: e.g. a farmer loaded with targetKind=null but a stale
		// BUILD_OR_OPERATE_DISTRICT goal, and a road builder retaining an old Y=61
		// assistance goal. Rebuild all movement intent from the durable job/target on
		// the next normal think tick instead of reviving yesterday's waypoint.
		brain.planGeneration++;
		brain.routeRequestPending = false;
		brain.escapeRequestPending = false;
		brain.route.clear();
		brain.routeIndex = 0;
		brain.escapeRoute.clear();
		brain.escapeIndex = 0;
		brain.escaping = false;
		brain.clearGoal();
		clearMoveTarget(record);
		brain.lastPlanTick = 0L;
		brain.lastProgressTick = 0L;
		brain.lastProgressX = record.lastX;
		brain.lastProgressY = record.lastY;
		brain.lastProgressZ = record.lastZ;

		// These are all measured against this class's session-local `ticks` value.
		// Reset them so cooldowns/heartbeats begin from the new session rather than
		// comparing tick 1 against (for example) tick 180,000 from yesterday.
		record.lastNavigationIssueTick = -HUMANOID_NAV_HEARTBEAT_TICKS;
		record.lastNavigationDebugLogTick = -NAVIGATION_DEBUG_LOG_INTERVAL_TICKS;
		record.lastDepotRecoveryScanTick = -DEPOT_RECOVERY_SCAN_INTERVAL_TICKS;
		record.lastNavigationCheckTick = -NAVIGATION_STUCK_CHECK_TICKS;
		record.lastObstacleBreakTick = -OBSTACLE_BREAK_COOLDOWN_TICKS;
		record.lastPickupTick = 0L;
		record.lastAttackTick = -SOLDIER_ATTACK_COOLDOWN_TICKS;
		record.lastWaterEscapeSearchTick = 0L;
		record.lastTerrainEscapeSearchTick = 0L;
		record.lastFarmWaterCheckTick = 0L;
		record.lastFarmerWaterSourceSearchTick = 0L;

		// Meal deadlines are also expressed in this session-local tick domain. Start
		// the reopened worker fed and schedule a fresh long initial delay rather than
		// carrying an incomparable absolute deadline across process restarts.
		record.hungrySinceTick = 0L;
		record.nextMealTick = -1L;
		record.hasMealTarget = false;

		record.navigationSampleX = record.lastX;
		record.navigationSampleY = record.lastY;
		record.navigationSampleZ = record.lastZ;
		record.lastTargetDistanceSquared = -1.0;
		record.stuckChecks = 0;
		record.livenessInitialised = false;
		record.lastLivenessMovementTick = 0L;
		record.livenessRecoveryStage = 0;
		record.consecutiveTickErrors = 0;
		record.lastTickErrorLogTick = 0L;
		record.storageActuallyFull = false;
		record.nonMinerMineAvoidanceTicks = 0;
		if (parseJob(record.job) != VillagerJob.MINER && !record.inMine) {
			record.nonMinerMineAvoidanceActive = false;
		}
		record.needsNavigationRehydrate = true;
	}

	private void load() {
		Path file = statePath();
		if (!Files.isRegularFile(file))
			return;
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			SavedState state = GSON.fromJson(reader, SavedState.class);
			if (state == null)
				return;
			if (state.workers != null) {
				for (WorkerRecord worker : state.workers) {
					if (worker == null || worker.uuid == null)
						continue;
					if (worker.carrying == null)
						worker.carrying = new LinkedHashMap<>();
					if (worker.workMaterials == null)
						worker.workMaterials = new LinkedHashMap<>();
					if (worker.brain == null)
						worker.brain = new WorkerBrainState();
					if (worker.factorySequence > 0)
						worker.factoryBuilt = true;
					if (state.version < 11)
						worker.mineLaneIndex = -1;
					if (state.version < 12) {
						// v11 used no-gravity vertical shafts. Cancel any persisted
						// transit so the worker physically joins the new staircase.
						worker.mineTransitDirection = 0;
						worker.mineTransitEntranceY = 0;
						worker.mineTransitBottomY = 0;
						worker.mineProgress = 0;
						if (parseJob(worker.job) == VillagerJob.MINER && worker.inMine) {
							worker.minerShaftRecoveryActive = true;
						}
					}
					if (state.version < 13 || worker.factoryTypeId == null || worker.factoryTypeId.isBlank()) {
						// Grandfather physical structures but not smelting permission: old
						// factories become wooden/crafting districts until Smelting is researched
						// and the player explicitly converts them in the new factory screen.
						worker.factoryTypeId = "wooden_factory";
					}
					resetSessionTransientStateAfterLoad(worker);
					// v6.38 hunger migration: existing workers are treated as freshly
					// fed once so an old save cannot open with half the population
					// immediately abandoning work to seek food.
					if (state.version < 3) {
						worker.hungrySinceTick = 0L;
						worker.nextMealTick = -1L;
						worker.hasMealTarget = false;
					}
					workers.put(worker.uuid, worker);
				}
			}
			if (state.depots != null) {
				for (DepotRecord depot : state.depots) {
					if (depot == null || depot.civilisationId == null || depot.cityId == null)
						continue;
					if (depot.chests == null)
						depot.chests = new ArrayList<>();
					depots.put(depotKey(depot.civilisationId, depot.cityId), depot);
				}
			}
			if (state.designatedWorkZones != null) {
				for (DesignatedWorkZoneRecord zone : state.designatedWorkZones) {
					if (zone == null || zone.id == null || zone.civilisationId == null || zone.type == null)
						continue;
					if ("FACTORY".equals(zone.type) && (zone.factoryTypeId == null || zone.factoryTypeId.isBlank()))
						zone.factoryTypeId = "wooden_factory";
					designatedWorkZones.put(zone.id, zone);
				}
				cleanupDesignatedZoneAssignments();
			}
		} catch (IOException | JsonParseException e) {
			System.err.println("Failed to load physical Grand Strategy villagers from " + file);
			e.printStackTrace();
		}
	}

	private void save() {
		if (worldRoot == null)
			return;
		Path file = statePath();
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.createDirectories(file.getParent());
			SavedState state = new SavedState();
			state.version = STATE_VERSION;
			state.workers = new ArrayList<>(workers.values());
			state.depots = new ArrayList<>(depots.values());
			state.designatedWorkZones = new ArrayList<>(designatedWorkZones.values());
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(state, writer);
			}
			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFailure) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			dirty = false;
		} catch (IOException e) {
			System.err.println("Failed to save physical Grand Strategy villagers to " + file);
			e.printStackTrace();
		}
	}

	public synchronized int trackedWorkerCount(String civilisationId) {
		int count = 0;
		for (WorkerRecord record : workers.values()) {
			if (Objects.equals(civilisationId, record.civilisationId))
				count++;
		}
		return count;
	}

	public synchronized Map<WorkerToolTier, Integer> toolTierCounts(String civilisationId) {
		EnumMap<WorkerToolTier, Integer> result = new EnumMap<>(WorkerToolTier.class);
		for (WorkerToolTier tier : WorkerToolTier.values())
			result.put(tier, 0);
		for (WorkerRecord record : workers.values()) {
			if (!Objects.equals(civilisationId, record.civilisationId))
				continue;
			WorkerToolTier tier = toolTier(record);
			result.put(tier, result.get(tier) + 1);
		}
		return Map.copyOf(result);
	}

	/**
	 * Compact human-readable activity state for the Economy worker table. This is
	 * derived only from persistent worker/brain state, so client synchronisation
	 * does not inspect or retain live Minecraft entity/navigation objects.
	 */
	private boolean targetKindMatchesProfession(VillagerJob job, String kind) {
		if (kind == null || kind.isBlank())
			return true;
		if (job == null)
			return false;
		return switch (job) {
		case FARMER -> kind.equals("farm") || kind.equals("farm_irrigation") || kind.equals("fetch_farm_water")
				|| kind.equals("farm_bucket_supply") || kind.equals("work_drop") || kind.equals("bone_meal_supply");
		case LUMBERJACK -> kind.equals("tree") || kind.equals("work_drop");
		case MINER -> kind.equals("ore") || kind.equals("strip_mine") || kind.equals("work_drop");
		case SOLDIER -> kind.equals("bone_meal_drop") || kind.equals("depot_return");
		default -> false;
		};
	}

	private String describeWorkerStatus(WorkerRecord record) {
		if (record == null)
			return "Unknown";
		VillagerJob job = parseJob(record.job);
		int carried = depotLoadTotal(record);
		WorkerBrainState brain = record.brain;

		if (record.missingTicks > 0)
			return "Temporarily unloaded / last seen";
		if (record.livenessRecoveryStage > 0)
			return "Recovering movement";
		if (record.combatTargetUuid != null)
			return "Fighting enemy";
		if (job == VillagerJob.SOLDIER && record.commandPostTargetProvidenceId != null) {
			return record.hasMoveTarget ? "Marching to command post" : "Assaulting command post";
		}
		if (record.nonMinerMineAvoidanceActive)
			return "Leaving mine (wrong profession)";
		if (record.minerShaftRecoveryActive)
			return "Recovering to mine stairs";
		if (record.mineTransitDirection < 0)
			return "Entering mine stairs";
		if (record.mineTransitDirection > 0)
			return "Leaving mine stairs";
		if (record.hasWaterEscapeTarget)
			return "Escaping water";
		if (record.hasTerrainEscapeTarget || (brain != null && brain.escaping))
			return "Escaping obstruction";
		if (record.hasMealTarget)
			return "Walking to food";
		if (record.hungrySinceTick > 0L)
			return "Hungry - waiting for food";

		if (record.forceDeposit && carried > 0) {
			if (record.inMine)
				return "Returning to mine stairs (" + carried + "/" + MINER_CARRY_LIMIT + ")";
			if (record.hasMoveTarget)
				return "Walking back to storage (" + carried + ")";
			if (record.storageActuallyFull)
				return "Storage genuinely full (" + carried + ")";
			return "Finding storage (" + carried + ")";
		}

		String kind = record.targetKind == null ? "" : record.targetKind;
		if (!targetKindMatchesProfession(job, kind))
			kind = "";
		switch (kind) {
		case "farm" -> {
			return record.hasMoveTarget ? "Walking to farm plot" : "Farming";
		}
		case "farm_irrigation" -> {
			return record.hasMoveTarget ? "Walking to irrigation point" : "Irrigating farm";
		}
		case "fetch_farm_water" -> {
			return record.hasMoveTarget ? "Fetching water" : "Filling water bucket";
		}
		case "farm_bucket_supply" -> {
			return record.hasMoveTarget ? "Getting bucket / iron" : "Preparing irrigation bucket";
		}
		case "tree" -> {
			return record.hasMoveTarget ? "Walking to tree" : "Cutting tree";
		}
		case "ore" -> {
			return record.hasMoveTarget ? "Moving to ore" : "Mining ore";
		}
		case "strip_mine" -> {
			return record.hasMoveTarget ? "Advancing strip mine" : "Strip mining";
		}
		case "work_drop" -> {
			return record.hasMoveTarget ? "Collecting work output" : "Picking up work output";
		}
		case "bone_meal_drop" -> {
			return record.hasMoveTarget ? "Collecting bone meal" : "Picking up bone meal";
		}
		case "bone_meal_supply" -> {
			return record.hasMoveTarget ? "Fetching bone meal" : "Taking bone meal";
		}
		default -> {
		}
		}

		if (brain != null && brain.routeRequestPending)
			return "Planning a better route";
		if (brain != null && brain.escapeRequestPending)
			return "Planning escape route";
		if (brain != null && brain.consecutiveFailures > 0 && !"NONE".equals(brain.lastFailure)
				&& !record.hasMoveTarget) {
			return "Replanning after " + readableStatusToken(brain.lastFailure);
		}

		if (job == null)
			return record.hasMoveTarget ? "Walking" : "Waiting for assignment";
		return switch (job) {
		case FARMER -> {
			if (!record.hasFarmerZone)
				yield "Allocating farm district";
			if (record.hasFarmerWaterSourceTarget)
				yield "Seeking irrigation water";
			if (record.farmerHasWaterBucket)
				yield "Taking water to farm";
			if (record.hasMoveTarget)
				yield "Walking to farm";
			if (record.farmerWaitingForCrops)
				yield "Waiting for crops to grow";
			yield "Tending farm";
		}
		case LUMBERJACK -> record.hasMoveTarget ? "Walking to forestry work" : "Searching for trees";
		case MINER -> {
			if (record.inMine)
				yield record.hasMoveTarget ? "Moving underground" : "Mining";
			yield record.hasMoveTarget ? "Walking to mine" : "Preparing mine";
		}
		case FACTORY_BUILDER -> {
			if (record.factoryProduct != null)
				yield record.hasMoveTarget ? "Delivering " + readableStatusToken(record.factoryProduct)
						: "Depositing " + readableStatusToken(record.factoryProduct);
			if (record.expandingDepot)
				yield "Expanding storage";
			if (!record.hasFactoryZone)
				yield "Allocating factory district";
			if (record.woodenFactoryBuilt && !record.factoryBuilt) {
				yield record.hasMoveTarget ? "Working wooden factory" : "Operating wooden factory";
			}
			if (!record.factoryBuilt) {
				int materials = carriedWorkMaterials(record);
				if (record.factoryCoreInitialised)
					yield "Rebuilding destroyed factory";
				if (record.hasMoveTarget && materials <= 0)
					yield "Fetching factory materials";
				if (record.hasMoveTarget)
					yield "Taking materials to factory";
				if (record.clearedFactoryKey == null)
					yield "Preparing factory site";
				if (materials <= 0)
					yield "Waiting for factory materials";
				yield "Building wooden factory";
			}
			yield record.hasMoveTarget ? "Moving around factory" : "Operating factory";
		}
		case ROAD_BUILDER -> {
			if (record.navigationAssistTicks > 0)
				yield "Clearing shared obstruction";
			yield record.hasMoveTarget ? "Building / travelling road" : "Planning road work";
		}
		case RESEARCHER -> record.hasMoveTarget ? "Walking to workstation" : "Researching";
		case ADMINISTRATOR -> {
			if (record.hasAdminTorchTarget)
				yield record.hasMoveTarget ? "Carrying torch to site" : "Lighting settlement";
			yield record.hasMoveTarget ? "Walking to administrative post" : "Administering settlement";
		}
		case SOLDIER -> record.hasMoveTarget ? "Marching" : "Garrisoned / waiting";
		};
	}

	/**
	 * Short form of the Economy-screen status used in the in-world nameplate. The
	 * full status remains available in Economy; this deliberately stays concise so
	 * a settlement does not become unreadable when many humanoids are visible.
	 */
	private String compactWorkerNameplateStatus(WorkerRecord record) {
		String status = describeWorkerStatus(record);
		if (status == null || status.isBlank() || "Unknown".equals(status))
			return "";

		if (status.startsWith("Walking back to storage")) {
			return status.replaceFirst("Walking back to storage", "To storage");
		}
		if (status.startsWith("Storage genuinely full")) {
			return status.replaceFirst("Storage genuinely full", "Storage full");
		}
		if (status.startsWith("Finding storage"))
			return status;
		if (status.startsWith("Returning to mine stairs"))
			return status;
		if (status.startsWith("Replanning after "))
			return status.replaceFirst("Replanning after ", "Replan: ");
		if (status.startsWith("Delivering "))
			return status;
		if (status.startsWith("Depositing "))
			return status;

		return switch (status) {
		case "Temporarily unloaded / last seen" -> "Unloaded";
		case "Fighting enemy" -> "Fighting";
		case "Marching to command post" -> "To command post";
		case "Assaulting command post" -> "Assaulting CP";
		case "Leaving mine (wrong profession)" -> "Leaving mine";
		case "Entering mine stairs" -> "Entering mine";
		case "Leaving mine stairs" -> "Leaving mine";
		case "Recovering to mine stairs" -> "Mine recovery";
		case "Collecting work output" -> "Collecting output";
		case "Picking up work output" -> "Picking up output";
		case "Escaping water" -> "Escaping water";
		case "Escaping obstruction" -> "Escaping";
		case "Walking to food" -> "To food";
		case "Hungry - waiting for food" -> "Waiting for food";
		case "Walking to farm plot", "Walking to farm" -> "To farm";
		case "Farming", "Tending farm" -> "Farming";
		case "Waiting for crops to grow" -> "Waiting for crops";
		case "Walking to irrigation point" -> "To irrigation";
		case "Irrigating farm" -> "Irrigating";
		case "Fetching water" -> "Fetching water";
		case "Filling water bucket" -> "Filling bucket";
		case "Getting bucket / iron" -> "Getting bucket";
		case "Preparing irrigation bucket" -> "Making bucket";
		case "Seeking irrigation water" -> "Finding water";
		case "Taking water to farm" -> "Water to farm";
		case "Walking to tree", "Walking to forestry work" -> "To tree";
		case "Cutting tree" -> "Logging";
		case "Searching for trees" -> "Finding tree";
		case "Moving to ore" -> "To ore";
		case "Mining ore", "Strip mining", "Mining" -> "Mining";
		case "Advancing strip mine", "Moving underground" -> "Mining route";
		case "Walking to mine" -> "To mine";
		case "Preparing mine" -> "Mine work";
		case "Planning a better route" -> "Planning route";
		case "Planning escape route" -> "Planning escape";
		case "Walking" -> "Walking";
		case "Waiting for assignment" -> "Unassigned";
		case "Allocating farm district" -> "Creating farm";
		case "Allocating factory district" -> "Creating factory zone";
		case "Expanding storage" -> "Expanding storage";
		case "Fetching factory materials" -> "Fetching materials";
		case "Taking materials to factory" -> "Materials to factory";
		case "Preparing factory site" -> "Clearing factory";
		case "Waiting for factory materials" -> "Needs materials";
		case "Building factory", "Building wooden factory" -> "Building factory";
		case "Working wooden factory", "Operating wooden factory" -> "Wood factory";
		case "Rebuilding destroyed factory" -> "Rebuilding factory";
		case "Moving around factory" -> "Factory work";
		case "Operating factory" -> "Operating factory";
		case "Clearing shared obstruction" -> "Clearing route";
		case "Building / travelling road" -> "Road work";
		case "Planning road work" -> "Planning road";
		case "Walking to workstation" -> "To workstation";
		case "Researching" -> "Researching";
		case "Carrying torch to site" -> "Carrying torch";
		case "Lighting settlement" -> "Lighting";
		case "Walking to administrative post" -> "To admin post";
		case "Administering settlement" -> "Administering";
		case "Marching" -> "Marching";
		case "Garrisoned / waiting" -> "Garrisoned";
		default -> status.length() <= 28 ? status : status.substring(0, 25) + "...";
		};
	}

	private int carriedWorkMaterials(WorkerRecord record) {
		if (record == null || record.workMaterials == null)
			return 0;
		int total = 0;
		for (Integer amount : record.workMaterials.values())
			if (amount != null && amount > 0)
				total += amount;
		return total;
	}

	private String readableStatusToken(String value) {
		if (value == null || value.isBlank())
			return "item";
		String lower = value.toLowerCase(Locale.ROOT).replace('_', ' ');
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	/** Position/job snapshot sent to clients. */
	public record VillagerMapMarker(String uuid, String civilisationId, String job, int appearanceVariant,
			int assignmentIndex, String toolTier, String status, int carriedItems, int blockX, int blockY, int blockZ) {
	}

	/** Position-only record for map discovery hot paths. */
	public record VillagerDiscoveryMarker(int blockX, int blockZ) {
	}

	/** Map-visible player-designated profession district. */
	public record WorkZoneMapMarker(String id, String civilisationId, String type, int minX, int maxX, int minZ,
			int maxZ, String assignedWorkerUuid, String factoryTypeId) {
	}

	private record WorkZone(int minX, int maxX, int minZ, int maxZ) {
	}

	private record SpawnLocation(BlockPos pos, City home) {
	}

	private record TerritorySpawnChunk(long chunkKey, City home) {
	}

	private record ToolDemand(VillagerJob job, WorkerToolTier tier) {
	}

	private record CombatTarget(WorkerRecord record, PathfinderMob villager) {
	}

	private record ObstacleClaim(String workerUuid, long claimTick) {
	}

	private static final class TravelAssistRequest {
		final String civilisationId;
		final int x;
		final int y;
		final int z;
		long requestTick;
		String assigneeUuid;
		long assignmentTick;

		TravelAssistRequest(String civilisationId, int x, int y, int z, long requestTick) {
			this.civilisationId = civilisationId;
			this.x = x;
			this.y = y;
			this.z = z;
			this.requestTick = requestTick;
		}

		BlockPos pos() {
			return new BlockPos(x, y, z);
		}
	}

	private static final class SavedState {
		int version;
		List<WorkerRecord> workers;
		List<DepotRecord> depots;
		List<DesignatedWorkZoneRecord> designatedWorkZones;
	}

	private static final class DesignatedWorkZoneRecord {
		String id;
		String civilisationId;
		String type;
		int minX;
		int maxX;
		int minZ;
		int maxZ;
		String assignedWorkerUuid;
		String factoryTypeId;
	}

	private static final class WorkerRecord {
		String uuid;
		String civilisationId;
		String homeCityId;
		String job;
		String toolTier;
		int appearanceVariant = -1;
		WorkerBrainState brain = new WorkerBrainState();
		double workExperience;
		int assignmentIndex;
		Map<String, Integer> carrying = new LinkedHashMap<>();
		// Materials deliberately withdrawn from depot chests for a specific build or
		// crafting task. WOOD is stored as plank-equivalent units: one log becomes
		// four units, while one plank is one unit. This lets workers physically
		// collect mixed logs/planks without inventing material at the workstation.
		Map<String, Integer> workMaterials = new LinkedHashMap<>();
		String clearedFactoryKey;
		String preparedToolTier;
		boolean bootstrapToolCrafting;
		String targetKind;
		int targetX;
		int targetY;
		int targetZ;
		int lastX;
		int lastY;
		int lastZ;
		int missingTicks;
		int workCounter;
		/** Runtime-only profession deadline; excluded from Gson saves. */
		transient long nextProfessionThinkTick;
		int buildProgress;
		int factorySequence;
		String factoryTypeId = "wooden_factory";
		boolean factoryBuilt;
		boolean woodenFactoryBuilt;
		boolean factoryCoreInitialised;
		boolean factoryGroundInitialised;
		int factoryGroundY;
		String factoryProduct;
		int factoryProductCount;
		boolean expandingDepot;
		int adminTorches;
		boolean hasAdminTorchTarget;
		int adminTorchTargetX;
		int adminTorchTargetY;
		int adminTorchTargetZ;
		int adminLightingCursor;
		boolean hasFarmerZone;
		String farmerDesignatedZoneId;
		int farmerZoneIndex;
		boolean farmerWaterKnown;
		long lastFarmWaterCheckTick;
		int farmerNoWorkCells;
		boolean farmerWaitingForCrops;
		long lastFarmerCropGrowthCheckTick;
		boolean farmerHasBucket;
		boolean farmerHasWaterBucket;
		boolean hasFarmerWaterSourceTarget;
		int farmerWaterSourceX;
		int farmerWaterSourceY;
		int farmerWaterSourceZ;
		long lastFarmerWaterSourceSearchTick;
		int farmerZoneMinX;
		int farmerZoneMaxX;
		int farmerZoneMinZ;
		int farmerZoneMaxZ;
		boolean hasFactoryZone;
		String factoryDesignatedZoneId;
		int factoryZoneIndex;
		int factoryZoneMinX;
		int factoryZoneMaxX;
		int factoryZoneMinZ;
		int factoryZoneMaxZ;
		int roadRouteIndex;
		int roadRouteStep;
		int mineProgress;
		int mineLaneIndex = -1;
		boolean inMine;
		boolean minerShaftRecoveryActive;
		boolean nonMinerMineAvoidanceActive;
		int nonMinerMineAvoidanceTicks;
		int mineTransitDirection;
		int mineTransitTargetY;
		int mineTransitBottomY;
		int mineTransitX;
		int mineTransitZ;
		int mineTransitEntranceY;
		boolean forceDeposit;
		boolean storageActuallyFull;
		boolean hasMoveTarget;
		// Runtime-only in meaning. It is deliberately set on every load/migration so
		// persisted task state is converted back into a live PathNavigation command.
		boolean needsNavigationRehydrate;
		int moveTargetX;
		int moveTargetY;
		int moveTargetZ;
		int navigationSampleX;
		int navigationSampleY;
		int navigationSampleZ;
		double lastTargetDistanceSquared = -1.0;
		long lastNavigationIssueTick;
		long lastNavigationDebugLogTick;
		long lastDepotRecoveryScanTick;
		int stuckChecks;
		// Runtime liveness watchdog state. Persisted values are reset on world load.
		boolean livenessInitialised;
		double livenessX;
		double livenessY;
		double livenessZ;
		long lastLivenessMovementTick;
		int livenessRecoveryStage;
		int consecutiveTickErrors;
		long lastTickErrorLogTick;
		int navigationAssistTicks;
		long lastObstacleBreakTick;
		long lastNavigationCheckTick;
		long lastPickupTick;
		String workDropTargetUuid;
		int boneMeal;
		String boneMealDropTargetUuid;
		long lastAttackTick;
		String combatTargetUuid;
		String commandPostTargetProvidenceId;
		boolean hasWaterEscapeTarget;
		int waterEscapeX;
		int waterEscapeY;
		int waterEscapeZ;
		int waterTicks;
		long lastWaterEscapeSearchTick;
		boolean hasTerrainEscapeTarget;
		int terrainEscapeX;
		int terrainEscapeY;
		int terrainEscapeZ;
		int terrainEscapeTicks;
		long lastTerrainEscapeSearchTick;
		long nextMealTick;
		long hungrySinceTick;
		boolean hasMealTarget;
		int mealTargetX;
		int mealTargetY;
		int mealTargetZ;
	}

	private static final class DepotRecord {
		String civilisationId;
		String cityId;
		boolean active = true;
		boolean initialisedStockpile;
		long lastRepairTick;
		List<StoredPos> chests = new ArrayList<>();

		DepotRecord() {
		}

		DepotRecord(String civilisationId, String cityId) {
			this.civilisationId = civilisationId;
			this.cityId = cityId;
		}
	}

	private static final class StoredPos {
		int x;
		int y;
		int z;

		StoredPos() {
		}

		StoredPos(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}

		BlockPos toBlockPos() {
			return new BlockPos(x, y, z);
		}

		String key() {
			return x + ":" + y + ":" + z;
		}
	}
}
