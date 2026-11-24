package com.toro.al.memprofiler;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes a single self-contained HTML file that runs the live viewer in
 * snapshot mode, offline (no server, no network).
 *
 * <h3>How the viewer is inlined</h3>
 * The live UI loads {@code web/*.js} as separate ES modules and Vue from unpkg.
 * Modules cannot import each other from {@code file://}, so at export time each
 * module reachable from {@code web/app.js} is read from the classpath, its
 * relative imports ({@code './state.js'}) are rewritten to bare specifiers
 * ({@code '@memprofiler/web/state.js'}), and it is stored as a
 * {@code data:text/javascript;base64} URL in an import map. {@code vue} maps to
 * the vendored {@code web/vendor/vue.esm-browser.prod.js} the same way. The
 * live UI is unchanged: it still loads the module files and the unpkg import map.
 *
 * <h3>Data</h3>
 * The profile is a JSON object {@code {points, references, gaps, sources, meta}} in
 * {@code <script type="application/json" id="profile-data">}. Every string in it
 * has {@code < > &} written as {@code \\uXXXX}, so no label can close the
 * element. A small classic script parses it into {@code window.__PROFILE__}
 * before the modules run. SAMPLE points beyond {@link Options#maxSamples} are
 * reduced by {@link Downsampler} (min/max per bucket); every other point is kept.
 */
public final class HtmlExporter {

  public static final class Options {
    /** Keep at most this many SAMPLE points (min/max downsampling above it). */
    public int maxSamples = 20000;
    /** Roots used to resolve {@code sourceFile} of marks for embedded source snippets. */
    public String[] sourceRoots = new String[0];
    /** Lines of source embedded above and below a mark's line (0 = no snippets). */
    public int sourceContextLines = 6;
    /** Export timestamp; null = now. Fixed in tests. */
    public Long exportedAt;
    /** Thousands separators for numbers inside mark labels in the marks table. */
    public boolean formatLabelNumbers;
    /** Segments between marks ({@code "1:2,2:4"}) shown as brackets; automatic callouts are always added. */
    public String segments;
    /** GC overhead shading threshold (percent of wall time, 0 = off). */
    public int gcOverheadPct = GcPressure.DEFAULT_THRESHOLD_PCT;
  }

  static final String VUE_RESOURCE = "web/vendor/vue.esm-browser.prod.js";
  static final String ENTRY_MODULE = "web/app.js";
  static final String BARE_PREFIX = "@memprofiler/";

  private static final Pattern IMPORT_SPECIFIER =
      Pattern.compile("(\\bfrom\\s*|\\bimport\\s*\\(?\\s*)(['\"])(\\.{1,2}/[^'\"]+)\\2");

  static final String VIEWER_PAGE = "viewer.html";
  private static final java.util.concurrent.ConcurrentHashMap<String, String> PAGE_CACHE =
      new java.util.concurrent.ConcurrentHashMap<String, String>();
  private static final java.util.concurrent.ConcurrentHashMap<String, String> IMPORT_MAP_CACHE =
      new java.util.concurrent.ConcurrentHashMap<String, String>();
  private static final Pattern IMPORTS_VUE = Pattern.compile("\\bfrom\\s*['\"]vue['\"]");

  private HtmlExporter() {}

  /** Writes the export to {@code file} (parent directories are created). */
  public static File export(PointSource src, Options opts, File file) throws IOException {
    File parent = file.getAbsoluteFile().getParentFile();
    if (parent != null) parent.mkdirs();
    OutputStream out = new FileOutputStream(file);
    try {
      export(src, opts, out);
    } finally {
      out.close();
    }
    return file;
  }

  /** Streams the export to {@code out} (UTF-8). Does not close {@code out}. */
  public static void export(PointSource src, Options opts, OutputStream out) throws IOException {
    if (opts == null) opts = new Options();
    ProfileStats stats = ProfileStats.scan(src);
    ExportMeta meta = src.meta().copy();
    stats.applyTo(meta);
    meta.exportedAt = opts.exportedAt != null ? opts.exportedAt.longValue() : System.currentTimeMillis();
    if (opts.formatLabelNumbers) meta.formatLabelNumbers = true;
    GapRule.Result gaps = stats.gaps(meta.intervalMs);

    String[] template = template();
    Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 1 << 16);
    w.write(template[0].replace("@@TITLE@@", escapeHtml(meta.effectiveTitle() + " — memory profile")));
    w.write("<script type=\"application/json\" id=\"profile-data\">");
    writeData(w, src, stats, gaps, meta, opts);
    w.write("</script>\n");
    w.write("<script>window.__PROFILE__ = JSON.parse(document.getElementById('profile-data').textContent);</script>\n");
    w.write(template[1]);
    w.flush();
  }

  // ---- data ----

  private static void writeData(final Writer w, PointSource src, ProfileStats stats, GapRule.Result gaps,
                                ExportMeta meta, Options opts) throws IOException {
    w.write("{\"points\":[");
    final StringBuilder sb = new StringBuilder(256);
    final boolean[] first = {true};
    final IOException[] failure = {null};
    Downsampler ds = new Downsampler(stats.sampleCount, opts.maxSamples, stats.lastIndex, new Downsampler.Sink() {
      public void accept(DataPoint p) {
        if (failure[0] != null) return;
        sb.setLength(0);
        if (!first[0]) sb.append(',');
        first[0] = false;
        sb.append('\n');
        appendPoint(sb, p);
        try {
          w.write(sb.toString());
        } catch (IOException e) {
          failure[0] = e;
        }
      }
    });
    src.forEach(ds);
    ds.finish();
    if (failure[0] != null) throw failure[0];
    meta.exportedSampleCount = ds.emittedSamples();

    sb.setLength(0);
    sb.append("\n],\"references\":[");
    List<DataPoint> refs = src.references();
    for (int i = 0; i < refs.size(); i++) {
      if (i > 0) sb.append(',');
      appendPoint(sb, refs.get(i));
    }
    sb.append("],\"gaps\":");
    gaps.appendJson(sb);
    appendAnalysis(sb, stats, gaps, opts.gcOverheadPct, opts.segments);
    sb.append(",\"sources\":");
    appendSources(sb, stats.annotated, opts);
    sb.append(",\"meta\":").append(meta.toJson());
    sb.append('}');
    w.write(sb.toString());
  }

  /**
   * {@code ,"gcPressure":{…},"segments":[…]}: GC overhead regions and the
   * segment brackets (user segments plus the largest increase and release), for
   * the viewer. Shared with the live {@code /data}.
   */
  static void appendAnalysis(StringBuilder sb, ProfileStats stats, GapRule.Result gaps, int gcPct, String segments) {
    sb.append(",\"gcPressure\":");
    GcPressure.of(stats, gaps, gcPct).appendJson(sb);
    List<Annotations.Row> rows = Annotations.rows(stats.annotated, stats.firstTs);
    GapRule.GcTime gc = stats.gapInput.gcTime();
    List<Segments.Segment> segs = new ArrayList<Segments.Segment>();
    try {
      segs.addAll(Segments.parse(segments, rows, gc));
    } catch (IllegalArgumentException ignored) {
      // a segment whose marks are not (yet) there: skip it
    }
    segs.addAll(Segments.callouts(rows, gc));
    sb.append(",\"segments\":");
    Segments.appendJson(sb, segs);
  }

  /** Same keys as {@link DataPoint#toJsonLine()}, with HTML-safe string escaping. */
  static void appendPoint(StringBuilder sb, DataPoint p) {
    sb.append("{\"index\":").append(p.index);
    sb.append(",\"timestamp\":").append(p.timestamp);
    sb.append(",\"usedMB\":").append(p.usedMB);
    sb.append(",\"committedMB\":").append(p.committedMB);
    sb.append(",\"maxMB\":").append(p.maxMB);
    sb.append(",\"kind\":\"").append(p.kind.name()).append('"');
    if (p.label != null) { sb.append(",\"label\":"); MiniJson.quote(sb, p.label); }
    if (p.location != null) { sb.append(",\"location\":"); MiniJson.quote(sb, p.location); }
    if (p.sourceFile != null) { sb.append(",\"sourceFile\":"); MiniJson.quote(sb, p.sourceFile); }
    if (p.scopeId != null) { sb.append(",\"scopeId\":"); MiniJson.quote(sb, p.scopeId); }
    if (p.deltaMB != null) sb.append(",\"deltaMB\":").append(p.deltaMB);
    if (p.durationMs != null) sb.append(",\"durationMs\":").append(p.durationMs);
    if (p.threadName != null) { sb.append(",\"thread\":"); MiniJson.quote(sb, p.threadName); }
    if (p.gcMs != null) sb.append(",\"gcMs\":").append(p.gcMs);
    if (p.gcTotalMs != null) sb.append(",\"gcTotalMs\":").append(p.gcTotalMs);
    sb.append('}');
  }

  // ---- source snippets ----

  private static void appendSources(StringBuilder sb, List<DataPoint> annotated, Options opts) {
    TreeMap<String, String> snippets = new TreeMap<String, String>();
    if (opts.sourceContextLines > 0 && opts.sourceRoots != null && opts.sourceRoots.length > 0) {
      Map<String, List<String>> fileCache = new HashMap<String, List<String>>();
      for (DataPoint p : annotated) {
        if (p.sourceFile == null || snippets.containsKey(p.sourceFile)) continue;
        String json = snippetJson(p.sourceFile, opts, fileCache);
        if (json != null) snippets.put(p.sourceFile, json);
      }
    }
    sb.append('{');
    boolean first = true;
    for (Map.Entry<String, String> e : snippets.entrySet()) {
      if (!first) sb.append(',');
      first = false;
      MiniJson.quote(sb, e.getKey());
      sb.append(':').append(e.getValue());
    }
    sb.append('}');
  }

  private static String snippetJson(String sourceFile, Options opts, Map<String, List<String>> cache) {
    String rel = sourceFile;
    int line = -1;
    int colon = sourceFile.lastIndexOf(':');
    if (colon > 0) {
      try {
        line = Integer.parseInt(sourceFile.substring(colon + 1));
        rel = sourceFile.substring(0, colon);
      } catch (NumberFormatException ignored) {
      }
    }
    if (line <= 0) return null;
    List<String> lines = cache.get(rel);
    if (lines == null && !cache.containsKey(rel)) {
      File f = resolveSource(rel, opts.sourceRoots);
      lines = f == null ? null : readLines(f);
      cache.put(rel, lines);
    }
    if (lines == null || line > lines.size()) return null;
    int n = opts.sourceContextLines;
    int start = Math.max(1, line - n);
    int end = Math.min(lines.size(), line + n);
    StringBuilder sb = new StringBuilder(128 + (end - start + 1) * 60);
    sb.append("{\"file\":");
    MiniJson.quote(sb, rel);
    sb.append(",\"line\":").append(line);
    sb.append(",\"start\":").append(start);
    sb.append(",\"lines\":[");
    for (int i = start; i <= end; i++) {
      if (i > start) sb.append(',');
      String s = lines.get(i - 1);
      if (s.length() > 300) s = s.substring(0, 300) + "…";
      MiniJson.quote(sb, s);
    }
    sb.append("]}");
    return sb.toString();
  }

  static File resolveSource(String relative, String[] roots) {
    if (relative == null || relative.isEmpty() || roots == null) return null;
    String norm = relative.replace('\\', '/');
    if (norm.startsWith("/")) norm = norm.substring(1);
    if (norm.contains("..")) return null;
    for (String root : roots) {
      if (root == null || root.trim().isEmpty()) continue;
      File candidate = new File(root.trim(), norm);
      if (candidate.isFile()) return candidate;
    }
    return null;
  }

  private static List<String> readLines(File f) {
    try {
      byte[] bytes = readAll(new FileInputStream(f));
      String text;
      try {
        text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
      } catch (CharacterCodingException e) {
        text = new String(bytes, StandardCharsets.ISO_8859_1);
      }
      List<String> out = new ArrayList<String>();
      BufferedReader r = new BufferedReader(new java.io.StringReader(text));
      String l;
      while ((l = r.readLine()) != null) out.add(l.replace("\t", "    "));
      return out;
    } catch (IOException e) {
      return null;
    }
  }

  // ---- template ----

  /** [0] = everything up to the data block, [1] = everything after it. */
  private static String[] template() throws IOException {
    return template(VIEWER_PAGE, ENTRY_MODULE);
  }

  /**
   * A page with its modules inlined (see the class comment), split at the data
   * slot. Used for the viewer and for the run comparison page.
   */
  static String[] template(String page, String entryModule) throws IOException {
    String key = page + "|" + entryModule;
    String head = PAGE_CACHE.get(key);
    if (head == null) {
      head = buildTemplate(page, entryModule);
      PAGE_CACHE.put(key, head);
    }
    int cut = head.indexOf(DATA_SLOT);
    return new String[] {head.substring(0, cut), head.substring(cut + DATA_SLOT.length())};
  }

  private static final String DATA_SLOT = "@@DATA@@";

  private static final String STYLESHEET = "<link rel=\"stylesheet\" href=\"/web/styles.css\">";

  private static String buildTemplate(String page, String entryModule) throws IOException {
    String html = resourceText(page);
    int a = html.indexOf("<title>"), b = html.indexOf("</title>");
    if (a < 0 || b < a) throw new IOException(page + ": <title> not found");
    html = html.substring(0, a) + "<title>@@TITLE@@</title>\n<link rel=\"icon\" href=\"data:,\">"
        + html.substring(b + "</title>".length());
    if (html.contains(STYLESHEET)) {
      String css = resourceText("web/styles.css");
      if (css.contains("</style")) throw new IOException("styles.css must not contain </style");
      html = replaceOnce(html, STYLESHEET, "<style>\n" + css + "\n</style>\n" + THEME_SCRIPT);
    } else {
      html = replaceOnce(html, "</head>", THEME_SCRIPT + "</head>");
    }
    html = replaceBlock(html, "<!-- export:importmap -->", "<!-- /export:importmap -->", importMap(entryModule));
    html = replaceBlock(html, "<!-- export:entry -->", "<!-- /export:entry -->",
        DATA_SLOT + "<script type=\"module\">import '" + BARE_PREFIX + entryModule + "';</script>");
    return html;
  }

  /** Follows the OS colour scheme unless the viewer picked a theme with the toggle. */
  private static final String THEME_SCRIPT =
      "<script>(function(){var t=null;try{t=localStorage.getItem('memprofiler.theme');}catch(e){}"
      + "if(t!=='light'&&t!=='dark'){t=window.matchMedia&&window.matchMedia('(prefers-color-scheme: light)').matches?'light':'dark';}"
      + "document.documentElement.setAttribute('data-theme',t);})();</script>\n";

  static String importMap() throws IOException {
    return importMap(ENTRY_MODULE);
  }

  /** The import map for every module reachable from {@code entryModule}; Vue only when a module imports it. */
  static String importMap(String entryModule) throws IOException {
    String cached = IMPORT_MAP_CACHE.get(entryModule);
    if (cached != null) return cached;
    TreeMap<String, String> entries = new TreeMap<String, String>();
    boolean usesVue = false;
    Deque<String> queue = new ArrayDeque<String>();
    queue.add(entryModule);
    while (!queue.isEmpty()) {
      String module = queue.poll();
      String key = BARE_PREFIX + module;
      if (entries.containsKey(key)) continue;
      String src = resourceText(module);
      if (IMPORTS_VUE.matcher(src).find()) usesVue = true;
      StringBuffer rewritten = new StringBuffer(src.length() + 256);
      Matcher m = IMPORT_SPECIFIER.matcher(src);
      while (m.find()) {
        String target = resolveRelative(module, m.group(3));
        if (!entries.containsKey(BARE_PREFIX + target)) queue.add(target);
        m.appendReplacement(rewritten,
            Matcher.quoteReplacement(m.group(1) + m.group(2) + BARE_PREFIX + target + m.group(2)));
      }
      m.appendTail(rewritten);
      entries.put(key, dataUrl(rewritten.toString()));
    }
    if (usesVue) entries.put("vue", dataUrl(resourceText(VUE_RESOURCE)));
    StringBuilder sb = new StringBuilder(512 * 1024);
    sb.append("<script type=\"importmap\">\n{\"imports\":{");
    boolean first = true;
    for (Map.Entry<String, String> e : entries.entrySet()) {
      if (!first) sb.append(',');
      first = false;
      sb.append('\n');
      MiniJson.quote(sb, e.getKey()).append(':');
      MiniJson.quote(sb, e.getValue());
    }
    sb.append("\n}}\n</script>");
    String map = sb.toString();
    IMPORT_MAP_CACHE.put(entryModule, map);
    return map;
  }

  /** Module names reachable from the entry module (for tests). */
  static List<String> bundledModules() throws IOException {
    List<String> out = new ArrayList<String>();
    Matcher m = Pattern.compile("\"" + Pattern.quote(BARE_PREFIX) + "([^\"]+)\"").matcher(importMap());
    while (m.find()) out.add(m.group(1));
    return out;
  }

  static String resolveRelative(String fromModule, String spec) {
    String dir = fromModule.contains("/") ? fromModule.substring(0, fromModule.lastIndexOf('/')) : "";
    List<String> parts = new ArrayList<String>();
    if (!dir.isEmpty()) for (String s : dir.split("/")) parts.add(s);
    for (String s : spec.split("/")) {
      if (s.equals(".") || s.isEmpty()) continue;
      if (s.equals("..")) {
        if (!parts.isEmpty()) parts.remove(parts.size() - 1);
      } else {
        parts.add(s);
      }
    }
    StringBuilder sb = new StringBuilder();
    for (String p : parts) {
      if (sb.length() > 0) sb.append('/');
      sb.append(p);
    }
    return sb.toString();
  }

  private static String dataUrl(String js) {
    return "data:text/javascript;base64,"
        + java.util.Base64.getEncoder().encodeToString(js.getBytes(StandardCharsets.UTF_8));
  }

  private static String replaceOnce(String s, String find, String repl) throws IOException {
    int i = s.indexOf(find);
    if (i < 0) throw new IOException("viewer.html: marker not found: " + find);
    return s.substring(0, i) + repl + s.substring(i + find.length());
  }

  private static String replaceBlock(String s, String start, String end, String repl) throws IOException {
    int a = s.indexOf(start);
    int b = s.indexOf(end);
    if (a < 0 || b < a) throw new IOException("viewer.html: block not found: " + start);
    return s.substring(0, a) + repl + s.substring(b + end.length());
  }

  static String resourceText(String name) throws IOException {
    InputStream in = HtmlExporter.class.getResourceAsStream("/" + name);
    if (in == null) throw new IOException(name + " not on classpath");
    return new String(readAll(in), StandardCharsets.UTF_8);
  }

  private static byte[] readAll(InputStream in) throws IOException {
    try {
      ByteArrayOutputStream buf = new ByteArrayOutputStream(16 * 1024);
      byte[] chunk = new byte[16 * 1024];
      int n;
      while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
      return buf.toByteArray();
    } finally {
      in.close();
    }
  }

  static String escapeHtml(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&': sb.append("&amp;"); break;
        case '<': sb.append("&lt;"); break;
        case '>': sb.append("&gt;"); break;
        case '"': sb.append("&quot;"); break;
        case '\'': sb.append("&#39;"); break;
        default: sb.append(c);
      }
    }
    return sb.toString();
  }
}
