package com.toro.al.memprofiler.api;

import java.lang.reflect.Method;

/**
 * Compile-time API for code that must not depend on the profiler jar, such as a
 * library or production code. Every call forwards to
 * {@code com.toro.al.memprofiler.MemoryProfiler} when the profiler is on the
 * runtime classpath (attached with {@code -javaagent} or added to the test
 * classpath), and does nothing otherwise. Nothing here ever throws.
 *
 * <pre>
 *   import com.toro.al.memprofiler.api.MemoryProfiler;
 *
 *   MemoryProfiler.markGc("5. Import: batch start");
 *   try (MemoryProfiler.Scope s = MemoryProfiler.scopeGc("load")) { ... }
 * </pre>
 *
 * The recorded location is the real caller: the profiler skips the frames of
 * this class. The artefact has no dependencies and targets Java 8.
 */
public final class MemoryProfiler {

  static final String IMPL = "com.toro.al.memprofiler.MemoryProfiler";
  /** Retry interval when the profiler is not (yet) on the classpath. */
  static final long RETRY_MS = 10_000;

  private MemoryProfiler() {}

  /** A scope; closing it records the scope end. A no-op when the profiler is absent. */
  public static final class Scope implements AutoCloseable {
    private final AutoCloseable delegate;

    Scope(AutoCloseable delegate) {
      this.delegate = delegate;
    }

    @Override
    public void close() {
      if (delegate == null) return;
      try {
        delegate.close();
      } catch (Throwable ignored) {
      }
    }
  }

  private static final Scope NOOP = new Scope(null);

  private static final class Impl {
    final Method mark, markGc, scope, scopeGc, referenceLine, title, titleSub, heapDump, isRunning;

    Impl(Class<?> c) throws Exception {
      mark = c.getMethod("mark", String.class);
      markGc = c.getMethod("markGc", String.class);
      scope = c.getMethod("scope", String.class);
      scopeGc = c.getMethod("scopeGc", String.class);
      referenceLine = c.getMethod("referenceLine", long.class, String.class);
      title = c.getMethod("title", String.class);
      titleSub = c.getMethod("title", String.class, String.class);
      heapDump = c.getMethod("heapDump", String.class);
      isRunning = c.getMethod("isRunning");
    }
  }

  private static volatile Impl impl;
  private static volatile long lastMissAt = Long.MIN_VALUE;
  static volatile String implName = IMPL; // tests point this elsewhere

  private static Impl impl() {
    Impl i = impl;
    if (i != null) return i;
    long now = System.currentTimeMillis();
    if (lastMissAt != Long.MIN_VALUE && now - lastMissAt < RETRY_MS) return null;
    synchronized (MemoryProfiler.class) {
      if (impl != null) return impl;
      Class<?> c = load(MemoryProfiler.class.getClassLoader());
      if (c == null) c = load(Thread.currentThread().getContextClassLoader());
      if (c != null) {
        try {
          impl = new Impl(c);
          return impl;
        } catch (Throwable ignored) {
          // an incompatible profiler version: stay a no-op
        }
      }
      lastMissAt = now;
      return null;
    }
  }

  private static Class<?> load(ClassLoader cl) {
    try {
      return Class.forName(implName, true, cl);
    } catch (Throwable t) {
      return null;
    }
  }

  /** Forgets the lookup (tests). */
  static void reset() {
    impl = null;
    lastMissAt = Long.MIN_VALUE;
  }

  private static Object call(Method m, Object... args) {
    if (m == null) return null;
    try {
      return m.invoke(null, args);
    } catch (Throwable t) {
      return null;
    }
  }

  /** True when the profiler classes are on the runtime classpath. */
  public static boolean isAvailable() {
    return impl() != null;
  }

  /** True when the profiler is available and started. */
  public static boolean isRunning() {
    Impl i = impl();
    return i != null && Boolean.TRUE.equals(call(i.isRunning));
  }

  /** {@code MemoryProfiler.mark(label)}: a cheap mark (no GC). */
  public static void mark(String label) {
    Impl i = impl();
    if (i != null) call(i.mark, label);
  }

  /** {@code MemoryProfiler.markGc(label)}: forced GC first, then a mark (retained heap). */
  public static void markGc(String label) {
    Impl i = impl();
    if (i != null) call(i.markGc, label);
  }

  /** {@code MemoryProfiler.scope(label)}. */
  public static Scope scope(String label) {
    Impl i = impl();
    if (i == null) return NOOP;
    Object s = call(i.scope, label);
    return s instanceof AutoCloseable ? new Scope((AutoCloseable) s) : NOOP;
  }

  /** {@code MemoryProfiler.scopeGc(label)}: forced GC at entry and exit. */
  public static Scope scopeGc(String label) {
    Impl i = impl();
    if (i == null) return NOOP;
    Object s = call(i.scopeGc, label);
    return s instanceof AutoCloseable ? new Scope((AutoCloseable) s) : NOOP;
  }

  /** {@code MemoryProfiler.referenceLine(mb, label)}. */
  public static void referenceLine(long mb, String label) {
    Impl i = impl();
    if (i != null) call(i.referenceLine, mb, label);
  }

  /** {@code MemoryProfiler.title(title)}. */
  public static void title(String title) {
    Impl i = impl();
    if (i != null) call(i.title, title);
  }

  /** {@code MemoryProfiler.title(title, subtitle)}. */
  public static void title(String title, String subtitle) {
    Impl i = impl();
    if (i != null) call(i.titleSub, title, subtitle);
  }

  /** {@code MemoryProfiler.heapDump(name)}; null when the profiler is absent or not running. */
  public static String heapDump(String name) {
    Impl i = impl();
    Object r = i == null ? null : call(i.heapDump, name);
    return r instanceof String ? (String) r : null;
  }
}
