package com.toro.al.memprofiler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MemoryProfiler#help()} and {@link MemoryProfiler#printHelp()}. */
class HelpTest {

  @TempDir
  File tmp;

  @AfterEach
  void tearDown() {
    MemoryProfiler.shutdown();
  }

  @Test
  void helpNamesOnlyRealCalls() {
    String help = MemoryProfiler.help();
    Set<String> statics = new HashSet<String>();
    for (Method m : MemoryProfiler.class.getMethods()) {
      if (Modifier.isStatic(m.getModifiers())) statics.add(m.getName());
    }
    Matcher calls = Pattern.compile("MemoryProfiler\\.(\\w+)\\(").matcher(help);
    int n = 0;
    while (calls.find()) {
      n++;
      assertTrue(statics.contains(calls.group(1)), "help names MemoryProfiler." + calls.group(1) + "(...), which does not exist");
    }
    assertTrue(n >= 10, "only " + n + " calls in the help");

    // every Config setter is listed (read by reflection), among them the newest
    for (String option : MemoryProfiler.configOptions()) assertTrue(help.contains(option), option);
    assertTrue(help.contains("sourceOpener"));
    assertTrue(help.contains("jsonlMode"));
    assertTrue(help.contains(MemoryProfiler.version()));
    assertTrue(help.contains(GithubLink.URL));
  }

  @Test
  void helpShowsTheStatus() {
    assertTrue(MemoryProfiler.help().contains("Status: not running"), MemoryProfiler.help());

    File jsonl = new File(tmp, "run.jsonl");
    MemoryProfiler.start(c -> c.startServer(false).jsonl(jsonl.getPath()).intervalMs(50));
    String running = MemoryProfiler.help();
    assertTrue(running.contains("Status: sampling, no live UI"), running);
    assertTrue(running.contains("JSONL " + jsonl.getPath()), running);

    MemoryProfiler.stop();
    assertTrue(MemoryProfiler.help().contains("Status: stopped"), MemoryProfiler.help());
  }

  @Test
  void printHelpPrintsTheHelp() throws Exception {
    assertEquals(MemoryProfiler.help(), captureOut(new ThrowingRunnable() {
      public void run() { MemoryProfiler.printHelp(); }
    }));
  }

  @Test
  void commandLineHelp() throws Exception {
    for (final String flag : new String[] { "--help", "-h", "help" }) {
      String out = captureOut(new ThrowingRunnable() {
        public void run() throws Exception { Cli.main(new String[] { flag }); }
      });
      assertEquals(Cli.USAGE, out, flag);
      assertTrue(out.contains("  api "), out);
    }
    for (final String[] args : new String[][] { { "api" }, { "help", "api" } }) {
      String out = captureOut(new ThrowingRunnable() {
        public void run() throws Exception { Cli.main(args); }
      });
      assertEquals(MemoryProfiler.help(), out, String.join(" ", args));
    }
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static String captureOut(ThrowingRunnable r) throws Exception {
    PrintStream old = System.out;
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    System.setOut(new PrintStream(bytes, true, "UTF-8"));
    try {
      r.run();
    } finally {
      System.setOut(old);
    }
    return bytes.toString("UTF-8");
  }
}
