# Java Memory Profiler

A lightweight, in-process heap profiler for Java apps. Unlike external profilers,
this tool is designed to be **driven from your code** — you decide exactly where
to trigger GC, drop markers, measure scopes, and capture heap dumps, giving you
precise, repeatable measurements at the points that matter.

> **Disclaimer:** This is a vibe-coded project — but highly functional and reliable.

![Live web UI overview](screenshots/overview.png)

> **📂 [See the full Demo](src/main/java/com/toro/al/memprofiler/Demo.java)** — a comprehensive working example that exercises every feature.
>
> ```
> java -cp java-memory-profiler-1.0.0.jar com.toro.al.memprofiler.Demo
> java -jar java-memory-profiler-1.0.0.jar --export docs   # headless: writes docs/demo-profile.html and .png
> ```

- **Continuous sampling** — a daemon thread records heap usage every N ms.
- **GC notifications** — every JVM garbage collection is captured as a `GC` event (name, cause, pause duration, bytes reclaimed) via `GarbageCollectorMXBean` notifications, so nothing is missed between samples.
- **Code markers** — call `MemoryProfiler.mark("label")` at any point to run a GC and drop a labeled marker into the timeline.
- **Scopes** — wrap a block in `MemoryProfiler.scope("label")` to record `SCOPE_START` / `SCOPE_END` with the heap delta and wall-clock duration.
- **`@Profile` annotation** — annotate interface methods and wrap the impl in `ProfiledProxy` to get scope markers without touching the method body.
- **Heap dumps** — record `.hprof` heap dumps from code (`MemoryProfiler.heapDump("name")`) or from the web UI with one click. Configure an opener to launch dumps in VisualVM, IntelliJ, or the OS default handler.
- **Java agent** — attach with `-javaagent:...` to start the profiler before `main()` runs, zero code changes.
- **Live web UI** — real-time line chart, marker overlays, event list, click-to-open source.
- **JSONL output** — every data point is appended to an optional `.jsonl` file for post-mortem analysis.
- **Mark annotations** — numbered badges on marks, scope bands, reference lines and a marks table, so a chart reads without hovering.
- **Export interactive HTML** — one self-contained `.html` file of the run that opens offline (from disk, email or a Jira attachment) with zoom, hover and the marks table. [Example](docs/demo-profile.html) (download it and open it locally).
- **Export PNG** — a static image of the whole run with the annotations, for bug reports. [Example](docs/demo-profile.png).

## API overview

The entire public surface lives on `MemoryProfiler`. You never need to reach
for a second class.

```java
// Lifecycle
MemoryProfiler.start();                        // defaults
MemoryProfiler.start(config);                  // prebuilt Config
MemoryProfiler.start(c -> c.intervalMs(250));  // inline Config via lambda
MemoryProfiler.stop();                         // stop sampling; UI stays up
MemoryProfiler.await();                        // block until JVM exits (keeps UI alive)
MemoryProfiler.shutdown();                     // full teardown: stop everything + close
MemoryProfiler.isRunning();                    // boolean
MemoryProfiler.get();                          // running instance, or null

// Point-in-time marks
MemoryProfiler.mark("label");                  // cheap    — no GC
MemoryProfiler.markGc("label");                // precise  — runs System.gc() first

// Scoped measurements (implement AutoCloseable)
try (Scope s = MemoryProfiler.scope("label"))   { ... }  // cheap
try (Scope s = MemoryProfiler.scopeGc("label")) { ... }  // GC at entry + exit → precise delta

// Annotation-driven scopes (interfaces only)
Iface wrapped = MemoryProfiler.wrap(Iface.class, impl);

// Heap dumps
MemoryProfiler.heapDump("after-load");             // records .hprof with given name
MemoryProfiler.heapDump();                          // auto-generated name

// Chart annotations and exports
MemoryProfiler.referenceLine(5120, "Production heap limit (5 GB)"); // dashed line, also in the JSONL
MemoryProfiler.title("Cache loader: full load");    // chart title for exports
MemoryProfiler.exportHtml("build/profile.html");    // self-contained, interactive, offline
MemoryProfiler.exportPng("build/profile.png");      // static image of the whole run
MemoryProfiler.exportPng("build/peak.png", PngOptions.defaults().fromMark("5").toMark("8").includeTable(true));
```

