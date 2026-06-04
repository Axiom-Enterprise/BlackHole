package org.bxteam.divinemc.config;

import com.google.common.base.Throwables;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bxteam.divinemc.async.pathfinding.PathfindTaskRejectPolicy; // Purpur - async pathfinding (DivineMC)
import org.bxteam.divinemc.config.annotations.Experimental;
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
        This is the main configuration file for DivineMC.
        If you need help with the configuration or have any questions related to DivineMC,
        join us in our Discord server.

        Discord: https://discord.gg/qNyybSSPm5
        Docs: https://bxteam.org/docs/divinemc
        Downloads: https://bxteam.org/downloads/divinemc""";

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

        public void load() {
            asyncChunkSending();
            asyncPathfinding(); // Purpur - async pathfinding (DivineMC)
            multithreadedTracker(); // Purpur - async entity tracker (DivineMC)
            asyncMobSpawning(); // Purpur - async mob spawning (DivineMC)
            asyncPlayerDataSave(); // Purpur - async playerdata save (Leaf)
            playerSave(); // Purpur - player-save (async auto-save write)
        }

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
