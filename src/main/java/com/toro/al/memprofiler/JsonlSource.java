package com.toro.al.memprofiler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A profile read back from a JSONL file written with {@code Config.jsonl(path)}.
 *
 * <p>The profiler appends to the file, so one file can hold several runs. A new
 * run starts wherever {@code index} goes back to 0 (or otherwise decreases).
 * Select a run with {@code "last"} (default), {@code "all"} (runs concatenated
 * and renumbered), or a 1-based run number.
 *
 * <p>The file is streamed: one scan to find the runs, then one read per
 * {@link #forEach} call. Lines that do not parse (for example a line cut short
 * by a crash) and unknown kinds are skipped.
 */
public final class JsonlSource implements PointSource {

  /** One run inside the file: lines {@code [firstLine, endLine)} (0-based). */
  public static final class Run {
    public final int number;
    public final long firstLine;
    public long endLine;
    public long points;
    public long firstTimestamp = Long.MAX_VALUE;
    public long lastTimestamp = Long.MIN_VALUE;

    Run(int number, long firstLine) {
      this.number = number;
      this.firstLine = firstLine;
    }
  }

  private final File file;
  private final List<Run> runs;
  private final List<Run> selected;
  private final boolean renumber;
  private final List<DataPoint> references = new ArrayList<DataPoint>();
  private final ExportMeta meta;

  private JsonlSource(File file, List<Run> runs, List<Run> selected, boolean renumber) {
    this.file = file;
    this.runs = runs;
    this.selected = selected;
    this.renumber = renumber;
    this.meta = new ExportMeta();
    meta.processName = null;
    meta.pid = -1;
    StringBuilder src = new StringBuilder(file.getName());
    if (runs.size() > 1) {
      if (renumber) src.append(" (all ").append(runs.size()).append(" runs)");
      else src.append(" (run ").append(selected.get(0).number).append(" of ").append(runs.size()).append(')');
    }
    meta.source = src.toString();
  }

  /**
   * @param runSelector {@code "last"} (or null), {@code "all"}, or a 1-based run number
   */
  public static JsonlSource open(File file, String runSelector) throws IOException {
    if (!file.isFile()) throw new IOException("JSONL file not found: " + file);
    List<Run> runs = scan(file);
    if (runs.isEmpty()) throw new IOException("no data points in " + file);
    String sel = runSelector == null ? "last" : runSelector.trim().toLowerCase();
    List<Run> selected;
    boolean renumber = false;
    if (sel.isEmpty() || "last".equals(sel)) {
      selected = Collections.singletonList(runs.get(runs.size() - 1));
    } else if ("all".equals(sel)) {
      selected = runs;
      renumber = runs.size() > 1;
    } else {
      int n;
      try {
        n = Integer.parseInt(sel);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("--run must be last, all or a run number, got: " + runSelector);
      }
      if (n < 1 || n > runs.size()) {
        throw new IllegalArgumentException("run " + n + " does not exist; " + file.getName()
            + " holds " + runs.size() + " run(s)");
      }
      selected = Collections.singletonList(runs.get(n - 1));
    }
    JsonlSource src = new JsonlSource(file, runs, selected, renumber);
    src.loadReferences();
    return src;
  }

  public List<Run> runs() {
    return Collections.unmodifiableList(runs);
  }

  public List<Run> selectedRuns() {
    return Collections.unmodifiableList(selected);
  }

  public void forEach(final Visitor visitor) throws IOException {
    final long[] ordinal = {0};
    read(new LineHandler() {
      public void handle(DataPoint p, int runNumber) {
        if (p.kind == DataPoint.Kind.REFERENCE) return;
        visitor.visit(renumber ? reindex(p, ordinal[0]++, runNumber) : p);
      }
    });
  }

  public List<DataPoint> references() {
    return Collections.unmodifiableList(references);
  }

  public ExportMeta meta() {
    return meta;
  }

