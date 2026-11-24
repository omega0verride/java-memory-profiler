package com.toro.al.memprofiler;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Single entry point for the profiler. Typical use:
 *
 * <pre>
 *   MemoryProfiler.start();                                  // defaults
 *   MemoryProfiler.start(c -&gt; c.intervalMs(250).jsonl(p));   // inline config
 *
 *   MemoryProfiler.mark("request received");                 // cheap
 *   MemoryProfiler.markGc("before load");                    // GC first, precise
 *
 *   try (MemoryProfiler.Scope s = MemoryProfiler.scope("query"))   { ... }
 *   try (MemoryProfiler.Scope s = MemoryProfiler.scopeGc("query")) { ... }
 *
 *   Job j = MemoryProfiler.wrap(Job.class, new JobImpl());   // @Profile methods
 *
 *   MemoryProfiler.referenceLine(5120, "Production heap limit (5 GB)");
 *   MemoryProfiler.exportHtml("build/profile.html");         // offline, interactive
 *   MemoryProfiler.exportPng("build/profile.png");           // static image of the whole run
 *
 *   MemoryProfiler.stop();
 * </pre>
 */
public final class MemoryProfiler {

  /** What {@link Config#jsonl(String)} does with an existing file. */
  public enum JsonlMode {
    /** Add this run to the end of the file (one file, several runs; see {@code export --list-runs}). */
    APPEND,
    /** Replace the file (default). */
    OVERWRITE
  }

  /**
   * Fluent config. Build one and pass to {@link #start(Config)}, or use the
   * {@link #start(Consumer)} overload for inline configuration.
   */
  public static final class Config {
    long intervalMs = 500;
    int maxPoints = 20000;
    int port = 0;
    String jsonlPath = null;
    JsonlMode jsonlMode = JsonlMode.OVERWRITE;
    String[] skipCallerPrefixes = new String[0];
    long keepUiAfterStopMs = -1;
    boolean openBrowser = true;
    boolean startServer = true;
    boolean gcListener = true;
    int gcPasses = 3;
    long gcPauseMs = 80;
    boolean autoGcNearPeak = false;
    boolean autoGcThreshold = false;
    long autoGcThresholdMB = 0;
    String[] sourceRoots = new String[0];
    String heapDumpDir = System.getProperty("java.io.tmpdir");
    String heapDumpOpener = null;   // "system", "visualvm", or "intellij"
    String heapDumpOpenerPath = null; // custom binary path for the opener
    String sourceOpener = null;     // "auto", "intellij", "vscode", "system" or a command; null = property, else auto
    String sourceOpenerPath = null; // launcher binary for intellij / vscode
    String title = null;
    String subtitle = null;
    String exportHtmlOnStop = null;
    String exportPngOnStop = null;
    PngOptions exportPngOptions = null;
    int exportMaxSamples = 20000;
    int exportSourceLines = 6;

    /** A config with every option at its default; see {@link #defaults()}. */
    public Config() {}

    /**
     * A config with every option at its default; the same as {@code new Config()}.
     *
     * @return a new config
     */
    public static Config defaults() { return new Config(); }

    /**
     * How often the sampler thread reads the heap (default 500 ms). Marks,
     * scopes and GC events are recorded when they happen, independent of this
     * interval.
     *
     * @param v the interval in milliseconds
     * @return this config
     */
    public Config intervalMs(long v)      { this.intervalMs = v; return this; }

    /**
     * Points kept in memory for the live UI and exports (default 20,000). Past
     * that, the oldest points are dropped; the JSONL file (see {@link #jsonl})
     * always keeps every point.
     *
     * @param v the maximum number of points in memory
     * @return this config
     */
    public Config maxPoints(int v)        { this.maxPoints = v; return this; }

    /**
     * Port of the live web UI (default 0: a free port, printed at start).
     *
     * @param v the port, or 0 for a free one
     * @return this config
     */
    public Config port(int v)             { this.port = v; return this; }

    /**
     * Also writes every data point to this JSONL file as it is recorded (default:
     * none, memory only). The file can be exported later with
     * {@code java -jar java-memory-profiler.jar export --jsonl <file>}. Whether
     * an existing file is replaced or appended to is set by {@link #jsonlMode}.
     *
     * @param path the {@code .jsonl} file, or null for none
     * @return this config
     */
    public Config jsonl(String path)      { this.jsonlPath = path; return this; }

    /**
     * {@link JsonlMode#OVERWRITE} (default) replaces an existing JSONL file;
     * {@link JsonlMode#APPEND} adds the run to it. Every run starts with a
     * {@code META} line and every point carries the run id, so appended runs are
     * always told apart.
     *
     * @param mode what to do with an existing file (null = {@code OVERWRITE})
     * @return this config
     */
    public Config jsonlMode(JsonlMode mode) { this.jsonlMode = mode == null ? JsonlMode.OVERWRITE : mode; return this; }

    /**
     * Extra class-name prefixes to skip when a mark, scope or heap dump records
     * its caller location, for helpers between your code and the profiler (for
     * example a reflection bridge, {@code "com.acme.util.ProfilerHooks"}).
     * Adds to the system property {@code memprofiler.skipCallers} (comma or
     * semicolon separated) and the {@code skipCallers} agent argument.
     *
     * @param prefixes class-name prefixes; frames whose class starts with one are skipped
     * @return this config
     */
    public Config skipCallerPrefixes(String... prefixes) {
      this.skipCallerPrefixes = prefixes == null ? new String[0] : prefixes.clone();
      return this;
    }

    /**
     * After {@link #stop()}, keep serving the UI for this long, then shut down
     * (so the JVM can exit normally). The on-stop exports are written before the
     * wait. {@link MemoryProfiler#await()} returns when the time is up.
     *
     * @param d how long to keep the UI; null or negative = until {@code shutdown()} (default)
     * @return this config
     */
    public Config keepUiAfterStop(Duration d) {
      this.keepUiAfterStopMs = d == null || d.isNegative() ? -1 : d.toMillis();
      return this;
    }

    /**
     * Opens the live UI in the default browser on {@link MemoryProfiler#start() start} (default true).
     *
     * @param v whether to open the browser
     * @return this config
     */
    public Config openBrowser(boolean v)  { this.openBrowser = v; return this; }

