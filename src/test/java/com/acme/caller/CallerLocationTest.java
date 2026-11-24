package com.acme.caller;

import com.toro.al.memprofiler.MemoryProfiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug 5: a mark made through a small helper (a project's reflection bridge) must
 * record the real caller, not the helper. Lives outside the profiler's package,
 * like user code.
 */
class CallerLocationTest {

  @TempDir
  File tmp;

  @AfterEach
  void tearDown() {
    MemoryProfiler.shutdown();
    System.clearProperty(MemoryProfiler.SKIP_CALLERS_PROPERTY);
  }

  /** A helper between application code and the profiler. */
  static final class Bridge {
    static void markGc(String label) {
      MemoryProfiler.markGc(label);
    }

    static void markGcSkippingMe(String label) {
      MemoryProfiler.markGc(label, 1);
    }

    /** Reflection, so the calling code compiles without the profiler jar. */
    static void markByReflection(String label) throws Exception {
      Class.forName("com.toro.al.memprofiler.MemoryProfiler")
          .getMethod("markGc", String.class, int.class).invoke(null, label, 1);
    }
  }

  private MemoryProfiler.Config quiet(File jsonl) {
    return MemoryProfiler.Config.defaults().startServer(false).openBrowser(false).gcListener(false)
        .intervalMs(1000).forceGc(1, 0).jsonl(jsonl.getPath());
  }

  private static List<String> markLocations(File jsonl) throws Exception {
    List<String> out = new ArrayList<String>();
    Pattern loc = Pattern.compile("\"kind\":\"MARK\".*?\"location\":\"([^\"]+)\"");
    for (String l : Files.readAllLines(jsonl.toPath(), StandardCharsets.UTF_8)) {
      Matcher m = loc.matcher(l);
      if (m.find()) out.add(m.group(1));
    }
    return out;
  }

  @Test
  void markLocationSkipsHelpers() throws Exception {
    File jsonl = new File(tmp, "marks.jsonl");
    MemoryProfiler.start(quiet(jsonl));
    Bridge.markGc("1. plain");
    Bridge.markGcSkippingMe("2. extra frames");
    Bridge.markByReflection("3. reflection");
    System.setProperty(MemoryProfiler.SKIP_CALLERS_PROPERTY, "x.y.Unused;" + Bridge.class.getName());
    Bridge.markGc("4. system property");
    MemoryProfiler.shutdown();

    List<String> locs = markLocations(jsonl);
    assertEquals(4, locs.size());
    String self = CallerLocationTest.class.getName() + ".markLocationSkipsHelpers:";
    assertTrue(locs.get(0).startsWith(Bridge.class.getName() + ".markGc:"), "without help: " + locs.get(0));
    assertTrue(locs.get(1).startsWith(self), locs.get(1));
    assertTrue(locs.get(2).startsWith(self), locs.get(2));
    assertTrue(locs.get(3).startsWith(self), locs.get(3));
  }

  @Test
  void configSkipsCallerPrefixes() throws Exception {
    File jsonl = new File(tmp, "config.jsonl");
    MemoryProfiler.start(quiet(jsonl).skipCallerPrefixes(Bridge.class.getName()));
    Bridge.markGc("1. config");
    MemoryProfiler.shutdown();
    assertTrue(markLocations(jsonl).get(0).startsWith(CallerLocationTest.class.getName() + ".configSkipsCallerPrefixes:"));
  }
}