  private void loadReferences() throws IOException {
    read(new LineHandler() {
      public void handle(DataPoint p, int runNumber) {
        if (p.kind == DataPoint.Kind.REFERENCE) references.add(p);
      }
    });
  }

  private interface LineHandler {
    void handle(DataPoint p, int runNumber);
  }

  private void read(LineHandler handler) throws IOException {
    BufferedReader r = reader(file);
    try {
      String line;
      long lineNo = 0;
      int runIdx = 0;
      Run run = selected.get(0);
      while ((line = r.readLine()) != null) {
        long ln = lineNo++;
        while (ln >= run.endLine) {
          if (++runIdx >= selected.size()) return;
          run = selected.get(runIdx);
        }
        if (ln < run.firstLine) continue;
        DataPoint p = parseLine(line);
        if (p != null) handler.handle(p, run.number);
      }
    } finally {
      r.close();
    }
  }

  private static List<Run> scan(File file) throws IOException {
    List<Run> runs = new ArrayList<Run>();
    BufferedReader r = reader(file);
    try {
      String line;
      long lineNo = 0;
      long prevIndex = Long.MIN_VALUE;
      Run run = null;
      while ((line = r.readLine()) != null) {
        long ln = lineNo++;
        DataPoint p = parseLine(line);
        if (p == null) continue;
        if (run == null || p.index <= prevIndex) {
          if (run != null) run.endLine = ln;
          run = new Run(runs.size() + 1, ln);
          runs.add(run);
        }
        prevIndex = p.index;
        if (p.kind != DataPoint.Kind.REFERENCE) {
          run.points++;
          if (p.timestamp < run.firstTimestamp) run.firstTimestamp = p.timestamp;
          if (p.timestamp > run.lastTimestamp) run.lastTimestamp = p.timestamp;
        }
      }
      if (run != null) run.endLine = lineNo;
    } finally {
      r.close();
    }
    return runs;
  }

  private static BufferedReader reader(File f) throws IOException {
    return new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8), 1 << 16);
  }

  /** Parses one JSONL line; returns null for blank, malformed or unknown-kind lines. */
  static DataPoint parseLine(String line) {
    String t = line.trim();
    if (t.isEmpty() || t.charAt(0) != '{') return null;
    Map<String, Object> m;
    try {
      m = MiniJson.parseObject(t);
    } catch (IllegalArgumentException e) {
      return null;
    }
    String kindName = MiniJson.asString(m.get("kind"));
    DataPoint.Kind kind;
    try {
      kind = DataPoint.Kind.valueOf(kindName);
    } catch (Exception e) {
      return null; // unknown (newer) kind
    }
    if (!(m.get("index") instanceof Number) || !(m.get("timestamp") instanceof Number)) return null;
    return new DataPoint(
        MiniJson.asLong(m.get("index"), 0),
        MiniJson.asLong(m.get("timestamp"), 0),
        MiniJson.asLong(m.get("usedMB"), 0),
        MiniJson.asLong(m.get("committedMB"), 0),
        MiniJson.asLong(m.get("maxMB"), -1),
        kind,
        MiniJson.asString(m.get("label")),
        MiniJson.asString(m.get("location")),
        MiniJson.asString(m.get("sourceFile")),
        MiniJson.asString(m.get("scopeId")),
        MiniJson.asLongOrNull(m.get("deltaMB")),
        MiniJson.asLongOrNull(m.get("durationMs")),
        MiniJson.asString(m.get("thread")));
  }

  /** Renumbers a point of a concatenated ("all") export; scope ids get a run prefix to stay unique. */
  private static DataPoint reindex(DataPoint p, long index, int runNumber) {
    String scopeId = p.scopeId == null ? null : "r" + runNumber + "." + p.scopeId;
    return new DataPoint(index, p.timestamp, p.usedMB, p.committedMB, p.maxMB, p.kind, p.label,
        p.location, p.sourceFile, scopeId, p.deltaMB, p.durationMs, p.threadName);
  }
}
