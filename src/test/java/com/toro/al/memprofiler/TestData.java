package com.toro.al.memprofiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Synthetic profiles for the export tests. */
final class TestData {

  static final long T0 = 1764000000000L;

  private TestData() {}

  static final class Builder {
    final List<DataPoint> points = new ArrayList<DataPoint>();
    long index = 0;
    long maxMB = 8192;

    DataPoint sample(long ts, long used) {
      return add(new DataPoint(index++, ts, used, used + 300, maxMB, DataPoint.Kind.SAMPLE,
          null, null, null, null, null, null, "mem-profiler-sampler"));
    }

    DataPoint mark(long ts, long used, String label) {
      return add(new DataPoint(index++, ts, used, used + 300, maxMB, DataPoint.Kind.MARK, label,
          "com.acme.loader.CacheLoaderTest.testFullLoad:" + (100 + index % 50),
          "com/acme/loader/CacheLoaderTest.java:" + (100 + index % 50), null, null, null, "main"));
    }

    DataPoint gc(long ts, long used) {
      return add(new DataPoint(index++, ts, used, used + 300, maxMB, DataPoint.Kind.GC, "end of minor GC",
          "G1 Young Generation / G1 Evacuation Pause", null, null, Long.valueOf(-50), Long.valueOf(7), "Service Thread"));
    }

    /** A GC event that ends at {@code ts} after a pause of {@code pauseMs}. */
    DataPoint gcPause(long ts, long used, long pauseMs) {
      return add(new DataPoint(index++, ts, used, used + 300, maxMB, DataPoint.Kind.GC, "end of major GC",
          "PS MarkSweep / Ergonomics", null, null, Long.valueOf(0), Long.valueOf(pauseMs), "Service Thread"));
    }

    /** A sample with the cumulative GC time counter. */
    DataPoint sampleWithGc(long ts, long used, long gcTotalMs) {
      return add(new DataPoint(index++, ts, used, used + 300, maxMB, DataPoint.Kind.SAMPLE,
          null, null, null, null, null, null, "mem-profiler-sampler", null, Long.valueOf(gcTotalMs)));
    }

    DataPoint scopeStart(long ts, long used, String label, String id) {
      return add(new DataPoint(index++, ts, used, used + 300, maxMB, DataPoint.Kind.SCOPE_START, label,
          "com.acme.Job.run:10", "com/acme/Job.java:10", id, null, null, "main"));
    }

    DataPoint scopeEnd(long ts, long used, String label, String id, long delta, long dur) {
      return add(new DataPoint(index++, ts, used, used + 300, maxMB, DataPoint.Kind.SCOPE_END, label,
          "com.acme.Job.run:20", "com/acme/Job.java:20", id, Long.valueOf(delta), Long.valueOf(dur), "main"));
    }

    DataPoint reference(long ts, long mb, String label) {
      return add(new DataPoint(index++, ts, mb, 0, maxMB, DataPoint.Kind.REFERENCE, label,
          null, null, null, null, null, "main"));
    }

    DataPoint add(DataPoint p) {
      points.add(p);
      return p;
    }

    PointSource source(String title) {
      ExportMeta m = new ExportMeta();
      m.title = title;
      m.processName = "com.acme.loader.CacheLoaderTest";
      m.pid = 4242;
      m.intervalMs = 250;
      m.profilerVersion = "test";
      return new PointSource.Fixed(points, m);
    }
  }

  /**
   * A 9-minute run sampled every 250 ms with 14 numbered marks; marks 3, 4 and
   * 5 fall within 10 seconds, mark 6 repeats three times, there is a
   * one-sample 6.8 GB spike, two scopes and a 5 GB reference line.
   */
  static Builder nineMinuteRun() {
    Builder b = new Builder();
    Random rnd = new Random(42);
    int n = 9 * 60 * 4;
    b.reference(T0, 5120, "Production heap limit (5 GB)");
    String[] labels = {
        "1. Live load start", "2. Live load done", "3. DB cache: warm-up", "4. DB cache: first batch",
        "5. DB cache: all rows in one batch (peak)", "6. progress 25%", "6. progress 50%", "6. progress 75%",
        "7. DB cache released", "8. Report built", "9. Export done", "10. Cleanup", "11. Shutdown", "12. End"};
    int[] at = {8, 60, 200, 216, 238, 400, 520, 640, 900, 1100, 1400, 1700, 1900, 2100};
    int m = 0;
    for (int i = 0; i < n; i++) {
      long ts = T0 + i * 250L;
      long base = i < 60 ? 400 + i * 20 : i < 200 ? 1600 : i < 240 ? 1600 + (i - 200) * 80 : i < 900 ? 4200 : 1200;
      long used = base + rnd.nextInt(400);
      if (i == 777) used = 6963;
      b.sample(ts, used);
      if (i % 37 == 5) b.gc(ts + 3, base * 8 / 10);
      if (i == 190) b.scopeStart(ts + 5, base, "db-cache", "s1");
      if (i == 260) b.scopeEnd(ts + 5, base, "db-cache", "s1", 80, 17500);
      if (i == 380) b.scopeStart(ts + 5, base, "progress loop", "s2");
      if (i == 700) b.scopeEnd(ts + 5, base, "progress loop", "s2", 90, 80000);
      if (m < at.length && i == at[m]) b.mark(ts + 9, base * 85 / 100, labels[m++]);
    }
    return b;
  }
}
