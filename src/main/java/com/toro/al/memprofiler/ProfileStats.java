package com.toro.al.memprofiler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * First pass over a {@link PointSource}: counts, time span, peak, the sampling
 * interval, GC totals, the gap rule input and the annotated points (marks and
 * scope ends), which are few and are needed before the second pass can place
 * anything.
 *
 * <p>{@code REFERENCE} points are never counted: their {@code usedMB} is a line
 * value, not a heap reading.
 */
final class ProfileStats {

  long count;
  long sampleCount;
  long firstTs = Long.MAX_VALUE;
  long lastTs = Long.MIN_VALUE;
  long lastIndex = -1;
  long jvmMaxHeapMB = -1;
  /** Highest heap reading of any point (samples and events), REFERENCE excluded. */
  long peakMB;
  long peakTs;
  long peakCommittedMB;
  /** Median gap between consecutive samples (ms); 0 when there are fewer than two samples. */
  long medianSampleGapMs;
  /** Highest reading among annotated points (MARK, SCOPE_*): "peak retained" for markGc runs. */
  long peakRetainedMB = -1;
  /** Reading of the last annotated point, -1 if none. */
  long lastAnnotatedMB = -1;
  /** Sum of {@code durationMs} of the GC events. */
  long gcEventMs;
  long gcEventCount;
  long longestGcMs;
  /** Cumulative GC time from the samples' counters (last − first), -1 when not recorded. */
  long gcCounterMs = -1;
  /** Forced-GC time recorded on marks and scopes. */
  long forcedGcMs;
  /** MARK, SCOPE_START and SCOPE_END points, in order. */
  final List<DataPoint> annotated = new ArrayList<DataPoint>();
  /** GC events (few compared to samples), in order. */
  final List<DataPoint> gcEvents = new ArrayList<DataPoint>();
  final GapRule.Collector gapInput = new GapRule.Collector();

  boolean isEmpty() {
    return count == 0;
  }

  /** Total GC time: the counters when recorded, else the GC events. */
  long gcTotalMs() {
    return gcCounterMs >= 0 ? Math.max(gcCounterMs, 0) : gcEventMs;
  }

  /** The gap rule for this profile; {@code configuredIntervalMs} 0 = use the median. */
  GapRule.Result gaps(long configuredIntervalMs) {
    return gapInput.result(configuredIntervalMs);
  }

  static ProfileStats scan(PointSource src) throws IOException {
    final ProfileStats s = new ProfileStats();
    final long[] anyMaxMB = {-1};
    final long[] firstCounter = {Long.MIN_VALUE}, lastCounter = {Long.MIN_VALUE};
    src.forEach(new PointSource.Visitor() {
      public void visit(DataPoint p) {
        if (p.kind == DataPoint.Kind.REFERENCE) return;
        s.gapInput.visit(p);
        s.count++;
        if (p.timestamp < s.firstTs) s.firstTs = p.timestamp;
        if (p.timestamp > s.lastTs) s.lastTs = p.timestamp;
        if (p.index > s.lastIndex) s.lastIndex = p.index;
        if (p.usedMB > s.peakMB) { s.peakMB = p.usedMB; s.peakTs = p.timestamp; }
        if (p.kind != DataPoint.Kind.AUTO_GC && p.maxMB > anyMaxMB[0]) anyMaxMB[0] = p.maxMB;
        if (p.gcMs != null) s.forcedGcMs += p.gcMs.longValue();
        if (p.kind == DataPoint.Kind.SAMPLE) {
          s.sampleCount++;
          if (p.maxMB > s.jvmMaxHeapMB) s.jvmMaxHeapMB = p.maxMB;
          if (p.committedMB > s.peakCommittedMB) s.peakCommittedMB = p.committedMB;
          if (p.gcTotalMs != null) {
            if (firstCounter[0] == Long.MIN_VALUE) firstCounter[0] = p.gcTotalMs.longValue();
            lastCounter[0] = p.gcTotalMs.longValue();
          }
        } else if (p.kind == DataPoint.Kind.GC) {
          s.gcEvents.add(p);
          s.gcEventCount++;
          long d = p.durationMs == null ? 0 : p.durationMs.longValue();
          s.gcEventMs += d;
          if (d > s.longestGcMs) s.longestGcMs = d;
        } else if (Annotations.isAnnotated(p.kind)) {
          s.annotated.add(p);
          if (p.usedMB > s.peakRetainedMB) s.peakRetainedMB = p.usedMB;
          s.lastAnnotatedMB = p.usedMB;
        }
      }
    });
    if (s.jvmMaxHeapMB < 0) s.jvmMaxHeapMB = anyMaxMB[0];
    s.medianSampleGapMs = s.gapInput.medianSampleGapMs();
    if (firstCounter[0] != Long.MIN_VALUE) s.gcCounterMs = lastCounter[0] - firstCounter[0];
    if (s.count == 0) {
      s.firstTs = 0;
      s.lastTs = 0;
    }
    return s;
  }

  /** Fills the data-derived fields of {@code meta}. The configured interval is left alone. */
  void applyTo(ExportMeta meta) {
    meta.startTime = firstTs;
    meta.endTime = lastTs;
    meta.jvmMaxHeapMB = jvmMaxHeapMB;
    meta.peakMB = peakMB;
    meta.peakRetainedMB = peakRetainedMB;
    meta.pointCount = count;
    meta.sampleCount = sampleCount;
    meta.medianSampleGapMs = medianSampleGapMs;
    meta.gcTotalMs = gcTotalMs();
    meta.longestGcMs = longestGcMs;
  }
}
