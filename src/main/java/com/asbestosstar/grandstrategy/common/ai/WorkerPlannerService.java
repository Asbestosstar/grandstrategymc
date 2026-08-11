package com.asbestosstar.grandstrategy.common.ai;

import com.asbestosstar.grandstrategy.common.GrandStrategyCommon;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multithreaded planner for physical workers.
 *
 * The service receives immutable primitive snapshots only. It never accesses a
 * live Minecraft Level, Entity, Chunk, PathNavigation or BlockState from a
 * background thread. Results are polled and applied later by
 * PhysicalVillagerSystem on the authoritative server tick thread.
 */
public final class WorkerPlannerService {
	private static final WorkerPlannerService INSTANCE = new WorkerPlannerService();
	private final Map<String, CompletableFuture<PlannerResult>> pending = new ConcurrentHashMap<>();
	/** Number of planner computations actually running/queued in the pool. */
	private final AtomicInteger activeJobs = new AtomicInteger();
	private ForkJoinPool pool;
	private int maxPending = 64;

	private WorkerPlannerService() {
	}

	public static WorkerPlannerService getInstance() {
		return INSTANCE;
	}

	public synchronized void start() {
		stop();
		int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
		// Route planning is pure snapshot work, so use most cores while deliberately
		// leaving roughly one quarter of the machine available for Minecraft's main
		// server thread, rendering/network threads and the strategy simulation pool.
		int reserved = Math.max(1, processors / 4);
		int parallelism = Math.max(2, processors - reserved);
		maxPending = Math.max(32, parallelism * 8);
		activeJobs.set(0);
		pool = new ForkJoinPool(parallelism);
	}

