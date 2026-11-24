package com.toro.al.memprofiler;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Improvement C: export --compare. */
class CompareTest {

  @TempDir
  File tmp;

  @BeforeAll
  static void headless() {
    System.setProperty("java.awt.headless", "true");
  }

  /** A run whose retained heap grows with every "6." progress mark, peaking at mark 7. */
  static TestData.Builder run(long peakRetained, int progressMarks, long durationMs) {
    TestData.Builder b = new TestData.Builder();
    b.reference(TestData.T0, 5120, "Heap limit (5 GB)");
    long step = durationMs / (progressMarks + 8);
    long t = TestData.T0;
    int k = 0;
    String[] fixed = {"0. Start", "1. Input read", "2. Input parsed", "3. Index built", "4. Report ready", "5. Export start"};
    for (String l : fixed) { fill(b, t, t + step, 400 + 50 * k++); t += step; b.mark(t, 100 * k, l); }
    for (int i = 1; i <= progressMarks; i++) {
      fill(b, t, t + step, 600);
      t += step;
      b.mark(t, 300 + (peakRetained - 300) * i / (progressMarks + 1), "6. exported part " + i);
    }
    fill(b, t, t + step, 700);
    t += step;
    b.mark(t, peakRetained, "7. export complete (peak)");
    fill(b, t, t + step, 500);
    t += step;
    b.mark(t, Math.min(1004, peakRetained * 9 / 10), "8. cleaned up");
    return b;
  }

  private static void fill(TestData.Builder b, long from, long to, long base) {
    for (long t = from + 250; t < to; t += 250) b.sample(t, base + (t / 250) % 300);
  }

  private static File jsonl(File dir, String name, TestData.Builder b, String title) throws Exception {
    File f = new File(dir, name);
    RunHeader h = RunHeader.anonymous();
    h.title = title;
    h.intervalMs = 250;
    DataStore store = new DataStore(1_000_000, h);
    store.enableJsonl(f.getPath(), false);
    for (DataPoint p : b.points) store.add(p);
    store.close();
    return f;
  }

  @Test
  void matchesMarksByNumberAndOccurrenceAndSummarizes() throws Exception {
    List<Comparison.Run> runs = new ArrayList<Comparison.Run>();
    runs.add(new Comparison.Run("Baseline", run(4800, 4, 480_000).source("baseline")));
    runs.add(new Comparison.Run("Variant A", run(1500, 4, 150_000).source("a")));
    runs.add(new Comparison.Run("Variant B", run(700, 3, 90_000).source("c")));
    Comparison c = Comparison.build(runs, "start");

    List<String> keys = new ArrayList<String>();
    for (Comparison.MarkRow r : c.markRows) keys.add(r.key);
    assertEquals("0#1", keys.get(0));
    assertTrue(keys.contains("6#4"));
    assertEquals(keys.indexOf("6#4") + 1, keys.indexOf("7#1"), "occurrences in order, before mark 7");
    Comparison.MarkRow sixFour = c.markRows.get(keys.indexOf("6#4"));
    assertTrue(sixFour.mb(0) != null && sixFour.mb(1) != null);
    assertNull(sixFour.mb(2), "variant B has only three 6. marks");

    Comparison.SummaryRow peakRetained = c.summary.get(0);
    assertEquals("Peak retained (highest mark)", peakRetained.name);
    assertEquals(Long.valueOf(4800), peakRetained.values[0]);
    assertEquals(Long.valueOf(1500), peakRetained.values[1]);
    assertEquals(Long.valueOf(700), peakRetained.values[2]);
    assertEquals("−3,300 (−69%)", Comparison.delta(peakRetained.values[0], peakRetained.values[1], "MB"));
    assertEquals(1, c.references.size(), "the same reference line of three runs is drawn once");

    PngRenderer.Model none = null;
    ComparePngRenderer.Model m = ComparePngRenderer.model(c, PngOptions.defaults().scale(1));
    assertEquals(3, m.series.size());
    for (int i = 0; i < m.endLabels.size(); i++) {
      for (int j = 0; j < i; j++) assertFalse(m.endLabels.get(i).overlaps(m.endLabels.get(j)), "end labels overlap");
    }
    assertTrue(m.yMaxMB >= 4800);
    assertNull(none);
  }

  @Test
  void alignsAtAMark() throws Exception {
    List<Comparison.Run> runs = new ArrayList<Comparison.Run>();
    runs.add(new Comparison.Run("Baseline", run(4800, 4, 480_000).source("baseline")));
    runs.add(new Comparison.Run("Variant", run(700, 3, 90_000).source("fix")));
    Comparison c = Comparison.build(runs, "mark:5");
    for (Comparison.Run r : c.runs) assertEquals(r.byKey.get("5#1").point.timestamp, r.alignTs);
    assertTrue(c.minMs() < 0, "time before mark 5 is negative");
    assertThrows(IllegalArgumentException.class, () -> Comparison.build(runs, "middle"));
    assertThrows(IllegalArgumentException.class, () -> Comparison.build(runs.subList(0, 1), "start"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void cliWritesTheOverlayPngAndTheInteractiveHtml() throws Exception {
    File a = jsonl(tmp, "base.jsonl", run(4800, 4, 480_000), "Baseline run");
    File b = jsonl(tmp, "fix.jsonl", run(1100, 4, 110_000), "Variant B run");
    File png = new File(tmp, "out/cmp.png"), html = new File(tmp, "out/cmp.html");
    ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
    int code = ExportCli.run(new String[] {"--compare", a.getPath() + "=Baseline", b.getPath(), "--png", png.getPath(),
        "--html", html.getPath(), "--align", "mark:1", "--scale", "1"}, new PrintStream(out), new PrintStream(err));
    assertEquals(0, code, err.toString());
    assertTrue(png.length() > 10_000);
    String page = new String(Files.readAllBytes(html.toPath()), StandardCharsets.UTF_8);
    assertTrue(page.contains("@memprofiler/web/compare-app.js"));
    assertTrue(page.contains("@memprofiler/web/gaps.js"));
    assertFalse(page.contains("\"vue\":"), "the comparison page does not need Vue");
    int s = page.indexOf("id=\"compare-data\">") + "id=\"compare-data\">".length();
    Map<String, Object> data = MiniJson.parseObject(page.substring(s, page.indexOf("</script>", s)));
    List<Object> runs = (List<Object>) data.get("runs");
    assertEquals(2, runs.size());
    assertEquals("Baseline", ((Map<String, Object>) runs.get(0)).get("label"));
    assertEquals("Variant B run", ((Map<String, Object>) runs.get(1)).get("label"), "label defaults to the recorded title");
    assertEquals("1", data.get("alignMark"));
    Map<String, Object> table = (Map<String, Object>) data.get("table");
    assertTrue(((List<Object>) table.get("rows")).size() >= 12);
    assertEquals(6, ((List<Object>) table.get("summary")).size());
  }
}
