package com.toro.al.memprofiler;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * The interactive comparison page of {@code export --compare --html}: a single
 * offline file (same inlining as {@link HtmlExporter}) running
 * {@code web/compare-app.js}. Runs can be toggled, the hover shows every run at
 * the same elapsed time, and clicking a mark row highlights that mark in every run.
 *
 * <p>Data: {@code {title, subtitle, align, alignMark, runs:[{label, points:[[elapsedMs, usedMB]], gaps,
 * marks, ...}], references, table:{rows, summary}}}. Times are elapsed milliseconds
 * from each run's alignment point; heap readings above {@code maxSamples / runs}
 * per run are reduced with {@link Downsampler} (min/max per bucket).
 */
final class CompareHtmlExporter {

  static final String PAGE = "compare.html";
  static final String ENTRY = "web/compare-app.js";

  private CompareHtmlExporter() {}

  static File export(Comparison c, String title, HtmlExporter.Options opts, File file) throws IOException {
    File parent = file.getAbsoluteFile().getParentFile();
    if (parent != null) parent.mkdirs();
    OutputStream out = new FileOutputStream(file);
    try {
      export(c, title, opts, out);
    } finally {
      out.close();
    }
    return file;
  }

  static void export(Comparison c, String title, HtmlExporter.Options opts, OutputStream out) throws IOException {
    if (opts == null) opts = new HtmlExporter.Options();
    String t = title != null && !title.trim().isEmpty() ? title : ComparePngRenderer.defaultTitle(c);
    String[] template = HtmlExporter.template(PAGE, ENTRY);
    Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 1 << 16);
    w.write(template[0].replace("@@TITLE@@", HtmlExporter.escapeHtml(t)));
    w.write("<script type=\"application/json\" id=\"compare-data\">");
    w.write(data(c, t, opts));
    w.write("</script>\n");
    w.write("<script>window.__COMPARE__ = JSON.parse(document.getElementById('compare-data').textContent);</script>\n");
    w.write(template[1]);
    w.flush();
  }

  static String data(Comparison c, String title, HtmlExporter.Options opts) throws IOException {
    final StringBuilder sb = new StringBuilder(256 * 1024);
    sb.append("{\"title\":"); MiniJson.quote(sb, title);
    sb.append(",\"subtitle\":"); MiniJson.quote(sb, ComparePngRenderer.subtitle(c));
    sb.append(",\"align\":"); MiniJson.quote(sb, c.align);
    sb.append(",\"alignMark\":"); MiniJson.quote(sb, c.alignMark);
    sb.append(",\"profilerVersion\":"); MiniJson.quote(sb, MemoryProfiler.version());
    sb.append(",\"exportedAt\":").append(opts.exportedAt != null ? opts.exportedAt.longValue() : System.currentTimeMillis());
    sb.append(",\"runs\":[");
    int perRun = Math.max(2000, opts.maxSamples / c.runs.size());
    for (int i = 0; i < c.runs.size(); i++) {
      final Comparison.Run r = c.runs.get(i);
      if (i > 0) sb.append(',');
      sb.append("{\"label\":"); MiniJson.quote(sb, r.label);
      sb.append(",\"color\":").append(i);
      sb.append(",\"alignTs\":").append(r.alignTs);
      sb.append(",\"alignMissing\":").append(r.alignMissing);
      sb.append(",\"startTime\":").append(r.stats.firstTs);
      sb.append(",\"startMs\":").append(r.startMs());
      sb.append(",\"endMs\":").append(r.endMs());
      sb.append(",\"durationMs\":").append(r.durationMs());
      sb.append(",\"peakMB\":").append(r.stats.peakMB);
      sb.append(",\"peakRetainedMB\":").append(r.stats.peakRetainedMB);
      sb.append(",\"gcTotalMs\":").append(r.stats.gcTotalMs());
      sb.append(",\"sampling\":"); MiniJson.quote(sb, r.meta.samplingText());
      sb.append(",\"source\":"); MiniJson.quote(sb, r.meta.source);
      sb.append(",\"points\":[");
      final boolean[] first = {true};
      Downsampler ds = new Downsampler(r.stats.sampleCount, perRun, r.stats.lastIndex, new Downsampler.Sink() {
        public void accept(DataPoint p) {
          if (p.kind == DataPoint.Kind.REFERENCE) return;
          if (!first[0]) sb.append(',');
          first[0] = false;
          sb.append('[').append(p.timestamp - r.alignTs).append(',').append(p.usedMB).append(']');
        }
      });
      r.src.forEach(ds);
      ds.finish();
      sb.append("],\"gaps\":{\"dashAfterMs\":").append(r.gaps.dashAfterMs);
      sb.append(",\"intervalMs\":").append(r.gaps.intervalMs);
      sb.append(",\"list\":[");
      for (int k = 0; k < r.gaps.gaps.size(); k++) {
        GapRule.Gap g = r.gaps.gaps.get(k);
        if (k > 0) sb.append(',');
        sb.append('[').append(g.from - r.alignTs).append(',').append(g.to - r.alignTs).append(',')
            .append(g.bridged ? 1 : 0).append(',').append(g.gcMs).append(']');
      }
      sb.append("]},\"marks\":[");
      boolean firstMark = true;
      for (java.util.Map.Entry<String, Annotations.Row> e : r.byKey.entrySet()) {
        Annotations.Row row = e.getValue();
        if (!firstMark) sb.append(',');
        firstMark = false;
        sb.append("{\"key\":"); MiniJson.quote(sb, e.getKey());
        sb.append(",\"badge\":"); MiniJson.quote(sb, row.badge);
        sb.append(",\"text\":"); MiniJson.quote(sb, PngRenderer.rowText(row));
        sb.append(",\"t\":").append(row.point.timestamp - r.alignTs);
        sb.append(",\"mb\":").append(row.point.usedMB);
        sb.append(",\"kind\":\"").append(row.point.kind.name()).append('"');
        if (row.point.gcMs != null) sb.append(",\"gcMs\":").append(row.point.gcMs);
        sb.append(",\"location\":"); MiniJson.quote(sb, row.shortLocation);
        sb.append('}');
      }
      sb.append("]}");
    }
    sb.append("],\"references\":[");
    for (int i = 0; i < c.references.size(); i++) {
      DataPoint ref = c.references.get(i);
      if (i > 0) sb.append(',');
      sb.append("{\"mb\":").append(ref.usedMB).append(",\"label\":");
      MiniJson.quote(sb, ref.label);
      sb.append('}');
    }
    sb.append("],\"table\":{\"rows\":[");
    for (int i = 0; i < c.markRows.size(); i++) {
      Comparison.MarkRow m = c.markRows.get(i);
      if (i > 0) sb.append(',');
      sb.append("{\"key\":"); MiniJson.quote(sb, m.key);
      sb.append(",\"badge\":"); MiniJson.quote(sb, m.badge);
      sb.append(",\"occurrence\":").append(m.occurrence);
      sb.append(",\"text\":"); MiniJson.quote(sb, m.text);
      sb.append(",\"cells\":[");
      for (int k = 0; k < c.runs.size(); k++) {
        if (k > 0) sb.append(',');
        Long v = m.mb(k);
        sb.append(v == null ? "null" : String.valueOf(v));
      }
      sb.append("],\"labels\":[");
      for (int k = 0; k < c.runs.size(); k++) {
        if (k > 0) sb.append(',');
        MiniJson.quote(sb, m.cells[k] == null ? null : m.cells[k].point.label);
      }
      sb.append("]}");
    }
    sb.append("],\"summary\":[");
    for (int i = 0; i < c.summary.size(); i++) {
      Comparison.SummaryRow s = c.summary.get(i);
      if (i > 0) sb.append(',');
      sb.append("{\"name\":"); MiniJson.quote(sb, s.name);
      sb.append(",\"unit\":"); MiniJson.quote(sb, s.unit);
      sb.append(",\"values\":[");
      for (int k = 0; k < s.values.length; k++) {
        if (k > 0) sb.append(',');
        sb.append(s.values[k] == null ? "null" : String.valueOf(s.values[k]));
      }
      sb.append("]}");
    }
    sb.append("]},\"warnings\":[");
    for (int i = 0; i < c.warnings.size(); i++) {
      if (i > 0) sb.append(',');
      MiniJson.quote(sb, c.warnings.get(i));
    }
    sb.append("]}");
    return sb.toString();
  }
}
