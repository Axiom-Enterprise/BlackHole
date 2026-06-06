package org.bxteam.divinemc.antixray;

import dev.imanity.antixray.sdk.AntiXrayAdapter;
import dev.imanity.antixray.sdk.AntiXraySDK;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.entity.Player;
import org.bxteam.divinemc.config.DivineConfig;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final int REVEAL_DEDUP_CAP = 1 << 15; // bound per-player revealed-set growth

    private static volatile RaytraceAntiXrayEngine instance;

    private final ExecutorService workers;
    private final Set<Block> hiddenBlocks;
    // Per-player set of already-revealed ore positions, to avoid resending each interval.
    private final ConcurrentHashMap<UUID, LongOpenHashSet> revealed = new ConcurrentHashMap<>();
    // Cached obfuscated section arrays per chunk, reused across viewers; invalidated on any block change.
    // The arrays are immutable copies, so sharing them between connections/sends is safe.
    private static final byte[] NO_OBF = new byte[0]; // sentinel: chunk has no hideable ore
    private static final int OBF_CACHE_CAP = 8192; // per-world entry cap; cleared wholesale on overflow
    // Cache the serialized obfuscated chunk-data buffer per chunk. byte[] is immutable and read-only on
    // send, so it is shared across all viewers and threads - the ore scan, section copy AND serialization
    // run once per chunk instead of once per (chunk, player). Invalidated on any block change.
    private final ConcurrentHashMap<World, ConcurrentHashMap<Long, byte[]>> obfCache = new ConcurrentHashMap<>();
    private long revealTick; // tick thread only
    // Diagnostics only. `submitted` has a single writer (SDK callbacks fire on the tick thread).
    // The hit counters are written from workers and are intentionally best-effort (no atomics):
    // an occasional lost increment is fine for stats and avoids CAS traffic on the hot path.
    private volatile long submitted;
    private volatile long visibleHits;
    private volatile long occludedHits;

    private RaytraceAntiXrayEngine(final int threads, final Set<Block> hiddenBlocks) {
        this.hiddenBlocks = hiddenBlocks;
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
        final Set<Block> hidden = new HashSet<>();
        for (final Material material : DivineConfig.PerformanceCategory.raytraceHiddenBlocks) {
            final Block block = CraftMagicNumbers.getBlock(material);
            if (block != null) {
                hidden.add(block);
            }
        }
        final RaytraceAntiXrayEngine engine = new RaytraceAntiXrayEngine(threads, hidden);
        instance = engine;
        AntiXraySDK.setAdapter(engine);
        LOGGER.info("Raytrace AntiXray engine started ({} threads, radius {}, obfuscate-on-send={}, {} hideable blocks, re-hide={})",
            threads, DivineConfig.PerformanceCategory.raytraceEngineRadius,
            DivineConfig.PerformanceCategory.raytraceObfuscateOnSend, hidden.size(),
            DivineConfig.PerformanceCategory.raytraceReHide);
        if (hidden.isEmpty()) {
            LOGGER.warn("Raytrace AntiXray: hidden-blocks resolved to ZERO blocks - no ore will be hidden. "
                + "Check raytrace-antixray.engine.hidden-blocks (material names) in the config.");
        } else if (!DivineConfig.PerformanceCategory.raytraceObfuscateOnSend) {
            LOGGER.warn("Raytrace AntiXray: engine is ON but obfuscate-on-send is OFF - ores are NOT hidden, "
                + "only revealed on sight. Enable raytrace-antixray.engine.obfuscate-on-send to actually hide ores.");
        }
    }

    public static RaytraceAntiXrayEngine instance() {
        return instance;
    }

    private void shutdown() {
        if (AntiXraySDK.getAdapter() == this) {
            AntiXraySDK.setAdapter(null);
        }
        this.revealed.clear();
        this.obfCache.clear();
        this.workers.shutdownNow();
    }

    @Override
    public void callBlockChange(final World world, final int x, final int y, final int z, final Material material) {
        this.submitted++; // tick thread only
        // Only hideable blocks (ores) are worth a reveal pass.
        if (!DivineConfig.PerformanceCategory.raytraceHiddenBlocks.contains(material)) {
            return;
        }
        if (!(world instanceof final CraftWorld craftWorld)) {
            return;
        }
        final ServerLevel level = craftWorld.getHandle();
        final BlockPos target = new BlockPos(x, y, z);
        final BlockState realState = level.getBlockStateIfLoaded(target); // tick thread
        if (realState == null) {
            return;
        }

        final int radius = DivineConfig.PerformanceCategory.raytraceEngineRadius;
        final long radiusSq = (long) radius * radius;
        final double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;

        // For each viewer in range, capture eye + a bounded occlusion snapshot on the TICK THREAD,
        // then raytrace off-thread. On a clear line of sight, send the real block (reveal-on-sight).
        // This only ever sends MORE accurate data on top of an obfuscated chunk, so it cannot break
        // gameplay whether or not Paper's engine-mode anti-xray is active.
        for (final Player bukkitPlayer : world.getPlayers()) {
            final Location eye = bukkitPlayer.getEyeLocation();
            final double eyeX = eye.getX(), eyeY = eye.getY(), eyeZ = eye.getZ();
            final double dx = eyeX - cx, dy = eyeY - cy, dz = eyeZ - cz;
            if (dx * dx + dy * dy + dz * dz > radiusSq) {
                continue;
            }

            final int ex = (int) Math.floor(eyeX);
            final int ey = (int) Math.floor(eyeY);
            final int ez = (int) Math.floor(eyeZ);
            final OcclusionSnapshot snapshot = OcclusionSnapshot.capture(level,
                Math.min(ex, x) - 1, Math.min(ey, y) - 1, Math.min(ez, z) - 1,
                Math.max(ex, x) + 1, Math.max(ey, y) + 1, Math.max(ez, z) + 1);
            if (snapshot == null) {
                continue; // out of capture bounds (too far) - skip
            }

            final ServerGamePacketListenerImpl connection = ((CraftPlayer) bukkitPlayer).getHandle().connection;
            this.workers.execute(() -> {
                if (Raytracer.isVisible(snapshot, eyeX, eyeY, eyeZ, x, y, z)) {
                    connection.send(new ClientboundBlockUpdatePacket(target, realState)); // reveal
                    this.visibleHits++; // best-effort diagnostic
                } else {
                    this.occludedHits++; // best-effort diagnostic
                }
            });
        }
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

    /**
     * Build the obfuscated section array for a chunk being sent to a player: a clone of the chunk's
     * section array where every section containing a hideable ore is replaced by a deep copy with those
     * ores rewritten to a dimension-appropriate fake block. The chunk packet is then serialized from
     * these copies, so the real ore data never reaches the wire (unlike an after-the-fact overlay, which
     * leaks the real chunk packet and is defeated by packet-sniffing xray). Reveal-on-sight then re-adds
     * the ores a viewer can actually see, on top of this obfuscated base.
     *
     * <p>Runs on the tick thread (reads live chunk state). Returns null when disabled, no engine is
     * active, or the chunk has no hideable ore — in which case the caller uses the normal send path.
     */
    public static byte[] obfuscatedBuffer(final LevelChunk chunk) {
        final RaytraceAntiXrayEngine engine = instance;
        if (engine == null || !DivineConfig.PerformanceCategory.raytraceObfuscateOnSend) {
            return null;
        }
        // Cache the (expensive) per-block scan + section copies + serialization per chunk and reuse the
        // resulting buffer across viewers. Invalidated on any block change via onBlockChanged().
        final World world = chunk.getLevel().getWorld();
        final long key = chunk.getPos().pack();
        final ConcurrentHashMap<Long, byte[]> worldCache =
            engine.obfCache.computeIfAbsent(world, w -> new ConcurrentHashMap<>());
        final byte[] cached = worldCache.get(key);
        if (cached != null) {
            return cached == NO_OBF ? null : cached;
        }
        final byte[] buf;
        try {
            final LevelChunkSection[] sections = engine.buildObfuscatedSections(chunk);
            buf = sections == null
                ? NO_OBF
                : net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData.serialize(sections);
        } catch (final Throwable t) {
            // Anti-xray must NEVER break chunk delivery. Under parallel/regionized ticking a section can
            // mutate mid-serialize; fall back to the normal (unobfuscated) send for this attempt and do
            // NOT cache, so the next send rebuilds. Better a momentarily visible ore than an unsent chunk.
            LOGGER.warn("Anti-xray obfuscation failed for chunk {}; sending unobfuscated this time", chunk.getPos(), t);
            return null;
        }
        if (worldCache.size() < OBF_CACHE_CAP) {
            worldCache.put(key, buf);
        } else {
            worldCache.clear(); // crude bound; rebuilt lazily on next send
        }
        return buf == NO_OBF ? null : buf;
    }

    /** Invalidate the cached obfuscation for the chunk containing (x,z). Cheap no-op when disabled. */
    public static void onBlockChanged(final World world, final int x, final int z) {
        final RaytraceAntiXrayEngine engine = instance;
        if (engine == null || !DivineConfig.PerformanceCategory.raytraceObfuscateOnSend) {
            return;
        }
        final ConcurrentHashMap<Long, byte[]> worldCache = engine.obfCache.get(world);
        if (worldCache != null) {
            worldCache.remove(ChunkPos.pack(x >> 4, z >> 4));
        }
    }

    private LevelChunkSection[] buildObfuscatedSections(final LevelChunk chunk) {
        final BlockState fake = fakeStateFor(chunk.getLevel().getWorld().getEnvironment());
        final Set<Block> hidden = this.hiddenBlocks;
        final LevelChunkSection[] sections = chunk.getSections();

        // Phase 1: does this chunk contain any hideable ore at all? If not, no obfuscation - normal path.
        boolean hasOre = false;
        for (final LevelChunkSection section : sections) {
            if (section != null && !section.hasOnlyAir()
                && section.maybeHas(state -> hidden.contains(state.getBlock()))) {
                hasOre = true;
                break;
            }
        }
        if (!hasOre) {
            return null;
        }

        // Phase 2: snapshot EVERY section into an immutable copy and rewrite ores in the copies. We must
        // copy all sections (not just ore-bearing ones): the buffer is serialized in two passes
        // (size then write), and under parallel/regionized ticking a shared live section could mutate
        // between them, breaking the size invariant. A per-section copy() is self-consistent, so the
        // serialize never races the world. The whole array is cached and reused across viewers.
        final LevelChunkSection[] out = new LevelChunkSection[sections.length];
        for (int i = 0; i < sections.length; i++) {
            final LevelChunkSection section = sections[i];
            if (section == null) {
                continue;
            }
            final LevelChunkSection copy = section.copy();
            out[i] = copy;
            if (section.hasOnlyAir() || !copy.maybeHas(state -> hidden.contains(state.getBlock()))) {
                continue; // nothing hideable in this section's snapshot
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (hidden.contains(copy.getBlockState(x, y, z).getBlock())) {
                            copy.setBlockState(x, y, z, fake, false);
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Null-safe per-server-tick entry point for the movement reveal pass. */
    public static void tickServer() {
        final RaytraceAntiXrayEngine engine = instance;
        if (engine != null) {
            engine.tickReveal();
        }
    }

    private void tickReveal() {
        if ((++this.revealTick % DivineConfig.PerformanceCategory.raytraceRevealIntervalTicks) != 0) {
            return;
        }
        final int radius = DivineConfig.PerformanceCategory.raytraceRevealRadius;
        final Collection<? extends Player> online = Bukkit.getOnlinePlayers();

        // Drop dedup state for players who logged off.
        if (!this.revealed.isEmpty()) {
            final Set<UUID> live = new HashSet<>(online.size());
            for (final Player p : online) {
                live.add(p.getUniqueId());
            }
            this.revealed.keySet().removeIf(uuid -> !live.contains(uuid));
        }

        for (final Player bukkitPlayer : online) {
            if (!(bukkitPlayer.getWorld() instanceof final CraftWorld craftWorld)) {
                continue;
            }
            final ServerLevel level = craftWorld.getHandle();
            final Location eye = bukkitPlayer.getEyeLocation();
            final double eyeX = eye.getX(), eyeY = eye.getY(), eyeZ = eye.getZ();
            final RevealRegion region = RevealRegion.capture(level,
                (int) Math.floor(eyeX), (int) Math.floor(eyeY), (int) Math.floor(eyeZ), radius, this.hiddenBlocks);
            if (region == null || region.orePositions().length == 0) {
                continue;
            }
            final ServerGamePacketListenerImpl connection = ((CraftPlayer) bukkitPlayer).getHandle().connection;
            final LongOpenHashSet dedup = this.revealed.computeIfAbsent(bukkitPlayer.getUniqueId(), k -> new LongOpenHashSet());
            final boolean reHide = DivineConfig.PerformanceCategory.raytraceReHide;
            final BlockState fakeState = reHide ? fakeStateFor(bukkitPlayer.getWorld().getEnvironment()) : null;
            this.workers.execute(() -> revealVisible(region, eyeX, eyeY, eyeZ, connection, dedup, fakeState));
        }
    }

    private static BlockState fakeStateFor(final World.Environment environment) {
        return switch (environment) {
            case NETHER -> Blocks.NETHERRACK.defaultBlockState();
            case THE_END -> Blocks.END_STONE.defaultBlockState();
            default -> Blocks.STONE.defaultBlockState();
        };
    }

    private void revealVisible(final RevealRegion region, final double eyeX, final double eyeY, final double eyeZ,
                               final ServerGamePacketListenerImpl connection, final LongOpenHashSet dedup,
                               final BlockState fakeState) {
        final long[] ores = region.orePositions();
        final BlockState[] states = region.oreStates();

        // Determine which ores are currently visible (line of sight, in radius).
        final LongOpenHashSet nowVisible = new LongOpenHashSet(ores.length);
        for (int k = 0; k < ores.length; k++) {
            final long packed = ores[k];
            if (Raytracer.isVisible(region, eyeX, eyeY, eyeZ,
                BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed))) {
                nowVisible.add(packed);
                this.visibleHits++; // best-effort diagnostic
            } else {
                this.occludedHits++; // best-effort diagnostic
            }
        }

        final it.unimi.dsi.fastutil.ints.IntArrayList revealIdx = new it.unimi.dsi.fastutil.ints.IntArrayList();
        final LongArrayList toHide = new LongArrayList();
        synchronized (dedup) {
            // Newly visible -> reveal (keep the index so the real state is a direct array lookup).
            for (int k = 0; k < ores.length; k++) {
                final long packed = ores[k];
                if (nowVisible.contains(packed) && dedup.add(packed)) {
                    revealIdx.add(k);
                }
            }
            // Previously revealed but no longer visible -> re-hide (opt-in; needs base obfuscation).
            if (fakeState != null) {
                final LongIterator it = dedup.iterator();
                while (it.hasNext()) {
                    final long p = it.nextLong();
                    if (!nowVisible.contains(p)) {
                        toHide.add(p);
                    }
                }
                for (int i = 0; i < toHide.size(); i++) {
                    dedup.remove(toHide.getLong(i));
                }
            }
            if (dedup.size() > REVEAL_DEDUP_CAP) {
                dedup.clear();
            }
        }

        // Network sends outside the lock.
        for (int i = 0; i < revealIdx.size(); i++) {
            final int k = revealIdx.getInt(i);
            connection.send(new ClientboundBlockUpdatePacket(BlockPos.of(ores[k]), states[k])); // reveal real
        }
        if (fakeState != null) {
            for (int i = 0; i < toHide.size(); i++) {
                connection.send(new ClientboundBlockUpdatePacket(BlockPos.of(toHide.getLong(i)), fakeState)); // re-hide
            }
        }
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
