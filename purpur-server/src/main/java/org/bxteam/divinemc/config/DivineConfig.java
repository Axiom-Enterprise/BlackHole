package org.bxteam.divinemc.config;

import com.google.common.base.Throwables;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bxteam.divinemc.async.pathfinding.PathfindTaskRejectPolicy; // Purpur - async pathfinding (DivineMC)
import org.bxteam.divinemc.chunk.ChunkSystemAlgorithm; // Purpur - chunk-system algorithm (DivineMC)
import org.bxteam.divinemc.config.annotations.Experimental;
import org.bxteam.divinemc.region.EnumRegionFileExtension; // Purpur - linear region file format (DivineMC)
import org.bxteam.divinemc.region.Flusher; // Purpur - linear region file format (DivineMC)
import org.bxteam.divinemc.region.buffered.BufferedRegionFileFlusher; // Purpur - linear region file format (DivineMC)
import org.bxteam.divinemc.region.linear.LinearImplementation; // Purpur - linear region file format (DivineMC)
import org.bxteam.divinemc.region.linear.LinearRegionFileFlusher; // Purpur - linear region file format (DivineMC)
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.comments.CommentType;
import org.simpleyaml.configuration.file.YamlFile;
import org.simpleyaml.exceptions.InvalidConfigurationException;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings({"SameParameterValue", "DataFlowIssue"})
public class DivineConfig {
    private static final String HEADER = """
        This is the main configuration file for Axiom.
        Modify these settings to tune async and performance features.""";

    public static final Logger LOGGER = LogManager.getLogger(DivineConfig.class.getSimpleName());
    public static final int CONFIG_VERSION = 8;

    private static File configFile;
    public static final YamlFile config = new YamlFile();

    public static void init(File configFile) {
        try {
            long begin = System.nanoTime();
            LOGGER.info("Loading config...");

            DivineConfig.configFile = configFile;
            if (configFile.exists()) {
                try {
                    config.load(configFile);
                } catch (InvalidConfigurationException e) {
                    throw new IOException(e);
                }
            }

            getInt("version", CONFIG_VERSION);
            config.options().header(HEADER);

            readConfig(DivineConfig.class, null);
            checkExperimentalFeatures();

            LOGGER.info("Config loaded in {}ms", (System.nanoTime() - begin) / 1_000_000);
        } catch (Exception e) {
            LOGGER.error("Failed to load config", e);
        }
    }

    static void readConfig(Class<?> clazz, Object instance) throws IOException {
        for (Method method : clazz.getDeclaredMethods()) {
            if (Modifier.isPrivate(method.getModifiers()) &&
                method.getParameterTypes().length == 0 &&
                method.getReturnType() == Void.TYPE &&
                !method.getName().equals("checkExperimentalFeatures")) {
                try {
                    method.setAccessible(true);
                    method.invoke(instance);
                } catch (InvocationTargetException ex) {
                    throw Throwables.propagate(ex.getCause());
                } catch (Exception ex) {
                    LOGGER.error("Error invoking {}", method, ex);
                }
            }
        }

        for (Class<?> innerClass : clazz.getDeclaredClasses()) {
            if (Modifier.isStatic(innerClass.getModifiers())) {
                try {
                    Object innerInstance = null;

                    Method loadMethod = null;
                    try {
                        loadMethod = innerClass.getDeclaredMethod("load");
                    } catch (NoSuchMethodException ignored) {
                        readConfig(innerClass, null);
                        continue;
                    }

                    if (loadMethod != null) {
                        try {
                            innerInstance = innerClass.getDeclaredConstructor().newInstance();
                        } catch (NoSuchMethodException e) {
                            innerInstance = null;
                        }

                        loadMethod.setAccessible(true);
                        loadMethod.invoke(innerInstance);
                    }
                } catch (Exception ex) {
                    LOGGER.error("Error processing inner class {}", innerClass.getName(), ex);
                }
            }
        }

        config.save(configFile);
    }

    private static void setComment(String key, String... comment) {
        if (config.contains(key)) {
            config.setComment(key, String.join("\n", comment), CommentType.BLOCK);
        }
    }

    private static void ensureDefault(String key, Object defaultValue, String... comment) {
        if (!config.contains(key)) config.set(key, defaultValue);
        if (comment.length > 0) config.setComment(key, String.join("\n", comment), CommentType.BLOCK);
    }

    private static boolean getBoolean(String key, boolean defaultValue, String... comment) {
        return getBoolean(key, null, defaultValue, comment);
    }

    private static boolean getBoolean(String key, @Nullable String oldKey, boolean defaultValue, String... comment) {
        ensureDefault(key, defaultValue, comment);
        return config.getBoolean(key, defaultValue);
    }

