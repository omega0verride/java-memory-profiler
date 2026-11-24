package com.toro.al.memprofiler;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.*;

/**
 * Provides Java source code for a given class name. Strategy:
 * <ol>
 *   <li>The real {@code .java} file under the configured source roots</li>
 *   <li>The {@code .java} source on the classpath (e.g. source jars)</li>
 *   <li>Otherwise, decompile the class bytecode using CFR</li>
 * </ol>
 * A requested source line (the line of a mark) is translated to the line of
 * the returned text: the same line for real source, and for decompiled code
 * the first statement CFR emitted for that line's bytecode.
 */
final class ClassSourceService {

  private ClassSourceService() {}

  static class SourceResult {
    final String source;
    final String origin; // "source" or "decompiled"
    final String className;
    /** Source file the text came from (relative to its root), or null for decompiled code. */
    final String file;
    /** The requested line in the original source file, 0 = none requested. */
    final int sourceLine;
    /** The line of {@link #source} to highlight, 0 = none (or not found in decompiled code). */
    final int line;

    SourceResult(String source, String origin, String className, String file, int sourceLine, int line) {
      this.source = source;
      this.origin = origin;
      this.className = className;
      this.file = file;
      this.sourceLine = sourceLine;
      this.line = line;
    }

    String toJson() {
      StringBuilder sb = new StringBuilder(source.length() + 160);
      sb.append("{\"className\":\"").append(jsonEscape(className)).append('"')
        .append(",\"origin\":\"").append(origin).append('"');
      if (file != null) sb.append(",\"file\":\"").append(jsonEscape(file)).append('"');
      sb.append(",\"sourceLine\":").append(sourceLine)
        .append(",\"line\":").append(line)
        .append(",\"source\":\"").append(jsonEscape(source)).append("\"}");
      return sb.toString();
    }
  }

  /** Get source for the given fully-qualified class name, with no line to find. */
  static SourceResult getSource(String className) throws ClassNotFoundException {
    return getSource(className, null, 0, null);
  }

  /**
   * Get source for {@code className}.
   *
   * @param className   fully-qualified class name; nested classes use {@code $}
   * @param sourceFile  source path relative to a root (for example
   *                    {@code com/acme/Foo.java}, as recorded for a mark), or null to
   *                    derive it from the class name (nested classes live in the
   *                    outer class's file)
   * @param sourceLine  line in that file to highlight, 0 = none
   * @param sourceRoots roots searched for {@code sourceFile} first (may be null)
   */
  static SourceResult getSource(String className, String sourceFile, int sourceLine, String[] sourceRoots)
      throws ClassNotFoundException {
    String rel = sourceFile != null && !sourceFile.trim().isEmpty()
        ? sourceFile.trim().replace('\\', '/') : defaultSourcePath(className);
    int line = Math.max(0, sourceLine);

    // 1. the real file under the configured source roots: exact line numbers
    java.io.File f = HtmlExporter.resolveSource(rel, sourceRoots);
    if (f != null) {
      try {
        String src = readStream(new java.io.FileInputStream(f));
        return new SourceResult(src, "source", className, rel, line, clampLine(src, line));
      } catch (IOException ignored) {}
    }

    // 2. .java source on the classpath (source jars)
    if (!rel.contains("..")) {
      InputStream srcIn = ClassSourceService.class.getClassLoader().getResourceAsStream(rel);
      if (srcIn != null) {
        try {
          String src = readStream(srcIn);
          return new SourceResult(src, "source", className, rel, line, clampLine(src, line));
        } catch (IOException ignored) {}
      }
    }

    // 3. decompile, and map the source line onto the decompiled text
    Decompiled d = decompile(className);
    return new SourceResult(d.text, "decompiled", className, null, line, line > 0 ? mapLine(d.mappings, line) : 0);
  }

  /** {@code com.acme.Outer$Inner} is in {@code com/acme/Outer.java}. */
  static String defaultSourcePath(String className) {
    String outer = className;
    int dollar = outer.indexOf('$');
    if (dollar > 0) outer = outer.substring(0, dollar);
    return outer.replace('.', '/') + ".java";
  }

  private static int clampLine(String src, int line) {
    if (line <= 0) return 0;
    int lines = 1;
    for (int i = 0; i < src.length(); i++) if (src.charAt(i) == '\n') lines++;
    return line <= lines ? line : 0;
  }

