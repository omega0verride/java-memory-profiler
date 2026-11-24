package com.toro.al.memprofiler;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The overlay PNG of {@code export --compare}: one line per run on a shared
 * elapsed-time axis (aligned at the start or at a mark), a legend and a direct
 * label at the end of each line, the reference lines, the same gap rule as the
 * single-run chart ({@link GapRule}), and the mark comparison table.
 */
final class ComparePngRenderer {

  private ComparePngRenderer() {}

  static final double MARGIN_L = 74;
  static final double TITLE_BLOCK = 106;
  static final double ROW_H = 22;

  static final class Series {
    long[] ts = new long[0];
    long[] mb = new long[0];
    int len;
  }

  /** Layout decisions, exposed for tests. */
  static final class Model {
    double width, height;
    int widthPx, heightPx;
    Annotations.Rect plot;
    long xMin, xMax;
    double yMaxMB, yStep;
    boolean gb;
    String title, subtitle;
    final List<Series> series = new ArrayList<Series>();
    /** End-label rectangles, per run (null when the run has no points). */
    final List<Annotations.Rect> endLabels = new ArrayList<Annotations.Rect>();
    double tableTop;
    double markColW;

    double xAt(long elapsedMs) {
      return plot.x + (elapsedMs - xMin) / (double) Math.max(1, xMax - xMin) * plot.w;
    }

    double yAt(double mb) {
      return plot.y + (1 - mb / yMaxMB) * plot.h;
    }
  }

