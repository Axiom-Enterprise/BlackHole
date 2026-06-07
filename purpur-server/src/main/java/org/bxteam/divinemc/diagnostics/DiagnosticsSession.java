package org.bxteam.divinemc.diagnostics;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bxteam.divinemc.config.DivineConfig;

/**
 * Orchestrates the two diagnostics modes and uploads the combined report.
 *
 * <ul>
 *   <li>{@link #metrics(Consumer)} — instant snapshot, returns a link immediately.</li>
 *   <li>{@link #debug(int, Consumer)} — profiles for N seconds (CPU flamegraph,
 *       tick/memory/CPU timelines, GC/JIT deltas, heap leak delta), then uploads.</li>
 * </ul>
 *
 * Only one timed (debug) session may run at a time. All heavy work is off the
 * main thread; the {@code onResult} callback is invoked on a background thread,
 * so callers that touch Bukkit state must re-schedule onto the main thread.
 */
public final class DiagnosticsSession {
    private static final AtomicBoolean DEBUG_RUNNING = new AtomicBoolean(false);

    private DiagnosticsSession() {
    }

    public static boolean isDebugRunning() {
        return DEBUG_RUNNING.get();
    }

    /** Instant metrics snapshot. */
    public static void metrics(Consumer<ReportUploader.Result> onResult) {
        CompletableFuture.runAsync(() -> {
            final DiagnosticsReport report = new DiagnosticsReport();
            report.type = "metrics";
            fillMeta(report, 0);
            SystemSnapshot.fill(report);

            final TickMonitor monitor = new TickMonitor(report, 1000, DivineConfig.diagnosticsLagSpikeThresholdMs);
            monitor.sampleOnce();

            final HeapHistogram.Snapshot heap = HeapHistogram.capture();
            HeapHistogram.fillTop(report, heap, DivineConfig.diagnosticsHeapHistogramTopN);

            ReportUploader.upload(DivineConfig.diagnosticsViewerBaseUrl(), report).thenAccept(onResult);
        });
    }

    /** Timed deep profiling session. */
    public static void debug(int seconds, Consumer<ReportUploader.Result> onResult) {
        if (!DEBUG_RUNNING.compareAndSet(false, true)) {
            onResult.accept(failed("a debug session is already running"));
            return;
        }

        final int duration = seconds > 0 ? seconds : DivineConfig.diagnosticsProfilerDurationSeconds;

        CompletableFuture.runAsync(() -> {
            try {
                final DiagnosticsReport report = new DiagnosticsReport();
                report.type = "debug";
                fillMeta(report, duration * 1000L);

                // Baselines for deltas.
                SystemSnapshot.fill(report);
                final Map<String, long[]> gcBaseline = gcBaseline(report);
                final long jitBaseline = report.jit.totalCompilationTimeMs;
                final HeapHistogram.Snapshot heapStart = HeapHistogram.capture();

                final CpuSampler sampler = new CpuSampler(DivineConfig.diagnosticsSamplerIntervalMs);
                final TickMonitor monitor = new TickMonitor(report, DivineConfig.diagnosticsSamplerIntervalMs, DivineConfig.diagnosticsLagSpikeThresholdMs);
                sampler.start();
                monitor.start();

                Thread.sleep(duration * 1000L);

                sampler.stop();
                monitor.stop();
                report.flamegraph = sampler.toFlame();

                // Recompute end-state system numbers + deltas.
                final DiagnosticsReport endState = new DiagnosticsReport();
                SystemSnapshot.fill(endState);
                report.system = endState.system;
                report.gc = endState.gc;
                report.jit = endState.jit;
                applyGcDeltas(report, gcBaseline);
                report.jit.deltaCompilationTimeMs = Math.max(0, report.jit.totalCompilationTimeMs - jitBaseline);

                final HeapHistogram.Snapshot heapEnd = HeapHistogram.capture();
                HeapHistogram.fillTop(report, heapEnd, DivineConfig.diagnosticsHeapHistogramTopN);
                HeapHistogram.fillDelta(report, heapStart, heapEnd, DivineConfig.diagnosticsHeapHistogramTopN);

                ReportUploader.upload(DivineConfig.diagnosticsViewerBaseUrl(), report).thenAccept(onResult);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                onResult.accept(failed("debug session interrupted"));
            } catch (Throwable t) {
                onResult.accept(failed(t.getClass().getSimpleName() + ": " + t.getMessage()));
            } finally {
                DEBUG_RUNNING.set(false);
            }
        });
    }

    private static void fillMeta(DiagnosticsReport report, long durationMs) {
        final DiagnosticsReport.Meta meta = report.meta;
        meta.serverBrand = Bukkit.getName();
        meta.serverVersion = Bukkit.getVersion();
        meta.minecraftVersion = Bukkit.getMinecraftVersion();
        meta.generatedAtEpochMs = System.currentTimeMillis();
        meta.onlinePlayers = Bukkit.getOnlinePlayers().size();
        meta.loadedWorlds = Bukkit.getWorlds().size();
        meta.durationMs = durationMs;
    }

    private static Map<String, long[]> gcBaseline(DiagnosticsReport report) {
        final Map<String, long[]> baseline = new HashMap<>();
        for (DiagnosticsReport.GcCollector c : report.gc) {
            baseline.put(c.name, new long[]{c.collectionCount, c.collectionTimeMs});
        }
        return baseline;
    }

    private static void applyGcDeltas(DiagnosticsReport report, Map<String, long[]> baseline) {
        for (DiagnosticsReport.GcCollector c : report.gc) {
            final long[] before = baseline.get(c.name);
            if (before != null) {
                c.deltaCount = Math.max(0, c.collectionCount - before[0]);
                c.deltaTimeMs = Math.max(0, c.collectionTimeMs - before[1]);
            }
        }
    }

    private static ReportUploader.Result failed(String message) {
        return ReportUploader.Result.error(message);
    }
}
