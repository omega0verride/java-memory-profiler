package com.acme.caller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Improvement B: code that compiles only against memprofiler-api forwards to the
 * profiler at runtime, and the recorded location is the real caller.
 */
class ApiFacadeTest {

  @TempDir
  File tmp;

  @AfterEach
  void tearDown() {
    com.toro.al.memprofiler.MemoryProfiler.shutdown();
  }

  @Test
  void forwardsToTheProfilerWithTheCallersLocation() throws Exception {
    File jsonl = new File(tmp, "api.jsonl");
    com.toro.al.memprofiler.MemoryProfiler.start(com.toro.al.memprofiler.MemoryProfiler.Config.defaults()
        .startServer(false).openBrowser(false).gcListener(false).forceGc(1, 0).jsonl(jsonl.getPath()));

    // what instrumented library code looks like: only the API is imported
    assertTrue(com.toro.al.memprofiler.api.MemoryProfiler.isRunning());
    com.toro.al.memprofiler.api.MemoryProfiler.markGc("1. via api");
    try (com.toro.al.memprofiler.api.MemoryProfiler.Scope s = com.toro.al.memprofiler.api.MemoryProfiler.scope("load")) {
      com.toro.al.memprofiler.api.MemoryProfiler.mark("2. inside");
    }
    com.toro.al.memprofiler.MemoryProfiler.shutdown();

    String text = new String(Files.readAllBytes(jsonl.toPath()), StandardCharsets.UTF_8);
    String self = "\"location\":\"" + ApiFacadeTest.class.getName() + ".forwardsToTheProfilerWithTheCallersLocation:";
    assertTrue(text.contains("\"label\":\"1. via api\""), text);
    for (String kind : new String[] {"MARK", "SCOPE_START", "SCOPE_END"}) {
      int at = text.indexOf("\"kind\":\"" + kind + "\"");
      assertTrue(at > 0, kind);
      int eol = text.indexOf('\n', at);
      assertTrue(text.substring(at, eol).contains(self), kind + ": " + text.substring(at, eol));
    }
    assertTrue(!text.contains("memprofiler.api.MemoryProfiler."), "no API frame is ever the location");
  }
}
