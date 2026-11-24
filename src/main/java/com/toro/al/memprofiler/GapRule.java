package com.toro.al.memprofiler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The one definition of where the heap line is continuous, dashed or broken.
 * The PNG renderer applies it directly; the live chart and the HTML export get
 * its result as data ({@code gaps} in {@code /data} and in the export), so all
 * three draw the same line.
 *
 * <h3>Rule</h3>
 * <ol>
 *   <li>The heap line goes through every point except {@code REFERENCE}, in time order.</li>
 *   <li>{@code interval} is the configured sampling interval (the JSONL {@code META}
 *       line), or the median gap between samples when it is unknown.</li>
 *   <li>Two consecutive line points more than {@code dashAfter = max(4 × interval, 1 s)}
 *       apart form a <i>gap</i>. Gaps closer than {@code dashAfter} to each other
 *       form one <i>stall</i> of the sampler.</li>
 *   <li>The GC time of a stretch is the union of the GC pauses in it
 *       ({@code GC} events: {@code [timestamp − durationMs, timestamp]}; forced GCs of
 *       {@code markGc}/{@code scopeGc}: {@code [timestamp − gcMs, timestamp]}), or the
 *       difference of the samples' cumulative {@code gcTotalMs} when that is larger.</li>
 *   <li>A stall is <b>bridged</b> when its time not accounted for by GC is at most
 *       {@code breakAfter = max(10 × interval, 5 s)}, or when GC accounts for at least
 *       25% of it. (The JVM drops GC notifications when the heap is exhausted, so a
 *       GC-bound stall can show well under 100%.) Every gap of a bridged stall is drawn
 *       as a <b>dashed</b> segment; the area fill stays continuous under it.</li>
 *   <li>Exception: the first or last gap of a bridged stall still breaks when it has no
 *       GC at all, is longer than {@code breakAfter} and normal sampling continues on
 *       its other side (a debugger pause right after a GC stall). A gap that ends the
 *       run stays bridged.</li>
 *   <li>Otherwise the stall is a real gap in observation (debugger pause, suspended VM):
 *       each of its gaps <b>breaks</b> the line, unless GC accounts for at least half
 *       of that gap (then it is dashed).</li>
 * </ol>
 */
final class GapRule {

  static final int DASH_FACTOR = 4;
  static final long DASH_MIN_MS = 1000;
  static final int BREAK_FACTOR = 10;
  static final long BREAK_MIN_MS = 5000;
  static final double STALL_GC_SHARE = 0.25;
  static final double GAP_GC_SHARE = 0.5;

  static final int SOLID = 0, DASHED = 1, BREAK = 2;

  private GapRule() {}

  static long dashAfterMs(long intervalMs) {
    return Math.max(DASH_FACTOR * Math.max(0, intervalMs), DASH_MIN_MS);
  }

  static long breakAfterMs(long intervalMs) {
    return Math.max(BREAK_FACTOR * Math.max(0, intervalMs), BREAK_MIN_MS);
  }

  static final class Gap {
    final long from, to;
    final boolean bridged;
    /** GC time accounted inside the gap. */
    final long gcMs;

    Gap(long from, long to, boolean bridged, long gcMs) {
      this.from = from;
      this.to = to;
      this.bridged = bridged;
      this.gcMs = gcMs;
    }

    long length() { return to - from; }
  }

  static final class Result {
    long intervalMs;
    long dashAfterMs;
    long breakAfterMs;
    /** Sorted by {@code from}, disjoint. */
    final List<Gap> gaps = new ArrayList<Gap>();

    /** {@link #SOLID}, {@link #DASHED} or {@link #BREAK} for a line segment from {@code a} to {@code b}. */
    int style(long a, long b) {
      if (b - a <= dashAfterMs || gaps.isEmpty()) return SOLID;
      int lo = 0, hi = gaps.size();
      while (lo < hi) { // first gap with to > a
        int mid = (lo + hi) >>> 1;
        if (gaps.get(mid).to > a) hi = mid; else lo = mid + 1;
      }
      int style = SOLID;
      for (int i = lo; i < gaps.size() && gaps.get(i).from < b; i++) {
        if (!gaps.get(i).bridged) return BREAK;
        style = DASHED;
      }
      return style;
    }

    int breaks() {
      int n = 0;
      for (Gap g : gaps) if (!g.bridged) n++;
      return n;
    }

    int bridged() {
      return gaps.size() - breaks();
    }