    /**
     * Runs the live web UI (default true). With false nothing listens on a port;
     * marks, the JSONL file and the exports work the same.
     *
     * @param v whether to start the web server
     * @return this config
     */
    public Config startServer(boolean v)  { this.startServer = v; return this; }

    /**
     * Records every JVM garbage collection as a {@code GC} event from
     * {@code GarbageCollectorMXBean} notifications: collector, cause, pause and
     * bytes reclaimed (default true).
     *
     * @param v whether to listen for GC notifications
     * @return this config
     */
    public Config gcListener(boolean v)   { this.gcListener = v; return this; }

    /**
     * Enable auto-GC when heap usage approaches the observed peak (default off;
     * also a toggle in the live UI).
     *
     * @param v enable or disable
     * @return this config
     */
    public Config autoGcNearPeak(boolean v)   { this.autoGcNearPeak = v; return this; }

    /**
     * Enable auto-GC when heap usage exceeds a fixed threshold (default off;
     * also a toggle in the live UI).
     *
     * @param v  enable or disable
     * @param mb threshold in MB (ignored when disabling)
     * @return this config
     */
    public Config autoGcThreshold(boolean v, long mb) {
      this.autoGcThreshold = v;
      this.autoGcThresholdMB = mb;
      return this;
    }

    /**
     * Controls the {@code System.gc()} loop used by {@link MemoryProfiler#markGc(String) markGc}
     * and {@link MemoryProfiler#scopeGc(String) scopeGc} (default 3 passes, 80 ms apart).
     *
     * @param passes  how many times {@code System.gc()} is called
     * @param pauseMs the pause after each call, in milliseconds
     * @return this config
     */
    public Config forceGc(int passes, long pauseMs) {
      this.gcPasses = passes;
      this.gcPauseMs = pauseMs;
      return this;
    }

    /**
     * Source roots (for example {@code "src/main/java"}) used to find the file of
     * a mark: each root is searched for {@code package/path/File.java}. Relative
     * roots resolve against the working directory. Used by the live UI's
     * "Open in IDE" and "View Source" (real source instead of decompiled code)
     * and by the source snippets in HTML exports.
     *
     * @param roots source directories, searched in order
     * @return this config
     */
    public Config sourceRoots(String... roots) {
      this.sourceRoots = roots == null ? new String[0] : roots.clone();
      return this;
    }

    /**
     * Directory where heap dump ({@code .hprof}) files are saved (default {@code java.io.tmpdir}).
     *
     * @param dir the directory
     * @return this config
     */
    public Config heapDumpDir(String dir) { this.heapDumpDir = dir; return this; }

    /**
     * How the live UI opens heap dump files: {@code "system"} (OS default, the
     * default), {@code "visualvm"} or {@code "intellij"}.
     *
     * @param opener     the opener name
     * @param binaryPath optional path to the opener binary (null to auto-detect)
     * @return this config
     */
    public Config heapDumpOpener(String opener, String binaryPath) {
      this.heapDumpOpener = opener;
      this.heapDumpOpenerPath = binaryPath;
      return this;
    }

    /**
     * Shorthand for {@link #heapDumpOpener(String, String)} with the binary found automatically.
     *
     * @param opener {@code "system"}, {@code "visualvm"} or {@code "intellij"}
     * @return this config
     */
    public Config heapDumpOpener(String opener) { return heapDumpOpener(opener, null); }

    /**
     * How the live UI's "Open in IDE" opens a mark's source file at its line:
     * <ul>
     *   <li>{@code "auto"} (default): IntelliJ IDEA if its launcher is found
     *       (PATH, JetBrains Toolbox scripts, the usual install locations), else
     *       VS Code, else {@code "system"}</li>
     *   <li>{@code "intellij"}: {@code idea --line N file}</li>
     *   <li>{@code "vscode"}: {@code code --goto file:N}</li>
     *   <li>{@code "system"}: the OS default application for {@code .java} files (no line)</li>
     *   <li>any other editor as a command with {@code {file}} and {@code {line}}
     *       placeholders, for example {@code "subl {file}:{line}"}; quote a binary
     *       path that contains spaces</li>
     * </ul>
     * Without this call the system property {@code memprofiler.sourceOpener} is
     * used, so each developer can pick an IDE without a code change.
     *
     * @param opener     one of the values above
     * @param binaryPath launcher for {@code intellij} / {@code vscode} when it is not
     *                   found automatically (null to detect; system property
     *                   {@code memprofiler.sourceOpenerPath})
     * @return this config
     * @throws IllegalArgumentException for an unknown opener name
     */
    public Config sourceOpener(String opener, String binaryPath) {
      if (opener != null && !opener.contains("{file}")) SourceOpener.parseKind(opener.trim());
      this.sourceOpener = opener;
      this.sourceOpenerPath = binaryPath;
      return this;
    }

    /**
     * Shorthand for {@link #sourceOpener(String, String)} with the launcher found automatically.
     *
     * @param opener {@code "auto"}, {@code "intellij"}, {@code "vscode"}, {@code "system"},
     *               or a command with {@code {file}} and {@code {line}}
     * @return this config
     * @throws IllegalArgumentException for an unknown opener name
     */
    public Config sourceOpener(String opener) { return sourceOpener(opener, null); }

    /**
     * The configured source roots.
     *
     * @return a copy of the roots (empty when none are set)
     */
    public String[] sourceRoots() { return sourceRoots.clone(); }

    /**
     * Chart title for exports (default: the process name). Can be changed while
     * running with {@link MemoryProfiler#title(String)}.
     *
     * @param v the title
     * @return this config
     */
    public Config title(String v) { this.title = v; return this; }

    /**
     * Chart subtitle for exports (default: process name, PID and start time).
     *
     * @param v the subtitle
     * @return this config
     */
    public Config subtitle(String v) { this.subtitle = v; return this; }

    /**
     * Writes an interactive HTML export on {@link #stop()} and {@link #shutdown()}.
     *
     * @param path the {@code .html} file to write
     * @return this config
     */
    public Config exportHtmlOnStop(String path) { this.exportHtmlOnStop = path; return this; }

