package com.toro.al.memprofiler;

/**
 * Keeps PNG export off the display. Before the first image is created,
 * {@code java.awt.headless} is set to {@code true}, unless the host set it
 * itself or AWT is already running (the profiler opened a browser or file
 * through {@link java.awt.Desktop}, or AWT/Java2D threads exist). In those
 * cases the property is left alone so the host application is not affected.
 */
final class Headless {

  private static volatile boolean awtUsed;

  private Headless() {}

  /** Call before the profiler itself touches AWT (for example {@code Desktop.browse}). */
  static void markAwtUsed() {
    awtUsed = true;
  }

  static void ensureForExport() {
    if (System.getProperty("java.awt.headless") != null) return;
    if (awtUsed || awtThreadsRunning()) return;
    System.setProperty("java.awt.headless", "true");
  }

  private static boolean awtThreadsRunning() {
    ThreadGroup root = Thread.currentThread().getThreadGroup();
    while (root.getParent() != null) root = root.getParent();
    Thread[] threads = new Thread[root.activeCount() + 16];
    int n = root.enumerate(threads, true);
    for (int i = 0; i < n; i++) {
      String name = threads[i].getName();
      if (name.startsWith("AWT-") || name.startsWith("Java2D") || name.startsWith("TimerQueue")) return true;
    }
    return false;
  }
}
