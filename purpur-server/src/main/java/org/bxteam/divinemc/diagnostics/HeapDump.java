package org.bxteam.divinemc.diagnostics;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes a binary HPROF heap dump to a temporary file via
 * {@link HotSpotDiagnosticMXBean#dumpHeap}. Always dumps the <em>live</em> set
 * ({@code live=true}), which forces a full GC first and only writes reachable
 * objects — smaller on disk and exactly what matters for leak analysis.
 *
 * <p>The dump is a stop-the-world operation; callers run it off the main thread.
 * The temporary file is the caller's responsibility to delete (see
 * {@link Result#cleanup()}).
 */
public final class HeapDump {
    private HeapDump() {
    }

    public static final class Result {
        public final boolean ok;
        public final Path file;        // null on failure
        public final long fileBytes;
        public final long dumpMillis;
        public final String error;     // null on success

        private Result(boolean ok, Path file, long fileBytes, long dumpMillis, String error) {
            this.ok = ok;
            this.file = file;
            this.fileBytes = fileBytes;
            this.dumpMillis = dumpMillis;
            this.error = error;
        }

        /** Best-effort delete of the temp dump file. */
        public void cleanup() {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (Throwable ignored) {
                    // temp dir cleanup is best-effort
                }
            }
        }
    }

    /**
     * Captures a live heap dump to a fresh temp file.
     *
     * @param maxBytes hard ceiling; if the produced dump exceeds it the file is
     *                 deleted and a failure {@link Result} is returned so the
     *                 caller can fall back to histogram-only attribution.
     *                 Pass {@code <= 0} to disable the ceiling.
     */
    public static Result capture(long maxBytes) {
        Path file = null;
        try {
            file = Files.createTempFile("axiom-diag-", ".hprof");
            // dumpHeap refuses to write to an existing file.
            Files.deleteIfExists(file);

            final HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            if (bean == null) {
                return new Result(false, null, 0, 0, "HotSpotDiagnosticMXBean unavailable");
            }

            final long start = System.nanoTime();
            bean.dumpHeap(file.toString(), true);
            final long dumpMillis = (System.nanoTime() - start) / 1_000_000L;

            final long size = Files.size(file);
            if (maxBytes > 0 && size > maxBytes) {
                final Path toDelete = file;
                file = null;
                Files.deleteIfExists(toDelete);
                return new Result(false, null, size, dumpMillis,
                    "heap dump " + (size / (1024 * 1024)) + " MB exceeds cap " + (maxBytes / (1024 * 1024)) + " MB");
            }
            return new Result(true, file, size, dumpMillis, null);
        } catch (Throwable t) {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (Throwable ignored) {
                    // best-effort
                }
            }
            return new Result(false, null, 0, 0, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
