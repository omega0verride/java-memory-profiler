package com.toro.al.memprofiler;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Renders a whole run (or a time range) to a static PNG with Java2D, so the
 * API, the CLI and CI produce the same image without a browser.
 *
 * <p>Two passes over the {@link PointSource}: {@link ProfileStats} first, then
 * an M4 reduction (first, last, min and max sample per output pixel column) that
 * keeps every peak while holding at most {@code 4 × width × scale} points.
 * Nothing is copied from the store.
 *
 * <p>Badge numbering, badge and band-title collision rules and colours come from
 * {@link Annotations} and mirror the HTML viewer.
 */
public final class PngRenderer {

  private PngRenderer() {}

  // ---- layout constants (logical px) ----
  static final double MARGIN_L = 74;
  static final double MARGIN_R = 28;
  static final double TITLE_BLOCK = 66;
  static final double BAND_AREA = 2 * Annotations.TITLE_ROW_H + 8;
  static final double NO_BAND_AREA = 12;
  static final double TABLE_ROW_H = 22;
  static final int TABLE_MAX_ROWS = 300;

  static final FontRenderContext FRC = new FontRenderContext(null, true, true);
  static final Font F_TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 20);
  static final Font F_SUB = new Font(Font.SANS_SERIF, Font.PLAIN, 12).deriveFont(12.5f);
  static final Font F_AXIS = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
  static final Font F_BADGE = new Font(Font.SANS_SERIF, Font.BOLD, 11);
  static final Font F_BAND = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
  static final Font F_REF = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
  static final Font F_KEY = new Font(Font.SANS_SERIF, Font.PLAIN, 11).deriveFont(11.5f);
  static final Font F_TABLE = new Font(Font.SANS_SERIF, Font.PLAIN, 11).deriveFont(11.5f);
  static final Font F_TABLE_HEAD = new Font(Font.SANS_SERIF, Font.BOLD, 11);
  static final Font F_TABLE_TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 13);
  static final Font F_MONO = new Font(Font.MONOSPACED, Font.PLAIN, 11);

  // ---- palette (mirrors the CSS variables of the viewer, light and dark) ----

  static final class Palette {
    Color bg, text, mute, faint, grid, axis, line, area, committed, bandFill, bandEdge, ref, refMax;
    Color tableHead, zebra, border, up, down;
    Color mark, start, end, gc, heapDump, autoGc;

    Color kind(DataPoint.Kind k) {
      switch (k) {
        case MARK: return mark;
        case SCOPE_START: return start;
        case SCOPE_END: return end;
        case GC: return gc;
        case HEAP_DUMP: return heapDump;
        case AUTO_GC: return autoGc;
        default: return line;
      }
    }
  }

  static Palette palette(boolean dark) {
    Palette p = new Palette();
    if (dark) {
      p.bg = rgb(0x0d1017); p.text = rgb(0xe8ecf4); p.mute = rgb(0x8791a5); p.faint = rgb(0x5a6478);
      p.grid = rgb(0x232a39); p.axis = rgb(0x2c3445); p.line = rgb(0x4ea1ff); p.area = rgba(0x4ea1ff, 0.16);
      p.committed = rgb(0x8791a5); p.bandFill = rgba(0xffffff, 0.05); p.bandEdge = rgba(0xffffff, 0.14);
      p.ref = rgb(0xff6e6e); p.refMax = rgb(0x8791a5);
      p.tableHead = rgb(0x151a24); p.zebra = rgb(0x121722); p.border = rgb(0x232a39);
      p.up = rgb(0xff6e6e); p.down = rgb(0x37c26a);
      p.mark = rgb(0xffb020); p.start = rgb(0x37c26a); p.end = rgb(0xff6e6e);
      p.gc = rgb(0x4ea1ff); p.heapDump = rgb(0xe040fb); p.autoGc = rgb(0xa3e635);
    } else {
      p.bg = rgb(0xffffff); p.text = rgb(0x1b2230); p.mute = rgb(0x5b6578); p.faint = rgb(0x8a93a3);
      p.grid = rgb(0xe6e9ef); p.axis = rgb(0xc9ced8); p.line = rgb(0x1f6fd1); p.area = rgba(0x1f6fd1, 0.12);
      p.committed = rgb(0x8a93a3); p.bandFill = rgba(0x0f172a, 0.045); p.bandEdge = rgba(0x0f172a, 0.14);
      p.ref = rgb(0xc43c3c); p.refMax = rgb(0x6b7280); // ref: 5.2:1 on white (dark #ff6e6e: 7.0:1 on #0d1017)
      p.tableHead = rgb(0xf3f5f8); p.zebra = rgb(0xf9fafb); p.border = rgb(0xe6e9ef);
      p.up = rgb(0xd64545); p.down = rgb(0x16a34a);
      p.mark = rgb(0xf59e0b); p.start = rgb(0x16a34a); p.end = rgb(0xe5484d);
      p.gc = rgb(0x2f80ed); p.heapDump = rgb(0xc026d3); p.autoGc = rgb(0x65a30d);
    }
    return p;
  }

  static Color rgb(int v) { return new Color(v); }

  static Color rgba(int v, double a) {
    return new Color((v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF, (int) Math.round(a * 255));
  }

  /** Dark or white text for a badge of this fill (WCAG crossover luminance). */
  static Color badgeText(Color fill) {
    return Annotations.luminance(fill.getRGB() & 0xFFFFFF) > 0.179 ? rgb(0x111827) : Color.WHITE;
  }

  // ---- model ----

  /** Everything the renderer decided, exposed for tests (no pixels needed). */
  static final class Model {
    double width, height;
    int widthPx, heightPx;
    Annotations.Rect plot;
    double bandTop;
    long runStart, runEnd, tMin, tMax;
    /** Marks shown: the chart range, or exactly fromMark..toMark (the chart range adds a margin). */
    long rowMin, rowMax;
    double yMaxMB;
    boolean gb;
    double yStep;
    ExportMeta meta;
    String title, subtitle;
    List<Annotations.Row> rows = new ArrayList<Annotations.Row>();
    List<Annotations.BadgePlacement> badges = new ArrayList<Annotations.BadgePlacement>();
    List<Annotations.Band> bands = new ArrayList<Annotations.Band>();
    List<Annotations.TitlePlacement> titles = new ArrayList<Annotations.TitlePlacement>();
    List<RefLine> refs = new ArrayList<RefLine>();
    String offScaleNote;
    Annotations.Rect offScaleRect;
    long[] seriesTs = new long[0];
    long[] seriesMB = new long[0];
    long[] committedTs = new long[0];
    long[] committedMB = new long[0];
    int seriesLen, committedLen;
    /** The gap rule for the whole run (see {@link GapRule}). */
    GapRule.Result gaps;
    ProfileStats stats;
    GcPressure gc;
    /** User segments and automatic callouts in range, with their bracket rectangles. */
    List<Segments.Segment> segments = new ArrayList<Segments.Segment>();
    List<Annotations.Rect> bracketRects = new ArrayList<Annotations.Rect>();
    boolean marksOnly;
    String key2;
    List<TableCol> columns = new ArrayList<TableCol>();
    List<DataPoint> events = new ArrayList<DataPoint>();
    int hiddenBadges;
    boolean table;
    int tableRows;
    double tableTop;
    String key;

    double xAt(long ts) {
      return plot.x + (ts - tMin) / (double) Math.max(1, tMax - tMin) * plot.w;
    }

    double yAt(double mb) {
      return plot.y + (1 - mb / yMaxMB) * plot.h;
    }
  }

  static final class RefLine {
    long mb;
    String label;
    boolean jvmMax;
    Annotations.Rect labelRect;
  }

  // ---- entry points ----

  public static File render(PointSource src, PngOptions opts, File file) throws IOException {
    File parent = file.getAbsoluteFile().getParentFile();
    if (parent != null) parent.mkdirs();
    OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16);
    try {
      render(src, opts, out);
    } finally {
      out.close();
    }
    return file;
  }

  public static void render(PointSource src, PngOptions opts, OutputStream out) throws IOException {
    if (opts == null) opts = PngOptions.defaults();
    Model m = model(src, opts);
    BufferedImage img = draw(m, opts);
    writePng(img, out);
  }

  /** Computes the whole layout (two passes over the source), without drawing. */
  static Model model(PointSource src, PngOptions o) throws IOException {
    ProfileStats stats = ProfileStats.scan(src);
    Model m = new Model();
    m.stats = stats;
    m.meta = src.meta().copy();
    stats.applyTo(m.meta);
    m.gaps = stats.gaps(m.meta.intervalMs);
    m.title = o.title != null && !o.title.trim().isEmpty() ? o.title : m.meta.effectiveTitle();
    m.runStart = stats.firstTs;
    m.runEnd = stats.lastTs;

    List<Annotations.Row> allRows = Annotations.rows(stats.annotated, stats.firstTs);
    if (o.formatLabelNumbers) for (Annotations.Row r : allRows) r.text = Annotations.formatNumbers(r.text);
    resolveRange(m, o, allRows);
    m.marksOnly = o.marksOnlyTable;
    GapRule.GcTime gcTime = stats.gapInput.gcTime();
    List<Segments.Segment> segs = new ArrayList<Segments.Segment>(Segments.parse(o.segments, allRows, gcTime));
    if (o.callouts) segs.addAll(Segments.callouts(allRows, gcTime));
    for (Segments.Segment sg : segs) {
      if (sg.to.point.timestamp >= m.tMin && sg.from.point.timestamp <= m.tMax) m.segments.add(sg);
    }
    m.gc = GcPressure.of(stats, m.gaps, o.gcOverheadPct);
    for (Annotations.Row r : allRows) {
      if (r.point.timestamp >= m.rowMin && r.point.timestamp <= m.rowMax) m.rows.add(r);
    }
    for (Annotations.Band b : Annotations.bands(stats.annotated, stats.firstTs, stats.lastTs)) {
      if (b.endTs >= m.tMin && b.startTs <= m.tMax) m.bands.add(b);
    }

    // geometry
    m.width = o.width;
    double bandArea = m.bands.isEmpty() ? NO_BAND_AREA : BAND_AREA;
    m.bandTop = TITLE_BLOCK;
    m.plot = new Annotations.Rect(MARGIN_L, TITLE_BLOCK + bandArea, o.width - MARGIN_L - MARGIN_R, o.height);

    // second pass: M4 per pixel column
    collectSeries(src, stats, m, o);

    // y scale and reference lines
    List<DataPoint> userRefs = src.references();
    computeYScale(m, o, stats, userRefs);
    layoutRefs(m, userRefs, stats.jvmMaxHeapMB);

    // badges
    List<Annotations.BadgeIn> ins = new ArrayList<Annotations.BadgeIn>(m.rows.size());
    for (Annotations.Row r : m.rows) {
      ins.add(new Annotations.BadgeIn(m.xAt(r.point.timestamp), m.yAt(r.point.usedMB),
          width(F_BADGE, r.badge) + 2 * Annotations.BADGE_PAD_X));
    }
    List<Annotations.Rect> obstacles = new ArrayList<Annotations.Rect>();
    for (RefLine r : m.refs) if (r.labelRect != null) obstacles.add(r.labelRect);
    layoutBrackets(m);
    obstacles.addAll(m.bracketRects);
    if (m.offScaleRect != null) obstacles.add(m.offScaleRect);
    m.badges = Annotations.layoutBadges(ins, m.plot, obstacles);
    for (Annotations.BadgePlacement b : m.badges) if (b.hidden) m.hiddenBadges++;

    // band titles
    List<Annotations.TitleIn> tins = new ArrayList<Annotations.TitleIn>(m.bands.size());
    for (Annotations.Band b : m.bands) tins.add(new Annotations.TitleIn(m.xAt(b.startTs), m.xAt(b.endTs), b.label));
    m.titles = Annotations.layoutBandTitles(tins, m.plot.x, m.plot.right(), measurer(F_BAND));

    // texts
    m.subtitle = subtitle(m, o);
    m.key = key(m, o);

    m.key2 = key2(m);
    m.columns = columns(o, m.rows);

    // table and total height
    double y = m.plot.bottom() + (m.key2.isEmpty() ? 62 : 78);
    if (m.marksOnly) y = TITLE_BLOCK - 6;
    m.table = o.includeTable || m.marksOnly;
    if (m.table) {
      m.tableTop = y + 6;
      m.tableRows = Math.min(m.rows.size(), TABLE_MAX_ROWS);
      y = m.tableTop + 26 + 24 + Math.max(1, m.tableRows) * TABLE_ROW_H
          + (m.rows.size() > TABLE_MAX_ROWS ? TABLE_ROW_H : 0) + 6;
    }
    m.height = Math.ceil(y + 12);
    m.widthPx = (int) Math.ceil(m.width * o.scale);
    m.heightPx = (int) Math.ceil(m.height * o.scale);
    return m;
  }

  private static void resolveRange(Model m, PngOptions o, List<Annotations.Row> rows) {
    long first = m.runStart, last = Math.max(m.runEnd, m.runStart + 1);
    long tMin = first, tMax = last;
    boolean byMark = false;
    if (o.from != null) tMin = absolute(o.from.longValue(), first);
    if (o.to != null) tMax = absolute(o.to.longValue(), first);
    if (o.fromMark != null) {
      Annotations.Row r = findMark(rows, o.fromMark, true);
      if (r == null) throw new IllegalArgumentException("fromMark " + o.fromMark + " not found");
      tMin = r.point.timestamp;
      byMark = true;
    }
    if (o.toMark != null) {
      Annotations.Row r = findMark(rows, o.toMark, false);
      if (r == null) throw new IllegalArgumentException("toMark " + o.toMark + " not found");
      tMax = r.point.timestamp;
      byMark = true;
    }
    if (tMax < tMin) { long t = tMin; tMin = tMax; tMax = t; }
    m.rowMin = tMin;
    m.rowMax = tMax;
    if (byMark) {
      long pad = Math.max(500, (tMax - tMin) / 30);
      tMin = Math.max(first, tMin - pad);
      tMax = Math.min(last, tMax + pad);
    }
    if (tMax - tMin < 1000) {
      long mid = (tMin + tMax) / 2;
      tMin = mid - 500;
      tMax = mid + 500;
    }
    m.tMin = tMin;
    m.tMax = tMax;
    if (!byMark) {
      m.rowMin = tMin;
      m.rowMax = tMax;
    } else {
      // a mark given by number bounds the rows; the other end stays the chart edge
      if (o.fromMark == null) m.rowMin = tMin;
      if (o.toMark == null) m.rowMax = tMax;
    }
  }

  private static long absolute(long v, long runStart) {
    return v < 100000000000L ? runStart + v : v;
  }

  private static Annotations.Row findMark(List<Annotations.Row> rows, String badge, boolean first) {
    String b = badge.trim();
    if (b.endsWith(".")) b = b.substring(0, b.length() - 1);
    Annotations.Row hit = null;
    for (Annotations.Row r : rows) {
      if (r.badge.equals(b)) {
        hit = r;
        if (first) return r;
      }
    }
    return hit;
  }

  private static void collectSeries(PointSource src, final ProfileStats stats, final Model m, final PngOptions o)
      throws IOException {
    final int cols = Math.max(1, (int) Math.round(m.plot.w * o.scale));
    final long tMin = m.tMin, span = Math.max(1, m.tMax - m.tMin), tMax = m.tMax;
    final long[] fT = new long[cols], fV = new long[cols], lT = new long[cols], lV = new long[cols];
    final long[] nT = new long[cols], nV = new long[cols], xT = new long[cols], xV = new long[cols];
    final boolean[] has = new boolean[cols];
    final boolean[] hasC = new boolean[cols];
    final long[] cMin = o.committed ? new long[cols] : null, cMax = o.committed ? new long[cols] : null;
    final long[] cMinT = o.committed ? new long[cols] : null, cMaxT = o.committed ? new long[cols] : null;
    final DataPoint[] edge = new DataPoint[2]; // last sample before tMin, first after tMax
    src.forEach(new PointSource.Visitor() {
      public void visit(DataPoint p) {
        if (p.index > stats.lastIndex) return;
        if (p.kind == DataPoint.Kind.REFERENCE) return; // a line value, not a heap reading
        if (p.kind != DataPoint.Kind.SAMPLE && p.timestamp >= tMin && p.timestamp <= tMax
            && !Annotations.isAnnotated(p.kind)) {
          m.events.add(p);
        }
        // the heap line goes through every heap reading (samples, GC, marks, ...), like the browser chart
        long ts = p.timestamp;
        if (ts < tMin) { if (edge[0] == null || ts >= edge[0].timestamp) edge[0] = p; return; }
        if (ts > tMax) { if (edge[1] == null || ts < edge[1].timestamp) edge[1] = p; return; }
        int c = (int) Math.min(cols - 1, (ts - tMin) * cols / span);
        long v = p.usedMB;
        boolean sample = p.kind == DataPoint.Kind.SAMPLE;
        if (!has[c]) {
          has[c] = true;
          fT[c] = lT[c] = nT[c] = xT[c] = ts;
          fV[c] = lV[c] = nV[c] = xV[c] = v;
          if (cMin != null && sample) { cMin[c] = cMax[c] = p.committedMB; cMinT[c] = cMaxT[c] = ts; hasC[c] = true; }
          return;
        }
        if (ts < fT[c]) { fT[c] = ts; fV[c] = v; }
        if (ts >= lT[c]) { lT[c] = ts; lV[c] = v; }
        if (v < nV[c]) { nV[c] = v; nT[c] = ts; }
        if (v > xV[c]) { xV[c] = v; xT[c] = ts; }
        if (cMin != null && sample && !hasC[c]) {
          cMin[c] = cMax[c] = p.committedMB; cMinT[c] = cMaxT[c] = ts; hasC[c] = true;
        } else if (cMin != null && sample) {
          if (p.committedMB < cMin[c]) { cMin[c] = p.committedMB; cMinT[c] = ts; }
          if (p.committedMB > cMax[c]) { cMax[c] = p.committedMB; cMaxT[c] = ts; }
        }
      }
    });
    int cap = cols * 4 + 2;
    long[] sT = new long[cap], sV = new long[cap];
    int n = 0;
    if (edge[0] != null) { sT[n] = edge[0].timestamp; sV[n++] = edge[0].usedMB; }
    long[] tt = new long[4], vv = new long[4];
    for (int c = 0; c < cols; c++) {
      if (!has[c]) continue;
      tt[0] = fT[c]; vv[0] = fV[c];
      tt[1] = nT[c]; vv[1] = nV[c];
      tt[2] = xT[c]; vv[2] = xV[c];
      tt[3] = lT[c]; vv[3] = lV[c];
      sort4(tt, vv);
      for (int k = 0; k < 4; k++) {
        if (n > 0 && sT[n - 1] == tt[k] && sV[n - 1] == vv[k]) continue;
        sT[n] = tt[k]; sV[n++] = vv[k];
      }
    }
    if (edge[1] != null) { sT[n] = edge[1].timestamp; sV[n++] = edge[1].usedMB; }
    m.seriesTs = sT;
    m.seriesMB = sV;
    m.seriesLen = n;
    if (cMin != null) {
      long[] cT = new long[cols * 2], cV = new long[cols * 2];
      int k = 0;
      for (int c = 0; c < cols; c++) {
        if (!hasC[c]) continue;
        boolean minFirst = cMinT[c] <= cMaxT[c];
        cT[k] = minFirst ? cMinT[c] : cMaxT[c]; cV[k++] = minFirst ? cMin[c] : cMax[c];
        if (cMin[c] != cMax[c]) { cT[k] = minFirst ? cMaxT[c] : cMinT[c]; cV[k++] = minFirst ? cMax[c] : cMin[c]; }
      }
      m.committedTs = cT;
      m.committedMB = cV;
      m.committedLen = k;
    }
  }

  private static void sort4(long[] t, long[] v) {
    for (int i = 1; i < 4; i++) {
      long kt = t[i], kv = v[i];
      int j = i - 1;
      while (j >= 0 && t[j] > kt) { t[j + 1] = t[j]; v[j + 1] = v[j]; j--; }
      t[j + 1] = kt; v[j + 1] = kv;
    }
  }

  private static void computeYScale(Model m, PngOptions o, ProfileStats stats, List<DataPoint> userRefs) {
    long peak = 0;
    for (int i = 0; i < m.seriesLen; i++) if (m.seriesMB[i] > peak) peak = m.seriesMB[i];
    for (int i = 0; i < m.committedLen; i++) if (m.committedMB[i] > peak) peak = m.committedMB[i];
    long peakDot = 0;
    for (Annotations.Row r : m.rows) if (r.point.usedMB > peakDot) peakDot = r.point.usedMB;
    for (DataPoint e : m.events) if (e.usedMB > peak) peak = e.usedMB;
    peak = Math.max(peak, peakDot);
    long top = peak;
    for (DataPoint r : userRefs) if (r.usedMB > top) top = r.usedMB;
    long jvmMax = stats.jvmMaxHeapMB;
    if (jvmMax > 0 && (peak == 0 || jvmMax <= peak * 3 / 2)) top = Math.max(top, jvmMax);
    double raw = Math.max(16, top * 1.08);
    // leave room above the highest dot for one badge
    double room = Annotations.DOT_GAP + Annotations.BADGE_H + 6;
    if (peakDot > 0 && m.plot.h > room * 2) raw = Math.max(raw, peakDot * m.plot.h / (m.plot.h - room));
    m.gb = raw >= 2048;
    double unit = m.gb ? 1024 : 1;
    double step = niceStep(raw / unit / 5) * unit;
    if (!m.gb) step = Math.max(1, step);
    m.yStep = step;
    m.yMaxMB = Math.ceil(raw / step - 1e-9) * step;
  }

  static double niceStep(double raw) {
    if (raw <= 0) return 1;
    double exp = Math.floor(Math.log10(raw));
    double base = Math.pow(10, exp);
    double f = raw / base;
    double nf = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
    return nf * base;
  }

  private static void layoutRefs(Model m, List<DataPoint> userRefs, long jvmMaxMB) {
    List<RefLine> lines = new ArrayList<RefLine>();
    for (DataPoint r : userRefs) {
      RefLine l = new RefLine();
      l.mb = r.usedMB;
      l.label = r.label == null || r.label.trim().isEmpty() ? formatMB(r.usedMB, m.gb) : r.label;
      lines.add(l);
    }
    if (jvmMaxMB > 0) {
      RefLine l = new RefLine();
      l.mb = jvmMaxMB;
      l.jvmMax = true;
      l.label = "JVM max heap · " + formatMB(jvmMaxMB, true);
      if (jvmMaxMB <= m.yMaxMB) {
        lines.add(l);
      } else {
        m.offScaleNote = "JVM max heap " + formatMB(jvmMaxMB, true) + " (off scale)";
        double w = width(F_REF, m.offScaleNote) + 8;
        m.offScaleRect = new Annotations.Rect(m.plot.right() - w - 4, m.plot.y + 3, w, 16);
      }
    }
    List<Annotations.Rect> taken = new ArrayList<Annotations.Rect>();
    if (m.offScaleRect != null) taken.add(m.offScaleRect);
    for (RefLine l : lines) {
      if (l.mb > m.yMaxMB) continue;
      double y = m.yAt(l.mb);
      double w = width(F_REF, l.label) + 8;
      double x = m.plot.right() - w - 4;
      Annotations.Rect above = new Annotations.Rect(x, y - 17, w, 15);
      Annotations.Rect below = new Annotations.Rect(x, y + 2, w, 15);
      if (above.y >= m.plot.y && isFree(above, taken)) l.labelRect = above;
      else if (below.bottom() <= m.plot.bottom() && isFree(below, taken)) l.labelRect = below;
      if (l.labelRect != null) taken.add(l.labelRect);
      m.refs.add(l);
    }
  }

  private static boolean isFree(Annotations.Rect r, List<Annotations.Rect> taken) {
    for (Annotations.Rect t : taken) if (r.near(t, 2)) return false;
    return true;
  }

  private static String subtitle(Model m, PngOptions o) {
    StringBuilder sb = new StringBuilder(m.meta.effectiveSubtitle());
    if (m.meta.jvmMaxHeapMB > 0) {
      if (sb.length() > 0) sb.append(" · ");
      sb.append("JVM max heap ").append(formatMB(m.meta.jvmMaxHeapMB, true));
    }
    if (sb.length() > 0) sb.append(" · ");
    sb.append("duration ").append(formatElapsed(m.runEnd - m.runStart, 1000));
    if (m.meta.peakMB > 0) sb.append(" · peak ").append(formatMB(m.meta.peakMB, true));
    if (m.tMin > m.runStart || m.tMax < m.runEnd) {
      sb.append(" · showing ").append(formatElapsed(Math.max(0, m.tMin - m.runStart), 1000))
          .append('–').append(formatElapsed(m.tMax - m.runStart, 1000));
    }
    return sb.toString();
  }

  static final double BRACKET_ROW_H = 21;

  /** Segment brackets at the top of the plot: greedy rows, label above a line with end ticks. */
  private static void layoutBrackets(Model m) {
    List<double[]> rowsRight = new ArrayList<double[]>();
    for (Segments.Segment sg : m.segments) {
      double x1 = Math.max(m.plot.x, m.xAt(sg.from.point.timestamp));
      double x2 = Math.min(m.plot.right(), m.xAt(sg.to.point.timestamp));
      String label = Segments.USER.equals(sg.kind) ? sg.label() : sg.callout();
      double lw = width(F_REF, label) + 10;
      double cx = (x1 + x2) / 2;
      double lx = Annotations.clamp(cx - lw / 2, m.plot.x, m.plot.right() - lw);
      double left = Math.min(x1, lx), right = Math.max(x2, lx + lw);
      int row = 0;
      while (row < rowsRight.size() && rowsRight.get(row)[0] + 6 > left) row++;
      if (row == rowsRight.size()) rowsRight.add(new double[] {Double.NEGATIVE_INFINITY});
      rowsRight.get(row)[0] = right;
      double y = m.plot.y + 4 + row * BRACKET_ROW_H;
      m.bracketRects.add(new Annotations.Rect(left, y, right - left, BRACKET_ROW_H - 3));
    }
  }

  private static String key2(Model m) {
    StringBuilder sb = new StringBuilder();
    if (m.gc != null && m.gc.totalMs > 0) {
      sb.append(m.gc.summary()).append('.');
      if (!m.gc.regions.isEmpty()) sb.append(" Shaded: GC took at least ").append(m.gc.thresholdPct).append("% of wall time.");
    }
    for (Segments.Segment sg : m.segments) {
      if (Segments.USER.equals(sg.kind)) continue;
      if (sb.length() > 0) sb.append(' ');
      sb.append(Character.toUpperCase(sg.kind.charAt(0))).append(sg.kind.substring(1)).append(": marks ")
          .append(sg.name()).append(' ').append(Segments.signedMB(sg.deltaMB)).append(" in ")
          .append(formatDurationShort(sg.durationMs)).append('.');
    }
    return sb.toString();
  }

  /** One column of the marks table. */
  static final class TableCol {
    final String id, head;
    double w;
    final boolean right;

    TableCol(String id, String head, double w, boolean right) {
      this.id = id;
      this.head = head;
      this.w = w;
      this.right = right;
    }
  }

  static final String[] ALL_COLUMNS = {"badge", "mark", "retained", "delta", "first", "elapsed", "gc", "location", "thread"};

  static List<TableCol> columns(PngOptions o, List<Annotations.Row> rows) {
    List<String> ids = new ArrayList<String>();
    if (o.tableColumns != null) {
      for (String c : o.tableColumns.split(",")) {
        String id = c.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) continue;
        boolean known = false;
        for (String a : ALL_COLUMNS) if (a.equals(id)) known = true;
        if (!known) throw new IllegalArgumentException("unknown table column " + c.trim() + "; use " + String.join(",", ALL_COLUMNS));
        ids.add(id);
      }
    } else {
      boolean anyGc = false;
      for (Annotations.Row r : rows) if (r.point.gcMs != null) anyGc = true;
      for (String a : ALL_COLUMNS) if (!"gc".equals(a) || anyGc) ids.add(a);
    }
    List<TableCol> cols = new ArrayList<TableCol>();
    for (String id : ids) {
      if ("badge".equals(id)) cols.add(new TableCol(id, "#", 44, false));
      else if ("mark".equals(id)) cols.add(new TableCol(id, "Mark", 0, false));
      else if ("retained".equals(id)) cols.add(new TableCol(id, "Retained MB", 92, true));
      else if ("delta".equals(id)) cols.add(new TableCol(id, "Δ prev", 72, true));
      else if ("first".equals(id)) cols.add(new TableCol(id, "Δ first", 76, true));
      else if ("elapsed".equals(id)) cols.add(new TableCol(id, "Elapsed (s)", 80, true));
      else if ("gc".equals(id)) cols.add(new TableCol(id, "GC ms", 64, true));
      else if ("location".equals(id)) cols.add(new TableCol(id, "Location", o.locationWidth, false));
      else if ("thread".equals(id)) cols.add(new TableCol(id, "Thread", 130, false));
    }
    double total = o.width - 48, fixed = 0;
    TableCol flex = null;
    for (TableCol c : cols) { if ("mark".equals(c.id)) flex = c; else fixed += c.w; }
    if (flex != null) {
      double rest = total - fixed;
      if (rest < 200) { // squeeze location and thread first
        for (TableCol c : cols) {
          if (rest >= 200) break;
          if ("location".equals(c.id) || "thread".equals(c.id)) {
            double cut = Math.min(c.w - 70, 200 - rest);
            if (cut > 0) { c.w -= cut; rest += cut; }
          }
        }
      }
      flex.w = Math.max(120, rest);
    } else if (!cols.isEmpty()) {
      double scale = total / Math.max(1, fixed);
      if (scale > 1) for (TableCol c : cols) c.w *= scale;
    }
    return cols;
  }

  private static String key(Model m, PngOptions o) {
    StringBuilder sb = new StringBuilder();
    sb.append("Line: ").append(m.meta.samplingText()).append(", includes garbage.");
    sb.append(" Numbered dots: retained heap after a forced full GC at each mark.");
    if (m.gaps != null) {
      boolean dashed = false, broken = false;
      for (GapRule.Gap g : m.gaps.gaps) {
        if (g.to < m.tMin || g.from > m.tMax) continue;
        if (g.bridged) dashed = true; else broken = true;
      }
      if (dashed) sb.append(" Dashed: no sample for over ").append(formatDuration(m.gaps.dashAfterMs))
          .append(", JVM busy in GC.");
      if (broken) sb.append(" Gap: no observation (VM paused).");
    }
    if (m.hiddenBadges > 0) {
      sb.append(' ').append(m.hiddenBadges).append(m.hiddenBadges == 1 ? " badge" : " badges")
          .append(" hidden for space").append(o.includeTable ? "; see the table." : ".");
    }
    return sb.toString();
  }

  // ---- drawing ----

  static BufferedImage draw(Model m, PngOptions o) {
    Headless.ensureForExport();
    Palette pal = palette(o.isDark());
    BufferedImage img = new BufferedImage(m.widthPx, m.heightPx, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
      g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.setColor(pal.bg);
      g.fillRect(0, 0, m.widthPx, m.heightPx);
      g.scale(o.scale, o.scale);

      drawHeader(g, m, pal);
      if (m.marksOnly) {
        drawTable(g, m, pal);
        return img;
      }
      drawBands(g, m, pal);
      drawGrid(g, m, pal, o);
      Shape oldClip = g.getClip();
      g.clip(new Rectangle2D.Double(m.plot.x, m.plot.y - 1, m.plot.w, m.plot.h + 2));
      drawGcShading(g, m, pal, o);
      drawSeries(g, m, pal, o);
      g.setClip(oldClip);
      drawBrackets(g, m, pal);
      drawRefs(g, m, pal);
      drawEvents(g, m, pal);
      drawBadges(g, m, pal);
      drawBandTitles(g, m, pal);
      drawXAxis(g, m, pal, o);
      drawKey(g, m, pal);
      if (m.table) drawTable(g, m, pal);
    } finally {
      g.dispose();
    }
    return img;
  }

  private static void drawHeader(Graphics2D g, Model m, Palette pal) {
    double maxW = m.width - 2 * 24;
    GithubLink.draw(g, m.width - 24, 34 - GithubLink.capHeight(F_TITLE) / 2, maxW, pal.mute);
    g.setColor(pal.text);
    g.setFont(F_TITLE);
    g.drawString(fitText(F_TITLE, m.title, maxW - GithubLink.reserved(maxW)), 24f, 34f);
    g.setColor(pal.mute);
    g.setFont(F_SUB);
    g.drawString(fitText(F_SUB, m.subtitle, maxW), 24f, 54f);
  }

  private static void drawBands(Graphics2D g, Model m, Palette pal) {
    for (Annotations.Band b : m.bands) {
      double x1 = Math.max(m.plot.x, m.xAt(b.startTs));
      double x2 = Math.min(m.plot.right(), m.xAt(b.endTs));
      if (x2 < x1) continue;
      if (x2 - x1 < 1) { double c = (x1 + x2) / 2; x1 = c - 0.5; x2 = c + 0.5; }
      g.setColor(pal.bandFill);
      g.fill(new Rectangle2D.Double(x1, m.plot.y, x2 - x1, m.plot.h));
      g.setColor(pal.bandEdge);
      g.setStroke(new BasicStroke(1f));
      if (b.startTs >= m.tMin) g.draw(new Line2D.Double(x1, m.plot.y, x1, m.plot.bottom()));
      if (b.endTs <= m.tMax && !b.open) g.draw(new Line2D.Double(x2, m.plot.y, x2, m.plot.bottom()));
    }
  }

  private static void drawGcShading(Graphics2D g, Model m, Palette pal, PngOptions o) {
    boolean dark = o.isDark();
    if (o.shadeForcedGc) {
      g.setColor(dark ? rgba(0xffffff, 0.07) : rgba(0x0f172a, 0.07));
      for (Annotations.Row r : m.rows) {
        Long gms = r.point.gcMs;
        if (gms == null || gms.longValue() <= 0) continue;
        double x1 = m.xAt(r.point.timestamp - gms.longValue()), x2 = m.xAt(r.point.timestamp);
        g.fill(new Rectangle2D.Double(x1, m.plot.y, Math.max(1, x2 - x1), m.plot.h));
      }
    }
    if (m.gc == null || m.gc.regions.isEmpty()) return;
    Color fill = dark ? rgba(0xff6e6e, 0.10) : rgba(0xd64545, 0.07);
    Color text = dark ? rgb(0xff9a9a) : rgb(0xb42318);
    g.setFont(F_REF);
    for (long[] r : m.gc.regions) {
      if (r[1] < m.tMin || r[0] > m.tMax) continue;
      double x1 = Math.max(m.plot.x, m.xAt(r[0])), x2 = Math.min(m.plot.right(), m.xAt(r[1]));
      if (x2 - x1 < 1) continue;
      g.setColor(fill);
      g.fill(new Rectangle2D.Double(x1, m.plot.y, x2 - x1, m.plot.h));
      String label = "GC overhead " + r[2] + "%";
      double w = width(F_REF, label);
      if (x2 - x1 >= w + 8) {
        g.setColor(text);
        g.drawString(label, (float) (x1 + 4), (float) (m.plot.bottom() - 6));
      } else if (x2 - x1 >= width(F_REF, r[2] + "%") + 6) {
        g.setColor(text);
        g.drawString(r[2] + "%", (float) (x1 + 3), (float) (m.plot.bottom() - 6));
      }
    }
  }

  private static void drawBrackets(Graphics2D g, Model m, Palette pal) {
    g.setFont(F_REF);
    double ascent = F_REF.getLineMetrics("Ag", FRC).getAscent();
    for (int i = 0; i < m.segments.size(); i++) {
      Segments.Segment sg = m.segments.get(i);
      Annotations.Rect r = m.bracketRects.get(i);
      Color c = Segments.INCREASE.equals(sg.kind) ? pal.up : Segments.RELEASE.equals(sg.kind) ? pal.down : pal.text;
      double x1 = Math.max(m.plot.x, m.xAt(sg.from.point.timestamp));
      double x2 = Math.min(m.plot.right(), m.xAt(sg.to.point.timestamp));
      double ly = r.y + 14;
      g.setColor(withAlpha(c, 0.9));
      g.setStroke(new BasicStroke(1.2f));
      g.draw(new Line2D.Double(x1, ly, x2, ly));
      g.draw(new Line2D.Double(x1, ly - 3, x1, ly + 3));
      g.draw(new Line2D.Double(x2, ly - 3, x2, ly + 3));
      String label = Segments.USER.equals(sg.kind) ? sg.label() : sg.callout();
      double lw = width(F_REF, label) + 10;
      double lx = Annotations.clamp((x1 + x2) / 2 - lw / 2, m.plot.x, m.plot.right() - lw);
      g.setColor(withAlpha(pal.bg, 0.9));
      g.fill(new RoundRectangle2D.Double(lx, r.y, lw, 13, 4, 4));
      g.setColor(c);
      g.drawString(label, (float) (lx + 5), (float) (r.y + (13 + ascent) / 2 - 1.5));
    }
  }

  private static void drawBandTitles(Graphics2D g, Model m, Palette pal) {
    g.setFont(F_BAND);
    double ascent = F_BAND.getLineMetrics("Ag", FRC).getAscent();
    for (int i = 0; i < m.titles.size(); i++) {
      Annotations.TitlePlacement t = m.titles.get(i);
      if (t.hidden) continue;
      double rowTop = m.bandTop + t.row * Annotations.TITLE_ROW_H;
      g.setColor(pal.mute);
      g.drawString(t.text, (float) (t.x + Annotations.TITLE_PAD_X), (float) (rowTop + ascent + 1));
    }
  }

  /** Tick times for the x axis: multiples of a nice step from the run start (or the epoch for clock time). */
  private static List<Long> xTicks(Model m, PngOptions o) {
    long span = Math.max(1, m.tMax - m.tMin);
    long step = timeStep(span, m.plot.w);
    long origin = o.clockAxis ? 0 : m.runStart;
    List<Long> ticks = new ArrayList<Long>();
    for (long t = origin + ceilDiv(m.tMin - origin, step) * step; t <= m.tMax; t += step) ticks.add(Long.valueOf(t));
    return ticks;
  }

  private static void drawGrid(Graphics2D g, Model m, Palette pal, PngOptions o) {
    g.setFont(F_AXIS);
    g.setStroke(new BasicStroke(1f));
    g.setColor(pal.grid);
    for (Long t : xTicks(m, o)) {
      double x = m.xAt(t.longValue());
      g.draw(new Line2D.Double(x, m.plot.y, x, m.plot.bottom()));
    }
    double ascent = F_AXIS.getLineMetrics("0", FRC).getAscent();
    for (double v = 0; v <= m.yMaxMB + 1e-6; v += m.yStep) {
      double y = m.yAt(v);
      g.setColor(v == 0 ? pal.axis : pal.grid);
      g.draw(new Line2D.Double(m.plot.x, y, m.plot.right(), y));
      String label = formatTick(v, m.gb, m.yStep);
      g.setColor(pal.mute);
      g.drawString(label, (float) (m.plot.x - 8 - width(F_AXIS, label)), (float) (y + ascent / 2 - 1));
    }
  }

  private static void drawSeries(Graphics2D g, Model m, Palette pal, PngOptions o) {
    if (m.seriesLen == 0) return;
    double base = m.yAt(0);
    Path2D.Double solid = new Path2D.Double();
    Path2D.Double dashed = new Path2D.Double();
    Path2D.Double area = new Path2D.Double();
    List<double[]> dots = new ArrayList<double[]>();
    int segStart = 0;
    for (int i = 1; i <= m.seriesLen; i++) {
      int style = i == m.seriesLen ? GapRule.BREAK : m.gaps.style(m.seriesTs[i - 1], m.seriesTs[i]);
      if (style != GapRule.BREAK) {
        double x0 = m.xAt(m.seriesTs[i - 1]), y0 = m.yAt(m.seriesMB[i - 1]);
        double x1 = m.xAt(m.seriesTs[i]), y1 = m.yAt(m.seriesMB[i]);
        Path2D.Double p = style == GapRule.DASHED ? dashed : solid;
        p.moveTo(x0, y0);
        p.lineTo(x1, y1);
        continue;
      }
      // close the continuous section [segStart, i)
      if (i - segStart == 1) {
        dots.add(new double[] {m.xAt(m.seriesTs[segStart]), m.yAt(m.seriesMB[segStart])});
      } else {
        area.moveTo(m.xAt(m.seriesTs[segStart]), base);
        for (int k = segStart; k < i; k++) area.lineTo(m.xAt(m.seriesTs[k]), m.yAt(m.seriesMB[k]));
        area.lineTo(m.xAt(m.seriesTs[i - 1]), base);
        area.closePath();
      }
      segStart = i;
    }
    g.setColor(pal.area);
    g.fill(area);
    if (m.committedLen > 1) {
      Path2D.Double c = new Path2D.Double();
      c.moveTo(m.xAt(m.committedTs[0]), m.yAt(m.committedMB[0]));
      for (int k = 1; k < m.committedLen; k++) c.lineTo(m.xAt(m.committedTs[k]), m.yAt(m.committedMB[k]));
      g.setColor(pal.committed);
      g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] {3, 3}, 0));
      g.draw(c);
    }
    g.setColor(pal.line);
    g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.draw(solid);
    g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] {5, 4}, 0));
    g.draw(dashed);
    for (double[] d : dots) g.fill(new Ellipse2D.Double(d[0] - 1.6, d[1] - 1.6, 3.2, 3.2));
  }

  private static void drawRefs(Graphics2D g, Model m, Palette pal) {
    g.setFont(F_REF);
    double ascent = F_REF.getLineMetrics("Ag", FRC).getAscent();
    for (RefLine r : m.refs) {
      double y = m.yAt(r.mb);
      Color c = r.jvmMax ? pal.refMax : pal.ref;
      g.setColor(c);
      g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {6, 4}, 0));
      g.draw(new Line2D.Double(m.plot.x, y, m.plot.right(), y));
      if (r.labelRect != null) {
        Annotations.Rect lr = r.labelRect;
        g.setColor(withAlpha(pal.bg, 0.85));
        g.fill(new RoundRectangle2D.Double(lr.x, lr.y, lr.w, lr.h, 4, 4));
        g.setColor(c);
        g.drawString(r.label, (float) (lr.x + 4), (float) (lr.y + (lr.h + ascent) / 2 - 1.5));
      }
    }
    if (m.offScaleNote != null) {
      Annotations.Rect lr = m.offScaleRect;
      g.setColor(withAlpha(pal.bg, 0.85));
      g.fill(new RoundRectangle2D.Double(lr.x, lr.y, lr.w, lr.h, 4, 4));
      g.setColor(pal.refMax);
      g.drawString(m.offScaleNote, (float) (lr.x + 4), (float) (lr.y + (lr.h + ascent) / 2 - 1.5));
    }
  }

  private static void drawEvents(Graphics2D g, Model m, Palette pal) {
    g.setStroke(new BasicStroke(1.5f));
    for (DataPoint e : m.events) {
      if (e.kind != DataPoint.Kind.HEAP_DUMP) continue;
      double x = m.xAt(e.timestamp), y = m.yAt(e.usedMB);
      Path2D.Double d = new Path2D.Double();
      d.moveTo(x, y - 5); d.lineTo(x + 5, y); d.lineTo(x, y + 5); d.lineTo(x - 5, y); d.closePath();
      g.setColor(pal.kind(e.kind));
      g.fill(d);
      g.setColor(pal.bg);
      g.draw(d);
    }
    // dots for every annotated point in range, hidden badge or not
    for (Annotations.Row r : m.rows) {
      double x = m.xAt(r.point.timestamp), y = m.yAt(r.point.usedMB);
      Ellipse2D.Double dot = new Ellipse2D.Double(x - Annotations.DOT_R, y - Annotations.DOT_R,
          2 * Annotations.DOT_R, 2 * Annotations.DOT_R);
      g.setColor(pal.kind(r.point.kind));
      g.fill(dot);
      g.setColor(pal.bg);
      g.draw(dot);
    }
  }

  private static void drawBadges(Graphics2D g, Model m, Palette pal) {
    // leaders first, so no leader crosses a badge drawn earlier
    g.setStroke(new BasicStroke(1f));
    for (int i = 0; i < m.badges.size(); i++) {
      Annotations.BadgePlacement b = m.badges.get(i);
      if (b.hidden || !b.leader) continue;
      Annotations.Row r = m.rows.get(i);
      double ax = m.xAt(r.point.timestamp), ay = m.yAt(r.point.usedMB);
      double cx = b.rect.x + b.rect.w / 2;
      g.setColor(withAlpha(pal.kind(r.point.kind), 0.75));
      if (b.below) g.draw(new Line2D.Double(cx, b.rect.y, ax, ay + Annotations.DOT_R));
      else g.draw(new Line2D.Double(cx, b.rect.bottom(), ax, ay - Annotations.DOT_R));
    }
    g.setFont(F_BADGE);
    double ascent = F_BADGE.getLineMetrics("0", FRC).getAscent();
    for (int i = 0; i < m.badges.size(); i++) {
      Annotations.BadgePlacement b = m.badges.get(i);
      if (b.hidden) continue;
      Annotations.Row r = m.rows.get(i);
      Color fill = pal.kind(r.point.kind);
      RoundRectangle2D.Double rr = new RoundRectangle2D.Double(b.rect.x, b.rect.y, b.rect.w, b.rect.h,
          Annotations.BADGE_H, Annotations.BADGE_H);
      g.setColor(fill);
      g.fill(rr);
      g.setColor(pal.bg);
      g.draw(rr);
      g.setColor(badgeText(fill));
      double tw = width(F_BADGE, r.badge);
      g.drawString(r.badge, (float) (b.rect.x + (b.rect.w - tw) / 2), (float) (b.rect.y + (b.rect.h + ascent) / 2 - 1));
    }
  }

  private static void drawXAxis(Graphics2D g, Model m, Palette pal, PngOptions o) {
    double bottom = m.plot.bottom();
    g.setFont(F_AXIS);
    long step = timeStep(Math.max(1, m.tMax - m.tMin), m.plot.w);
    double ascent = F_AXIS.getLineMetrics("0", FRC).getAscent();
    SimpleDateFormat clock = new SimpleDateFormat(step < 1000 ? "HH:mm:ss.SSS" : "HH:mm:ss", Locale.ROOT);
    g.setStroke(new BasicStroke(1f));
    double lastRight = Double.NEGATIVE_INFINITY;
    for (Long tick : xTicks(m, o)) {
      long t = tick.longValue();
      double x = m.xAt(t);
      g.setColor(pal.axis);
      g.draw(new Line2D.Double(x, bottom, x, bottom + 4));
      String label = o.clockAxis ? clock.format(new Date(t)) : formatElapsed(t - m.runStart, step);
      double w = width(F_AXIS, label);
      double lx = Annotations.clamp(x - w / 2, m.plot.x - 20, m.width - w - 4);
      if (lx < lastRight + 6) continue;
      g.setColor(pal.mute);
      g.drawString(label, (float) lx, (float) (bottom + 10 + ascent));
      lastRight = lx + w;
    }
    // GC ticks just under the axis
    if (o.gcTicks) {
      g.setStroke(new BasicStroke(1.2f));
      for (DataPoint e : m.events) {
        if (e.kind != DataPoint.Kind.GC && e.kind != DataPoint.Kind.AUTO_GC) continue;
        double x = m.xAt(e.timestamp);
        g.setColor(withAlpha(pal.kind(e.kind), 0.8));
        g.draw(new Line2D.Double(x, bottom + 1, x, bottom + 7));
      }
    }
    String axisName = o.clockAxis ? "time" : "elapsed (m:ss)";
    g.setColor(pal.faint);
    g.drawString(axisName, (float) (m.plot.right() - width(F_AXIS, axisName)), (float) (bottom + 26 + ascent));
    String unit = m.gb ? "GB" : "MB";
    g.drawString(unit, (float) (m.plot.x - 8 - width(F_AXIS, unit)), (float) (m.plot.y - 6));
  }

  private static void drawKey(Graphics2D g, Model m, Palette pal) {
    g.setFont(F_KEY);
    g.setColor(pal.mute);
    float y = (float) (m.plot.bottom() + 50);
    // small swatches: line and dot
    g.setColor(pal.line);
    g.setStroke(new BasicStroke(2f));
    g.draw(new Line2D.Double(m.plot.x, y - 4, m.plot.x + 14, y - 4));
    g.setColor(pal.mark);
    g.fill(new Ellipse2D.Double(m.plot.x + 20, y - 8, 8, 8));
    g.setColor(pal.mute);
    g.drawString(fitText(F_KEY, m.key, m.width - m.plot.x - 34 - 24), (float) (m.plot.x + 34), y);
    if (m.key2 != null && !m.key2.isEmpty()) {
      g.drawString(fitText(F_KEY, m.key2, m.width - m.plot.x - 34 - 24), (float) (m.plot.x + 34), y + 16);
    }
  }

  private static void drawTable(Graphics2D g, Model m, Palette pal) {
    double left = 24, right = m.width - 24;
    double y = m.tableTop;
    g.setFont(F_TABLE_TITLE);
    g.setColor(pal.text);
    double titleAscent = F_TABLE_TITLE.getLineMetrics("A", FRC).getAscent();
    g.drawString("Marks", (float) left, (float) (y + titleAscent));
    g.setFont(F_TABLE);
    g.setColor(pal.mute);
    String count = m.rows.isEmpty() ? "none in range" : String.valueOf(m.rows.size());
    g.drawString(count, (float) (left + width(F_TABLE_TITLE, "Marks") + 8), (float) (y + titleAscent));
    y += 26;

    double ascentHead = F_TABLE_HEAD.getLineMetrics("A", FRC).getAscent();
    double ascent = F_TABLE.getLineMetrics("A", FRC).getAscent();
    g.setColor(pal.tableHead);
    g.fill(new Rectangle2D.Double(left, y, right - left, 24));
    g.setColor(pal.mute);
    double x = left;
    for (TableCol c : m.columns) {
      drawCell(g, F_TABLE_HEAD, c.head, x, c.w, y + (24 + ascentHead) / 2 - 1, c.right);
      x += c.w;
    }
    y += 24;

    DecimalFormat mbFmt = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));
    if (m.rows.isEmpty()) {
      g.setFont(F_TABLE);
      g.setColor(pal.faint);
      g.drawString("No marks in this range.", (float) (left + 8), (float) (y + (TABLE_ROW_H + ascent) / 2 - 1));
      y += TABLE_ROW_H;
    }
    double badgeAscent = F_BADGE.getLineMetrics("0", FRC).getAscent();
    Long firstMB = m.rows.isEmpty() ? null : Long.valueOf(m.rows.get(0).point.usedMB);
    for (int i = 0; i < m.tableRows; i++) {
      Annotations.Row r = m.rows.get(i);
      if (i % 2 == 1) {
        g.setColor(pal.zebra);
        g.fill(new Rectangle2D.Double(left, y, right - left, TABLE_ROW_H));
      }
      double base = y + (TABLE_ROW_H + ascent) / 2 - 1;
      x = left;
      for (TableCol c : m.columns) {
        g.setColor(pal.text);
        if ("badge".equals(c.id)) {
          Color fill = pal.kind(r.point.kind);
          double bw = Math.max(Annotations.BADGE_H, width(F_BADGE, r.badge) + 2 * Annotations.BADGE_PAD_X);
          double by = y + (TABLE_ROW_H - Annotations.BADGE_H) / 2;
          g.setColor(fill);
          g.fill(new RoundRectangle2D.Double(x + 6, by, bw, Annotations.BADGE_H, Annotations.BADGE_H, Annotations.BADGE_H));
          g.setColor(badgeText(fill));
          g.setFont(F_BADGE);
          g.drawString(r.badge, (float) (x + 6 + (bw - width(F_BADGE, r.badge)) / 2),
              (float) (by + (Annotations.BADGE_H + badgeAscent) / 2 - 1));
        } else if ("mark".equals(c.id)) {
          drawCell(g, F_TABLE, rowText(r), x, c.w, base, false);
        } else if ("retained".equals(c.id)) {
          drawCell(g, F_TABLE, mbFmt.format(r.point.usedMB), x, c.w, base, true);
        } else if ("delta".equals(c.id) || "first".equals(c.id)) {
          Long d = "delta".equals(c.id) ? r.deltaPrevMB
              : i == 0 || firstMB == null ? null : Long.valueOf(r.point.usedMB - firstMB.longValue());
          if (d != null) {
            long v = d.longValue();
            g.setColor(v > 0 ? pal.up : v < 0 ? pal.down : pal.mute);
            drawCell(g, F_TABLE, (v > 0 ? "+" : "") + mbFmt.format(v), x, c.w, base, true);
          }
        } else if ("elapsed".equals(c.id)) {
          drawCell(g, F_TABLE, String.format(Locale.ROOT, "%.1f", r.elapsedMs / 1000.0), x, c.w, base, true);
        } else if ("gc".equals(c.id)) {
          g.setColor(pal.mute);
          if (r.point.gcMs != null) drawCell(g, F_TABLE, mbFmt.format(r.point.gcMs.longValue()), x, c.w, base, true);
        } else if ("location".equals(c.id)) {
          g.setColor(pal.mute);
          drawCell(g, F_MONO, r.shortLocation, x, c.w, base, false);
        } else if ("thread".equals(c.id)) {
          g.setColor(pal.mute);
          drawCell(g, F_TABLE, r.point.threadName == null ? "" : r.point.threadName, x, c.w, base, false);
        }
        x += c.w;
      }
      y += TABLE_ROW_H;
    }
    if (m.rows.size() > m.tableRows) {
      g.setFont(F_TABLE);
      g.setColor(pal.faint);
      g.drawString("… " + (m.rows.size() - m.tableRows) + " more (see the HTML export)",
          (float) (left + 8), (float) (y + (TABLE_ROW_H + ascent) / 2 - 1));
      y += TABLE_ROW_H;
    }
    g.setColor(pal.border);
    g.setStroke(new BasicStroke(1f));
    g.draw(new Line2D.Double(left, y, right, y));
  }

  static String rowText(Annotations.Row r) {
    if (r.point.kind == DataPoint.Kind.SCOPE_START) return r.text + "  (scope start)";
    if (r.point.kind == DataPoint.Kind.SCOPE_END) {
      StringBuilder sb = new StringBuilder(r.text).append("  (scope end");
      if (r.point.deltaMB != null) {
        long d = r.point.deltaMB.longValue();
        sb.append(", ").append(d >= 0 ? "+" : "").append(d).append(" MB");
      }
      if (r.point.durationMs != null) sb.append(" in ").append(formatDuration(r.point.durationMs.longValue()));
      return sb.append(')').toString();
    }
    return r.text;
  }

  static void drawCell(Graphics2D g, Font f, String text, double x, double w, double baseline, boolean right) {
    if (text == null || text.isEmpty()) return;
    double pad = 8;
    String t = fitText(f, text, w - 2 * pad);
    g.setFont(f);
    double tx = right ? x + w - pad - width(f, t) : x + pad;
    g.drawString(t, (float) tx, (float) baseline);
  }

  // ---- text helpers ----

  static double width(Font f, String s) {
    if (s == null || s.isEmpty()) return 0;
    return f.getStringBounds(s, FRC).getWidth();
  }

  static Annotations.TextMeasurer measurer(final Font f) {
    return new Annotations.TextMeasurer() {
      public double width(String text) {
        return PngRenderer.width(f, text);
      }
    };
  }

  static String fitText(Font f, String s, double maxW) {
    if (s == null) return "";
    if (width(f, s) <= maxW) return s;
    for (int n = s.length() - 1; n >= 1; n--) {
      String c = s.substring(0, n).trim() + "…";
      if (width(f, c) <= maxW) return c;
    }
    return "";
  }

  static Color withAlpha(Color c, double a) {
    return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(a * 255));
  }

  static String formatMB(long mb, boolean preferGb) {
    if (preferGb && mb >= 1024) {
      double gb = mb / 1024.0;
      String s = String.format(Locale.ROOT, "%.1f", gb);
      if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
      return s + " GB";
    }
    return mb + " MB";
  }

  static String formatTick(double mb, boolean gb, double stepMB) {
    if (!gb) return String.valueOf(Math.round(mb));
    double v = mb / 1024.0, step = stepMB / 1024.0;
    int decimals = step >= 1 ? 0 : step >= 0.1 ? 1 : 2;
    if (decimals > 0 && Math.abs(step * Math.pow(10, decimals) - Math.round(step * Math.pow(10, decimals))) > 1e-6) {
      decimals++;
    }
    return String.format(Locale.ROOT, "%." + decimals + "f", v);
  }

  /** {@code m:ss} (or {@code h:mm:ss}); tenths when the tick step is under a second. */
  static String formatElapsed(long ms, long step) {
    boolean neg = ms < 0;
    ms = Math.abs(ms);
    long totalSec = ms / 1000;
    long h = totalSec / 3600, mi = (totalSec / 60) % 60, s = totalSec % 60;
    StringBuilder sb = new StringBuilder();
    if (neg) sb.append('-');
    if (h > 0) sb.append(h).append(':').append(two(mi));
    else sb.append(mi);
    sb.append(':').append(two(s));
    if (step < 1000) sb.append('.').append((ms % 1000) / 100);
    return sb.toString();
  }

  static String formatDuration(long ms) {
    if (ms < 1000) return ms + " ms";
    if (ms < 60000) return String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
    return formatElapsed(ms, 1000);
  }

  /** {@code 850 ms}, {@code 49.6 s}, {@code 9:02}. */
  static String formatDurationShort(long ms) {
    if (ms < 1000) return ms + " ms";
    if (ms < 60000) return String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
    return formatElapsed(ms, 1000);
  }

  private static String two(long v) {
    return v < 10 ? "0" + v : String.valueOf(v);
  }

  private static final long[] TIME_STEPS = {
      100, 200, 500, 1000, 2000, 5000, 10000, 15000, 30000, 60000, 120000, 300000, 600000,
      900000, 1800000, 3600000, 7200000, 10800000, 21600000, 43200000, 86400000};

  static long timeStep(long spanMs, double plotW) {
    double maxTicks = Math.max(2, plotW / 90);
    for (long s : TIME_STEPS) if (spanMs / (double) s <= maxTicks) return s;
    return TIME_STEPS[TIME_STEPS.length - 1] * (long) Math.ceil(spanMs / (TIME_STEPS[TIME_STEPS.length - 1] * maxTicks));
  }

  private static long ceilDiv(long a, long b) {
    long q = a / b;
    if ((a % b != 0) && ((a ^ b) >= 0)) q++;
    return q;
  }

  // ---- PNG encoding ----

  static void writePng(BufferedImage img, OutputStream out) throws IOException {
    Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("png");
    if (!it.hasNext()) throw new IOException("no PNG writer available");
    ImageWriter writer = it.next();
    MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out);
    try {
      writer.setOutput(ios);
      writer.write(null, new IIOImage(img, null, null), writer.getDefaultWriteParam());
    } finally {
      writer.dispose();
      ios.close();
    }
  }
}
