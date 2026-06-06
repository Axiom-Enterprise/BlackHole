package org.bxteam.divinemc.diagnostics;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.List;

/**
 * Captures one instantaneous picture of the JVM via {@code java.lang.management}
 * MBeans. Independent of spark — works on any JVM the server runs on.
 */
public final class SystemSnapshot {
    private SystemSnapshot() {
    }

    public static void fill(DiagnosticsReport report) {
        final DiagnosticsReport.System s = report.system;

        final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        final MemoryUsage heap = memory.getHeapMemoryUsage();
        final MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        s.heapUsed = heap.getUsed();
        s.heapCommitted = heap.getCommitted();
        s.heapMax = heap.getMax();
        s.nonHeapUsed = nonHeap.getUsed();
        s.nonHeapCommitted = nonHeap.getCommitted();

        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            final MemoryUsage usage = pool.getUsage();
            if (usage == null) {
                continue;
            }
            final DiagnosticsReport.MemoryPool p = new DiagnosticsReport.MemoryPool();
            p.name = pool.getName();
            p.type = pool.getType().name();
            p.used = usage.getUsed();
            p.committed = usage.getCommitted();
            p.max = usage.getMax();
            s.pools.add(p);
        }

        final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        s.threadCount = threads.getThreadCount();
        s.daemonThreadCount = threads.getDaemonThreadCount();
        s.peakThreadCount = threads.getPeakThreadCount();
        s.totalStartedThreads = threads.getTotalStartedThreadCount();
        final ThreadInfo[] infos = threads.getThreadInfo(threads.getAllThreadIds());
        for (ThreadInfo info : infos) {
            if (info == null) {
                continue;
            }
            s.threadStates.merge(info.getThreadState().name(), 1, Integer::sum);
        }

        final ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
        s.loadedClasses = classes.getLoadedClassCount();
        s.totalLoadedClasses = classes.getTotalLoadedClassCount();
        s.unloadedClasses = classes.getUnloadedClassCount();

        s.processCpuLoad = osDouble("getProcessCpuLoad");
        s.systemCpuLoad = osDouble("getCpuLoad");
        if (s.systemCpuLoad < 0) {
            s.systemCpuLoad = osDouble("getSystemCpuLoad"); // older JDK name
        }
        s.systemLoadAverage = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();

        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            final DiagnosticsReport.GcCollector c = new DiagnosticsReport.GcCollector();
            c.name = gc.getName();
            c.collectionCount = Math.max(0, gc.getCollectionCount());
            c.collectionTimeMs = Math.max(0, gc.getCollectionTime());
            report.gc.add(c);
        }

        final CompilationMXBean compilation = ManagementFactory.getCompilationMXBean();
        if (compilation != null) {
            report.jit.compilerName = compilation.getName();
            if (compilation.isCompilationTimeMonitoringSupported()) {
                report.jit.totalCompilationTimeMs = compilation.getTotalCompilationTime();
            }
        }

        final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        report.meta.uptimeMs = runtime.getUptime();
        report.meta.availableProcessors = Runtime.getRuntime().availableProcessors();
        report.meta.jvmFlags.addAll(filterFlags(runtime.getInputArguments()));
    }

    /** Reads a 0..1 CPU metric from com.sun.management OS bean reflectively (avoids hard dep). */
    private static double osDouble(String method) {
        try {
            final java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            final java.lang.reflect.Method m = os.getClass().getMethod(method);
            m.setAccessible(true);
            final Object value = m.invoke(os);
            return value instanceof Number n ? n.doubleValue() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static List<String> filterFlags(List<String> args) {
        return args.stream()
            .filter(a -> a.startsWith("-X") || a.startsWith("-D") || a.startsWith("-server") || a.startsWith("--"))
            .toList();
    }
}
