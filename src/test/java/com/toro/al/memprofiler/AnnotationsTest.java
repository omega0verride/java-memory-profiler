package com.toro.al.memprofiler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotationsTest {

  private static final Annotations.TextMeasurer SEVEN_PX = new Annotations.TextMeasurer() {
    public double width(String text) { return text.length() * 7.0; }
  };

  @Test
  void numbersComeFromTheLabelOrThePosition() {
    TestData.Builder b = new TestData.Builder();
    b.mark(1000, 10, "7. DB cache: all rows in one batch (peak)");
    b.mark(2000, 30, "baseline");
    b.mark(3000, 25, "6. progress 25%");
    b.mark(4000, 25, "6. progress 50%");
    b.mark(5000, 40, "7.5 GB loaded");
    b.mark(6000, 40, "007. padded");
    b.mark(7000, 40, "12.");
    List<Annotations.Row> rows = Annotations.rows(b.points, 0);
    // unnumbered points continue after the highest explicit number (12)
    assertEquals(Arrays.asList("7", "13", "6", "6", "14", "7", "12"), badges(rows));
    assertEquals("DB cache: all rows in one batch (peak)", rows.get(0).text);
    assertEquals("baseline", rows.get(1).text);
    assertEquals("7.5 GB loaded", rows.get(4).text);
    assertEquals("(mark)", rows.get(6).text);
    assertNull(rows.get(0).deltaPrevMB);
    assertEquals(Long.valueOf(20), rows.get(1).deltaPrevMB);
    assertEquals(Long.valueOf(-5), rows.get(2).deltaPrevMB);
    assertEquals(2000, rows.get(1).elapsedMs);
  }

  @Test
  void withoutNumberedLabelsBadgesArePositions() {
    TestData.Builder b = new TestData.Builder();
    b.mark(1000, 10, "baseline");
    b.scopeStart(1500, 10, "load", "s1");
    b.scopeEnd(1600, 10, "load", "s1", 0, 100);
    b.mark(2000, 10, "after");
    assertEquals(Arrays.asList("1", "2", "3", "4"), badges(Annotations.rows(b.points, 0)));
  }

  @Test
  void shortLocationDropsThePackage() {
    assertEquals("Loader.load:42", Annotations.shortLocation("com.acme.Loader.load:42"));
    assertEquals("Outer$Inner.run", Annotations.shortLocation("a.b.Outer$Inner.run"));
    assertEquals("Main.main:3", Annotations.shortLocation("Main.main:3"));
    assertEquals("unknown", Annotations.shortLocation("unknown"));
    assertEquals("", Annotations.shortLocation(null));
  }

  @Test
  void pairsScopesIntoBands() {
    TestData.Builder b = new TestData.Builder();
    b.scopeEnd(500, 1, "evicted start", "s0", 0, 0);
    b.scopeStart(1000, 1, "outer", "s1");
    b.scopeStart(1100, 1, "inner", "s2");
    b.scopeEnd(1200, 1, "inner", "s2", 0, 100);
    b.scopeEnd(2000, 1, "outer", "s1", 0, 1000);
    b.scopeStart(3000, 1, "still open", "s3");
    List<Annotations.Band> bands = Annotations.bands(b.points, 0, 9000);
    assertEquals(4, bands.size());
    assertEquals("evicted start", bands.get(0).label);
    assertEquals(0, bands.get(0).startTs);
    assertTrue(bands.get(0).open);
    assertEquals("outer", bands.get(1).label);
    assertFalse(bands.get(1).open);
    assertEquals(2000, bands.get(1).endTs);
    assertEquals("inner", bands.get(2).label);
    assertEquals(9000, bands.get(3).endTs);
    assertTrue(bands.get(3).open);
  }

  @Test
  void badgesForCloseMarksStackWithoutOverlapping() {
    // 14 marks on a 9-minute axis, 3, 4 and 5 within 10 seconds of each other
    Annotations.Rect plot = new Annotations.Rect(74, 104, 1298, 640);
    long span = 9 * 60_000;
    long[] at = {2_000, 15_000, 50_000, 54_000, 59_500, 100_000, 130_000, 160_000, 225_000, 275_000,
        350_000, 425_000, 475_000, 525_000};
    // 3, 4 and 5 also sit at almost the same height, so their badges must stack
    double[] mb = {476, 1383, 3900, 3920, 3944, 3822, 3358, 3629, 936, 947, 1057, 1030, 1060, 1085};
    List<Annotations.BadgeIn> in = new ArrayList<Annotations.BadgeIn>();
    for (int i = 0; i < at.length; i++) {
      double x = plot.x + at[i] / (double) span * plot.w;
      double y = plot.y + (1 - mb[i] / 10240) * plot.h;
      in.add(new Annotations.BadgeIn(x, y, (i + 1 >= 10 ? 14 : 7) + 2 * Annotations.BADGE_PAD_X));
    }
    List<Annotations.BadgePlacement> out = Annotations.layoutBadges(in, plot, null);
    assertNoOverlapInside(out, plot, 0);
    // the cluster stacked upward with leader lines; the other badges sit above their dots
    assertTrue(out.get(3).leader && out.get(4).leader);
    assertTrue(out.get(3).rect.y < out.get(2).rect.y && out.get(4).rect.y < out.get(3).rect.y);
    assertFalse(out.get(0).leader);
  }

  @Test
  void crowdedBadgesAreHiddenRatherThanOverlapping() {
    Annotations.Rect plot = new Annotations.Rect(0, 0, 300, 120);
    List<Annotations.BadgeIn> in = new ArrayList<Annotations.BadgeIn>();
    Random rnd = new Random(3);
    for (int i = 0; i < 200; i++) in.add(new Annotations.BadgeIn(rnd.nextDouble() * 300, rnd.nextDouble() * 120, 24));
    List<Annotations.Rect> obs = new ArrayList<Annotations.Rect>();
    obs.add(new Annotations.Rect(200, 10, 90, 16));
    List<Annotations.BadgePlacement> out = Annotations.layoutBadges(in, plot, obs);
    int hidden = assertNoOverlapInside(out, plot, 0);
    assertTrue(hidden > 0, "200 badges cannot all fit");
    for (Annotations.BadgePlacement b : out) {
      if (!b.hidden) assertFalse(b.rect.overlaps(obs.get(0)), "badge on top of a reference label");
    }
  }

  static int assertNoOverlapInside(List<Annotations.BadgePlacement> out, Annotations.Rect plot, int expectHiddenMax) {
    int hidden = 0;
    for (int i = 0; i < out.size(); i++) {
      Annotations.BadgePlacement a = out.get(i);
      if (a.hidden) { hidden++; continue; }
      assertTrue(a.rect.x >= plot.x - 1e-6 && a.rect.right() <= plot.right() + 1e-6, "badge " + i + " leaves the plot horizontally");
      assertTrue(a.rect.y >= plot.y - 1e-6 && a.rect.bottom() <= plot.bottom() + 1e-6, "badge " + i + " leaves the plot vertically");
      for (int j = 0; j < i; j++) {
        Annotations.BadgePlacement b = out.get(j);
        if (!b.hidden) assertFalse(a.rect.overlaps(b.rect), "badges " + j + " and " + i + " overlap");
      }
    }
    if (expectHiddenMax >= 0 && plot.w > 1000) assertEquals(expectHiddenMax, hidden, "badges hidden");
    return hidden;
  }

  @Test
  void bandTitlesAlternateRowsAndNeverOverlap() {
    List<Annotations.TitleIn> in = new ArrayList<Annotations.TitleIn>();
    // a wide band, then 30 narrow adjacent ones, then one at the right edge
    in.add(new Annotations.TitleIn(100, 600, "phase-1 live load"));
    for (int i = 0; i < 30; i++) {
      double x = 620 + i * 12;
      in.add(new Annotations.TitleIn(x, x + 11, "narrow scope number " + i));
    }
    in.add(new Annotations.TitleIn(1380, 1500, "edge band with a long title that must be cut"));
    double left = 74, right = 1372;
    List<Annotations.TitlePlacement> out = Annotations.layoutBandTitles(in, left, right, SEVEN_PX);
    for (int i = 0; i < out.size(); i++) {
      Annotations.TitlePlacement a = out.get(i);
      if (a.hidden) continue;
      assertTrue(a.x >= left - 1e-6 && a.x + a.w <= right + 1e-6, "title " + i + " leaves the plot");
      assertEquals(a.w, SEVEN_PX.width(a.text) + 2 * Annotations.TITLE_PAD_X, 1e-6);
      for (int j = 0; j < i; j++) {
        Annotations.TitlePlacement b = out.get(j);
        if (b.hidden || b.row != a.row) continue;
        assertTrue(a.x >= b.x + b.w || b.x >= a.x + a.w, "titles " + j + " and " + i + " overlap");
      }
    }
    assertFalse(out.get(0).hidden);
    assertEquals(0, out.get(0).row);
    assertFalse(out.get(1).hidden);
    assertFalse(out.get(2).hidden);
    assertTrue(out.get(1).row != out.get(2).row, "adjacent narrow bands alternate rows");
    assertTrue(out.get(2).text.endsWith("…") || out.get(2).text.length() <= 20);
  }

  @Test
  void fitCutsWithEllipsis() {
    assertEquals("abc", Annotations.fit("abc", 100, SEVEN_PX));
    String cut = Annotations.fit("abcdefghijkl", 50, SEVEN_PX);
    assertTrue(cut.endsWith("…"));
    assertTrue(SEVEN_PX.width(cut) + 2 * Annotations.TITLE_PAD_X <= 50);
    assertNull(Annotations.fit("abcdef", 10, SEVEN_PX));
  }

  private static List<String> badges(List<Annotations.Row> rows) {
    List<String> out = new ArrayList<String>();
    for (Annotations.Row r : rows) out.add(r.badge);
    return out;
  }
}
