package com.toro.al.memprofiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "View Source" and "Open in IDE" land on the mark's line. */
class SourceNavigationTest {

  private static final String FIXTURE = SourceFixture.class.getName();
  private static final String FIXTURE_FILE = "com/toro/al/memprofiler/SourceFixture.java";
  private static final String[] TEST_ROOTS = { "src/test/java" };

  @TempDir
  File tmp;

  // ---- View Source ----

  @Test
  void decompiledSourceHighlightsTheStatementOfTheMarkLine() throws Exception {
    int[] marks = SourceFixture.run();
    for (int i = 0; i < marks.length; i++) {
      ClassSourceService.SourceResult r = ClassSourceService.getSource(FIXTURE, FIXTURE_FILE, marks[i], null);
      assertEquals("decompiled", r.origin);
      assertNull(r.file);
      assertEquals(marks[i], r.sourceLine);
      assertTrue(r.line > 0, "line " + marks[i] + " not found in the decompiled code");
      String text = r.source.split("\n", -1)[r.line - 1];
      assertTrue(text.contains(i == 0 ? "first-mark" : "second-mark"), "highlighted: " + text);
    }
  }

  @Test
  void decompiledLineWithoutCodeIsNotHighlighted() throws Exception {
    // line 1 is the package declaration: no bytecode
    ClassSourceService.SourceResult r = ClassSourceService.getSource(FIXTURE, FIXTURE_FILE, 1, null);
    assertEquals("decompiled", r.origin);
    assertEquals(0, r.line);
  }

  @Test
  void realSourceUnderSourceRootsKeepsTheLine() throws Exception {
    int second = SourceFixture.run()[1];
    ClassSourceService.SourceResult r = ClassSourceService.getSource(FIXTURE, FIXTURE_FILE, second, TEST_ROOTS);
    assertEquals("source", r.origin);
    assertEquals(FIXTURE_FILE, r.file);
    assertEquals(second, r.line);
    assertTrue(r.source.split("\n", -1)[second - 1].contains("record(\"second-mark "));
    assertTrue(r.toJson().contains("\"line\":" + second));
  }

  @Test
  void sourceFileDefaultsToTheOuterClassFile() {
    assertEquals("com/acme/Outer.java", ClassSourceService.defaultSourcePath("com.acme.Outer$Inner$1"));
    assertEquals("Top.java", ClassSourceService.defaultSourcePath("Top"));
  }

  @Test
  void classSourceEndpointTakesFileAndLine() throws Exception {
    int second = SourceFixture.run()[1];
    int port = freePort();
    ProfilerServer server = new ProfilerServer(port, new DataStore(100), null, null, TEST_ROOTS);
    server.start();
    try {
      String q = "/class/source?class=" + enc(FIXTURE) + "&file=" + enc(FIXTURE_FILE) + "&line=" + second;
      String json = new String(read(get(port, q)), StandardCharsets.UTF_8);
      assertTrue(json.contains("\"origin\":\"source\""), json);
      assertTrue(json.contains("\"line\":" + second), json);

      // without a file under the roots: decompiled, with the mapped line
      String q2 = "/class/source?class=" + enc(FIXTURE) + "&file=" + enc("missing/Nowhere.java") + "&line=" + second;
      String json2 = new String(read(get(port, q2)), StandardCharsets.UTF_8);
      assertTrue(json2.contains("\"origin\":\"decompiled\""), json2);
      assertTrue(json2.contains("\"sourceLine\":" + second), json2);
      assertTrue(!json2.contains("\"line\":0"), json2);
    } finally {
      server.stop();
    }
  }

  // ---- Open in IDE ----

  @Test
  void ideCommandsCarryTheLine() {
    String f = "/work/My Project/src/Foo.java";
    assertEquals(Arrays.asList("/opt/idea/bin/idea.sh", "--line", "42", f),
        SourceOpener.command(SourceOpener.Kind.INTELLIJ, "/opt/idea/bin/idea.sh", null, f, 42));
    assertEquals(Arrays.asList("code", "--goto", f + ":42"),
        SourceOpener.command(SourceOpener.Kind.VSCODE, "code", null, f, 42));
    assertEquals(Arrays.asList("code", f), SourceOpener.command(SourceOpener.Kind.VSCODE, "code", null, f, 0));
    assertEquals(Arrays.asList("/opt/Sublime Text/subl", f + ":42"),
        SourceOpener.command(SourceOpener.Kind.COMMAND, null, "\"/opt/Sublime Text/subl\" {file}:{line}", f, 42));
    assertEquals(Arrays.asList("emacsclient", "+1", f),
        SourceOpener.command(SourceOpener.Kind.COMMAND, null, "emacsclient +{line} {file}", f, 0));
  }