	public synchronized void stop() {
		for (CompletableFuture<PlannerResult> future : pending.values())
			future.cancel(true);
		pending.clear();
		if (pool != null) {
			pool.shutdownNow();
			try {
				pool.awaitTermination(1, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			pool = null;
		}
		activeJobs.set(0);
	}

	public boolean hasPending(String workerUuid) {
		CompletableFuture<PlannerResult> f = pending.get(workerUuid);
		return f != null && !f.isDone();
	}

	/**
	 * Cheap admission check used before the server thread captures an expensive
	 * terrain snapshot. This is intentionally separate from
	 * requestRoute/requestEscape so a saturated planner does not make the main
	 * thread scan thousands of blocks only to reject the job afterwards.
	 */
	public boolean canAccept(String workerUuid) {
		ForkJoinPool executor = pool;
		if (workerUuid == null || executor == null || executor.isShutdown())
			return false;
		CompletableFuture<PlannerResult> existing = pending.get(workerUuid);
		return (existing == null || existing.isDone()) && activeJobs.get() < maxPending;
	}

	private boolean tryAcquirePlannerSlot() {
		while (true) {
			int current = activeJobs.get();
			if (current >= maxPending)
				return false;
			if (activeJobs.compareAndSet(current, current + 1))
				return true;
		}
	}

	private void releasePlannerSlot() {
		activeJobs.updateAndGet(value -> Math.max(0, value - 1));
	}

	/**
	 * Drops an obsolete planner request when a physical worker body is migrated.
	 */
	public void forget(String workerUuid) {
		if (workerUuid == null)
			return;
		CompletableFuture<PlannerResult> future = pending.remove(workerUuid);
		if (future != null)
			future.cancel(true);
	}

	public boolean requestRoute(String workerUuid, long generation, NavigationSnapshot snapshot) {
		ForkJoinPool executor = pool;
		if (workerUuid == null || snapshot == null || executor == null || executor.isShutdown())
			return false;
		CompletableFuture<PlannerResult> existing = pending.get(workerUuid);
		if (existing != null && !existing.isDone())
			return false;
		if (!tryAcquirePlannerSlot())
			return false;
		try {
			CompletableFuture<PlannerResult> future = CompletableFuture
					.supplyAsync(() -> planSurface(workerUuid, generation, snapshot), executor)
					.exceptionally(error -> failed(workerUuid, generation, false, NavigationFailure.UNKNOWN))
					.whenComplete((result, error) -> releasePlannerSlot());
			pending.put(workerUuid, future);
			return true;
		} catch (RuntimeException error) {
			releasePlannerSlot();
			return false;
		}
	}

	public boolean requestEscape(String workerUuid, long generation, EscapeSnapshot snapshot) {
		ForkJoinPool executor = pool;
		if (workerUuid == null || snapshot == null || executor == null || executor.isShutdown())
			return false;
		CompletableFuture<PlannerResult> existing = pending.get(workerUuid);
		if (existing != null && !existing.isDone())
			return false;
		if (!tryAcquirePlannerSlot())
			return false;
		try {
			CompletableFuture<PlannerResult> future = CompletableFuture
					.supplyAsync(() -> planEscape(workerUuid, generation, snapshot), executor)
					.exceptionally(error -> failed(workerUuid, generation, true, NavigationFailure.UNKNOWN))
					.whenComplete((result, error) -> releasePlannerSlot());
			pending.put(workerUuid, future);
			return true;
		} catch (RuntimeException error) {
			releasePlannerSlot();
			return false;
		}
	}

	/** Polls one completed result without blocking the server tick. */
	public PlannerResult poll(String workerUuid) {
		CompletableFuture<PlannerResult> future = pending.get(workerUuid);
		if (future == null || !future.isDone())
			return null;
		pending.remove(workerUuid, future);
		try {
			return future.join();
		} catch (RuntimeException e) {
			// Cancellation/rare completion failure is treated as "no usable result".
			// PhysicalVillagerSystem's liveness watchdog will immediately rebuild the
			// request instead of leaving a permanent routeRequestPending flag.
			return null;
		}
	}

	private PlannerResult planSurface(String workerUuid, long generation, NavigationSnapshot s) {
		daxFriendlyTouch(s.terrain());
		if (!s.inside(s.startCellX(), s.startCellZ()) || !s.inside(s.goalCellX(), s.goalCellZ())) {
			return failed(workerUuid, generation, false, NavigationFailure.UNLOADED_ROUTE);
		}
		int n = s.width() * s.depth();
		if (s.terrain().length < n || s.surfaceY().length < n) {
			return failed(workerUuid, generation, false, NavigationFailure.UNKNOWN);
		}

		int start = s.index(s.startCellX(), s.startCellZ());
		int goal = s.index(s.goalCellX(), s.goalCellZ());
		if (s.terrain()[start] == NavigationSnapshot.BLOCKED) {
			return failed(workerUuid, generation, false, NavigationFailure.PATH_NOT_FOUND);
		}

		double[] g = new double[n];
		Arrays.fill(g, Double.POSITIVE_INFINITY);
		int[] parent = new int[n];
		Arrays.fill(parent, -1);
		boolean[] closed = new boolean[n];
		boolean[] penalised = new boolean[n];
		for (int idx : s.penalisedCells())
			if (idx >= 0 && idx < n)
				penalised[idx] = true;

		PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
		g[start] = 0.0;
		open.add(new Node(start, heuristic2d(s, start, goal)));
		int[] dx = { 1, -1, 0, 0, 1, 1, -1, -1 };
		int[] dz = { 0, 0, 1, -1, 1, -1, 1, -1 };

		while (!open.isEmpty()) {
			Node node = open.poll();
			int current = node.index();
			if (closed[current])
				continue;
			closed[current] = true;
			if (current == goal)
				break;
			int cx = current % s.width();
			int cz = current / s.width();
			for (int d = 0; d < dx.length; d++) {
				int nx = cx + dx[d];
				int nz = cz + dz[d];
				if (!s.inside(nx, nz))
					continue;
				int ni = s.index(nx, nz);
				byte terrain = s.terrain()[ni];
				if (terrain == NavigationSnapshot.BLOCKED || closed[ni])
					continue;
				int rise = Math.abs(s.surfaceY()[ni] - s.surfaceY()[current]);
				if (rise > 5)
					continue;
				double base = switch (terrain) {
				case NavigationSnapshot.ROAD -> 0.55;
				case NavigationSnapshot.WATER -> 3.4;
				case NavigationSnapshot.ROUGH -> 1.65;
				default -> 1.0;
				};
				if (d >= 4)
					base *= 1.41421356237;
				base += rise * 0.42;
				if (penalised[ni])
					base += 18.0;
				double candidate = g[current] + base;
				if (candidate < g[ni]) {
					g[ni] = candidate;
					parent[ni] = current;
					open.add(new Node(ni, candidate + heuristic2d(s, ni, goal)));
				}
			}
		}

		if (start != goal && parent[goal] < 0) {
			return failed(workerUuid, generation, false, NavigationFailure.PATH_NOT_FOUND);
		}

		List<Integer> reverse = new ArrayList<>();
		for (int at = goal; at >= 0; at = parent[at]) {
			reverse.add(at);
			if (at == start)
				break;
		}
		java.util.Collections.reverse(reverse);
		List<WorkerBrainState.BrainWaypoint> waypoints = simplifySurfacePath(s, reverse);
		if (waypoints.isEmpty()) {
			waypoints = List
					.of(new WorkerBrainState.BrainWaypoint(s.intendedGoalX(), s.intendedGoalY(), s.intendedGoalZ()));
		}
		return new PlannerResult(workerUuid, generation, false, true, NavigationFailure.NONE, List.copyOf(waypoints));
	}

	private List<WorkerBrainState.BrainWaypoint> simplifySurfacePath(NavigationSnapshot s, List<Integer> cells) {
		List<WorkerBrainState.BrainWaypoint> out = new ArrayList<>();
		if (cells.size() <= 1)
			return out;
		int lastDx = Integer.MIN_VALUE;
		int lastDz = Integer.MIN_VALUE;
		int lastEmitted = 0;
		for (int i = 1; i < cells.size(); i++) {
			int prev = cells.get(i - 1);
			int cur = cells.get(i);
			int px = prev % s.width();
			int pz = prev / s.width();
			int cx = cur % s.width();
			int cz = cur / s.width();
			int dx = Integer.compare(cx, px);
			int dz = Integer.compare(cz, pz);
			boolean turn = lastDx != Integer.MIN_VALUE && (dx != lastDx || dz != lastDz);
			boolean spacing = i - lastEmitted >= 4;
			boolean finalCell = i == cells.size() - 1;
			if (turn || spacing || finalCell) {
				int worldX = s.originX() + cx * s.cellSize() + s.cellSize() / 2;
				int worldZ = s.originZ() + cz * s.cellSize() + s.cellSize() / 2;
				int worldY = s.surfaceY()[cur];
				if (finalCell) {
					worldX = s.intendedGoalX();
					worldZ = s.intendedGoalZ();
					worldY = s.intendedGoalY();
				}
				out.add(new WorkerBrainState.BrainWaypoint(worldX, worldY, worldZ));
				lastEmitted = i;
			}
			lastDx = dx;
			lastDz = dz;
		}
		return out;
	}

	private double heuristic2d(NavigationSnapshot s, int a, int b) {
		int ax = a % s.width();
		int az = a / s.width();
		int bx = b % s.width();
		int bz = b / s.width();
		int dx = Math.abs(ax - bx);
		int dz = Math.abs(az - bz);
		return Math.max(dx, dz) + 0.41421356237 * Math.min(dx, dz);
	}

	private PlannerResult planEscape(String workerUuid, long generation, EscapeSnapshot s) {
		daxFriendlyTouch(s.voxels());
		int sx = s.startX();
		int sy = s.startY();
		int sz = s.startZ();
		if (!s.inside(sx, sy, sz))
			return failed(workerUuid, generation, true, NavigationFailure.UNKNOWN);

		int[] goal = chooseEscapeGoal(s);
		if (goal == null)
			return failed(workerUuid, generation, true, NavigationFailure.PATH_NOT_FOUND);

		int n = s.width() * s.height() * s.depth();
		double[] dist = new double[n];
		Arrays.fill(dist, Double.POSITIVE_INFINITY);
		int[] parent = new int[n];
		Arrays.fill(parent, -1);
		PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
		int start = s.index(sx, sy, sz);
		int target = s.index(goal[0], goal[1], goal[2]);
		dist[start] = 0.0;
		open.add(new Node(start, escapeHeuristic(s, sx, sy, sz, goal)));
		boolean[] closed = new boolean[n];

		while (!open.isEmpty()) {
			int cur = open.poll().index();
			if (closed[cur])
				continue;
			closed[cur] = true;
			if (cur == target)
				break;
			int cx = cur % s.width();
			int yz = cur / s.width();
			int cz = yz % s.depth();
			int cy = yz / s.depth();
			int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
			for (int[] d : dirs) {
				int nx = cx + d[0];
				int nz = cz + d[1];
				for (int dy = 1; dy >= -1; dy--) {
					int ny = cy + dy;
					if (!s.inside(nx, ny, nz) || ny <= 0 || ny + 1 >= s.height())
						continue;
					int ni = s.index(nx, ny, nz);
					if (closed[ni])
						continue;
					double step = escapePositionCost(s, nx, ny, nz, dy);
					if (!Double.isFinite(step))
						continue;
					double candidate = dist[cur] + step;
					if (candidate < dist[ni]) {
						dist[ni] = candidate;
						parent[ni] = cur;
						open.add(new Node(ni, candidate + escapeHeuristic(s, nx, ny, nz, goal)));
					}
				}
			}
		}
		if (start != target && parent[target] < 0) {
			return failed(workerUuid, generation, true, NavigationFailure.PATH_NOT_FOUND);
		}

		List<Integer> reverse = new ArrayList<>();
		for (int at = target; at >= 0; at = parent[at]) {
			reverse.add(at);
			if (at == start)
				break;
		}
		java.util.Collections.reverse(reverse);
		List<WorkerBrainState.BrainWaypoint> waypoints = new ArrayList<>();
		for (int i = 1; i < reverse.size(); i++) {
			int idx = reverse.get(i);
			int x = idx % s.width();
			int yz = idx / s.width();
			int z = yz % s.depth();
			int y = yz / s.depth();
			waypoints.add(new WorkerBrainState.BrainWaypoint(s.originX() + x, s.originY() + y, s.originZ() + z));
		}
		return new PlannerResult(workerUuid, generation, true, true, NavigationFailure.NONE, List.copyOf(waypoints));
	}

	private int[] chooseEscapeGoal(EscapeSnapshot s) {
		int bestX = -1, bestY = -1, bestZ = -1;
		double best = Double.NEGATIVE_INFINITY;
		int sx = s.startX(), sy = s.startY(), sz = s.startZ();
		for (int y = 1; y < s.height() - 2; y++) {
			for (int z = 1; z < s.depth() - 1; z++) {
				for (int x = 1; x < s.width() - 1; x++) {
					boolean boundary = x <= 1 || z <= 1 || x >= s.width() - 2 || z >= s.depth() - 2
							|| y >= Math.min(s.height() - 3, sy + 5);
					if (!boundary)
						continue;
					if (!canOccupy(s, x, y, z))
						continue;
					int dx = x - sx;
					int dz = z - sz;
					double directional = dx * (double) s.directionX() + dz * (double) s.directionZ();
					double elevation = (y - sy) * 3.0;
					double distancePenalty = Math.sqrt(dx * (double) dx + dz * (double) dz) * 0.2;
					double score = directional + elevation - distancePenalty;
					if (score > best) {
						best = score;
						bestX = x;
						bestY = y;
						bestZ = z;
					}
				}
			}
		}
		return bestX < 0 ? null : new int[] { bestX, bestY, bestZ };
	}

	private boolean canOccupy(EscapeSnapshot s, int x, int y, int z) {
		byte feet = s.voxel(x, y, z);
		byte head = s.voxel(x, y + 1, z);
		byte below = s.voxel(x, y - 1, z);
		boolean bodyPossible = feet != EscapeSnapshot.PROTECTED && head != EscapeSnapshot.PROTECTED;
		boolean support = below != EscapeSnapshot.AIR && below != EscapeSnapshot.WATER;
		return bodyPossible && support;
	}

	private double escapePositionCost(EscapeSnapshot s, int x, int y, int z, int dy) {
		byte feet = s.voxel(x, y, z);
		byte head = s.voxel(x, y + 1, z);
		byte below = s.voxel(x, y - 1, z);
		if (feet == EscapeSnapshot.PROTECTED || head == EscapeSnapshot.PROTECTED)
			return Double.POSITIVE_INFINITY;
		if (below == EscapeSnapshot.AIR || below == EscapeSnapshot.WATER)
			return Double.POSITIVE_INFINITY;
		double cost = 1.0 + Math.max(0, dy) * 0.5 + Math.max(0, -dy) * 0.2;
		cost += voxelBreakCost(feet) + voxelBreakCost(head);
		if (feet == EscapeSnapshot.WATER || head == EscapeSnapshot.WATER)
			cost += 3.0;
		return cost;
	}

	private double voxelBreakCost(byte voxel) {
		return switch (voxel) {
		case EscapeSnapshot.BREAK_SOFT -> 8.0;
		case EscapeSnapshot.BREAK_HARD -> 18.0;
		default -> 0.0;
		};
	}

	private double escapeHeuristic(EscapeSnapshot s, int x, int y, int z, int[] goal) {
		return Math.abs(goal[0] - x) + Math.abs(goal[2] - z) + Math.abs(goal[1] - y) * 1.25;
	}

	private PlannerResult failed(String workerUuid, long generation, boolean escape, NavigationFailure failure) {
		return new PlannerResult(workerUuid, generation, escape, false, failure, List.of());
	}

	/**
	 * DAX-compatible preprocessing boundary. Planner data is stored in contiguous
	 * primitive arrays. On SPARC systems with the optional DaxIntStream library we
	 * submit a real stream scan/count; otherwise this is a zero-allocation CPU
	 * touch. Route graph traversal remains on ordinary CPU cores because it is
	 * branch-heavy.
	 */
	private void daxFriendlyTouch(byte[] data) {
		if (data == null || data.length == 0)
			return;
		if (GrandStrategyCommon.isDaxAvailable()) {
			try {
				int[] ints = new int[data.length];
				for (int i = 0; i < data.length; i++)
					ints[i] = data[i] & 0xFF;
				Class<?> daxClass = Class.forName("com.oracle.dax.DaxIntStream");
				Method of = daxClass.getMethod("of", int[].class);
				Object stream = of.invoke(null, (Object) ints);
				try {
					Method count = stream.getClass().getMethod("count");
					count.invoke(stream);
				} catch (ReflectiveOperationException ignored) {
					// Older DAX stream builds may expose a slightly different terminal API.
					// Creating the stream still verifies the offload-compatible data path.
				}
				return;
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// Transparent fallback below.
			}
		}
		int checksum = 0;
		for (byte datum : data)
			checksum ^= datum;
		if (checksum == Integer.MIN_VALUE)
			throw new AssertionError();
	}

	private record Node(int index, double score) {
	}
}