    /**
     * Writes a PNG of the whole run on {@link #stop()} and {@link #shutdown()}.
     *
     * @param path the {@code .png} file to write
     * @return this config
     */
    public Config exportPngOnStop(String path) { return exportPngOnStop(path, null); }

    /**
     * Like {@link #exportPngOnStop(String)} with image options.
     *
     * @param path    the {@code .png} file to write
     * @param options size, theme, range, marks table and more (null = defaults)
     * @return this config
     */
    public Config exportPngOnStop(String path, PngOptions options) {
      this.exportPngOnStop = path;
      this.exportPngOptions = options;
      return this;
    }

    /**
     * HTML exports keep at most this many SAMPLE points (default 20,000); above
     * it samples are reduced with min/max per bucket so peaks survive. Marks,
     * scopes, GC events and heap dumps are always kept.
     *
     * @param v the maximum number of samples (at least 100)
     * @return this config
     */
    public Config exportMaxSamples(int v) { this.exportMaxSamples = Math.max(100, v); return this; }

    /**
     * Lines of source embedded above and below each mark in HTML exports when
     * {@link #sourceRoots} resolve the file (default 6, 0 = none).
     *
     * @param v lines of context on each side
     * @return this config
     */
    public Config exportSourceLines(int v) { this.exportSourceLines = Math.max(0, v); return this; }
  }

  /**
   * An open scope from {@link #scope(String)} or {@link #scopeGc(String)}. Its
   * {@code SCOPE_START} point is recorded on creation; {@link #close()} records
   * {@code SCOPE_END} with the heap delta and the duration. Use it in a
   * try-with-resources block. A scope opened while the profiler is not running
   * records nothing.
   */
  public final class Scope implements AutoCloseable {
    private final String id;
    private final String label;
    private final long startTime;
    private final long startUsedMB;
    private final boolean gc;
    private boolean closed = false;

    Scope(String id, String label, long startTime, long startUsedMB, boolean gc) {
      this.id = id;
      this.label = label;
      this.startTime = startTime;
      this.startUsedMB = startUsedMB;
      this.gc = gc;
    }

    /**
     * Records the {@code SCOPE_END} point (after a forced GC for {@link #scopeGc(String) scopeGc}).
     * Only the first call records anything.
     */
    public void close() {
      if (closed) return;
      closed = true;
      endScope(this);
    }

    /**
     * The id shared by this scope's start and end points.
     *
     * @return {@code s1}, {@code s2}, ...; {@code "noop"} when the profiler was not running
     */
    public String id() { return id; }

    /**
     * The scope's label.
     *
     * @return the label passed to {@link #scope(String) scope} or {@link #scopeGc(String) scopeGc}
     */
    public String label() { return label; }

    /**
     * Used heap when the scope opened (after the forced GC for {@code scopeGc}).
     *
     * @return used heap in MB
     */
    public long startUsedMB() { return startUsedMB; }
  }

  private static MemoryProfiler INSTANCE;
  /** Owner of the no-op scopes returned while the profiler is not running (created on first use). */
  private static final class NoopScopes {
    static final MemoryProfiler OWNER = new MemoryProfiler(Config.defaults());
  }

  private final Config config;
  private final DataStore store;
  private final HeapSampler sampler;
  private final GcListener gcListener;
  private final AutoGcController autoGc;
  private final HeapDumper heapDumper;
  private ProfilerServer server;
  private final AtomicLong scopeCounter = new AtomicLong(0);
  private final Map<String, Scope> openScopes = new HashMap<String, Scope>();
  private volatile String title;
  private volatile String subtitle;
  /** Next store index when the on-stop exports last ran (-1 = never); avoids writing twice for stop() + shutdown(). */
  private long onStopExportedAt = -1;

  private MemoryProfiler(Config config) {
    this.config = config;
    this.title = config.title;
    this.subtitle = config.subtitle;
    RunHeader header = RunHeader.forThisJvm(config.intervalMs);
    header.title = config.title;
    header.subtitle = config.subtitle;
    this.store = new DataStore(config.maxPoints, header);
    this.autoGc = new AutoGcController(store, config.gcPasses, config.gcPauseMs);
    if (config.autoGcNearPeak) this.autoGc.setNearPeakEnabled(true);
    if (config.autoGcThreshold) {
      this.autoGc.setThresholdEnabled(true);
      this.autoGc.setThresholdMB(config.autoGcThresholdMB);
    }
    this.sampler = new HeapSampler(store, config.intervalMs, autoGc);
    this.gcListener = new GcListener(store);
    this.heapDumper = new HeapDumper(new java.io.File(config.heapDumpDir));
    if (config.heapDumpOpener != null) {
      String op = config.heapDumpOpener.toLowerCase();
      if ("visualvm".equals(op)) heapDumper.setOpener(HeapDumper.Opener.VISUALVM);
      else if ("intellij".equals(op)) heapDumper.setOpener(HeapDumper.Opener.INTELLIJ);
      else heapDumper.setOpener(HeapDumper.Opener.SYSTEM);
      if (config.heapDumpOpenerPath != null) heapDumper.setOpenerPath(config.heapDumpOpenerPath);
    }
  }

  // ---- lifecycle ----

  /**
   * Starts the profiler with the default {@link Config}: samples the heap every
   * 500 ms, records GC events and serves the live UI on a free port (opened in
   * the browser).
   *
   * @return the running profiler; if it is already running, that instance
   */
  public static synchronized MemoryProfiler start() {
    return start(Config.defaults());
  }

  /**
   * Starts the profiler with {@code config}. Only one profiler runs per JVM:
   * while one is running this returns it and ignores {@code config}.
   *
   * @param config the configuration
   * @return the running profiler
   * @throws RuntimeException if the JSONL file or the web server cannot be opened
   */
  public static synchronized MemoryProfiler start(Config config) {
    if (INSTANCE != null) return INSTANCE;
    MemoryProfiler p = new MemoryProfiler(config);
    try {
      if (config.jsonlPath != null) p.store.enableJsonl(config.jsonlPath, config.jsonlMode == JsonlMode.APPEND);
      if (config.startServer) {
        int port = config.port > 0 ? config.port : findFreePort();
        p.server = new ProfilerServer(port, p.store, p.autoGc, p.heapDumper, config.sourceRoots);
        p.server.setExports(p);
        p.server.setSourceOpener(new SourceOpener(config.sourceOpener, config.sourceOpenerPath));
        p.server.start();
        String url = "http://localhost:" + port + "/";
        System.out.println("[memprofiler] live UI at " + url);
        if (config.openBrowser) openBrowser(url);
      }
      if (config.gcListener) p.gcListener.start();
      p.sampler.start();
    } catch (IOException e) {
      throw new RuntimeException("Failed to start memory profiler", e);
    }
    INSTANCE = p;
    return p;
  }

