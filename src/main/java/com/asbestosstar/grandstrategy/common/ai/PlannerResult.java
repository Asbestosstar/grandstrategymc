package com.asbestosstar.grandstrategy.common.ai;

import java.util.List;

/**
 * Result produced off-thread and later validated/applied on the server thread.
 */
public record PlannerResult(String workerUuid, long generation, boolean escape, boolean success,
		NavigationFailure failure, List<WorkerBrainState.BrainWaypoint> waypoints) {
}