    private static int getInt(String key, int defaultValue, String... comment) {
        return getInt(key, null, defaultValue, comment);
    }

    private static int getInt(String key, @Nullable String oldKey, int defaultValue, String... comment) {
        ensureDefault(key, defaultValue, comment);
        return config.getInt(key, defaultValue);
    }

    private static double getDouble(String key, double defaultValue, String... comment) {
        return getDouble(key, null, defaultValue, comment);
    }

    private static double getDouble(String key, @Nullable String oldKey, double defaultValue, String... comment) {
        ensureDefault(key, defaultValue, comment);
        return config.getDouble(key, defaultValue);
    }

    private static long getLong(String key, long defaultValue, String... comment) {
        return getLong(key, null, defaultValue, comment);
    }

    private static long getLong(String key, @Nullable String oldKey, long defaultValue, String... comment) {
        ensureDefault(key, defaultValue, comment);
        return config.getLong(key, defaultValue);
    }

    private static String getString(String key, String defaultValue, String... comment) {
        return getOldString(key, null, defaultValue, comment);
    }

    private static String getOldString(String key, @Nullable String oldKey, String defaultValue, String... comment) {
        ensureDefault(key, defaultValue, comment);
        return config.getString(key, defaultValue);
    }

    private static List<String> getStringList(String key, List<String> defaultValue, String... comment) {
        return getStringList(key, null, defaultValue, comment);
    }

    private static List<String> getStringList(String key, @Nullable String oldKey, List<String> defaultValue, String... comment) {
        ensureDefault(key, defaultValue, comment);
        return config.getStringList(key);
    }

    public static boolean asyncLocator = false;
    private static void asyncLocate() {
        asyncLocator = getBoolean(ConfigCategory.ASYNC.key("async-locator.enabled"), asyncLocator,
            "Whether asynchronous locator should be enabled.",
            "Offloads /locate structure, biome and POI searches to Paper's async task pool,",
            "so a slow locate no longer blocks the main thread. Result is sent when ready.");
    }

    // Purpur start - async chunk sending (DivineMC)
    public static class AsyncCategory {
        // Purpur start - parallel world ticking (DivineMC)
        // Parallel world ticking settings
        @Experimental("Parallel World Ticking")
        public static boolean enableParallelWorldTicking = false; // Purpur - default off
        public static int parallelThreadCount = 4;
        public static boolean logContainerCreationStacktraces = false;
        public static boolean disableHardThrow = false;
        // Purpur end - parallel world ticking (DivineMC)

        // Purpur start - regionized chunk ticking (DivineMC)
        // Regionized chunk ticking
        @Experimental("Regionized Chunk Ticking")
        public static boolean enableRegionizedChunkTicking = false; // Purpur - default off
        public static int regionizedChunkTickingExecutorThreadCount = 4;
        public static int regionizedChunkTickingExecutorThreadPriority = Thread.NORM_PRIORITY + 2;
        // Purpur end - regionized chunk ticking (DivineMC)

        // Async chunk sending settings
        public static boolean asyncChunkSendingEnabled = false; // Purpur - default off
        public static int asyncChunkSendingMaxThreads = 1;

        // Async pathfinding settings // Purpur - async pathfinding (DivineMC)
        public static boolean asyncPathfinding = false; // Purpur - default off
        public static int asyncPathfindingMaxThreads = 1; // Purpur - async pathfinding (DivineMC)
        public static int asyncPathfindingKeepalive = 60; // Purpur - async pathfinding (DivineMC)
        public static int asyncPathfindingQueueSize = 0; // Purpur - async pathfinding (DivineMC)
        public static PathfindTaskRejectPolicy asyncPathfindingRejectPolicy = PathfindTaskRejectPolicy.CALLER_RUNS; // Purpur - async pathfinding (DivineMC)

        // Purpur start - async entity tracker (DivineMC)
        // Multithreaded tracker settings
        public static boolean multithreadedEnabled = false; // Purpur - default off
        public static boolean multithreadedCompatModeEnabled = false;
        public static int asyncEntityTrackerMaxThreads = 1;
        public static int asyncEntityTrackerKeepalive = 60;
        public static int asyncEntityTrackerQueueSize = 0;
        // Purpur end - async entity tracker (DivineMC)

        // Purpur start - async mob spawning (DivineMC)
        public static boolean enableAsyncSpawning = false; // Purpur - default off
        public static boolean asyncNaturalSpawn = true;
        // Purpur end - async mob spawning (DivineMC)

        public static boolean asyncPlayerDataSave = false; // Purpur - async playerdata save (Leaf) - default off