    /** {@code {"intervalMs":…,"dashAfterMs":…,"breakAfterMs":…,"list":[[from,to,bridged,gcMs],…]}} */
    void appendJson(StringBuilder sb) {
      sb.append("{\"intervalMs\":").append(intervalMs);
      sb.append(",\"dashAfterMs\":").append(dashAfterMs);
      sb.append(",\"breakAfterMs\":").append(breakAfterMs);
      sb.append(",\"list\":[");
      for (int i = 0; i < gaps.size(); i++) {
        Gap g = gaps.get(i);
        if (i > 0) sb.append(',');
        sb.append('[').append(g.from).append(',').append(g.to).append(',')
            .append(g.bridged ? 1 : 0).append(',').append(g.gcMs).append(']');
      }
      sb.append("]}");
    }
  }

  /** Growable primitive list. */
  static final class Longs {
    long[] a = new long[256];
    int n;

    void add(long v) {
      if (n == a.length) a = Arrays.copyOf(a, n * 2);
      a[n++] = v;
    }

    long[] sorted() {
      long[] c = Arrays.copyOf(a, n);
      Arrays.sort(c);
      return c;
    }
  }

  /**
   * Collects what the rule needs in one pass over the points (any order), then
   * computes the gaps. Memory: one long per point plus two per GC pause.
   */
  static final class Collector implements PointSource.Visitor {
    final Longs ts = new Longs();
    final Longs sampleTs = new Longs();
    final Longs gcStart = new Longs();
    final Longs gcEnd = new Longs();
    /** Samples with a cumulative GC counter, packed as pairs (ts, gcTotalMs). */
    final Longs counters = new Longs();

    public void visit(DataPoint p) {
      if (p.kind == DataPoint.Kind.REFERENCE) return;
      ts.add(p.timestamp);
      if (p.kind == DataPoint.Kind.SAMPLE) {
        sampleTs.add(p.timestamp);
        if (p.gcTotalMs != null) {
          counters.add(p.timestamp);
          counters.add(p.gcTotalMs.longValue());
        }
      }
      if (p.kind == DataPoint.Kind.GC && p.durationMs != null && p.durationMs.longValue() > 0) {
        gcStart.add(p.timestamp - p.durationMs.longValue());
        gcEnd.add(p.timestamp);
      }
      if (p.gcMs != null && p.gcMs.longValue() > 0) {
        gcStart.add(p.timestamp - p.gcMs.longValue());
        gcEnd.add(p.timestamp);
      }
    }

    /** Median gap between samples (ms); 0 with fewer than two samples. */
    long medianSampleGapMs() {
      long[] s = sampleTs.sorted();
      if (s.length < 2) return 0;
      long[] g = new long[s.length - 1];
      int k = 0;
      for (int i = 1; i < s.length; i++) if (s[i] > s[i - 1]) g[k++] = s[i] - s[i - 1];
      if (k == 0) return 0;
      g = Arrays.copyOf(g, k);
      Arrays.sort(g);
      return g[k / 2];
    }

    GcTime gcTime() {
      return new GcTime(gcStart, gcEnd, counters);
    }

    /** @param configuredIntervalMs the configured interval, or 0 to use the median sample gap */
    Result result(long configuredIntervalMs) {
      long interval = configuredIntervalMs > 0 ? configuredIntervalMs : medianSampleGapMs();
      return compute(ts.sorted(), gcTime(), interval > 0 ? interval : 500);
    }
  }

  /** GC time inside any time range: the union of the pauses, or the counter difference. */
  static final class GcTime {
    final long[] start, end, cum; // merged, disjoint, sorted; cum[i] = total length of 0..i-1
    final long[] cTs, cGc;

