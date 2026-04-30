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

  public synchronized void add(DataPoint dp) {
    if (dp.usedMB > maxObservedMB) maxObservedMB = dp.usedMB;
    points.add(dp);
    while (points.size() > maxInMemory) {
      points.remove(0);
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
