package com.toro.al.memprofiler;

import java.util.ArrayList;
import java.util.List;

/**
 * A class whose source is not on the test classpath, so "View Source" has to
 * decompile it; {@link #run()} returns the source lines of its two "marks".
 */
final class SourceFixture {

  private SourceFixture() {}

  /** Stands in for a mark: returns the caller's source line. */
  static int record(String label) {
    return new Throwable().getStackTrace()[1].getLineNumber();
  }

  static int[] run() {
    int first = record("first-mark");
    List<String> filler = new ArrayList<String>();
    for (int i = 0; i < 3; i++) {
      filler.add("item " + i);
    }
    int second = record("second-mark " + filler.size());
    return new int[] { first, second };
  }
}
