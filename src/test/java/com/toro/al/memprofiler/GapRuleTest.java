package com.toro.al.memprofiler;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bug 2: the heap line breaks only for real gaps in observation, never for GC pauses. */
class GapRuleTest {

  static final long T0 = TestData.T0;

  @BeforeAll
  static void headless() {
    System.setProperty("java.awt.headless", "true");
  }

  /**
   * A GC-thrashing run: normal sampling with long full GCs (the sampler stalls,
   * a GC event covers the stall), then back-to-back 15 s full GCs at a flat
   * 3,000 MB where the JVM recorded only some GC notifications (it drops them
   * when the heap is exhausted), ending in a 75 s stretch without any GC event.
   */
  static TestData.Builder gcThrashRun() {
    TestData.Builder b = new TestData.Builder();
    b.maxMB = 4096;
    long t = T0;
    long used = 200;
    // 0:00 - 4:00: sampling every 250 ms, a 12 s full GC every 30 s
    while (t < T0 + 240_000) {
      long next = t + 250;
      if ((t - T0) % 30_000 < 250 && t > T0) {
        b.gcPause(t + 12_000, used, 12_000);
        next = t + 12_000 + 5;
      }
      b.sample(t, used);
      used = Math.min(3000, used + 5);
      t = next;
    }
    // from 4:00: thrashing, a sample per 15 s full GC; most GC notifications lost
    int k = 0;
    for (; t < T0 + 970_000; t += 15_000, k++) {
      b.sample(t, 3000);
      if (k % 3 == 0) b.gcPause(t + 14_990, 3000, 14_980);
    }
    b.sample(T0 + 971_000, 3000);
    b.sample(T0 + 1_046_000, 3000); // 75 s later, no GC event in between
    return b;
  }

  @Test
  void gcThrashRunIsOneContinuousLine() throws Exception {
    TestData.Builder b = gcThrashRun();
    PointSource src = b.source("thrash");
    GapRule.Result r = GapRule.of(src, 250);
    assertEquals(0, r.breaks(), "every gap of the GC-thrash run is bridged");
    assertTrue(r.bridged() > 20);
    boolean longBridge = false;
    for (GapRule.Gap g : r.gaps) if (g.length() > 60_000 && g.bridged) longBridge = true;
    assertTrue(longBridge, "the 75 s gap without a GC event is bridged");

    PngRenderer.Model m = PngRenderer.model(src, PngOptions.defaults().scale(1));
    for (int i = 1; i < m.seriesLen; i++) {
      assertTrue(m.gaps.style(m.seriesTs[i - 1], m.seriesTs[i]) != GapRule.BREAK, "no break in the PNG line");
    }
    // a flat 3,000 MB plateau from 4:00 to the end
    for (int i = 0; i < m.seriesLen; i++) {
      if (m.seriesTs[i] >= T0 + 250_000) assertEquals(3000, m.seriesMB[i]);
    }
    assertTrue(m.seriesTs[m.seriesLen - 1] >= T0 + 1_046_000, "the line reaches the end of the run");
    assertTrue(m.key.contains("Dashed:"), m.key);
    assertFalse(m.key.contains("Gap:"), m.key);
  }

  @Test
  void sixtySecondGapWithoutGcStillBreaks() throws Exception {
    TestData.Builder b = new TestData.Builder();
    for (long t = 0; t <= 60_000; t += 250) b.sample(T0 + t, 500 + t / 1000);
    for (long t = 120_000; t <= 180_000; t += 250) b.sample(T0 + t, 600);
    GapRule.Result r = GapRule.of(b.source("paused"), 250);
    assertEquals(1, r.gaps.size());
    assertEquals(1, r.breaks());
    assertEquals(T0 + 60_000, r.gaps.get(0).from);
    assertEquals(T0 + 120_000, r.gaps.get(0).to);
    assertEquals(GapRule.BREAK, r.style(T0 + 60_000, T0 + 120_000));

    PngRenderer.Model m = PngRenderer.model(b.source("paused"), PngOptions.defaults().scale(1));
    assertTrue(m.key.contains("Gap: no observation"), m.key);
  }

