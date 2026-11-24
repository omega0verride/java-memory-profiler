package com.toro.al.memprofiler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Streaming min/max downsampler for SAMPLE points.
 *
 * <p>Every non-SAMPLE point passes through. When there are more samples than
 * {@code maxSamples}, the samples are split by position into
 * {@code (maxSamples - 2) / 2} equal buckets and each bucket keeps only its
 * lowest and highest {@code usedMB} sample (first occurrence on ties), plus the
 * very first and very last sample of the run. Peaks and troughs survive, a
 * one-sample spike included, and the result never exceeds {@code maxSamples}.
 *
 * <p>Output order is the input order (by {@code index}). Memory use is one
 * bucket's worth of events.
 */
final class Downsampler implements PointSource.Visitor {

  interface Sink {
    void accept(DataPoint p);
  }

  private static final Comparator<DataPoint> BY_INDEX = new Comparator<DataPoint>() {
    public int compare(DataPoint a, DataPoint b) {
      return a.index < b.index ? -1 : a.index > b.index ? 1 : 0;
    }
  };

  private final Sink sink;
  private final long totalSamples;
  private final long buckets;
  private final boolean active;
  private final long lastIndex;

  private long ordinal = 0;
  private long bucket = -1;
  private DataPoint min, max, first, last;
  private final List<DataPoint> pendingEvents = new ArrayList<DataPoint>();
  private long emittedSamples = 0;

  /**
   * @param totalSamples number of SAMPLE points the source will deliver (from a first pass)
   * @param maxSamples   keep at most this many SAMPLE points
   * @param lastIndex    ignore points with a higher index (data that arrived after the first pass)
   */
  Downsampler(long totalSamples, int maxSamples, long lastIndex, Sink sink) {
    this.sink = sink;
    this.totalSamples = totalSamples;
    this.lastIndex = lastIndex;
    this.active = maxSamples > 0 && totalSamples > maxSamples;
    this.buckets = Math.max(1, (Math.max(4, maxSamples) - 2) / 2);
  }

  public void visit(DataPoint p) {
    if (p.index > lastIndex) return;
    if (!active) {
      if (p.kind == DataPoint.Kind.SAMPLE) emittedSamples++;
      sink.accept(p);
      return;
    }
    if (p.kind != DataPoint.Kind.SAMPLE) {
      pendingEvents.add(p);
      return;
    }
    long ord = ordinal++;
    if (ord >= totalSamples) return; // more samples than the first pass saw
    long b = ord * buckets / totalSamples;
    if (b != bucket) {
      flush();
      bucket = b;
    }
    if (ord == 0) first = p;
    if (ord == totalSamples - 1) last = p;
    if (min == null || p.usedMB < min.usedMB) min = p;
    if (max == null || p.usedMB > max.usedMB) max = p;
  }

  /** Emits whatever is still buffered. Call once after the pass. */
  void finish() {
    if (active) flush();
  }

  long emittedSamples() {
    return emittedSamples;
  }

  private void flush() {
    List<DataPoint> out = new ArrayList<DataPoint>(pendingEvents.size() + 4);
    out.addAll(pendingEvents);
    pendingEvents.clear();
    addSample(out, first);
    addSample(out, min);
    addSample(out, max);
    addSample(out, last);
    first = min = max = last = null;
    Collections.sort(out, BY_INDEX);
    for (DataPoint p : out) sink.accept(p);
  }

  private void addSample(List<DataPoint> out, DataPoint p) {
    if (p == null) return;
    for (DataPoint q : out) if (q == p) return;
    out.add(p);
    emittedSamples++;
  }
}
