package org.bxteam.divinemc.diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bxteam.divinemc.config.DivineConfig;

/**
 * Background thread-health watchdog. Three jobs, all safe:
 *
 * <ol>
 *   <li><b>Monitor + alert</b> — a single daemon loop enumerates live threads, groups them by
 *       normalized name (pool family), and logs an actionable warning when a group grows without
 *       bound or when the container's pid/thread budget ({@code cgroup pids.max}) nears exhaustion.
 *       This is what catches a plugin leaking native threads (e.g. an addon self-host loop) before
 *       it trips {@code OutOfMemoryError: unable to create native thread} and kills the server.</li>
 *   <li><b>On-demand snapshot</b> — {@link #snapshot()} renders the same view for the
 *       {@code /axiomthreads} command.</li>
 *   <li><b>Best-effort interrupt</b> — {@link #interrupt(String, boolean)} cooperatively
 *       {@link Thread#interrupt() interrupts} threads matching an admin-supplied pattern, never
 *       touching the main thread, fork-critical pools, or JVM/system threads. {@code interrupt()}
 *       is cooperative: a thread that ignores its interrupt flag survives. There is no force-kill —
 *       {@code Thread.stop()} was removed and would corrupt state.</li>
 * </ol>
 *
 * <p>The watchdog never kills anything on its own. Reaping of <i>idle</i> fork-owned pool threads is
 * handled separately by giving those pools a finite keep-alive + {@code allowCoreThreadTimeOut}, so
 * empty pools shed their threads naturally without any external intervention.
 */
public final class ThreadWatchdog {
    private static final Logger LOGGER = LogManager.getLogger("ThreadWatchdog");

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile Thread loopThread;

    /** First-seen count per group (growth baseline). */
    private static final Map<String, Integer> baseline = new HashMap<>();
    /** Count at the last warning, per group, so we only re-warn after another full threshold of growth. */
    private static final Map<String, Integer> lastWarned = new HashMap<>();
    private static volatile boolean pidsWarned = false;

    /**
     * Thread name prefixes we must NEVER interrupt: the main loop, fork-owned pools, Netty, the
     * scheduler, chunk/region IO, and JVM/system threads. Matched case-insensitively as a prefix.
     */
    private static final String[] CRITICAL_PREFIXES = {
        "server thread", "main", "server console", "server infinisleeper", "dedicatedserver",
        "paper ", "netty", "server i/o", "worker-main", "craft scheduler", "timer-",
        "region ticking", "async io", "player save", "async pathfinding", "tracker",
        "regionfile", "chunk", "io-worker", "divinemc", "flusher", "waypoint",
        // JVM / platform
        "reference handler", "finalizer", "signal dispatcher", "common-cleaner",
        "notification thread", "process reaper", "compilerthread", "c1 compilerthread",
        "c2 compilerthread", "sweeper thread", "service thread", "g1 ", "gc ", "vm ",
        "attach listener", "destroyjavavm", "jvmci", "monitor ctrl-break", "jfr",
        "forkjoinpool.commonpool",
    };

    private ThreadWatchdog() {
    }

    /**
     * Idempotent. Starts the daemon loop when the watchdog is enabled and not already running;
     * signals the loop to stop when it has been disabled via a config reload. Safe to call on every
     * config load.
     */
    public static void apply() {
        if (DivineConfig.threadWatchdogEnabled) {
            start();
        } else {
            stop();
        }
    }

    private static void start() {
        if (!RUNNING.compareAndSet(false, true)) {
            return; // already running
        }
        final Thread t = new Thread(ThreadWatchdog::loop, "Axiom Thread Watchdog");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        loopThread = t;
        t.start();
        LOGGER.info("Thread watchdog started (interval {}s, pids warn at {}%).",
            DivineConfig.threadWatchdogIntervalSeconds, DivineConfig.threadWatchdogPidsWarnPercent);
    }

    /** Signals the loop to exit. */
    public static void stop() {
        if (RUNNING.compareAndSet(true, false)) {
            final Thread t = loopThread;
            if (t != null) {
                t.interrupt();
            }
            loopThread = null;
        }
    }