  /**
   * The decompiled line for {@code sourceLine}: for every bytecode range the
   * class file attributes to that line, the first statement CFR emitted inside
   * it; the earliest such line wins. When no statement starts inside a range
   * (the line's code was folded into a neighbour), the nearest statement before
   * it is used. 0 when the line has no bytecode (comments, blank lines, or a
   * class compiled without line numbers).
   */
  static int mapLine(List<SinkReturns.LineNumberMapping> mappings, int sourceLine) {
    int best = Integer.MAX_VALUE;
    for (SinkReturns.LineNumberMapping m : mappings) {
      NavigableMap<Integer, Integer> classFile = m.getClassFileMappings();
      NavigableMap<Integer, Integer> output = m.getMappings();
      if (classFile == null || output == null || output.isEmpty()) continue;
      for (Map.Entry<Integer, Integer> e : classFile.entrySet()) {
        if (e.getValue() == null || e.getValue().intValue() != sourceLine) continue;
        Integer next = classFile.higherKey(e.getKey());
        SortedMap<Integer, Integer> inRange =
            output.subMap(e.getKey(), true, next == null ? Integer.MAX_VALUE : next.intValue(), false);
        if (!inRange.isEmpty()) {
          for (Integer outLine : inRange.values()) best = Math.min(best, outLine.intValue());
        } else {
          Map.Entry<Integer, Integer> near = output.floorEntry(e.getKey());
          if (near == null) near = output.ceilingEntry(e.getKey());
          if (near != null) best = Math.min(best, near.getValue().intValue());
        }
      }
    }
    return best == Integer.MAX_VALUE ? 0 : best;
  }

  static final class Decompiled {
    final String text;
    final List<SinkReturns.LineNumberMapping> mappings;
    Decompiled(String text, List<SinkReturns.LineNumberMapping> mappings) {
      this.text = text;
      this.mappings = mappings;
    }
  }

  static Decompiled decompile(String className) throws ClassNotFoundException {
    // Find the class (Instrumentation sees classes of every loader), else load it
    Instrumentation inst = ProfilerAgent.instrumentation();
    Class<?> targetClass = null;
    if (inst != null) {
      for (Class<?> c : inst.getAllLoadedClasses()) {
        if (c.getName().equals(className)) {
          targetClass = c;
          break;
        }
      }
    }
    if (targetClass == null) {
      targetClass = Class.forName(className);
    }

    final ClassLoader targetLoader = targetClass.getClassLoader();

    // CFR reads the bytes from the class's own loader; the JAVA sink gets the
    // text, the LINENUMBER sink the bytecode-offset maps used by mapLine
    final StringBuilder output = new StringBuilder();
    final List<SinkReturns.LineNumberMapping> mappings = new ArrayList<SinkReturns.LineNumberMapping>();

    OutputSinkFactory sinkFactory = new OutputSinkFactory() {
      @Override
      public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
        if (sinkType == SinkType.LINENUMBER) return Collections.singletonList(SinkClass.LINE_NUMBER_MAPPING);
        return Collections.singletonList(SinkClass.STRING);
      }

      @Override
      public <T> Sink<T> getSink(final SinkType sinkType, SinkClass sinkClass) {
        return new Sink<T>() {
          @Override
          public void write(T t) {
            if (sinkType == SinkType.JAVA && t instanceof String) {
              output.append((String) t);
            } else if (sinkType == SinkType.LINENUMBER && t instanceof SinkReturns.LineNumberMapping) {
              mappings.add((SinkReturns.LineNumberMapping) t);
            }
          }
        };
      }
    };

    Map<String, String> options = new HashMap<String, String>();
    options.put("showversion", "false");
    options.put("comments", "false");
    options.put("innerclasses", "false");
    options.put("trackbytecodeloc", "true");

    CfrDriver driver = new CfrDriver.Builder()
      .withClassFileSource(new ClassLoaderClassFileSource(targetLoader))
      .withOutputSink(sinkFactory)
      .withOptions(options)
      .build();

    driver.analyse(Collections.singletonList(className.replace('.', '/') + ".class"));

    String result = output.toString();
    if (result.isEmpty()) {
      throw new ClassNotFoundException("Could not decompile: " + className);
    }
    return new Decompiled(result, mappings);
  }

  /**
   * ClassFileSource implementation that reads from a ClassLoader.
   */
  private static class ClassLoaderClassFileSource implements ClassFileSource {
    private final ClassLoader loader;

    ClassLoaderClassFileSource(ClassLoader loader) {
      this.loader = loader != null ? loader : ClassLoader.getSystemClassLoader();
    }

    @Override
    public void informAnalysisRelativePathDetail(String usePath, String specPath) {}

    @Override
    public Collection<String> addJar(String jarPath) {
      return Collections.emptyList();
    }

    @Override
    public String getPossiblyRenamedPath(String path) {
      return path;
    }

    @Override
    public org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair<byte[], String> getClassFileContent(String path) throws IOException {
      // path is like "com/example/Foo.class"
      InputStream in = loader.getResourceAsStream(path);
      if (in == null) {
        // Try system classloader for JDK classes
        in = ClassLoader.getSystemResourceAsStream(path);
      }
      if (in == null) {
        throw new IOException("Cannot find class file: " + path);
      }
      byte[] bytes = readBytes(in);
      return org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair.make(bytes, path);
    }
  }

  private static byte[] readBytes(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
    byte[] buf = new byte[4096];
    int n;
    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    in.close();
    return out.toByteArray();
  }

  private static String readStream(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
    byte[] buf = new byte[4096];
    int n;
    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    in.close();
    return out.toString("UTF-8");
  }

  private static String jsonEscape(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder(s.length() + 32);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\': sb.append("\\\\"); break;
        case '"': sb.append("\\\""); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20) {
            sb.append("\\u").append(String.format("%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    return sb.toString();
  }
}
