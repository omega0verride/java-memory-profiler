package com.toro.al.memprofiler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for bugs 3, 4 and 5 and improvements A and F of 1.0.5. */
class RegressionTest {

  @TempDir
  File tmp;

  @BeforeAll
  static void headless() {
    System.setProperty("java.awt.headless", "true");
  }

  @AfterEach
  void tearDown() {
    MemoryProfiler.shutdown();
    System.clearProperty(MemoryProfiler.SKIP_CALLERS_PROPERTY);
  }

  // ---- bug 3: REFERENCE is never a heap reading ----

  /** A run that peaks at 2 GB with a 5 GB reference line, as a 1.0.4 JSONL (REFERENCE as a point line). */
  private File legacyReferenceRun() throws Exception {
    File f = new File(tmp, "legacy-ref.jsonl");
    Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
    long t = TestData.T0;
    w.write(new DataPoint(0, t, 20, 500, 4096, DataPoint.Kind.SAMPLE, null, null, null, null, null, null, "s").toJsonLine() + "\n");
    w.write(new DataPoint(1, t, 5120, 500, 4096, DataPoint.Kind.REFERENCE, "Heap limit (5 GB)", "T.t:1", null, null,
        null, null, "main").toJsonLine() + "\n");
    for (int i = 2; i < 400; i++) {
      long used = i == 200 ? 2048 : 500 + i;
      w.write(new DataPoint(i, t + i * 250L, used, 3000, 4096, DataPoint.Kind.SAMPLE, null, null, null, null, null,
          null, "s").toJsonLine() + "\n");
    }
    w.write(new DataPoint(400, t + 400 * 250L, 900, 3000, 4096, DataPoint.Kind.MARK, "1. done", "T.t:9", null, null,
        null, null, "main").toJsonLine() + "\n");
    w.close();
    return f;
  }

  @Test
  @SuppressWarnings("unchecked")
  void referenceValueIsNeverThePeak() throws Exception {
    JsonlSource src = JsonlSource.open(legacyReferenceRun(), "last");
    assertEquals(1, src.runs().size());
    assertEquals(1, src.references().size());
    assertEquals(5120, src.references().get(0).usedMB);

    ProfileStats stats = ProfileStats.scan(src);
    assertEquals(2048, stats.peakMB, "ProfileStats");

    PngRenderer.Model m = PngRenderer.model(src, PngOptions.defaults().scale(1));
    assertTrue(m.subtitle.contains("peak 2 GB"), m.subtitle);
    for (int i = 0; i < m.seriesLen; i++) assertTrue(m.seriesMB[i] <= 2048, "no spike to the reference value");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    HtmlExporter.export(src, null, out);
    Map<String, Object> data = MiniJson.parseObject(HtmlExporterTest.embeddedJson(out.toString("UTF-8")));
    assertEquals(Long.valueOf(2048), ((Map<String, Object>) data.get("meta")).get("peakMB"), "HTML header");
    for (Object o : (List<Object>) data.get("points")) {
      assertFalse("REFERENCE".equals(((Map<String, Object>) o).get("kind")), "no REFERENCE among the points");
    }
    assertEquals(1, ((List<Object>) data.get("references")).size());

    // live: the store keeps references out of the points and out of maxObservedMB (the UI summary)
    DataStore store = new DataStore(1000);
    store.add(new DataPoint(-1, 1, 2048, 3000, 4096, DataPoint.Kind.SAMPLE, null, null, null, null, null, null, "s"));
    store.add(new DataPoint(-1, 2, 5120, 0, 4096, DataPoint.Kind.REFERENCE, "limit", null, null, null, null, null, "m"));
    assertEquals(2048, store.maxObservedMB());
    assertEquals(1, store.snapshot().size());
    assertEquals(1, store.references().size());
  }

  @Test
  void downsamplerDropsReferences() {
    final List<DataPoint> out = new ArrayList<DataPoint>();
    Downsampler d = new Downsampler(1, 100, Long.MAX_VALUE, new Downsampler.Sink() {
      public void accept(DataPoint p) { out.add(p); }
    });
    d.visit(new DataPoint(0, 1, 5120, 0, 0, DataPoint.Kind.REFERENCE, "x", null, null, null, null, null, null));
    d.visit(new DataPoint(1, 2, 100, 0, 0, DataPoint.Kind.SAMPLE, null, null, null, null, null, null, null));
    d.finish();
    assertEquals(1, out.size());
    assertEquals(DataPoint.Kind.SAMPLE, out.get(0).kind);
  }

  // ---- bug 4: the key prints the configured interval ----

