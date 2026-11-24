package com.toro.al.memprofiler;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Push-based counterpart to {@link HeapSampler}. Subscribes to every
 * {@link GarbageCollectorMXBean} and emits a {@link DataPoint.Kind#GC}
 * point for each collection, so GC pauses that happen between samples
 * are never missed.
 */
public class GcListener {

  private final DataStore store;
  private final Set<String> heapPoolNames;
  private final List<NotificationEmitter> emitters = new ArrayList<NotificationEmitter>();
  private final List<NotificationListener> listeners = new ArrayList<NotificationListener>();
  private boolean running = false;

  public GcListener(DataStore store) {
    this.store = store;
    this.heapPoolNames = Collections.unmodifiableSet(collectHeapPoolNames());
  }

  public synchronized void start() {
    if (running) return;
    running = true;
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (!(gc instanceof NotificationEmitter)) continue;
      NotificationEmitter emitter = (NotificationEmitter) gc;
      NotificationListener listener = new NotificationListener() {
        public void handleNotification(Notification notification, Object handback) {
          try {
            handle(notification);
          } catch (Throwable ignored) {
          }
        }
      };
      try {
        emitter.addNotificationListener(listener, null, null);
        emitters.add(emitter);
        listeners.add(listener);
      } catch (Throwable ignored) {
      }
    }
  }

  public synchronized void stop() {
    running = false;
    for (int i = 0; i < emitters.size(); i++) {
      try {
        emitters.get(i).removeNotificationListener(listeners.get(i));
      } catch (Throwable ignored) {
      }
    }
    emitters.clear();
    listeners.clear();
  }

  private void handle(Notification notification) {
    if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
        .equals(notification.getType())) return;
    Object userData = notification.getUserData();
    if (!(userData instanceof CompositeData)) return;

    GarbageCollectionNotificationInfo info =
        GarbageCollectionNotificationInfo.from((CompositeData) userData);
    GcInfo gcInfo = info.getGcInfo();
    if (gcInfo == null) return;

    long beforeBytes = sumHeapUsed(gcInfo.getMemoryUsageBeforeGc());
    long afterBytes = sumHeapUsed(gcInfo.getMemoryUsageAfterGc());

    MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    long usedMB = HeapSampler.toMB(afterBytes);
    long committedMB = HeapSampler.toMB(heap.getCommitted());
    long maxMB = HeapSampler.toMB(heap.getMax());
    long deltaMB = HeapSampler.toMB(afterBytes) - HeapSampler.toMB(beforeBytes);
    long durationMs = gcInfo.getDuration();

    DataPoint dp = new DataPoint(
        -1, // assigned by DataStore.add
        System.currentTimeMillis(),
        usedMB,
        committedMB,
        maxMB,
        DataPoint.Kind.GC,
        info.getGcAction(),
        info.getGcName() + " / " + info.getGcCause(),
        null,
        Long.valueOf(deltaMB),
        Long.valueOf(durationMs),
        Thread.currentThread().getName()
    );
    store.add(dp);
  }

  private long sumHeapUsed(Map<String, MemoryUsage> pools) {
    if (pools == null) return 0;
    long total = 0;
    for (Map.Entry<String, MemoryUsage> e : pools.entrySet()) {
      if (!heapPoolNames.contains(e.getKey())) continue;
      long u = e.getValue().getUsed();
      if (u > 0) total += u;
    }
    return total;
  }

  private static Set<String> collectHeapPoolNames() {
    Set<String> names = new HashSet<String>();
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getType() == MemoryType.HEAP) names.add(pool.getName());
    }
    return names;
  }
}