  static File render(Comparison c, PngOptions opts, File file) throws IOException {
    File parent = file.getAbsoluteFile().getParentFile();
    if (parent != null) parent.mkdirs();
    OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16);
    try {
      render(c, opts, out);
    } finally {
      out.close();
    }
    return file;
  }

  static void render(Comparison c, PngOptions o, OutputStream out) throws IOException {
    if (o == null) o = PngOptions.defaults();
    Model m = model(c, o);
    PngRenderer.writePng(draw(c, m, o), out);
  }

  static String defaultTitle(Comparison c) {
    StringBuilder sb = new StringBuilder("Heap comparison: ");
    for (int i = 0; i < c.runs.size(); i++) {
      if (i > 0) sb.append(i == 1 ? " vs " : ", ");
      sb.append(c.runs.get(i).label);
    }
    return sb.toString();
  }

  static String subtitle(Comparison c) {
    StringBuilder sb = new StringBuilder();
    sb.append(c.runs.size()).append(" runs · x: elapsed since ");
    sb.append(c.alignMark == null ? "each run's start" : "mark " + c.alignMark + " of each run");
    sb.append(" · dots: marks (retained heap after a forced GC) · dashed: sampler stalled by GC");
    return sb.toString();
  }

  static Model model(final Comparison c, PngOptions o) throws IOException {
    final Model m = new Model();
    m.width = o.width;
    m.title = o.title != null && !o.title.trim().isEmpty() ? o.title : defaultTitle(c);
    m.subtitle = subtitle(c);
    double labelW = 0;
    for (Comparison.Run r : c.runs) labelW = Math.max(labelW, PngRenderer.width(PngRenderer.F_KEY, r.label));
    double marginR = Math.min(240, labelW + 26);
    m.plot = new Annotations.Rect(MARGIN_L, TITLE_BLOCK, o.width - MARGIN_L - marginR, o.height);
    m.xMin = c.minMs();
    m.xMax = Math.max(c.maxMs(), m.xMin + 1000);

    // y scale over every run and the reference lines
    long top = c.peakMB();
    for (DataPoint ref : c.references) top = Math.max(top, ref.usedMB);
    double raw = Math.max(16, top * 1.08);
    m.gb = raw >= 2048;
    double unit = m.gb ? 1024 : 1;
    m.yStep = PngRenderer.niceStep(raw / unit / 5) * unit;
    if (!m.gb) m.yStep = Math.max(1, m.yStep);
    m.yMaxMB = Math.ceil(raw / m.yStep - 1e-9) * m.yStep;

    // M4 per pixel column, per run
    final int cols = Math.max(1, (int) Math.round(m.plot.w * o.scale));
    for (final Comparison.Run r : c.runs) {
      final long[] fT = new long[cols], fV = new long[cols], lT = new long[cols], lV = new long[cols];
      final long[] nT = new long[cols], nV = new long[cols], xT = new long[cols], xV = new long[cols];
      final boolean[] has = new boolean[cols];
      final long span = m.xMax - m.xMin;
      r.src.forEach(new PointSource.Visitor() {
        public void visit(DataPoint p) {
          if (p.kind == DataPoint.Kind.REFERENCE) return;
          long e = p.timestamp - r.alignTs;
          int col = (int) Math.max(0, Math.min(cols - 1, (e - m.xMin) * cols / Math.max(1, span)));
          long t = p.timestamp, v = p.usedMB;
          if (!has[col]) {
            has[col] = true;
            fT[col] = lT[col] = nT[col] = xT[col] = t;
            fV[col] = lV[col] = nV[col] = xV[col] = v;
            return;
          }
          if (t < fT[col]) { fT[col] = t; fV[col] = v; }
          if (t >= lT[col]) { lT[col] = t; lV[col] = v; }
          if (v < nV[col]) { nV[col] = v; nT[col] = t; }
          if (v > xV[col]) { xV[col] = v; xT[col] = t; }
        }
      });
      Series s = new Series();
      s.ts = new long[cols * 4];
      s.mb = new long[cols * 4];
      long[] tt = new long[4], vv = new long[4];
      for (int k = 0; k < cols; k++) {
        if (!has[k]) continue;
        tt[0] = fT[k]; vv[0] = fV[k];
        tt[1] = nT[k]; vv[1] = nV[k];
        tt[2] = xT[k]; vv[2] = xV[k];
        tt[3] = lT[k]; vv[3] = lV[k];
        for (int i = 1; i < 4; i++) { // insertion sort by time
          long kt = tt[i], kv = vv[i];
          int j = i - 1;
          while (j >= 0 && tt[j] > kt) { tt[j + 1] = tt[j]; vv[j + 1] = vv[j]; j--; }
          tt[j + 1] = kt; vv[j + 1] = kv;
        }
        for (int i = 0; i < 4; i++) {
          if (s.len > 0 && s.ts[s.len - 1] == tt[i] && s.mb[s.len - 1] == vv[i]) continue;
          s.ts[s.len] = tt[i];
          s.mb[s.len++] = vv[i];
        }
      }
      m.series.add(s);
    }

    // direct labels at the end of each line, stacked so they never overlap
    final List<double[]> ends = new ArrayList<double[]>(); // {run, y}
    for (int i = 0; i < c.runs.size(); i++) {
      Series s = m.series.get(i);
      if (s.len == 0) continue;
      ends.add(new double[] {i, m.yAt(s.mb[s.len - 1])});
    }
    Collections.sort(ends, new Comparator<double[]>() {
      public int compare(double[] a, double[] b) { return Double.compare(a[1], b[1]); }
    });
    double h = 15, prevBottom = m.plot.y - 1;
    double[] ys = new double[ends.size()];
    for (int i = 0; i < ends.size(); i++) {
      double y = Math.max(ends.get(i)[1] - h / 2, prevBottom + 1);
      ys[i] = y;
      prevBottom = y + h;
    }
    // push back up if the stack ran past the bottom
    double over = prevBottom - m.plot.bottom();
    if (over > 0) for (int i = ys.length - 1; i >= 0; i--) {
      ys[i] -= over;
      if (i > 0 && ys[i - 1] + h + 1 <= ys[i]) break;
      if (i > 0) over = ys[i - 1] + h + 1 - ys[i]; else break;
    }
    for (int i = 0; i < c.runs.size(); i++) m.endLabels.add(null);
    for (int i = 0; i < ends.size(); i++) {
      int run = (int) ends.get(i)[0];
      Series s = m.series.get(run);
      double x = m.xAt(s.ts[s.len - 1] - c.runs.get(run).alignTs) + 6;
      double w = PngRenderer.width(PngRenderer.F_KEY, c.runs.get(run).label) + 4;
      m.endLabels.set(run, new Annotations.Rect(Math.min(x, o.width - w - 4), ys[i], w, h));
    }

    // table
    m.tableTop = m.plot.bottom() + 46;
    int rows = c.markRows.size() + c.summary.size();
    m.markColW = Math.max(220, Math.min(420, o.width * 0.3));
    double y = m.tableTop + 26 + 24 + rows * ROW_H + 10;
    m.height = Math.ceil(y + 12);
    m.widthPx = (int) Math.ceil(m.width * o.scale);
    m.heightPx = (int) Math.ceil(m.height * o.scale);
    return m;
  }

  static BufferedImage draw(Comparison c, Model m, PngOptions o) {
    Headless.ensureForExport();
    boolean dark = o.isDark();
    PngRenderer.Palette pal = PngRenderer.palette(dark);
    BufferedImage img = new BufferedImage(m.widthPx, m.heightPx, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
      g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
      g.setColor(pal.bg);
      g.fillRect(0, 0, m.widthPx, m.heightPx);
      g.scale(o.scale, o.scale);

      // header and legend
      double maxW = m.width - 48;
      GithubLink.draw(g, m.width - 24, 34 - GithubLink.capHeight(PngRenderer.F_TITLE) / 2, maxW, pal.mute);
      g.setColor(pal.text);
      g.setFont(PngRenderer.F_TITLE);
      g.drawString(PngRenderer.fitText(PngRenderer.F_TITLE, m.title, maxW - GithubLink.reserved(maxW)), 24f, 34f);
      g.setColor(pal.mute);
      g.setFont(PngRenderer.F_SUB);
      g.drawString(PngRenderer.fitText(PngRenderer.F_SUB, m.subtitle, maxW), 24f, 54f);
      double lx = 24;
      g.setFont(PngRenderer.F_KEY);
      for (int i = 0; i < c.runs.size(); i++) {
        Comparison.Run r = c.runs.get(i);
        String text = r.label + "  peak " + PngRenderer.formatMB(r.stats.peakMB, true)
            + (r.stats.peakRetainedMB >= 0 ? " · retained " + PngRenderer.formatMB(r.stats.peakRetainedMB, true) : "");
        double tw = PngRenderer.width(PngRenderer.F_KEY, text);
        if (lx + tw + 30 > m.width - 24) break;
        g.setColor(new Color(Comparison.color(i, dark)));
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(lx, 76, lx + 16, 76));
        g.setColor(pal.text);
        g.drawString(text, (float) (lx + 22), 80f);
        lx += 22 + tw + 22;
      }

      drawGrid(g, m, pal);
      Shape oldClip = g.getClip();
      g.clip(new Rectangle2D.Double(m.plot.x, m.plot.y - 2, m.plot.w, m.plot.h + 4));
      drawRefs(g, c, m, pal);
      for (int i = 0; i < c.runs.size(); i++) drawRun(g, c, m, i, new Color(Comparison.color(i, dark)), pal);
      g.setClip(oldClip);
      // end labels
      g.setFont(PngRenderer.F_KEY);
      double asc = PngRenderer.F_KEY.getLineMetrics("Ag", PngRenderer.FRC).getAscent();
      for (int i = 0; i < c.runs.size(); i++) {
        Annotations.Rect r = m.endLabels.get(i);
        if (r == null) continue;
        Series s = m.series.get(i);
        double ex = m.xAt(s.ts[s.len - 1] - c.runs.get(i).alignTs), ey = m.yAt(s.mb[s.len - 1]);
        g.setColor(new Color(Comparison.color(i, dark)));
        g.setStroke(new BasicStroke(1f));
        if (Math.abs(r.y + r.h / 2 - ey) > 2) g.draw(new Line2D.Double(ex + 2, ey, r.x - 1, r.y + r.h / 2));
        g.setColor(pal.text);
        g.drawString(c.runs.get(i).label, (float) (r.x + 2), (float) (r.y + (r.h + asc) / 2 - 1.5));
      }
      drawXAxis(g, c, m, pal);
      drawTable(g, c, m, pal, dark);
    } finally {
      g.dispose();
    }
    return img;
  }

  private static void drawGrid(Graphics2D g, Model m, PngRenderer.Palette pal) {
    g.setFont(PngRenderer.F_AXIS);
    g.setStroke(new BasicStroke(1f));
    double ascent = PngRenderer.F_AXIS.getLineMetrics("0", PngRenderer.FRC).getAscent();
    for (double v = 0; v <= m.yMaxMB + 1e-6; v += m.yStep) {
      double y = m.yAt(v);
      g.setColor(v == 0 ? pal.axis : pal.grid);
      g.draw(new Line2D.Double(m.plot.x, y, m.plot.right(), y));
      String label = PngRenderer.formatTick(v, m.gb, m.yStep);
      g.setColor(pal.mute);
      g.drawString(label, (float) (m.plot.x - 8 - PngRenderer.width(PngRenderer.F_AXIS, label)), (float) (y + ascent / 2 - 1));
    }
    String unit = m.gb ? "GB" : "MB";
    g.setColor(pal.faint);
    g.drawString(unit, (float) (m.plot.x - 8 - PngRenderer.width(PngRenderer.F_AXIS, unit)), (float) (m.plot.y - 6));
    long step = PngRenderer.timeStep(m.xMax - m.xMin, m.plot.w);
    g.setColor(pal.grid);
    for (long t = ceil(m.xMin, step); t <= m.xMax; t += step) {
      double x = m.xAt(t);
      g.draw(new Line2D.Double(x, m.plot.y, x, m.plot.bottom()));
    }
  }

  private static long ceil(long v, long step) {
    long q = Math.floorDiv(v, step);
    if (q * step < v) q++;
    return q * step;
  }

  private static void drawXAxis(Graphics2D g, Comparison c, Model m, PngRenderer.Palette pal) {
    g.setFont(PngRenderer.F_AXIS);
    double ascent = PngRenderer.F_AXIS.getLineMetrics("0", PngRenderer.FRC).getAscent();
    long step = PngRenderer.timeStep(m.xMax - m.xMin, m.plot.w);
    double lastRight = Double.NEGATIVE_INFINITY;
    for (long t = ceil(m.xMin, step); t <= m.xMax; t += step) {
      double x = m.xAt(t);
      g.setColor(pal.axis);
      g.draw(new Line2D.Double(x, m.plot.bottom(), x, m.plot.bottom() + 4));
      String label = PngRenderer.formatElapsed(t, step);
      double w = PngRenderer.width(PngRenderer.F_AXIS, label);
      double lx = x - w / 2;
      if (lx < lastRight + 6) continue;
      g.setColor(pal.mute);
      g.drawString(label, (float) lx, (float) (m.plot.bottom() + 10 + ascent));
      lastRight = lx + w;
    }
    String name = c.alignMark == null ? "elapsed since start (m:ss)" : "elapsed since mark " + c.alignMark + " (m:ss)";
    g.setColor(pal.faint);
    g.drawString(name, (float) (m.plot.right() - PngRenderer.width(PngRenderer.F_AXIS, name)),
        (float) (m.plot.bottom() + 26 + ascent));
  }

  private static void drawRefs(Graphics2D g, Comparison c, Model m, PngRenderer.Palette pal) {
    g.setFont(PngRenderer.F_REF);
    double ascent = PngRenderer.F_REF.getLineMetrics("Ag", PngRenderer.FRC).getAscent();
    for (DataPoint r : c.references) {
      if (r.usedMB > m.yMaxMB) continue;
      double y = m.yAt(r.usedMB);
      g.setColor(pal.ref);
      g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {6, 4}, 0));
      g.draw(new Line2D.Double(m.plot.x, y, m.plot.right(), y));
      String label = r.label == null || r.label.trim().isEmpty() ? PngRenderer.formatMB(r.usedMB, true) : r.label;
      double w = PngRenderer.width(PngRenderer.F_REF, label) + 8;
      g.setColor(PngRenderer.withAlpha(pal.bg, 0.85));
      g.fill(new RoundRectangle2D.Double(m.plot.x + 4, y - 17, w, 15, 4, 4));
      g.setColor(pal.ref);
      g.drawString(label, (float) (m.plot.x + 8), (float) (y - 17 + (15 + ascent) / 2 - 1.5));
    }
  }

  private static void drawRun(Graphics2D g, Comparison c, Model m, int i, Color color, PngRenderer.Palette pal) {
    Comparison.Run r = c.runs.get(i);
    Series s = m.series.get(i);
    Path2D.Double solid = new Path2D.Double(), dashed = new Path2D.Double();
    for (int k = 1; k < s.len; k++) {
      int style = r.gaps.style(s.ts[k - 1], s.ts[k]);
      if (style == GapRule.BREAK) continue;
      Path2D.Double p = style == GapRule.DASHED ? dashed : solid;
      p.moveTo(m.xAt(s.ts[k - 1] - r.alignTs), m.yAt(s.mb[k - 1]));
      p.lineTo(m.xAt(s.ts[k] - r.alignTs), m.yAt(s.mb[k]));
    }
    g.setColor(color);
    g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.draw(solid);
    g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] {5, 4}, 0));
    g.draw(dashed);
    for (Annotations.Row row : r.rows) {
      double x = m.xAt(row.point.timestamp - r.alignTs), y = m.yAt(row.point.usedMB);
      Ellipse2D.Double dot = new Ellipse2D.Double(x - 3.5, y - 3.5, 7, 7);
      g.setColor(color);
      g.fill(dot);
      g.setColor(pal.bg);
      g.setStroke(new BasicStroke(1.2f));
      g.draw(dot);
    }
  }

  private static void drawTable(Graphics2D g, Comparison c, Model m, PngRenderer.Palette pal, boolean dark) {
    double left = 24, right = m.width - 24;
    double y = m.tableTop;
    double titleAsc = PngRenderer.F_TABLE_TITLE.getLineMetrics("A", PngRenderer.FRC).getAscent();
    g.setFont(PngRenderer.F_TABLE_TITLE);
    g.setColor(pal.text);
    g.drawString("Marks: retained MB per run (Δ to " + c.runs.get(0).label + ")", (float) left, (float) (y + titleAsc));
    y += 26;
    int n = c.runs.size();
    double badgeW = 44, markW = m.markColW;
    double runW = (right - left - badgeW - markW) / n;
    double ascH = PngRenderer.F_TABLE_HEAD.getLineMetrics("A", PngRenderer.FRC).getAscent();
    double asc = PngRenderer.F_TABLE.getLineMetrics("A", PngRenderer.FRC).getAscent();
    g.setColor(pal.tableHead);
    g.fill(new Rectangle2D.Double(left, y, right - left, 24));
    g.setFont(PngRenderer.F_TABLE_HEAD);
    g.setColor(pal.mute);
    PngRenderer.drawCell(g, PngRenderer.F_TABLE_HEAD, "#", left, badgeW, y + (24 + ascH) / 2 - 1, false);
    PngRenderer.drawCell(g, PngRenderer.F_TABLE_HEAD, "Mark", left + badgeW, markW, y + (24 + ascH) / 2 - 1, false);
    for (int i = 0; i < n; i++) {
      double x = left + badgeW + markW + i * runW;
      g.setColor(new Color(Comparison.color(i, dark)));
      g.fill(new Rectangle2D.Double(x + runW - 8 - 10 - PngRenderer.width(PngRenderer.F_TABLE_HEAD, c.runs.get(i).label),
          y + 9, 8, 6));
      g.setColor(pal.mute);
      PngRenderer.drawCell(g, PngRenderer.F_TABLE_HEAD, c.runs.get(i).label, x, runW, y + (24 + ascH) / 2 - 1, true);
    }
    y += 24;
    int row = 0;
    for (Comparison.MarkRow r : c.markRows) {
      if (row++ % 2 == 1) {
        g.setColor(pal.zebra);
        g.fill(new Rectangle2D.Double(left, y, right - left, ROW_H));
      }
      double base = y + (ROW_H + asc) / 2 - 1;
      g.setColor(pal.mute);
      PngRenderer.drawCell(g, PngRenderer.F_TABLE, r.badge + (r.occurrence > 1 ? " (" + r.occurrence + ")" : ""),
          left, badgeW + 10, base, false);
      g.setColor(pal.text);
      PngRenderer.drawCell(g, PngRenderer.F_TABLE, r.text, left + badgeW, markW, base, false);
      Long first = r.mb(0);
      for (int i = 0; i < n; i++) {
        drawValueCell(g, pal, Comparison.value(r.mb(i), "MB"), i == 0 ? "" : Comparison.delta(first, r.mb(i), "MB"),
            left + badgeW + markW + i * runW, runW, base, r.mb(i) != null && first != null ? r.mb(i) - first : 0);
      }
      y += ROW_H;
    }
    g.setColor(pal.border);
    g.setStroke(new BasicStroke(1f));
    g.draw(new Line2D.Double(left, y, right, y));
    for (Comparison.SummaryRow s : c.summary) {
      double base = y + (ROW_H + asc) / 2 - 1;
      g.setColor(pal.text);
      PngRenderer.drawCell(g, PngRenderer.F_TABLE_HEAD, s.name, left, badgeW + markW, base, false);
      Long first = s.values[0];
      for (int i = 0; i < n; i++) {
        Long v = s.values[i];
        drawValueCell(g, pal, Comparison.value(v, s.unit), i == 0 ? "" : Comparison.delta(first, v, s.unit),
            left + badgeW + markW + i * runW, runW, base, v != null && first != null ? v - first : 0);
      }
      y += ROW_H;
    }
  }

  /** Value right-aligned, with the delta in a smaller, coloured run before it. */
  private static void drawValueCell(Graphics2D g, PngRenderer.Palette pal, String value, String delta, double x,
                                    double w, double base, long sign) {
    Font f = PngRenderer.F_TABLE;
    double vw = PngRenderer.width(f, value);
    g.setFont(f);
    g.setColor(pal.text);
    g.drawString(value, (float) (x + w - 8 - vw), (float) base);
    if (delta.isEmpty()) return;
    Font fd = f.deriveFont(10.5f);
    String d = PngRenderer.fitText(fd, delta, w - vw - 24);
    double dw = PngRenderer.width(fd, d);
    g.setFont(fd);
    g.setColor(sign > 0 ? pal.up : sign < 0 ? pal.down : pal.mute);
    g.drawString(d, (float) (x + w - 8 - vw - 8 - dw), (float) base);
  }
}
