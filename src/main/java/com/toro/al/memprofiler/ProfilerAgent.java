package com.toro.al.memprofiler;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point. Bootstraps the profiler before {@code main()} runs.
 *
 * <pre>
 *   -javaagent:java-memory-profiler-1.0.0.jar
 *   -javaagent:java-memory-profiler-1.0.0.jar=intervalMs=250,jsonl=C:/tmp/heap.jsonl,port=9090,openBrowser=false
 *   -javaagent:java-memory-profiler-1.0.0.jar=exportHtml=C:/tmp/profile.html,exportPng=C:/tmp/profile.png
 * </pre>
 *
 * Agent args are comma-separated {@code key=value} pairs. Keys mirror the
 * fluent methods on {@link MemoryProfiler.Config}:
 *
 * <ul>
 *   <li>{@code intervalMs}   — background sampler interval (default 500)</li>
 *   <li>{@code maxPoints}    — in-memory buffer size (default 20000)</li>
 *   <li>{@code port}         — fixed live-UI port (default auto)</li>
 *   <li>{@code jsonl}        — write all data points to this path</li>
 *   <li>{@code jsonlMode}    — {@code overwrite} (default) or {@code append}</li>
 *   <li>{@code skipCallers}  — class-name prefixes to skip for mark locations, separated by {@code ;}</li>
 *   <li>{@code keepUiAfterStop} — seconds to keep the UI up after the run, then exit (default: until killed)</li>
 *   <li>{@code openBrowser}  — open default browser at startup (default true)</li>
 *   <li>{@code startServer}  — run the live HTTP server (default true)</li>
 *   <li>{@code gcListener}   — subscribe to JVM GC notifications (default true)</li>
 *   <li>{@code heapDumpDir}  — directory for .hprof heap dumps (default java.io.tmpdir)</li>
 *   <li>{@code heapDumpOpener} — how to open dumps: system, visualvm, or intellij (default system)</li>
 *   <li>{@code heapDumpOpenerPath} — path to the opener binary (default auto-detect)</li>
 *   <li>{@code sourceRoots}  — source roots, separated by {@code ;} (Open in IDE, HTML snippets)</li>
 *   <li>{@code title}        — chart title for exports</li>
 *   <li>{@code subtitle}     — chart subtitle for exports</li>
 *   <li>{@code exportHtml}   — write an interactive HTML export here when the JVM exits</li>
 *   <li>{@code exportPng}    — write a PNG of the whole run here when the JVM exits</li>
 *   <li>{@code png.<option>} — PNG options, e.g. {@code png.includeTable=true,png.theme=dark,png.width=1600}</li>
 *   <li>{@code exportMaxSamples} — SAMPLE points kept in HTML exports (default 20000)</li>
 * </ul>
 */
public final class ProfilerAgent {

  private static volatile Instrumentation instrumentation;

  private ProfilerAgent() {}

  public static void premain(String agentArgs, Instrumentation inst) {
    instrumentation = inst;
    startFromArgs(agentArgs);
  }

  public static void agentmain(String agentArgs, Instrumentation inst) {
    instrumentation = inst;
    startFromArgs(agentArgs);
  }

  /** Returns the Instrumentation instance if running as an agent, null otherwise. */
  public static Instrumentation instrumentation() { return instrumentation; }

  private static void startFromArgs(String agentArgs) {
    MemoryProfiler.Config cfg = MemoryProfiler.Config.defaults();
    boolean autoGcThreshold = false;
    long autoGcThresholdMB = 0;
    String exportPng = null;
    PngOptions pngOptions = PngOptions.defaults();
    if (agentArgs != null && agentArgs.length() > 0) {
      for (String pair : agentArgs.split(",")) {
        int eq = pair.indexOf('=');
        if (eq <= 0) continue;
        String k = pair.substring(0, eq).trim();
        String v = pair.substring(eq + 1).trim();
        if ("intervalMs".equals(k))      cfg.intervalMs(Long.parseLong(v));
        else if ("maxPoints".equals(k))  cfg.maxPoints(Integer.parseInt(v));
        else if ("port".equals(k))       cfg.port(Integer.parseInt(v));
        else if ("jsonl".equals(k))      cfg.jsonl(v);
        else if ("jsonlMode".equals(k))  cfg.jsonlMode("append".equalsIgnoreCase(v)
            ? MemoryProfiler.JsonlMode.APPEND : MemoryProfiler.JsonlMode.OVERWRITE);
        else if ("skipCallers".equals(k)) cfg.skipCallerPrefixes(v.split(";"));
        else if ("keepUiAfterStop".equals(k)) cfg.keepUiAfterStop(java.time.Duration.ofSeconds(Long.parseLong(v)));
        else if ("subtitle".equals(k))   cfg.subtitle(v);
        else if ("openBrowser".equals(k)) cfg.openBrowser(Boolean.parseBoolean(v));
        else if ("startServer".equals(k)) cfg.startServer(Boolean.parseBoolean(v));
        else if ("gcListener".equals(k)) cfg.gcListener(Boolean.parseBoolean(v));
        else if ("autoGcNearPeak".equals(k)) cfg.autoGcNearPeak(Boolean.parseBoolean(v));
        else if ("autoGcThreshold".equals(k)) autoGcThreshold = Boolean.parseBoolean(v);
        else if ("autoGcThresholdMB".equals(k)) autoGcThresholdMB = Long.parseLong(v);
        else if ("heapDumpDir".equals(k)) cfg.heapDumpDir(v);
        else if ("heapDumpOpener".equals(k)) cfg.heapDumpOpener(v);
        else if ("heapDumpOpenerPath".equals(k)) cfg.heapDumpOpener(cfg.heapDumpOpener, v);
        else if ("sourceRoots".equals(k)) cfg.sourceRoots(v.split(";"));
        else if ("title".equals(k))      cfg.title(v);
        else if ("exportHtml".equals(k)) cfg.exportHtmlOnStop(v);
        else if ("exportPng".equals(k))  exportPng = v;
        else if ("exportMaxSamples".equals(k)) cfg.exportMaxSamples(Integer.parseInt(v));
        else if (k.startsWith("png.")) pngOptions.set(k.substring(4), v);
      }
    }
    if (autoGcThreshold) cfg.autoGcThreshold(true, autoGcThresholdMB);
    if (exportPng != null) cfg.exportPngOnStop(exportPng, pngOptions);
    MemoryProfiler.start(cfg);
    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
      public void run() { try { MemoryProfiler.shutdown(); } catch (Throwable ignored) {} }
    }, "mem-profiler-shutdown"));
  }
}