  @Test
  void forcedGcOfAMarkAndLongGcPausesAreDashedNotBroken() throws Exception {
    TestData.Builder b = new TestData.Builder();
    for (long t = 0; t <= 10_000; t += 250) b.sample(T0 + t, 800);
    // markGc: 3 x System.gc() on a big heap = 4.2 s without samples
    b.add(new DataPoint(b.index++, T0 + 14_200, 700, 900, 8192, DataPoint.Kind.MARK, "1. loaded",
        "com.acme.T.run:1", null, null, null, null, "main", Long.valueOf(4_150), null));
    for (long t = 14_300; t <= 20_000; t += 250) b.sample(T0 + t, 750);
    // a 24 s stop-the-world full GC
    b.gcPause(T0 + 44_000, 600, 23_900);
    for (long t = 44_010; t <= 50_000; t += 250) b.sample(T0 + t, 600);
    GapRule.Result r = GapRule.of(b.source("forced"), 250);
    assertEquals(0, r.breaks());
    assertEquals(GapRule.DASHED, r.style(T0 + 10_000, T0 + 14_200));
    assertEquals(GapRule.DASHED, r.style(T0 + 20_000, T0 + 44_000));
    assertEquals(GapRule.SOLID, r.style(T0 + 44_010, T0 + 44_260));
  }

  @Test
  void gcCountersBridgeGapsWithLostNotifications() throws Exception {
    TestData.Builder b = new TestData.Builder();
    long gc = 1_000;
    for (long t = 0; t <= 10_000; t += 250) b.sampleWithGc(T0 + t, 900, gc);
    gc += 39_500; // 40 s gap, 39.5 s of it in GC, no GC event recorded
    for (long t = 50_000; t <= 60_000; t += 250) b.sampleWithGc(T0 + t, 900, gc);
    GapRule.Result r = GapRule.of(b.source("counters"), 250);
    assertEquals(1, r.gaps.size());
    assertTrue(r.gaps.get(0).bridged);
    assertEquals(39_500, r.gaps.get(0).gcMs);
  }

  @Test
  @SuppressWarnings("unchecked")
  void htmlExportCarriesTheSameGapsAsThePng() throws Exception {
    TestData.Builder b = gcThrashRun();
    for (long t = 1_100_000; t <= 1_110_000; t += 250) b.sample(T0 + t, 2500); // after a 54 s pause
    PointSource src = b.source("thrash");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    HtmlExporter.export(src, null, out);
    Map<String, Object> data = MiniJson.parseObject(HtmlExporterTest.embeddedJson(out.toString("UTF-8")));
    Map<String, Object> gaps = (Map<String, Object>) data.get("gaps");
    List<Object> list = (List<Object>) gaps.get("list");
    PngRenderer.Model m = PngRenderer.model(src, PngOptions.defaults().scale(1));
    assertEquals(m.gaps.gaps.size(), list.size());
    for (int i = 0; i < list.size(); i++) {
      List<Object> g = (List<Object>) list.get(i);
      GapRule.Gap e = m.gaps.gaps.get(i);
      assertEquals(Long.valueOf(e.from), g.get(0));
      assertEquals(Long.valueOf(e.to), g.get(1));
      assertEquals(Long.valueOf(e.bridged ? 1 : 0), g.get(2));
    }
    assertEquals(Long.valueOf(m.gaps.dashAfterMs), gaps.get("dashAfterMs"));
    assertEquals(1, m.gaps.breaks(), "the pause after the run is a real break");
    assertTrue(new String(out.toByteArray(), StandardCharsets.UTF_8).contains("@memprofiler/web/gaps.js"));
  }
}