        // Purpur start - player-save (async auto-save write)
        public static boolean playerSaveEnabled = false; // Purpur - default off
        public static int playerSaveWorkerThreads = 2;
        public static int playerSaveQueueCapacity = 1024;
        public static boolean playerSaveCoalesce = true;
        // Purpur end - player-save (async auto-save write)

        // Purpur start - async-enhancements (player NBT compression offload)
        public static boolean asyncPlayerNbtCompression = false; // Purpur - default off
        public static int ioPoolThreads = 3;
        // Purpur end - async-enhancements (player NBT compression offload)

        // Purpur start - async target finding (Leaf)
        public static boolean asyncTargetFinding = false; // Purpur - default off
        public static boolean asyncTargetFindingAlertOther = true;
        public static boolean asyncTargetFindingSearchBlock = true;
        public static boolean asyncTargetFindingSearchEntity = true;
        public static int asyncTargetFindingQueueSize = 4096;
        // Purpur end - async target finding (Leaf)

        public void load() {
            parallelWorldTicking(); // Purpur - parallel world ticking (DivineMC)
            regionizedChunkTicking(); // Purpur - regionized chunk ticking (DivineMC)
            asyncChunkSending();
            asyncPathfinding(); // Purpur - async pathfinding (DivineMC)
            multithreadedTracker(); // Purpur - async entity tracker (DivineMC)
            asyncMobSpawning(); // Purpur - async mob spawning (DivineMC)
            asyncPlayerDataSave(); // Purpur - async playerdata save (Leaf)
            playerSave(); // Purpur - player-save (async auto-save write)
            asyncEnhancements(); // Purpur - async-enhancements (player NBT compression offload)
            asyncTargetFinding(); // Purpur - async target finding (Leaf)
        }

        // Purpur start - async target finding (Leaf)
        private static void asyncTargetFinding() {
            asyncTargetFinding = getBoolean(ConfigCategory.ASYNC.key("async-target-finding.enable"), asyncTargetFinding,
                "This moves the expensive entity and block search calculations to background thread while",
                "keeping the actual validation on the main thread.");
            // Disable if parallel world ticking is enabled, as they are incompatible.
            if (asyncTargetFinding && AsyncCategory.enableParallelWorldTicking) {
                LOGGER.warn("Async target finding is incompatible with Parallel World Ticking. Disabling Async target finding automatically.");
                asyncTargetFinding = false;
            }
            asyncTargetFindingAlertOther = getBoolean(ConfigCategory.ASYNC.key("async-target-finding.alert-other"), asyncTargetFindingAlertOther,
                "Whether to offload the 'alert nearby mobs of the same type' search to the background thread.");
            asyncTargetFindingSearchBlock = getBoolean(ConfigCategory.ASYNC.key("async-target-finding.search-block"), asyncTargetFindingSearchBlock,
                "Whether to offload block target searches (e.g. MoveToBlockGoal) to the background thread.");
            asyncTargetFindingSearchEntity = getBoolean(ConfigCategory.ASYNC.key("async-target-finding.search-entity"), asyncTargetFindingSearchEntity,
                "Whether to offload entity target searches to the background thread.");
            asyncTargetFindingQueueSize = getInt(ConfigCategory.ASYNC.key("async-target-finding.queue-size"), asyncTargetFindingQueueSize,
                "The size of the per-world ring buffer used to hand mobs to the background goal thread.");

            if (asyncTargetFindingQueueSize <= 0) {
                asyncTargetFindingQueueSize = 4096;
            }
            if (!asyncTargetFinding) {
                asyncTargetFindingAlertOther = false;
                asyncTargetFindingSearchEntity = false;
                asyncTargetFindingSearchBlock = false;
            } else {
                LOGGER.info("Async target finding is enabled");
            }
        }
        // Purpur end - async target finding (Leaf)

        // Purpur start - parallel world ticking (DivineMC)
        private static void parallelWorldTicking() {
            enableParallelWorldTicking = getBoolean(ConfigCategory.ASYNC.key("parallel-world-ticking.enable"), enableParallelWorldTicking,
                "Enables Parallel World Ticking, which executes each world's tick in a separate thread while ensuring that all worlds complete their tick before the next cycle begins.",
                "",
                "Read more info about this feature at https://bxteam.org/docs/divinemc/features/parallel-world-ticking");
            parallelThreadCount = getInt(ConfigCategory.ASYNC.key("parallel-world-ticking.thread-count"), parallelThreadCount);
            logContainerCreationStacktraces = getBoolean(ConfigCategory.ASYNC.key("parallel-world-ticking.log-container-creation-stacktraces"), logContainerCreationStacktraces);
            disableHardThrow = getBoolean(ConfigCategory.ASYNC.key("parallel-world-ticking.disable-hard-throw"), disableHardThrow,
                "Disables annoying 'not on main thread' throws. But, THIS IS NOT RECOMMENDED because you SHOULD FIX THE ISSUES THEMSELVES instead of RISKING DATA CORRUPTION! If you lose something, take the blame on yourself.");
        }
        // Purpur end - parallel world ticking (DivineMC)

