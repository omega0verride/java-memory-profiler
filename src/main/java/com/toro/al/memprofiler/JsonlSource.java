package com.toro.al.memprofiler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A profile read back from a JSONL file written with {@code Config.jsonl(path)}.
 *
 * <h3>Runs</h3>
 * One file can hold several runs (appended with {@code JsonlMode.APPEND}). Files
 * written by 1.0.5 and later are self-describing: each run starts with a
 * {@code META} line and every point carries the run's {@code runId}, so a run is
 * exactly the lines with its id (even when two JVMs wrote to the file at the
 * same time). The last {@code META} line of a run gives its title, subtitle,
 * sampling interval and reference lines.
 *
 * <p>Lines without a {@code runId} (files from 1.0.4 and older) are split by
 * index: a new run starts where {@code index} goes back to 0, or drops by more
 * than {@link #MAX_INVERSION}. A small inversion (two threads that wrote out of
 * index order) stays in the same run.
 *
 * <p>Select a run with {@code "last"} (default), {@code "all"} (runs
 * concatenated and renumbered), or a 1-based run number. The file is streamed:
 * one scan to find the runs, then one read per {@link #forEach} call. Lines
 * that do not parse (for example a line cut short by a crash) and unknown kinds
 * are skipped.
 */
public final class JsonlSource implements PointSource {

  /**
   * Largest backwards step of {@code index} that still counts as the same run
   * (legacy files). Points written by concurrent threads (sampler and GC
   * listener) by 1.0.4 and older can appear a few positions out of index order.
   */
  static final long MAX_INVERSION = 16;

  /** One run inside the file. */
  public static final class Run {
    public final int number;
    /** The run id ({@code null} for runs from files without run ids). */
    public final String runId;
    public final long firstLine;
    /** One past the last line that belongs to the run. */
    public long endLine;
    public long points;
    public long firstTimestamp = Long.MAX_VALUE;
    public long lastTimestamp = Long.MIN_VALUE;
    /** The last {@code META} line of the run (null for legacy runs). */
    RunHeader header;
    /** REFERENCE point lines of a legacy run. */
    final List<DataPoint> legacyReferences = new ArrayList<DataPoint>();

    Run(int number, String runId, long firstLine) {
      this.number = number;
      this.runId = runId;
      this.firstLine = firstLine;
    }

    public String title() { return header == null ? null : header.title; }
    public RunHeader header() { return header; }
    public long durationMs() { return points == 0 ? 0 : lastTimestamp - firstTimestamp; }

    List<DataPoint> references() {
      List<DataPoint> out = new ArrayList<DataPoint>();
      if (header != null) out.addAll(header.references);
      out.addAll(legacyReferences);
      return out;
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
    for (Run r : selected) {
      references.addAll(r.references());
      RunHeader h = r.header;
      if (h == null) continue;
      if (meta.title == null) meta.title = h.title;
      if (meta.subtitle == null) meta.subtitle = h.subtitle;
      if (meta.intervalMs <= 0) meta.intervalMs = h.intervalMs;
      if (meta.processName == null) meta.processName = h.processName;
      if (meta.pid < 0) meta.pid = h.pid;
      if (meta.runId == null) meta.runId = h.runId;
      if (meta.recordedVersion == null) meta.recordedVersion = h.profilerVersion;
    }
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
    return new JsonlSource(file, runs, selected, renumber);
  }

  public List<Run> runs() {
    return Collections.unmodifiableList(runs);
  }

  public List<Run> selectedRuns() {
    return Collections.unmodifiableList(selected);
  }

  public File file() {
    return file;
  }

  public void forEach(final Visitor visitor) throws IOException {
    long ordinal = 0;
    for (Run run : selected) {
      BufferedReader r = reader(file);
      try {
        String line;
        long ln = 0;
        while ((line = r.readLine()) != null) {
          long cur = ln++;
          if (cur < run.firstLine) continue;
          if (cur >= run.endLine) break;
          Parsed p = parse(line);
          if (p == null || p.point == null || p.point.kind == DataPoint.Kind.REFERENCE) continue;
          if (run.runId != null ? !run.runId.equals(p.runId) : p.runId != null) continue;
          DataPoint dp = p.point;
          if (renumber) {
            String scopeId = dp.scopeId == null ? null : "r" + run.number + "." + dp.scopeId;
            dp = dp.withIndexAndScope(ordinal++, scopeId);
          }
          visitor.visit(dp);
        }
      } finally {
        r.close();
      }
    }
  }

  public List<DataPoint> references() {
    return Collections.unmodifiableList(references);
  }

  public ExportMeta meta() {
    return meta;
  }

  /** A new legacy run starts where the index goes back to 0, or drops by more than {@link #MAX_INVERSION}. */
  static boolean startsNewRun(long prevIndex, long index) {
    if (prevIndex == Long.MIN_VALUE) return false;
    return index == 0 || prevIndex - index > MAX_INVERSION;
  }

  private static List<Run> scan(File file) throws IOException {
    List<Run> runs = new ArrayList<Run>();
    Map<String, Run> byId = new HashMap<String, Run>();
    BufferedReader r = reader(file);
    try {
      String line;
      long lineNo = 0;
      long prevLegacyIndex = Long.MIN_VALUE;
      Run legacy = null; // the legacy run lines without a run id attach to
      while ((line = r.readLine()) != null) {
        long ln = lineNo++;
        Parsed p = parse(line);
        if (p == null) continue;
        Run run;
        if (p.header != null || p.runId != null) {
          String id = p.header != null ? p.header.runId : p.runId;
          run = byId.get(id);
          if (run == null) {
            run = new Run(runs.size() + 1, id, ln);
            runs.add(run);
            byId.put(id, run);
          }
          legacy = null;
          if (p.header != null) run.header = p.header;
        } else {
          if (legacy == null || startsNewRun(prevLegacyIndex, p.point.index)) {
            legacy = new Run(runs.size() + 1, null, ln);
            runs.add(legacy);
          }
          prevLegacyIndex = p.point.index;
          run = legacy;
        }
        run.endLine = ln + 1;
        DataPoint dp = p.point;
        if (dp == null) continue;
        if (dp.kind == DataPoint.Kind.REFERENCE) {
          if (run.runId == null) run.legacyReferences.add(dp);
          continue;
        }
        run.points++;
        if (dp.timestamp < run.firstTimestamp) run.firstTimestamp = dp.timestamp;
        if (dp.timestamp > run.lastTimestamp) run.lastTimestamp = dp.timestamp;
      }
    } finally {
      r.close();
    }
    // a META line without any point is not a run
    List<Run> out = new ArrayList<Run>();
    for (Run run : runs) if (run.points > 0 || !run.references().isEmpty()) out.add(run);
    if (out.size() != runs.size()) {
      List<Run> renumbered = new ArrayList<Run>();
      for (Run run : out) {
        Run c = new Run(renumbered.size() + 1, run.runId, run.firstLine);
        c.endLine = run.endLine;
        c.points = run.points;
        c.firstTimestamp = run.firstTimestamp;
        c.lastTimestamp = run.lastTimestamp;
        c.header = run.header;
        c.legacyReferences.addAll(run.legacyReferences);
        renumbered.add(c);
      }
      out = renumbered;
    }
    return out;
  }

  private static BufferedReader reader(File f) throws IOException {
    return new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8), 1 << 16);
  }

  /** One parsed line: a point (with its run id) or a META header. */
  static final class Parsed {
    DataPoint point;
    String runId;
    RunHeader header;
  }

  /** Parses one JSONL line; returns null for blank, malformed or unknown-kind lines. */
  static Parsed parse(String line) {
    String t = line.trim();
    if (t.isEmpty() || t.charAt(0) != '{') return null;
    Map<String, Object> m;
    try {
      m = MiniJson.parseObject(t);
    } catch (IllegalArgumentException e) {
      return null;
    }
    String kindName = MiniJson.asString(m.get("kind"));
    if ("META".equals(kindName)) {
      RunHeader h = RunHeader.fromJson(m);
      if (h == null) return null;
      Parsed p = new Parsed();
      p.header = h;
      p.runId = h.runId;
      return p;
    }
    DataPoint.Kind kind;
    try {
      kind = DataPoint.Kind.valueOf(kindName);
    } catch (Exception e) {
      return null; // unknown (newer) kind
    }
    if (!(m.get("index") instanceof Number) || !(m.get("timestamp") instanceof Number)) return null;
    Parsed p = new Parsed();
    p.runId = MiniJson.asString(m.get("runId"));
    p.point = new DataPoint(
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
        MiniJson.asString(m.get("thread")),
        MiniJson.asLongOrNull(m.get("gcMs")),
        MiniJson.asLongOrNull(m.get("gcTotalMs")));
    return p;
  }

  /** Parses one JSONL point line; null for META, blank, malformed or unknown-kind lines. */
  static DataPoint parseLine(String line) {
    Parsed p = parse(line);
    return p == null ? null : p.point;
  }
}
