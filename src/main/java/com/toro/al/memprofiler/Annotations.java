package com.toro.al.memprofiler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mark annotations shared by the PNG renderer and (mirrored line by line in
 * {@code web/annotations.js}) the HTML viewer: badge numbering, the marks
 * table, scope bands, and the two collision-avoiding layouts.
 *
 * <p><b>Keep this file and {@code web/annotations.js} in step.</b> Both use the
 * same constants, the same order of placement, and the same tie-breaking, so
 * the PNG and the HTML place badges and band titles the same way (text widths
 * come from each platform's font, so positions can differ by a pixel or two).
 */
final class Annotations {

  private Annotations() {}

  // ---- shared constants (CSS px == PNG logical px) ----
  static final double BADGE_H = 16;
  static final double BADGE_PAD_X = 5;
  /** Minimum spacing between two badges, and the vertical stacking step is {@code BADGE_H + BADGE_GAP}. */
  static final double BADGE_GAP = 3;
  /** Distance from the dot centre to the bottom of an unstacked badge. */
  static final double DOT_GAP = 7;
  static final double DOT_R = 4;
  static final double TITLE_ROW_H = 15;
  static final double TITLE_PAD_X = 3;
  static final double TITLE_GAP = 8;
  /** A band title may be wider than its band, but is cut to this width (or the band width, if larger). */
  static final double TITLE_MAX_W = 140;

  /** {@code "7. DB cache"} → number 7, text "DB cache". The dot must be followed by space or end ("7.5 GB" is not numbered). */
  static final Pattern NUMBERED = Pattern.compile("^\\s*(\\d+)\\.(?:\\s+|$)");

  static boolean isAnnotated(DataPoint.Kind k) {
    return k == DataPoint.Kind.MARK || k == DataPoint.Kind.SCOPE_START || k == DataPoint.Kind.SCOPE_END;
  }

  // ---- marks table ----

  /** One annotated point: a row in the marks table and a badge on the chart. */
  static final class Row {
    DataPoint point;
    /** 1-based position among all annotated points of the run. */
    int seq;
    /** The badge text: the label's own number, or the next number after the highest explicit one. */
    String badge;
    /** Label without the number prefix. */
    String text;
    /** usedMB minus the previous row's usedMB; null for the first row. */
    Long deltaPrevMB;
    long elapsedMs;
    String shortLocation;
  }

  /**
   * Builds the rows for all annotated points of a run (numbering is over the
   * whole run, so a range export keeps the same numbers).
   *
   * <p>A label that starts with {@code "<number>. "} shows that number (numbers
   * may repeat). Other points are numbered sequentially, starting after the
   * highest explicit number in the run, so the two kinds never share a badge.
   */
  static List<Row> rows(List<DataPoint> annotated, long runStartTs) {
    long maxExplicit = 0;
    for (DataPoint p : annotated) {
      Matcher m = NUMBERED.matcher(p.label == null ? "" : p.label);
      if (m.find()) {
        String digits = stripLeadingZeros(m.group(1));
        if (digits.length() <= 9) maxExplicit = Math.max(maxExplicit, Long.parseLong(digits));
      }
    }
    List<Row> rows = new ArrayList<Row>(annotated.size());
    Row prev = null;
    int seq = 0;
    long nextImplicit = maxExplicit;
    for (DataPoint p : annotated) {
      Row r = new Row();
      r.point = p;
      r.seq = ++seq;
      String label = p.label == null ? "" : p.label;
      Matcher m = NUMBERED.matcher(label);
      if (m.find()) {
        r.badge = stripLeadingZeros(m.group(1));
        r.text = label.substring(m.end()).trim();
      } else {
        r.badge = String.valueOf(++nextImplicit);
        r.text = label.trim();
      }
      if (r.text.isEmpty()) r.text = p.kind == DataPoint.Kind.MARK ? "(mark)" : "(scope)";
      r.deltaPrevMB = prev == null ? null : Long.valueOf(p.usedMB - prev.point.usedMB);
      r.elapsedMs = p.timestamp - runStartTs;
      r.shortLocation = shortLocation(p.location);
      rows.add(r);
      prev = r;
    }
    return rows;
  }

  private static String stripLeadingZeros(String digits) {
    int i = 0;
    while (i < digits.length() - 1 && digits.charAt(i) == '0') i++;
    return digits.substring(i);
  }

  /** {@code com.acme.Loader.load:42} → {@code Loader.load:42}. */
  static String shortLocation(String location) {
    if (location == null || location.isEmpty()) return "";
    String loc = location;
    String line = "";
    int colon = loc.lastIndexOf(':');
    if (colon > 0) {
      String tail = loc.substring(colon + 1);
      if (isDigits(tail)) {
        line = loc.substring(colon);
        loc = loc.substring(0, colon);
      }
    }
    int methodDot = loc.lastIndexOf('.');
    if (methodDot <= 0) return location;
    String cls = loc.substring(0, methodDot);
    String method = loc.substring(methodDot + 1);
    int clsDot = cls.lastIndexOf('.');
    String simple = clsDot >= 0 ? cls.substring(clsDot + 1) : cls;
    return simple + "." + method + line;
  }

  private static boolean isDigits(String s) {
    if (s.isEmpty()) return false;
    for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
    return true;
  }

  // ---- scope bands ----

  static final class Band {
    String scopeId;
    String label;
    long startTs;
    long endTs;
    /** Start or end is missing (still open, or evicted from the buffer). */
    boolean open;
    long order;
  }

  /** Pairs SCOPE_START / SCOPE_END by scope id. Bands are ordered by start time, then index. */
  static List<Band> bands(List<DataPoint> annotated, long firstTs, long lastTs) {
    Map<String, Band> byId = new LinkedHashMap<String, Band>();
    for (DataPoint p : annotated) {
      if (p.scopeId == null) continue;
      if (p.kind == DataPoint.Kind.SCOPE_START) {
        Band b = new Band();
        b.scopeId = p.scopeId;
        b.label = p.label == null ? p.scopeId : p.label;
        b.startTs = p.timestamp;
        b.endTs = lastTs;
        b.open = true;
        b.order = p.index;
        byId.put(p.scopeId, b);
      } else if (p.kind == DataPoint.Kind.SCOPE_END) {
        Band b = byId.get(p.scopeId);
        if (b == null) {
          b = new Band();
          b.scopeId = p.scopeId;
          b.label = p.label == null ? p.scopeId : p.label;
          b.startTs = firstTs;
          b.order = Long.MIN_VALUE + p.index;
          byId.put(p.scopeId, b);
          b.open = true;
        } else {
          b.open = false;
        }
        b.endTs = p.timestamp;
      }
    }
    List<Band> out = new ArrayList<Band>(byId.values());
    Collections.sort(out, new Comparator<Band>() {
      public int compare(Band a, Band b) {
        if (a.startTs != b.startTs) return a.startTs < b.startTs ? -1 : 1;
        return a.order < b.order ? -1 : a.order > b.order ? 1 : 0;
      }
    });
    return out;
  }

  // ---- layout ----

  static final class Rect {
    final double x, y, w, h;

    Rect(double x, double y, double w, double h) {
      this.x = x;
      this.y = y;
      this.w = w;
      this.h = h;
    }

    double right() { return x + w; }
    double bottom() { return y + h; }

    /** True when the two rectangles are closer than {@code gap} on both axes. */
    boolean near(Rect o, double gap) {
      return x < o.x + o.w + gap && o.x < x + w + gap && y < o.y + o.h + gap && o.y < y + h + gap;
    }

    /** True when the rectangles share a positive area. */
    boolean overlaps(Rect o) {
      return x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h;
    }
  }

  /** A badge to place: dot centre and badge width (text width + 2 × {@link #BADGE_PAD_X}). */
  static final class BadgeIn {
    final double ax, ay, w;

    BadgeIn(double ax, double ay, double w) {
      this.ax = ax;
      this.ay = ay;
      this.w = Math.max(BADGE_H, w);
    }
  }

  static final class BadgePlacement {
    Rect rect;
    boolean hidden;
    /** The badge left its preferred slot: draw a leader line to the dot. */
    boolean leader;
    /** The badge sits below its dot (the leader goes up from the badge top). */
    boolean below;
  }

  /**
   * Places badges in the given order (time order). Each badge is centred above
   * its dot; if that slot touches an already placed badge (or an obstacle) it
   * moves up one slot at a time ({@code BADGE_H + BADGE_GAP}); if it reaches the
   * top of the plot it tries the slots below the preferred one; if none is free
   * it is hidden (the marks table still lists it). Badges never leave
   * {@code plot}.
   */
  static List<BadgePlacement> layoutBadges(List<BadgeIn> in, Rect plot, List<Rect> obstacles) {
    List<BadgePlacement> out = new ArrayList<BadgePlacement>(in.size());
    List<Rect> placed = new ArrayList<Rect>(in.size() + (obstacles == null ? 0 : obstacles.size()));
    if (obstacles != null) placed.addAll(obstacles);
    double step = BADGE_H + BADGE_GAP;
    double top = plot.y, bottom = plot.bottom(), left = plot.x, right = plot.right();
    for (BadgeIn b : in) {
      BadgePlacement bp = new BadgePlacement();
      double w = Math.min(b.w, plot.w);
      double x = clamp(b.ax - w / 2, left, right - w);
      double pref = b.ay - DOT_GAP - BADGE_H;
      double y0 = clamp(pref, top, bottom - BADGE_H);
      Rect hit = null;
      for (double y = y0; y >= top - 1e-6; y -= step) {
        Rect r = new Rect(x, y, w, BADGE_H);
        if (isFree(r, placed)) { hit = r; break; }
      }
      if (hit == null) {
        for (double y = y0 + step; y + BADGE_H <= bottom + 1e-6; y += step) {
          Rect r = new Rect(x, y, w, BADGE_H);
          if (isFree(r, placed)) { hit = r; break; }
        }
      }
      if (hit == null) {
        bp.hidden = true;
      } else {
        bp.rect = hit;
        bp.leader = Math.abs(hit.y - pref) > 0.5;
        bp.below = hit.y > b.ay;
        placed.add(hit);
      }
      out.add(bp);
    }
    return out;
  }

  private static boolean isFree(Rect r, List<Rect> placed) {
    for (int i = 0, n = placed.size(); i < n; i++) {
      if (r.near(placed.get(i), BADGE_GAP)) return false;
    }
    return true;
  }

  interface TextMeasurer {
    double width(String text);
  }

  static final class TitleIn {
    final double x1, x2;
    final String text;

    TitleIn(double x1, double x2, String text) {
      this.x1 = x1;
      this.x2 = x2;
      this.text = text == null ? "" : text;
    }
  }

  static final class TitlePlacement {
    boolean hidden;
    int row;
    double x;
    double w;
    String text;
  }

  /**
   * Places band titles on two rows above the plot, in band order. A title is
   * centred over the visible part of its band and cut to
   * {@code max(band width, TITLE_MAX_W)}. Row 0 is tried first, then row 1; on a
   * row the title may slide right past the previous title, but must still start
   * inside its band. A title that fits on neither row is hidden. Titles on the
   * same row never overlap and never leave {@code [left, right]}.
   */
  static List<TitlePlacement> layoutBandTitles(List<TitleIn> in, double left, double right, TextMeasurer m) {
    List<TitlePlacement> out = new ArrayList<TitlePlacement>(in.size());
    double[] rowRight = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
    for (TitleIn t : in) {
      TitlePlacement tp = new TitlePlacement();
      tp.hidden = true;
      double vx1 = Math.max(t.x1, left), vx2 = Math.min(t.x2, right);
      if (vx2 < vx1 || t.text.isEmpty()) { out.add(tp); continue; }
      double cap = Math.min(right - left, Math.max(vx2 - vx1, TITLE_MAX_W));
      String text = fit(t.text, cap, m);
      if (text == null) { out.add(tp); continue; }
      double w = m.width(text) + 2 * TITLE_PAD_X;
      double cx = (vx1 + vx2) / 2;
      for (int row = 0; row < 2; row++) {
        double x = clamp(cx - w / 2, left, right - w);
        x = Math.max(x, rowRight[row] + TITLE_GAP);
        if (x > Math.max(vx2 - 2, vx1)) continue; // would start after its band
        String txt = text;
        double tw = w;
        if (x + tw > right) {
          txt = fit(text, right - x, m);
          if (txt == null) continue;
          tw = m.width(txt) + 2 * TITLE_PAD_X;
        }
        tp.hidden = false;
        tp.row = row;
        tp.x = x;
        tp.w = tw;
        tp.text = txt;
        rowRight[row] = x + tw;
        break;
      }
      out.add(tp);
    }
    return out;
  }

  /** {@code text}, or a prefix + "…", that fits in {@code maxW} (with padding); null if not even one character fits. */
  static String fit(String text, double maxW, TextMeasurer m) {
    if (m.width(text) + 2 * TITLE_PAD_X <= maxW) return text;
    for (int n = text.length() - 1; n >= 1; n--) {
      String c = rtrim(text.substring(0, n)) + "…";
      if (m.width(c) + 2 * TITLE_PAD_X <= maxW) return c;
    }
    return null;
  }

  private static String rtrim(String s) {
    int e = s.length();
    while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) e--;
    return s.substring(0, e);
  }

  static double clamp(double v, double lo, double hi) {
    if (hi < lo) return lo;
    return v < lo ? lo : v > hi ? hi : v;
  }

  /** Relative luminance (sRGB) of a 0xRRGGBB colour; text on the badge is dark when this is above 0.4. */
  static double luminance(int rgb) {
    double r = channel((rgb >> 16) & 0xFF), g = channel((rgb >> 8) & 0xFF), b = channel(rgb & 0xFF);
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  }

  private static double channel(int c) {
    double v = c / 255.0;
    return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
  }
}