        // Purpur start - regionized chunk ticking (DivineMC)
        private static void regionizedChunkTicking() {
            enableRegionizedChunkTicking = getBoolean(ConfigCategory.ASYNC.key("regionized-chunk-ticking.enable"), enableRegionizedChunkTicking,
                "Enables regionized chunk ticking, similar to like Folia works.",
                "",
                "Read more info about this feature at https://bxteam.org/docs/divinemc/features/regionized-chunk-ticking");

            regionizedChunkTickingExecutorThreadCount = getInt(ConfigCategory.ASYNC.key("regionized-chunk-ticking.executor-thread-count"), regionizedChunkTickingExecutorThreadCount,
                "The amount of threads to allocate to regionized chunk ticking.");
            regionizedChunkTickingExecutorThreadPriority = getInt(ConfigCategory.ASYNC.key("regionized-chunk-ticking.executor-thread-priority"), regionizedChunkTickingExecutorThreadPriority,
                "Configures the thread priority of the executor");

            if (regionizedChunkTickingExecutorThreadCount < 1 || regionizedChunkTickingExecutorThreadCount > 10) {
                LOGGER.warn("Invalid regionized chunk ticking thread count: {}, resetting to default (4)", regionizedChunkTickingExecutorThreadCount);
                regionizedChunkTickingExecutorThreadCount = 4;
            }
        }
        // Purpur end - regionized chunk ticking (DivineMC)

        // Purpur start - async playerdata save (Leaf)
        private static void asyncPlayerDataSave() {
            asyncPlayerDataSave = getBoolean(ConfigCategory.ASYNC.key("async-playerdata-save.enable"), asyncPlayerDataSave,
                "Make PlayerData saving asynchronously.",
                "Only the periodic auto-save is offloaded to a background I/O thread;",
                "player quit and server shutdown saves always stay synchronous so data is",
                "flushed before the connection drops or the process exits.");

            if (asyncPlayerDataSave) {
                org.dreeam.leaf.async.AsyncPlayerDataSaving.init();
            }
        }
        // Purpur end - async playerdata save (Leaf)

        // Purpur start - player-save (async auto-save write)
        private static void playerSave() {
            playerSaveEnabled = getBoolean(ConfigCategory.ASYNC.key("player-save.enable"), playerSaveEnabled,
                "Offloads only the disk-write step of the periodic player auto-save to a background",
                "I/O pool, keeping NBT serialization on the main thread. Player quit and server",
                "shutdown saves always stay fully synchronous so data is flushed before the connection",
                "drops or the process exits. The write is atomic (temp file + atomic rename), and on",
                "queue overflow the write falls back to the calling thread so no data is dropped.",
                "Mutually exclusive with async-playerdata-save: if both are enabled, async-playerdata-save",
                "wins and player-save is disabled (a warning is logged).");
            playerSaveWorkerThreads = getInt(ConfigCategory.ASYNC.key("player-save.worker-threads"), playerSaveWorkerThreads,
                "Number of background threads used to write player data to disk.");
            playerSaveQueueCapacity = getInt(ConfigCategory.ASYNC.key("player-save.queue-capacity"), playerSaveQueueCapacity,
                "Maximum number of queued writes before overflow falls back to a synchronous",
                "write on the main thread.");
            playerSaveCoalesce = getBoolean(ConfigCategory.ASYNC.key("player-save.coalesce"), playerSaveCoalesce,
                "When enabled, a newer queued snapshot for the same player supersedes an older one",
                "still waiting in the queue, reducing redundant disk writes. In-flight and sole writes",
                "are never dropped.");

            if (playerSaveEnabled) {
                if (asyncPlayerDataSave) {
                    LOGGER.warn("Both async.player-save and async.async-playerdata-save are enabled; they target the same player .dat path and are mutually exclusive. async-playerdata-save takes precedence, disabling player-save.");
                    playerSaveEnabled = false;
                } else {
                    org.bxteam.divinemc.async.PlayerSaveExecutor.init();
                }
            }
        }
        // Purpur end - player-save (async auto-save write)

