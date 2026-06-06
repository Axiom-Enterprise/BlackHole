# Axiom Diagnostics Viewer

Self-hosted web viewer for Axiom diagnostics reports — the server-side analogue of
spark's `bytebin` + `spark.lucko.me`. The `/axiommetrics` and `/axiomdebug` commands
upload a gzipped JSON report here; this service stores it under a random temporary
link and renders interactive graphs (TPS/MSPT, heap, CPU, GC, JIT, thread states,
heap histogram, and a CPU flamegraph).

## Build

```bash
./gradlew :axiom-diagnostics-viewer:shadowJar
# -> axiom-diagnostics-viewer/build/libs/axiom-diagnostics-viewer-<version>-all.jar
```

## Run

```bash
java -jar axiom-diagnostics-viewer-*-all.jar \
  --port 8080 \
  --public-url https://diag.example.com \
  --ttl-minutes 30
```

Options (flag / env var / default):

| Flag | Env | Default |
|------|-----|---------|
| `--port` | `PORT` | `8080` |
| `--public-url` | `PUBLIC_URL` | `http://localhost:<port>` |
| `--ttl-minutes` | `TTL_MINUTES` | `30` |

`--public-url` must match the externally reachable URL — it is the base of the link
handed back to players in-game.

## Endpoints

- `POST /upload` — body is the report JSON (optionally `Content-Encoding: gzip`); returns `{ "key", "url" }`.
- `GET /{key}` — interactive viewer page.
- `GET /{key}/data` — raw report JSON.
- `GET /health` — liveness + stored report count.

## Server config

In `divinemc.yml` (`diagnostics` section) point the server at this service:

```yaml
diagnostics:
  enabled: true
  viewer-url: https://diag.example.com   # this service's public URL
  profiler-duration-seconds: 30
  sampler-interval-ms: 10
  lag-spike-threshold-ms: 100.0
  heap-histogram-top-n: 50
```

## systemd unit (example)

```ini
[Unit]
Description=Axiom Diagnostics Viewer
After=network.target

[Service]
ExecStart=/usr/bin/java -jar /opt/axiom/axiom-diagnostics-viewer-all.jar --port 8080 --public-url https://diag.example.com
Restart=on-failure
User=axiom

[Install]
WantedBy=multi-user.target
```

Put it behind nginx/Caddy for TLS; reports are ephemeral (default 30-minute TTL) and
held in memory only — restarting the service drops all active links.
