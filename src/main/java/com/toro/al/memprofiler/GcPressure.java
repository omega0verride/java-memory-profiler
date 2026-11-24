package com.toro.al.memprofiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GC pressure of a run: total GC time, the longest pause, the GC share per
 * minute, and the time ranges where GC takes more than a threshold of wall
 * time ("GC overhead 90%"), which the PNG, the HTML export and the live chart shade.
 *
 * <p>GC time is the union of the recorded GC pauses, the forced GCs of marks
 * and scopes, the samples' GC counters, and the sampler stalls that the gap
 * rule ({@link GapRule}) bridges as GC-bound (the JVM drops GC notifications
 * when the heap is exhausted, so the stalls fill in what the events miss).
 */
final class GcPressure {

  static final int DEFAULT_THRESHOLD_PCT = 50;
  static final long MIN_WINDOW_MS = 10_000;

  long totalMs;
  long longestMs;
  long durationMs;
  long windowMs;
  int thresholdPct;
  /** GC share (0–100) per minute of the run. */
  final List<Integer> perMinute = new ArrayList<Integer>();
  /** {from, to, sharePct} ranges at or above the threshold. */
  final List<long[]> regions = new ArrayList<long[]>();

  /**
   * @param thresholdPct shade windows where GC takes at least this share of wall time (0 = no regions)
   */
  static GcPressure of(ProfileStats stats, GapRule.Result gaps, int thresholdPct) {
    GcPressure p = new GcPressure();
    p.thresholdPct = thresholdPct;
    p.totalMs = stats.gcTotalMs();
    p.longestMs = stats.longestGcMs;
    long start = stats.firstTs, end = stats.lastTs;
    p.durationMs = Math.max(0, end - start);
    if (p.durationMs <= 0) return p;
    GapRule.GcTime gc = stats.gapInput.gcTime();
    p.windowMs = Math.max(MIN_WINDOW_MS, p.durationMs / 240);
    for (long t = start; t < end; t += 60_000) {
      long e = Math.min(end, t + 60_000);
      p.perMinute.add(Integer.valueOf((int) Math.round(100.0 * busy(gc, gaps, t, e) / Math.max(1, e - t))));
    }
    if (thresholdPct <= 0) return p;
    long regionStart = -1, regionGc = 0;
    for (long t = start; t < end; t += p.windowMs) {
      long e = Math.min(end, t + p.windowMs);
      long b = busy(gc, gaps, t, e);
      boolean hot = e > t && b * 100 >= (long) thresholdPct * (e - t);
      if (hot) {
        if (regionStart < 0) { regionStart = t; regionGc = 0; }
        regionGc += b;
      }
      if ((!hot || e >= end) && regionStart >= 0) {
        long regionEnd = hot ? e : t;
        p.regions.add(new long[] {regionStart, regionEnd,
            Math.round(100.0 * regionGc / Math.max(1, regionEnd - regionStart))});
        regionStart = -1;
      }
    }
    return p;
  }

  /** GC time in {@code [a, b]}: GC pauses and counters, or the bridged gaps, whichever is larger. */
  static long busy(GapRule.GcTime gc, GapRule.Result gaps, long a, long b) {
    long g = gc.within(a, b);
    long stalled = 0;
    if (gaps != null) {
      for (GapRule.Gap gap : gaps.gaps) {
        if (!gap.bridged || gap.to <= a || gap.from >= b) continue;
        stalled += Math.min(gap.to, b) - Math.max(gap.from, a);
      }
    }
    return Math.min(b - a, Math.max(g, stalled));
  }

  int sharePct() {
    return durationMs > 0 ? (int) Math.round(100.0 * totalMs / durationMs) : 0;
  }

  /** {@code "GC 1:12 (40% of the run), longest pause 3.2 s"}. */
  String summary() {
    StringBuilder sb = new StringBuilder("GC ").append(PngRenderer.formatDurationShort(totalMs));
    if (durationMs > 0) sb.append(" (").append(sharePct()).append("% of the run)");
    if (longestMs > 0) sb.append(", longest pause ").append(PngRenderer.formatDurationShort(longestMs));
    return sb.toString();
  }

  /** {@code "0:00 3%, 1:00 41%, …"}. */
  String perMinuteText() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < perMinute.size(); i++) {
      if (i > 0) sb.append(", ");
      sb.append(i).append(":00 ").append(perMinute.get(i)).append('%');
    }
    return sb.toString();
  }

  void appendJson(StringBuilder sb) {
    sb.append("{\"totalMs\":").append(totalMs);
    sb.append(",\"longestMs\":").append(longestMs);
    sb.append(",\"sharePct\":").append(sharePct());
    sb.append(",\"thresholdPct\":").append(thresholdPct);
    sb.append(",\"windowMs\":").append(windowMs);
    sb.append(",\"perMinute\":[");
    for (int i = 0; i < perMinute.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(perMinute.get(i));
    }
    sb.append("],\"regions\":[");
    for (int i = 0; i < regions.size(); i++) {
      long[] r = regions.get(i);
      if (i > 0) sb.append(',');
      sb.append('[').append(r[0]).append(',').append(r[1]).append(',').append(r[2]).append(']');
    }
    sb.append("]}");
  }

  static String pct(long v) {
    return String.format(Locale.ROOT, "%d%%", v);
  }
}