Every method is safe to call before `start()` or after `stop()`/`shutdown()` — marks/scopes
become no-ops instead of throwing. The exceptions are `exportHtml` / `exportPng`, which
work while running and after `stop()`, but throw `IllegalStateException` after
`shutdown()` (use `exportHtmlOnStop` / `exportPngOnStop` to export on the way out).

### Lifecycle: `stop` vs `shutdown` vs `await`

| Method       | Sampling | GC listener | Web UI | Data store |
|--------------|----------|-------------|--------|------------|
| `stop()`     | Stopped  | Stopped     | **Running** | Flushed |
| `shutdown()` | Stopped  | Stopped     | Stopped | Closed  |

`stop()` ends profiling but keeps the web server alive so you can still browse
the collected data. Call `await()` after `stop()` to block the calling thread
and prevent the JVM from exiting:

```java
MemoryProfiler.stop();    // profiling ends, data flushed
MemoryProfiler.await();   // blocks here — UI stays up until Ctrl-C
```

`shutdown()` tears everything down and releases all resources. This is what the
Java agent's shutdown hook calls.

### Configuration

`Config` is a fluent builder. Keys match the agent args (next section) 1:1, so
you only learn one vocabulary.

```java
MemoryProfiler.start(MemoryProfiler.Config.defaults()
    .intervalMs(250)                 // sampler tick (default 500)
    .maxPoints(50_000)               // ring-buffer capacity (default 20_000)
    .port(9090)                      // fixed UI port (default: pick a free one)
    .jsonl("C:/tmp/heap.jsonl")      // append every data point to disk
    .openBrowser(false)              // default true
    .startServer(true)               // default true; set false for headless
    .gcListener(true)                // default true; set false to skip GC notifications
    .autoGcNearPeak(true)            // auto-GC when heap nears observed peak (default false)
    .autoGcThreshold(true, 1024)     // auto-GC when heap exceeds threshold in MB (default false)
    .sourceRoots("src/main/java",    // resolve class locations to files for "Open in IDE"
                 "src/test/java")    //   and source snippets in HTML exports
    .forceGc(3, 80)                  // System.gc() loop used by markGc/scopeGc
    .title("Cache loader")           // chart title for exports (default: process name)
    .exportHtmlOnStop("build/profile.html")   // written on stop() and shutdown()
    .exportPngOnStop("build/profile.png")     // optional PngOptions as 2nd argument
    .exportMaxSamples(20_000)        // HTML exports: SAMPLE points kept (min/max downsampling)
    .exportSourceLines(6));          // HTML exports: lines of source around each mark
```

#### Heap dump configuration

```java
MemoryProfiler.start(c -> c
    .heapDumpDir("C:/tmp/dumps")               // where .hprof files are saved (default: java.io.tmpdir)
    .heapDumpOpener("visualvm",                // "system", "visualvm", or "intellij"
        "C:/libs/visualvm_221/bin/visualvm.exe") // optional path to the opener binary
);
```

The opener controls what happens when you click a dump in the UI or call
`heapDumper.open(path)`:

| Opener      | Behaviour                                                                |
|-------------|--------------------------------------------------------------------------|
| `system`    | Uses `Desktop.open()` — opens with whatever the OS associates with `.hprof`. |
| `visualvm`  | Launches VisualVM with `--openfile`. Auto-detects `jvisualvm` on PATH or in `JAVA_HOME`, or use the explicit binary path. |
| `intellij`  | Launches IntelliJ IDEA with the file path. Works best when `.hprof` is associated with IntelliJ internally. Alternatively, use `system` and set IntelliJ as the default handler for `.hprof` files in Windows. |

The lambda form is usually shorter:

