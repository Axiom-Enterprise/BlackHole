package org.bxteam.axiom.diagnostics.viewer;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-memory store of uploaded reports keyed by a random URL-safe token, each
 * with a fixed TTL. Expired entries are swept periodically, giving the
 * spark-style "temporary link" behaviour.
 */
public final class ReportStore {
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final ScheduledExecutorService janitor =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "report-janitor");
            thread.setDaemon(true);
            return thread;
        });

    public ReportStore(long ttlMillis) {
        this.ttlMillis = ttlMillis;
        janitor.scheduleAtFixedRate(this::sweep, 1, 1, TimeUnit.MINUTES);
    }

    public record Entry(String json, long expiresAt) {
    }

    /** Stores JSON, returns the new key. */
    public String put(String json) {
        final byte[] buf = new byte[12];
        random.nextBytes(buf);
        final String key = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        entries.put(key, new Entry(json, System.currentTimeMillis() + ttlMillis));
        return key;
    }

    /** Returns the JSON for a key, or null if missing/expired. */
    public String get(String key) {
        final Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt() < System.currentTimeMillis()) {
            entries.remove(key);
            return null;
        }
        return entry.json();
    }

    public int size() {
        return entries.size();
    }

    /** Epoch-ms expiry for a key, or -1 if missing. */
    public long expiresAt(String key) {
        final Entry entry = entries.get(key);
        return entry == null ? -1 : entry.expiresAt();
    }

    /** Configured TTL in whole minutes. */
    public long ttlMinutes() {
        return ttlMillis / 60_000L;
    }

    private void sweep() {
        final long now = System.currentTimeMillis();
        entries.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
    }
}
