package com.toro.al.memprofiler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

public class ProfilerServer {

  private final HttpServer server;
  private final java.util.concurrent.ExecutorService executor;
  private final DataStore store;
  private final AutoGcController autoGc;
  private final HeapDumper heapDumper;
  private final String[] sourceRoots;
  private final int port;
  private String cachedHtml;
  private volatile MemoryProfiler exports;
  private volatile SourceOpener sourceOpener = new SourceOpener(null, null);

  public ProfilerServer(int port, DataStore store) throws IOException {
    this(port, store, null, null, new String[0]);
  }

  public ProfilerServer(int port, DataStore store, AutoGcController autoGc) throws IOException {
    this(port, store, autoGc, null, new String[0]);
  }

  public ProfilerServer(int port, DataStore store, AutoGcController autoGc, String[] sourceRoots) throws IOException {
    this(port, store, autoGc, null, sourceRoots);
  }

  public ProfilerServer(int port, DataStore store, AutoGcController autoGc, HeapDumper heapDumper, String[] sourceRoots) throws IOException {
    this.port = port;
    this.store = store;
    this.autoGc = autoGc;
    this.heapDumper = heapDumper;
    this.sourceRoots = sourceRoots == null ? new String[0] : sourceRoots.clone();
    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    this.executor = Executors.newFixedThreadPool(4, new java.util.concurrent.ThreadFactory() {
      private int n;
      public synchronized Thread newThread(Runnable r) {
        return new Thread(r, "mem-profiler-http-" + (++n));
      }
    });
    this.server.setExecutor(executor);
    this.server.createContext("/", new Handler());
  }

  public void start() { server.start(); }

  /** Lets {@code /export/*} use the profiler's title, sampling interval and export options. */
  void setExports(MemoryProfiler profiler) { this.exports = profiler; }

  /** How "Open in IDE" opens a source file at its line (default: auto-detect IntelliJ / VS Code). */
  void setSourceOpener(SourceOpener opener) { if (opener != null) this.sourceOpener = opener; }

  /** Stops the server and its worker threads, so a JVM whose work is done can exit. */
  public void stop() {
    server.stop(0);
    executor.shutdownNow();
  }

  public int port() { return port; }

  private class Handler implements com.sun.net.httpserver.HttpHandler {
    public void handle(HttpExchange ex) throws IOException {
      try {
        String path = ex.getRequestURI().getPath();
        if ("/".equals(path) || "/index.html".equals(path) || "/viewer.html".equals(path)) {
          serveHtml(ex);
        } else if ("/layout.html".equals(path)) {
          serveResource(ex, "/layout.html", "text/html; charset=utf-8");
        } else if ("/data".equals(path)) {
          serveData(ex);
        } else if ("/data/since".equals(path)) {
          serveSince(ex);
        } else if ("/data/file".equals(path)) {
          serveFile(ex);
        } else if ("/open".equals(path)) {
          handleOpen(ex);
        } else if ("/gc".equals(path)) {
          handleForceGc(ex);
        } else if ("/mark".equals(path)) {
          handleMark(ex);
        } else if ("/autogc".equals(path)) {
          handleAutoGc(ex);
        } else if ("/heapdump".equals(path)) {
          handleHeapDump(ex);
        } else if ("/heapdumps".equals(path)) {
          handleHeapDumps(ex);
        } else if ("/heapdump/open".equals(path)) {
          handleHeapDumpOpen(ex);
        } else if ("/layout".equals(path)) {
          handleLayout(ex);
        } else if ("/layout/classes".equals(path)) {
          handleLayoutClasses(ex);
        } else if ("/layout/compile".equals(path)) {
          handleLayoutCompile(ex);
        } else if ("/class/source".equals(path)) {
          handleClassSource(ex);
        } else if ("/export/html".equals(path)) {
          handleExportHtml(ex);
        } else if ("/export/png".equals(path)) {
          handleExportPng(ex);
        } else if ("/source.html".equals(path)) {
          serveResource(ex, "/source.html", "text/html; charset=utf-8");
        } else if (path.startsWith("/web/")) {
          serveStatic(ex, path);
        } else {
          write(ex, 404, "text/plain", "Not found");
        }
      } catch (Throwable t) {
        write(ex, 500, "text/plain", "Error: " + t.getMessage());
      } finally {
        ex.close();
      }
    }
  }

