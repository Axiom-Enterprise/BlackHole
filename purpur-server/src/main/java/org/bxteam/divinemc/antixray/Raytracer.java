package org.bxteam.divinemc.antixray;

/**
 * Pure, allocation-free voxel ray traversal (Amanatides &amp; Woo) used to decide whether a target
 * block is visible from a viewer's eye through the world's opaque blocks.
 *
 * <p>Deliberately has NO reference to {@code Level} or any mutable game state: occlusion is queried
 * through {@link OcclusionView}, which the caller backs with a thread-safe snapshot. This keeps the
 * raytrace safe to run on the anti-xray worker threads without racing the tick thread.
 */
public final class Raytracer {

    private Raytracer() {}

    /** Thread-safe occlusion lookup over a captured snapshot of the world. */
    @FunctionalInterface
    public interface OcclusionView {
        /** @return true if the block at (x,y,z) fully blocks line of sight (opaque full cube). */
        boolean occludes(int x, int y, int z);
    }

    /**
     * @param view      thread-safe occlusion snapshot
     * @param eyeX/Y/Z  viewer eye position (world coordinates)
     * @param tx/ty/tz  target block position
     * @return true if no opaque block lies strictly between the eye voxel and the target voxel
     */
    public static boolean isVisible(final OcclusionView view,
                                    final double eyeX, final double eyeY, final double eyeZ,
                                    final int tx, final int ty, final int tz) {
        // Aim at the target block centre.
        final double dirX = (tx + 0.5) - eyeX;
        final double dirY = (ty + 0.5) - eyeY;
        final double dirZ = (tz + 0.5) - eyeZ;

        int x = floor(eyeX);
        int y = floor(eyeY);
        int z = floor(eyeZ);

        final int stepX = dirX > 0 ? 1 : (dirX < 0 ? -1 : 0);
        final int stepY = dirY > 0 ? 1 : (dirY < 0 ? -1 : 0);
        final int stepZ = dirZ > 0 ? 1 : (dirZ < 0 ? -1 : 0);

        // Distance (in t units, ray = eye + t*dir, t in [0,1] reaches the target centre) to the next
        // voxel boundary on each axis, and the t-step to cross one full voxel on that axis.
        double tMaxX = boundary(eyeX, dirX, stepX);
        double tMaxY = boundary(eyeY, dirY, stepY);
        double tMaxZ = boundary(eyeZ, dirZ, stepZ);
        final double tDeltaX = stepX != 0 ? Math.abs(1.0 / dirX) : Double.POSITIVE_INFINITY;
        final double tDeltaY = stepY != 0 ? Math.abs(1.0 / dirY) : Double.POSITIVE_INFINITY;
        final double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dirZ) : Double.POSITIVE_INFINITY;

        // Manhattan distance bounds the number of voxels crossed; +3 guards rounding.
        final int maxSteps = Math.abs(tx - x) + Math.abs(ty - y) + Math.abs(tz - z) + 3;

        for (int i = 0; i < maxSteps; i++) {
            if (x == tx && y == ty && z == tz) {
                return true; // reached the target without hitting an occluder
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) { x += stepX; tMaxX += tDeltaX; }
                else { z += stepZ; tMaxZ += tDeltaZ; }
            } else {
                if (tMaxY < tMaxZ) { y += stepY; tMaxY += tDeltaY; }
                else { z += stepZ; tMaxZ += tDeltaZ; }
            }

            // Stop before testing the target voxel itself (the target ore must not occlude itself).
            if (x == tx && y == ty && z == tz) {
                return true;
            }
            if (view.occludes(x, y, z)) {
                return false;
            }
        }
        return false; // ran out of steps without reaching the target -> treat as not visible
    }

    private static double boundary(final double origin, final double dir, final int step) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        final int voxel = floor(origin);
        final double next = step > 0 ? (voxel + 1) : voxel;
        return (next - origin) / dir;
    }

    private static int floor(final double v) {
        final int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
