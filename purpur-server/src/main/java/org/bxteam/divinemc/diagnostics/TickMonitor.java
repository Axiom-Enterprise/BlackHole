package org.bxteam.divinemc.diagnostics;

import org.bukkit.Bukkit;

/**
 * Samples TPS / MSPT and heap/CPU usage at a fixed interval on a daemon thread,
 * building the timelines the viewer charts. Records a lag spike whenever the
 * sampled MSPT exceeds the configured threshold.
 *
 * <p>Reads via the Bukkit API ({@link Bukkit#getTPS()}, {@link Bukkit#getAverageTickTime()})
 * so it is agnostic to the underlying tick loop (regionized / parallel).
 */
public final class TickMonitor {
    private final long intervalMs;
    private final double lagSpikeThresholdMs;
    private final long startNanos = System.nanoTime();
    private final DiagnosticsReport report;
    private volatile boolean running;
    private Thread worker;

    public TickMonitor(DiagnosticsReport report, long intervalMs, double lagSpikeThresholdMs) {
        this.report = report;
        this.intervalMs = Math.max(50, intervalMs);
        this.lagSpikeThresholdMs = lagSpikeThresholdMs;
    }

    public void start() {
        running = true;
        worker = new Thread(this::loop, "Axiom-TickMonitor");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        running = false;
        if (worker != null) {
            try {
                worker.join(intervalMs * 2 + 500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Capture a single point immediately (used by the instant metrics command). */
    public void sampleOnce() {
        record();
    }

    private void loop() {
        while (running) {
            record();
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void record() {
        final long t = (System.nanoTime() - startNanos) / 1_000_000L;
        final double tps = Math.max(0.0, Math.min(Bukkit.getTPS()[0], 20.0));
        final double mspt = Bukkit.getAverageTickTime();

        final DiagnosticsReport.TickSample tick = new DiagnosticsReport.TickSample();
        tick.t = t;
        tick.tps = tps;
        tick.mspt = mspt;
        report.tickTimeline.add(tick);

        if (mspt >= lagSpikeThresholdMs) {
            final DiagnosticsReport.LagSpike spike = new DiagnosticsReport.LagSpike();
            spike.t = t;
            spike.mspt = mspt;
            report.lagSpikes.add(spike);
        }

        final java.lang.management.MemoryMXBean mem = java.lang.management.ManagementFactory.getMemoryMXBean();
        final DiagnosticsReport.MemorySample memSample = new DiagnosticsReport.MemorySample();
        memSample.t = t;
        memSample.heapUsed = mem.getHeapMemoryUsage().getUsed();
        memSample.nonHeapUsed = mem.getNonHeapMemoryUsage().getUsed();
        report.memoryTimeline.add(memSample);

        final DiagnosticsReport.CpuSample cpu = new DiagnosticsReport.CpuSample();
        cpu.t = t;
        cpu.process = osDouble("getProcessCpuLoad");
        cpu.system = osDouble("getCpuLoad");
        report.cpuTimeline.add(cpu);
    }

    private static double osDouble(String method) {
        try {
            final java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            final java.lang.reflect.Method m = os.getClass().getMethod(method);
            m.setAccessible(true);
            final Object value = m.invoke(os);
            return value instanceof Number n ? n.doubleValue() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