  @Test
  void keyPrintsTheConfiguredInterval() {
    ExportMeta m = new ExportMeta();
    m.intervalMs = 250;
    m.medianSampleGapMs = 260;
    assertEquals("heap sampled every 250 ms", m.samplingText());
    m.medianSampleGapMs = 90_000;
    assertEquals("heap sampled every 250 ms (effective median 90000 ms, sampler delayed by GC)", m.samplingText());
    m.intervalMs = 0;
    m.medianSampleGapMs = 256;
    assertEquals("heap sampled about every 256 ms (median; interval not recorded)", m.samplingText());
  }

  @Test
  void configuredIntervalComesFromTheJsonlMeta() throws Exception {
    File jsonl = new File(tmp, "meta.jsonl");
    DataStore store = new DataStore(10_000, header(250, "Cache loader", "variant A"));
    store.enableJsonl(jsonl.getPath(), false);
    for (int i = 0; i < 400; i++) {
      // the sampler fell far behind: one sample every 2 s
      store.add(new DataPoint(-1, TestData.T0 + i * 2000L, 500, 900, 4096, DataPoint.Kind.SAMPLE,
          null, null, null, null, null, null, "s"));
    }
    store.close();
    PngRenderer.Model m = PngRenderer.model(JsonlSource.open(jsonl, "last"), PngOptions.defaults().scale(1));
    assertTrue(m.key.startsWith("Line: heap sampled every 250 ms (effective median 2000 ms, sampler delayed by GC)"),
        m.key);
    assertEquals("Cache loader", m.title);
  }

  // ---- bug 5: see com.acme.caller.CallerLocationTest (needs a package outside the profiler's) ----

  private static MemoryProfiler.Config quiet() {
    return MemoryProfiler.Config.defaults().startServer(false).openBrowser(false).gcListener(false)
        .intervalMs(1000).forceGc(1, 0);
  }

  @Test
  void markGcRecordsItsForcedGcTime() {
    MemoryProfiler.start(quiet().forceGc(2, 30));
    MemoryProfiler.markGc("1. forced");
    MemoryProfiler.mark("2. cheap");
    List<DataPoint> pts = MemoryProfiler.get().store().snapshot();
    DataPoint forced = null, cheap = null;
    for (DataPoint p : pts) {
      if ("1. forced".equals(p.label)) forced = p;
      if ("2. cheap".equals(p.label)) cheap = p;
    }
    assertNotNull(forced.gcMs);
    assertTrue(forced.gcMs.longValue() >= 60, "2 passes with a 30 ms pause");
    assertNull(cheap.gcMs);
  }

  // ---- improvement A: self-describing JSONL ----

  private static RunHeader header(long intervalMs, String title, String subtitle) {
    RunHeader h = RunHeader.anonymous();
    h.intervalMs = intervalMs;
    h.title = title;
    h.subtitle = subtitle;
    return h;
  }

  @Test
  void jsonlStartsWithMetaAndEveryPointCarriesTheRunId() throws Exception {
    File jsonl = new File(tmp, "a.jsonl");
    MemoryProfiler.start(quiet().jsonl(jsonl.getPath()).intervalMs(250).title("First title"));
    MemoryProfiler.markGc("1. start");
    MemoryProfiler.title("Cache loader", "variant B");
    MemoryProfiler.referenceLine(5120, "Production heap limit (5 GB)");
    MemoryProfiler.markGc("2. end");
    MemoryProfiler.shutdown();

    List<String> lines = Files.readAllLines(jsonl.toPath(), StandardCharsets.UTF_8);
    Map<String, Object> first = MiniJson.parseObject(lines.get(0));
    assertEquals("META", first.get("kind"));
    assertEquals(Long.valueOf(250), first.get("intervalMs"));
    assertEquals("First title", first.get("title"));
    String runId = (String) first.get("runId");
    assertNotNull(runId);
    int metas = 0;
    for (String l : lines) {
      Map<String, Object> m = MiniJson.parseObject(l);
      assertEquals(runId, m.get("runId"), l);
      if ("META".equals(m.get("kind"))) metas++;
      assertFalse("REFERENCE".equals(m.get("kind")), "references live in the META line");
    }
    assertEquals(3, metas, "start, title change, reference line");

    JsonlSource src = JsonlSource.open(jsonl, "last");
    assertEquals("Cache loader", src.meta().title);
    assertEquals("variant B", src.meta().subtitle);
    assertEquals(250, src.meta().intervalMs);
    assertEquals(1, src.references().size());
    assertEquals(5120, src.references().get(0).usedMB);
  }

