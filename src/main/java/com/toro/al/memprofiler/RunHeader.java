package com.toro.al.memprofiler;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Describes one profiling run: written as the {@code META} line of the JSONL
 * file at {@code start()}, and again whenever the title, subtitle or reference
 * lines change (the last {@code META} line of a run wins). Every point of the
 * run carries the same {@code runId}.
 *
 * <pre>
 * {"kind":"META","schema":1,"runId":"…","timestamp":…,"profilerVersion":"1.0.5",
 *  "intervalMs":250,"maxMB":4096,"pid":4242,"processName":"…","jvmArgs":["-Xmx4g"],
 *  "startTime":…,"title":"…","subtitle":null,
 *  "references":[{"mb":5120,"label":"Production heap limit (5 GB)","timestamp":…}]}
 * </pre>
 */
public final class RunHeader {

  static final int SCHEMA = 1;

  String runId;
  String profilerVersion;
  /** Configured sampling interval; 0 = unknown. */
  long intervalMs;
  long maxMB = -1;
  long pid = -1;
  String processName;
  List<String> jvmArgs = new ArrayList<String>();
  long startTime;
  String title;
  String subtitle;
  /** REFERENCE points, in the order they were added. */
  final List<DataPoint> references = new ArrayList<DataPoint>();

  /** A header for a new run in this JVM. */
  static RunHeader forThisJvm(long intervalMs) {
    RunHeader h = new RunHeader();
    h.runId = UUID.randomUUID().toString();
    h.profilerVersion = MemoryProfiler.version();
    h.intervalMs = intervalMs;
    h.startTime = System.currentTimeMillis();
    h.pid = ProfilerServer.currentPid();
    h.processName = ProfilerServer.currentProcessName();
    try {
      h.maxMB = HeapSampler.toMB(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax());
      h.jvmArgs = new ArrayList<String>(ManagementFactory.getRuntimeMXBean().getInputArguments());
    } catch (Throwable ignored) {
    }
    return h;
  }

  /** A header with only a fresh run id (stores created without a profiler, e.g. in tests). */
  static RunHeader anonymous() {
    RunHeader h = new RunHeader();
    h.runId = UUID.randomUUID().toString();
    h.profilerVersion = MemoryProfiler.version();
    h.startTime = System.currentTimeMillis();
    return h;
  }

  public String runId() { return runId; }
  public String title() { return title; }
  public String subtitle() { return subtitle; }
  public long intervalMs() { return intervalMs; }
  public long startTime() { return startTime; }
  public List<DataPoint> references() { return Collections.unmodifiableList(references); }

  /** The JSONL {@code META} line. */
  String toJsonLine(long now) {
    StringBuilder sb = new StringBuilder(512);
    sb.append("{\"kind\":\"META\",\"schema\":").append(SCHEMA);
    sb.append(",\"runId\":"); MiniJson.quote(sb, runId);
    sb.append(",\"timestamp\":").append(now);
    sb.append(",\"profilerVersion\":"); MiniJson.quote(sb, profilerVersion);
    sb.append(",\"intervalMs\":").append(intervalMs);
    sb.append(",\"maxMB\":").append(maxMB);
    sb.append(",\"pid\":").append(pid);
    sb.append(",\"processName\":"); MiniJson.quote(sb, processName);
    sb.append(",\"jvmArgs\":[");
    for (int i = 0; i < jvmArgs.size(); i++) {
      if (i > 0) sb.append(',');
      MiniJson.quote(sb, jvmArgs.get(i));
    }
    sb.append("],\"startTime\":").append(startTime);
    sb.append(",\"title\":"); MiniJson.quote(sb, title);
    sb.append(",\"subtitle\":"); MiniJson.quote(sb, subtitle);
    sb.append(",\"references\":[");
    for (int i = 0; i < references.size(); i++) {
      DataPoint r = references.get(i);
      if (i > 0) sb.append(',');
      sb.append("{\"mb\":").append(r.usedMB);
      sb.append(",\"label\":"); MiniJson.quote(sb, r.label);
      sb.append(",\"timestamp\":").append(r.timestamp);
      if (r.location != null) { sb.append(",\"location\":"); MiniJson.quote(sb, r.location); }
      if (r.sourceFile != null) { sb.append(",\"sourceFile\":"); MiniJson.quote(sb, r.sourceFile); }
      sb.append('}');
    }
    sb.append("]}");
    return sb.toString();
  }

  /** Parses a {@code META} line (already parsed to a map); null if it has no run id. */
  @SuppressWarnings("unchecked")
  static RunHeader fromJson(Map<String, Object> m) {
    String runId = MiniJson.asString(m.get("runId"));
    if (runId == null || runId.isEmpty()) return null;
    RunHeader h = new RunHeader();
    h.runId = runId;
    h.profilerVersion = MiniJson.asString(m.get("profilerVersion"));
    h.intervalMs = MiniJson.asLong(m.get("intervalMs"), 0);
    h.maxMB = MiniJson.asLong(m.get("maxMB"), -1);
    h.pid = MiniJson.asLong(m.get("pid"), -1);
    h.processName = MiniJson.asString(m.get("processName"));
    if (m.get("jvmArgs") instanceof List) {
      for (Object o : (List<Object>) m.get("jvmArgs")) if (o instanceof String) h.jvmArgs.add((String) o);
    }
    h.startTime = MiniJson.asLong(m.get("startTime"), 0);
    h.title = MiniJson.asString(m.get("title"));
    h.subtitle = MiniJson.asString(m.get("subtitle"));
    if (m.get("references") instanceof List) {
      for (Object o : (List<Object>) m.get("references")) {
        if (!(o instanceof Map)) continue;
        Map<String, Object> r = (Map<String, Object>) o;
        h.references.add(new DataPoint(-1, MiniJson.asLong(r.get("timestamp"), h.startTime),
            MiniJson.asLong(r.get("mb"), 0), 0, h.maxMB, DataPoint.Kind.REFERENCE,
            MiniJson.asString(r.get("label")), MiniJson.asString(r.get("location")),
            MiniJson.asString(r.get("sourceFile")), null, null, null, null));
      }
    }
    return h;
  }

  RunHeader copy() {
    RunHeader h = new RunHeader();
    h.runId = runId;
    h.profilerVersion = profilerVersion;
    h.intervalMs = intervalMs;
    h.maxMB = maxMB;
    h.pid = pid;
    h.processName = processName;
    h.jvmArgs = new ArrayList<String>(jvmArgs);
    h.startTime = startTime;
    h.title = title;
    h.subtitle = subtitle;
    h.references.addAll(references);
    return h;
  }
}
