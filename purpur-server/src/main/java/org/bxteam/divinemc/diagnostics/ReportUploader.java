package org.bxteam.divinemc.diagnostics;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPOutputStream;

/**
 * Serializes a {@link DiagnosticsReport} to gzipped JSON and POSTs it to the
 * self-hosted Axiom viewer's {@code /upload} endpoint, returning the temporary
 * link. All work happens off the caller's thread.
 */
public final class ReportUploader {
    private static final Gson GSON = new Gson();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private ReportUploader() {
    }

    public static final class Result {
        public final boolean ok;
        public final String link;
        public final String error;

        private Result(boolean ok, String link, String error) {
            this.ok = ok;
            this.link = link;
            this.error = error;
        }

        public static Result error(String message) {
            return new Result(false, null, message);
        }
    }

    /** Uploads asynchronously. The returned future always completes (never fails). */
    public static CompletableFuture<Result> upload(String viewerBaseUrl, DiagnosticsReport report) {
        return CompletableFuture.supplyAsync(() -> uploadBlocking(viewerBaseUrl, report));
    }

    private static Result uploadBlocking(String viewerBaseUrl, DiagnosticsReport report) {
        if (viewerBaseUrl == null || viewerBaseUrl.isBlank()) {
            return new Result(false, null, "diagnostics viewer-url is not configured");
        }
        try {
            final byte[] body = gzip(GSON.toJson(report));
            final String base = viewerBaseUrl.endsWith("/")
                ? viewerBaseUrl.substring(0, viewerBaseUrl.length() - 1)
                : viewerBaseUrl;

            final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/upload"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/octet-stream")
                .header("Content-Encoding", "gzip")
                .header("User-Agent", "Axiom-Diagnostics")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

            final HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return new Result(false, null, "viewer returned HTTP " + response.statusCode());
            }

            final JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            final String url = json.has("url") ? json.get("url").getAsString()
                : base + "/" + json.get("key").getAsString();
            return new Result(true, url, null);
        } catch (Throwable t) {
            return new Result(false, null, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static byte[] gzip(String json) throws Exception {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return baos.toByteArray();
    }
}
