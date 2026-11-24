package com.toro.al.memprofiler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only, streaming view over a profile for the exporters. Implementations
 * visit points in place (the live {@link DataStore} under its lock, a JSONL file
 * line by line) so that exporting never copies the data it is measuring.
 */
public interface PointSource {

  interface Visitor {
    void visit(DataPoint p);
  }

  /** Visits every point except {@link DataPoint.Kind#REFERENCE} points, oldest first. */
  void forEach(Visitor visitor) throws IOException;

  /** The reference lines of this profile (may be empty, never null). */
  List<DataPoint> references() throws IOException;

  /** Process-level details known about the source (title, process name, PID, ...). */
  ExportMeta meta();

  /** The live in-memory store of a running (or stopped) profiler. */
  final class Store implements PointSource {
    private final DataStore store;
    private final ExportMeta meta;

    public Store(DataStore store, ExportMeta meta) {
      this.store = store;
      this.meta = meta;
    }

    public void forEach(Visitor visitor) {
      store.forEach(visitor);
    }

    public List<DataPoint> references() {
      return store.references();
    }

    public ExportMeta meta() {
      return meta;
    }
  }

  /** A fixed list of points (tests and small tools). REFERENCE points are split out. */
  final class Fixed implements PointSource {
    private final List<DataPoint> points = new ArrayList<DataPoint>();
    private final List<DataPoint> references = new ArrayList<DataPoint>();
    private final ExportMeta meta;

    public Fixed(List<DataPoint> all, ExportMeta meta) {
      for (DataPoint p : all) {
        if (p.kind == DataPoint.Kind.REFERENCE) references.add(p);
        else points.add(p);
      }
      this.meta = meta;
    }

    public void forEach(Visitor visitor) {
      for (DataPoint p : points) visitor.visit(p);
    }

    public List<DataPoint> references() {
      return Collections.unmodifiableList(references);
    }

    public ExportMeta meta() {
      return meta;
    }
  }
}
