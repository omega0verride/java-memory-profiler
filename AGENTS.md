# Java Memory Profiler: guide for AI agents

A heap profiler that runs inside the JVM it measures and is driven from code.
You put marks and scopes where they matter, the human watches the live chart,
and you both talk about the same numbered marks. This file is `AGENTS.md` in
the repository and in the jar: `java -jar <jar> agents` prints it with the
jar's path and version. With no arguments the jar runs the demo and opens a
browser; `java -jar <jar> --help` lists the commands.

## Where the details are

This guide shows how to work with the profiler. It does not list every option:

- **Java API**: the javadoc, in the sources the jar carries. Start with the
  class comment and `Config`:
  `unzip -p <jar> META-INF/sources/com/toro/al/memprofiler/MemoryProfiler.java`
  (or `jar xf <jar> META-INF/sources`).
- **Export options**: `java -jar <jar> export --help`.
- **Agent arguments, the JSONL schema, how the chart is drawn**: the README,
  sections "Java agent" and "Data model":
  https://github.com/omega0verride/java-memory-profiler

## 1. Attach

No code changes. The live UI runs on a fixed port and the data goes to a file:

    java -javaagent:<jar>=jsonl=build/heap.jsonl,port=9090 -cp app.jar com.acme.Main

When no human is watching, add `startServer=false`. In a test, or a `main`
you control, start it from code instead (the profiler jar on the classpath):

    MemoryProfiler.start(c -> c.jsonl("build/heap.jsonl").startServer(false));
    // ... the code under investigation ...
    MemoryProfiler.shutdown();   // flushes the JSONL

## 2. Mark

- Use `MemoryProfiler.markGc("3. Import: rows loaded")` at checkpoints. It
  forces a GC first, so its reading is the retained heap. Use
  `MemoryProfiler.mark("...")` (no GC) only in hot paths: its reading
  includes garbage.
- Start labels with `<n>. ` to give the mark badge `n` on the chart, in the
  marks table and in the exports. Agree on the numbers with the human before
  the run, so you can both say "3 to 5 retained +180 MB".
- `try (MemoryProfiler.Scope s = MemoryProfiler.scopeGc("parse")) { ... }`
  records what a block retained, drawn as a band.
- Code that must build without the profiler (a library, production code)
  depends on `memprofiler-api` and imports
  `com.toro.al.memprofiler.api.MemoryProfiler`. The calls are the same, and
  they do nothing when the profiler is absent.
- While the app runs you can mark it from outside, for example around requests
  you send to it: `curl "http://localhost:9090/mark?gc=true&label=4.%20after%20upload"`.
  The human can do the same with the **mark** button in the UI.
- Marks added only for an investigation are temporary. Tell the human which
  ones you added, and remove them when you are done unless they want them kept.

## 3. Read the results

Read the JSONL with the export command, not screenshots of the UI:

    java -jar <jar> export --jsonl build/heap.jsonl --summary                       # peak, retained, GC time
    java -jar <jar> export --jsonl build/heap.jsonl --summary --segments 1:3,3:5    # retained change between marks
    java -jar <jar> export --jsonl build/heap.jsonl --html build/profile.html --png build/profile.png --include-table
    java -jar <jar> export --compare before.jsonl=before after.jsonl=fix --html build/compare.html

- The HTML file is interactive and works offline. Hand it, or the PNG, to the
  human for a bug report.
- A file can hold several runs (`jsonlMode=append`): `--list-runs`, then `--run <n>`.
- To parse the file yourself: one JSON object per line. `kind` is `META` (the
  run header) or a point kind: `SAMPLE`, `MARK`, `SCOPE_START`, `SCOPE_END`
  (with `deltaMB` and `durationMs`), `GC`, `HEAP_DUMP`, `AUTO_GC`. A run is
  the lines with its `runId`. Files from 1.0.4 and older can also hold
  `REFERENCE` points: a reference line's value, not a heap reading.

## Reading the numbers

- Compare `markGc` readings with `markGc` readings. Samples and plain marks
  include garbage.
- `System.gc()` is only a hint, so deltas are approximate. Repeat a run before
  calling a few MB a leak.
- A dashed heap line means GC stalled the sampler; no data was lost. A break
  means nothing was observed (a debugger pause, a suspended VM).
- To see *what* is retained, take a heap dump
  (`MemoryProfiler.heapDump("after-load")`, or the UI button) and open the
  `.hprof` in VisualVM or IntelliJ.
- The UI has no authentication and listens on every network interface, not
  only localhost. Keep its port closed to other machines (firewall), and use
  `startServer=false` where no human needs the UI.