  private synchronized String html() throws IOException {
    if (cachedHtml != null) return cachedHtml;
    InputStream in = ProfilerServer.class.getResourceAsStream("/viewer.html");
    if (in == null) throw new IOException("viewer.html not on classpath");
    StringBuilder sb = new StringBuilder();
    byte[] buf = new byte[4096];
    int n;
    while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
    in.close();
    cachedHtml = sb.toString();
    return cachedHtml;
  }

  private void serveHtml(HttpExchange ex) throws IOException {
    write(ex, 200, "text/html; charset=utf-8", html());
  }

  private void serveResource(HttpExchange ex, String resource, String contentType) throws IOException {
    InputStream in = ProfilerServer.class.getResourceAsStream(resource);
    if (in == null) {
      write(ex, 404, "text/plain", "not found");
      return;
    }
    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
    byte[] chunk = new byte[4096];
    int n;
    while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
    in.close();
    byte[] bytes = buf.toByteArray();
    ex.getResponseHeaders().set("Content-Type", contentType);
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    ex.sendResponseHeaders(200, bytes.length);
    OutputStream os = ex.getResponseBody();
    os.write(bytes);
    os.close();
  }

  private void serveStatic(HttpExchange ex, String path) throws IOException {
    if (path.contains("..")) {
      write(ex, 400, "text/plain", "bad path");
      return;
    }
    String resource = path.substring(1);
    InputStream in = ProfilerServer.class.getResourceAsStream("/" + resource);
    if (in == null) {
      write(ex, 404, "text/plain", "not found");
      return;
    }
    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
    byte[] chunk = new byte[4096];
    int n;
    while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
    in.close();
    byte[] bytes = buf.toByteArray();
    ex.getResponseHeaders().set("Content-Type", contentType(path));
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    ex.sendResponseHeaders(200, bytes.length);
    OutputStream os = ex.getResponseBody();
    os.write(bytes);
    os.close();
  }

  private static String contentType(String path) {
    if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
    if (path.endsWith(".css")) return "text/css; charset=utf-8";
    if (path.endsWith(".html")) return "text/html; charset=utf-8";
    if (path.endsWith(".json")) return "application/json; charset=utf-8";
    return "application/octet-stream";
  }

