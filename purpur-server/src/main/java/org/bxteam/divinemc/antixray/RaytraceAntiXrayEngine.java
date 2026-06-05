package org.bxteam.divinemc.antixray;

import dev.imanity.antixray.sdk.AntiXrayAdapter;
import dev.imanity.antixray.sdk.AntiXraySDK;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bxteam.divinemc.config.DivineConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Built-in multithreaded raytrace anti-xray engine. Registers itself as the
 * {@link AntiXrayAdapter} so the fork-level SDK hooks (see patch 0037) feed it block-change and
 * player-interaction signals; a paid plugin may override it by calling {@link AntiXraySDK#setAdapter}.
 *
 * <p>Threading contract: SDK callbacks fire on the tick thread. This engine only captures primitives
 * there and defers work to its own pool; it never touches live {@code Level} state off-thread. The
 * actual visibility recompute runs through {@link Raytracer} against a captured {@code OcclusionView}
 * snapshot, and packet obfuscation is the next increment — this MVP wires the lifecycle, config and
 * async pipeline without altering gameplay.
 */
public final class RaytraceAntiXrayEngine implements AntiXrayAdapter {
    private static final Logger LOGGER = LogManager.getLogger("RaytraceAntiXray");
    private static final int QUEUE_CAPACITY = 1 << 16;

    private static volatile RaytraceAntiXrayEngine instance;

    private final ExecutorService workers;
    // Diagnostics only. `submitted` has a single writer (SDK callbacks fire on the tick thread).
    // The hit counters are written from workers and are intentionally best-effort (no atomics):
    // an occasional lost increment is fine for stats and avoids CAS traffic on the hot path.
    private volatile long submitted;
    private volatile long visibleHits;
    private volatile long occludedHits;

    private RaytraceAntiXrayEngine(final int threads) {
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            threads, threads, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(QUEUE_CAPACITY),
            r -> {
                final Thread t = new Thread(r, "DivineMC Raytrace AntiXray");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy()); // best-effort: drop stale work under flood
        pool.allowCoreThreadTimeOut(true);
        this.workers = pool;
    }

    /** Idempotent; safe to call on every config (re)load. Starts the engine only when enabled. */
    public static synchronized void bootstrap() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
        if (!DivineConfig.PerformanceCategory.raytraceEngineEnabled) {
            return;
        }
        final int threads = Math.max(1, DivineConfig.PerformanceCategory.raytraceEngineThreads);
        final RaytraceAntiXrayEngine engine = new RaytraceAntiXrayEngine(threads);
        instance = engine;
        AntiXraySDK.setAdapter(engine);
        LOGGER.info("Raytrace AntiXray engine started ({} threads, radius {})",
            threads, DivineConfig.PerformanceCategory.raytraceEngineRadius);
    }

    public static RaytraceAntiXrayEngine instance() {
        return instance;
    }

    private void shutdown() {
        if (AntiXraySDK.getAdapter() == this) {
            AntiXraySDK.setAdapter(null);
        }
        this.workers.shutdownNow();
    }

    @Override
    public void callBlockChange(final World world, final int x, final int y, final int z, final Material material) {
        this.submitted++; // tick thread only
        final String worldName = world.getName();
        this.workers.execute(() -> onBlockChange(worldName, x, y, z));
    }

    @Override
    public void callPlayerLeftClickBlock(final World world, final Player player, final int x, final int y, final int z) {
        this.submitted++; // tick thread only
        if (!(world instanceof final CraftWorld craftWorld)) {
            return;
        }
        final ServerLevel level = craftWorld.getHandle();

        // Capture viewer eye + a bounded occlusion snapshot of the eye->target box on the TICK THREAD;
        // the target is the clicked block (within reach), so the box is small. The raytrace itself then
        // runs off-thread against the immutable snapshot - no live Level access on the workers.
        final Location eye = player.getEyeLocation();
        final double eyeX = eye.getX();
        final double eyeY = eye.getY();
        final double eyeZ = eye.getZ();

        final int ex = (int) Math.floor(eyeX);
        final int ey = (int) Math.floor(eyeY);
        final int ez = (int) Math.floor(eyeZ);
        final OcclusionSnapshot snapshot = OcclusionSnapshot.capture(level,
            Math.min(ex, x) - 1, Math.min(ey, y) - 1, Math.min(ez, z) - 1,
            Math.max(ex, x) + 1, Math.max(ey, y) + 1, Math.max(ez, z) + 1);
        if (snapshot == null) {
            return; // box too large / unloaded
        }

        this.workers.execute(() -> {
            if (Raytracer.isVisible(snapshot, eyeX, eyeY, eyeZ, x, y, z)) {
                this.visibleHits++; // best-effort diagnostic
            } else {
                this.occludedHits++; // best-effort diagnostic
            }
        });
    }

    // --- extension point (next increment: section-cache snapshot -> reveal -> packet rewrite) ---

    private void onBlockChange(final String world, final int x, final int y, final int z) {
        // TODO(next): invalidate the section's cached occlusion and schedule a reveal pass for viewers.
    }

    public long submittedSignals() {
        return this.submitted;
    }

    public long visibleHits() {
        return this.visibleHits;
    }

    public long occludedHits() {
        return this.occludedHits;
    }
}