    private static void loop() {
        while (RUNNING.get() && DivineConfig.threadWatchdogEnabled) {
            try {
                tick();
            } catch (Throwable t) {
                LOGGER.debug("Thread watchdog tick failed", t);
            }
            final long sleepMs = Math.max(5, DivineConfig.threadWatchdogIntervalSeconds) * 1000L;
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                break; // stop() or shutdown
            }
        }
        RUNNING.set(false);
    }

    // --- monitor pass ---

    private static void tick() {
        final Map<String, Integer> groups = groupCounts();
        final int growthWarn = Math.max(4, DivineConfig.threadWatchdogGroupGrowthWarn);

        for (Map.Entry<String, Integer> e : groups.entrySet()) {
            final String group = e.getKey();
            final int count = e.getValue();
            baseline.putIfAbsent(group, count);
            final int base = baseline.get(group);
            final int warnedAt = lastWarned.getOrDefault(group, base);
            // Warn once per full `growthWarn` of net growth above the last point we warned at.
            if (count - warnedAt >= growthWarn) {
                LOGGER.warn("Thread group \"{}\" is growing without bound: {} threads now (+{} since first seen). "
                        + "A plugin or pool is likely leaking threads; this trends toward the container's pid limit. "
                        + "Run /axiomthreads for the full breakdown.",
                    group, count, count - base);
                lastWarned.put(group, count);
            }
        }

        checkPids();
    }

    private static void checkPids() {
        final long[] pids = pidsUsage();
        if (pids == null) {
            return; // not a Linux cgroup (e.g. dev box) — nothing to budget against
        }
        final long current = pids[0];
        final long max = pids[1];
        if (max <= 0) {
            return; // unlimited
        }
        final int pct = (int) (current * 100 / max);
        final int warnPct = Math.max(50, Math.min(99, DivineConfig.threadWatchdogPidsWarnPercent));
        if (pct >= warnPct) {
            if (!pidsWarned) {
                LOGGER.error("Container pid/thread budget at {}% ({}/{}). New threads will soon fail with "
                        + "\"unable to create native thread\" and crash the server. Find the leak with /axiomthreads, "
                        + "then either stop the offending plugin or raise the container's pids limit.",
                    pct, current, max);
                pidsWarned = true;
            }
        } else if (pct < warnPct - 10) {
            pidsWarned = false; // re-arm once we've recovered with hysteresis
        }
    }

    // --- grouping ---

    /** Live thread count per normalized group name, descending by count. */
    private static Map<String, Integer> groupCounts() {
        final Map<String, Integer> counts = new HashMap<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            final String group = normalize(t.getName());
            counts.merge(group, 1, Integer::sum);
        }
        return counts;
    }

    /** Collapses a thread name into its pool family by stripping a trailing instance index. */
    static String normalize(String name) {
        if (name == null || name.isEmpty()) {
            return "(unnamed)";
        }
        // "Region Ticking-3" -> "Region Ticking", "Netty Server IO #2" -> "Netty Server IO",
        // "pool-7-thread-12" -> "pool-thread", "worker-3" -> "worker"
        String n = name.replaceAll("[ _#\\-]*\\d+$", "");
        n = n.replaceAll("-\\d+-(thread|worker)", "-$1"); // pool-N-thread-M families
        n = n.trim();
        return n.isEmpty() ? name : n;
    }

    // --- cgroup pids ---

    /**
     * Reads the container's pid/thread budget from the cgroup pids controller.
     *
     * @return {@code [current, max]}, {@code max <= 0} meaning unlimited, or {@code null} when no
     *         cgroup pids controller is present (non-Linux, or not containerized).
     */
    public static long[] pidsUsage() {
        // cgroup v2 (unified)
        final Long v2cur = readLong(Path.of("/sys/fs/cgroup/pids.current"));
        if (v2cur != null) {
            return new long[]{v2cur, readMax(Path.of("/sys/fs/cgroup/pids.max"))};
        }
        // cgroup v1
        final Long v1cur = readLong(Path.of("/sys/fs/cgroup/pids/pids.current"));
        if (v1cur != null) {
            return new long[]{v1cur, readMax(Path.of("/sys/fs/cgroup/pids/pids.max"))};
        }
        return null;
    }

    private static long readMax(Path p) {
        try {
            final String s = Files.readString(p).trim();
            if (s.equals("max")) {
                return -1; // unlimited
            }
            return Long.parseLong(s);
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    private static Long readLong(Path p) {
        try {
            return Long.parseLong(Files.readString(p).trim());
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    // --- on-demand report ---

    /** Human-readable thread breakdown for {@code /axiomthreads}. */
    public static String snapshot() {
        final Map<String, Integer> groups = groupCounts();
        final int total = groups.values().stream().mapToInt(Integer::intValue).sum();

        final List<Map.Entry<String, Integer>> sorted = new ArrayList<>(groups.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());

        final StringBuilder sb = new StringBuilder();
        sb.append("Live threads: ").append(total);

        final long[] pids = pidsUsage();
        if (pids != null) {
            sb.append("  |  container pids: ").append(pids[0]);
            if (pids[1] > 0) {
                sb.append('/').append(pids[1]).append(" (").append(pids[0] * 100 / pids[1]).append("%)");
            } else {
                sb.append("/unlimited");
            }
        }
        sb.append('\n');

        final int top = Math.min(15, sorted.size());
        for (int i = 0; i < top; i++) {
            final Map.Entry<String, Integer> e = sorted.get(i);
            final Integer base = baseline.get(e.getKey());
            sb.append(String.format(Locale.ROOT, "  %4d  %s", e.getValue(), e.getKey()));
            if (base != null && e.getValue() > base) {
                sb.append(" (+").append(e.getValue() - base).append(" since boot)");
            }
            sb.append('\n');
        }
        if (sorted.size() > top) {
            sb.append("  ... ").append(sorted.size() - top).append(" more groups\n");
        }
        return sb.toString();
    }

    // --- best-effort interrupt ---

    public record InterruptResult(List<String> matched, List<String> skippedCritical, boolean dryRun, boolean allowed) {
    }

    /**
     * Cooperatively interrupts live threads whose name matches {@code pattern} (treated as a regex
     * when valid, otherwise a case-insensitive substring). Never touches the calling thread, the
     * main server thread, fork-owned pools, Netty, the scheduler, or JVM/system threads — those are
     * collected into {@code skippedCritical} for transparency.
     *
     * @param dryRun when true, only reports what <i>would</i> be interrupted; nothing is touched.
     * @return the matched (and, when not a dry run, interrupted) thread names plus skipped critical
     *         matches. {@code allowed} is false when interrupts are disabled in the config.
     */
    public static InterruptResult interrupt(String pattern, boolean dryRun) {
        final boolean allowed = DivineConfig.threadWatchdogAllowInterrupt;
        final List<String> matched = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();

        final Pattern regex = compile(pattern);
        final String needle = pattern.toLowerCase(Locale.ROOT);
        final Thread self = Thread.currentThread();

        for (Thread t : new TreeMap<>(byName(Thread.getAllStackTraces().keySet())).values()) {
            final String name = t.getName();
            final boolean hit = regex != null ? regex.matcher(name).find()
                : name.toLowerCase(Locale.ROOT).contains(needle);
            if (!hit) {
                continue;
            }
            if (t == self || isCritical(name)) {
                skipped.add(name);
                continue;
            }
            matched.add(name);
            if (allowed && !dryRun) {
                t.interrupt();
            }
        }
        return new InterruptResult(matched, skipped, dryRun, allowed);
    }

    private static boolean isCritical(String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        for (String prefix : CRITICAL_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static Pattern compile(String pattern) {
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return null; // fall back to substring match
        }
    }

    /** Stable ordering for deterministic reporting. */
    private static Map<String, Thread> byName(Iterable<Thread> threads) {
        final Map<String, Thread> m = new HashMap<>();
        int dedup = 0;
        for (Thread t : threads) {
            m.put(t.getName() + ' ' + (dedup++), t);
        }
        return m;
    }
}
