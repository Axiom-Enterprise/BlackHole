package org.bxteam.divinemc.async.tracking;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.misc.NearbyPlayers;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.server.ServerEntityLookup;
import ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerEntity;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.util.NamedAgnosticThreadFactory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MultithreadedTracker {
    private static final String THREAD_PREFIX = "Async Tracker";
    private static final Logger LOGGER = LogManager.getLogger(THREAD_PREFIX);

    private static long lastWarnMillis = System.currentTimeMillis();
    public static final ThreadPoolExecutor TRACKER_EXECUTOR = DivineConfig.AsyncCategory.multithreadedEnabled ? new ThreadPoolExecutor(
        getCorePoolSize(),
        getMaxPoolSize(),
        getKeepAliveTime(), TimeUnit.SECONDS,
        getQueueImpl(),
        getThreadFactory(),
        getRejectedPolicy()
    ) : null;

    static {
        // Leaf - core==max (see getCorePoolSize); let idle shard threads time out per keep-alive
        // instead of being pinned for the lifetime of the server.
        if (TRACKER_EXECUTOR != null) {
            TRACKER_EXECUTOR.allowCoreThreadTimeOut(true);
        }
    }

    public static void tick(ServerLevel level) {
        try {
            if (!DivineConfig.AsyncCategory.multithreadedCompatModeEnabled) {
                tickAsync(level);
            } else {
                tickAsyncWithCompatMode(level);
            }
        } catch (Exception e) {
            LOGGER.error("Error occurred while executing async task.", e);
        }
    }

    private static void tickAsync(ServerLevel level) {
        final NearbyPlayers nearbyPlayers = level.moonrise$getNearbyPlayers();
        final ServerEntityLookup entityLookup = (ServerEntityLookup) level.moonrise$getEntityLookup();

        final ReferenceList<Entity> trackerEntities = entityLookup.trackerEntities;
        final Entity[] trackerEntitiesRaw = trackerEntities.getRawDataUnchecked();
        final int size = trackerEntities.size(); // Leaf - iterate live [0,size) only; ReferenceList nulls the tail on remove, so this is equivalent to the full-array null-skip but avoids scanning the null padding each tick

        // Leaf start - resolve per-entity tracker + chunk on the calling (main/region) thread.
        // Entity#chunkPosition() is mutated by setPos on the entity tick thread; reading it inside the
        // worker raced with that write (stale/wrong NearbyPlayers chunk -> wrong viewer set). Mirror the
        // compat path: snapshot here, defer only the heavy moonrise$tick/sendChanges to the pool. Use
        // parallel arrays (no per-entity lambda) to keep this allocation-light.
        final ChunkMap.TrackedEntity[] trackers = new ChunkMap.TrackedEntity[size];
        final NearbyPlayers.TrackedChunk[] trackedChunks = new NearbyPlayers.TrackedChunk[size];
        int index = 0;
        for (int i = 0; i < size; i++) {
            final Entity entity = trackerEntitiesRaw[i];
            if (entity == null) continue;

            final ChunkMap.TrackedEntity tracker = ((EntityTrackerEntity) entity).moonrise$getTrackedEntity();
            if (tracker == null) continue;

            trackers[index] = tracker;
            trackedChunks[index] = nearbyPlayers.getChunk(entity.chunkPosition());
            index++;
        }

        submitSharded(index, j -> {
            final ChunkMap.TrackedEntity tracker = trackers[j];
            synchronized (tracker) {
                tracker.moonrise$tick(trackedChunks[j]);
                tracker.serverEntity.sendChanges();
            }
        });
        // Leaf end
    }

    private static void tickAsyncWithCompatMode(ServerLevel level) {
        final NearbyPlayers nearbyPlayers = level.moonrise$getNearbyPlayers();
        final ServerEntityLookup entityLookup = (ServerEntityLookup) level.moonrise$getEntityLookup();

        final ReferenceList<Entity> trackerEntities = entityLookup.trackerEntities;
        final Entity[] trackerEntitiesRaw = trackerEntities.getRawDataUnchecked();
        final int size = trackerEntities.size(); // Leaf - bound by live size (tail is null), skip padding scan
        final Runnable[] tickTask = new Runnable[size];
        final ChunkMap.TrackedEntity[] sendChangesTrackers = new ChunkMap.TrackedEntity[size]; // Leaf - store tracker directly instead of allocating a per-entity sendChanges lambda each tick
        int index = 0;

        for (int i = 0; i < size; i++) {
            final Entity entity = trackerEntitiesRaw[i];
            if (entity == null) continue;

            final ChunkMap.TrackedEntity tracker = ((EntityTrackerEntity) entity).moonrise$getTrackedEntity();

            if (tracker == null) continue;

            synchronized (tracker) {
                tickTask[index] = tracker.tickCompact(nearbyPlayers.getChunk(entity.chunkPosition()));
                sendChangesTrackers[index] = tracker;
            }
            index++;
        }

        // Leaf start - shard across the pool instead of one whole-level task (see submitSharded).
        // Chunks were already resolved on the calling thread above, so this only parallelizes the
        // tick.run()/sendChanges work; per-tracker independence + the synchronized snapshot keep it safe.
        submitSharded(index, j -> {
            final Runnable tick = tickTask[j];
            if (tick != null) tick.run();
            sendChangesTrackers[j].serverEntity.sendChanges();
        });
        // Leaf end
    }

    // Original ChunkMap#newTrackerTick of Paper
    // Just for diff usage for future update
    @SuppressWarnings("DuplicatedCode")
    private static void tickOriginal(ServerLevel level) {
        final ca.spottedleaf.moonrise.patches.chunk_system.level.entity.server.ServerEntityLookup entityLookup = (ca.spottedleaf.moonrise.patches.chunk_system.level.entity.server.ServerEntityLookup) ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) level).moonrise$getEntityLookup();

        final ca.spottedleaf.moonrise.common.list.ReferenceList<net.minecraft.world.entity.Entity> trackerEntities = entityLookup.trackerEntities;
        final Entity[] trackerEntitiesRaw = trackerEntities.getRawDataUnchecked();
        for (int i = 0, len = trackerEntities.size(); i < len; ++i) {
            final Entity entity = trackerEntitiesRaw[i];
            final ChunkMap.TrackedEntity tracker = ((ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerEntity) entity).moonrise$getTrackedEntity();
            if (tracker == null) {
                continue;
            }
            ((ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerTrackedEntity) tracker).moonrise$tick(((ca.spottedleaf.moonrise.patches.chunk_system.entity.ChunkSystemEntity) entity).moonrise$getChunkData().nearbyPlayers);
            if (((ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerTrackedEntity) tracker).moonrise$hasPlayers()
                || ((ca.spottedleaf.moonrise.patches.chunk_system.entity.ChunkSystemEntity) entity).moonrise$getChunkStatus().isOrAfter(FullChunkStatus.ENTITY_TICKING)) {
                tracker.serverEntity.sendChanges();
            }
        }
    }

    // Leaf start - split [0,count) tracking work into up to maxPoolSize contiguous shards and submit
    // one task per shard, so the pool actually parallelizes within a level. ThreadPoolExecutor only
    // grows past corePoolSize once the queue is full, so a single whole-level task never used more than
    // one thread; sharding + core==max (below) realizes the configured parallelism. Each entity's
    // tracker is independent and individually synchronized, so this preserves tracking semantics.
    @FunctionalInterface
    private interface IndexTask {
        void run(int index);
    }

    private static void submitSharded(final int count, final IndexTask task) {
        if (count <= 0) return;

        final int threads = Math.max(1, Math.min(count, getMaxPoolSize()));
        final int shardSize = (count + threads - 1) / threads;
        for (int t = 0; t < threads; t++) {
            final int start = t * shardSize;
            if (start >= count) break;
            final int end = Math.min(start + shardSize, count);
            TRACKER_EXECUTOR.execute(() -> {
                for (int j = start; j < end; j++) {
                    task.run(j);
                }
            });
        }
    }
    // Leaf end

    private static int getCorePoolSize() {
        // Leaf - keep core == max so sharded tasks (submitSharded) run concurrently; with corePoolSize=1
        // the executor only ever used a single thread because the queue never filled.
        return Math.max(1, getMaxPoolSize());
    }

    private static int getMaxPoolSize() {
        return DivineConfig.AsyncCategory.asyncEntityTrackerMaxThreads;
    }

    private static long getKeepAliveTime() {
        return DivineConfig.AsyncCategory.asyncEntityTrackerKeepalive;
    }

    private static BlockingQueue<Runnable> getQueueImpl() {
        final int queueCapacity = DivineConfig.AsyncCategory.asyncEntityTrackerQueueSize;

        return new LinkedBlockingQueue<>(queueCapacity);
    }

    private static @NotNull ThreadFactory getThreadFactory() {
        return new NamedAgnosticThreadFactory<>(THREAD_PREFIX, TickThread::new, Thread.NORM_PRIORITY - 2);
    }

    private static @NotNull RejectedExecutionHandler getRejectedPolicy() {
        return (rejectedTask, executor) -> {
            BlockingQueue<Runnable> workQueue = executor.getQueue();

            if (!executor.isShutdown()) {
                if (!workQueue.isEmpty()) {
                    List<Runnable> pendingTasks = new ArrayList<>(workQueue.size());

                    workQueue.drainTo(pendingTasks);

                    for (Runnable pendingTask : pendingTasks) {
                        pendingTask.run();
                    }
                }

                rejectedTask.run();
            }

            if (System.currentTimeMillis() - lastWarnMillis > 30000L) {
                LOGGER.warn("Async entity tracker is busy! Tracking tasks will be done in the server thread. Increasing max-threads in DivineMC config may help.");
                lastWarnMillis = System.currentTimeMillis();
            }
        };
    }

    public static class MultithreadedTrackerThread extends Thread {
        public MultithreadedTrackerThread(Runnable runnable) {
            super(runnable);
        }
    }
}
