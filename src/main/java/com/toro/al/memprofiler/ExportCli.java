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
      + "\n"
      + "Input\n"
      + "  --jsonl <file>          JSONL written with Config.jsonl(...) or the jsonl= agent argument\n"
      + "  --run last|all|<n>      which run of an appended file (a run starts where index resets to 0); default last\n"
      + "  --list-runs             print the runs in the file and exit\n"
      + "  --title <text>          chart title (default: the file name)\n"
      + "  --subtitle <text>       chart subtitle\n"
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
      + "  --no-gc-ticks           do not mark GC events under the x axis\n";

  static int run(String[] args, PrintStream out, PrintStream err) {
    String jsonl = null, html = null, png = null, run = "last", title = null, subtitle = null;
    String sourceRoots = null;
    boolean listRuns = false;
    HtmlExporter.Options htmlOpts = new HtmlExporter.Options();
    PngOptions pngOpts = PngOptions.defaults();
    List<String> problems = new ArrayList<String>();
    try {
      for (int i = 0; i < args.length; i++) {
        String a = args[i];
        if ("--help".equals(a) || "-h".equals(a)) { out.print(USAGE); return 0; }
        else if ("--jsonl".equals(a)) jsonl = value(args, ++i, a);
        else if ("--html".equals(a)) html = value(args, ++i, a);
        else if ("--png".equals(a)) png = value(args, ++i, a);
        else if ("--run".equals(a)) run = value(args, ++i, a);
        else if ("--list-runs".equals(a)) listRuns = true;
        else if ("--title".equals(a)) title = value(args, ++i, a);
        else if ("--subtitle".equals(a)) subtitle = value(args, ++i, a);
        else if ("--source-roots".equals(a)) sourceRoots = value(args, ++i, a);
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
        else problems.add("unknown option " + a);
      }
    } catch (IllegalArgumentException e) {
      problems.add(e.getMessage());
    }
    if (jsonl == null) problems.add("--jsonl is required");
    if (html == null && png == null && !listRuns) problems.add("give --html and/or --png");
    if (!problems.isEmpty()) {
      for (String p : problems) err.println("export: " + p);
      err.println();
      err.print(USAGE);
      return 2;
    }
    try {
      JsonlSource src = JsonlSource.open(new File(jsonl), run);
      if (listRuns) {
        for (JsonlSource.Run r : src.runs()) {
          out.println("run " + r.number + ": " + r.points + " points, "
              + ExportMeta.formatDateTime(r.firstTimestamp) + " to " + ExportMeta.formatDateTime(r.lastTimestamp));
        }
        if (html == null && png == null) return 0;
      }
      src.meta().title = title != null ? title : new File(jsonl).getName();
      src.meta().subtitle = subtitle;
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