  /**
   * Starts the profiler with a config built inline, for example
   * {@code start(c -> c.intervalMs(250).jsonl("build/heap.jsonl"))}.
   *
   * @param configure called with a default config to change
   * @return the running profiler; if it is already running, that instance
   *         ({@code configure} is still called but its config is ignored)
   */
  public static synchronized MemoryProfiler start(Consumer<Config> configure) {
    Config c = Config.defaults();
    configure.accept(c);
    return start(c);
  }

  /**
   * Stops sampling and GC listening but keeps the web server running
   * so collected data remains browsable. Call {@link #shutdown()} to
   * tear everything down, or {@link #await()} to block until the JVM exits.
   */
  public static synchronized void stop() {
    if (INSTANCE == null) return;
    INSTANCE.sampler.stop();
    INSTANCE.gcListener.stop();
    INSTANCE.store.flush();
    INSTANCE.runOnStopExports();
    long keep = INSTANCE.config.keepUiAfterStopMs;
    if (keep >= 0 && INSTANCE.server != null) {
      System.out.println("[memprofiler] profiling stopped – UI available for " + (keep / 1000) + " s more");
      INSTANCE.scheduleShutdown(keep);
    } else if (keep >= 0) {
      shutdown();
    } else {
      System.out.println("[memprofiler] profiling stopped – UI still available");
    }
  }

  private final Object shutdownLatch = new Object();
  private volatile boolean shutDown = false;
  private Thread shutdownTimer;

  private synchronized void scheduleShutdown(final long afterMs) {
    if (shutdownTimer != null) return;
    final MemoryProfiler self = this;
    shutdownTimer = new Thread(new Runnable() {
      public void run() {
        try {
          Thread.sleep(afterMs);
        } catch (InterruptedException e) {
          return;
        }
        synchronized (MemoryProfiler.class) {
          if (INSTANCE == self) shutdown();
        }
      }
    }, "mem-profiler-ui-timeout");
    shutdownTimer.setDaemon(true);
    shutdownTimer.start();
  }

  /**
   * Blocks the calling thread, keeping the web UI up until the JVM is killed
   * (Ctrl-C) or {@link #shutdown()} is called from another thread. With
   * {@link Config#keepUiAfterStop(Duration)} it returns once that time is up.
   * Returns at once when the profiler or its web server is not running.
   * Typical usage at the end of {@code main}:
   * <pre>
   *   MemoryProfiler.stop();   // stop sampling; the UI stays up
   *   MemoryProfiler.await();  // keep the JVM alive to browse the results
   * </pre>
   */
  public static void await() {
    MemoryProfiler p = INSTANCE;
    if (p == null || p.server == null) return;
    if (p.config.keepUiAfterStopMs >= 0) {
      // the UI shuts down on its own (keepUiAfterStop); wait for that, not forever
      System.out.println("[memprofiler] awaiting – the UI closes " + (p.config.keepUiAfterStopMs / 1000)
          + " s after stop()");
      p.waitForShutdown(Long.MAX_VALUE);
      return;
    }
    System.out.println("[memprofiler] awaiting – press Ctrl-C to exit");
    p.waitForShutdown(Long.MAX_VALUE);
  }

  /**
   * Keeps the UI up for at most {@code d} (or until {@link #shutdown()} is called
   * elsewhere), then shuts the profiler down and returns, so the JVM can exit
   * normally. Use it at the end of a test instead of {@link #await()}, which
   * blocks until the JVM is killed.
   *
   * @param d the longest time to keep the UI up; null or negative = shut down at once
   */
  public static void await(Duration d) {
    MemoryProfiler p = INSTANCE;
    if (p == null) return;
    long ms = d == null || d.isNegative() ? 0 : d.toMillis();
    if (p.server != null && ms > 0) {
      System.out.println("[memprofiler] UI available for " + (ms / 1000) + " s");
      p.waitForShutdown(ms);
    }
    synchronized (MemoryProfiler.class) {
      if (INSTANCE == p) shutdown();
    }
  }

