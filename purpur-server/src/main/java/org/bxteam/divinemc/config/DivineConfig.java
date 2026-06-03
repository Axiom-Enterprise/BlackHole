package org.bxteam.divinemc.config;

import com.google.common.base.Throwables;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
