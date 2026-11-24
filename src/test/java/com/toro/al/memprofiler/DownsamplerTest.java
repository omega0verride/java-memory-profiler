package com.toro.al.memprofiler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownsamplerTest {

  private static List<DataPoint> run(List<DataPoint> in, int max) {
    long samples = 0, lastIndex = -1;
    for (DataPoint p : in) {
      if (p.kind == DataPoint.Kind.SAMPLE) samples++;
      lastIndex = Math.max(lastIndex, p.index);
    }
    final List<DataPoint> out = new ArrayList<DataPoint>();
    Downsampler d = new Downsampler(samples, max, lastIndex, new Downsampler.Sink() {
      public void accept(DataPoint p) { out.add(p); }
    });
    for (DataPoint p : in) d.visit(p);
    d.finish();
    return out;
  }

  @Test
  void keepsEveryEventAndEveryBucketMinMax() {
    TestData.Builder b = new TestData.Builder();
    Random rnd = new Random(1);
    int n = 100_000;
    for (int i = 0; i < n; i++) {
      long ts = TestData.T0 + i * 100L;
      long used = 1000 + rnd.nextInt(500);
      if (i == 54_321) used = 6963; // one-sample spike
      if (i == 77_777) used = 3;    // one-sample trough
      b.sample(ts, used);
      if (i % 200 == 0) b.gc(ts + 1, used / 2);
      if (i % 5000 == 0) b.mark(ts + 2, used, (i / 5000) + ". step");
      if (i % 7000 == 0) b.scopeStart(ts + 3, used, "s", "s" + i);
      if (i % 7000 == 10) b.scopeEnd(ts + 3, used, "s", "s" + (i - 10), 1, 1);
    }
    int max = 20_000;
    List<DataPoint> out = run(b.points, max);

    Set<Long> outIdx = new HashSet<Long>();
    int samples = 0;
    long prevIndex = -1;
    for (DataPoint p : out) {
      assertTrue(p.index > prevIndex, "output must stay in index order");
      prevIndex = p.index;
      outIdx.add(p.index);
      if (p.kind == DataPoint.Kind.SAMPLE) samples++;
    }
    assertTrue(samples <= max, "kept " + samples + " samples, limit " + max);
    assertTrue(samples > max * 0.9, "should use most of the budget, kept " + samples);

    // every non-sample point survives
    for (DataPoint p : b.points) {
      if (p.kind != DataPoint.Kind.SAMPLE) assertTrue(outIdx.contains(p.index), p.kind + " #" + p.index + " dropped");
    }

    // per-bucket min and max survive (same bucketing as Downsampler)
    List<DataPoint> all = new ArrayList<DataPoint>();
    for (DataPoint p : b.points) if (p.kind == DataPoint.Kind.SAMPLE) all.add(p);
    long buckets = (max - 2) / 2;
    DataPoint[] mins = new DataPoint[(int) buckets], maxs = new DataPoint[(int) buckets];
    for (int i = 0; i < all.size(); i++) {
      int bk = (int) ((long) i * buckets / all.size());
      DataPoint p = all.get(i);
      if (mins[bk] == null || p.usedMB < mins[bk].usedMB) mins[bk] = p;
      if (maxs[bk] == null || p.usedMB > maxs[bk].usedMB) maxs[bk] = p;
    }
    for (int k = 0; k < buckets; k++) {
      assertTrue(outIdx.contains(mins[k].index), "bucket " + k + " min dropped");
      assertTrue(outIdx.contains(maxs[k].index), "bucket " + k + " max dropped");
    }
    // global extremes, first and last sample
    assertTrue(contains(out, 6963), "spike lost");
    assertTrue(contains(out, 3), "trough lost");
    assertTrue(outIdx.contains(all.get(0).index));
    assertTrue(outIdx.contains(all.get(all.size() - 1).index));
  }

  @Test
  void passesThroughWhenUnderTheLimit() {
    TestData.Builder b = TestData.nineMinuteRun();
    List<DataPoint> in = new ArrayList<DataPoint>();
    for (DataPoint p : b.points) if (p.kind != DataPoint.Kind.REFERENCE) in.add(p);
    List<DataPoint> out = run(in, 20_000);
    assertEquals(in.size(), out.size());
    for (int i = 0; i < in.size(); i++) assertSame(in.get(i), out.get(i));
  }

  private static boolean contains(List<DataPoint> out, long used) {
    for (DataPoint p : out) if (p.kind == DataPoint.Kind.SAMPLE && p.usedMB == used) return true;
    return false;
  }
}
