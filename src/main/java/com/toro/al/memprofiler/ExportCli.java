package com.toro.al.memprofiler;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code java -jar java-memory-profiler.jar export ...}: HTML and PNG exports
 * from a JSONL file, without a running profiler.
 */
final class ExportCli {

  private ExportCli() {}

  static final String USAGE =
      "Usage: java -jar java-memory-profiler.jar export --jsonl <file> [--html <out.html>] [--png <out.png>] [options]\n"
      + "       java -jar java-memory-profiler.jar export --compare <a.jsonl>=<label> <b.jsonl>=<label> ... [--html ...] [--png ...]\n"
      + "\n"
      + "Compare runs\n"
      + "  --compare <spec>...     two to eight runs on one chart, with a mark comparison table; a spec is\n"
      + "                          <file>[#<run>][=<label>] (run: a number or last; label: default the recorded title)\n"
      + "  --align start|mark:<n>  shared time zero: each run's start (default) or its first mark n\n"
      + "\n"
      + "Input\n"
      + "  --jsonl <file>          JSONL written with Config.jsonl(...) or the jsonl= agent argument\n"
      + "  --run last|all|<n>      which run of an appended file (see --list-runs); default last\n"
      + "  --list-runs             print the runs in the file (number, title, points, duration) and exit\n"
      + "  --title <text>          chart title (default: the title recorded in the file, else the file name)\n"
      + "  --subtitle <text>       chart subtitle (default: the recorded subtitle)\n"
      + "  --interval <ms>         configured sampling interval, for files that do not record it (1.0.4 and older)\n"
      + "\n"
      + "HTML\n"
      + "  --html <out.html>       write the interactive, self-contained viewer\n"
      + "  --source-roots <a;b>    roots to resolve marks' source files for embedded snippets\n"
      + "  --source-lines <n>      lines of context around each mark (default 6)\n"
      + "  --max-samples <n>       keep at most n SAMPLE points, min/max per bucket (default 20000)\n"
      + "\n"
      + "PNG\n"
      + "  --png <out.png>         write a static image of the run\n"
      + "  --width <px>            image width in logical px (default 1400)\n"
      + "  --height <px>           plot height in logical px (default 640)\n"
      + "  --scale <n>             pixel density (default 2)\n"
      + "  --from <ms> --to <ms>   time range: epoch ms, or ms since the first point when < 10^11\n"
      + "  --from-mark <n> --to-mark <n>   time range from mark badge n to mark badge n\n"
      + "  --theme light|dark      (default light)\n"
      + "  --include-table         draw the marks table under the chart\n"
      + "  --clock                 wall-clock x axis instead of elapsed m:ss\n"
      + "  --committed             also draw the committed heap line\n"
      + "  --no-gc-ticks           do not mark GC events under the x axis\n"
      + "  --segments <a:b,...>    brackets with the retained-heap change between marks, e.g. 1:2,2:4,5:7 (also printed)\n"
      + "  --no-callouts           no automatic \"largest increase\" / \"largest release\" brackets\n"
      + "  --gc-overhead <pct>     shade ranges where GC takes at least pct% of wall time (default 50, 0 = off)\n"
      + "  --shade-forced-gc       shade the forced GCs of markGc / scopeGc\n"
      + "  --marks-only-table      only the marks table, no chart\n"
      + "  --table-columns <list>  marks table columns: badge,mark,retained,delta,first,elapsed,gc,location,thread\n"
      + "  --location-width <px>   width of the location column (default 230)\n"
      + "  --format-label-numbers  thousands separators in numbers inside labels (1250000 -> 1,250,000)\n"
      + "\n"
      + "Output\n"
      + "  --summary               print peak, retained, GC time, GC share per minute and segments\n";

