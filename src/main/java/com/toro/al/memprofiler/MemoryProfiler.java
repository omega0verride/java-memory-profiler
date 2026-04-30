package com.toro.al.memprofiler;

import java.awt.Desktop;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.URI;
import java.util.HashMap;
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
 *   MemoryProfiler.stop();
 * </pre>
 */
public final class MemoryProfiler {

  /**
   * Fluent config. Build one and pass to {@link #start(Config)}, or use the
   * {@link #start(Consumer)} overload for inline configuration.
   */
  public static final class Config {
    long intervalMs = 500;
    int maxPoints = 20000;
    int port = 0;
    String jsonlPath = null;
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

    public static Config defaults() { return new Config(); }

    public Config intervalMs(long v)      { this.intervalMs = v; return this; }
    public Config maxPoints(int v)        { this.maxPoints = v; return this; }
    public Config port(int v)             { this.port = v; return this; }
    public Config jsonl(String path)      { this.jsonlPath = path; return this; }
    public Config openBrowser(boolean v)  { this.openBrowser = v; return this; }
    public Config startServer(boolean v)  { this.startServer = v; return this; }
    public Config gcListener(boolean v)   { this.gcListener = v; return this; }

    /** Enable auto-GC when heap usage approaches the observed peak. */
    public Config autoGcNearPeak(boolean v)   { this.autoGcNearPeak = v; return this; }
    /** Enable auto-GC when heap usage exceeds a fixed threshold.
     *  @param v enable or disable
     *  @param mb threshold in MB (ignored when disabling) */
    public Config autoGcThreshold(boolean v, long mb) {
      this.autoGcThreshold = v;
      this.autoGcThresholdMB = mb;
      return this;
    }

    /** Controls the {@code System.gc()} loop used by {@link #markGc} / {@link #scopeGc}. */
    public Config forceGc(int passes, long pauseMs) {
      this.gcPasses = passes;
      this.gcPauseMs = pauseMs;
      return this;
    }

    /**
     * Source roots used by the "Open in IDE" link to resolve a class location to
     * a real file on disk. Each root is searched for {@code package/path/File.java}.
     */
    public Config sourceRoots(String... roots) {
      this.sourceRoots = roots == null ? new String[0] : roots.clone();
      return this;
    }

    /** Directory where heap dump (.hprof) files are saved. Defaults to java.io.tmpdir. */
    public Config heapDumpDir(String dir) { this.heapDumpDir = dir; return this; }

    /**
     * How to open heap dump files. Options: "system" (OS default), "visualvm", "intellij".
     * @param opener the opener name
     * @param binaryPath optional path to the opener binary (null to auto-detect)
     */
    public Config heapDumpOpener(String opener, String binaryPath) {
      this.heapDumpOpener = opener;
      this.heapDumpOpenerPath = binaryPath;
      return this;
    }

    /** Shorthand: set opener with auto-detection. */
    public Config heapDumpOpener(String opener) { return heapDumpOpener(opener, null); }

    public String[] sourceRoots() { return sourceRoots.clone(); }
  }

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

    public void close() {
      if (closed) return;
      closed = true;
      endScope(this);
    }

