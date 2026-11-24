package com.toro.al.memprofiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportCliTest {

  @TempDir
  File tmp;

  @Test
  void exportsHtmlAndPngFromJsonl() throws Exception {
    System.setProperty("java.awt.headless", "true");
    File jsonl = new File(tmp, "run.jsonl");
    DataStore store = new DataStore(1_000_000);
    store.enableJsonl(jsonl.getPath());
    for (DataPoint p : TestData.nineMinuteRun().points) store.add(p);
    store.close();

    File html = new File(tmp, "out/profile.html");
    File png = new File(tmp, "out/profile.png");
    ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
    int code = ExportCli.run(new String[] {"--jsonl", jsonl.getPath(), "--html", html.getPath(), "--png", png.getPath(),
        "--title", "CLI test", "--width", "1000", "--scale", "1", "--include-table", "--from-mark", "3", "--to-mark", "6",
        "--theme", "dark", "--run", "last"}, new PrintStream(out), new PrintStream(err));
    assertEquals(0, code, err.toString());
    assertTrue(html.length() > 100_000);
    assertTrue(png.length() > 10_000);
    assertTrue(out.toString().contains("HTML written to"));
  }

  @Test
  void rejectsBadArguments() {
    ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
    assertEquals(2, ExportCli.run(new String[] {"--html", "x.html"}, new PrintStream(out), new PrintStream(err)));
    assertTrue(err.toString().contains("--jsonl is required"));
    assertEquals(2, ExportCli.run(new String[] {"--jsonl", "nope.jsonl", "--png", "x.png", "--bogus"},
        new PrintStream(out), new PrintStream(err)));
    assertEquals(0, ExportCli.run(new String[] {"--help"}, new PrintStream(out), new PrintStream(err)));
  }
}
