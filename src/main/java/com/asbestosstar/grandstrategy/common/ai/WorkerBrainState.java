package com.asbestosstar.grandstrategy.common.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent high-level memory for one physical Grand Strategy humanoid.
 *
 * This object deliberately contains only primitive/immutable-style data so it
 * can be saved with the world and copied safely into background planner
 * requests. Minecraft entities, levels, chunks, navigators and block states
 * must never be stored here.
 */
public final class WorkerBrainState {
	public String intent = WorkerIntent.IDLE.name();

	/**
	 * Final objective. Intermediate Minecraft navigation targets are never written
	 * here.
	 */
	public boolean hasGoal;
	public int goalX;
	public int goalY;
	public int goalZ;
	public String goalKind;

	/**
	 * Monotonically increasing generation used to reject stale background plans.
	 */
	public long planGeneration;
	public boolean routeRequestPending;
	public boolean escapeRequestPending;

	/**
	 * Hierarchical route: Minecraft only navigates to the current local waypoint.
	 */
	public List<BrainWaypoint> route = new ArrayList<>();
	public int routeIndex;

	/** Temporary escape route. It never replaces the final goal above. */
	public List<BrainWaypoint> escapeRoute = new ArrayList<>();
	public int escapeIndex;
	public boolean escaping;

	public String lastFailure = NavigationFailure.NONE.name();
	public int consecutiveFailures;
	public long lastPlanTick;
	public long lastProgressTick;
	public int lastProgressX;
	public int lastProgressY;
	public int lastProgressZ;

	/**
	 * Small bounded memory of bad locations so replans do not repeat the same
	 * mistake.
	 */
	public List<FailedLocation> failedLocations = new ArrayList<>();

	public void setGoal(String kind, int x, int y, int z, WorkerIntent nextIntent) {
		boolean changed = !hasGoal || goalX != x || goalY != y || goalZ != z
				|| (kind != null && !kind.equals(goalKind));
		hasGoal = true;
		goalKind = kind;
		goalX = x;
		goalY = y;
		goalZ = z;
		intent = (nextIntent == null ? WorkerIntent.TRAVEL_TO_WORK : nextIntent).name();
		if (changed) {
			planGeneration++;
			routeRequestPending = false;
			escapeRequestPending = false;
			route.clear();
			routeIndex = 0;
			escapeRoute.clear();
			escapeIndex = 0;
			escaping = false;
			consecutiveFailures = 0;
			lastFailure = NavigationFailure.NONE.name();
		}
	}

	public void clearGoal() {
		hasGoal = false;
		goalKind = null;
		routeRequestPending = false;
		escapeRequestPending = false;
		route.clear();
		routeIndex = 0;
		escapeRoute.clear();
		escapeIndex = 0;
		escaping = false;
		intent = WorkerIntent.IDLE.name();
	}

	public void rememberFailure(int x, int y, int z, NavigationFailure failure, long tick) {
		lastFailure = (failure == null ? NavigationFailure.UNKNOWN : failure).name();
		consecutiveFailures++;
		failedLocations.add(new FailedLocation(x, y, z, lastFailure, tick));
		while (failedLocations.size() > 16)
			failedLocations.remove(0);
	}

	public record BrainWaypoint(int x, int y, int z) {
	}

	public record FailedLocation(int x, int y, int z, String reason, long tick) {
	}
}
