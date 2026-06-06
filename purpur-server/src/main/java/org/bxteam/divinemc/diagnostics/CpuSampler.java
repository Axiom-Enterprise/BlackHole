package org.bxteam.divinemc.diagnostics;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;

/**
 * Wall-clock CPU profiler. Polls every thread's stack at a fixed interval and
 * aggregates the frames into a call tree — the same fundamental technique spark
 * uses. Each sample adds {@code intervalMs} to every frame on a thread's stack,
 * so a node's value approximates the time spent in that frame.
 *
 * <p>Runs on its own daemon thread; sampling is lock-free against the main thread.
 */
public final class CpuSampler {
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final long intervalMs;
    private final Node root = new Node("root");
    private volatile boolean running;
    private Thread worker;

    public CpuSampler(long intervalMs) {
        this.intervalMs = Math.max(1, intervalMs);
    }

    public void start() {
        running = true;
        worker = new Thread(this::loop, "Axiom-CpuSampler");
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

    private void loop() {
        while (running) {
            final long startNanos = System.nanoTime();
            sampleOnce();
            final long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            final long sleep = intervalMs - elapsedMs;
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void sampleOnce() {
        final ThreadInfo[] infos = threadBean.dumpAllThreads(false, false);
        for (ThreadInfo info : infos) {
            if (info == null) {
                continue;
            }
            final StackTraceElement[] stack = info.getStackTrace();
            if (stack.length == 0) {
                continue;
            }
            // Group by thread name so the flamegraph separates worker pools.
            Node node = root.child(info.getThreadName());
            node.value += intervalMs;
            // Walk root->leaf so children represent callees.
            for (int i = stack.length - 1; i >= 0; i--) {
                final StackTraceElement frame = stack[i];
                node = node.child(frame.getClassName() + "." + frame.getMethodName());
                node.value += intervalMs;
            }
        }
    }

    /** Convert the accumulated tree into the report's flamegraph shape. */
    public DiagnosticsReport.FlameNode toFlame() {
        return convert(root);
    }

    private static DiagnosticsReport.FlameNode convert(Node n) {
        final DiagnosticsReport.FlameNode out = new DiagnosticsReport.FlameNode(n.name);
        out.value = n.value;
        if (!n.children.isEmpty()) {
            out.children = new java.util.ArrayList<>(n.children.size());
            for (Node c : n.children.values()) {
                out.children.add(convert(c));
            }
        }
        return out;
    }

    private static final class Node {
        final String name;
        long value;
        final Map<String, Node> children = new HashMap<>();

        Node(String name) {
            this.name = name;
        }

        Node child(String name) {
            return children.computeIfAbsent(name, Node::new);
        }
    }
}