  private void waitForShutdown(long maxMs) {
    long deadline = maxMs == Long.MAX_VALUE ? Long.MAX_VALUE : System.currentTimeMillis() + maxMs;
    synchronized (shutdownLatch) {
      while (!shutDown) {
        long left = deadline == Long.MAX_VALUE ? 0 : deadline - System.currentTimeMillis();
        if (deadline != Long.MAX_VALUE && left <= 0) return;
        try {
          shutdownLatch.wait(left);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  /**
   * Fully tears down the profiler: stops sampling, GC listener, web server,
   * and flushes / closes the data store.
   */
  public static synchronized void shutdown() {
    if (INSTANCE == null) return;
    INSTANCE.sampler.stop();
    INSTANCE.gcListener.stop();
    INSTANCE.runOnStopExports();
    if (INSTANCE.server != null) INSTANCE.server.stop();
    INSTANCE.store.close();
    MemoryProfiler p = INSTANCE;
    INSTANCE = null;
    synchronized (p.shutdownLatch) {
      p.shutDown = true;
      p.shutdownLatch.notifyAll();
    }
  }

  /**
   * The running profiler.
   *
   * @return the instance, or null before {@link #start()} and after {@link #shutdown()}
   */
  public static MemoryProfiler get() { return INSTANCE; }

  /**
   * Whether the profiler is started: true between {@link #start()} and
   * {@link #shutdown()}, including after {@link #stop()} while the UI stays up.
   *
   * @return true when started
   */
  public static boolean isRunning() { return INSTANCE != null; }

  // ---- marks ----

  /**
   * Records a MARK point with the current heap reading. Does <b>not</b>
   * force a GC — cheap enough for hot paths. The caller's class, method and
   * line are recorded as the mark's location. A leading number
   * ({@code "3. Cache loaded"}) becomes the mark's badge in the chart and the
   * exports. No-op when the profiler is not running.
   *
   * @param label text shown in the chart, the events list and the marks table
   */
  public static void mark(String label) {
    doMark(label, false, 0);
  }

  /**
   * Like {@link #mark(String)}, for helpers: the recorded location skips
   * {@code extraFramesToSkip} more frames of your code (1 = the caller of the
   * method that calls this one).
   *
   * @param label             text shown in the chart, the events list and the marks table
   * @param extraFramesToSkip frames of your code to skip when recording the location
   */
  public static void mark(String label, int extraFramesToSkip) {
    doMark(label, false, extraFramesToSkip);
  }

  /**
   * Runs {@code System.gc()} first, then records a MARK point. Use when you
   * want the reading to reflect live (non-reclaimable) memory only. The GC
   * time is recorded with the mark (the marks table's GC ms column). No-op when
   * the profiler is not running.
   *
   * @param label text shown in the chart, the events list and the marks table
   */
  public static void markGc(String label) {
    doMark(label, true, 0);
  }

  /**
   * Like {@link #markGc(String)}, for helpers such as a reflection bridge: the
   * recorded location skips {@code extraFramesToSkip} more frames of your code,
   * so it points at the real caller instead of the helper.
   *
   * @param label             text shown in the chart, the events list and the marks table
   * @param extraFramesToSkip frames of your code to skip when recording the location
   */
  public static void markGc(String label, int extraFramesToSkip) {
    doMark(label, true, extraFramesToSkip);
  }

  private static void doMark(String label, boolean gc, int extraFrames) {
    MemoryProfiler p = INSTANCE;
    if (p == null) return;
    Long gcMs = gc ? Long.valueOf(forceGc(p.config.gcPasses, p.config.gcPauseMs)) : null;
    MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    long usedMB = HeapSampler.toMB(heap.getUsed());
    CallerInfo caller = callerInfo(extraFrames);
    DataPoint dp = new DataPoint(
        -1,
        System.currentTimeMillis(),
        usedMB,
        HeapSampler.toMB(heap.getCommitted()),
        HeapSampler.toMB(heap.getMax()),
        DataPoint.Kind.MARK,
        label,
        caller.display,
        caller.sourceFile,
        null,
        null,
        null,
        Thread.currentThread().getName(),
        gcMs,
        null
    );
    dp = p.store.add(dp);
    System.out.println("[memprofiler] mark " + label + " -> " + usedMB + " MB (" + dp.location + ")");
  }

  // ---- scopes ----

  /**
   * Cheap scope: no forced GC at entry or exit. Records {@code SCOPE_START} now
   * and {@code SCOPE_END} (heap delta and duration) when the returned scope is
   * closed:
   * <pre>
   *   try (MemoryProfiler.Scope s = MemoryProfiler.scope("load")) { ... }
   * </pre>
   *
   * @param label name of the scope band in the chart
   * @return the open scope; a no-op scope when the profiler is not running
   */
  public static Scope scope(String label) {
    return openScope(label, false);
  }

  /**
   * Precise scope: forces GC at both entry and exit, so {@code deltaMB}
   * reflects retained (non-reclaimable) growth rather than allocation churn.
   *
   * @param label name of the scope band in the chart
   * @return the open scope; a no-op scope when the profiler is not running
   */
  public static Scope scopeGc(String label) {
    return openScope(label, true);
  }

  private static Scope openScope(String label, boolean gc) {
    MemoryProfiler p = INSTANCE;
    if (p == null) return NoopScopes.OWNER.new Scope("noop", label, 0, 0, gc);
    Long gcMs = gc ? Long.valueOf(forceGc(p.config.gcPasses, p.config.gcPauseMs)) : null;
    long now = System.currentTimeMillis();
    MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    long usedMB = HeapSampler.toMB(heap.getUsed());
    String id = "s" + p.scopeCounter.incrementAndGet();
    CallerInfo caller = callerInfo(0);

    DataPoint start = new DataPoint(
        -1,
        now,
        usedMB,
        HeapSampler.toMB(heap.getCommitted()),
        HeapSampler.toMB(heap.getMax()),
        DataPoint.Kind.SCOPE_START,
        label,
        caller.display,
        caller.sourceFile,
        id,
        null,
        null,
        Thread.currentThread().getName(),
        gcMs,
        null
    );
    p.store.add(start);

    Scope s = p.new Scope(id, label, now, usedMB, gc);
    synchronized (p.openScopes) {
      p.openScopes.put(id, s);
    }
    return s;
  }

  void endScope(Scope s) {
    if ("noop".equals(s.id)) return;
    synchronized (openScopes) {
      openScopes.remove(s.id);
    }
    Long gcMs = s.gc ? Long.valueOf(forceGc(config.gcPasses, config.gcPauseMs)) : null;
    long now = System.currentTimeMillis();
    MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    long usedMB = HeapSampler.toMB(heap.getUsed());
    long delta = usedMB - s.startUsedMB;
    long duration = now - s.startTime;

    CallerInfo caller = callerInfo(0);
    DataPoint end = new DataPoint(
        -1,
        now,
        usedMB,
        HeapSampler.toMB(heap.getCommitted()),
        HeapSampler.toMB(heap.getMax()),
        DataPoint.Kind.SCOPE_END,
        s.label,
        caller.display,
        caller.sourceFile,
        s.id,
        Long.valueOf(delta),
        Long.valueOf(duration),
        Thread.currentThread().getName(),
        gcMs,
        null
    );
    store.add(end);
    System.out.println("[memprofiler] scope " + s.label + " done dt=" + duration + "ms delta=" + delta + "MB now=" + usedMB + "MB");
  }

  // ---- @Profile proxy ----

  /**
   * Wrap an interface implementation so every {@link Profile @Profile} method
   * becomes a scope automatically. Non-annotated methods pass through.
   * {@code @Profile(gc=true)} uses {@link #scopeGc}, otherwise {@link #scope}.
   * The scope label is {@code @Profile("...")}, or {@code Iface.method} when empty.
   *
   * @param <T>   the interface type
   * @param iface the interface to proxy (must be an interface)
   * @param impl  the implementation calls are forwarded to
   * @return a proxy implementing {@code iface}
   * @throws IllegalArgumentException if {@code iface} is not an interface
   */
  @SuppressWarnings("unchecked")
  public static <T> T wrap(Class<T> iface, final T impl) {
    if (!iface.isInterface()) {
      throw new IllegalArgumentException("MemoryProfiler.wrap requires an interface: " + iface);
    }
    return (T) Proxy.newProxyInstance(
        iface.getClassLoader(),
        new Class<?>[] { iface },
        new InvocationHandler() {
          public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Profile ann = method.getAnnotation(Profile.class);
            if (ann == null) {
              try {
                return method.invoke(impl, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            }
            String label = ann.value();
            if (label == null || label.length() == 0) {
              label = method.getDeclaringClass().getSimpleName() + "." + method.getName();
            }
            Scope s = ann.gc() ? scopeGc(label) : scope(label);
            try {
              return method.invoke(impl, args);
            } catch (InvocationTargetException e) {
              throw e.getCause();
            } finally {
              s.close();
            }
          }
        });
  }

  // ---- reference lines and title ----

  /**
   * Adds a dashed horizontal reference line to the chart and the exports, for
   * example {@code referenceLine(5120, "Production heap limit (5 GB)")}. It is
   * also written to the JSONL file (in the {@code references} of the run's
   * {@code META} line), so exports made later from that file show it. The value
   * is never treated as a heap reading (peak, statistics, the heap line).
   * No-op when the profiler is not running.
   *
   * @param mb    the line's height in MB
   * @param label text drawn on the line
   */
  public static void referenceLine(long mb, String label) {
    MemoryProfiler p = INSTANCE;
    if (p == null) return;
    MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    CallerInfo caller = callerInfo(0);
    p.store.add(new DataPoint(
        -1,
        System.currentTimeMillis(),
        mb,
        HeapSampler.toMB(heap.getCommitted()),
        HeapSampler.toMB(heap.getMax()),
        DataPoint.Kind.REFERENCE,
        label,
        caller.display,
        caller.sourceFile,
        null,
        null,
        null,
        Thread.currentThread().getName()));
  }

  /**
   * Sets the chart title used by the exports, also recorded in the JSONL (the
   * subtitle stays process, PID and start time). No-op when the profiler is not running.
   *
   * @param title the chart title
   */
  public static void title(String title) {
    MemoryProfiler p = INSTANCE;
    if (p == null) return;
    p.title = title;
    p.store.setTitles(p.title, p.subtitle);
  }

  /**
   * Sets the chart title and subtitle used by the exports, also recorded in the
   * JSONL. No-op when the profiler is not running.
   *
   * @param title    the chart title
   * @param subtitle the line under the title
   */
  public static void title(String title, String subtitle) {
    MemoryProfiler p = INSTANCE;
    if (p == null) return;
    p.title = title;
    p.subtitle = subtitle;
    p.store.setTitles(title, subtitle);
  }

  /**
   * Sets the subtitle used by the exports (and recorded in the JSONL). No-op
   * when the profiler is not running.
   *
   * @param subtitle the line under the title
   */
  public static void subtitle(String subtitle) {
    MemoryProfiler p = INSTANCE;
    if (p == null) return;
    p.subtitle = subtitle;
    p.store.setTitles(p.title, subtitle);
  }

  // ---- exports ----

  /**
   * Writes a self-contained, interactive HTML file of the profile so far
   * (works while running and after {@link #stop()}). The file opens offline
   * from disk or as an attachment. With {@link Config#sourceRoots}, the source
   * lines around each mark are embedded and shown when hovering the mark.
   *
   * @param path the {@code .html} file to write (parent directories are created)
   * @return the absolute path written
   * @throws IllegalStateException if the profiler is not running
   * @throws UncheckedIOException if the file cannot be written
   */
  public static String exportHtml(String path) {
    MemoryProfiler p = requireInstance("exportHtml");
    try {
      return HtmlExporter.export(p.pointSource(), p.htmlOptions(), new File(path)).getAbsolutePath();
    } catch (IOException e) {
      throw new UncheckedIOException("HTML export to " + path + " failed", e);
    }
  }

  /**
   * Writes a PNG of the whole run so far with default options. See {@link #exportPng(String, PngOptions)}.
   *
   * @param path the {@code .png} file to write
   * @return the absolute path written
   * @throws IllegalStateException if the profiler is not running
   * @throws UncheckedIOException if the file cannot be written
   */
  public static String exportPng(String path) {
    return exportPng(path, null);
  }

  /**
   * Writes a static PNG of the whole run (or the range in {@code options}) with
   * numbered mark badges, scope bands and reference lines.
   *
   * @param path    the {@code .png} file to write
   * @param options size, theme, time range, marks table and more (null = defaults)
   * @return the absolute path written
   * @throws IllegalStateException if the profiler is not running
   * @throws UncheckedIOException if the file cannot be written
   */
  public static String exportPng(String path, PngOptions options) {
    MemoryProfiler p = requireInstance("exportPng");
    try {
      return PngRenderer.render(p.pointSource(), options, new File(path)).getAbsolutePath();
    } catch (IOException e) {
      throw new UncheckedIOException("PNG export to " + path + " failed", e);
    }
  }

  private static MemoryProfiler requireInstance(String what) {
    MemoryProfiler p = INSTANCE;
    if (p == null) {
      throw new IllegalStateException("MemoryProfiler." + what + ": profiler is not running "
          + "(call it before shutdown(), or use Config.exportHtmlOnStop / exportPngOnStop)");
    }
    return p;
  }

  /** The live store as an export source, with the current title and process details. */
  PointSource pointSource() {
    ExportMeta m = new ExportMeta();
    m.title = title;
    m.subtitle = subtitle;
    m.processName = ProfilerServer.currentProcessName();
    m.pid = ProfilerServer.currentPid();
    m.intervalMs = config.intervalMs;
    m.runId = store.header().runId();
    m.source = "live";
    return new PointSource.Store(store, m);
  }

  HtmlExporter.Options htmlOptions() {
    HtmlExporter.Options o = new HtmlExporter.Options();
    o.maxSamples = config.exportMaxSamples;
    o.sourceRoots = config.sourceRoots.clone();
    o.sourceContextLines = config.exportSourceLines;
    return o;
  }

  private void runOnStopExports() {
    if (config.exportHtmlOnStop == null && config.exportPngOnStop == null) return;
    long mark = store.peekNextIndex();
    if (mark == onStopExportedAt) return;
    onStopExportedAt = mark;
    if (config.exportHtmlOnStop != null) {
      try {
        File f = HtmlExporter.export(pointSource(), htmlOptions(), new File(config.exportHtmlOnStop));
        System.out.println("[memprofiler] HTML export written to " + f.getAbsolutePath());
      } catch (Throwable t) {
        System.err.println("[memprofiler] HTML export to " + config.exportHtmlOnStop + " failed: " + t);
      }
    }
    if (config.exportPngOnStop != null) {
      try {
        File f = PngRenderer.render(pointSource(), config.exportPngOptions, new File(config.exportPngOnStop));
        System.out.println("[memprofiler] PNG export written to " + f.getAbsolutePath());
      } catch (Throwable t) {
        System.err.println("[memprofiler] PNG export to " + config.exportPngOnStop + " failed: " + t);
      }
    }
  }

  /**
   * The profiler version.
   *
   * @return the version from the jar manifest, or {@code "dev"} when running from classes
   */
  public static String version() {
    Package pkg = MemoryProfiler.class.getPackage();
    String v = pkg == null ? null : pkg.getImplementationVersion();
    return v != null ? v : "dev";
  }

  // ---- help ----

  /**
   * Prints {@link #help()} to {@code System.out}: whether the profiler is
   * running (and where its live UI is), the main calls with one line each, the
   * {@link Config} options and where to read more. Safe to call at any time,
   * for example from {@code jshell} with the jar on the class path.
   */
  public static void printHelp() {
    System.out.print(help());
    System.out.flush();
  }

  /**
   * The text {@link #printHelp()} prints.
   *
   * @return a short overview of the API, ending with a newline
   */
  public static String help() {
    StringBuilder sb = new StringBuilder(2048);
    sb.append("Java Memory Profiler ").append(version())
      .append(": an in-process heap profiler driven from your code\n");
    MemoryProfiler p = INSTANCE;
    if (p == null) {
      sb.append("Status: not running; call MemoryProfiler.start()\n");
    } else {
      sb.append("Status: ").append(p.sampler.isRunning() ? "sampling" : "stopped");
      sb.append(p.server != null ? ", live UI at http://localhost:" + p.server.port() + "/" : ", no live UI (startServer(false))");
      String jsonl = p.store.jsonlPath();
      if (jsonl != null) sb.append(", JSONL ").append(jsonl);
      sb.append('\n');
    }
    sb.append("\nStart and stop\n");
    row(sb, "MemoryProfiler.start();", "sample every 500 ms, live UI on a free port");
    row(sb, "MemoryProfiler.start(c -> c.jsonl(\"build/heap.jsonl\").port(9090));", null);
    row(sb, "MemoryProfiler.stop();", "stop sampling; the UI stays up");
    row(sb, "MemoryProfiler.await();", "keep the UI up until the JVM is killed");
    row(sb, "MemoryProfiler.await(Duration.ofMinutes(2));", "... or for a while, then shut down");
    row(sb, "MemoryProfiler.shutdown();", "stop everything, flush the JSONL");
    sb.append("\nMarks and scopes (a label starting \"3. \" gives the mark badge 3)\n");
    row(sb, "MemoryProfiler.markGc(\"3. Loaded\");", "forced GC first: the retained heap");
    row(sb, "MemoryProfiler.mark(\"tick\");", "no GC: cheap, but includes garbage");
    row(sb, "try (MemoryProfiler.Scope s = MemoryProfiler.scopeGc(\"parse\")) { ... }", null);
    row(sb, "", "what the block retained; scope(...) skips the GCs");
    row(sb, "Job j = MemoryProfiler.wrap(Job.class, impl);", "@Profile methods become scopes");
    sb.append("\nChart, exports, heap dumps\n");
    row(sb, "MemoryProfiler.referenceLine(5120, \"Prod limit\");", "dashed line on the chart");
    row(sb, "MemoryProfiler.title(\"Cache loader\");", "export title (also subtitle(...))");
    row(sb, "MemoryProfiler.exportHtml(\"build/profile.html\");", "interactive, works offline");
    row(sb, "MemoryProfiler.exportPng(\"build/profile.png\");", "static image of the run");
    row(sb, "MemoryProfiler.heapDump(\"after-load\");", ".hprof of the live objects");
    sb.append("\nConfig options (MemoryProfiler.Config, see its javadoc)\n");
    appendWrapped(sb, configOptions(), "  ", 92);
    sb.append("\nWithout code changes\n");
    row(sb, "java -javaagent:<jar>=jsonl=build/heap.jsonl,port=9090 -cp app.jar com.acme.Main", null);
    row(sb, "-D" + SourceOpener.PROPERTY + "=intellij|vscode|system", "\"Open in IDE\" at the mark's line");
    row(sb, "-D" + SKIP_CALLERS_PROPERTY + "=com.acme.util", "frames to skip for mark locations");
    sb.append("\nMore\n");
    row(sb, "java -jar <jar> --help", "the command-line tools (export, agents)");
    row(sb, "java -jar <jar> export --help", "HTML / PNG exports and summaries from a JSONL file");
    row(sb, GithubLink.URL, null);
    return sb.toString();
  }

  /** The fluent setters of {@link Config}, sorted: read from the class, so the list never goes stale. */
  static List<String> configOptions() {
    java.util.TreeSet<String> names = new java.util.TreeSet<String>();
    for (Method m : Config.class.getMethods()) {
      if (m.getReturnType() == Config.class && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
        names.add(m.getName());
      }
    }
    return new ArrayList<String>(names);
  }

  private static final int HELP_COLUMN = 54;

  /** One help line: the code, then its description in a column (or two spaces past long code). */
  private static void row(StringBuilder sb, String code, String description) {
    StringBuilder line = new StringBuilder("  ").append(code);
    if (description != null) {
      int pad = Math.max(2, HELP_COLUMN - line.length());
      for (int i = 0; i < pad; i++) line.append(' ');
      line.append(description);
    }
    sb.append(line).append('\n');
  }

  private static void appendWrapped(StringBuilder sb, List<String> words, String indent, int width) {
    StringBuilder line = new StringBuilder(indent);
    for (int i = 0; i < words.size(); i++) {
      String w = words.get(i) + (i < words.size() - 1 ? "," : "");
      if (line.length() > indent.length() && line.length() + 1 + w.length() > width) {
        sb.append(line).append('\n');
        line.setLength(0);
        line.append(indent);
      }
      if (line.length() > indent.length()) line.append(' ');
      line.append(w);
    }
    if (line.length() > indent.length()) sb.append(line).append('\n');
  }

  // ---- heap dumps ----

  /**
   * Records a heap dump (hprof) with the given name. The file is saved to the
   * configured {@code heapDumpDir}. Only live objects are included.
   *
   * <pre>
   *   MemoryProfiler.heapDump("after-load");
   * </pre>
   *
   * @param name a label for the dump (used in the filename)
   * @return the file path of the dump, or null if not available
   */
  public static String heapDump(String name) {
    MemoryProfiler p = INSTANCE;
    if (p == null) return null;
    HeapDumper.DumpInfo info = p.heapDumper.dump(name);
    if (info == null) return null;

    // Record a HEAP_DUMP data point on the timeline
    MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    long usedMB = HeapSampler.toMB(heap.getUsed());
    CallerInfo caller = callerInfo(0);
    DataPoint dp = new DataPoint(
        -1,
        System.currentTimeMillis(),
        usedMB,
        HeapSampler.toMB(heap.getCommitted()),
        HeapSampler.toMB(heap.getMax()),
        DataPoint.Kind.HEAP_DUMP,
        name,
        caller.display,
        caller.sourceFile,
        null,
        null,
        null,
        Thread.currentThread().getName()
    );
    p.store.add(dp);

    return info.path;
  }

  /**
   * Records a heap dump named {@code dump} (plus a timestamp). See {@link #heapDump(String)}.
   *
   * @return the file path of the dump, or null if not available
   */
  public static String heapDump() {
    return heapDump("dump");
  }

  // ---- internals ----

  /** Runs the forced GC loop; returns the wall time it took (ms). */
  private static long forceGc(int passes, long pauseMs) {
    long t0 = System.nanoTime();
    for (int i = 0; i < passes; i++) {
      System.gc();
      try {
        Thread.sleep(pauseMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return (System.nanoTime() - t0) / 1000000L;
  }

  static final class CallerInfo {
    final String display;
    final String sourceFile;
    CallerInfo(String display, String sourceFile) {
      this.display = display;
      this.sourceFile = sourceFile;
    }
    static final CallerInfo UNKNOWN = new CallerInfo("unknown", null);
  }

  /** System property with extra caller prefixes to skip (comma or semicolon separated). */
  public static final String SKIP_CALLERS_PROPERTY = "memprofiler.skipCallers";

  /** Configured and system-property prefixes of frames to skip when recording a location. */
  static List<String> skipCallerPrefixes() {
    List<String> out = new ArrayList<String>();
    MemoryProfiler p = INSTANCE;
    if (p != null) {
      for (String s : p.config.skipCallerPrefixes) if (s != null && !s.trim().isEmpty()) out.add(s.trim());
    }
    String prop = System.getProperty(SKIP_CALLERS_PROPERTY);
    if (prop != null) {
      for (String s : prop.split("[,;]")) if (!s.trim().isEmpty()) out.add(s.trim());
    }
    return out;
  }

  static CallerInfo callerInfo(int extraFrames) {
    List<String> skip = skipCallerPrefixes();
    int toSkip = Math.max(0, extraFrames);
    StackTraceElement[] stack = Thread.currentThread().getStackTrace();
    frames:
    for (StackTraceElement e : stack) {
      String cn = e.getClassName();
      for (int i = 0; i < skip.size(); i++) if (cn.startsWith(skip.get(i))) continue frames;
      if (cn.equals(MemoryProfiler.class.getName())) continue;
      if (cn.startsWith("java.lang.Thread")) continue;
      // the profiler's own frames; the bundled Demo is user code for this purpose
      if (cn.startsWith("com.toro.al.memprofiler.") && !cn.startsWith("com.toro.al.memprofiler.Demo")) continue;
      // Skip reflection / proxy / lambda machinery
      if (cn.startsWith("sun.reflect.")) continue;
      if (cn.startsWith("jdk.internal.reflect.")) continue;
      if (cn.startsWith("java.lang.reflect.")) continue;
      if (cn.contains("$Proxy")) continue;
      if (cn.startsWith("jdk.proxy")) continue;
      if (cn.contains("$$Lambda")) continue;
      if (cn.contains("$$FastClassBy")) continue;
      if (cn.startsWith("com.sun.proxy.")) continue;
      if (toSkip > 0) {
        toSkip--;
        continue;
      }

      int line = e.getLineNumber();
      String display = cn + "." + e.getMethodName() + (line > 0 ? ":" + line : "");

      String fileName = e.getFileName();
      String sourceFile = null;
      if (fileName != null) {
        int dot = cn.lastIndexOf('.');
        String pkgPath = dot > 0 ? cn.substring(0, dot).replace('.', '/') + "/" : "";
        sourceFile = pkgPath + fileName + (line > 0 ? ":" + line : "");
      }
      return new CallerInfo(display, sourceFile);
    }
    return CallerInfo.UNKNOWN;
  }

  private static int findFreePort() throws IOException {
    ServerSocket ss = new ServerSocket(0);
    try {
      return ss.getLocalPort();
    } finally {
      ss.close();
    }
  }

  private static void openBrowser(String url) {
    try {
      Headless.markAwtUsed();
      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(new URI(url));
      }
    } catch (Throwable ignored) {
    }
  }

  DataStore store() { return store; }
  Config config() { return config; }
  AutoGcController autoGc() { return autoGc; }
  HeapDumper heapDumper() { return heapDumper; }
}
