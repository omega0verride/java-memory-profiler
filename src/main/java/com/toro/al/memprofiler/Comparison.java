package com.toro.al.memprofiler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Several runs side by side (a baseline and fix variants): the model behind
 * {@code export --compare}, shared by the overlay PNG and the comparison HTML.
 *
 * <ul>
 *   <li><b>Alignment.</b> Each run's time axis is elapsed time from its own start,
 *       or from the first occurrence of mark {@code n} ({@code --align mark:n}).</li>
 *   <li><b>Mark rows.</b> Marks are matched across runs by their number (the
 *       {@code "<n>."} label prefix, as on the badges); a repeated number (several
 *       {@code "6."} progress marks) is matched by occurrence: the second {@code 6}
 *       of one run with the second {@code 6} of the other.</li>
 *   <li><b>Summary rows.</b> Peak retained (highest mark), peak sampled, duration,
 *       total GC time, GC share and the heap still held at the last mark.</li>
 * </ul>
 */
final class Comparison {

  /** Categorical run colours (validated for CVD and contrast; light and dark steps of the same hues). */
  static final int[] LIGHT = {0x2a78d6, 0xeb6834, 0x1baf7a, 0xeda100, 0xe87ba4, 0x008300, 0x6250d6, 0xe34948};
  static final int[] DARK = {0x3987e5, 0xd95926, 0x199e70, 0xc98500, 0xd55181, 0x0ca30c, 0x9085e9, 0xe66767};
  static final int MAX_RUNS = LIGHT.length;

  static int color(int run, boolean dark) {
    return (dark ? DARK : LIGHT)[run % MAX_RUNS];
  }

  /** One input run. */
  static final class Run {
    final String label;
    final PointSource src;
    final ExportMeta meta;
    final ProfileStats stats;
    final GapRule.Result gaps;
    final List<Annotations.Row> rows;
    final List<DataPoint> references;
    /** Time zero of this run on the shared axis. */
    long alignTs;
    /** The alignment mark was not found in this run (aligned at its start instead). */
    boolean alignMissing;
    final Map<String, Annotations.Row> byKey = new LinkedHashMap<String, Annotations.Row>();

    Run(String label, PointSource src) throws IOException {
      this.src = src;
      this.stats = ProfileStats.scan(src);
      this.meta = src.meta().copy();
      stats.applyTo(meta);
      this.label = label != null && !label.trim().isEmpty() ? label.trim() : meta.effectiveTitle();
      this.gaps = stats.gaps(meta.intervalMs);
      this.rows = Annotations.rows(stats.annotated, stats.firstTs);
      this.references = src.references();
      Map<String, Integer> occ = new LinkedHashMap<String, Integer>();
      for (Annotations.Row r : rows) {
        Integer n = occ.get(r.badge);
        n = n == null ? 1 : n + 1;
        occ.put(r.badge, n);
        byKey.put(key(r.badge, n), r);
      }
      this.alignTs = stats.firstTs;
    }

    long startMs() { return stats.firstTs - alignTs; }
    long endMs() { return stats.lastTs - alignTs; }
    long durationMs() { return stats.lastTs - stats.firstTs; }
  }

  static String key(String badge, int occurrence) {
    return badge + "#" + occurrence;
  }

  /** A row of the mark table: one mark number (and occurrence), one cell per run. */
  static final class MarkRow {
    String key;
    String badge;
    int occurrence;
    /** Label text (number prefix removed) from the first run that has the mark. */
    String text;
    /** Per run: the row, or null when that run has no such mark. */
    Annotations.Row[] cells;

    Long mb(int run) {
      Annotations.Row r = cells[run];
      return r == null ? null : Long.valueOf(r.point.usedMB);
    }
  }

  /** A summary row: one value per run (null = not available). */
  static final class SummaryRow {
    final String name;
    /** "MB", "ms" or "%". */
    final String unit;
    final Long[] values;

    SummaryRow(String name, String unit, int runs) {
      this.name = name;
      this.unit = unit;
      this.values = new Long[runs];
    }
  }

  final List<Run> runs;
  final String align;
  final String alignMark;
  final List<MarkRow> markRows = new ArrayList<MarkRow>();
  final List<SummaryRow> summary = new ArrayList<SummaryRow>();
  /** Reference lines of all runs, duplicates (same value and caption) removed. */
  final List<DataPoint> references = new ArrayList<DataPoint>();
  final List<String> warnings = new ArrayList<String>();

  private Comparison(List<Run> runs, String align, String alignMark) {
    this.runs = runs;
    this.align = align;
    this.alignMark = alignMark;
  }