```java
MemoryProfiler.start(c -> c.intervalMs(100).jsonl("heap.jsonl"));
```

### Marks — `mark` vs `markGc`

A mark records a single labeled data point with the current heap reading.

| Call                 | Runs `System.gc()`? | When to use                                              |
|----------------------|---------------------|----------------------------------------------------------|
| `mark("label")`      | No                  | Tag events in hot paths: per-request, per-iteration, etc. Cheap. |
| `markGc("label")`    | Yes (configurable)  | Checkpoints where you want a *retained-memory* reading (before/after a load). |

```java
MemoryProfiler.markGc("before load");
loadConfig();
MemoryProfiler.markGc("after load");    // delta between the two = real retained growth

for (Request r : stream) {
  MemoryProfiler.mark("req " + r.id);   // cheap — tagging only
  handle(r);
}
```

### Scopes — `scope` vs `scopeGc`

A scope pairs a `SCOPE_START` with a `SCOPE_END`, and the end carries the
heap delta and wall-clock duration.

| Call                  | Runs `System.gc()`? | When to use                                          |
|-----------------------|---------------------|------------------------------------------------------|
| `scope("label")`      | No                  | Always-on instrumentation, tight loops. Delta reflects raw allocation/retention at the moment of exit. |
| `scopeGc("label")`    | Yes, at entry and at exit | Debugging leaks or sizing components. Delta reflects what the scope *retained*, not allocation churn. |

```java
try (var s = MemoryProfiler.scope("handle-request")) {
  handleRequest(req);
}

try (var s = MemoryProfiler.scopeGc("load-index")) {   // forces GC at start + end
  loadBigIndex();
}
```

### Annotations — `@Profile` + `wrap`

`@Profile` marks an interface method as a scope. Wrap the impl with
`MemoryProfiler.wrap(...)` so each call gets a scope automatically.

```java
public interface ImportJob {
  @Profile("parse-feed")                    // → scope("parse-feed")
  void parse();

  @Profile(value = "write-db", gc = true)   // → scopeGc("write-db")
  void write();

  void housekeeping();                      // no annotation → passes through, not profiled
}

ImportJob job = MemoryProfiler.wrap(ImportJob.class, new ImportJobImpl());
job.parse();   // SCOPE_START + SCOPE_END with delta + duration
job.write();   // ditto, plus System.gc() at both ends
```

Limitations:

- **Interfaces only.** `java.lang.reflect.Proxy` can't proxy concrete classes.
  For a class with no interface, call `scope`/`scopeGc` directly in the method body.
- **The wrap call is required.** The annotation is just metadata; without
  `wrap` nothing intercepts the invocation.

### Java agent (no code changes)

```
java -javaagent:java-memory-profiler-1.0.0.jar=intervalMs=250,jsonl=C:/tmp/heap.jsonl,port=9090,openBrowser=false ^
     -cp your-app.jar com.example.Main
```

Agent args are comma-separated `key=value` pairs. **Keys match `Config`
method names 1:1.**

