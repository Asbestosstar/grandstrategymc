package com.asbestosstar.grandstrategy.common.ai;

import java.util.Arrays;

/**
 * Immutable primitive-array surface snapshot captured on the server thread and
 * consumed off-thread. One cell represents several Minecraft blocks.
 */
public record NavigationSnapshot(int originX, int originZ, int cellSize, int width, int depth, int[] surfaceY,
		byte[] terrain, int startCellX, int startCellZ, int goalCellX, int goalCellZ, int intendedGoalX,
		int intendedGoalY, int intendedGoalZ, int[] penalisedCells) {
	public static final byte BLOCKED = 0;
	public static final byte NORMAL = 1;
	public static final byte ROAD = 2;
	public static final byte WATER = 3;
	public static final byte ROUGH = 4;

	public NavigationSnapshot {
		surfaceY = surfaceY == null ? new int[0] : Arrays.copyOf(surfaceY, surfaceY.length);
		terrain = terrain == null ? new byte[0] : Arrays.copyOf(terrain, terrain.length);
		penalisedCells = penalisedCells == null ? new int[0] : Arrays.copyOf(penalisedCells, penalisedCells.length);
	}

	public int index(int x, int z) {
		return z * width + x;
	}

	public boolean inside(int x, int z) {
		return x >= 0 && z >= 0 && x < width && z < depth;
	}
}