  @Test
  void openerNamesAndValidation() {
    assertEquals(SourceOpener.Kind.INTELLIJ, SourceOpener.parseKind("IntelliJ"));
    assertEquals(SourceOpener.Kind.INTELLIJ, SourceOpener.parseKind("idea"));
    assertEquals(SourceOpener.Kind.VSCODE, SourceOpener.parseKind("code"));
    assertEquals(SourceOpener.Kind.AUTO, SourceOpener.parseKind(""));
    assertEquals(SourceOpener.Kind.COMMAND, new SourceOpener("subl {file}:{line}", null).kind());
    assertThrows(IllegalArgumentException.class, () -> MemoryProfiler.Config.defaults().sourceOpener("notepad"));
    MemoryProfiler.Config.defaults().sourceOpener("vscode").sourceOpener("notepad {file}");

    String old = System.getProperty(SourceOpener.PROPERTY);
    try {
      System.setProperty(SourceOpener.PROPERTY, "vscode");
      assertEquals(SourceOpener.Kind.VSCODE, new SourceOpener(null, null).kind());
      assertEquals(SourceOpener.Kind.SYSTEM, new SourceOpener("system", null).kind());
      System.setProperty(SourceOpener.PROPERTY, "nonsense");
      assertEquals(SourceOpener.Kind.AUTO, new SourceOpener(null, null).kind());
    } finally {
      if (old == null) System.clearProperty(SourceOpener.PROPERTY);
      else System.setProperty(SourceOpener.PROPERTY, old);
    }
  }

  @Test
  void openEndpointLaunchesTheOpenerWithFileAndLine() throws Exception {
    int second = SourceFixture.run()[1];
    File out = new File(tmp, "opened.txt");
    String java = new File(System.getProperty("java.home"), "bin/java").getAbsolutePath();
    String classes = new File(RecordArgs.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
    String template = "\"" + java + "\" -cp \"" + classes + "\" " + RecordArgs.class.getName()
        + " \"" + out.getAbsolutePath() + "\" {file} {line}";

    int port = freePort();
    ProfilerServer server = new ProfilerServer(port, new DataStore(100), null, null, TEST_ROOTS);
    server.setSourceOpener(new SourceOpener(template, null));
    server.start();
    try {
      HttpURLConnection c = get(port, "/open?path=" + enc(FIXTURE_FILE + ":" + second));
      String body = new String(read(c), StandardCharsets.UTF_8);
      assertEquals(200, c.getResponseCode(), body);
      assertTrue(body.startsWith("Opened SourceFixture.java:" + second), body);

      long deadline = System.currentTimeMillis() + 20000;
      while (!(out.isFile() && out.length() > 0) && System.currentTimeMillis() < deadline) Thread.sleep(50);
      List<String> args = Files.readAllLines(out.toPath(), StandardCharsets.UTF_8);
      assertEquals(new File("src/test/java", FIXTURE_FILE).getAbsolutePath(), args.get(0));
      assertEquals(String.valueOf(second), args.get(1));

      // a missing file is reported, not launched
      HttpURLConnection miss = get(port, "/open?path=" + enc("com/acme/Missing.java:3"));
      String missBody = new String(read(miss), StandardCharsets.UTF_8);
      assertEquals(404, miss.getResponseCode());
      assertTrue(missBody.contains("searched: src/test/java"), missBody);
    } finally {
      server.stop();
    }
  }

  /** The "IDE" of {@link #openEndpointLaunchesTheOpenerWithFileAndLine}: writes its arguments to a file. */
  public static final class RecordArgs {
    public static void main(String[] args) throws Exception {
      File target = new File(args[0]);
      File part = new File(target.getPath() + ".part");
      Files.write(part.toPath(), Arrays.asList(Arrays.copyOfRange(args, 1, args.length)), StandardCharsets.UTF_8);
      if (!part.renameTo(target)) throw new IllegalStateException("rename failed");
    }
  }

  // ---- helpers ----

  private static String enc(String s) throws Exception {
    return URLEncoder.encode(s, "UTF-8");
  }

  private static int freePort() throws Exception {
    ServerSocket s = new ServerSocket(0);
    try {
      return s.getLocalPort();
    } finally {
      s.close();
    }
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
}
