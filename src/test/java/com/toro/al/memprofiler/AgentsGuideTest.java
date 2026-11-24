package com.toro.al.memprofiler;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AGENTS.md ships in the jar and names methods, flags, agent arguments and endpoints
 * without repeating their docs. These checks fail when one of them goes away, so the
 * guide cannot silently go stale.
 */
class AgentsGuideTest {

  private static final String SOURCES = "/META-INF/sources/com/toro/al/memprofiler/";

  private static String resource(String path) throws Exception {
    InputStream in = AgentsGuideTest.class.getResourceAsStream(path);
    assertNotNull(in, path + " is not packaged (see processResources in build.gradle.kts)");
    try {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    } finally {
      in.close();
    }
  }

  private static String guide() throws Exception {
    return resource("/AGENTS.md");
  }

  private static List<String> all(String regex, String text) {
    List<String> found = new ArrayList<>();
    Matcher m = Pattern.compile(regex).matcher(text);
    while (m.find()) found.add(m.group(1));
    return found;
  }

  private static Set<String> publicStaticMethods(Class<?> c) {
    Set<String> names = new HashSet<>();
    for (Method m : c.getMethods()) if (Modifier.isStatic(m.getModifiers())) names.add(m.getName());
    return names;
  }

  private static Set<String> publicMethods(Class<?> c) {
    Set<String> names = new HashSet<>();
    for (Method m : c.getMethods()) names.add(m.getName());
    return names;
  }

  @Test
  void agentsCommandPrintsTheGuideWithTheJarLocation() throws Exception {
    String printed = Cli.agentsGuide();
    assertTrue(printed.startsWith("<jar> = "), printed);
    assertTrue(printed.endsWith(guide()));
  }

  @Test
  void apiCallsExist() throws Exception {
    String g = guide();
    Set<String> api = publicStaticMethods(MemoryProfiler.class);
    Set<String> facade = publicStaticMethods(com.toro.al.memprofiler.api.MemoryProfiler.class);
    for (String name : all("MemoryProfiler\\.(\\w+)\\(", g)) {
      assertTrue(api.contains(name), "AGENTS.md calls MemoryProfiler." + name + "(...), which does not exist");
    }
    // the marking calls are said to be the same on the memprofiler-api facade
    for (String name : new String[] {"mark", "markGc", "scopeGc", "heapDump"}) {
      assertTrue(facade.contains(name), "memprofiler-api has no " + name);
    }
    Set<String> config = publicMethods(MemoryProfiler.Config.class);
    for (String chain : all("c -> c((?:\\.\\w+\\([^)]*\\))+)", g)) {
      for (String name : all("\\.(\\w+)\\(", chain)) {
        assertTrue(config.contains(name), "AGENTS.md calls Config." + name + "(...), which does not exist");
      }
    }
  }

  @Test
  void sourcePathsArePackaged() throws Exception {
    List<String> paths = all("(META-INF/sources/\\S+?\\.java)", guide());
    assertTrue(!paths.isEmpty());
    for (String p : paths) resource("/" + p);
  }

  @Test
  void commandLineFlagsExist() throws Exception {
    String known = ExportCli.USAGE + Cli.USAGE;
    for (String flag : all("(?<![\\w-])(--[a-z][a-z-]+)", guide())) {
      assertTrue(Pattern.compile("(?<![\\w-])" + flag + "(?![\\w-])").matcher(known).find(),
          "AGENTS.md uses " + flag + ", which is not in the usage of the jar or of export");
    }
  }

  @Test
  void agentArgumentsAreParsed() throws Exception {
    String g = guide();
    String agent = resource(SOURCES + "ProfilerAgent.java");
    List<String> keys = new ArrayList<>();
    for (String args : all("-javaagent:<jar>=(\\S+)", g)) {
      for (String pair : args.split(",")) keys.add(pair.substring(0, pair.indexOf('=')));
    }
    keys.addAll(all("`([a-z][A-Za-z]+)=[^`\\s]+`", g));
    assertTrue(keys.size() >= 3, "found " + keys);
    for (String key : keys) {
      assertTrue(agent.contains("\"" + key + "\""), "AGENTS.md uses the agent argument " + key + ", which ProfilerAgent does not parse");
    }
  }

  @Test
  void endpointsExist() throws Exception {
    String server = resource(SOURCES + "ProfilerServer.java");
    List<String> urls = all("http://localhost:\\d+(/[^\\s\"`]+)", guide());
    assertTrue(!urls.isEmpty());
    for (String url : urls) {
      String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
      assertTrue(server.contains("\"" + path + "\""), "AGENTS.md uses " + path + ", which ProfilerServer does not serve");
      for (String param : all("[?&](\\w+)=", url)) {
        assertTrue(server.contains("\"" + param + "\""), "AGENTS.md passes " + param + " to " + path + ", which is not read");
      }
    }
  }

  @Test
  void everyPointKindIsNamed() throws Exception {
    String g = guide();
    List<String> missing = new ArrayList<>();
    for (DataPoint.Kind k : DataPoint.Kind.values()) if (!g.contains("`" + k.name() + "`")) missing.add(k.name());
    assertEquals(new ArrayList<String>(), missing, "point kinds AGENTS.md does not name");
  }
}
