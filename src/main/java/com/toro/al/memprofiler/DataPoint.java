package com.toro.al.memprofiler;

public class DataPoint {

  public enum Kind {
    SAMPLE,
    MARK,
    SCOPE_START,
    SCOPE_END,
    GC,
    HEAP_DUMP,
    AUTO_GC
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

  public DataPoint(long index, long timestamp, long usedMB, long committedMB, long maxMB,
                   Kind kind, String label, String location, String scopeId,
                   Long deltaMB, Long durationMs, String threadName) {
    this(index, timestamp, usedMB, committedMB, maxMB, kind, label, location,
        null, scopeId, deltaMB, durationMs, threadName);
  }

  public DataPoint(long index, long timestamp, long usedMB, long committedMB, long maxMB,
                   Kind kind, String label, String location, String sourceFile, String scopeId,
                   Long deltaMB, Long durationMs, String threadName) {
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
  }

  public String toJsonLine() {
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
    sb.append('}');
    return sb.toString();
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
  }
}
