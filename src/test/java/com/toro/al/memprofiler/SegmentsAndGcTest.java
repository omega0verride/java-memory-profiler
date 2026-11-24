package com.toro.al.memprofiler;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Improvements D (segments), E (GC pressure) and G (table options). */
class SegmentsAndGcTest {

  @TempDir
  File tmp;

  @BeforeAll
  static void headless() {
    System.setProperty("java.awt.headless", "true");
  }

  @Test
  void segmentsGiveTheRetainedChangeBetweenMarks() throws Exception {
    PointSource src = TestData.nineMinuteRun().source("run");
    ProfileStats stats = ProfileStats.scan(src);
    List<Annotations.Row> rows = Annotations.rows(stats.annotated, stats.firstTs);
    List<Segments.Segment> segs = Segments.parse("2:4, 5:7", rows, stats.gapInput.gcTime());
    assertEquals(2, segs.size());
    Segments.Segment s = segs.get(0);
    assertEquals("2", s.from.badge);
    assertEquals("4", s.to.badge);
    assertEquals(s.to.point.usedMB - s.from.point.usedMB, s.deltaMB);
    // 5:7 ends at the (only) mark 7, after the three 6. progress marks
    assertEquals("7", segs.get(1).to.badge);
    assertThrows(IllegalArgumentException.class, () -> Segments.parse("2:99", rows, null));
    assertThrows(IllegalArgumentException.class, () -> Segments.parse("2", rows, null));

    List<Segments.Segment> callouts = Segments.callouts(rows, null);
    assertEquals(Segments.INCREASE, callouts.get(0).kind);
    assertEquals(Segments.RELEASE, callouts.get(1).kind);
    assertTrue(callouts.get(0).deltaMB > 0 && callouts.get(1).deltaMB < 0);

    PngRenderer.Model m = PngRenderer.model(src, PngOptions.defaults().scale(1).segments("2:4,5:7"));
    assertEquals(4, m.segments.size(), "two segments and two callouts");
    assertEquals(4, m.bracketRects.size());
    for (Annotations.BadgePlacement b : m.badges) {
      if (b.hidden) continue;
      for (Annotations.Rect r : m.bracketRects) assertFalse(b.rect.overlaps(r), "badge over a segment bracket");
    }
    assertEquals(2, PngRenderer.model(src, PngOptions.defaults().scale(1).segments("2:4,5:7").callouts(false))
        .segments.size());

    ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
    File jsonl = new File(tmp, "run.jsonl");
    DataStore store = new DataStore(1_000_000);
    store.enableJsonl(jsonl.getPath(), false);
    for (DataPoint p : TestData.nineMinuteRun().points) store.add(p);
    store.close();
    assertEquals(0, ExportCli.run(new String[] {"--jsonl", jsonl.getPath(), "--summary", "--segments", "2:4"},
        new PrintStream(out), new PrintStream(err)), err.toString());
    String text = out.toString("UTF-8");
    assertTrue(text.contains("2→4"), text);
    assertTrue(text.contains("largest release"), text);
    assertTrue(text.contains("GC/minute"), text);
  }

  @Test
  void gcOverheadRegionsCoverTheThrash() throws Exception {
    TestData.Builder b = GapRuleTest.gcThrashRun();
    PointSource src = b.source("thrash");
    ProfileStats stats = ProfileStats.scan(src);
    GapRule.Result gaps = stats.gaps(250);
    GcPressure gc = GcPressure.of(stats, gaps, 50);
    assertFalse(gc.regions.isEmpty());
    long[] last = gc.regions.get(gc.regions.size() - 1);
    assertTrue(last[0] <= TestData.T0 + 250_000, "the GC-bound plateau is shaded from about 4:00");
    assertEquals(stats.lastTs, last[1], "to the end");
    assertTrue(last[2] >= 90, "GC overhead " + last[2] + "%");
    assertEquals(18, gc.perMinute.size());
    assertTrue(gc.perMinute.get(10) >= 90);
    assertTrue(gc.longestMs >= 14_000, "longest pause " + gc.longestMs);
    assertTrue(gc.summary().startsWith("GC "));

    PngRenderer.Model m = PngRenderer.model(src, PngOptions.defaults().scale(1));
    assertTrue(m.key2.contains("Shaded: GC took at least 50%"), m.key2);
    assertTrue(PngRenderer.model(src, PngOptions.defaults().scale(1).gcOverhead(0)).gc.regions.isEmpty());
  }

  @Test
  void tableOptions() throws Exception {
    PointSource src = TestData.nineMinuteRun().source("run");
    PngRenderer.Model full = PngRenderer.model(src, PngOptions.defaults().scale(1).includeTable(true));
    PngRenderer.Model only = PngRenderer.model(src, PngOptions.defaults().scale(1).marksOnlyTable(true));
    assertTrue(only.height < full.height - 500, "no chart above the table");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PngRenderer.render(src, PngOptions.defaults().scale(1).marksOnlyTable(true), out);
    BufferedImage img = ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(only.heightPx, img.getHeight());

    PngRenderer.Model cols = PngRenderer.model(src, PngOptions.defaults().scale(1).includeTable(true)
        .tableColumns("badge,mark,retained,first").locationWidth(400));
    assertEquals(4, cols.columns.size());
    assertEquals("first", cols.columns.get(3).id);
    PngRenderer.Model wide = PngRenderer.model(src, PngOptions.defaults().scale(1).includeTable(true).locationWidth(400));
    for (PngRenderer.TableCol c : wide.columns) if ("location".equals(c.id)) assertEquals(400, c.w, 1e-9);
    assertThrows(IllegalArgumentException.class,
        () -> PngRenderer.model(src, PngOptions.defaults().includeTable(true).tableColumns("badge,nope")));
  }

  @Test
  void labelNumbersGetThousandsSeparators() {
    assertEquals("Import done: 1,250,000 rows (all files)",
        Annotations.formatNumbers("Import done: 1250000 rows (all files)"));
    assertEquals("cache 52,000 entries, 1000 rows, 7.5 GB, 12345.6 ms",
        Annotations.formatNumbers("cache 52000 entries, 1000 rows, 7.5 GB, 12345.6 ms"));
  }
}
