package com.toro.al.memprofiler;

public class DataPoint {

  public enum Kind {
    SAMPLE,
    MARK,
    SCOPE_START,
    SCOPE_END,
    GC,
    HEAP_DUMP,
    AUTO_GC,
    /**
     * A user-defined horizontal reference line ({@link MemoryProfiler#referenceLine}).
     * {@code usedMB} holds the line's value (not a heap reading) and {@code label}
     * its caption. Never part of the heap line, the peak or any statistic: the store
     * keeps it apart and it travels in the {@code references} sections of
     * {@code /data}, the exports and the JSONL {@code META} line. Files written by
     * 1.0.4 and older hold it as a point line; readers move it to the references.
     */
    REFERENCE
  }

  public final long index;
  public final long timestamp;
  public final long usedMB;
  public final long committedMB;
  public final long maxMB;
  public final Kind kind;
  public final String label;
  public final String location;
  public final String sourceFile;
  public final String scopeId;
  public final Long deltaMB;
  public final Long durationMs;
  public final String threadName;
  /**
   * MARK, SCOPE_START and SCOPE_END taken with a forced GC ({@code markGc},
   * {@code scopeGc}): wall time spent in the forced {@code System.gc()} loop just
   * before the reading. null otherwise.
   */
  public final Long gcMs;
  /**
   * SAMPLE only: cumulative collection time of all collectors since JVM start
   * ({@code GarbageCollectorMXBean.getCollectionTime()}). The difference between
   * two samples is the GC time between them, even when GC notifications were
   * lost (the JVM drops them when the heap is exhausted). null when unknown.
   */
  public final Long gcTotalMs;

  public DataPoint(long index, long timestamp, long usedMB, long committedMB, long maxMB,
                   Kind kind, String label, String location, String scopeId,
                   Long deltaMB, Long durationMs, String threadName) {
    this(index, timestamp, usedMB, committedMB, maxMB, kind, label, location,
        null, scopeId, deltaMB, durationMs, threadName);
  }

  public DataPoint(long index, long timestamp, long usedMB, long committedMB, long maxMB,
                   Kind kind, String label, String location, String sourceFile, String scopeId,
                   Long deltaMB, Long durationMs, String threadName) {
    this(index, timestamp, usedMB, committedMB, maxMB, kind, label, location, sourceFile, scopeId,
        deltaMB, durationMs, threadName, null, null);
  }

  public DataPoint(long index, long timestamp, long usedMB, long committedMB, long maxMB,
                   Kind kind, String label, String location, String sourceFile, String scopeId,
                   Long deltaMB, Long durationMs, String threadName, Long gcMs, Long gcTotalMs) {
    this.index = index;
    this.timestamp = timestamp;
    this.usedMB = usedMB;
    this.committedMB = committedMB;
    this.maxMB = maxMB;
    this.kind = kind;
    this.label = label;
    this.location = location;
    this.sourceFile = sourceFile;
    this.scopeId = scopeId;
    this.deltaMB = deltaMB;
    this.durationMs = durationMs;
    this.threadName = threadName;
    this.gcMs = gcMs;
    this.gcTotalMs = gcTotalMs;
  }

  /** This point with another index (the store assigns indexes when it appends). */
  public DataPoint withIndex(long newIndex) {
    if (newIndex == index) return this;
    return new DataPoint(newIndex, timestamp, usedMB, committedMB, maxMB, kind, label, location,
        sourceFile, scopeId, deltaMB, durationMs, threadName, gcMs, gcTotalMs);
  }

  /** This point with another scope id (concatenated "all" exports keep scope ids unique). */
  DataPoint withIndexAndScope(long newIndex, String newScopeId) {
    return new DataPoint(newIndex, timestamp, usedMB, committedMB, maxMB, kind, label, location,
        sourceFile, newScopeId, deltaMB, durationMs, threadName, gcMs, gcTotalMs);
  }

  /** True for every kind that is a heap reading (everything but {@link Kind#REFERENCE}). */
  public boolean isHeapReading() {
    return kind != Kind.REFERENCE;
  }

  public String toJsonLine() {
    return toJsonLine(null);
  }

  /** One JSONL line; {@code runId} (may be null) is appended as the last key. */
  public String toJsonLine(String runId) {
    StringBuilder sb = new StringBuilder(256);
    sb.append('{');
    sb.append("\"index\":").append(index);
    sb.append(",\"timestamp\":").append(timestamp);
    sb.append(",\"usedMB\":").append(usedMB);
    sb.append(",\"committedMB\":").append(committedMB);
    sb.append(",\"maxMB\":").append(maxMB);
    sb.append(",\"kind\":\"").append(kind.name()).append('"');
    if (label != null) sb.append(",\"label\":\"").append(escape(label)).append('"');
    if (location != null) sb.append(",\"location\":\"").append(escape(location)).append('"');
    if (sourceFile != null) sb.append(",\"sourceFile\":\"").append(escape(sourceFile)).append('"');
    if (scopeId != null) sb.append(",\"scopeId\":\"").append(escape(scopeId)).append('"');
    if (deltaMB != null) sb.append(",\"deltaMB\":").append(deltaMB);
    if (durationMs != null) sb.append(",\"durationMs\":").append(durationMs);
    if (threadName != null) sb.append(",\"thread\":\"").append(escape(threadName)).append('"');
    if (gcMs != null) sb.append(",\"gcMs\":").append(gcMs);
    if (gcTotalMs != null) sb.append(",\"gcTotalMs\":").append(gcTotalMs);
    if (runId != null) sb.append(",\"runId\":\"").append(escape(runId)).append('"');
    sb.append('}');
    return sb.toString();
  }

  static String escape(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\': sb.append("\\\\"); break;
        case '"': sb.append("\\\""); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
          else sb.append(c);
      }
    }
    return sb.toString();
  }
}