        // Purpur start - async-enhancements (player NBT compression offload)
        private static void asyncEnhancements() {
            asyncPlayerNbtCompression = getBoolean(ConfigCategory.ASYNC.key("async-enhancements.async-player-nbt-compression"), asyncPlayerNbtCompression,
                "Offloads the gzip-compression and disk-write of the periodic player auto-save to a",
                "shared background I/O pool, keeping NBT serialization on the main thread. This is the",
                "lightest of the three player-save offload tiers: it only moves the gzip + write off the",
                "main thread. Player quit and server shutdown saves always stay fully synchronous so data",
                "is flushed before the connection drops or the process exits. The write is atomic (temp",
                "file + atomic rename) and on queue overflow falls back to a synchronous write on the",
                "calling thread so no data is dropped.",
                "Lowest precedence: this tier is active only when both async-playerdata-save and player-save",
                "are disabled. It substantially overlaps those heavier tiers, so leave it OFF if either is",
                "in use. There is a small crash-window trade-off (a save queued to the pool but not yet",
                "flushed when the process is killed is lost), identical to the other async write tiers.");
            ioPoolThreads = getInt(ConfigCategory.ASYNC.key("async-enhancements.io-pool-threads"), ioPoolThreads,
                "Number of background threads in the shared I/O pool used to gzip and write player data.",
                "Clamped to the range 1..4.");
            ioPoolThreads = Math.max(1, Math.min(4, ioPoolThreads));

            if (asyncPlayerNbtCompression) {
                if (asyncPlayerDataSave || playerSaveEnabled) {
                    LOGGER.warn("async.async-enhancements.async-player-nbt-compression is enabled together with a heavier player-save tier ({}); they target the same player .dat path. The heavier tier takes precedence and this feature stays inactive.",
                        asyncPlayerDataSave ? "async-playerdata-save" : "player-save");
                } else {
                    org.bxteam.divinemc.async.AsyncIO.init();
                }
            }
        }
        // Purpur end - async-enhancements (player NBT compression offload)

        // Purpur start - async entity tracker (DivineMC)
        private static void multithreadedTracker() {
            multithreadedEnabled = getBoolean(ConfigCategory.ASYNC.key("multithreaded-tracker.enable"), multithreadedEnabled,
                "Make entity tracking saving asynchronously, can improve performance significantly,",
                "especially in some massive entities in small area situations.");
            multithreadedCompatModeEnabled = getBoolean(ConfigCategory.ASYNC.key("multithreaded-tracker.compat-mode"), multithreadedCompatModeEnabled,
                "Enable compat mode ONLY if Citizens or NPC plugins using real entity has installed.",
                "Compat mode fixes visible issues with player type NPCs of Citizens.",
                "But we recommend to use packet based / virtual entity NPC plugin, e.g. ZNPC Plus, Adyeshach, Fancy NPC and etc.");

            asyncEntityTrackerMaxThreads = getInt(ConfigCategory.ASYNC.key("multithreaded-tracker.max-threads"), asyncEntityTrackerMaxThreads);
            asyncEntityTrackerKeepalive = getInt(ConfigCategory.ASYNC.key("multithreaded-tracker.keepalive"), asyncEntityTrackerKeepalive);
            asyncEntityTrackerQueueSize = getInt(ConfigCategory.ASYNC.key("multithreaded-tracker.queue-size"), asyncEntityTrackerQueueSize);

            if (asyncEntityTrackerMaxThreads < 0) {
                asyncEntityTrackerMaxThreads = Math.max(Runtime.getRuntime().availableProcessors() + asyncEntityTrackerMaxThreads, 1);
            } else if (asyncEntityTrackerMaxThreads == 0) {
                asyncEntityTrackerMaxThreads = Math.max(Runtime.getRuntime().availableProcessors() / 4, 1);
            }

            if (!multithreadedEnabled) {
                asyncEntityTrackerMaxThreads = 0;
            } else {
                LOGGER.info("Using {} threads for Async Entity Tracker", asyncEntityTrackerMaxThreads);
            }

            if (asyncEntityTrackerQueueSize <= 0) asyncEntityTrackerQueueSize = asyncEntityTrackerMaxThreads * 384;
        }
        // Purpur end - async entity tracker (DivineMC)

