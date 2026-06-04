package org.dreeam.leaf.async;

import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.TimeUnit;

// Purpur - async playerdata save (Leaf)
// Adapted from Leaf's org.dreeam.leaf.async.ShutdownExecutors. Leaf's original also drained
// server.mobSpawnExecutor, AsyncPathProcessor.PATH_PROCESSING_EXECUTOR and
// AsyncTracker.TRACKER_EXECUTOR. On this Purpur (DivineMC) base those subsystems live under
// org.bxteam.divinemc.* (Pufferfish AsyncExecutor for mob spawning, MultithreadedTracker for the
// entity tracker) and are managed by their own patches, so only the player I/O pool introduced by
// this feature is drained here. This guarantees pending asynchronous player-data saves are flushed
// to disk before the JVM exits.
public class ShutdownExecutors {

    public static final Logger LOGGER = LogManager.getLogger("Leaf");

    public static void shutdown(MinecraftServer server) {
        if (AsyncPlayerDataSaving.IO_POOL != null) {
            LOGGER.info("Waiting for player I/O executor to shutdown...");
            AsyncPlayerDataSaving.IO_POOL.shutdown();
            try {
                AsyncPlayerDataSaving.IO_POOL.awaitTermination(60L, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