  @Test
  void appendKeepsRunsApartAndOverwriteReplaces() throws Exception {
    File jsonl = new File(tmp, "runs.jsonl");
    for (int run = 1; run <= 2; run++) {
      MemoryProfiler.start(quiet().jsonl(jsonl.getPath()).jsonlMode(MemoryProfiler.JsonlMode.APPEND)
          .title("Run " + run));
      MemoryProfiler.markGc("1. only mark");
      MemoryProfiler.shutdown();
    }
    JsonlSource src = JsonlSource.open(jsonl, "all");
    assertEquals(2, src.runs().size());
    assertEquals("Run 1", src.runs().get(0).title());
    assertEquals("Run 2", src.runs().get(1).title());

    ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
    assertEquals(0, ExportCli.run(new String[] {"--jsonl", jsonl.getPath(), "--list-runs"},
        new PrintStream(out), new PrintStream(err)));
    assertTrue(out.toString().contains("run 2: \"Run 2\""), out.toString());

    MemoryProfiler.start(quiet().jsonl(jsonl.getPath()).title("Run 3"));
    MemoryProfiler.markGc("1. only mark");
    MemoryProfiler.shutdown();
    src = JsonlSource.open(jsonl, "all");
    assertEquals(1, src.runs().size(), "OVERWRITE is the default");
    assertEquals("Run 3", src.runs().get(0).title());
  }

  @Test
  void cliTakesTitleAndIntervalFromTheFile() throws Exception {
    File jsonl = new File(tmp, "cli.jsonl");
    MemoryProfiler.start(quiet().jsonl(jsonl.getPath()).intervalMs(250).title("From META"));
    MemoryProfiler.markGc("1. a");
    MemoryProfiler.shutdown();
    File png = new File(tmp, "cli.png"), html = new File(tmp, "cli.html");
    ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
    assertEquals(0, ExportCli.run(new String[] {"--jsonl", jsonl.getPath(), "--png", png.getPath(), "--html",
        html.getPath(), "--scale", "1"}, new PrintStream(out), new PrintStream(err)), err.toString());
    String h = new String(Files.readAllBytes(html.toPath()), StandardCharsets.UTF_8);
    assertTrue(h.contains("<title>From META"), "title from the META line");
  }

  // ---- improvement F: keep the UI up for a while, then exit normally ----

  @Test
  void awaitWithDurationReturnsAndShutsDown() throws Exception {
    MemoryProfiler.start(MemoryProfiler.Config.defaults().openBrowser(false).gcListener(false).intervalMs(1000));
    long t0 = System.currentTimeMillis();
    MemoryProfiler.stop();
    MemoryProfiler.await(Duration.ofMillis(300));
    long took = System.currentTimeMillis() - t0;
    assertFalse(MemoryProfiler.isRunning());
    assertTrue(took >= 250 && took < 5000, "took " + took);
  }

  @Test
  void keepUiAfterStopShutsDownByItself() throws Exception {
    File html = new File(tmp, "on-stop.html");
    java.net.ServerSocket ss = new java.net.ServerSocket(0);
    int port = ss.getLocalPort();
    ss.close();
    MemoryProfiler.start(MemoryProfiler.Config.defaults().port(port).openBrowser(false).gcListener(false)
        .intervalMs(1000).exportHtmlOnStop(html.getPath()).keepUiAfterStop(Duration.ofMillis(300)));
    MemoryProfiler.markGc("1. x");
    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("http://localhost:" + port + "/data")
        .openConnection();
    assertEquals(200, c.getResponseCode()); // starts an HTTP worker thread
    c.getInputStream().close();
    MemoryProfiler.stop();
    assertTrue(html.isFile(), "the snapshot is written before the wait");
    assertTrue(MemoryProfiler.isRunning(), "UI still up");
    long t0 = System.currentTimeMillis();
    MemoryProfiler.await(); // returns when the UI closes, not never
    assertFalse(MemoryProfiler.isRunning());
    assertTrue(System.currentTimeMillis() - t0 < 5000);
    // no worker thread keeps the JVM alive afterwards
    long deadline = System.currentTimeMillis() + 3000;
    boolean alive = true;
    while (alive && System.currentTimeMillis() < deadline) {
      alive = false;
      for (Thread t : Thread.getAllStackTraces().keySet()) {
        if (t.isAlive() && !t.isDaemon() && t.getName().startsWith("mem-profiler-http-")) alive = true;
      }
      if (alive) Thread.sleep(50);
    }
    assertFalse(alive, "HTTP worker threads must stop with the UI");
  }
}