  /**
   * @param align {@code "start"} (default, null) or {@code "mark:<n>"}
   */
  static Comparison build(List<Run> runs, String align) {
    if (runs.size() < 2) throw new IllegalArgumentException("--compare needs at least two runs");
    if (runs.size() > MAX_RUNS) throw new IllegalArgumentException("--compare takes at most " + MAX_RUNS + " runs");
    String a = align == null || align.trim().isEmpty() ? "start" : align.trim();
    String mark = null;
    if (a.startsWith("mark:")) {
      mark = a.substring(5).trim();
      if (mark.endsWith(".")) mark = mark.substring(0, mark.length() - 1);
      if (mark.isEmpty()) throw new IllegalArgumentException("--align mark:<n> needs a mark number");
    } else if (!"start".equals(a)) {
      throw new IllegalArgumentException("--align must be start or mark:<n>, got: " + align);
    }
    Comparison c = new Comparison(runs, a, mark);
    for (Run r : runs) {
      if (mark == null) continue;
      Annotations.Row hit = r.byKey.get(key(mark, 1));
      if (hit == null) {
        r.alignMissing = true;
        c.warnings.add("run \"" + r.label + "\" has no mark " + mark + "; aligned at its start");
      } else {
        r.alignTs = hit.point.timestamp;
      }
    }
    c.buildMarkRows();
    c.buildSummary();
    for (Run r : runs) {
      for (DataPoint ref : r.references) {
        boolean dup = false;
        for (DataPoint e : c.references) {
          if (e.usedMB == ref.usedMB && eq(e.label, ref.label)) dup = true;
        }
        if (!dup) c.references.add(ref);
      }
    }
    return c;
  }

  private static boolean eq(String a, String b) {
    return a == null ? b == null : a.equals(b);
  }

  private void buildMarkRows() {
    List<String> keys = new ArrayList<String>();
    for (Run r : runs) for (String k : r.byKey.keySet()) if (!keys.contains(k)) keys.add(k);
    Collections.sort(keys, new Comparator<String>() {
      public int compare(String x, String y) {
        int c = Long.compare(num(x.substring(0, x.indexOf('#'))), num(y.substring(0, y.indexOf('#'))));
        if (c != 0) return c;
        return Integer.parseInt(x.substring(x.indexOf('#') + 1)) - Integer.parseInt(y.substring(y.indexOf('#') + 1));
      }
    });
    for (String k : keys) {
      MarkRow m = new MarkRow();
      m.key = k;
      m.badge = k.substring(0, k.indexOf('#'));
      m.occurrence = Integer.parseInt(k.substring(k.indexOf('#') + 1));
      m.cells = new Annotations.Row[runs.size()];
      for (int i = 0; i < runs.size(); i++) {
        m.cells[i] = runs.get(i).byKey.get(k);
        if (m.text == null && m.cells[i] != null) m.text = PngRenderer.rowText(m.cells[i]);
      }
      markRows.add(m);
    }
  }

  private static long num(String s) {
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return Long.MAX_VALUE;
    }
  }

  private void buildSummary() {
    int n = runs.size();
    SummaryRow peakRetained = new SummaryRow("Peak retained (highest mark)", "MB", n);
    SummaryRow peakSampled = new SummaryRow("Peak sampled", "MB", n);
    SummaryRow held = new SummaryRow("Held at the last mark", "MB", n);
    SummaryRow duration = new SummaryRow("Duration", "ms", n);
    SummaryRow gc = new SummaryRow("Total GC time", "ms", n);
    SummaryRow share = new SummaryRow("GC share of the run", "%", n);
    for (int i = 0; i < n; i++) {
      Run r = runs.get(i);
      ProfileStats s = r.stats;
      peakRetained.values[i] = s.peakRetainedMB >= 0 ? Long.valueOf(s.peakRetainedMB) : null;
      peakSampled.values[i] = Long.valueOf(s.peakMB);
      held.values[i] = s.lastAnnotatedMB >= 0 ? Long.valueOf(s.lastAnnotatedMB) : null;
      duration.values[i] = Long.valueOf(r.durationMs());
      gc.values[i] = Long.valueOf(s.gcTotalMs());
      share.values[i] = r.durationMs() > 0 ? Long.valueOf(Math.round(100.0 * s.gcTotalMs() / r.durationMs())) : null;
    }
    summary.add(peakRetained);
    summary.add(peakSampled);
    summary.add(held);
    summary.add(duration);
    summary.add(gc);
    summary.add(share);
  }

  long minMs() {
    long v = Long.MAX_VALUE;
    for (Run r : runs) v = Math.min(v, r.startMs());
    return v;
  }

  long maxMs() {
    long v = Long.MIN_VALUE;
    for (Run r : runs) v = Math.max(v, r.endMs());
    return v;
  }

  long peakMB() {
    long v = 0;
    for (Run r : runs) v = Math.max(v, r.stats.peakMB);
    return v;
  }

  /** {@code "1,250"}. */
  static String mb(long v) {
    return String.format(java.util.Locale.US, "%,d", v);
  }

  /** Delta of {@code v} to the first run: {@code "−3,550 (−74%)"}, or "" for the first run or missing values. */
  static String delta(Long first, Long v, String unit) {
    if (first == null || v == null) return "";
    long d = v.longValue() - first.longValue();
    String sign = d > 0 ? "+" : d < 0 ? "−" : "±";
    String abs;
    if ("ms".equals(unit)) abs = PngRenderer.formatDurationShort(Math.abs(d));
    else if ("%".equals(unit)) return sign + Math.abs(d) + " pt";
    else abs = mb(Math.abs(d));
    String pct = first.longValue() != 0 ? " (" + sign + Math.round(Math.abs(100.0 * d / first.longValue())) + "%)" : "";
    return sign + abs + pct;
  }

  /** A value in its unit: {@code "1,250 MB"}, {@code "9:02"}, {@code "54%"}. */
  static String value(Long v, String unit) {
    if (v == null) return "—";
    if ("ms".equals(unit)) return PngRenderer.formatDurationShort(v.longValue());
    if ("%".equals(unit)) return v + "%";
    return mb(v.longValue());
  }
}
