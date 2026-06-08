package org.bxteam.divinemc.async.rct;

import ca.spottedleaf.moonrise.common.list.IteratorSafeOrderedReferenceSet;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.datafixers.DataFixer;
import io.papermc.paper.entity.activation.ActivationRange;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.util.NamedAgnosticThreadFactory;
import org.jetbrains.annotations.NotNull;

public final class RegionizedChunkTicking extends ServerChunkCache {
    public static final Executor REGION_EXECUTOR = buildRegionExecutor();

    // Like Executors.newFixedThreadPool but with a finite keep-alive + allowCoreThreadTimeOut, so idle
    // region-ticking threads are reaped after 60s of no work (e.g. an empty server) instead of pinning
    // the full thread count for the lifetime of the process. The pool re-spawns threads on demand the
    // next tick that has work, so steady-state behaviour under load is unchanged.
    private static Executor buildRegionExecutor() {
        final int n = Math.max(1, DivineConfig.AsyncCategory.regionizedChunkTickingExecutorThreadCount);
        final java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
            n, n, 60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(),
            new NamedAgnosticThreadFactory<>("Region Ticking", TickThread::new, DivineConfig.AsyncCategory.regionizedChunkTickingExecutorThreadPriority));
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
    private static final int LOG_INTERVAL = 18000;
    // Chunk-radius around an entity that must stay within its own region for the entity to be safely
    // ticked on a region thread. 1 covers normal per-tick movement (<1 chunk); border entities are
    // deferred to the sequential main-thread pass to avoid cross-region entityStatusChange races.
    // NOTE: this is only the BASE margin. The effective margin is widened per-entity by its projected
    // movement this tick (see entityReachChunks) so fast movers (projectiles, knockback, riptide,
    // ender pearls, elytra) that would cross >1 chunk in a single tick are also deferred. Without this,
    // a fast entity leaves its region's interior mid-tick and races a concurrently-ticking neighbour
    // region on the same ChunkEntitySlices -> EntityLookup#entityStatusChange "is receiving update" abort.
    private static final int ENTITY_BORDER_MARGIN = 1;
    private final AvgTimeLogger avgTimeLogger;
    private int i = 0;

    public RegionizedChunkTicking(
        ServerLevel level,
        LevelStorageSource.LevelStorageAccess levelStorageAccess,
        DataFixer fixerUpper,
        StructureTemplateManager structureTemplateManager,
        Executor executor,
        ChunkGenerator generator,
        int viewDistance,
        int simulationDistance,
        boolean sync,
        ChunkStatusUpdateListener chunkStatusListener,
        Supplier<SavedDataStorage> overworldDataStorage,
        final SavedDataStorage savedDataStorage
    ) {
        super(level, levelStorageAccess, fixerUpper, structureTemplateManager, executor, generator, viewDistance, simulationDistance, sync, chunkStatusListener, overworldDataStorage, savedDataStorage);
        this.avgTimeLogger = new AvgTimeLogger(level.serverLevelData.getLevelName());
    }

    @Override
    protected void iterateTickingChunksFaster(final @NotNull CompletableFuture<Void> spawns) {
        final ServerLevel world = this.level;
        final int randomTickSpeed = world.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
        // Purpur - regionized chunk ticking (DivineMC) - ReferenceList has no toArray() in this tree; build an exact-size snapshot from the backing array
        final ca.spottedleaf.moonrise.common.list.ReferenceList<LevelChunk> entityTickingChunks = world.moonrise$getEntityTickingChunks();
        final LevelChunk[] raw = Arrays.copyOf(entityTickingChunks.getRawDataUnchecked(), entityTickingChunks.size());
        final TickPair tickPair = computePlayerRegions();
        final RegionData[] regions = tickPair.regions();

        ActivationRange.activateEntities(level); // Paper - EAR
        ObjectArrayList<CompletableFuture<LongOpenHashSet>> futures = new ObjectArrayList<>(regions.length);
        for (final RegionData region : regions) {
            if (region == null || region.isEmpty()) {
                continue;
            }
            futures.add(tick(region, randomTickSpeed));
        }

        finishTicking(futures, randomTickSpeed, raw, tickPair);
        spawns.join();
    }

