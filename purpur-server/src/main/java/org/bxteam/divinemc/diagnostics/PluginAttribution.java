package org.bxteam.divinemc.diagnostics;

import io.papermc.paper.plugin.configuration.PluginMeta;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bxteam.divinemc.diagnostics.DiagnosticsReport.HeapClass;
import org.bxteam.divinemc.diagnostics.DiagnosticsReport.LeakSuspect;
import org.bxteam.divinemc.diagnostics.DiagnosticsReport.PackageInfo;
import org.bxteam.divinemc.diagnostics.DiagnosticsReport.PluginInfo;

/**
 * Attributes the heap to individual Bukkit plugins and synthesizes leak signals.
 *
 * <p>The bridge from a plugin to its share of the heap is its main class name:
 * the {@link HprofParser} knows which classloader defined that class, and every
 * class defined by the same loader belongs to the plugin. This works for both the
 * legacy {@code PluginClassLoader} and Paper's modern plugin classloader without
 * any version-specific reflection. When no heap dump is available we fall back to
 * reflecting the legacy {@code classes} map and intersect with the class histogram.
 *
 * <p>Non-heap leak signals — threads still bound to the plugin classloader,
 * scheduled tasks, registered listeners, and orphaned (reloaded) classloaders —
 * are gathered live from the running server.
 */
public final class PluginAttribution {
    private static final int TOP_DRILL = 15;
    private static final int MAX_PACKAGES = 40;
    private static final int MAX_SUSPECTS = 40;
    private static final int THREAD_LEAK_THRESHOLD = 12;
    private static final int TASK_LEAK_THRESHOLD = 40;

    private PluginAttribution() {
    }

    /**
     * @param heap      parsed heap dump, or null when the dump failed / was skipped
     * @param histStart class histogram at window start (debug only; null in metrics)
     * @param histEnd   class histogram at window end / the single metrics histogram
     */
    public static void fill(DiagnosticsReport report, HprofParser.Result heap,
                            Map<String, long[]> histStart, Map<String, long[]> histEnd) {
        final boolean debug = histStart != null;

        // Group heap classes by their defining classloader, and remember a class -> loader map.
        final Map<Long, List<HprofParser.ClassRec>> byLoader = new HashMap<>();
        final Map<String, Long> loaderOfClassName = new HashMap<>();
        if (heap != null) {
            for (HprofParser.ClassRec rec : heap.classById.values()) {
                byLoader.computeIfAbsent(rec.loaderId, k -> new ArrayList<>()).add(rec);
                loaderOfClassName.putIfAbsent(rec.name, rec.loaderId);
            }
        }

        final Map<Long, String> loaderToPlugin = new HashMap<>();
        final Set<Long> pluginLoaderIds = new HashSet<>();

        Plugin[] plugins;
        try {
            plugins = Bukkit.getPluginManager().getPlugins();
        } catch (Throwable t) {
            plugins = new Plugin[0];
        }

        for (Plugin plugin : plugins) {
            final PluginInfo pi = new PluginInfo();
            pi.name = safe(plugin::getName, "?");
            pi.enabled = safeBool(plugin::isEnabled);

            String mainClass = pi.name;
            try {
                final PluginMeta meta = plugin.getPluginMeta();
                pi.version = meta.getVersion();
                pi.authors = meta.getAuthors() == null ? new ArrayList<>() : new ArrayList<>(meta.getAuthors());
                mainClass = meta.getMainClass() != null ? meta.getMainClass() : plugin.getClass().getName();
            } catch (Throwable t) {
                mainClass = plugin.getClass().getName();
            }
            pi.main = mainClass;
            final ClassLoader pluginLoader = plugin.getClass().getClassLoader();
            pi.loaderType = pluginLoader == null ? "?" : pluginLoader.getClass().getSimpleName();

            // Heap bridge: main class -> defining loader(s).
            long primaryLoaderId = 0;
            int loaderCount = 0;
            if (heap != null) {
                final List<Long> classIds = heap.classIdsByName.get(mainClass);
                if (classIds != null && !classIds.isEmpty()) {
                    final Set<Long> loaderIds = new HashSet<>();
                    for (long cid : classIds) {
                        final HprofParser.ClassRec rec = heap.classById.get(cid);
                        if (rec != null) {
                            loaderIds.add(rec.loaderId);
                        }
                    }
                    loaderCount = loaderIds.size();
                    // Primary = the loader that defined the most classes (the live one).
                    long best = 0;
                    int bestSize = -1;
                    for (long lid : loaderIds) {
                        final int size = byLoader.getOrDefault(lid, List.of()).size();
                        if (size > bestSize) {
                            bestSize = size;
                            best = lid;
                        }
                    }
                    primaryLoaderId = best;
                }
            }
            pi.classLoaders = Math.max(1, loaderCount);
            pi.classLoaderId = primaryLoaderId;

            List<String> ownedNames = null;
            if (primaryLoaderId != 0 && byLoader.containsKey(primaryLoaderId)) {
                final List<HprofParser.ClassRec> owned = byLoader.get(primaryLoaderId);
                loaderToPlugin.put(primaryLoaderId, pi.name);
                pluginLoaderIds.add(primaryLoaderId);
                applyOwnedHeap(pi, owned, debug, histStart, histEnd);
                ownedNames = new ArrayList<>(owned.size());
                for (HprofParser.ClassRec rec : owned) {
                    ownedNames.add(rec.name);
                }
            } else {
                // No dump bridge — fall back to the legacy classes map + histogram.
                ownedNames = reflectOwnedClasses(pluginLoader);
                applyOwnedHistogram(pi, ownedNames, debug, histStart, histEnd);
            }

            fillNonHeapSignals(pi, plugin, pluginLoader);
            scorePlugin(pi, debug);
            report.plugins.add(pi);
        }

        report.plugins.sort(Comparator.comparingInt((PluginInfo p) -> p.leakScore)
            .thenComparingLong(p -> p.liveBytes).reversed());

        if (heap != null) {
            buildPackages(report, heap, loaderToPlugin);
        }
        buildSuspects(report, histStart, histEnd, loaderOfClassName, loaderToPlugin);

        if (report.heapDump != null) {
            report.heapDump.pluginClassLoaderCount = pluginLoaderIds.size();
        }
    }

