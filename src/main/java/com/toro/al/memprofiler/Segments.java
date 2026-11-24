package com.toro.al.memprofiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Memory explained by phase: the retained-heap change between two marks
 * ({@code --segments 1:2,2:4,5:7}), plus the automatic "largest increase" and
 * "largest release" between consecutive marks.
 *
 * <p>A segment {@code a:b} runs from the first mark numbered {@code a} to the
 * last mark numbered {@code b} after it (as {@code --from-mark} / {@code --to-mark}).
 */
final class Segments {

  private Segments() {}

  static final String USER = "segment", INCREASE = "largest increase", RELEASE = "largest release";

  static final class Segment {
    String kind = USER;
    Annotations.Row from, to;
    long deltaMB;
    long durationMs;
    /** GC time inside the segment (pauses, forced GCs, counters). */
    long gcMs;

    String name() {
      return from.badge + "→" + to.badge;
    }

    /** {@code "2→4 +512 MB in 0:12"}. */
    String label() {
      return name() + " " + signedMB(deltaMB) + " in " + PngRenderer.formatDurationShort(durationMs);
    }

    /** Label of an automatic callout: {@code "largest release 7→8 −1,750 MB"}. */
    String callout() {
      return kind + " " + name() + " " + signedMB(deltaMB);
    }
  }

  static String signedMB(long mb) {
    return (mb > 0 ? "+" : mb < 0 ? "−" : "±") + String.format(Locale.US, "%,d", Math.abs(mb)) + " MB";
  }

  /**
   * Parses {@code "1:2,2:4,5:7"} against the run's rows.
   *
   * @throws IllegalArgumentException for a malformed spec or a mark that does not exist
   */
  static List<Segment> parse(String spec, List<Annotations.Row> rows, GapRule.GcTime gc) {
    List<Segment> out = new ArrayList<Segment>();
    if (spec == null || spec.trim().isEmpty()) return out;
    for (String part : spec.split(",")) {
      String p = part.trim();
      if (p.isEmpty()) continue;
      String[] ab = p.split("[:\\-–]|\\.\\.");
      if (ab.length != 2) throw new IllegalArgumentException("--segments: expected <from>:<to>, got: " + p);
      String a = strip(ab[0]), b = strip(ab[1]);
      int fi = -1;
      for (int i = 0; i < rows.size(); i++) if (rows.get(i).badge.equals(a)) { fi = i; break; }
      if (fi < 0) throw new IllegalArgumentException("--segments: mark " + a + " not found");
      int ti = -1;
      for (int i = fi; i < rows.size(); i++) if (rows.get(i).badge.equals(b)) ti = i;
      if (ti < 0) throw new IllegalArgumentException("--segments: no mark " + b + " after mark " + a);
      out.add(make(USER, rows.get(fi), rows.get(ti), gc));
    }
    return out;
  }

  private static String strip(String s) {
    String t = s.trim();
    return t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
  }

  static Segment make(String kind, Annotations.Row from, Annotations.Row to, GapRule.GcTime gc) {
    Segment s = new Segment();
    s.kind = kind;
    s.from = from;
    s.to = to;
    s.deltaMB = to.point.usedMB - from.point.usedMB;
    s.durationMs = to.point.timestamp - from.point.timestamp;
    s.gcMs = gc == null ? 0 : gc.within(from.point.timestamp, to.point.timestamp);
    return s;
  }

  /** The largest increase and the largest release between consecutive marks (either may be missing). */
  static List<Segment> callouts(List<Annotations.Row> rows, GapRule.GcTime gc) {
    List<Segment> out = new ArrayList<Segment>();
    Annotations.Row[] up = null, down = null;
    long maxUp = 0, maxDown = 0;
    Annotations.Row prev = null;
    for (Annotations.Row r : rows) {
      if (r.point.kind != DataPoint.Kind.MARK) continue;
      if (prev != null) {
        long d = r.point.usedMB - prev.point.usedMB;
        if (d > maxUp) { maxUp = d; up = new Annotations.Row[] {prev, r}; }
        if (d < maxDown) { maxDown = d; down = new Annotations.Row[] {prev, r}; }
      }
      prev = r;
    }
    if (up != null) out.add(make(INCREASE, up[0], up[1], gc));
    if (down != null) out.add(make(RELEASE, down[0], down[1], gc));
    return out;
  }

  /** A plain-text table for the CLI. */
  static String table(List<Segment> segments) {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format(Locale.US, "%-10s %14s %10s %10s  %s%n", "segment", "retained Δ", "duration", "GC", "from → to"));
    for (Segment s : segments) {
      sb.append(String.format(Locale.US, "%-10s %14s %10s %10s  %s → %s%n", s.name(), signedMB(s.deltaMB),
          PngRenderer.formatDurationShort(s.durationMs), PngRenderer.formatDurationShort(s.gcMs),
          s.from.text, s.to.text));
    }
    return sb.toString();
  }

  /** {@code [{"kind":…,"from":…,"to":…,"fromTs":…,"toTs":…,"deltaMB":…,"durationMs":…,"gcMs":…,"label":…}]} */
  static void appendJson(StringBuilder sb, List<Segment> segments) {
    sb.append('[');
    for (int i = 0; i < segments.size(); i++) {
      Segment s = segments.get(i);
      if (i > 0) sb.append(',');
      sb.append("{\"kind\":"); MiniJson.quote(sb, s.kind);
      sb.append(",\"from\":"); MiniJson.quote(sb, s.from.badge);
      sb.append(",\"to\":"); MiniJson.quote(sb, s.to.badge);
      sb.append(",\"fromIndex\":").append(s.from.point.index);
      sb.append(",\"toIndex\":").append(s.to.point.index);
      sb.append(",\"fromTs\":").append(s.from.point.timestamp);
      sb.append(",\"toTs\":").append(s.to.point.timestamp);
      sb.append(",\"deltaMB\":").append(s.deltaMB);
      sb.append(",\"durationMs\":").append(s.durationMs);
      sb.append(",\"gcMs\":").append(s.gcMs);
      sb.append(",\"label\":"); MiniJson.quote(sb, USER.equals(s.kind) ? s.label() : s.callout());
      sb.append('}');
    }
    sb.append(']');
  }
}
