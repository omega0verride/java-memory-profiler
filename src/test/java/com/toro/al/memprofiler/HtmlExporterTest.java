package com.toro.al.memprofiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlExporterTest {

  static final String NASTY = "8. Report </script><script>alert(1)</script> & <b>\"quotes\"</b> 'single'   {{ 1+1 }}";

  @TempDir
  File tmp;

  /** Writes the points the way the profiler does (DataStore with JSONL enabled). */
  private File writeJsonl(List<DataPoint> points, String name, boolean append) throws IOException {
    File f = new File(tmp, name);
    if (!append) f.delete();
    DataStore store = new DataStore(1_000_000);
    store.enableJsonl(f.getPath());
    for (DataPoint p : points) store.add(p);
    store.close();
    return f;
  }

  private static String export(PointSource src, HtmlExporter.Options o) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    HtmlExporter.export(src, o, out);
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
  }

  static String embeddedJson(String html) {
    String open = "<script type=\"application/json\" id=\"profile-data\">";
    int a = html.indexOf(open);
    assertTrue(a >= 0, "data block missing");
    int b = html.indexOf("</script>", a);
    return html.substring(a + open.length(), b);
  }

  private static HtmlExporter.Options fixed() {
    HtmlExporter.Options o = new HtmlExporter.Options();
    o.exportedAt = Long.valueOf(1764000123456L);
    return o;
  }

  @Test
  @SuppressWarnings("unchecked")
  void jsonlToHtmlRoundTripsEveryPoint() throws Exception {
    TestData.Builder b = TestData.nineMinuteRun();
    b.mark(TestData.T0 + 600_000, 1000, NASTY);
    File jsonl = writeJsonl(b.points, "run.jsonl", false);

    JsonlSource src = JsonlSource.open(jsonl, "last");
    src.meta().title = "Cache </title><script>x()</script> loader";
    String html = export(src, fixed());

    String json = embeddedJson(html);
    assertFalse(json.contains("<"), "embedded JSON must not contain '<'");
    assertFalse(json.contains(" "), "embedded JSON must not contain raw U+2028");
    Map<String, Object> data = MiniJson.parseObject(json);

    List<Object> points = (List<Object>) data.get("points");
    List<DataPoint> expected = new ArrayList<DataPoint>();
    List<DataPoint> expectedRefs = new ArrayList<DataPoint>();
    for (DataPoint p : b.points) (p.kind == DataPoint.Kind.REFERENCE ? expectedRefs : expected).add(p);
    assertEquals(expected.size(), points.size());
    for (int i = 0; i < expected.size(); i++) assertPoint(expected.get(i), (Map<String, Object>) points.get(i));

    List<Object> refs = (List<Object>) data.get("references");
    assertEquals(1, refs.size());
    // references travel in the JSONL META line: value, caption, time and location survive
    Map<String, Object> ref = (Map<String, Object>) refs.get(0);
    assertEquals(Long.valueOf(expectedRefs.get(0).usedMB), ref.get("usedMB"));
    assertEquals(expectedRefs.get(0).label, ref.get("label"));
    assertEquals(Long.valueOf(expectedRefs.get(0).timestamp), ref.get("timestamp"));
    assertEquals("REFERENCE", ref.get("kind"));

    Map<String, Object> meta = (Map<String, Object>) data.get("meta");
    assertEquals("Cache </title><script>x()</script> loader", meta.get("title"));
    assertEquals(Long.valueOf(8192), meta.get("jvmMaxHeapMB"));
    assertEquals(Long.valueOf(6963), meta.get("peakMB"));
    assertEquals(Long.valueOf(1764000123456L), meta.get("exportedAt"));

    // the title is escaped in <title> and nowhere else injected as markup
    assertTrue(html.contains("<title>Cache &lt;/title&gt;&lt;script&gt;x()&lt;/script&gt; loader"));
    // user text never lands inside the Vue template (#app) where {{ }} would be evaluated
    String app = html.substring(html.indexOf("<div id=\"app\">"), html.indexOf("<script type=\"application/json\""));
    assertFalse(app.contains("1+1"));
  }

  @SuppressWarnings("unchecked")
  private static void assertPoint(DataPoint e, Map<String, Object> a) {
    assertEquals(Long.valueOf(e.index), a.get("index"));
    assertEquals(Long.valueOf(e.timestamp), a.get("timestamp"));
    assertEquals(Long.valueOf(e.usedMB), a.get("usedMB"));
    assertEquals(Long.valueOf(e.committedMB), a.get("committedMB"));
    assertEquals(Long.valueOf(e.maxMB), a.get("maxMB"));
    assertEquals(e.kind.name(), a.get("kind"));
    assertEquals(e.label, a.get("label"));
    assertEquals(e.location, a.get("location"));
    assertEquals(e.sourceFile, a.get("sourceFile"));
    assertEquals(e.scopeId, a.get("scopeId"));
    assertEquals(e.deltaMB, a.get("deltaMB"));
    assertEquals(e.durationMs, a.get("durationMs"));
    assertEquals(e.threadName, a.get("thread"));
  }

  @Test
  void sameDataExportsByteIdenticalExceptTheTimestamp() throws Exception {
    TestData.Builder b = TestData.nineMinuteRun();
    HtmlExporter.Options o1 = fixed();
    HtmlExporter.Options o2 = fixed();
    String a = export(b.source("t"), o1);
    String same = export(b.source("t"), o2);
    assertArrayEquals(a.getBytes(StandardCharsets.UTF_8), same.getBytes(StandardCharsets.UTF_8));

    o2.exportedAt = Long.valueOf(1764999999999L);
    String later = export(b.source("t"), o2);
    String strip1 = a.replaceAll("\"exportedAt\":\\d+", "\"exportedAt\":0");
    String strip2 = later.replaceAll("\"exportedAt\":\\d+", "\"exportedAt\":0");
    assertFalse(a.equals(later));
    assertEquals(strip1, strip2);
  }

  @Test
  void isSelfContained() throws Exception {
    String html = export(TestData.nineMinuteRun().source("t"), fixed());
    assertFalse(html.contains("unpkg.com"), "must not load Vue from the network");
    assertFalse(html.contains("src=\"/web/"), "must not reference server paths");
    assertFalse(html.contains("href=\"/web/"), "must not reference server paths");
    assertTrue(html.contains("<script type=\"importmap\">"));
    assertTrue(html.contains("\"vue\":\"data:text/javascript;base64,"));
    assertTrue(html.indexOf("<script type=\"importmap\">") < html.indexOf("<script type=\"module\">"));
    assertTrue(html.indexOf("id=\"profile-data\"") < html.indexOf("<script type=\"module\">"));
    assertTrue(html.contains("<style>"));
  }

  @Test
  void bundlesEveryViewerModule() throws Exception {
    List<String> bundled = HtmlExporter.bundledModules();
    File web = new File("src/main/resources/web");
    List<String> expected = new ArrayList<String>();
    for (File f : web.listFiles()) {
      if (f.getName().endsWith(".js") && !f.getName().equals("layout-app.js") && !f.getName().equals("source-app.js")
          && !f.getName().equals("compare-app.js")) { // compare-app.js: the comparison page's own entry
        expected.add("web/" + f.getName());
      }
    }
    for (File f : new File(web, "components").listFiles()) expected.add("web/components/" + f.getName());
    for (String e : expected) assertTrue(bundled.contains(e), e + " is not reachable from web/app.js");
    assertEquals(expected.size(), bundled.size(), "bundled: " + bundled);
  }

  @Test
  @SuppressWarnings("unchecked")
  void embedsSourceSnippetsFromSourceRoots() throws Exception {
    File root = new File(tmp, "src");
    File file = new File(root, "com/acme/loader/CacheLoaderTest.java");
    file.getParentFile().mkdirs();
    Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
    for (int i = 1; i <= 200; i++) w.write(i == 105 ? "  String s = \"</script>\";\n" : "  // line " + i + "\n");
    w.close();

    TestData.Builder b = new TestData.Builder();
    b.sample(TestData.T0, 100);
    b.add(new DataPoint(b.index++, TestData.T0 + 10, 100, 200, 1000, DataPoint.Kind.MARK, "1. here",
        "com.acme.loader.CacheLoaderTest.run:105", "com/acme/loader/CacheLoaderTest.java:105", null, null, null, "main"));
    b.sample(TestData.T0 + 250, 100);
    HtmlExporter.Options o = fixed();
    o.sourceRoots = new String[] {new File(tmp, "missing").getPath(), root.getPath()};
    o.sourceContextLines = 3;
    String html = export(b.source("t"), o);
    Map<String, Object> data = MiniJson.parseObject(embeddedJson(html));
    Map<String, Object> sources = (Map<String, Object>) data.get("sources");
    Map<String, Object> snip = (Map<String, Object>) sources.get("com/acme/loader/CacheLoaderTest.java:105");
    assertNotNull(snip);
    assertEquals(Long.valueOf(105), snip.get("line"));
    assertEquals(Long.valueOf(102), snip.get("start"));
    List<Object> lines = (List<Object>) snip.get("lines");
    assertEquals(7, lines.size());
    assertEquals("  String s = \"</script>\";", lines.get(3));

    o.sourceRoots = new String[0];
    data = MiniJson.parseObject(embeddedJson(export(b.source("t"), o)));
    assertTrue(((Map<String, Object>) data.get("sources")).isEmpty());
  }

  @Test
  @SuppressWarnings("unchecked")
  void downsamplesLargeRunsButKeepsEvents() throws Exception {
    TestData.Builder b = new TestData.Builder();
    for (int i = 0; i < 50_000; i++) {
      b.sample(TestData.T0 + i * 100L, 1000 + (i % 97) + (i == 33_333 ? 6000 : 0));
      if (i % 1000 == 0) b.mark(TestData.T0 + i * 100L + 1, 900, "m" + i);
    }
    HtmlExporter.Options o = fixed();
    o.maxSamples = 2000;
    Map<String, Object> data = MiniJson.parseObject(embeddedJson(export(b.source("t"), o)));
    List<Object> points = (List<Object>) data.get("points");
    int samples = 0, marks = 0;
    long peak = 0;
    for (Object x : points) {
      Map<String, Object> p = (Map<String, Object>) x;
      if ("SAMPLE".equals(p.get("kind"))) {
        samples++;
        peak = Math.max(peak, ((Long) p.get("usedMB")).longValue());
      } else if ("MARK".equals(p.get("kind"))) {
        marks++;
      }
    }
    assertTrue(samples <= 2000);
    assertEquals(50, marks);
    assertEquals(7000 + 33_333 % 97, peak);
    Map<String, Object> meta = (Map<String, Object>) data.get("meta");
    assertEquals(Long.valueOf(50_000), meta.get("sampleCount"));
    assertEquals(Long.valueOf(samples), meta.get("exportedSampleCount"));
  }

  @Test
  void tenMinuteRunAt250msStaysSmall() throws Exception {
    TestData.Builder b = new TestData.Builder();
    for (int i = 0; i < 10 * 60 * 4; i++) {
      b.sample(TestData.T0 + i * 250L, 1000 + i % 300);
      if (i % 4 == 0) b.gc(TestData.T0 + i * 250L + 1, 900);
      if (i % 40 == 0) b.mark(TestData.T0 + i * 250L + 2, 900, (i / 40) + ". step");
    }
    String html = export(b.source("t"), fixed());
    int bytes = html.getBytes(StandardCharsets.UTF_8).length;
    assertTrue(bytes < 3 * 1024 * 1024, "export is " + bytes + " bytes");
  }

  // ---- JSONL runs ----

  @Test
  void splitsAppendedRunsWhereIndexResets() throws Exception {
    File f = null;
    for (int run = 1; run <= 3; run++) {
      TestData.Builder b = new TestData.Builder();
      for (int i = 0; i < 10 * run; i++) b.sample(TestData.T0 + run * 1_000_000L + i * 250L, 100 * run);
      b.scopeStart(TestData.T0 + run * 1_000_000L + 5, 100, "job", "s1");
      b.scopeEnd(TestData.T0 + run * 1_000_000L + 2000, 100, "job", "s1", 0, 1995);
      f = writeJsonl(b.points, "multi.jsonl", run > 1);
    }
    JsonlSource last = JsonlSource.open(f, null);
    assertEquals(3, last.runs().size());
    assertEquals(3, last.selectedRuns().get(0).number);
    assertEquals(32, count(last));
    assertTrue(last.meta().source.contains("run 3 of 3"));

    JsonlSource second = JsonlSource.open(f, "2");
    assertEquals(22, count(second));
    final long[] used = {0};
    second.forEach(new PointSource.Visitor() {
      public void visit(DataPoint p) { used[0] = p.usedMB; }
    });
    assertEquals(100, used[0]);

    JsonlSource all = JsonlSource.open(f, "all");
    final List<DataPoint> pts = new ArrayList<DataPoint>();
    all.forEach(new PointSource.Visitor() {
      public void visit(DataPoint p) { pts.add(p); }
    });
    assertEquals(12 + 22 + 32, pts.size());
    for (int i = 0; i < pts.size(); i++) assertEquals(i, pts.get(i).index, "renumbered");
    assertEquals(3, Annotations.bands(scopes(pts), 0, Long.MAX_VALUE).size(), "scope ids must stay unique per run");

    try {
      JsonlSource.open(f, "4");
      throw new AssertionError("run 4 should not exist");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("3 run"));
    }
  }

  @Test
  void skipsBrokenLinesAndUnknownKinds() throws Exception {
    File f = new File(tmp, "broken.jsonl");
    Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
    w.write("{\"index\":0,\"timestamp\":1,\"usedMB\":5,\"committedMB\":6,\"maxMB\":7,\"kind\":\"SAMPLE\"}\n");
    w.write("{\"index\":1,\"timestamp\":2,\"usedMB\":5,\"committedMB\":6,\"maxMB\":7,\"kind\":\"FROM_THE_FUTURE\"}\n");
    w.write("\n");
    w.write("{\"index\":2,\"timestamp\":3,\"usedMB\":5,\"commi");
    w.close();
    JsonlSource src = JsonlSource.open(f, "last");
    assertEquals(1, src.runs().size());
    assertEquals(1, count(src));
    assertNull(JsonlSource.parseLine("not json"));
  }

  private static List<DataPoint> scopes(List<DataPoint> pts) {
    List<DataPoint> out = new ArrayList<DataPoint>();
    for (DataPoint p : pts) if (Annotations.isAnnotated(p.kind)) out.add(p);
    return out;
  }

  private static int count(PointSource src) throws IOException {
    final int[] n = {0};
    src.forEach(new PointSource.Visitor() {
      public void visit(DataPoint p) { n[0]++; }
    });
    return n[0];
  }
}
