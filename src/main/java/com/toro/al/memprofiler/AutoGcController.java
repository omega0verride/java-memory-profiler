package com.toro.al.memprofiler;

/**
 * Watches each heap sample and triggers {@code System.gc()} when the user has
 * opted in to either of two automatic policies:
 *
 * <ul>
 *   <li><b>near-peak</b> — used heap has reached {@link #nearPeakRatio} of the
 *       currently observed peak, so we're about to push it higher unless
 *       reclaimable garbage is freed first.</li>
 *   <li><b>threshold</b> — used heap has crossed a fixed MB value set from
 *       the UI.</li>
 * </ul>
 *
 * Every trigger is rate-limited by {@link #cooldownMs} so a sustained-high
 * heap doesn't call GC on every sample.
 */
public class AutoGcController {

  private final DataStore store;
  private final int gcPasses;
  private final long gcPauseMs;

  private volatile boolean nearPeakEnabled = false;
  private volatile boolean thresholdEnabled = false;
  private volatile long thresholdMB = 0;
  private volatile double nearPeakRatio = 0.95;
  private volatile long cooldownMs = 2000;

  private volatile long lastTriggerTime = 0;

  public AutoGcController(DataStore store, int gcPasses, long gcPauseMs) {
    this.store = store;
    this.gcPasses = gcPasses;
    this.gcPauseMs = gcPauseMs;
  }

  public void setNearPeakEnabled(boolean v) { this.nearPeakEnabled = v; }
  public void setThresholdEnabled(boolean v) { this.thresholdEnabled = v; }
  public void setThresholdMB(long v) { this.thresholdMB = v; }
  public void setNearPeakRatio(double v) { this.nearPeakRatio = v; }
  public void setCooldownMs(long v) { this.cooldownMs = v; }

  public boolean nearPeakEnabled() { return nearPeakEnabled; }
  public boolean thresholdEnabled() { return thresholdEnabled; }
  public long thresholdMB() { return thresholdMB; }
  public double nearPeakRatio() { return nearPeakRatio; }
  public long cooldownMs() { return cooldownMs; }

  public void check(long usedMB, long peakMB) {
    if (!nearPeakEnabled && !thresholdEnabled) return;
    long now = System.currentTimeMillis();
    if (now - lastTriggerTime < cooldownMs) return;

    String reason = null;
    if (thresholdEnabled && thresholdMB > 0 && usedMB >= thresholdMB) {
      reason = "threshold " + thresholdMB + "MB";
    } else if (nearPeakEnabled && peakMB > 0
        && usedMB >= (long) Math.ceil(peakMB * nearPeakRatio)) {
      reason = "near peak " + peakMB + "MB";
    }
    if (reason == null) return;

    lastTriggerTime = now;
    recordTrigger(reason, usedMB);
    runGc();
  }

  private void recordTrigger(String reason, long usedMB) {
    DataPoint dp = new DataPoint(
        store.nextIndex(),
        System.currentTimeMillis(),
        usedMB,
        usedMB,
        usedMB,
        DataPoint.Kind.AUTO_GC,
        "auto-gc: " + reason,
        "AutoGcController",
        null,
        null,
        null,
        Thread.currentThread().getName()
    );
    store.add(dp);
  }

  private void runGc() {
    for (int i = 0; i < gcPasses; i++) {
      System.gc();
      try {
        Thread.sleep(gcPauseMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
