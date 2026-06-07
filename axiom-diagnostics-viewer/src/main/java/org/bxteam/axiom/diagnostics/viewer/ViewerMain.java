package org.bxteam.axiom.diagnostics.viewer;

import com.google.gson.JsonObject;
import io.javalin.Javalin;
import io.javalin.http.ContentType;
import io.javalin.http.HttpStatus;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Standalone web service that receives Axiom diagnostics reports and renders
 * them as interactive graphs at a temporary link — a self-hosted analogue of
 * spark's bytebin + spark.lucko.me viewer.
 *
 * <p>Run: {@code java -jar axiom-diagnostics-viewer-all.jar [--port N]}
 *
 * <p>Config via env / system properties / args:
 * <ul>
 *   <li>{@code --port} / {@code PORT} — listen port (default 8080)</li>
 *   <li>{@code --public-url} / {@code PUBLIC_URL} — external base URL used to build the returned link
 *       (default {@code http://localhost:<port>})</li>
 *   <li>{@code --ttl-minutes} / {@code TTL_MINUTES} — link lifetime (default 30)</li>
 * </ul>
 */
public final class ViewerMain {
    private static final long MAX_UPLOAD_BYTES = 64L * 1024 * 1024; // 64 MiB decompressed cap

    /** Hard ceiling on link lifetime — reports never live longer than this. */
    private static final long MAX_TTL_MINUTES = 60;

    public static void main(String[] args) {
        final int port = intOpt(args, "--port", "PORT", 8080);
        // Links expire; never longer than one hour regardless of configuration.
        final long ttlMinutes = Math.max(1, Math.min(MAX_TTL_MINUTES, intOpt(args, "--ttl-minutes", "TTL_MINUTES", 30)));
        final String publicUrl = stripTrailingSlash(
            strOpt(args, "--public-url", "PUBLIC_URL", "http://localhost:" + port));

        final ReportStore store = new ReportStore(ttlMinutes * 60_000L);
        final String viewerHtml = loadResource("/viewer.html");

        final Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false).start(port);

        app.get("/", ctx -> ctx.contentType(ContentType.TEXT_HTML).result(landingPage(ttlMinutes)));

        app.get("/health", ctx -> {
            final JsonObject health = new JsonObject();
            health.addProperty("status", "ok");
            health.addProperty("reports", store.size());
            health.addProperty("ttlMinutes", ttlMinutes);
            ctx.contentType(ContentType.APPLICATION_JSON).result(health.toString());
        });

        app.post("/upload", ctx -> {
            final String json;
            try {
                json = readBody(ctx.bodyInputStream(), isGzip(ctx.header("Content-Encoding")));
            } catch (IOException e) {
                ctx.status(HttpStatus.BAD_REQUEST).contentType(ContentType.APPLICATION_JSON)
                    .result(errorJson("invalid body: " + e.getMessage()));
                return;
            }
            final String key = store.put(json);
            final JsonObject out = new JsonObject();
            out.addProperty("key", key);
            out.addProperty("url", publicUrl + "/" + key);
            ctx.contentType(ContentType.APPLICATION_JSON).result(out.toString());
        });

        app.get("/{key}/data", ctx -> {
            final String key = ctx.pathParam("key");
            final String json = store.get(key);
            if (json == null) {
                ctx.status(HttpStatus.NOT_FOUND).contentType(ContentType.APPLICATION_JSON)
                    .result(errorJson("report not found or expired"));
                return;
            }
            ctx.header("X-Report-Expires-At", Long.toString(store.expiresAt(key)));
            ctx.contentType(ContentType.APPLICATION_JSON).result(json);
        });

        app.get("/{key}", ctx -> {
            if (store.get(ctx.pathParam("key")) == null) {
                ctx.status(HttpStatus.NOT_FOUND).html(notFoundPage());
                return;
            }
            ctx.header("Content-Security-Policy",
                "default-src 'none'; "
                + "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; "
                + "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; "
                + "connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'");
            ctx.contentType(ContentType.TEXT_HTML).result(viewerHtml);
        });

        Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
        System.out.println("Axiom diagnostics viewer listening on :" + port + " (public: " + publicUrl
            + ", ttl: " + ttlMinutes + "m)");
    }

    private static String readBody(InputStream raw, boolean gzip) throws IOException {
        try (InputStream in = gzip ? new GZIPInputStream(raw) : raw) {
            final byte[] bytes = in.readNBytes((int) MAX_UPLOAD_BYTES);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static boolean isGzip(String encoding) {
        return encoding != null && encoding.toLowerCase().contains("gzip");
    }

    private static String landingPage(long ttlMinutes) {
        return "<!doctype html><html lang=en><head><meta charset=utf-8>"
            + "<meta name=viewport content=\"width=device-width, initial-scale=1\">"
            + "<title>Axiom Diagnostics</title><style>"
            + "body{margin:0;background:#0e1116;color:#dfe5ee;font:15px/1.6 system-ui,sans-serif;"
            + "display:flex;min-height:100vh;align-items:center;justify-content:center}"
            + ".box{max-width:560px;padding:40px;text-align:center}"
            + "h1{color:#4fd1c5;font-size:24px;margin:0 0 8px}"
            + "p{color:#8b96a5;margin:8px 0}code{background:#171c24;color:#4fd1c5;padding:2px 7px;border-radius:5px}"
            + ".pill{display:inline-block;margin-top:14px;background:#23303a;color:#4fd1c5;border-radius:999px;padding:4px 14px;font-size:13px}"
            + "</style></head><body><div class=box>"
            + "<h1>Axiom Diagnostics</h1>"
            + "<p>Self-hosted viewer for Axiom server diagnostics &mdash; TPS/MSPT, heap, CPU, GC, JIT, "
            + "per-plugin memory attribution and leak detection.</p>"
            + "<p>Run <code>/axiommetrics</code> or <code>/axiomdebug</code> in-game to generate a report; "
            + "you&rsquo;ll get a private link to open here.</p>"
            + "<div class=pill>Links expire after " + ttlMinutes + " minute" + (ttlMinutes == 1 ? "" : "s") + "</div>"
            + "</div></body></html>";
    }

    private static String notFoundPage() {
        return "<!doctype html><meta charset=utf-8><title>Expired</title>"
            + "<body style=\"font-family:sans-serif;background:#111;color:#eee;text-align:center;padding-top:20vh\">"
            + "<h1>Report not found</h1><p>This diagnostics link has expired or never existed.</p></body>";
    }

    // --- option parsing helpers ---

    private static String strOpt(String[] args, String flag, String env, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) {
                return args[i + 1];
            }
        }
        final String fromEnv = System.getenv(env);
        if (fromEnv != null) {
            return fromEnv;
        }
        final String fromProp = System.getProperty(env);
        return fromProp != null ? fromProp : def;
    }

    private static int intOpt(String[] args, String flag, String env, int def) {
        try {
            return Integer.parseInt(strOpt(args, flag, env, Integer.toString(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String loadResource(String path) {
        try (InputStream in = ViewerMain.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + path, e);
        }
    }

    private static String errorJson(String message) {
        final JsonObject json = new JsonObject();
        json.addProperty("error", message);
        return json.toString();
    }
}
