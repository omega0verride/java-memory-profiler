package com.toro.al.memprofiler;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.api.OutputSinkFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.*;

/**
 * Provides Java source code for a given class name. Strategy:
 * <ol>
 *   <li>Try to find the .java source on the classpath (e.g. source jars)</li>
 *   <li>Otherwise, decompile the class bytecode using CFR</li>
 * </ol>
 */
final class ClassSourceService {

  private ClassSourceService() {}

  static class SourceResult {
    final String source;
    final String origin; // "source" or "decompiled"
    final String className;

    SourceResult(String source, String origin, String className) {
      this.source = source;
      this.origin = origin;
      this.className = className;
    }

    String toJson() {
      StringBuilder sb = new StringBuilder(source.length() + 128);
      sb.append("{\"className\":\"").append(jsonEscape(className)).append('"')
        .append(",\"origin\":\"").append(origin).append('"')
        .append(",\"source\":\"").append(jsonEscape(source)).append("\"}");
      return sb.toString();
    }
  }

  /**
   * Get source for the given fully-qualified class name.
   */
  static SourceResult getSource(String className) throws ClassNotFoundException {
    // Try to find .java source on classpath
    String sourcePath = className.replace('.', '/') + ".java";
    InputStream srcIn = ClassSourceService.class.getClassLoader().getResourceAsStream(sourcePath);
    if (srcIn != null) {
      try {
        String src = readStream(srcIn);
        return new SourceResult(src, "source", className);
      } catch (IOException ignored) {}
    }

    // Decompile using CFR
    String decompiled = decompile(className);
    return new SourceResult(decompiled, "decompiled", className);
  }

  private static String decompile(String className) throws ClassNotFoundException {
    // Load the class bytecode
    String resourcePath = className.replace('.', '/') + ".class";
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    if (cl == null) cl = ClassSourceService.class.getClassLoader();

    // Also try with Instrumentation to ensure the class is loaded
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

    // Use CFR with a custom class file source that reads from the classloader
    final StringBuilder output = new StringBuilder();

    OutputSinkFactory sinkFactory = new OutputSinkFactory() {
      @Override
      public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
        return Collections.singletonList(SinkClass.STRING);
      }

      @Override
      public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
        return new Sink<T>() {
          @Override
          public void write(T t) {
            if (sinkType == SinkType.JAVA && t instanceof String) {
              output.append((String) t);
            }
          }
        };
      }
    };

    Map<String, String> options = new HashMap<String, String>();
    options.put("showversion", "false");
    options.put("comments", "false");
    options.put("innerclasses", "false");

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
    return result;
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
