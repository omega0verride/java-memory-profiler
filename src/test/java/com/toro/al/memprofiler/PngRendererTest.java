package com.toro.al.memprofiler;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PngRendererTest {

  @TempDir
  File tmp;

  @BeforeAll
  static void headless() {
    System.setProperty("java.awt.headless", "true");
  }

  private static byte[] png(PointSource src, PngOptions o) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PngRenderer.render(src, o, out);
    return out.toByteArray();
  }

  @Test
  void fourteenMarksRenderWithoutOverlaps() throws Exception {
    PngOptions o = PngOptions.defaults().includeTable(true);
    PngRenderer.Model m = PngRenderer.model(TestData.nineMinuteRun().source("Cache loader"), o);
    assertEquals(18, m.rows.size(), "14 marks + 2 scopes x 2 ends");
    assertEquals(0, m.hiddenBadges, "every badge should fit on a 1400 px chart");
    AnnotationsTest.assertNoOverlapInside(m.badges, m.plot, -1);
    // marks 3, 4 and 5 are within 10 s: their badges must not overlap and must stay near their dots
    for (Annotations.BadgePlacement b : m.badges) {
      for (PngRenderer.RefLine r : m.refs) {
        if (r.labelRect != null) assertFalse(b.rect.overlaps(r.labelRect), "badge over a reference label");
      }
    }
    for (int i = 0; i < m.titles.size(); i++) {
      Annotations.TitlePlacement a = m.titles.get(i);
      if (a.hidden) continue;
      assertTrue(a.x >= m.plot.x && a.x + a.w <= m.plot.right() + 1e-6, "band title leaves the plot");
      for (int j = 0; j < i; j++) {
        Annotations.TitlePlacement b = m.titles.get(j);
        if (!b.hidden && b.row == a.row) assertTrue(a.x >= b.x + b.w || b.x >= a.x + a.w, "band titles overlap");
      }
    }
    // reference lines: the user line and the JVM max (8 GB is within 1.5 x the 6.8 GB peak)
    assertEquals(2, m.refs.size());
    assertTrue(m.yMaxMB >= 8192);
    assertTrue(m.gb);
    assertTrue(m.key.startsWith("Line: heap sampled every 250 ms"));
    assertEquals(18, m.tableRows);
  }

  @Test
  void oneSampleSpikeSurvivesTheReduction() throws Exception {
    TestData.Builder b = new TestData.Builder();
    Random rnd = new Random(5);
    for (int i = 0; i < 20_000; i++) {
      b.sample(TestData.T0 + i * 250L, i == 12_345 ? 6963 : 1000 + rnd.nextInt(800));
    }
    PngOptions o = PngOptions.defaults().theme("dark");
    PngRenderer.Model m = PngRenderer.model(b.source("spike"), o);
    long max = 0;
    for (int i = 0; i < m.seriesLen; i++) max = Math.max(max, m.seriesMB[i]);
    assertEquals(6963, max, "the spike must be in the drawn series");
    assertTrue(m.seriesLen <= 4 * Math.round(m.plot.w * o.scale()) + 2);

    // and it is actually painted: the line colour appears at the spike's x near its y
    BufferedImage img = ImageIO.read(new ByteArrayInputStream(png(b.source("spike"), o)));
    double s = o.scale();
    int x = (int) Math.round(m.xAt(TestData.T0 + 12_345 * 250L) * s);
    int yTop = (int) Math.round(m.yAt(6963) * s);
    boolean found = false;
    int line = PngRenderer.palette(true).line.getRGB() & 0xFFFFFF;
    for (int dx = -3; dx <= 3 && !found; dx++) {
      for (int dy = -2; dy <= 6 && !found; dy++) {
        int rgb = img.getRGB(x + dx, yTop + dy) & 0xFFFFFF;
        if (close(rgb, line, 60)) found = true;
      }
    }
    assertTrue(found, "no line pixel at the spike");
  }

  private static boolean close(int a, int b, int tol) {
    return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) <= tol
        && Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) <= tol
        && Math.abs((a & 255) - (b & 255)) <= tol;
  }

  @Test
  void outputIsDeterministic() throws Exception {
    PngOptions o = PngOptions.defaults().includeTable(true).scale(1);
    byte[] a = png(TestData.nineMinuteRun().source("t"), o);
    byte[] b = png(TestData.nineMinuteRun().source("t"), o);
    assertArrayEquals(a, b);
    BufferedImage img = ImageIO.read(new ByteArrayInputStream(a));
    assertEquals(1400, img.getWidth());
  }

  @Test
  void rangeByMarkNumbers() throws Exception {
    PngOptions o = PngOptions.defaults().fromMark("5").toMark("8");
    PngRenderer.Model m = PngRenderer.model(TestData.nineMinuteRun().source("t"), o);
    for (Annotations.Row r : m.rows) {
      assertTrue(r.point.timestamp >= m.tMin && r.point.timestamp <= m.tMax);
    }
    assertEquals("5", m.rows.get(0).badge, "mark 4 is inside the margin but not in the requested range");
    assertEquals("8", m.rows.get(m.rows.size() - 1).badge);
    assertTrue(m.tMin < m.rows.get(0).point.timestamp, "the chart keeps a margin before mark 5");
    assertTrue(m.subtitle.contains("showing"));
    assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
      public void execute() throws Throwable {
        PngRenderer.model(TestData.nineMinuteRun().source("t"), PngOptions.defaults().fromMark("99"));
      }
    });
    // elapsed-ms range
    PngRenderer.Model r = PngRenderer.model(TestData.nineMinuteRun().source("t"),
        PngOptions.defaults().from(60_000).to(120_000));
    assertEquals(TestData.T0 + 60_000, r.tMin);
    assertEquals(TestData.T0 + 120_000, r.tMax);
  }

  @Test
  void twentyThousandPointsAtScale2IsFastAndLean() throws Exception {
    TestData.Builder b = new TestData.Builder();
    Random rnd = new Random(9);
    for (int i = 0; i < 20_000; i++) {
      b.sample(TestData.T0 + i * 250L, 1000 + rnd.nextInt(3000));
      if (i % 1000 == 0) b.mark(TestData.T0 + i * 250L + 1, 900, (i / 1000) + ". step " + i);
      if (i % 50 == 0) b.gc(TestData.T0 + i * 250L + 2, 800);
    }
    PointSource src = b.source("perf");
    PngOptions o = PngOptions.defaults().scale(2).includeTable(true);
    png(src, PngOptions.defaults().scale(1).width(600)); // warm up fonts and ImageIO

    com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long tid = Thread.currentThread().getId();
    long alloc0 = threads.getThreadAllocatedBytes(tid);
    long t0 = System.nanoTime();
    byte[] out = png(src, o);
    long ms = (System.nanoTime() - t0) / 1_000_000;
    long allocated = threads.getThreadAllocatedBytes(tid) - alloc0;
    System.out.println("[png] 20k points, scale 2: " + ms + " ms, " + allocated / (1024 * 1024) + " MB allocated, "
        + out.length / 1024 + " KB");
    assertTrue(ms < 2000, "took " + ms + " ms");
    assertTrue(allocated < 200L * 1024 * 1024, "allocated " + allocated / (1024 * 1024) + " MB");
  }

  @Test
  void writesFilesAndHandlesEmptyProfiles() throws Exception {
    File f = PngRenderer.render(TestData.nineMinuteRun().source("t"), null, new File(tmp, "a/b/c.png"));
    assertTrue(f.isFile() && f.length() > 1000);
    TestData.Builder b = new TestData.Builder();
    b.sample(TestData.T0, 10);
    File one = PngRenderer.render(b.source("t"), PngOptions.defaults().includeTable(true), new File(tmp, "one.png"));
    assertTrue(one.isFile());
  }

  @Test
  void niceTicksAndFormats() {
    assertEquals(1, PngRenderer.niceStep(0.8), 1e-9);
    assertEquals(2, PngRenderer.niceStep(1.7), 1e-9);
    assertEquals(250, PngRenderer.niceStep(230), 1e-9);
    assertEquals("1:05", PngRenderer.formatElapsed(65_000, 1000));
    assertEquals("1:01:05", PngRenderer.formatElapsed(3_665_000, 1000));
    assertEquals("0:01.5", PngRenderer.formatElapsed(1_500, 500));
    assertEquals("5 GB", PngRenderer.formatMB(5120, true));
    assertEquals("6.8 GB", PngRenderer.formatMB(6963, true));
    assertEquals("512 MB", PngRenderer.formatMB(512, true));
    assertEquals(60_000, PngRenderer.timeStep(9 * 60_000, 1298));
  }
}
