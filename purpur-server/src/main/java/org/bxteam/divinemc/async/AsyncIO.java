package org.bxteam.divinemc.async;

import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.util.NamedAgnosticThreadFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;

/**
 * Shared bounded I/O pool for the async-enhancements feature: it offloads the gzip-compression
 * and disk-write of already-serialized (uncompressed) player NBT off the main thread.
 * <p>
 * NBT serialization stays on the main thread (the tag is not thread-safe). The pool only receives
 * an immutable, right-sized {@code byte[]} copy of the uncompressed NBT, gzips it, and writes it
 * atomically (temp file + ATOMIC_MOVE) so a crash never leaves a partial/corrupt {@code .dat}.
 * gzip of the uncompressed NBT bytes is byte-equivalent (decompresses identically) to
 * {@link net.minecraft.nbt.NbtIo#writeCompressed}, so written files load back correctly.
 */
@org.jspecify.annotations.NullMarked
public final class AsyncIO {
    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("AsyncIO");
    private static volatile ThreadPoolExecutor pool;

    private AsyncIO() {
    }

    public static void init() {
        if (!DivineConfig.AsyncCategory.asyncPlayerNbtCompression) return;
        int threads = Math.max(1, DivineConfig.AsyncCategory.ioPoolThreads);
        ThreadPoolExecutor p = new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new NamedAgnosticThreadFactory<>("Async IO", Thread::new, Thread.NORM_PRIORITY),
            new ThreadPoolExecutor.AbortPolicy()); // overflow throws -> we catch for inline fallback
        p.allowCoreThreadTimeOut(true);
        pool = p;
    }

    /**
     * Called on the MAIN thread with the already-serialized UNCOMPRESSED NBT bytes (a copy).
     * Submits a task that gzips the bytes and writes them atomically to {@code target}.
     * Returns false if not offloaded (pool absent or queue overflow); the caller must then write inline.
     */
    public static boolean submitGzipWrite(byte[] rawUncompressed, Path target) {
        ThreadPoolExecutor p = pool;
        if (p == null) return false;
        try {
            p.execute(() -> writeGzipAtomic(target, rawUncompressed));
            return true;
        } catch (RejectedExecutionException overflow) {
            return false; // caller writes inline
        }
    }

    private static void writeGzipAtomic(Path target, byte[] raw) {
        try {
            Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
            try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
                out.write(raw);
            }
            try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            LOGGER.error("Failed async I/O gzip write to {}", target, e);
        }
    }

    public static void shutdown() {
        ThreadPoolExecutor p = pool;
        if (p == null) return;
        p.shutdown();
        try { if (!p.awaitTermination(60, TimeUnit.SECONDS)) { LOGGER.warn("Async IO pool did not drain in 60s"); p.shutdownNow(); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); p.shutdownNow(); }
        pool = null;
    }
}
