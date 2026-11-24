package com.toro.al.memprofiler;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;

public class HeapSampler {

  private final DataStore store;
  private final long intervalMs;
  private final AutoGcController autoGc;
  private volatile boolean running = false;
  private Thread thread;

  public HeapSampler(DataStore store, long intervalMs) {
    this(store, intervalMs, null);
  }

  public HeapSampler(DataStore store, long intervalMs, AutoGcController autoGc) {
    this.store = store;
    this.intervalMs = intervalMs;
    this.autoGc = autoGc;
  }

  public synchronized void start() {
    if (running) return;
    running = true;
    thread = new Thread(new Runnable() {
      public void run() {
        // fixed rate: a tick every intervalMs (sampling time does not add up); after
        // a stall longer than one interval (a long GC) sample at once, then resume
        long next = System.currentTimeMillis();
        while (running) {
          try {
            sampleOnce();
            next += intervalMs;
            long now = System.currentTimeMillis();
            if (now - next > intervalMs) next = now;
            long wait = next - now;
            if (wait > 0) Thread.sleep(wait);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          } catch (Throwable ignored) {
          }
        }
      }
    }, "mem-profiler-sampler");
    thread.setDaemon(true);
    thread.start();
  }

  /** True between {@link #start()} and {@link #stop()}. */
  boolean isRunning() { return running; }

  public synchronized void stop() {
    running = false;
    Thread t = thread;
    thread = null;
    if (t == null) return;
    t.interrupt();
    // Let a sample that is already being taken land before stop() returns, so
    // whatever runs next (the on-stop exports) sees the final data.
    if (t != Thread.currentThread()) {
      try {
        t.join(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public void sampleOnce() {
    MemoryUsage heap = memBean().getHeapMemoryUsage();
    long usedMB = toMB(heap.getUsed());
    long committedMB = toMB(heap.getCommitted());
    long maxMB = toMB(heap.getMax());

    long gcTotal = gcTotalMs();
    DataPoint dp = new DataPoint(
        -1,
        System.currentTimeMillis(),
        usedMB,
        committedMB,
        maxMB,
        DataPoint.Kind.SAMPLE,
        null,
        null,
        null,
        null,
        null,
        null,
        Thread.currentThread().getName(),
        null,
        gcTotal >= 0 ? Long.valueOf(gcTotal) : null
    );
    store.add(dp);
    if (autoGc != null) {
      autoGc.check(usedMB, store.maxObservedMB());
    }
  }

  private static volatile List<GarbageCollectorMXBean> gcBeans;

  /**
   * Cumulative collection time of all collectors (ms), or -1 if unavailable. The
   * JVM keeps these counters itself, so they are exact even when GC
   * notifications are dropped (heap exhausted).
   */
  static long gcTotalMs() {
    try {
      List<GarbageCollectorMXBean> beans = gcBeans;
      if (beans == null) gcBeans = beans = ManagementFactory.getGarbageCollectorMXBeans();
      long total = 0;
      boolean any = false;
      for (GarbageCollectorMXBean b : beans) {
        long t = b.getCollectionTime();
        if (t >= 0) { total += t; any = true; }
      }
      return any ? total : -1;
    } catch (Throwable t) {
      return -1;
    }
  }

  static MemoryMXBean memBean() {
    return ManagementFactory.getMemoryMXBean();
  }

  static long toMB(long bytes) {
    if (bytes < 0) return -1;
    return bytes / (1024L * 1024L);
  }
}
