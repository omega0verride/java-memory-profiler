package com.toro.al.memprofiler;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

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
        while (running) {
          try {
            sampleOnce();
            Thread.sleep(intervalMs);
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

  public synchronized void stop() {
    running = false;
    if (thread != null) thread.interrupt();
    thread = null;
  }

  public void sampleOnce() {
    MemoryUsage heap = memBean().getHeapMemoryUsage();
    long usedMB = toMB(heap.getUsed());
    long committedMB = toMB(heap.getCommitted());
    long maxMB = toMB(heap.getMax());

    DataPoint dp = new DataPoint(
        store.nextIndex(),
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
        Thread.currentThread().getName()
    );
    store.add(dp);
    if (autoGc != null) {
      autoGc.check(usedMB, store.maxObservedMB());
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
