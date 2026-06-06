package org.bxteam.divinemc.diagnostics;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.management.ObjectName;

/**
 * Heap class histogram via the {@code com.sun.management:type=DiagnosticCommand}
 * MBean ({@code gcClassHistogram}) — the same data as {@code jcmd GC.class_histogram}.
 * Used to spot memory leaks: the top classes by retained bytes, and (in debug
 * mode) the per-class growth between two snapshots.
 */
public final class HeapHistogram {
    private HeapHistogram() {
    }

    /** One raw histogram: class name -> [instances, bytes]. */
    public static final class Snapshot {
        public final Map<String, long[]> byClass;

        Snapshot(Map<String, long[]> byClass) {
            this.byClass = byClass;
        }
    }

    public static Snapshot capture() {
        final Map<String, long[]> out = new LinkedHashMap<>();
        try {
            final ObjectName name = new ObjectName("com.sun.management:type=DiagnosticCommand");
            final String raw = (String) ManagementFactory.getPlatformMBeanServer().invoke(
                name,
                "gcClassHistogram",
                new Object[]{new String[0]},
                new String[]{String[].class.getName()});
            parse(raw, out);
        } catch (Throwable ignored) {
            // DiagnosticCommand may be unavailable (security manager / restricted JVM).
        }
        return new Snapshot(out);
    }

    /** Top-N entries by bytes, written into the report. */
    public static void fillTop(DiagnosticsReport report, Snapshot snapshot, int topN) {
        snapshot.byClass.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
            .limit(Math.max(1, topN))
            .forEach(e -> {
                final DiagnosticsReport.HeapClass hc = new DiagnosticsReport.HeapClass();
                hc.name = e.getKey();
                hc.instances = e.getValue()[0];
                hc.bytes = e.getValue()[1];
                report.heap.add(hc);
            });
    }

    /** Top-N growth (end - start) by bytes delta, written into the report. */
    public static void fillDelta(DiagnosticsReport report, Snapshot start, Snapshot end, int topN) {
        final List<DiagnosticsReport.HeapDelta> deltas = new ArrayList<>();
        for (Map.Entry<String, long[]> e : end.byClass.entrySet()) {
            final long[] before = start.byClass.get(e.getKey());
            final long instDelta = e.getValue()[0] - (before == null ? 0 : before[0]);
            final long byteDelta = e.getValue()[1] - (before == null ? 0 : before[1]);
            if (byteDelta <= 0) {
                continue;
            }
            final DiagnosticsReport.HeapDelta d = new DiagnosticsReport.HeapDelta();
            d.name = e.getKey();
            d.instancesDelta = instDelta;
            d.bytesDelta = byteDelta;
            deltas.add(d);
        }
        deltas.sort((a, b) -> Long.compare(b.bytesDelta, a.bytesDelta));
        report.heapDelta = deltas.subList(0, Math.min(deltas.size(), Math.max(1, topN)));
    }

    // Lines look like: "   1:        123456       7891011  java.lang.String"
    private static void parse(String raw, Map<String, long[]> out) {
        if (raw == null) {
            return;
        }
        for (String line : raw.split("\n")) {
            final String[] parts = line.trim().split("\\s+");
            if (parts.length < 4 || !parts[0].endsWith(":")) {
                continue;
            }
            try {
                final long instances = Long.parseLong(parts[1]);
                final long bytes = Long.parseLong(parts[2]);
                out.put(parts[3], new long[]{instances, bytes});
            } catch (NumberFormatException ignored) {
                // header / total / non-data line
            }
        }
    }
}