    GcTime(Longs s, Longs e, Longs counters) {
      int n = s.n;
      long[][] iv = new long[n][];
      for (int i = 0; i < n; i++) iv[i] = new long[] {s.a[i], e.a[i]};
      Arrays.sort(iv, new java.util.Comparator<long[]>() {
        public int compare(long[] x, long[] y) { return x[0] < y[0] ? -1 : x[0] > y[0] ? 1 : 0; }
      });
      long[] ms = new long[n], me = new long[n];
      int m = 0;
      for (long[] v : iv) {
        if (m > 0 && v[0] <= me[m - 1]) {
          if (v[1] > me[m - 1]) me[m - 1] = v[1];
        } else {
          ms[m] = v[0];
          me[m] = v[1];
          m++;
        }
      }
      start = Arrays.copyOf(ms, m);
      end = Arrays.copyOf(me, m);
      cum = new long[m + 1];
      for (int i = 0; i < m; i++) cum[i + 1] = cum[i] + (end[i] - start[i]);
      int c = counters.n / 2;
      long[][] pairs = new long[c][];
      for (int i = 0; i < c; i++) pairs[i] = new long[] {counters.a[2 * i], counters.a[2 * i + 1]};
      Arrays.sort(pairs, new java.util.Comparator<long[]>() {
        public int compare(long[] x, long[] y) { return x[0] < y[0] ? -1 : x[0] > y[0] ? 1 : 0; }
      });
      cTs = new long[c];
      cGc = new long[c];
      for (int i = 0; i < c; i++) { cTs[i] = pairs[i][0]; cGc[i] = pairs[i][1]; }
    }

    /** GC milliseconds inside {@code [a, b]}. */
    long within(long a, long b) {
      if (b <= a) return 0;
      return Math.min(b - a, Math.max(union(a, b), counter(a, b)));
    }

    /** Total GC time by the pauses (union), over the whole run. */
    long total() {
      return cum[cum.length - 1];
    }

    private long union(long a, long b) {
      int m = start.length;
      if (m == 0) return 0;
      int lo = 0, hi = m;
      while (lo < hi) { // first interval with end > a
        int mid = (lo + hi) >>> 1;
        if (end[mid] > a) hi = mid; else lo = mid + 1;
      }
      long sum = 0;
      for (int i = lo; i < m && start[i] < b; i++) {
        sum += Math.min(end[i], b) - Math.max(start[i], a);
      }
      return sum;
    }

    private long counter(long a, long b) {
      int n = cTs.length;
      if (n < 2) return 0;
      int i = lastAtOrBefore(a), j = firstAtOrAfter(b);
      if (i < 0 || j >= n || j <= i) return 0;
      return Math.max(0, cGc[j] - cGc[i]);
    }

    private int lastAtOrBefore(long t) {
      int lo = 0, hi = cTs.length;
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (cTs[mid] <= t) lo = mid + 1; else hi = mid;
      }
      return lo - 1;
    }

    private int firstAtOrAfter(long t) {
      int lo = 0, hi = cTs.length;
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (cTs[mid] < t) lo = mid + 1; else hi = mid;
      }
      return lo;
    }
  }

  /** Applies the rule to sorted line timestamps. */
  static Result compute(long[] t, GcTime gc, long intervalMs) {
    Result r = new Result();
    r.intervalMs = intervalMs;
    r.dashAfterMs = dashAfterMs(intervalMs);
    r.breakAfterMs = breakAfterMs(intervalMs);
    List<long[]> raw = new ArrayList<long[]>();
    for (int i = 1; i < t.length; i++) {
      if (t[i] - t[i - 1] > r.dashAfterMs) raw.add(new long[] {t[i - 1], t[i]});
    }
    int i = 0;
    while (i < raw.size()) {
      int j = i;
      while (j + 1 < raw.size() && raw.get(j + 1)[0] - raw.get(j)[1] < r.dashAfterMs) j++;
      long s = raw.get(i)[0], e = raw.get(j)[1];
      long stallGc = gc.within(s, e);
      boolean stallBridged = (e - s) - stallGc <= r.breakAfterMs || stallGc >= STALL_GC_SHARE * (e - s);
      for (int k = i; k <= j; k++) {
        long a = raw.get(k)[0], b = raw.get(k)[1];
        long g = gc.within(a, b);
        // a pause right before or after a GC stall is not part of it: an edge gap
        // without any GC, longer than breakAfter, next to normal sampling, breaks
        boolean pausedEdge = g == 0 && b - a > r.breakAfterMs
            && ((k == j && b != t[t.length - 1]) || (k == i && a != t[0]));
        boolean bridged = (stallBridged && !pausedEdge) || g >= GAP_GC_SHARE * (b - a);
        r.gaps.add(new Gap(a, b, bridged, g));
      }
      i = j + 1;
    }
    return r;
  }

  /** The rule for a whole source (one pass). */
  static Result of(PointSource src, long configuredIntervalMs) throws java.io.IOException {
    Collector c = new Collector();
    src.forEach(c);
    return c.result(configuredIntervalMs);
  }
}
