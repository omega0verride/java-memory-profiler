package com.toro.al.memprofiler;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class DataStore {

  private final List<DataPoint> points = new ArrayList<DataPoint>();
  /** {@link DataPoint.Kind#REFERENCE} points. Few, never evicted, not part of {@link #snapshot()}. */
  private final List<DataPoint> references = new ArrayList<DataPoint>();
  private final AtomicLong nextIndex = new AtomicLong(0);
  private final int maxInMemory;
  private BufferedWriter writer;
  private String jsonlPath;
  private long maxObservedMB = 0;

  public DataStore(int maxInMemory) {
    this.maxInMemory = maxInMemory;
  }

  public synchronized void enableJsonl(String path) throws IOException {
    closeWriter();
    File f = new File(path);
    if (f.getParentFile() != null) f.getParentFile().mkdirs();
    this.jsonlPath = path;
    this.writer = new BufferedWriter(new FileWriter(f, true));
  }

  public long nextIndex() {
    return nextIndex.getAndIncrement();
  }

  /** The index the next point will get; changes whenever anything is added. */
  long peekNextIndex() {
    return nextIndex.get();
  }

  public synchronized void add(DataPoint dp) {
    if (dp.kind == DataPoint.Kind.REFERENCE) {
      references.add(dp);
    } else {
      if (dp.usedMB > maxObservedMB) maxObservedMB = dp.usedMB;
      points.add(dp);
      while (points.size() > maxInMemory) {
        points.remove(0);
      }
    }
    if (writer != null) {
      try {
        writer.write(dp.toJsonLine());
        writer.newLine();
        writer.flush();
      } catch (IOException ignored) {
      }
    }
  }

  public synchronized List<DataPoint> snapshot() {
    return new ArrayList<DataPoint>(points);
  }

  public synchronized List<DataPoint> since(long afterIndex) {
    List<DataPoint> out = new ArrayList<DataPoint>();
    for (DataPoint dp : points) {
      if (dp.index > afterIndex) out.add(dp);
    }
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