| Key            | Default | Meaning                                                    |
|----------------|---------|------------------------------------------------------------|
| `intervalMs`   | `500`   | Background sampler interval (ms).                          |
| `maxPoints`    | `20000` | In-memory ring-buffer capacity.                            |
| `port`         | auto    | Fixed port for the live UI.                                |
| `jsonl`        | none    | Append all data points to this `.jsonl` file.              |
| `openBrowser`  | `true`  | Open the default browser at startup.                       |
| `startServer`  | `true`  | Run the embedded HTTP server.                              |
| `gcListener`   | `true`  | Subscribe to `GarbageCollectorMXBean` notifications.       |
| `autoGcNearPeak` | `false` | Trigger `System.gc()` when heap nears the observed peak. |
| `autoGcThreshold` | `false` | Trigger `System.gc()` when heap exceeds a fixed MB threshold. |
| `autoGcThresholdMB` | —   | Threshold in MB for `autoGcThreshold`.                     |
| `sourceRoots`  | none    | Source roots separated by `;` ("Open in IDE", snippets in HTML exports). |
| `title`        | process | Chart title for exports.                                   |
| `exportHtml`   | none    | Write an interactive HTML export here when the JVM exits.  |
| `exportPng`    | none    | Write a PNG of the whole run here when the JVM exits.      |
| `png.<option>` | —       | Any [PNG option](#png-options), e.g. `png.includeTable=true,png.theme=dark`. |
| `exportMaxSamples` | `20000` | SAMPLE points kept in HTML exports.                    |
| `heapDumpDir`  | tmpdir  | Directory where `.hprof` heap dumps are saved.             |
| `heapDumpOpener` | `system` | How to open dumps: `system`, `visualvm`, or `intellij`.  |
| `heapDumpOpenerPath` | auto | Path to the opener binary (VisualVM / IntelliJ).       |

```
java -javaagent:java-memory-profiler-1.0.0.jar=exportHtml=C:/tmp/profile.html,exportPng=C:/tmp/profile.png,png.includeTable=true ^
     -cp your-app.jar com.example.Main
```

A shutdown hook calls `shutdown()` to flush the JSONL file, write the
`exportHtml` / `exportPng` files and stop the server on JVM exit. When
attached, you can still call `MemoryProfiler.mark/scope/...` from code — the
agent just bootstraps the singleton early.

## Live web UI

Opens at the URL printed on startup (`http://localhost:<port>/`).

![Chart with markers and scopes](screenshots/chart-events.png)
- The line chart shows the used-heap timeline, updated every 500 ms.
- Markers (amber), scope starts (green), scope ends (red), and GC events
  (blue) appear as vertical lines on the chart and as entries in the
  right-hand event list.
- **Follow** keeps the chart scrolling with live data; uncheck it to freeze
  the view window while you inspect.
- **mark** drops an ad-hoc marker from the browser. Useful when you want to
  tag an external event (a request you just sent, a UI action).
- **GC** triggers `System.gc()` remotely.
- **download jsonl** streams the JSONL file back to the browser.
- Click any point or event to see its full details (timestamp, thread,
  location, delta, duration). The **open in IDE** link asks the JVM's
  desktop handler to open the source file. The link only works when you
  configure source roots so the profiler can map a class location like
  `com.example.Import.parse:88` to a real file:

  ![Event details and filters](screenshots/events-panel.png)

  ```java
  MemoryProfiler.start(c -> c.sourceRoots(
      "src/main/java",
      "src/test/java"));
  ```
- **Range select** — Shift+Click two points on the chart (or toggle the
  Range Select tool in the left panel) to analyze a range: memory delta,
  min/max/avg used, duration, and event counts within the window.

  ![Range selection](screenshots/range-select.png)

- **Heap dumps** — click "Record Heap Dump" in the left panel to capture an
  `.hprof` file immediately. A badge shows the count of recorded dumps;
  click it to open a dropdown listing all dumps. Click any dump to open it
  in the configured tool (VisualVM, IntelliJ, or the OS default).

  ![Heap dumps panel](screenshots/heap-dumps.png)

## Mark annotations

Marks are easier to discuss when they have numbers. The live chart (the
**marks** toggle in the chart's control bar), the HTML export and the PNG
export all draw the same annotations with the same rules:

- **Badges.** Every `MARK`, `SCOPE_START` and `SCOPE_END` is a dot at its
  `usedMB` with a numbered badge above it. A label that starts with
  `<number>. ` shows that number: `markGc("7. DB cache: all rows in one batch (peak)")`
  gets badge **7**. Numbers may repeat (several `6.` progress marks each get
  their own **6** badge). Points without a number are numbered in order,
  starting after the highest explicit number in the run, so the two never
  share a badge. (`"7.5 GB loaded"` is not numbered: the dot must be followed
  by a space.)
- **Collision avoidance.** Badges are placed in time order. A badge that
  would touch an earlier one moves up one slot at a time and gets a thin
  leader line to its dot; at the top of the plot it tries the slots below;
  if nothing is free it is hidden (the PNG key says how many). Badges never
  overlap each other or the reference-line labels, and never leave the plot.
- **Marks table.** `#`, label (without the number prefix), retained MB,
  delta to the previous row, elapsed seconds, `Class.method:line` and thread.
  In the browser, clicking a row selects the point and centres the chart on it.
- **Scope bands.** Each `scope()` / `scopeGc()` pair is a light, neutral band
  across the plot with its label above the plot. Titles of narrow, adjacent
  bands go on two alternating rows; a title that does not fit is shortened
  with "…" or left out. Titles never overlap.
- **Reference lines.** A dashed line for the JVM max heap (or an "off scale"
  note when it is far above the data) and one per
  `MemoryProfiler.referenceLine(mb, label)`. Reference lines are stored as
  `REFERENCE` points, so exports made later from the JSONL file show them.

The rules live in `Annotations.java` (PNG) and `web/annotations.js` (browser),
which mirror each other. `markGc` readings are taken after a forced full GC,
which is what the PNG key calls "retained heap"; plain `mark` readings include
garbage.

## Export interactive HTML

One self-contained `.html` file that reproduces the viewer for a finished (or
running) profile, offline: no server and no network access. Double-click it,
attach it to an email or a Jira ticket. **[Example generated from the demo](docs/demo-profile.html)**
(download it and open it locally; GitHub shows the source).

What the file contains:

- The whole run, initially zoomed to fit, with zoom (wheel), pan (drag),
  crosshair, hover card and a **reset zoom** button.
- The Summary, Events, Selected and range panels working on the embedded data.
- Mark badges, scope bands, reference lines and the marks table.
- A header with the title, process, PID, JVM max heap, start time, duration,
  profiler version and export time.
- Light and dark themes: the file follows the OS setting; the header toggle
  overrides it.
- Optional **source snippets**: when `sourceRoots` resolve a mark's source
  file, ±6 lines around the mark are embedded and shown in the hover card and
  the Selected panel.
- Live-only controls (GC, add mark, auto-GC, heap dump, open in IDE, object
  layout) are hidden or disabled with a tooltip that says the page is a snapshot.

| Entry point | Usage |
|-------------|-------|
| UI          | **Export HTML** button in the header: downloads `profile-<process>-<yyyyMMdd-HHmmss>.html` |
| HTTP        | `GET /export/html` (sent as an attachment) |
| Java        | `MemoryProfiler.exportHtml("build/profile.html")`, while running or after `stop()` |
| Config      | `.exportHtmlOnStop("build/profile.html")` writes it on `stop()` and `shutdown()` |
| Agent       | `-javaagent:java-memory-profiler.jar=exportHtml=C:/tmp/profile.html` |
| CLI (offline) | `java -jar java-memory-profiler.jar export --jsonl run.jsonl --html out.html` |

CLI options for HTML:

| Option | Default | Meaning |
|--------|---------|---------|
| `--jsonl <file>` | required | JSONL written with `Config.jsonl(...)` or the `jsonl=` agent argument |
| `--run last\|all\|<n>` | `last` | A JSONL file can hold several appended runs; a new run starts where `index` goes back to 0. `all` joins them (renumbered). `--list-runs` prints them. |
| `--title <text>` / `--subtitle <text>` | file name | Header text |
| `--source-roots <a;b>` | none | Roots for embedded source snippets |
| `--source-lines <n>` | `6` | Lines of context above and below each mark |
| `--max-samples <n>` | `20000` | SAMPLE points kept |

**How it is built.** At export time each ES module reachable from
`web/app.js` is read from the jar, its relative imports (`'./state.js'`) are
rewritten to bare names (`'@memprofiler/web/state.js'`), and it is embedded
as a `data:text/javascript;base64,…` URL in an import map. Vue 3.4.38 is
vendored in the jar (`web/vendor/vue.esm-browser.prod.js`, MIT, with its
licence header and `LICENSE-vue.txt`) and mapped the same way; the CSS is
inlined. The live UI is unchanged: it still loads the separate module files
and Vue from unpkg. The profile is embedded as
`<script type="application/json" id="profile-data">` with every `<`, `>` and
`&` inside strings written as `\u003c` etc., so labels such as `</script>`
stay text. `api.js` reads it as `window.__PROFILE__` and never polls.

**Size.** Every non-SAMPLE point is kept. When there are more SAMPLE points
than `exportMaxSamples`, they are split into equal buckets and each bucket
keeps its lowest and highest reading (plus the first and last sample), so
peaks survive. A 10-minute run sampled every 250 ms is under 1 MB. Two exports
of the same data are byte-identical except for `exportedAt`.

## Export PNG

A static image of the **whole run** (or a time range), rendered in Java2D,
so the API, the CLI and CI produce the same image without a browser.

![PNG export of the demo](docs/demo-profile.png)

The image has the title and subtitle, a y axis in MB or GB with round ticks,
an elapsed `m:ss` x axis (or wall-clock time), the heap line with a light
fill (each output pixel column keeps its first, last, lowest and highest
sample, so a one-sample spike still shows), mark dots and numbered badges,
scope bands with titles, reference lines, GC events as ticks under the x
axis, a one-line key, and optionally the marks table under the chart.

| Entry point | Usage |
|-------------|-------|
| UI          | **Export PNG** menu in the header: download or copy to the clipboard, for the full run or the current view, with or without the marks table. The image is rendered by the server at full resolution, not grabbed from the screen. |
| HTTP        | `GET /export/png?width=&height=&scale=&from=&to=&fromMark=&toMark=&theme=&includeTable=&title=` (`download=true` sends it as an attachment) |
| Java        | `MemoryProfiler.exportPng("heap.png")`, `MemoryProfiler.exportPng("heap.png", PngOptions.defaults().includeTable(true))` |
| Config      | `.exportPngOnStop("heap.png")` or `.exportPngOnStop("heap.png", options)` |
| Agent       | `exportPng=C:/tmp/profile.png`, plus `png.<option>=value` |
| CLI (offline) | `java -jar java-memory-profiler.jar export --jsonl run.jsonl --png out.png --include-table --from-mark 5 --to-mark 8` |

#### PNG options

| `PngOptions` / query | CLI | Default | Meaning |
|----------------------|-----|---------|---------|
| `width`        | `--width`   | `1400`  | Image width in logical pixels |
| `height`       | `--height`  | `640`   | Plot height; title, axis, key and table are added |
| `scale`        | `--scale`   | `2`     | Pixel density (the PNG is `width × scale` pixels wide) |
| `from` / `to`  | `--from` / `--to` | whole run | Epoch ms, or ms since the first point when below 10^11 |
| `fromMark` / `toMark` | `--from-mark` / `--to-mark` | — | From the first mark with this badge to the last mark with that badge (a small margin is added) |
| `theme`        | `--theme`   | `light` | `light` or `dark` |
| `includeTable` | `--include-table` | `false` | Draw the marks table under the chart |
| `title`        | `--title`   | profiler title | Chart title |
| `clockAxis` (`clock`) | `--clock` | `false` | Wall-clock `HH:mm:ss` x axis instead of elapsed `m:ss` |
| `committed`    | `--committed` | `false` | Also draw the committed heap line |
| `gcTicks`      | `--no-gc-ticks` | `true` | GC events as ticks under the x axis |

Notes:

- Before rendering, `java.awt.headless` is set to `true` unless it is already
  set or AWT is already running in the process (the host application is not
  changed then). Text uses the logical `SansSerif` font, so the JDK's default
  fonts are enough.
- The exporters read the live store in place (under its lock, without copying
  it) or stream the JSONL file. A 20,000-point run at `scale=2` renders in
  about one second and allocates about 35 MB.
- Exports cover what is in the in-memory buffer (`maxPoints`). For longer
  runs, enable `jsonl` and export from the file with the CLI.
- Output is deterministic for the same input and options.

## Object layout analyzer

The web UI includes an object layout tool that shows the exact memory layout of
any Java class — object header, fields (with JVM offsets), and padding bytes.
Access it at `http://localhost:<port>/layout.html`.

![Object layout analyzer](screenshots/object-layout.png)

There are two ways to analyze a class:

### 1. Paste source code

Paste a class definition (or just field declarations) directly into the UI.
The profiler compiles the code in-memory and analyzes the resulting class.
No agent required.

```java
// You can paste a full class:
public class Order {
  long id;
  boolean active;
  String customer;
  int quantity;
  double total;
}

// Or just field declarations (auto-wrapped in a class):
long id;
boolean active;
String customer;
```

### 2. Browse loaded classes (agent mode)

When running as a Java agent, the profiler has access to
`Instrumentation.getAllLoadedClasses()` and can list every class the JVM has
loaded. The UI provides a searchable class list — pick any class to see its
layout instantly.

This requires either:

- **Java agent** — attach with `-javaagent:java-memory-profiler-1.0.0.jar=...`
  (instrumentation is available automatically).
- **Self-attach via VM option** — if not using the agent, add
  `-Djdk.attach.allowAttachSelf=true` to your VM options so the profiler can
  obtain instrumentation at runtime without the `-javaagent` flag.

If neither is available, the class browser returns an error and only the
paste-source-code mode works.

### Layout output

For each class the analyzer reports:

| Region   | Description                                              |
|----------|----------------------------------------------------------|
| Header   | Object header (typically 12 bytes with compressed oops)  |
| Field    | Instance field with offset, size, type, declaring class  |
| Padding  | Alignment gaps inserted by the JVM                       |

Summary: shallow size, header size, total field size, total padding.

## Data model

Each data point is one JSON object:

```json
{"index":42,"timestamp":1713878400000,"usedMB":312,"committedMB":512,"maxMB":2048,
 "kind":"SCOPE_END","label":"parse-feed","location":"com.example.Import.parse:88",
 "sourceFile":"com/example/Import.java:88",
 "scopeId":"s7","deltaMB":18,"durationMs":412,"thread":"worker-1"}
```

`kind` is one of:

| Kind          | Source                                    | Notes                                                                     |
|---------------|-------------------------------------------|---------------------------------------------------------------------------|
| `SAMPLE`      | background sampler (every `interval` ms)  | heap used/committed/max at the sample instant                             |
| `MARK`        | `MemoryProfiler.mark(...)` / `markGc(...)` / UI button | `markGc` + the UI button run `System.gc()` first; plain `mark` does not  |
| `SCOPE_START` | `scope(...)` / `scopeGc(...)` entry       | baseline for the scope                                                    |
| `SCOPE_END`   | `Scope.close()`                           | fills `deltaMB` and `durationMs` relative to the matching `SCOPE_START`   |
| `GC`          | `GarbageCollectorMXBean` notification     | `label` = gcAction, `location` = `gcName / gcCause`, `deltaMB` = reclaimed, `durationMs` = pause |
| `HEAP_DUMP`   | `MemoryProfiler.heapDump(...)` / UI button | `label` = dump name, `location` = path to `.hprof` file |
| `AUTO_GC`     | `AutoGcController` (threshold or near-peak trigger) | `label` = trigger reason, `location` = `AutoGcController` |
| `REFERENCE`   | `MemoryProfiler.referenceLine(mb, label)` | `usedMB` = the line's value, `label` = its caption. Not a heap reading: it is kept out of `/data` points and the chart line, and served as `references`. Readers that do not know the kind skip it. |

Downstream tooling can tail the JSONL.

## Notes

- `System.gc()` is only a hint to the JVM. In practice all mainstream
  collectors honour it promptly, which is what makes before/after deltas
  meaningful — but scope delta values are still approximate.
- The embedded server binds to `localhost` only. Not intended for
  production / public exposure.
- The in-memory buffer is a fixed-size window. For long runs, enable JSONL
  output and tail the file.
