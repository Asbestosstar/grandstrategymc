package com.asbestosstar.grandstrategy.common.ai;

import java.util.Arrays;

/** Small immutable 3-D voxel snapshot for exceptional trapped-worker planning. */
public record EscapeSnapshot(
        int originX,
        int originY,
        int originZ,
        int width,
        int height,
        int depth,
        byte[] voxels,
        int startX,
        int startY,
        int startZ,
        int directionX,
        int directionZ
) {
    public static final byte AIR = 0;
    public static final byte WATER = 1;
    public static final byte BREAK_SOFT = 2;
    public static final byte BREAK_HARD = 3;
    public static final byte PROTECTED = 4;

    public EscapeSnapshot {
        voxels = voxels == null ? new byte[0] : Arrays.copyOf(voxels, voxels.length);
    }

    public int index(int x, int y, int z) { return (y * depth + z) * width + x; }
    public boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < width && y < height && z < depth;
    }
    public byte voxel(int x, int y, int z) {
        return inside(x, y, z) ? voxels[index(x, y, z)] : PROTECTED;
    }
}

