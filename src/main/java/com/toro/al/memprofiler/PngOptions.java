package com.toro.al.memprofiler;

/**
 * Options for {@link MemoryProfiler#exportPng(String, PngOptions)}, the
 * {@code /export/png} endpoint and the {@code export --png} CLI.
 *
 * <pre>
 *   MemoryProfiler.exportPng("heap.png", PngOptions.defaults()
 *       .fromMark("5").toMark("8")
 *       .includeTable(true)
 *       .theme("dark"));
 * </pre>
 */
public final class PngOptions {

  int width = 1400;
  int height = 640;
  double scale = 2;
  Long from;
  Long to;
  String fromMark;
  String toMark;
  String theme = "light";
  boolean includeTable = false;
  String title;
  boolean clockAxis = false;
  boolean committed = false;
  boolean gcTicks = true;

  public static PngOptions defaults() {
    return new PngOptions();
  }

  /** Image width in logical pixels (default 1400). */
  public PngOptions width(int v) { this.width = Math.max(480, Math.min(8000, v)); return this; }

  /** Plot height in logical pixels (default 640); title, axis, key and table are added to it. */
  public PngOptions height(int v) { this.height = Math.max(160, Math.min(6000, v)); return this; }

  /** Pixel density (default 2 for HiDPI); the PNG is {@code width × scale} pixels wide. */
  public PngOptions scale(double v) { this.scale = Math.max(0.5, Math.min(4, v)); return this; }

  /**
   * Start of the time range: epoch milliseconds (as in the JSONL {@code timestamp}),
   * or, for values below 10^11, milliseconds elapsed since the first point.
   */
  public PngOptions from(long ms) { this.from = Long.valueOf(ms); return this; }

  /** End of the time range; same units as {@link #from(long)}. */
  public PngOptions to(long ms) { this.to = Long.valueOf(ms); return this; }

  /** Start at the first mark whose badge is {@code number} (for example "5" for {@code "5. Load start"}). */
  public PngOptions fromMark(String number) { this.fromMark = number; return this; }

  /** End at the last mark whose badge is {@code number}. */
  public PngOptions toMark(String number) { this.toMark = number; return this; }

  /** {@code "light"} (default) or {@code "dark"}. */
  public PngOptions theme(String v) { this.theme = "dark".equalsIgnoreCase(v) ? "dark" : "light"; return this; }

  /** Draw the marks table under the chart (default false). */
  public PngOptions includeTable(boolean v) { this.includeTable = v; return this; }

  /** Chart title (default: the profiler title, or the process name). */
  public PngOptions title(String v) { this.title = v; return this; }

  /** Label the x axis with wall-clock time (HH:mm:ss) instead of elapsed m:ss. */
  public PngOptions clockAxis(boolean v) { this.clockAxis = v; return this; }

  /** Also draw the committed heap line (default false). */
  public PngOptions committed(boolean v) { this.committed = v; return this; }

  /** Draw GC events as ticks under the x axis (default true). */
  public PngOptions gcTicks(boolean v) { this.gcTicks = v; return this; }

  public int width() { return width; }
  public int height() { return height; }
  public double scale() { return scale; }
  public boolean isDark() { return "dark".equals(theme); }

  PngOptions copy() {
    PngOptions o = new PngOptions();
    o.width = width;
    o.height = height;
    o.scale = scale;
    o.from = from;
    o.to = to;
    o.fromMark = fromMark;
    o.toMark = toMark;
    o.theme = theme;
    o.includeTable = includeTable;
    o.title = title;
    o.clockAxis = clockAxis;
    o.committed = committed;
    o.gcTicks = gcTicks;
    return o;
  }

  /**
   * Applies one option by name, as used by the HTTP endpoint, the agent and the CLI.
   * Unknown names are ignored; returns false if the value does not parse.
   */
  boolean set(String key, String value) {
    if (value == null) return true;
    String v = value.trim();
    try {
      if ("width".equals(key)) width(Integer.parseInt(v));
      else if ("height".equals(key)) height(Integer.parseInt(v));
      else if ("scale".equals(key)) scale(Double.parseDouble(v));
      else if ("from".equals(key)) { if (!v.isEmpty()) from(Math.round(Double.parseDouble(v))); }
      else if ("to".equals(key)) { if (!v.isEmpty()) to(Math.round(Double.parseDouble(v))); }
      else if ("fromMark".equals(key)) fromMark(v.isEmpty() ? null : v);
      else if ("toMark".equals(key)) toMark(v.isEmpty() ? null : v);
      else if ("theme".equals(key)) theme(v);
      else if ("includeTable".equals(key)) includeTable(bool(v));
      else if ("title".equals(key)) title(value.isEmpty() ? null : value);
      else if ("clock".equals(key) || "clockAxis".equals(key)) clockAxis(bool(v));
      else if ("committed".equals(key)) committed(bool(v));
      else if ("gcTicks".equals(key)) gcTicks(bool(v));
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static boolean bool(String v) {
    return v.isEmpty() || "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
  }
}