  static int run(String[] args, PrintStream out, PrintStream err) {
    String jsonl = null, html = null, png = null, run = "last", title = null, subtitle = null;
    String sourceRoots = null;
    long intervalMs = 0;
    boolean listRuns = false;
    boolean summary = false;
    List<String> compare = new ArrayList<String>();
    String align = null;
    HtmlExporter.Options htmlOpts = new HtmlExporter.Options();
    PngOptions pngOpts = PngOptions.defaults();
    List<String> problems = new ArrayList<String>();
    try {
      for (int i = 0; i < args.length; i++) {
        String a = args[i];
        if ("--help".equals(a) || "-h".equals(a)) { out.print(USAGE); return 0; }
        else if ("--jsonl".equals(a)) jsonl = value(args, ++i, a);
        else if ("--compare".equals(a)) {
          while (i + 1 < args.length && !args[i + 1].startsWith("--")) compare.add(args[++i]);
          if (compare.isEmpty()) problems.add("--compare needs run specs: <file>[=<label>] ...");
        }
        else if ("--align".equals(a)) align = value(args, ++i, a);
        else if ("--html".equals(a)) html = value(args, ++i, a);
        else if ("--png".equals(a)) png = value(args, ++i, a);
        else if ("--run".equals(a)) run = value(args, ++i, a);
        else if ("--list-runs".equals(a)) listRuns = true;
        else if ("--title".equals(a)) title = value(args, ++i, a);
        else if ("--subtitle".equals(a)) subtitle = value(args, ++i, a);
        else if ("--source-roots".equals(a)) sourceRoots = value(args, ++i, a);
        else if ("--interval".equals(a) || "--interval-ms".equals(a)) intervalMs = Long.parseLong(value(args, ++i, a));
        else if ("--source-lines".equals(a)) htmlOpts.sourceContextLines = Integer.parseInt(value(args, ++i, a));
        else if ("--max-samples".equals(a)) htmlOpts.maxSamples = Integer.parseInt(value(args, ++i, a));
        else if ("--width".equals(a)) pngSet(pngOpts, "width", value(args, ++i, a), problems);
        else if ("--height".equals(a)) pngSet(pngOpts, "height", value(args, ++i, a), problems);
        else if ("--scale".equals(a)) pngSet(pngOpts, "scale", value(args, ++i, a), problems);
        else if ("--from".equals(a)) pngSet(pngOpts, "from", value(args, ++i, a), problems);
        else if ("--to".equals(a)) pngSet(pngOpts, "to", value(args, ++i, a), problems);
        else if ("--from-mark".equals(a)) pngOpts.fromMark(value(args, ++i, a));
        else if ("--to-mark".equals(a)) pngOpts.toMark(value(args, ++i, a));
        else if ("--theme".equals(a)) pngOpts.theme(value(args, ++i, a));
        else if ("--include-table".equals(a)) pngOpts.includeTable(true);
        else if ("--clock".equals(a)) pngOpts.clockAxis(true);
        else if ("--committed".equals(a)) pngOpts.committed(true);
        else if ("--no-gc-ticks".equals(a)) pngOpts.gcTicks(false);
        else if ("--segments".equals(a)) pngOpts.segments(value(args, ++i, a));
        else if ("--no-callouts".equals(a)) pngOpts.callouts(false);
        else if ("--gc-overhead".equals(a)) pngSet(pngOpts, "gcOverhead", value(args, ++i, a), problems);
        else if ("--shade-forced-gc".equals(a)) pngOpts.shadeForcedGc(true);
        else if ("--marks-only-table".equals(a)) pngOpts.marksOnlyTable(true);
        else if ("--table-columns".equals(a)) pngOpts.tableColumns(value(args, ++i, a));
        else if ("--location-width".equals(a)) pngSet(pngOpts, "locationWidth", value(args, ++i, a), problems);
        else if ("--format-label-numbers".equals(a)) { pngOpts.formatLabelNumbers(true); htmlOpts.formatLabelNumbers = true; }
        else if ("--summary".equals(a)) summary = true;
        else problems.add("unknown option " + a);
      }
    } catch (IllegalArgumentException e) {
      problems.add(e.getMessage());
    }
    if (jsonl == null && compare.isEmpty()) problems.add("--jsonl is required");
    if (jsonl != null && !compare.isEmpty()) problems.add("give --jsonl or --compare, not both");
    if (html == null && png == null && !listRuns && !summary) problems.add("give --html and/or --png");
    if (!problems.isEmpty()) {
      for (String p : problems) err.println("export: " + p);
      err.println();
      err.print(USAGE);
      return 2;
    }
    if (!compare.isEmpty()) return runCompare(compare, run, align, title, html, png, pngOpts, htmlOpts, out, err);
    try {
      JsonlSource src = JsonlSource.open(new File(jsonl), run);
      if (listRuns) {
        for (JsonlSource.Run r : src.runs()) {
          StringBuilder sb = new StringBuilder("run ").append(r.number).append(": ");
          if (r.title() != null) sb.append('"').append(r.title()).append("\", ");
          sb.append(r.points).append(" points, ");
          if (r.points > 0) {
            sb.append(PngRenderer.formatElapsed(r.durationMs(), 1000)).append(" (")
                .append(ExportMeta.formatDateTime(r.firstTimestamp)).append(" to ")
                .append(ExportMeta.formatDateTime(r.lastTimestamp)).append(')');
          }
          if (r.header() != null && r.header().intervalMs() > 0) sb.append(", every ").append(r.header().intervalMs()).append(" ms");
          if (r.runId != null) sb.append(", id ").append(r.runId);
          out.println(sb);
        }
        if (html == null && png == null) return 0;
      }
      if (title != null) src.meta().title = title;
      else if (src.meta().title == null || src.meta().title.trim().isEmpty()) src.meta().title = new File(jsonl).getName();
      if (subtitle != null) src.meta().subtitle = subtitle;
      if (intervalMs > 0) src.meta().intervalMs = intervalMs;
      htmlOpts.segments = pngOpts.segments;
      htmlOpts.gcOverheadPct = pngOpts.gcOverheadPct;
      if (sourceRoots != null) htmlOpts.sourceRoots = sourceRoots.split("[;" + File.pathSeparator + "]");
      if (title != null) pngOpts.title(title);
      if (html != null) {
        File f = HtmlExporter.export(src, htmlOpts, new File(html));
        out.println("HTML written to " + f.getAbsolutePath() + " (" + f.length() / 1024 + " KB)");
      }
      if (png != null) {
        File f = PngRenderer.render(src, pngOpts, new File(png));
        out.println("PNG written to " + f.getAbsolutePath() + " (" + f.length() / 1024 + " KB)");
      }
      if (summary || pngOpts.segments != null) printSummary(src, pngOpts, summary, out);
      return 0;
    } catch (IllegalArgumentException e) {
      err.println("export: " + e.getMessage());
      return 2;
    } catch (Exception e) {
      err.println("export failed: " + e);
      return 1;
    }
  }