    // --- owned-class footprint from the heap dump ---

    private static void applyOwnedHeap(PluginInfo pi, List<HprofParser.ClassRec> owned, boolean debug,
                                       Map<String, long[]> histStart, Map<String, long[]> histEnd) {
        long instances = 0;
        long bytes = 0;
        final Map<String, long[]> packages = new HashMap<>();
        for (HprofParser.ClassRec rec : owned) {
            instances += rec.instances;
            bytes += rec.shallowBytes;
            final long[] agg = packages.computeIfAbsent(packageOf(rec.name), k -> new long[3]);
            agg[0]++;
            agg[1] += rec.instances;
            agg[2] += rec.shallowBytes;
        }
        pi.ownedClasses = owned.size();
        pi.liveInstances = instances;
        pi.liveBytes = bytes;

        owned.stream()
            .sorted(Comparator.comparingLong((HprofParser.ClassRec r) -> r.shallowBytes).reversed())
            .limit(TOP_DRILL)
            .forEach(rec -> {
                final HeapClass hc = new HeapClass();
                hc.name = rec.name;
                hc.instances = rec.instances;
                hc.bytes = rec.shallowBytes;
                pi.topClasses.add(hc);
            });

        packages.entrySet().stream()
            .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[2]).reversed())
            .limit(TOP_DRILL)
            .forEach(e -> {
                final PackageInfo p = new PackageInfo();
                p.name = e.getKey();
                p.owner = pi.name;
                p.classes = (int) e.getValue()[0];
                p.instances = e.getValue()[1];
                p.bytes = e.getValue()[2];
                pi.topPackages.add(p);
            });

        if (debug && histStart != null && histEnd != null) {
            long di = 0;
            long db = 0;
            for (HprofParser.ClassRec rec : owned) {
                final long[] end = histEnd.get(rec.name);
                if (end == null) {
                    continue;
                }
                final long[] start = histStart.get(rec.name);
                di += end[0] - (start == null ? 0 : start[0]);
                db += end[1] - (start == null ? 0 : start[1]);
            }
            pi.instancesDelta = di;
            pi.bytesDelta = db;
        }
    }

    // --- owned-class footprint from the histogram (no-dump fallback) ---

    private static void applyOwnedHistogram(PluginInfo pi, List<String> ownedNames, boolean debug,
                                            Map<String, long[]> histStart, Map<String, long[]> histEnd) {
        if (ownedNames == null || ownedNames.isEmpty() || histEnd == null) {
            return;
        }
        long instances = 0;
        long bytes = 0;
        long di = 0;
        long db = 0;
        int counted = 0;
        for (String name : ownedNames) {
            final long[] end = histEnd.get(name);
            if (end == null) {
                continue;
            }
            counted++;
            instances += end[0];
            bytes += end[1];
            final HeapClass hc = new HeapClass();
            hc.name = name;
            hc.instances = end[0];
            hc.bytes = end[1];
            pi.topClasses.add(hc);
            if (debug && histStart != null) {
                final long[] start = histStart.get(name);
                di += end[0] - (start == null ? 0 : start[0]);
                db += end[1] - (start == null ? 0 : start[1]);
            }
        }
        pi.ownedClasses = ownedNames.size();
        pi.liveInstances = instances;
        pi.liveBytes = bytes;
        pi.instancesDelta = di;
        pi.bytesDelta = db;
        pi.topClasses.sort(Comparator.comparingLong((HeapClass h) -> h.bytes).reversed());
        if (pi.topClasses.size() > TOP_DRILL) {
            pi.topClasses.subList(TOP_DRILL, pi.topClasses.size()).clear();
        }
        if (counted == 0) {
            pi.topClasses.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> reflectOwnedClasses(ClassLoader loader) {
        if (loader == null) {
            return new ArrayList<>();
        }
        try {
            final java.lang.reflect.Field f = loader.getClass().getDeclaredField("classes");
            f.setAccessible(true);
            final Object value = f.get(loader);
            if (value instanceof Map<?, ?> map) {
                return new ArrayList<>((Set<String>) map.keySet());
            }
        } catch (Throwable ignored) {
            // modern Paper classloaders keep no such map — nothing to recover here
        }
        return new ArrayList<>();
    }

    // --- live (non-heap) leak signals ---

    private static void fillNonHeapSignals(PluginInfo pi, Plugin plugin, ClassLoader pluginLoader) {
        try {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                ClassLoader ctx;
                try {
                    ctx = t.getContextClassLoader();
                } catch (Throwable ignored) {
                    continue;
                }
                if (ctx == pluginLoader && pluginLoader != null) {
                    pi.threads++;
                    if (pi.threadNames.size() < 10) {
                        pi.threadNames.add(t.getName());
                    }
                }
            }
        } catch (Throwable ignored) {
            // thread enumeration is best-effort
        }
        try {
            for (BukkitTask task : Bukkit.getScheduler().getPendingTasks()) {
                if (task.getOwner() == plugin) {
                    pi.tasks++;
                }
            }
        } catch (Throwable ignored) {
            // scheduler may be mid-tick
        }
        try {
            pi.listeners = HandlerList.getRegisteredListeners(plugin).size();
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // --- leak scoring ---

    private static void scorePlugin(PluginInfo pi, boolean debug) {
        int score = 0;
        if (debug && pi.bytesDelta > 0) {
            final long growthMb = pi.bytesDelta / (1024 * 1024);
            final int pts = (int) Math.min(40, growthMb * 2);
            if (pts > 0) {
                score += pts;
                pi.leakReasons.add("owned heap grew " + mb(pi.bytesDelta) + " during the window");
            }
        }
        if (pi.classLoaders > 1) {
            score += 30;
            pi.leakReasons.add(pi.classLoaders + " classloaders retained — a previous version was reloaded and never garbage-collected");
        }
        if (pi.threads > THREAD_LEAK_THRESHOLD) {
            score += Math.min(15, pi.threads - THREAD_LEAK_THRESHOLD);
            pi.leakReasons.add(pi.threads + " threads bound to the plugin classloader");
        }
        if (pi.tasks > TASK_LEAK_THRESHOLD) {
            score += Math.min(15, (pi.tasks - TASK_LEAK_THRESHOLD) / 2);
            pi.leakReasons.add(pi.tasks + " scheduled tasks still pending");
        }
        pi.leakScore = Math.max(0, Math.min(100, score));
    }

    // --- global package rollup ---

    private static void buildPackages(DiagnosticsReport report, HprofParser.Result heap, Map<Long, String> loaderToPlugin) {
        final Map<String, long[]> agg = new HashMap<>();   // package -> [classes, instances, bytes]
        final Map<String, String> owner = new HashMap<>(); // package -> owner label (first wins, plugin preferred)
        for (HprofParser.ClassRec rec : heap.classById.values()) {
            final String pkg = packageOf(rec.name);
            final long[] a = agg.computeIfAbsent(pkg, k -> new long[3]);
            a[0]++;
            a[1] += rec.instances;
            a[2] += rec.shallowBytes;
            final String plugin = loaderToPlugin.get(rec.loaderId);
            final String label = plugin != null ? plugin : classify(rec.name);
            owner.merge(pkg, label, (existing, incoming) ->
                existing.equals("minecraft") || existing.equals("jdk") || existing.equals("unknown") ? incoming : existing);
        }
        agg.entrySet().stream()
            .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[2]).reversed())
            .limit(MAX_PACKAGES)
            .forEach(e -> {
                final PackageInfo p = new PackageInfo();
                p.name = e.getKey();
                p.owner = owner.getOrDefault(e.getKey(), "unknown");
                p.classes = (int) e.getValue()[0];
                p.instances = e.getValue()[1];
                p.bytes = e.getValue()[2];
                report.packages.add(p);
            });
    }

    // --- leak suspects ---

    private static void buildSuspects(DiagnosticsReport report, Map<String, long[]> histStart, Map<String, long[]> histEnd,
                                      Map<String, Long> loaderOfClassName, Map<Long, String> loaderToPlugin) {
        // Class growth (debug only): top growing classes by byte delta.
        if (histStart != null && histEnd != null) {
            final List<LeakSuspect> growth = new ArrayList<>();
            for (Map.Entry<String, long[]> e : histEnd.entrySet()) {
                final long[] start = histStart.get(e.getKey());
                final long db = e.getValue()[1] - (start == null ? 0 : start[1]);
                if (db <= 0) {
                    continue;
                }
                final LeakSuspect s = new LeakSuspect();
                s.kind = "class-growth";
                s.detail = e.getKey();
                s.instances = e.getValue()[0] - (start == null ? 0 : start[0]);
                s.bytes = e.getValue()[1];
                s.bytesDelta = db;
                final Long loaderId = loaderOfClassName.get(e.getKey());
                s.plugin = loaderId != null ? loaderToPlugin.get(loaderId) : null;
                s.severity = db > 64L * 1024 * 1024 ? "high" : db > 8L * 1024 * 1024 ? "medium" : "low";
                s.reason = (s.plugin != null ? "plugin " + s.plugin + ": " : "") + "grew " + mb(db);
                growth.add(s);
            }
            growth.sort(Comparator.comparingLong((LeakSuspect s) -> s.bytesDelta).reversed());
            growth.stream().limit(25).forEach(report.leakSuspects::add);
        }

        // Classloader / thread / task leaks from the per-plugin signals.
        for (PluginInfo pi : report.plugins) {
            if (pi.classLoaders > 1) {
                report.leakSuspects.add(suspect("classloader-leak", pi.name,
                    pi.classLoaders + " retained classloaders", "high",
                    "plugin reloaded; old classloader(s) not collected"));
            }
            if (pi.threads > THREAD_LEAK_THRESHOLD) {
                report.leakSuspects.add(suspect("thread-leak", pi.name,
                    pi.threads + " plugin threads", "medium", "threads bound to the plugin classloader"));
            }
            if (pi.tasks > TASK_LEAK_THRESHOLD) {
                report.leakSuspects.add(suspect("task-leak", pi.name,
                    pi.tasks + " pending tasks", "low", "scheduled tasks accumulating"));
            }
        }

        if (report.leakSuspects.size() > MAX_SUSPECTS) {
            report.leakSuspects.subList(MAX_SUSPECTS, report.leakSuspects.size()).clear();
        }
    }

    private static LeakSuspect suspect(String kind, String plugin, String detail, String severity, String reason) {
        final LeakSuspect s = new LeakSuspect();
        s.kind = kind;
        s.plugin = plugin;
        s.detail = detail;
        s.severity = severity;
        s.reason = reason;
        return s;
    }

    // --- helpers ---

    private static String packageOf(String className) {
        if (className == null || className.isEmpty()) {
            return "(unknown)";
        }
        final String elem = arrayElement(className);
        if (elem == null) {
            return "(primitive arrays)";
        }
        final int dot = elem.lastIndexOf('.');
        return dot < 0 ? "(default)" : elem.substring(0, dot);
    }

    private static String classify(String className) {
        final String n = arrayElement(className);
        if (n == null) {
            return "jdk"; // primitive array — bootstrap-owned
        }
        if (n.startsWith("java.") || n.startsWith("jdk.") || n.startsWith("sun.")
            || n.startsWith("javax.") || n.startsWith("com.sun.")) {
            return "jdk";
        }
        if (n.startsWith("net.minecraft.") || n.startsWith("org.bukkit.")
            || n.startsWith("io.papermc.") || n.startsWith("com.destroystokyo.")
            || n.startsWith("org.spigotmc.") || n.startsWith("org.purpurmc.")
            || n.startsWith("org.bxteam.") || n.startsWith("ca.spottedleaf.")
            || n.startsWith("gg.pufferfish.")) {
            return "minecraft";
        }
        return "unknown";
    }

    /**
     * Unwraps array class names to their element class. {@code "[Lcom.foo.Bar;" → "com.foo.Bar"},
     * {@code "com.foo.Bar" → "com.foo.Bar"}, and primitive arrays ({@code "[B"}, {@code "[[I"}) → null.
     */
    private static String arrayElement(String className) {
        int dims = 0;
        while (dims < className.length() && className.charAt(dims) == '[') {
            dims++;
        }
        if (dims == 0) {
            return className;
        }
        final String tail = className.substring(dims);
        if (tail.startsWith("L") && tail.endsWith(";")) {
            return tail.substring(1, tail.length() - 1);
        }
        return null; // primitive array
    }

    private static String mb(long bytes) {
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private interface Sup<T> {
        T get() throws Throwable;
    }

    private static String safe(Sup<String> sup, String def) {
        try {
            final String v = sup.get();
            return v != null ? v : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean safeBool(Sup<Boolean> sup) {
        try {
            return Boolean.TRUE.equals(sup.get());
        } catch (Throwable t) {
            return false;
        }
    }
}
