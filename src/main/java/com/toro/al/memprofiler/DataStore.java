package com.toro.al.memprofiler;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The profile of the running JVM: a bounded in-memory buffer of points, the
 * reference lines, the run header, and the optional JSONL file.
 *
 * <p>{@link #add} assigns the index and writes the JSONL line in one critical
 * section, so the file order always equals the index order, whichever thread
 * (sampler, GC listener, application) adds the point.
 */
public class DataStore {

  private final List<DataPoint> points = new ArrayList<DataPoint>();
  /** {@link DataPoint.Kind#REFERENCE} points. Few, never evicted, not part of {@link #snapshot()}. */
  private final List<DataPoint> references = new ArrayList<DataPoint>();
  private long nextIndex = 0;
  private final int maxInMemory;
  private BufferedWriter writer;
  private String jsonlPath;
  private long maxObservedMB = 0;
  private RunHeader header;

  public DataStore(int maxInMemory) {
    this(maxInMemory, null);
  }

  DataStore(int maxInMemory, RunHeader header) {
    this.maxInMemory = maxInMemory;
    this.header = header != null ? header : RunHeader.anonymous();
  }

  /** Appends to {@code path} (kept for compatibility; see {@link #enableJsonl(String, boolean)}). */
  public synchronized void enableJsonl(String path) throws IOException {
    enableJsonl(path, true);
  }

  /**
   * Starts writing JSONL to {@code path}: appended to an existing file, or the
   * file is replaced. The run's {@code META} line is written first.
   */
  public synchronized void enableJsonl(String path, boolean append) throws IOException {
    closeWriter();
    File f = new File(path);
    if (f.getAbsoluteFile().getParentFile() != null) f.getAbsoluteFile().getParentFile().mkdirs();
    this.jsonlPath = path;
    this.writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, append), StandardCharsets.UTF_8));
    writeMeta();
  }

  /** Provisional index for a point under construction; {@link #add} assigns the real one. */
  public synchronized long nextIndex() {
    return nextIndex;
  }

  /** The index the next point will get; changes whenever anything is added. */
  synchronized long peekNextIndex() {
    return nextIndex;
  }

  /**
   * Appends a point and returns it with its assigned index. A REFERENCE point is
   * not a heap reading: it goes to {@link #references()} and the run header (a
   * new {@code META} line), never to the points.
   */
  public synchronized DataPoint add(DataPoint dp) {
    dp = dp.withIndex(nextIndex++);
    if (dp.kind == DataPoint.Kind.REFERENCE) {
      references.add(dp);
      header.references.add(dp);
      writeMeta();
      return dp;
    }
    if (dp.usedMB > maxObservedMB) maxObservedMB = dp.usedMB;
    points.add(dp);
    if (points.size() > maxInMemory) {
      points.subList(0, points.size() - maxInMemory).clear();
    }
    if (writer != null) {
      try {
        writer.write(dp.toJsonLine(header.runId));
        writer.newLine();
        writer.flush();
      } catch (IOException ignored) {
      }
    }
    return dp;
  }

  /** Sets the title and subtitle of the run and writes a new {@code META} line. */
  synchronized void setTitles(String title, String subtitle) {
    header.title = title;
    header.subtitle = subtitle;
    writeMeta();
  }

  /** A copy of the run header. */
  public synchronized RunHeader header() {
    return header.copy();
  }

  private void writeMeta() {
    if (writer == null) return;
    try {
      writer.write(header.toJsonLine(System.currentTimeMillis()));
      writer.newLine();
      writer.flush();
    } catch (IOException ignored) {
    }
  }

  public synchronized List<DataPoint> snapshot() {
    return new ArrayList<DataPoint>(points);
  }

  public synchronized List<DataPoint> since(long afterIndex) {
    List<DataPoint> out = new ArrayList<DataPoint>();
    for (int i = points.size() - 1; i >= 0; i--) {
      if (points.get(i).index <= afterIndex) break;
      out.add(points.get(i));
    }
    java.util.Collections.reverse(out);
    return out;
  }

  /** User-defined reference lines, in the order they were added. */
  public synchronized List<DataPoint> references() {
    return new ArrayList<DataPoint>(references);
  }

  /**
   * Visits the in-memory points in order while holding the store lock, without
   * copying them. Visitors must be quick (the sampler waits on the lock) and
   * must not call back into the store.
   */
  public synchronized void forEach(PointSource.Visitor visitor) {
    for (int i = 0, n = points.size(); i < n; i++) {
      visitor.visit(points.get(i));
    }
  }

  public synchronized int size() {
    return points.size();
  }

  public synchronized long maxObservedMB() {
    return maxObservedMB;
  }

  public synchronized String jsonlPath() {
    return jsonlPath;
  }

  public synchronized void flush() {
    if (writer != null) {
      try {
        writer.flush();
      } catch (IOException ignored) {
      }
    }
  }

  public synchronized void close() {
    closeWriter();
  }

  private void closeWriter() {
    if (writer != null) {
      try {
        writer.close();
      } catch (IOException ignored) {
      }
      writer = null;
    }
  }
}
