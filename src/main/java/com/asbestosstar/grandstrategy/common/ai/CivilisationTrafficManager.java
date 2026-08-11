package com.asbestosstar.grandstrategy.common.ai;

import java.util.HashMap;
import java.util.Map;

/**
 * Server-thread shared memory for recurring movement bottlenecks. Several
 * workers failing at the same coarse location turn an individual path problem
 * into a civilisation infrastructure request that road builders can service
 * once for all.
 */
public final class CivilisationTrafficManager {
	private static final CivilisationTrafficManager INSTANCE = new CivilisationTrafficManager();
	private static final int CELL = 4;
	private static final int REQUEST_SCORE = 3;
	private static final long HOTSPOT_TTL = 2_400L;
	private final Map<String, Hotspot> hotspots = new HashMap<>();

	private CivilisationTrafficManager() {
	}

	public static CivilisationTrafficManager getInstance() {
		return INSTANCE;
	}

	public synchronized void clear() {
		hotspots.clear();
	}

	public synchronized InfrastructureNeed recordFailure(String civilisationId, int x, int y, int z,
			NavigationFailure reason, long tick) {
		if (civilisationId == null || civilisationId.isBlank())
			return null;
		prune(tick);
		int cx = Math.floorDiv(x, CELL);
		int cz = Math.floorDiv(z, CELL);
		String key = civilisationId + ':' + cx + ':' + cz;
		Hotspot hotspot = hotspots.computeIfAbsent(key, ignored -> new Hotspot());
		hotspot.civilisationId = civilisationId;
		hotspot.x = cx * CELL + CELL / 2;
		hotspot.y = y;
		hotspot.z = cz * CELL + CELL / 2;
		hotspot.lastTick = tick;
		hotspot.score++;
		hotspot.reason = reason == null ? NavigationFailure.UNKNOWN : reason;
		if (hotspot.score >= REQUEST_SCORE && tick - hotspot.lastRequestTick >= 200L) {
			hotspot.lastRequestTick = tick;
			return new InfrastructureNeed(hotspot.x, hotspot.y, hotspot.z, hotspot.reason, hotspot.score);
		}
		return null;
	}

	public synchronized void noteSuccess(String civilisationId, int x, int z, long tick) {
		if (civilisationId == null)
			return;
		int cx = Math.floorDiv(x, CELL);
		int cz = Math.floorDiv(z, CELL);
		Hotspot hotspot = hotspots.get(civilisationId + ':' + cx + ':' + cz);
		if (hotspot != null) {
			hotspot.score = Math.max(0, hotspot.score - 1);
			hotspot.lastTick = tick;
			if (hotspot.score == 0)
				hotspots.remove(civilisationId + ':' + cx + ':' + cz);
		}
	}

	private void prune(long tick) {
		hotspots.entrySet().removeIf(entry -> tick - entry.getValue().lastTick > HOTSPOT_TTL);
	}

	public record InfrastructureNeed(int x, int y, int z, NavigationFailure reason, int score) {
	}

	private static final class Hotspot {
		String civilisationId;
		int x;
		int y;
		int z;
		int score;
		long lastTick;
		long lastRequestTick;
		NavigationFailure reason = NavigationFailure.UNKNOWN;
	}
}
