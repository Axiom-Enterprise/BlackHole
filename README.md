<div align="center">

## Axiom

[![MIT License](https://img.shields.io/github/license/PurpurMC/Purpur?&logo=github)](LICENSE)
[![Join us on Discord](https://discord.com/api/guilds/685683385313919172/widget.png?style=shield)](https://purpurmc.org/discord)

Axiom is a high-performance Minecraft server built on top of
[Purpur](https://github.com/PurpurMC/Purpur) (which is itself a fork of
[Paper](https://github.com/PaperMC/Paper)). It keeps every Purpur, Paper, Spigot
and Bukkit feature and API, then layers on an aggressive set of **multithreading
and async patches** plus a built-in **diagnostics suite** for profiling a live
server.

If you run a busy server and the main thread is your bottleneck, Axiom is for
you. It is a **drop-in replacement** — same plugins, same worlds, same API.

</div>

## What Axiom adds over Purpur

Axiom's value is performance. The headline work moves expensive jobs **off the
main tick thread** or **across multiple threads**:

| Area | Patch | What it does |
|------|-------|--------------|
| World ticking | Parallel world ticking (SparklyPaper) | Each loaded world ticks on its own thread. |
| Chunk ticking | Regionized chunk ticking (DivineMC) | Independent regions inside one world tick in parallel. |
| Chunk I/O | Async chunk sending | Chunk packets are built and sent off-thread. |
| Pathfinding | Petal async pathfinding | Mob path computation runs on a worker pool. |
| Entity tracking | Petal multithreaded tracker | Entity tracking is spread across threads. |
| Mob spawning | Pufferfish async natural spawn | Natural spawn candidate search runs async. |
| AI targeting | Leaf async target finding | `nearest target` scans run off-main. |
| Player data | Async playerdata save + NBT compression | Saves and gzip happen on a background executor. |
| Region files | Linear region file format | Denser, faster region storage. |
| Anti-Xray | Raytrace anti-xray + off-thread obfuscation | Chunk obfuscation built off the tick thread. |
| Networking | Configurable keep-alive interval/limit | Tune keep-alive timing per server. |
| Diagnostics | `/axiommetrics`, `/axiomdebug` + web viewer | spark-style reports for TPS, heap, CPU, GC, flamegraphs. |

All of it is **config-gated** — turn on only what your workload needs.

---

## Enabling the performance features

Async features are controlled from **`divinemc.yml`** (the `async` section).
Everything is **off by default** so you opt in deliberately.

```yaml
# divinemc.yml
async:
  # Tick every world on its own thread. Biggest win on multi-world servers.
  enable-parallel-world-ticking: true
  parallel-thread-count: 4

  # Tick independent regions of a single world in parallel.
  enable-regionized-chunk-ticking: true

  # Build + flush chunk packets off the main thread.
  async-chunk-sending-enabled: true

  # Mob pathfinding on a worker pool.
  async-pathfinding: true

  # Async natural mob spawning.
  enable-async-spawning: true
  async-natural-spawn: true

  # Async AI target acquisition.
  async-target-finding: true
  async-target-finding-search-block: true
  async-target-finding-search-entity: true

  # Multithreaded entity tracker.
  multithreaded-enabled: true
  multithreaded-compat-mode-enabled: false

  # Async player-data persistence.
  async-player-data-save: true
  async-player-nbt-compression: true

network:
  keep-alive-interval-seconds: 15
  keep-alive-limit-seconds: 30

region-settings:
  region-file-type: LINEAR   # or ANVIL
```

> **Recommended starting point:** enable `parallel-world-ticking` and
> `async-chunk-sending` first, measure with `/axiommetrics`, then add
> regionized ticking and the tracker once you trust the gains.

### Plugin compatibility note (important)

Under parallel/regionized ticking, **different worlds tick on different
threads at the same time**. Plugins that touch world state from the wrong
thread can race. Axiom guards core internals (entity maps, ticket lists,
trackers, etc.), but **your plugin code must stay on the owning thread**.

Use the scheduler instead of touching another world directly:

```java
// BAD under parallel ticking: World A's tick thread mutating World B.
otherEntity.teleport(loc);

// GOOD: hop onto the region/entity's owning thread first.
entity.getScheduler().run(plugin, task -> {
    entity.teleport(loc);
}, null);

// World-bound work goes on the region scheduler for that location.
Bukkit.getRegionScheduler().run(plugin, location, task -> {
    location.getBlock().setType(Material.GLOWSTONE);
});
```

If a plugin assumes a single global tick thread, run it with parallel ticking
**off** until it adopts the Folia-style scheduler API (already present via
Paper).

---

## Diagnostics

Axiom ships a self-profiling suite — think spark, but built in.

```
/axiommetrics          # snapshot: TPS, MSPT, heap, CPU, GC, threads
/axiommetrics 30       # sample for 30s, then upload a full report
/axiomdebug            # deep report incl. CPU flamegraph + heap histogram
/axiomthreads          # per-thread state dump
/axiomreload           # reload Axiom/diagnostics config
```

Reports are gzipped and pushed to the **Axiom Diagnostics Viewer**, a small
self-hosted web service (in [`axiom-diagnostics-viewer/`](axiom-diagnostics-viewer))
that renders interactive graphs and a flamegraph. Point the server at it in
`divinemc.yml`:

```yaml
diagnostics:
  enabled: true
  viewer-url: https://diag.example.com
  profiler-duration-seconds: 30
  sampler-interval-ms: 10
  lag-spike-threshold-ms: 100.0
  heap-histogram-top-n: 50
```

Build and run the viewer:

```bash
./gradlew :axiom-diagnostics-viewer:shadowJar
java -jar axiom-diagnostics-viewer/build/libs/axiom-diagnostics-viewer-*-all.jar \
  --port 8080 --public-url https://diag.example.com --ttl-minutes 30
```

See [`axiom-diagnostics-viewer/README.md`](axiom-diagnostics-viewer/README.md)
for endpoints and a systemd unit.

---

## Gameplay features (inherited from Purpur)

Axiom keeps every Purpur gameplay feature and the full Purpur API. A few you can
build on:

**Ridable mobs** — any configured mob can be ridden and steered:

```yaml
# purpur.yml
mobs:
  cow:
    ridable: true
    ridable-in-water: true
    controllable: true
```

```java
import org.purpurmc.purpur.entity.Ridable;

if (entity instanceof Ridable ridable && ridable.isRidable()) {
    ridable.addPassenger(player);
}
```

**Mobs that burn in daylight** — toggle per entity at runtime via the Purpur API:

```java
// Make any living entity burn in sunlight like an undead.
zombie.setBurnsInDaylight(true);
```

**6-row barrels / enderchests, configurable entity base attributes, villager
farming, elytra tuning, mob-griefing overrides** and much more — all driven from
`purpur.yml`. See the Purpur docs for the full catalogue.

---

## Downloads

Built jars and the downloads API are shared with the Purpur project.

* List versions: `https://api.purpurmc.org/v2/purpur`
* List builds: `https://api.purpurmc.org/v2/purpur/<version>`
* Download a build: `https://api.purpurmc.org/v2/purpur/<version>/<build>/download`
* Latest build: `https://api.purpurmc.org/v2/purpur/<version>/latest/download`

## API / Dependency information

Axiom exposes the Purpur API (which includes Paper, Spigot and Bukkit).

Maven:
```xml
<repository>
    <id>purpur</id>
    <url>https://repo.purpurmc.org/snapshots</url>
</repository>
<dependency>
    <groupId>org.purpurmc.purpur</groupId>
    <artifactId>purpur-api</artifactId>
    <version>[26.1.2.build,)</version>
    <scope>provided</scope>
</dependency>
```

Gradle:
```kotlin
repositories {
    maven("https://repo.purpurmc.org/snapshots")
}
dependencies {
    compileOnly("org.purpurmc.purpur:purpur-api:26.1.2.build.+")
}
```

## Building from source

Clone the repository (do **not** download a zip), then:

```bash
./gradlew applyAllPatches      # set up the source tree for your IDE
./gradlew build                # build api + server into */build/libs
./gradlew createMojmapBundlerJar   # build a runnable purpurclip server jar
```

`createMojmapBundlerJar` output lands in `purpur-server/build/libs`. Use
`./gradlew publishToMavenLocal` to install `purpur-api` / `purpur` to your local
Maven repo.

To add or edit a patch, see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

All patches are licensed under the MIT license unless noted in the patch header.
See [PaperMC/Paper](https://github.com/PaperMC/Paper) and
[PaperMC/paperweight](https://github.com/PaperMC/paperweight) for upstream
licensing. Axiom builds on [Purpur](https://github.com/PurpurMC/Purpur),
[DivineMC](https://github.com/BX-Team/DivineMC) and the Petal, Pufferfish, Leaf
and Moonrise projects — thanks to all of them.

[![MIT License](https://img.shields.io/github/license/PurpurMC/Purpur?&logo=github)](LICENSE)
