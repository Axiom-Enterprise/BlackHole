package org.bxteam.divinemc.antixray;

import dev.imanity.antixray.sdk.AntiXrayAdapter;
import dev.imanity.antixray.sdk.AntiXraySDK;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bxteam.divinemc.config.DivineConfig;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    private final AtomicLong submitted = new AtomicLong();

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
        this.submitted.incrementAndGet();
        final String worldName = world.getName();
        this.workers.execute(() -> onBlockChange(worldName, x, y, z));
    }

    @Override
    public void callPlayerLeftClickBlock(final World world, final Player player, final int x, final int y, final int z) {
        this.submitted.incrementAndGet();
        final String worldName = world.getName();
        final UUID viewer = player.getUniqueId();
        this.workers.execute(() -> onPlayerInteract(worldName, viewer, x, y, z));
    }

    // --- extension points (next increment: snapshot capture -> Raytracer -> packet rewrite) ---

    private void onBlockChange(final String world, final int x, final int y, final int z) {
        // TODO(next): mark the section dirty and schedule a reveal pass for viewers in range.
    }

    private void onPlayerInteract(final String world, final UUID viewer, final int x, final int y, final int z) {
        // TODO(next): raytrace from the viewer eye against a captured OcclusionView and reveal hits.
    }

    public long submittedSignals() {
        return this.submitted.get();
    }
}
