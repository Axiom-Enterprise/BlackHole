package org.bxteam.divinemc.diagnostics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Combined diagnostics report. Serialized to JSON (gson) and uploaded to the
 * self-hosted Axiom diagnostics viewer, which renders the graphs.
 *
 * <p>All fields are plain data so gson can serialize them without adapters.
 * Field names are the JSON keys the viewer expects.
 */
public final class DiagnosticsReport {
    /** Report schema version, bumped when the viewer contract changes. */
    public int schema = 2;
    /** "metrics" (instant snapshot) or "debug" (timed profiling session). */
    public String type;
    public Meta meta = new Meta();

    public System system = new System();
    public List<GcCollector> gc = new ArrayList<>();
    public Jit jit = new Jit();

    /** Timeline samples captured over a window (debug) or a single point (metrics). */
    public List<TickSample> tickTimeline = new ArrayList<>();
    public List<LagSpike> lagSpikes = new ArrayList<>();
    public List<MemorySample> memoryTimeline = new ArrayList<>();
    public List<CpuSample> cpuTimeline = new ArrayList<>();

    /** Heap class histogram (top N). Present in both modes. */
    public List<HeapClass> heap = new ArrayList<>();
    /** Per-class growth between session start and end. Debug only; null otherwise. */
    public List<HeapDelta> heapDelta;

    /** CPU profiler flamegraph root. Debug only; null in metrics mode. */
    public FlameNode flamegraph;

    /** Per-plugin attribution (classes, packages, live footprint, leak signals). Both modes. */
    public List<PluginInfo> plugins = new ArrayList<>();
    /** Heap rolled up by package, attributed to an owning plugin / minecraft / jdk. Both modes. */
    public List<PackageInfo> packages = new ArrayList<>();
    /** Ranked leak suspects across classes, classloaders, threads and tasks. Both modes. */
    public List<LeakSuspect> leakSuspects = new ArrayList<>();
    /** Heap-dump capture + parse metadata. Null if a dump was never attempted. */
    public HeapDumpInfo heapDump;

    public static final class Meta {
        public String serverBrand;
        public String serverVersion;
        public String minecraftVersion;
        public long generatedAtEpochMs;
        public long uptimeMs;
        public int onlinePlayers;
        public int loadedWorlds;
        public int availableProcessors;
        public List<String> jvmFlags = new ArrayList<>();
        /** Profiling window length in ms (debug). 0 for metrics. */
        public long durationMs;
    }

    public static final class System {
        public long heapUsed;
        public long heapCommitted;
        public long heapMax;
        public long nonHeapUsed;
        public long nonHeapCommitted;
        public double processCpuLoad;   // 0..1, -1 if unavailable
        public double systemCpuLoad;    // 0..1, -1 if unavailable
        public double systemLoadAverage;
        public int threadCount;
        public int daemonThreadCount;
        public int peakThreadCount;
        public long totalStartedThreads;
        public Map<String, Integer> threadStates = new LinkedHashMap<>();
        public int loadedClasses;
        public long totalLoadedClasses;
        public long unloadedClasses;
        public List<MemoryPool> pools = new ArrayList<>();
    }

    public static final class MemoryPool {
        public String name;
        public String type; // HEAP / NON_HEAP
        public long used;
        public long committed;
        public long max;
    }

    public static final class GcCollector {
        public String name;
        public long collectionCount;
        public long collectionTimeMs;
        /** Deltas over the profiling window (debug). 0 for metrics. */
        public long deltaCount;
        public long deltaTimeMs;
    }

    public static final class Jit {
        public String compilerName;
        public long totalCompilationTimeMs;
        /** Compilation time accrued during the window (debug). 0 for metrics. */
        public long deltaCompilationTimeMs;
    }

    public static final class TickSample {
        public long t;       // ms offset from report start
        public double tps;
        public double mspt;
    }

    public static final class LagSpike {
        public long t;       // ms offset from report start
        public double mspt;
    }

    public static final class MemorySample {
        public long t;       // ms offset from report start
        public long heapUsed;
        public long nonHeapUsed;
    }

    public static final class CpuSample {
        public long t;       // ms offset from report start
        public double process; // 0..1
        public double system;  // 0..1
    }

    public static final class HeapClass {
        public String name;
        public long instances;
        public long bytes;
    }

    public static final class HeapDelta {
        public String name;
        public long instancesDelta;
        public long bytesDelta;
    }

    /** d3-flame-graph compatible node: {name, value, children}. */
    public static final class FlameNode {
        public String name;
        public long value;
        public List<FlameNode> children;

        public FlameNode(String name) {
            this.name = name;
        }
    }

    /** One installed plugin with its heap footprint and leak signals. */
    public static final class PluginInfo {
        public String name;
        public String version;
        public String main;                            // main class name
        public List<String> authors = new ArrayList<>();
        public boolean enabled;
        public String loaderType;                      // simple class name of the plugin classloader
        /** HPROF classloader object id (debugging aid; 0 when the dump could not bridge it). */
        public long classLoaderId;
        public int ownedClasses;                       // classes loaded by this plugin's loader
        public long liveInstances;                     // instances of owned classes (from heap dump)
        public long liveBytes;                         // shallow bytes of owned-class instances
        public long instancesDelta;                    // owned-class growth over the window (debug); 0 in metrics
        public long bytesDelta;
        public int threads;                            // live threads attributed to the plugin
        public List<String> threadNames = new ArrayList<>();
        public int tasks;                              // scheduled Bukkit tasks owned
        public int listeners;                          // registered event listeners
        public int classLoaders;                       // loaders found for this plugin (>1 ⇒ reload leak)
        public int leakScore;                          // 0..100 heuristic
        public List<String> leakReasons = new ArrayList<>();
        public List<PackageInfo> topPackages = new ArrayList<>();  // drill-down
        public List<HeapClass> topClasses = new ArrayList<>();     // drill-down (top owned classes by bytes)
    }

    /** Heap rolled up by Java package. */
    public static final class PackageInfo {
        public String name;
        public String owner;     // plugin name / "minecraft" / "jdk" / "unknown"
        public int classes;
        public long instances;
        public long bytes;
    }

    /** A ranked leak suspect. */
    public static final class LeakSuspect {
        public String kind;        // class-growth | classloader-leak | thread-leak | task-leak
        public String plugin;      // owning plugin, or null
        public String detail;      // class name / human description
        public long bytes;
        public long bytesDelta;
        public long instances;
        public String severity;    // low | medium | high
        public String reason;
    }

    /** Heap-dump capture + parse metadata. */
    public static final class HeapDumpInfo {
        public boolean captured;
        public boolean parsed;
        public String skipReason;          // null when captured + parsed cleanly
        public long fileBytes;
        public long dumpMillis;
        public long parseMillis;
        public long totalInstances;
        public long totalShallowBytes;
        public int classCount;
        public int classLoaderCount;
        public int pluginClassLoaderCount;
    }
}