    private CompletableFuture<LongOpenHashSet> tick(RegionData region, int randomTickSpeed) {
        return CompletableFuture.supplyAsync(() -> {
            final long start = System.nanoTime();
            final LongOpenHashSet regionChunksIDs = new LongOpenHashSet(region.chunks().size());
            for (final long key : region.chunks()) {
                final LevelChunk chunk = fullChunks.get(key);
                if (chunk != null) {
                    level.tickChunk(chunk, randomTickSpeed);
                    regionChunksIDs.add(key);
                }
            }

            for (Entity entity : region.entities()) {
                tickEntity(entity);
            }

            final long end = System.nanoTime();
            region.players().forEach(player -> player.avgTickTimeNanos.add(end - start));
            return regionChunksIDs;
        }, REGION_EXECUTOR);
    }

    private void finishTicking(final ObjectArrayList<CompletableFuture<LongOpenHashSet>> ticked, final int randomTickSpeed, final LevelChunk[] raw, final TickPair tickPair) {
        try {
            CompletableFuture.allOf(ticked.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException ex) {
            LOGGER.error("Error during region chunk ticking", ex.getCause());
        }

        if (false && i % 100 == 0 && tickPair.regions().length > 0) {
            REGION_EXECUTOR.execute(() -> {
                StringBuilder sb = new StringBuilder();
                for (RegionData regionData : tickPair.regions()) {
                    sb.append("Region with ").append(regionData.chunks().size()).append(" chunks and ").append(regionData.entities().size()).append(" entities ticked for Players:\n");
                    for (ServerPlayer player : regionData.players()) {
                        long avgNanos = Math.round(player.avgTickTimeNanos.average().orElse(0));
                        long ms = avgNanos / 1_000_000;
                        long us = (avgNanos % 1_000_000) / 1_000;
                        long ns = avgNanos % 1_000;
                        sb.append("- ").append(player.displayName).append(" avg region tick time: ").append(ms).append(" ms ").append(us).append(" us ").append(ns).append(" ns").append("\n");
                    }
                }
                avgTimeLogger.logTickTime(sb.toString());
            });
        }

        LongOpenHashSet tickedChunkKeys = new LongOpenHashSet(raw.length);

        for (CompletableFuture<LongOpenHashSet> future : ticked) {
            if (!future.isCompletedExceptionally()) {
                try {
                    tickedChunkKeys.addAll(future.join());
                } catch (Exception e) {
                    LOGGER.error("Exception retrieving region ticking result", e);
                }
            }
        }

        for (LevelChunk chunk : raw) {
            if (!tickedChunkKeys.contains(chunk.coordinateKey)) {
                level.tickChunk(chunk, randomTickSpeed);
            }
        }

        for (Entity entity : tickPair.entities()) {
            tickEntity(entity);
        }
    }

    private TickPair computePlayerRegions() {
        List<ServerPlayer> players = new ArrayList<>(level.players());
        final int defaultTickDist = level.moonrise$getViewDistanceHolder().getViewDistances().tickViewDistance();
        final int defaultAmountOfChunks = (2 * defaultTickDist + 1) * (2 * defaultTickDist + 1);
        final int playerCount = players.size();

        Rectangle[] boundaries = new Rectangle[playerCount];
        int[] playerTickDistances = new int[playerCount];

        for (int i = 0; i < playerCount; i++) {
            ServerPlayer player = players.get(i);
            ChunkPos pos = player.chunkPosition();
            int tickDist = player.moonrise$getViewDistanceHolder().getViewDistances().tickViewDistance();
            if (tickDist == -1) tickDist = defaultTickDist;

            playerTickDistances[i] = tickDist;

            boundaries[i] = new Rectangle(
                pos.x() - tickDist, pos.z() - tickDist,
                pos.x() + tickDist, pos.z() + tickDist
            );
        }

        UnionFind uf = new UnionFind(playerCount);
        for (int i = 0; i < playerCount; i++) {
            for (int j = i + 1; j < playerCount; j++) {
                if (boundaries[i].intersects(boundaries[j])) {
                    uf.union(i, j);
                }
            }
        }

        Int2IntOpenHashMap rootToGroup = new Int2IntOpenHashMap(playerCount);
        rootToGroup.defaultReturnValue(-1);
        ObjectArrayList<IntArrayList> groups = new ObjectArrayList<>();

        for (int i = 0; i < playerCount; i++) {
            int root = uf.find(i);
            int groupIdx = rootToGroup.get(root);
            if (groupIdx == -1) {
                groupIdx = groups.size();
                rootToGroup.put(root, groupIdx);
                groups.add(new IntArrayList(1));
            }
            groups.get(groupIdx).add(i);
        }

        ObjectArrayList<RegionData> regions = new ObjectArrayList<>(groups.size());

        int totalEstimatedChunks = 0;

        for (IntArrayList group : groups) {
            if (group.isEmpty()) continue;

            LongOpenHashSet groupChunks = new LongOpenHashSet(defaultAmountOfChunks);

            for (int i = 0; i < group.size(); i++) {
                int playerIdx = group.getInt(i);
                ServerPlayer player = players.get(playerIdx);
                ChunkPos center = player.chunkPosition();
                int dist = playerTickDistances[playerIdx];

                for (int dx = -dist; dx <= dist; dx++) {
                    for (int dz = -dist; dz <= dist; dz++) {
                        groupChunks.add(CoordinateUtils.getChunkKey(center.x() + dx, center.z() + dz));
                    }
                }
            }

            regions.add(new RegionData(groupChunks, ConcurrentHashMap.newKeySet(100), ConcurrentHashMap.newKeySet(4)));
            totalEstimatedChunks += groupChunks.size();
        }

        Long2IntOpenHashMap chunkToRegion = new Long2IntOpenHashMap(totalEstimatedChunks);
        chunkToRegion.defaultReturnValue(-1);

        for (int idx = 0; idx < regions.size(); idx++) {
            for (long key : regions.get(idx).chunks()) {
                chunkToRegion.put(key, idx);
            }
        }

        i++;
        if (false && i % LOG_INTERVAL == 0) {
            LOGGER.info("Computed {} regions for {} players", regions.size(), players.size());
            LOGGER.info("region sizes for each region: {}", Arrays.toString(regions.stream().mapToInt(r -> r.chunks().size()).toArray()));
        }

        final Set<Entity> firstTick = ConcurrentHashMap.newKeySet();

        IteratorSafeOrderedReferenceSet<Entity> entities;
        synchronized (entities = getEntityTickList().entities) {
            entities.createRawIterator();

            try {
                final Entity[] rawList = entities.getListRaw();
                final int limit = entities.getListSize();
                Arrays.stream(rawList, 0, limit)
                    .parallel()
                    .filter(Objects::nonNull)
                    .forEach(entity -> {
                        final ChunkPos cp = entity.chunkPosition();
                        final int regionIndex = chunkToRegion.get(cp.pack());
                        if (regionIndex == -1) {
                            firstTick.add(entity);
                            return;
                        }
                        final RegionData targetRegion = regions.get(regionIndex);
                        if (entity instanceof ServerPlayer player) {
                            targetRegion.players().add(player);
                        }
                        // Border safety: a region thread may only tick an entity that cannot, in one tick,
                        // move into a chunk owned by a DIFFERENT concurrently-ticking region. If the whole
                        // ENTITY_BORDER_MARGIN-chunk neighbourhood is in this same region (or unregioned,
                        // which no thread touches during region ticking), it is safe; otherwise defer it to
                        // the sequential main-thread pass so its cross-slice entityStatusChange never races
                        // another region's tick (the "entity chunk is receiving update" abort).
                        if (isRegionInterior(chunkToRegion, cp.x(), cp.z(), regionIndex, entityReachChunks(entity))) {
                            targetRegion.entities().add(entity);
                        } else {
                            firstTick.add(entity);
                        }
                    });
            } finally {
                entities.finishRawIterator();
            }
        }

        regions.sort(Comparator.comparingDouble(r -> ((RegionData) r).players().stream().map(p -> p.avgTickTimeNanos.average().orElse(-1)).max(Comparator.naturalOrder()).orElse(-1d)).reversed());
        return new TickPair(regions.toArray(new RegionData[0]), firstTick);
    }

    /**
     * Projected chunk reach of an entity for the upcoming tick: the base {@link #ENTITY_BORDER_MARGIN}
     * plus the number of chunks its current velocity can carry it horizontally in one tick. Computed from
     * the pre-tick delta movement (set last tick, only read here), so a projectile/knockback/riptide/elytra
     * entity that will travel several chunks is treated as a wide-radius entity and deferred to the
     * sequential main-thread pass instead of being parallel-ticked into a neighbour region.
     *
     * <p>Velocity-less long jumps (enderman/chorus/command teleports) carry no pre-tick delta and remain
     * caught by Moonrise's own {@code startPreventingStatusUpdates} guard, which aborts safely (no
     * corruption) rather than racing; they are rare and unbounded, so no static margin can cover them.
     */
    private static int entityReachChunks(final Entity entity) {
        final net.minecraft.world.phys.Vec3 v = entity.getDeltaMovement();
        // chunks are 16 blocks; use Chebyshev (max of axes) since the interior check is a square ring
        final double horizBlocks = Math.max(Math.abs(v.x), Math.abs(v.z));
        return ENTITY_BORDER_MARGIN + (int) Math.ceil(horizBlocks / 16.0);
    }

    /**
     * @return true if every chunk within {@code margin} of (chunkX,chunkZ) belongs to {@code regionIndex}
     * or to no region at all. Only then can an entity there be ticked on the region thread without its
     * movement this tick crossing into another concurrently-ticking region's slices. {@code margin} is the
     * per-entity {@link #entityReachChunks} value, never below {@link #ENTITY_BORDER_MARGIN}.
     */
    private static boolean isRegionInterior(final Long2IntOpenHashMap chunkToRegion,
                                            final int chunkX, final int chunkZ, final int regionIndex,
                                            final int margin) {
        for (int dx = -margin; dx <= margin; dx++) {
            for (int dz = -margin; dz <= margin; dz++) {
                final int neighbour = chunkToRegion.get(CoordinateUtils.getChunkKey(chunkX + dx, chunkZ + dz));
                if (neighbour != -1 && neighbour != regionIndex) {
                    return false;
                }
            }
        }
        return true;
    }

    private void tickEntity(Entity entity) {
        if (!entity.isRemoved() && !level.tickRateManager().isEntityFrozen(entity)) {
            if (entity.moonrise$isUpdatingSectionStatus()) {
                LOGGER.info("Skipping tick for entity {} as it is in the process of updating section status", entity);
                return;
            }
            entity.checkDespawn();
            // Paper - rewrite chunk system
            Entity vehicle = entity.getVehicle();
            if (vehicle != null) {
                if (!vehicle.isRemoved() && vehicle.hasPassenger(entity)) {
                    return;
                }

                entity.stopRiding();
            }

            level.guardEntityTick(level::tickNonPassenger, entity);
        }
    }

    @Override
    public void close() throws IOException {
        avgTimeLogger.close();
        super.close();
    }

    record RegionData(LongOpenHashSet chunks, Set<Entity> entities, Set<ServerPlayer> players) {
        public boolean isEmpty() {
            return chunks.isEmpty();
        }
    }

    record Rectangle(int minX, int minZ, int maxX, int maxZ) {
        boolean intersects(Rectangle other) {
            return !(this.maxX < other.minX ||
                this.minX > other.maxX ||
                this.maxZ < other.minZ ||
                this.minZ > other.maxZ);
        }
    }

    record TickPair(RegionData[] regions, Set<Entity> entities) {
    }
}
