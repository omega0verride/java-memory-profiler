package com.toro.al.memprofiler;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Descriptive data for an export: who produced the profile and how. Instances
 * are copied before the exporters fill in the numbers they compute.
 */
public final class ExportMeta {

  /** Chart title. {@code null} = derived from the process name. */
  public String title;
  /** Chart subtitle. {@code null} = derived (process, PID, start time). */
  public String subtitle;
  public String processName;
  public long pid = -1;
  /** Where the data came from, e.g. "live" or "run.jsonl (run 2 of 3)". */
  public String source = "live";
  /** Configured sampling interval (live config or the JSONL META line), 0 when unknown. */
  public long intervalMs;
  /** The run id from the JSONL META line, if any. */
  public String runId;
  /** Profiler version that recorded the data (JSONL META), if known. */
  public String recordedVersion;
  public String profilerVersion = MemoryProfiler.version();
  /** Export time (epoch ms). The only field that changes between two exports of the same data. */
  public long exportedAt;

  // ---- filled in from the data by the exporters ----
  public long startTime;
  public long endTime;
  public long jvmMaxHeapMB = -1;
  public long peakMB;
  /** Highest reading among marks and scope ends (-1 if none). */
  public long peakRetainedMB = -1;
  /** Median gap between samples: the effective sampling interval. */
  public long medianSampleGapMs;
  public long gcTotalMs;
  public long longestGcMs;
  /** Viewer option: thousands separators for numbers inside mark labels. */
  public boolean formatLabelNumbers;
  public long pointCount;
  public long sampleCount;
  public long exportedSampleCount;

  public ExportMeta copy() {
    ExportMeta m = new ExportMeta();
    m.title = title;
    m.subtitle = subtitle;
    m.processName = processName;
    m.pid = pid;
    m.source = source;
    m.intervalMs = intervalMs;
    m.runId = runId;
    m.recordedVersion = recordedVersion;
    m.peakRetainedMB = peakRetainedMB;
    m.medianSampleGapMs = medianSampleGapMs;
    m.gcTotalMs = gcTotalMs;
    m.longestGcMs = longestGcMs;
    m.formatLabelNumbers = formatLabelNumbers;
    m.profilerVersion = profilerVersion;
    m.exportedAt = exportedAt;
    m.startTime = startTime;
    m.endTime = endTime;
    m.jvmMaxHeapMB = jvmMaxHeapMB;
    m.peakMB = peakMB;
    m.pointCount = pointCount;
    m.sampleCount = sampleCount;
    m.exportedSampleCount = exportedSampleCount;
    return m;
  }

  /** The title to show: the explicit one, or the process name. */
  public String effectiveTitle() {
    if (title != null && title.trim().length() > 0) return title;
    if (processName != null && processName.length() > 0) return "Heap profile: " + simpleName(processName);
    return "Heap profile";
  }

  /** The subtitle to show: the explicit one, or process name, PID and start time. */
  public String effectiveSubtitle() {
    if (subtitle != null) return subtitle;
    StringBuilder sb = new StringBuilder();
    if (processName != null && processName.length() > 0) sb.append(processName);
    if (pid >= 0) {
      if (sb.length() > 0) sb.append(" · ");
      sb.append("PID ").append(pid);
    }
    if (startTime > 0) {
      if (sb.length() > 0) sb.append(" · ");
      sb.append("started ").append(formatDateTime(startTime));
    }
    if (source != null && !"live".equals(source)) {
      if (sb.length() > 0) sb.append(" · ");
      sb.append(source);
    }
    return sb.toString();
  }

  /**
   * How the line was sampled, for the PNG and HTML keys: the configured interval,
   * plus the effective median when the two differ a lot (a GC-bound run), or the
   * median when the configured interval is not recorded (files from 1.0.4).
   */
  public String samplingText() {
    long med = medianSampleGapMs;
    if (intervalMs > 0) {
      StringBuilder sb = new StringBuilder("heap sampled every ").append(intervalMs).append(" ms");
      if (med > 0 && (med * 2 > intervalMs * 3 || med * 3 < intervalMs * 2)) {
        sb.append(" (effective median ").append(med).append(" ms");
        if (med > intervalMs) sb.append(", sampler delayed by GC");
        sb.append(')');
      }
      return sb.toString();
    }
    if (med > 0) return "heap sampled about every " + med + " ms (median; interval not recorded)";
    return "sampled heap";
  }

  String toJson() {
    StringBuilder sb = new StringBuilder(512);
    sb.append('{');
    sb.append("\"title\":"); MiniJson.quote(sb, effectiveTitle());
    // the explicit subtitle only; the viewer shows process, PID and start time as separate chips
    sb.append(",\"subtitle\":"); MiniJson.quote(sb, subtitle);
    sb.append(",\"processName\":"); MiniJson.quote(sb, processName);
    sb.append(",\"pid\":").append(pid);
    sb.append(",\"source\":"); MiniJson.quote(sb, source);
    sb.append(",\"intervalMs\":").append(intervalMs);
    sb.append(",\"medianSampleGapMs\":").append(medianSampleGapMs);
    sb.append(",\"samplingText\":"); MiniJson.quote(sb, samplingText());
    sb.append(",\"runId\":"); MiniJson.quote(sb, runId);
    sb.append(",\"profilerVersion\":"); MiniJson.quote(sb, profilerVersion);
    sb.append(",\"recordedVersion\":"); MiniJson.quote(sb, recordedVersion);
    sb.append(",\"startTime\":").append(startTime);
    sb.append(",\"endTime\":").append(endTime);
    sb.append(",\"durationMs\":").append(Math.max(0, endTime - startTime));
    sb.append(",\"jvmMaxHeapMB\":").append(jvmMaxHeapMB);
    sb.append(",\"peakMB\":").append(peakMB);
    sb.append(",\"peakRetainedMB\":").append(peakRetainedMB);
    sb.append(",\"gcTotalMs\":").append(gcTotalMs);
    sb.append(",\"longestGcMs\":").append(longestGcMs);
    sb.append(",\"formatLabelNumbers\":").append(formatLabelNumbers);
    sb.append(",\"pointCount\":").append(pointCount);
    sb.append(",\"sampleCount\":").append(sampleCount);
    sb.append(",\"exportedSampleCount\":").append(exportedSampleCount);
    // Keep exportedAt last: it is the only value that differs between two exports.
    sb.append(",\"exportedAt\":").append(exportedAt);
    sb.append('}');
    return sb.toString();
  }

  static String simpleName(String processName) {
    if (processName == null) return "";
    String p = processName.replace('\\', '/');
    int slash = p.lastIndexOf('/');
    if (slash >= 0) p = p.substring(slash + 1);
    if (p.endsWith(".jar")) return p.substring(0, p.length() - 4);
    int dot = p.lastIndexOf('.');
    return dot >= 0 && dot < p.length() - 1 ? p.substring(dot + 1) : p;
  }

  static String formatDateTime(long ts) {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    return f.format(new Date(ts));
  }

  /** {@code yyyyMMdd-HHmmss} in local time, for file names. */
  static String fileStamp(long ts) {
    SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT);
    return f.format(new Date(ts));
  }

  /** A file-name-safe version of the process name ("profile-&lt;this&gt;-&lt;stamp&gt;.html"). */
  static String fileSafeProcess(String processName) {
    String s = simpleName(processName);
    if (s.length() == 0) s = "jvm";
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' ? c : '_');
    }
    return sb.toString();
  }
}
