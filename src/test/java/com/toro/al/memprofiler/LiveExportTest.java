package com.toro.al.memprofiler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The profiler API and HTTP endpoints, against a real (short) profiling session. */
class LiveExportTest {

  @TempDir
  File tmp;

  @AfterEach
  void tearDown() {
    MemoryProfiler.shutdown();
  }

  @Test
  @SuppressWarnings("unchecked")
  void exportsWhileRunningAfterStopAndOnShutdown() throws Exception {
    System.setProperty("java.awt.headless", "true");
    int port = freePort();
    File onStopHtml = new File(tmp, "stop.html");
    File onStopPng = new File(tmp, "stop.png");
    File jsonl = new File(tmp, "run.jsonl");
    MemoryProfiler.start(MemoryProfiler.Config.defaults()
        .port(port).openBrowser(false).intervalMs(50).jsonl(jsonl.getPath())
        .title("Live test").exportHtmlOnStop(onStopHtml.getPath())
        .exportPngOnStop(onStopPng.getPath(), PngOptions.defaults().scale(1).includeTable(true)));
    MemoryProfiler.referenceLine(64, "Budget (64 MB)");
    MemoryProfiler.markGc("1. start");
    byte[][] keep = new byte[8][];
    try (MemoryProfiler.Scope s = MemoryProfiler.scope("allocate")) {
      for (int i = 0; i < keep.length; i++) {
        keep[i] = new byte[1024 * 1024];
        Thread.sleep(30);
      }
    }
    MemoryProfiler.mark("2. allocated " + keep.length + " MB");
    Thread.sleep(200);

    // Java API while running
    String html = MemoryProfiler.exportHtml(new File(tmp, "live.html").getPath());
    String png = MemoryProfiler.exportPng(new File(tmp, "live.png").getPath());
    assertTrue(new File(html).length() > 100_000);
    assertTrue(new File(png).length() > 1000);
    Map<String, Object> data = MiniJson.parseObject(
        HtmlExporterTest.embeddedJson(new String(java.nio.file.Files.readAllBytes(new File(html).toPath()), StandardCharsets.UTF_8)));
    assertEquals(1, ((List<Object>) data.get("references")).size());
    assertEquals("Live test", ((Map<String, Object>) data.get("meta")).get("title"));

    // HTTP endpoints
    HttpURLConnection c = get(port, "/export/html");
    assertEquals(200, c.getResponseCode());
    assertTrue(c.getHeaderField("Content-Disposition").matches("attachment; filename=\"profile-.+-\\d{8}-\\d{6}\\.html\""));
    assertTrue(read(c).length > 100_000);

    c = get(port, "/export/png?width=900&scale=1&fromMark=1&toMark=2&theme=dark&includeTable=true&title=From%20HTTP");
    assertEquals(200, c.getResponseCode());
    assertEquals("image/png", c.getHeaderField("Content-Type"));
    byte[] img = read(c);
    assertEquals((byte) 0x89, img[0]);
    assertEquals('P', img[1]);

    c = get(port, "/export/png?fromMark=42");
    assertEquals(400, c.getResponseCode());

    c = get(port, "/data");
    String json = new String(read(c), StandardCharsets.UTF_8);
    assertTrue(json.contains("\"references\":[{"), "references are served to the live UI");
    assertTrue(!json.contains("\"kind\":\"REFERENCE\"}") || json.indexOf("\"points\"") > json.indexOf("REFERENCE"),
        "reference lines are not mixed into the points");

    // stop() writes the on-stop exports; shutdown() does not write them again without new data
    MemoryProfiler.stop();
    assertTrue(onStopHtml.isFile() && onStopPng.isFile());
    long stamp = onStopHtml.lastModified();
    String afterStop = MemoryProfiler.exportHtml(new File(tmp, "after-stop.html").getPath());
    assertTrue(new File(afterStop).isFile());
    Thread.sleep(1100);
    MemoryProfiler.shutdown();
    assertEquals(stamp, onStopHtml.lastModified());

    assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
      public void execute() {
        MemoryProfiler.exportHtml("x.html");
      }
    });

    // the JSONL carries the reference line, so an offline export shows it too
    JsonlSource src = JsonlSource.open(jsonl, "last");
    assertEquals(1, src.references().size());
    assertEquals(64, src.references().get(0).usedMB);
  }

  private static HttpURLConnection get(int port, String path) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL("http://localhost:" + port + path).openConnection();
    c.setConnectTimeout(5000);
    c.setReadTimeout(20000);
    return c;
  }

  private static byte[] read(HttpURLConnection c) throws Exception {
    InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    in.close();
    return out.toByteArray();
  }

  private static int freePort() throws Exception {
    ServerSocket s = new ServerSocket(0);
    try {
      return s.getLocalPort();
    } finally {
      s.close();
    }
  }
}
