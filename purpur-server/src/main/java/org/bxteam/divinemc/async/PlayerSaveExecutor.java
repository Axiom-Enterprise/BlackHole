package org.bxteam.divinemc.async;

import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.util.NamedAgnosticThreadFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.*;

@org.jspecify.annotations.NullMarked
public final class PlayerSaveExecutor {
    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("PlayerSaveExecutor");
    private static volatile ThreadPoolExecutor pool;
    // coalesce: latest snapshot + target per player; a single queued task per player writes the latest
    private static final ConcurrentHashMap<UUID, byte[]> latest = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Path> targets = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Boolean> queued = new ConcurrentHashMap<>();

    private PlayerSaveExecutor() {
    }

    public static void init() {
        if (!DivineConfig.AsyncCategory.playerSaveEnabled) return;
        int threads = Math.max(1, DivineConfig.AsyncCategory.playerSaveWorkerThreads);
        int cap = Math.max(1, DivineConfig.AsyncCategory.playerSaveQueueCapacity);
        ThreadPoolExecutor p = new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(cap),
            new NamedAgnosticThreadFactory<>("Player Save IO", Thread::new, Thread.NORM_PRIORITY),
            new ThreadPoolExecutor.AbortPolicy()); // overflow throws -> we catch for inline fallback
        p.allowCoreThreadTimeOut(true);
        pool = p;
    }

    /** Called on the MAIN thread with already-serialized bytes. Returns false if not offloaded (caller must write inline). */
    public static boolean submitWrite(UUID id, byte[] data, Path target) {
        ThreadPoolExecutor p = pool;
        if (p == null) return false;
        final boolean coalesce = DivineConfig.AsyncCategory.playerSaveCoalesce;
        if (coalesce) {
            latest.put(id, data);
            targets.put(id, target);
            if (queued.putIfAbsent(id, Boolean.TRUE) != null) return true; // a task is already queued; it will pick up the latest
            try {
                p.execute(() -> {
                    queued.remove(id);
                    byte[] d = latest.remove(id);
                    Path t = targets.remove(id);
                    if (d != null && t != null) writeAtomic(t, d);
                });
                return true;
            } catch (RejectedExecutionException overflow) {
                queued.remove(id); latest.remove(id); targets.remove(id);
                return false; // caller writes inline
            }
        } else {
            try { p.execute(() -> writeAtomic(target, data)); return true; }
            catch (RejectedExecutionException overflow) { return false; }
        }
    }

    private static void writeAtomic(Path target, byte[] data) {
        try {
            Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
            Files.write(tmp, data);
            try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            LOGGER.error("Failed async player save write to {}", target, e);
        }
    }

    public static void shutdown() {
        ThreadPoolExecutor p = pool;
        if (p == null) return;
        p.shutdown();
        try { if (!p.awaitTermination(60, TimeUnit.SECONDS)) { LOGGER.warn("Player Save IO pool did not drain in 60s"); p.shutdownNow(); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); p.shutdownNow(); }
        pool = null;
    }
}