        // Purpur start - async pathfinding (DivineMC)
        private static void asyncPathfinding() {
            asyncPathfinding = getBoolean(ConfigCategory.ASYNC.key("pathfinding.enable"), asyncPathfinding);
            asyncPathfindingMaxThreads = getInt(ConfigCategory.ASYNC.key("pathfinding.max-threads"), asyncPathfindingMaxThreads);
            asyncPathfindingKeepalive = getInt(ConfigCategory.ASYNC.key("pathfinding.keepalive"), asyncPathfindingKeepalive);
            asyncPathfindingQueueSize = getInt(ConfigCategory.ASYNC.key("pathfinding.queue-size"), asyncPathfindingQueueSize);

            final int maxThreads = Runtime.getRuntime().availableProcessors();
            if (asyncPathfindingMaxThreads < 0) {
                asyncPathfindingMaxThreads = Math.max(maxThreads + asyncPathfindingMaxThreads, 1);
            } else if (asyncPathfindingMaxThreads == 0) {
                asyncPathfindingMaxThreads = Math.max(maxThreads / 4, 1);
            }

            if (!asyncPathfinding) {
                asyncPathfindingMaxThreads = 0;
            } else {
                LOGGER.info("Using {} threads for Async Pathfinding", asyncPathfindingMaxThreads);
            }

            if (asyncPathfindingQueueSize <= 0) asyncPathfindingQueueSize = asyncPathfindingMaxThreads * 256;

            try {
                asyncPathfindingRejectPolicy = PathfindTaskRejectPolicy.valueOf(getString(ConfigCategory.ASYNC.key("pathfinding.reject-policy"),
                    maxThreads >= 12 && asyncPathfindingQueueSize < 512
                        ? PathfindTaskRejectPolicy.FLUSH_ALL.toString()
                        : PathfindTaskRejectPolicy.CALLER_RUNS.toString(),
                    "The policy to use when the queue is full and a new task is submitted.",
                    "FLUSH_ALL: All pending tasks will be run on server thread.",
                    "CALLER_RUNS: Newly submitted task will be run on server thread."));
            } catch (IllegalArgumentException ignore) {
                LOGGER.warn("Invalid async pathfinding reject policy, using default CALLER_RUNS");
                asyncPathfindingRejectPolicy = PathfindTaskRejectPolicy.CALLER_RUNS;
            }
        }
        // Purpur end - async pathfinding (DivineMC)

        private static void asyncChunkSending() {
            asyncChunkSendingEnabled = getBoolean(ConfigCategory.ASYNC.key("chunk-sending.enable"), asyncChunkSendingEnabled,
                "Makes chunk sending asynchronous, which can significantly reduce main thread load when many players are loading chunks.");
            asyncChunkSendingMaxThreads = getInt(ConfigCategory.ASYNC.key("chunk-sending.max-threads"), asyncChunkSendingMaxThreads);

            if (asyncChunkSendingMaxThreads < 0) {
                asyncChunkSendingMaxThreads = Math.max(Runtime.getRuntime().availableProcessors() + asyncChunkSendingMaxThreads, 1);
            } else if (asyncChunkSendingMaxThreads == 0) {
                asyncChunkSendingMaxThreads = Math.max(Runtime.getRuntime().availableProcessors() / 4, 1);
            }
        }

        // Purpur start - async mob spawning (DivineMC)
        private static void asyncMobSpawning() {
            enableAsyncSpawning = getBoolean(ConfigCategory.ASYNC.key("mob-spawning.enable"), enableAsyncSpawning,
                "Enables optimization that will offload much of the computational effort involved with spawning new mobs to a different thread.");
            asyncNaturalSpawn = getBoolean(ConfigCategory.ASYNC.key("mob-spawning.async-natural-spawn"), asyncNaturalSpawn,
                "Enables offloading of natural spawning to a different thread");
        }
        // Purpur end - async mob spawning (DivineMC)
    }
    // Purpur end - async chunk sending (DivineMC)

    // Purpur start - chunk-system algorithm (DivineMC)
    public static class PerformanceCategory {
        public static ChunkSystemAlgorithm chunkWorkerAlgorithm = ChunkSystemAlgorithm.MOONRISE;
        public static boolean fastRandom = false; // DivineMC - fast xoroshiro RandomSource for runtime hot paths - default off

        public static boolean antiXraySdk = false; // DivineMC - Raytrace AntiXray SDK hooks - default off
        @Experimental("Raytrace AntiXray Engine")
        public static boolean raytraceEngineEnabled = false; // DivineMC - built-in raytrace anti-xray engine - default off
        public static int raytraceEngineThreads = 2;
        public static int raytraceEngineRadius = 64;
        public static int raytraceRevealIntervalTicks = 20;
        public static int raytraceRevealRadius = 16;
        public static java.util.Set<org.bukkit.Material> raytraceHiddenBlocks = java.util.EnumSet.noneOf(org.bukkit.Material.class);

        private static final List<String> DEFAULT_HIDDEN_BLOCKS = java.util.List.of(
            "COAL_ORE", "DEEPSLATE_COAL_ORE", "IRON_ORE", "DEEPSLATE_IRON_ORE", "COPPER_ORE", "DEEPSLATE_COPPER_ORE",
            "GOLD_ORE", "DEEPSLATE_GOLD_ORE", "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE", "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE",
            "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE",
            "NETHER_GOLD_ORE", "NETHER_QUARTZ_ORE", "ANCIENT_DEBRIS");

