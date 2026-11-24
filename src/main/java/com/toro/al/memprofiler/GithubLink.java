package com.toro.al.memprofiler;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;

/**
 * The GitHub mark and repository name drawn in the title row of PNG exports,
 * right-aligned so it never sits over the plot. The HTML pages carry the same
 * link in their headers (viewer.html, compare.html, layout.html, source.html).
 */
final class GithubLink {

  private GithubLink() {}

  static final String REPO = "omega0verride/java-memory-profiler";
  static final String URL = "https://github.com/" + REPO;

  static final Font FONT = PngRenderer.F_KEY;
  static final double ICON = 14;
  static final double GAP = 6;
  /** Space kept between the end of the title and the link. */
  static final double MARGIN = 20;
  /** Below this share of the header row left for the title, only the mark is drawn. */
  static final double MIN_TITLE_SHARE = 0.55;

  /** Octicon "mark-github" (16 x 16), arcs converted to cubics so Java2D can draw it. */
  private static final Path2D.Double MARK = parse(
      "M 8 0 C 12.42 0 16 3.58 16 8 C 15.999 11.437 13.806 14.491 10.55 15.59 C 10.15 15.67 10 15.42 10 15.21"
      + " C 10 14.94 10.01 14.08 10.01 13.01 C 10.01 12.26 9.76 11.78 9.47 11.53 C 11.25 11.33 13.12 10.65 13.12 7.58"
      + " C 13.12 6.7 12.81 5.99 12.3 5.43 C 12.38 5.23 12.66 4.41 12.22 3.31 C 12.22 3.31 11.55 3.09 10.02 4.13"
      + " C 9.38 3.95 8.7 3.86 8.02 3.86 C 7.34 3.86 6.66 3.95 6.02 4.13 C 4.49 3.1 3.82 3.31 3.82 3.31"
      + " C 3.38 4.41 3.66 5.23 3.74 5.43 C 3.23 5.99 2.92 6.71 2.92 7.58 C 2.92 10.64 4.78 11.33 6.56 11.53"
      + " C 6.33 11.73 6.12 12.08 6.05 12.6 C 5.59 12.81 4.44 13.15 3.72 11.94 C 3.57 11.7 3.12 11.11 2.49 11.12"
      + " C 1.82 11.13 2.22 11.5 2.5 11.65 C 2.84 11.84 3.23 12.55 3.32 12.78 C 3.48 13.23 4 14.09 6.01 13.72"
      + " C 6.01 14.39 6.02 15.02 6.02 15.21 C 6.02 15.42 5.87 15.66 5.47 15.59 C 2.202 14.502 0 11.444 0 8"
      + " C 0 3.58 3.58 0 8 0 Z");

  /** True when the repository name fits next to the mark in a header row {@code rowW} wide. */
  static boolean showName(double rowW) {
    return rowW - fullWidth() - MARGIN >= rowW * MIN_TITLE_SHARE;
  }

  /** Width to take off the title's fit width in a header row {@code rowW} wide. */
  static double reserved(double rowW) {
    return (showName(rowW) ? fullWidth() : ICON) + MARGIN;
  }

  /**
   * Draws the link right-aligned at {@code right}, vertically centred on {@code midY}.
   * {@code rowW} is the header row width, used to decide whether the name fits.
   */
  static void draw(Graphics2D g, double right, double midY, double rowW, Color color) {
    boolean name = showName(rowW);
    double x = right - (name ? fullWidth() : ICON);
    g.setColor(color);
    AffineTransform t = AffineTransform.getTranslateInstance(x, midY - ICON / 2);
    t.scale(ICON / 16, ICON / 16);
    g.fill(MARK.createTransformedShape(t));
    if (name) {
      g.setFont(FONT);
      g.drawString(REPO, (float) (x + ICON + GAP), (float) (midY + capHeight(FONT) / 2));
    }
  }

  /** Height of a capital letter, for centring text on a line. */
  static double capHeight(Font f) {
    return f.createGlyphVector(PngRenderer.FRC, "H").getVisualBounds().getHeight();
  }

  private static double fullWidth() {
    return ICON + GAP + PngRenderer.width(FONT, REPO);
  }

  /** Parses absolute M / L / C / Z commands separated by spaces. */
  static Path2D.Double parse(String d) {
    Path2D.Double p = new Path2D.Double();
    String[] t = d.trim().split("\\s+");
    int i = 0;
    while (i < t.length) {
      String c = t[i++];
      if ("M".equals(c)) {
        p.moveTo(n(t[i]), n(t[i + 1]));
        i += 2;
      } else if ("L".equals(c)) {
        p.lineTo(n(t[i]), n(t[i + 1]));
        i += 2;
      } else if ("C".equals(c)) {
        p.curveTo(n(t[i]), n(t[i + 1]), n(t[i + 2]), n(t[i + 3]), n(t[i + 4]), n(t[i + 5]));
        i += 6;
      } else if ("Z".equals(c)) {
        p.closePath();
      } else {
        throw new IllegalArgumentException("unsupported path command: " + c);
      }
    }
    return p;
  }

  private static double n(String s) {
    return Double.parseDouble(s);
  }
}