    public String id() { return id; }
    public String label() { return label; }
    public long startUsedMB() { return startUsedMB; }
  }

  private static MemoryProfiler INSTANCE;

  private final Config config;
  private final DataStore store;
  private final HeapSampler sampler;
  private final GcListener gcListener;
  private final AutoGcController autoGc;
  private final HeapDumper heapDumper;
  private ProfilerServer server;
  private final AtomicLong scopeCounter = new AtomicLong(0);
  private final Map<String, Scope> openScopes = new HashMap<String, Scope>();

  private MemoryProfiler(Config config) {
    this.config = config;
    this.store = new DataStore(config.maxPoints);
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

  public static synchronized MemoryProfiler start() {
    return start(Config.defaults());
  }

  public static synchronized MemoryProfiler start(Config config) {
    if (INSTANCE != null) return INSTANCE;
    MemoryProfiler p = new MemoryProfiler(config);
    try {
      if (config.jsonlPath != null) p.store.enableJsonl(config.jsonlPath);
      if (config.startServer) {
        int port = config.port > 0 ? config.port : findFreePort();
        p.server = new ProfilerServer(port, p.store, p.autoGc, p.heapDumper, config.sourceRoots);
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
    System.out.println("[memprofiler] profiling stopped – UI still available");
  }

  /**
   * Blocks the calling thread, keeping the web server alive until the
   * JVM shuts down. Typical usage:
   * <pre>
 *   MemoryProfiler.stop();      // stop sampling; UI stays up
 *   MemoryProfiler.await();     // block until JVM exits
 *   MemoryProfiler.shutdown();  // full teardown
   *   MemoryProfiler.await(); // blocks here; UI stays up
   * </pre>
   */
  public static void await() {
    if (INSTANCE == null || INSTANCE.server == null) return;
    System.out.println("[memprofiler] awaiting – press Ctrl-C to exit");
    try {
      Thread.currentThread().join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
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
    if (INSTANCE.server != null) INSTANCE.server.stop();
    INSTANCE.store.close();
    INSTANCE = null;
  }

  public static MemoryProfiler get() { return INSTANCE; }

  public static boolean isRunning() { return INSTANCE != null; }

  // ---- marks ----

  /**
   * Records a MARK point with the current heap reading. Does <b>not</b>
   * force a GC — cheap enough for hot paths.
   */
  public static void mark(String label) {
    doMark(label, false);
  }

  /**
   * Runs {@code System.gc()} first, then records a MARK point. Use when you
   * want the reading to reflect live (non-reclaimable) memory only.
   */
  public static void markGc(String label) {
    doMark(label, true);
  }

  private static void doMark(String label, boolean gc) {
    MemoryProfiler p = INSTANCE;
    if (p == null) return;
    if (gc) forceGc(p.config.gcPasses, p.config.gcPauseMs);
    MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    long usedMB = HeapSampler.toMB(heap.getUsed());
    CallerInfo caller = callerInfo();
    DataPoint dp = new DataPoint(
        p.store.nextIndex(),
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
        Thread.currentThread().getName()
    );
    p.store.add(dp);
    System.out.println("[memprofiler] mark " + label + " -> " + usedMB + " MB (" + dp.location + ")");
  }

  // ---- scopes ----

  /** Cheap scope: no forced GC at entry or exit. */
  public static Scope scope(String label) {
    return openScope(label, false);
  }

  /**
   * Precise scope: forces GC at both entry and exit, so {@code deltaMB}
   * reflects retained (non-reclaimable) growth rather than allocation churn.
   */
  public static Scope scopeGc(String label) {
    return openScope(label, true);
  }

  private static Scope openScope(String label, boolean gc) {
    MemoryProfiler p = INSTANCE;
    if (p == null) return new MemoryProfiler(Config.defaults()).new Scope("noop", label, 0, 0, gc);
    if (gc) forceGc(p.config.gcPasses, p.config.gcPauseMs);
    long now = System.currentTimeMillis();
    MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    long usedMB = HeapSampler.toMB(heap.getUsed());
    String id = "s" + p.scopeCounter.incrementAndGet();
    CallerInfo caller = callerInfo();

    DataPoint start = new DataPoint(
        p.store.nextIndex(),
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
        Thread.currentThread().getName()
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
    if (s.gc) forceGc(config.gcPasses, config.gcPauseMs);
    long now = System.currentTimeMillis();
    MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    long usedMB = HeapSampler.toMB(heap.getUsed());
    long delta = usedMB - s.startUsedMB;
    long duration = now - s.startTime;

    CallerInfo caller = callerInfo();
    DataPoint end = new DataPoint(
        store.nextIndex(),
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
        Thread.currentThread().getName()
    );
    store.add(end);
    System.out.println("[memprofiler] scope " + s.label + " done dt=" + duration + "ms delta=" + delta + "MB now=" + usedMB + "MB");
  }

  // ---- @Profile proxy ----

  /**
   * Wrap an interface implementation so every {@link Profile @Profile} method
   * becomes a scope automatically. Non-annotated methods pass through.
   * {@code @Profile(gc=true)} uses {@link #scopeGc}, otherwise {@link #scope}.
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
    CallerInfo caller = callerInfo();
    DataPoint dp = new DataPoint(
        p.store.nextIndex(),
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

  /** Records a heap dump with an auto-generated name. */
  public static String heapDump() {
    return heapDump("dump");
  }

  // ---- internals ----

  private static void forceGc(int passes, long pauseMs) {
    for (int i = 0; i < passes; i++) {
      System.gc();
      try {
        Thread.sleep(pauseMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
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

  private static CallerInfo callerInfo() {
    StackTraceElement[] stack = Thread.currentThread().getStackTrace();
    for (StackTraceElement e : stack) {
      String cn = e.getClassName();
      if (cn.equals(MemoryProfiler.class.getName())) continue;
      if (cn.startsWith("java.lang.Thread")) continue;
      if (cn.startsWith("com.toro.al.memprofiler.")) continue;
      // Skip reflection / proxy / lambda machinery
      if (cn.startsWith("sun.reflect.")) continue;
      if (cn.startsWith("jdk.internal.reflect.")) continue;
      if (cn.startsWith("java.lang.reflect.")) continue;
      if (cn.contains("$Proxy")) continue;
      if (cn.startsWith("jdk.proxy")) continue;
      if (cn.contains("$$Lambda")) continue;
      if (cn.contains("$$FastClassBy")) continue;
      if (cn.startsWith("com.sun.proxy.")) continue;

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