        public static void load() {
            chunkSettings();
            fastRandomSettings();
            antiXraySettings();
            antiXrayEngineSettings();
            org.bxteam.divinemc.antixray.RaytraceAntiXrayEngine.bootstrap();
        }

        private static void fastRandomSettings() {
            fastRandom = getBoolean(ConfigCategory.PERFORMANCE.key("fast-random"), fastRandom,
                "Use a fast xoroshiro128++ RandomSource for runtime, non-worldgen random",
                "(mob AI, particles, item drop physics). Does not affect worldgen determinism.");
        }

        private static void antiXraySettings() {
            antiXraySdk = getBoolean(ConfigCategory.PERFORMANCE.key("raytrace-antixray.sdk-hooks"), antiXraySdk,
                "Fire Raytrace AntiXray SDK callbacks from the server (block changes + player",
                "left-click). Needed by the Raytrace AntiXray plugin or the built-in engine for",
                "efficient, fork-level anti-xray. No effect unless an AntiXrayAdapter is registered.");
        }

        private static void antiXrayEngineSettings() {
            raytraceEngineEnabled = getBoolean(ConfigCategory.PERFORMANCE.key("raytrace-antixray.engine.enabled"), raytraceEngineEnabled,
                "Built-in multithreaded raytrace anti-xray engine (registers as the SDK adapter).",
                "EXPERIMENTAL foundation: computes per-player block visibility off-thread; packet",
                "obfuscation is not yet wired, so this does not hide ores on its own yet.");
            raytraceEngineThreads = getInt(ConfigCategory.PERFORMANCE.key("raytrace-antixray.engine.threads"), raytraceEngineThreads,
                "Worker threads for the raytrace anti-xray engine.");
            raytraceEngineRadius = getInt(ConfigCategory.PERFORMANCE.key("raytrace-antixray.engine.update-radius"), raytraceEngineRadius,
                "Max block distance from a viewer eye at which the engine evaluates visibility.");
            raytraceRevealIntervalTicks = Math.max(1, getInt(ConfigCategory.PERFORMANCE.key("raytrace-antixray.engine.reveal-interval-ticks"), raytraceRevealIntervalTicks,
                "How often (server ticks) to run the movement reveal pass per online player."));
            raytraceRevealRadius = getInt(ConfigCategory.PERFORMANCE.key("raytrace-antixray.engine.reveal-radius"), raytraceRevealRadius,
                "Block radius around each viewer scanned for ores to reveal on a clear line of sight.");

            final List<String> names = getStringList(ConfigCategory.PERFORMANCE.key("raytrace-antixray.engine.hidden-blocks"), DEFAULT_HIDDEN_BLOCKS,
                "Blocks the engine treats as hideable; only changes to these trigger a raytrace reveal.");
            final java.util.Set<org.bukkit.Material> hidden = java.util.EnumSet.noneOf(org.bukkit.Material.class);
            for (final String name : names) {
                final org.bukkit.Material material = org.bukkit.Material.matchMaterial(name);
                if (material != null) {
                    hidden.add(material);
                } else {
                    LOGGER.warn("Unknown material '{}' in raytrace-antixray.engine.hidden-blocks", name);
                }
            }
            raytraceHiddenBlocks = hidden;
        }

        private static void chunkSettings() {
            chunkWorkerAlgorithm = ChunkSystemAlgorithm.valueOf(getString(ConfigCategory.PERFORMANCE.key("chunks.chunk-worker-algorithm"), chunkWorkerAlgorithm.name(),
                "Algorithm used to determine the number of worker threads for chunk loading and generation.",
                "",
                "Available algorithms:",
                " - MOONRISE: Paper's default algorithm. Conservative approach, uses fewer threads (CPU cores / 2).",
                " - C2ME: More aggressive thread allocation than MOONRISE. Considers both CPU cores and available memory. May use more threads on high-end systems.",
                " - C2ME_NEW: Modern C2ME algorithm. Balanced approach between MOONRISE and C2ME. Optimized for current hardware, slightly less aggressive than old C2ME."));
        }
    }
    // Purpur end - chunk-system algorithm (DivineMC)

    // Purpur start - linear region file format (DivineMC)
    public static class RegionSettingsCategory {
        // Region Format
        public static EnumRegionFileExtension regionFileType = EnumRegionFileExtension.MCA;
        public static int compressionLevel = 4;
        public static int threadCount = 4;
        public static Flusher<?> flusher = null;

