package org.bxteam.divinemc.antixray;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Immutable, box-bounded occlusion grid captured from a {@link ServerLevel} on the tick thread, then
 * read freely from anti-xray worker threads. Blocks outside the captured box are reported as
 * transparent (conservative: never falsely occlude), so callers must size the box to contain the ray.
 */
public final class OcclusionSnapshot implements Raytracer.OcclusionView {

    /** Hard cap on captured volume to keep a single capture cheap and bounded. */
    private static final int MAX_VOLUME = 48 * 48 * 48;

    private final int minX, minY, minZ;
    private final int sizeX, sizeY, sizeZ;
    private final boolean[] solid;

    private OcclusionSnapshot(final int minX, final int minY, final int minZ,
                             final int sizeX, final int sizeY, final int sizeZ, final boolean[] solid) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.solid = solid;
    }

    /**
     * Capture the occlusion of the inclusive box [min..max]. MUST be called on the tick thread for the
     * given level. Returns {@code null} if the box exceeds {@link #MAX_VOLUME}.
     */
    public static OcclusionSnapshot capture(final ServerLevel level,
                                            final int minX, final int minY, final int minZ,
                                            final int maxX, final int maxY, final int maxZ) {
        final int sizeX = maxX - minX + 1;
        final int sizeY = maxY - minY + 1;
        final int sizeZ = maxZ - minZ + 1;
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) return null;
        final long volume = (long) sizeX * sizeY * sizeZ;
        if (volume > MAX_VOLUME) return null;

        final boolean[] solid = new boolean[(int) volume];
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++, i++) {
                    final BlockState state = level.getBlockStateIfLoaded(pos.set(x, y, z));
                    solid[i] = state != null && state.isSolidRender();
                }
            }
        }
        return new OcclusionSnapshot(minX, minY, minZ, sizeX, sizeY, sizeZ, solid);
    }

    @Override
    public boolean occludes(final int x, final int y, final int z) {
        final int dx = x - this.minX;
        final int dy = y - this.minY;
        final int dz = z - this.minZ;
        if (dx < 0 || dy < 0 || dz < 0 || dx >= this.sizeX || dy >= this.sizeY || dz >= this.sizeZ) {
            return false; // outside captured box -> conservatively transparent
        }
        return this.solid[(dy * this.sizeZ + dz) * this.sizeX + dx];
    }
}
