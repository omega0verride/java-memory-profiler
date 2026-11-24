package com.toro.al.memprofiler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * First pass over a {@link PointSource}: counts, time span, peak, the sampling
 * interval and the annotated points (marks and scope ends), which are few and
 * are needed before the second pass can place anything.
 */
final class ProfileStats {

  long count;
  long sampleCount;
  long firstTs = Long.MAX_VALUE;
  long lastTs = Long.MIN_VALUE;
  long lastIndex = -1;
  long jvmMaxHeapMB = -1;
  long peakMB;
  long peakCommittedMB;
  /** Median gap between consecutive samples (ms); 0 when there are fewer than two samples. */
  long medianSampleGapMs;
  /** MARK, SCOPE_START and SCOPE_END points, in order. */
  final List<DataPoint> annotated = new ArrayList<DataPoint>();

  boolean isEmpty() {
    return count == 0;
  }

  static ProfileStats scan(PointSource src) throws IOException {
    final ProfileStats s = new ProfileStats();
    final long[] gaps = new long[4096];
    final int[] gapCount = {0};
    final long[] prevSampleTs = {Long.MIN_VALUE};
    final long[] anyMaxMB = {-1};
    src.forEach(new PointSource.Visitor() {
      public void visit(DataPoint p) {
        s.count++;
        if (p.timestamp < s.firstTs) s.firstTs = p.timestamp;
        if (p.timestamp > s.lastTs) s.lastTs = p.timestamp;
        if (p.index > s.lastIndex) s.lastIndex = p.index;
        if (p.usedMB > s.peakMB) s.peakMB = p.usedMB;
        if (p.kind != DataPoint.Kind.AUTO_GC && p.maxMB > anyMaxMB[0]) anyMaxMB[0] = p.maxMB;
        if (p.kind == DataPoint.Kind.SAMPLE) {
          s.sampleCount++;
          if (p.maxMB > s.jvmMaxHeapMB) s.jvmMaxHeapMB = p.maxMB;
          if (p.committedMB > s.peakCommittedMB) s.peakCommittedMB = p.committedMB;
          if (prevSampleTs[0] != Long.MIN_VALUE && gapCount[0] < gaps.length) {
            long g = p.timestamp - prevSampleTs[0];
            if (g > 0) gaps[gapCount[0]++] = g;
          }
          prevSampleTs[0] = p.timestamp;
        } else if (Annotations.isAnnotated(p.kind)) {
          s.annotated.add(p);
        }
      }
    });
    if (s.jvmMaxHeapMB < 0) s.jvmMaxHeapMB = anyMaxMB[0];
    if (gapCount[0] > 0) {
      long[] g = Arrays.copyOf(gaps, gapCount[0]);
      Arrays.sort(g);
      s.medianSampleGapMs = g[g.length / 2];
    }
    if (s.count == 0) {
      s.firstTs = 0;
      s.lastTs = 0;
    }
    return s;
  }

  /** Fills the data-derived fields of {@code meta}. */
  void applyTo(ExportMeta meta) {
    meta.startTime = firstTs;
    meta.endTime = lastTs;
    meta.jvmMaxHeapMB = jvmMaxHeapMB;
    meta.peakMB = peakMB;
    meta.pointCount = count;
    meta.sampleCount = sampleCount;
    if (meta.intervalMs <= 0) meta.intervalMs = medianSampleGapMs;
  }
}