        // Linear region file settings
        public static int linearIoFlushDelayMs = 10000;
        public static LinearImplementation linearImplementation = LinearImplementation.V2;

        // Buffered linear region file settings
        public static int checkIntervalMs = 20;
        public static int flushOfWriteTimeoutMs = 3000;

        public static void load() {
            regionFileExtension();
            linear();
            buffered();
            flusher();
        }

        private static void regionFileExtension() {
            try {
                regionFileType = EnumRegionFileExtension.fromString(getString(ConfigCategory.REGION.key("type"), regionFileType.toString(),
                    "The type of region file format to use for storing chunk data.",
                    "Valid values:",
                    " - MCA: Default Minecraft region file format",
                    " - LINEAR: Linear region file format V2",
                    " - B_LINEAR: Buffered region file format (just uses Zstd)"));
            } catch (IllegalArgumentException ignore) {
                LOGGER.warn("Invalid region file type: {}, resetting to default (MCA)", getString(ConfigCategory.REGION.key("type"), regionFileType.toString()));
                regionFileType = EnumRegionFileExtension.MCA;
            }

            threadCount = getInt(ConfigCategory.REGION.key("thread-count"), threadCount,
                "The number of threads to use for IO operations.");

            if (threadCount < 1) {
                LOGGER.warn("Invalid thread count: {}, resetting to default (4)", threadCount);
                threadCount = 4;
            }

            compressionLevel = getInt(ConfigCategory.REGION.key("compression-level"), compressionLevel,
                "The compression level to use for the either linear or buffered linear region file format.");

            if (compressionLevel > 23 || compressionLevel < 1) {
                LOGGER.warn("Invalid compression level: {}, resetting to default (4)", compressionLevel);
                compressionLevel = 4;
            }
        }

        private static void linear() {
            linearIoFlushDelayMs = getInt(ConfigCategory.REGION.key("linear.io-flush-delay-ms"), linearIoFlushDelayMs,
                "The delay in milliseconds to wait before flushing IO operations.");

            linearImplementation = LinearImplementation.valueOf(getString(ConfigCategory.REGION.key("linear.implementation"), linearImplementation.name(),
                "The implementation of the linear region file format to use.",
                "Valid values:",
                " - V1: Basic and default linear implementation",
                " - V2: Introduces a grid-based compression scheme for better data management and flexibility (default)",
                " - V3: Minor improvements over V2"));
        }

        private static void buffered() {
            checkIntervalMs = getInt(ConfigCategory.REGION.key("b-linear.check-interval-ms"), checkIntervalMs,
                "The interval in milliseconds to check for dirty region files to flush.");
            flushOfWriteTimeoutMs = getInt(ConfigCategory.REGION.key("b-linear.flush-of-write-timeout-ms"), flushOfWriteTimeoutMs,
                "The timeout in milliseconds to wait before forcing a flush of a region file that is being written to.");
        }

        private static void flusher() {
            flusher = switch (regionFileType) {
                case MCA -> null;
                case LINEAR -> new LinearRegionFileFlusher(threadCount, linearIoFlushDelayMs);
                case B_LINEAR -> new BufferedRegionFileFlusher(threadCount, checkIntervalMs, flushOfWriteTimeoutMs);
            };
        }
    }
    // Purpur end - linear region file format (DivineMC)

    private static void checkExperimentalFeatures() {
        List<String> enabledExperimentalFeatures = new ArrayList<>();

        Class<?>[] innerClasses = DivineConfig.class.getDeclaredClasses();
        for (Class<?> innerClass : innerClasses) {
            if (Modifier.isStatic(innerClass.getModifiers())) {
                Field[] fields = innerClass.getDeclaredFields();
                for (Field field : fields) {
                    if (field.isAnnotationPresent(Experimental.class) &&
                        field.getType() == boolean.class &&
                        Modifier.isStatic(field.getModifiers()) &&
                        Modifier.isPublic(field.getModifiers())) {
                        try {
                            field.setAccessible(true);
                            boolean value = field.getBoolean(null);
                            if (value) {
                                Experimental annotation = field.getAnnotation(Experimental.class);
                                String featureName = annotation.value();
                                enabledExperimentalFeatures.add(featureName);
                            }
                        } catch (IllegalAccessException e) {
                            LOGGER.debug("Failed to access field {}", field.getName(), e);
                        }
                    }
                }
            }
        }

        if (!enabledExperimentalFeatures.isEmpty()) {
            LOGGER.warn("You have the following experimental features enabled: [{}]. Please proceed with caution!", String.join(", ", enabledExperimentalFeatures));
        }
    }
}
