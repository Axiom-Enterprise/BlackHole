package org.bxteam.divinemc.antixray;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * Immutable, box-bounded capture used by the movement reveal pass: a single tick-thread sweep records
 * both the occlusion grid (for {@link Raytracer}) and the positions of hideable ore blocks found in
 * the box. Workers then raytrace each ore against this snapshot without touching live world state.
 */
public final class RevealRegion implements Raytracer.OcclusionView {

    private static final int MAX_VOLUME = 48 * 48 * 48;

    private final int minX, minY, minZ;
    private final int sizeX, sizeY, sizeZ;
    private final boolean[] solid;
    private final long[] orePositions;     // packed via BlockPos.asLong
    private final BlockState[] oreStates;  // parallel to orePositions (immutable, safe off-thread)

    private RevealRegion(final int minX, final int minY, final int minZ,
                         final int sizeX, final int sizeY, final int sizeZ,
                         final boolean[] solid, final long[] orePositions, final BlockState[] oreStates) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.solid = solid;
        this.orePositions = orePositions;
        this.oreStates = oreStates;
    }

    /** Capture a cube of radius {@code r} around (cx,cy,cz). MUST run on the tick thread. */
    public static RevealRegion capture(final ServerLevel level,
                                       final int cx, final int cy, final int cz, final int r,
                                       final Set<Block> oreBlocks) {
        final int minX = cx - r, minY = cy - r, minZ = cz - r;
        final int maxX = cx + r, maxY = cy + r, maxZ = cz + r;
        final int sizeX = maxX - minX + 1, sizeY = maxY - minY + 1, sizeZ = maxZ - minZ + 1;
        final long volume = (long) sizeX * sizeY * sizeZ;
        if (volume > MAX_VOLUME) return null;

        final boolean[] solid = new boolean[(int) volume];
        final LongArrayList ores = new LongArrayList();
        final ObjectArrayList<BlockState> states = new ObjectArrayList<>();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++, i++) {
                    final BlockState state = level.getBlockStateIfLoaded(pos.set(x, y, z));
                    if (state == null) continue;
                    solid[i] = state.isSolidRender();
                    if (oreBlocks.contains(state.getBlock())) {
                        ores.add(BlockPos.asLong(x, y, z));
                        states.add(state);
                    }
                }
            }
        }
        return new RevealRegion(minX, minY, minZ, sizeX, sizeY, sizeZ, solid,
            ores.toLongArray(), states.toArray(new BlockState[0]));
    }

    public long[] orePositions() {
        return this.orePositions;
    }

    public BlockState[] oreStates() {
        return this.oreStates;
    }

    @Override
    public boolean occludes(final int x, final int y, final int z) {
        final int dx = x - this.minX, dy = y - this.minY, dz = z - this.minZ;
        if (dx < 0 || dy < 0 || dz < 0 || dx >= this.sizeX || dy >= this.sizeY || dz >= this.sizeZ) {
            return false;
        }
        return this.solid[(dy * this.sizeZ + dz) * this.sizeX + dx];
    }
}