  private static void printSummary(PointSource src, PngOptions o, boolean full, PrintStream out) throws java.io.IOException {
    ProfileStats stats = ProfileStats.scan(src);
    ExportMeta meta = src.meta().copy();
    stats.applyTo(meta);
    GapRule.Result gaps = stats.gaps(meta.intervalMs);
    List<Annotations.Row> rows = Annotations.rows(stats.annotated, stats.firstTs);
    if (full) {
      GcPressure gc = GcPressure.of(stats, gaps, o.gcOverheadPct);
      out.println();
      out.println(meta.effectiveTitle());
      out.println("  duration     " + PngRenderer.formatDurationShort(stats.lastTs - stats.firstTs)
          + ", line: " + meta.samplingText());
      out.println("  peak         " + Comparison.mb(stats.peakMB) + " MB (sampled)"
          + (stats.peakRetainedMB >= 0 ? ", " + Comparison.mb(stats.peakRetainedMB) + " MB retained (highest mark)" : ""));
      if (stats.lastAnnotatedMB >= 0) out.println("  last mark    " + Comparison.mb(stats.lastAnnotatedMB) + " MB");
      out.println("  " + gc.summary() + ", forced GC of marks " + PngRenderer.formatDurationShort(stats.forcedGcMs));
      out.println("  GC/minute    " + gc.perMinuteText());
      out.println("  line gaps    " + gaps.bridged() + " dashed (GC stalls), " + gaps.breaks() + " breaks (no observation)");
      for (long[] r : gc.regions) {
        out.println("  GC overhead  " + PngRenderer.formatElapsed(r[0] - stats.firstTs, 1000) + "–"
            + PngRenderer.formatElapsed(r[1] - stats.firstTs, 1000) + ": " + r[2] + "%");
      }
      for (Segments.Segment s : Segments.callouts(rows, gaps == null ? null : stats.gapInput.gcTime())) {
        out.println("  " + s.callout() + " in " + PngRenderer.formatDurationShort(s.durationMs));
      }
    }
    if (o.segments != null) {
      out.println();
      out.print(Segments.table(Segments.parse(o.segments, rows, stats.gapInput.gcTime())));
    }
  }

  static int runCompare(List<String> specs, String defaultRun, String align, String title, String html, String png,
                        PngOptions pngOpts, HtmlExporter.Options htmlOpts, PrintStream out, PrintStream err) {
    try {
      List<Comparison.Run> runs = new ArrayList<Comparison.Run>();
      for (String spec : specs) {
        String path = spec, label = null, runSel = defaultRun;
        int eq = path.lastIndexOf('=');
        if (eq > 0) {
          label = path.substring(eq + 1);
          path = path.substring(0, eq);
        }
        int hash = path.lastIndexOf('#');
        if (hash > 0) {
          String sel = path.substring(hash + 1);
          if (sel.matches("\\d+|last")) {
            runSel = sel;
            path = path.substring(0, hash);
          }
        }
        JsonlSource src = JsonlSource.open(new File(path), runSel);
        if (src.meta().title == null) src.meta().title = new File(path).getName();
        runs.add(new Comparison.Run(label, src));
      }
      Comparison c = Comparison.build(runs, align);
      for (String w : c.warnings) err.println("export: warning: " + w);
      if (title != null) pngOpts.title(title);
      if (html != null) {
        File f = CompareHtmlExporter.export(c, title, htmlOpts, new File(html));
        out.println("HTML written to " + f.getAbsolutePath() + " (" + f.length() / 1024 + " KB)");
      }
      if (png != null) {
        File f = ComparePngRenderer.render(c, pngOpts, new File(png));
        out.println("PNG written to " + f.getAbsolutePath() + " (" + f.length() / 1024 + " KB)");
      }
      return 0;
    } catch (IllegalArgumentException e) {
      err.println("export: " + e.getMessage());
      return 2;
    } catch (Exception e) {
      err.println("export failed: " + e);
      return 1;
    }
  }

  private static String value(String[] args, int i, String opt) {
    if (i >= args.length) throw new IllegalArgumentException(opt + " needs a value");
    return args[i];
  }

  private static void pngSet(PngOptions o, String key, String v, List<String> problems) {
    if (!o.set(key, v)) problems.add("bad value for --" + key + ": " + v);
  }
}