  private void serveData(HttpExchange ex) throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"maxObservedMB\":").append(store.maxObservedMB());
    sb.append(",\"jsonlPath\":");
    appendStringOrNull(sb, store.jsonlPath());
    sb.append(",\"pid\":").append(currentPid());
    sb.append(",\"processName\":");
    appendStringOrNull(sb, currentProcessName());
    sb.append(",\"intervalMs\":").append(configuredIntervalMs());
    sb.append(",\"version\":");
    appendStringOrNull(sb, MemoryProfiler.version());
    RunHeader h = store.header();
    sb.append(",\"runId\":");
    appendStringOrNull(sb, h.runId());
    appendReferences(sb);
    appendGaps(sb);
    sb.append(",\"points\":[");
    List<DataPoint> snap = store.snapshot();
    for (int i = 0; i < snap.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(snap.get(i).toJsonLine());
    }
    sb.append("]}");
    write(ex, 200, "application/json", sb.toString());
  }

  private void serveSince(HttpExchange ex) throws IOException {
    long after = parseLong(ex.getRequestURI().getQuery(), "index", -1);
    StringBuilder sb = new StringBuilder();
    sb.append("{\"maxObservedMB\":").append(store.maxObservedMB());
    appendReferences(sb);
    List<DataPoint> pts = store.since(after);
    if (!pts.isEmpty()) appendGaps(sb);
    sb.append(",\"points\":[");
    for (int i = 0; i < pts.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(pts.get(i).toJsonLine());
    }
    sb.append("]}");
    write(ex, 200, "application/json", sb.toString());
  }

  private void appendReferences(StringBuilder sb) {
    sb.append(",\"references\":[");
    List<DataPoint> refs = store.references();
    for (int i = 0; i < refs.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(refs.get(i).toJsonLine());
    }
    sb.append(']');
  }

  private long configuredIntervalMs() {
    MemoryProfiler p = exports;
    return p != null ? p.config().intervalMs : store.header().intervalMs();
  }

  /**
   * The gap rule over the in-memory points ({@link GapRule}), so the live chart
   * breaks, dashes and connects the line exactly as the exports do.
   */
  private void appendGaps(StringBuilder sb) {
    ExportMeta m = new ExportMeta();
    m.intervalMs = configuredIntervalMs();
    ProfileStats stats;
    try {
      stats = ProfileStats.scan(new PointSource.Store(store, m));
    } catch (IOException e) {
      return;
    }
    stats.applyTo(m);
    GapRule.Result gaps = stats.gaps(m.intervalMs);
    sb.append(",\"samplingText\":");
    appendStringOrNull(sb, m.samplingText());
    sb.append(",\"gaps\":");
    gaps.appendJson(sb);
    HtmlExporter.appendAnalysis(sb, stats, gaps, GcPressure.DEFAULT_THRESHOLD_PCT, null);
  }

  private PointSource exportSource() {
    MemoryProfiler p = exports;
    if (p != null) return p.pointSource();
    ExportMeta m = new ExportMeta();
    m.processName = currentProcessName();
    m.pid = currentPid();
    return new PointSource.Store(store, m);
  }

  private void handleExportHtml(HttpExchange ex) throws IOException {
    HtmlExporter.Options opts;
    if (exports != null) {
      opts = exports.htmlOptions();
    } else {
      opts = new HtmlExporter.Options();
      opts.sourceRoots = sourceRoots.clone();
    }
    String name = "profile-" + ExportMeta.fileSafeProcess(currentProcessName()) + "-"
        + ExportMeta.fileStamp(System.currentTimeMillis()) + ".html";
    HtmlExporter.importMap(); // fail before the headers go out if a resource is missing
    ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + "\"");
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    ex.sendResponseHeaders(200, 0);
    OutputStream os = ex.getResponseBody();
    try {
      HtmlExporter.export(exportSource(), opts, os);
    } finally {
      os.close();
    }
  }

  private void handleExportPng(HttpExchange ex) throws IOException {
    String q = ex.getRequestURI().getQuery();
    PngOptions opts = PngOptions.defaults();
    String[] keys = {"width", "height", "scale", "from", "to", "fromMark", "toMark", "theme",
        "includeTable", "title", "clock", "committed", "gcTicks", "segments", "callouts", "gcOverhead",
        "shadeForcedGc", "marksOnlyTable", "tableColumns", "locationWidth", "formatLabelNumbers"};
    for (String k : keys) {
      String v = parseString(q, k);
      if (v != null && !opts.set(k, v)) {
        write(ex, 400, "text/plain", "bad value for " + k + ": " + v);
        return;
      }
    }
    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(512 * 1024);
    try {
      PngRenderer.render(exportSource(), opts, buf);
    } catch (IllegalArgumentException e) {
      write(ex, 400, "text/plain", e.getMessage());
      return;
    }
    byte[] bytes = buf.toByteArray();
    boolean download = "true".equalsIgnoreCase(parseString(q, "download"));
    String name = "profile-" + ExportMeta.fileSafeProcess(currentProcessName()) + "-"
        + ExportMeta.fileStamp(System.currentTimeMillis()) + ".png";
    ex.getResponseHeaders().set("Content-Type", "image/png");
    ex.getResponseHeaders().set("Content-Disposition",
        (download ? "attachment" : "inline") + "; filename=\"" + name + "\"");
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    ex.sendResponseHeaders(200, bytes.length);
    OutputStream os = ex.getResponseBody();
    os.write(bytes);
    os.close();
  }

  private void serveFile(HttpExchange ex) throws IOException {
    String path = store.jsonlPath();
    if (path == null) {
      write(ex, 404, "text/plain", "JSONL not enabled");
      return;
    }
    File f = new File(path);
    if (!f.exists()) {
      write(ex, 404, "text/plain", "JSONL file missing");
      return;
    }
    StringBuilder sb = new StringBuilder((int) Math.min(f.length(), 1 << 20));
    BufferedReader r = new BufferedReader(new FileReader(f));
    String line;
    while ((line = r.readLine()) != null) sb.append(line).append('\n');
    r.close();
    write(ex, 200, "application/jsonl", sb.toString());
  }

  private void handleOpen(HttpExchange ex) throws IOException {
    String q = ex.getRequestURI().getQuery();
    String path = parseString(q, "path");
    if (path == null) {
      write(ex, 400, "text/plain", "missing path");
      return;
    }

    int colon = path.lastIndexOf(':');
    String bare = path;
    int line = 0;
    if (colon > 0) {
      String tail = path.substring(colon + 1);
      boolean allDigits = !tail.isEmpty() && tail.length() < 10;
      for (int i = 0; i < tail.length(); i++) {
        if (!Character.isDigit(tail.charAt(i))) { allDigits = false; break; }
      }
      if (allDigits) {
        bare = path.substring(0, colon);
        line = Integer.parseInt(tail);
      }
    }

    File f = new File(bare);
    if (!f.isAbsolute() || !f.exists()) {
      File resolved = resolveSource(bare);
      if (resolved != null) f = resolved;
    }
    if (!f.exists()) {
      String msg = "file not found: " + bare;
      if (sourceRoots.length == 0) {
        msg += " (configure sourceRoots in MemoryProfiler.start to resolve class locations)";
      } else {
        StringBuilder sb = new StringBuilder(msg).append(" (searched: ");
        for (int i = 0; i < sourceRoots.length; i++) {
          if (i > 0) sb.append(", ");
          sb.append(sourceRoots[i]);
        }
        msg = sb.append(')').toString();
      }
      write(ex, 404, "text/plain", msg);
      return;
    }
    try {
      if (line > 0 || f.getName().endsWith(".java")) {
        // a mark's source location: the IDE, at the line
        write(ex, 200, "text/plain", sourceOpener.open(f, line));
      } else {
        Headless.markAwtUsed();
        Desktop.getDesktop().open(f);
        write(ex, 200, "text/plain", "ok");
      }
    } catch (Throwable t) {
      write(ex, 500, "text/plain", t.getMessage() != null ? t.getMessage() : t.toString());
    }
  }

  private File resolveSource(String relative) {
    if (relative == null || relative.isEmpty()) return null;
    String norm = relative.replace('\\', '/');
    if (norm.startsWith("/")) norm = norm.substring(1);
    for (String root : sourceRoots) {
      File candidate = new File(root, norm);
      if (candidate.isFile()) return candidate;
    }
    return null;
  }

  private void handleForceGc(HttpExchange ex) throws IOException {
    for (int i = 0; i < 3; i++) {
      System.gc();
      try { Thread.sleep(60); } catch (InterruptedException e) { break; }
    }
    write(ex, 200, "text/plain", "ok");
  }

  private void handleAutoGc(HttpExchange ex) throws IOException {
    if (autoGc == null) {
      write(ex, 503, "text/plain", "autoGc not available");
      return;
    }
    String q = ex.getRequestURI().getQuery();
    String nearPeak = parseString(q, "nearPeak");
    if (nearPeak != null) autoGc.setNearPeakEnabled("true".equalsIgnoreCase(nearPeak));
    String threshold = parseString(q, "threshold");
    if (threshold != null) autoGc.setThresholdEnabled("true".equalsIgnoreCase(threshold));
    long thresholdMB = parseLong(q, "thresholdMB", Long.MIN_VALUE);
    if (thresholdMB != Long.MIN_VALUE && thresholdMB >= 0) autoGc.setThresholdMB(thresholdMB);

    StringBuilder sb = new StringBuilder(128);
    sb.append('{')
      .append("\"nearPeak\":").append(autoGc.nearPeakEnabled())
      .append(",\"threshold\":").append(autoGc.thresholdEnabled())
      .append(",\"thresholdMB\":").append(autoGc.thresholdMB())
      .append(",\"nearPeakRatio\":").append(autoGc.nearPeakRatio())
      .append(",\"cooldownMs\":").append(autoGc.cooldownMs())
      .append('}');
    write(ex, 200, "application/json", sb.toString());
  }

  private void handleMark(HttpExchange ex) throws IOException {
    String q = ex.getRequestURI().getQuery();
    String label = parseString(q, "label");
    if (label == null) label = "manual";
    String gc = parseString(q, "gc");
    if ("true".equalsIgnoreCase(gc)) MemoryProfiler.markGc(label);
    else MemoryProfiler.mark(label);
    write(ex, 200, "text/plain", "ok");
  }

  private void handleHeapDump(HttpExchange ex) throws IOException {
    if (heapDumper == null) {
      write(ex, 503, "text/plain", "heap dumper not available");
      return;
    }
    String q = ex.getRequestURI().getQuery();
    String name = parseString(q, "name");
    if (name == null || name.isEmpty()) name = "dump";
    String path = MemoryProfiler.heapDump(name);
    if (path == null) {
      write(ex, 500, "text/plain", "heap dump failed");
      return;
    }
    // Find the dump info from the list
    java.util.List<HeapDumper.DumpInfo> list = heapDumper.list();
    HeapDumper.DumpInfo info = list.get(list.size() - 1);
    write(ex, 200, "application/json", info.toJson());
  }

  private void handleHeapDumps(HttpExchange ex) throws IOException {
    if (heapDumper == null) {
      write(ex, 200, "application/json", "{\"dumps\":[],\"opener\":\"system\",\"openerPath\":null}");
      return;
    }
    java.util.List<HeapDumper.DumpInfo> list = heapDumper.list();
    StringBuilder sb = new StringBuilder(256);
    sb.append("{\"dumps\":[");
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(list.get(i).toJson());
    }
    sb.append("],\"opener\":\"").append(heapDumper.opener().name().toLowerCase()).append('"');
    sb.append(",\"openerPath\":");
    appendStringOrNull(sb, heapDumper.openerPath());
    sb.append('}');
    write(ex, 200, "application/json", sb.toString());
  }

  private void handleHeapDumpOpen(HttpExchange ex) throws IOException {
    if (heapDumper == null) {
      write(ex, 503, "text/plain", "heap dumper not available");
      return;
    }
    String q = ex.getRequestURI().getQuery();
    String path = parseString(q, "path");
    if (path == null || path.isEmpty()) {
      write(ex, 400, "text/plain", "missing path");
      return;
    }
    heapDumper.open(path);
    write(ex, 200, "text/plain", "ok");
  }

  private void handleLayout(HttpExchange ex) throws IOException {
    String q = ex.getRequestURI().getQuery();
    String className = parseString(q, "class");
    if (className == null || className.isEmpty()) {
      write(ex, 400, "application/json", "{\"error\":\"missing 'class' parameter\"}");
      return;
    }
    try {
      ObjectLayoutAnalyzer.LayoutResult result = ObjectLayoutAnalyzer.analyze(className);
      write(ex, 200, "application/json", result.toJson());
    } catch (ClassNotFoundException e) {
      write(ex, 404, "application/json", "{\"error\":\"class not found: " +
        className.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
    } catch (Throwable t) {
      write(ex, 500, "application/json", "{\"error\":\"" +
        (t.getMessage() != null ? t.getMessage().replace("\\", "\\\\").replace("\"", "\\\"") : "unknown error") + "\"}");
    }
  }

  private void handleClassSource(HttpExchange ex) throws IOException {
    String q = ex.getRequestURI().getQuery();
    String className = parseString(q, "class");
    if (className == null || className.isEmpty()) {
      write(ex, 400, "application/json", "{\"error\":\"missing 'class' parameter\"}");
      return;
    }
    String file = parseString(q, "file");
    long line = parseLong(q, "line", 0);
    try {
      ClassSourceService.SourceResult result = ClassSourceService.getSource(className, file,
          (int) Math.max(0, Math.min(Integer.MAX_VALUE, line)), sourceRoots);
      write(ex, 200, "application/json", result.toJson());
    } catch (ClassNotFoundException e) {
      write(ex, 404, "application/json", "{\"error\":\"class not found: " +
        className.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
    } catch (Throwable t) {
      String msg = t.getMessage() != null ? t.getMessage() : "unknown error";
      write(ex, 500, "application/json", "{\"error\":\"" +
        msg.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}");
    }
  }

  private void handleLayoutClasses(HttpExchange ex) throws IOException {
    String q = ex.getRequestURI().getQuery();
    String filter = parseString(q, "filter");
    java.lang.instrument.Instrumentation inst = ProfilerAgent.instrumentation();
    if (inst == null) {
      // Attempt self-attach
      SelfAttach.tryAttach();
      inst = ProfilerAgent.instrumentation();
    }
    if (inst == null) {
      write(ex, 503, "application/json",
        "{\"error\":\"Instrumentation not available. Run with -javaagent or add -Djdk.attach.allowAttachSelf=true to VM options.\"}");
      return;
    }
    Class<?>[] loaded = inst.getAllLoadedClasses();
    StringBuilder sb = new StringBuilder(loaded.length * 40);
    sb.append('[');
    boolean first = true;
    String filterLower = (filter != null && !filter.isEmpty()) ? filter.toLowerCase() : null;
    for (Class<?> c : loaded) {
      if (c.isArray() || c.isPrimitive() || c.isSynthetic()) continue;
      String name = c.getName();
      // Skip internal/anonymous classes
      if (name.contains("$$") || name.contains("$Lambda")) continue;
      if (filterLower != null && !name.toLowerCase().contains(filterLower)) continue;
      if (!first) sb.append(',');
      sb.append('"').append(jsonEscape(name)).append('"');
      first = false;
    }
    sb.append(']');
    write(ex, 200, "application/json", sb.toString());
  }

  private static String jsonEscape(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private void handleLayoutCompile(HttpExchange ex) throws IOException {
    // Read POST body as source code
    InputStream bodyIn = ex.getRequestBody();
    java.io.ByteArrayOutputStream bodyBuf = new java.io.ByteArrayOutputStream();
    byte[] chunk = new byte[4096];
    int n;
    while ((n = bodyIn.read(chunk)) > 0) bodyBuf.write(chunk, 0, n);
    bodyIn.close();
    String source = bodyBuf.toString("UTF-8");

    if (source == null || source.trim().isEmpty()) {
      write(ex, 400, "application/json", "{\"error\":\"empty source code\"}");
      return;
    }

    // Wrap bare field definitions in a class if no class declaration is present
    String trimmed = source.trim();
    if (!trimmed.contains("class ")) {
      source = "public class _Layout {\n" + source + "\n}";
    }

    try {
      Class<?> clazz = InMemoryCompiler.compile(source);
      ObjectLayoutAnalyzer.LayoutResult result = ObjectLayoutAnalyzer.analyze(clazz);
      write(ex, 200, "application/json", result.toJson());
    } catch (InMemoryCompiler.CompilationException e) {
      String msg = e.getMessage() != null ? e.getMessage() : "compilation failed";
      write(ex, 400, "application/json", "{\"error\":\"" +
        msg.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}");
    } catch (Throwable t) {
      write(ex, 500, "application/json", "{\"error\":\"" +
        (t.getMessage() != null ? t.getMessage().replace("\\", "\\\\").replace("\"", "\\\"") : "unknown error") + "\"}");
    }
  }

  private static void write(HttpExchange ex, int code, String contentType, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", contentType);
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    ex.sendResponseHeaders(code, bytes.length);
    OutputStream os = ex.getResponseBody();
    os.write(bytes);
    os.close();
  }

  private static void appendStringOrNull(StringBuilder sb, String s) {
    if (s == null) { sb.append("null"); return; }
    sb.append('"');
    sb.append(s.replace("\\", "\\\\").replace("\"", "\\\""));
    sb.append('"');
  }

  private static long parseLong(String query, String key, long dflt) {
    String v = parseString(query, key);
    if (v == null) return dflt;
    try { return Long.parseLong(v); } catch (NumberFormatException e) { return dflt; }
  }

  static long currentPid() {
    try {
      String name = ManagementFactory.getRuntimeMXBean().getName();
      int at = name.indexOf('@');
      return Long.parseLong(at > 0 ? name.substring(0, at) : name);
    } catch (Throwable t) {
      return -1;
    }
  }

  static String currentProcessName() {
    String cmd = System.getProperty("sun.java.command");
    if (cmd == null || cmd.isEmpty()) return null;
    int sp = cmd.indexOf(' ');
    return sp > 0 ? cmd.substring(0, sp) : cmd;
  }

  private static String parseString(String query, String key) {
    if (query == null) return null;
    for (String part : query.split("&")) {
      int eq = part.indexOf('=');
      if (eq <= 0) continue;
      String k = part.substring(0, eq);
      if (!k.equals(key)) continue;
      try {
        return URLDecoder.decode(part.substring(eq + 1), "UTF-8");
      } catch (Exception e) {
        return null;
      }
    }
    return null;
  }
}
