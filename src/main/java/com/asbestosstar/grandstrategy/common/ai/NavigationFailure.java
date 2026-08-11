package com.asbestosstar.grandstrategy.common.ai;

/**
 * Reason a local movement segment failed. Different reasons trigger different
 * recovery.
 */
public enum NavigationFailure {
	NONE, BLOCKED_WALL, CLIFF_UP, CLIFF_DOWN, WATER_TRAP, PATH_NOT_FOUND, CROWD_BLOCKED, UNLOADED_ROUTE,
	RESERVATION_CONFLICT, STRUCTURE_BLOCKED, NO_PROGRESS, UNKNOWN
}
