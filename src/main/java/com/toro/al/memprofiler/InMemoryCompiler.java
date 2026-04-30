package com.toro.al.memprofiler;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles Java source code from a String in-memory using {@link javax.tools.JavaCompiler}.
 * Returns the compiled {@link Class} objects, ready for analysis.
 *
 * <p>This does not write anything to disk. Both source and bytecode live in memory.
 */
public class InMemoryCompiler {

  private static final Pattern CLASS_PATTERN =
      Pattern.compile("(?:^|\\s)(?:public\\s+)?(?:abstract\\s+)?(?:final\\s+)?class\\s+(\\w+)");
  private static final Pattern PACKAGE_PATTERN =
      Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

  /**
   * Compile a single Java source string. The class name is extracted from the source.
   *
   * @param source Java source code
   * @return the compiled class
   * @throws CompilationException if compilation fails
   */
  public static Class<?> compile(String source) throws CompilationException {
    // Extract class name from source
    String className = extractClassName(source);
    if (className == null) {
      throw new CompilationException("Could not find a class declaration in the source");
    }

    // Extract package
    Matcher pkgMatcher = PACKAGE_PATTERN.matcher(source);
    String fullName;
    if (pkgMatcher.find()) {
      fullName = pkgMatcher.group(1) + "." + className;
    } else {
      fullName = className;
    }

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new CompilationException("No Java compiler available. Ensure you are running on a JDK (not a JRE).");
    }

    // In-memory file manager
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
    MemFileManager fileManager = new MemFileManager(
        compiler.getStandardFileManager(diagnostics, null, null));

    // Source file object
    JavaFileObject sourceFile = new SourceObject(fullName, source);
    List<JavaFileObject> compilationUnits = Collections.<JavaFileObject>singletonList(sourceFile);

    // Compile
    JavaCompiler.CompilationTask task = compiler.getTask(
        null, fileManager, diagnostics, null, null, compilationUnits);
    boolean success = task.call();

    if (!success) {
      StringBuilder sb = new StringBuilder("Compilation failed:\n");
      for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
        if (d.getKind() == Diagnostic.Kind.ERROR) {
          sb.append("  line ").append(d.getLineNumber())
            .append(": ").append(d.getMessage(null)).append('\n');
        }
      }
      throw new CompilationException(sb.toString().trim());
    }

    // Load the compiled class
    MemClassLoader loader = new MemClassLoader(fileManager.getOutput());
    try {
      return loader.loadClass(fullName);
    } catch (ClassNotFoundException e) {
      throw new CompilationException("Compiled but could not load class: " + fullName);
    }
  }

  static String extractClassName(String source) {
    Matcher m = CLASS_PATTERN.matcher(source);
    return m.find() ? m.group(1) : null;
  }

  public static class CompilationException extends Exception {
    public CompilationException(String message) { super(message); }
  }

  // --- In-memory JavaFileObject for source ---
  private static class SourceObject extends SimpleJavaFileObject {
    private final String source;

    SourceObject(String className, String source) {
      super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
            Kind.SOURCE);
      this.source = source;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return source;
    }
  }

  // --- In-memory JavaFileObject for compiled bytecode ---
  private static class BytecodeObject extends SimpleJavaFileObject {
    private final ByteArrayOutputStream bos = new ByteArrayOutputStream();
    private final String className;

    BytecodeObject(String className) {
      super(URI.create("bytes:///" + className.replace('.', '/') + Kind.CLASS.extension),
            Kind.CLASS);
      this.className = className;
    }

    @Override
    public OutputStream openOutputStream() {
      return bos;
    }

    byte[] getBytes() { return bos.toByteArray(); }
    String getClassName() { return className; }
  }

  // --- File manager that stores compiled classes in memory ---
  private static class MemFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
    private final List<BytecodeObject> output = new ArrayList<BytecodeObject>();

    MemFileManager(StandardJavaFileManager delegate) {
      super(delegate);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(JavaFileManager.Location location,
        String className, JavaFileObject.Kind kind, FileObject sibling) throws IOException {
      BytecodeObject obj = new BytecodeObject(className);
      output.add(obj);
      return obj;
    }

    List<BytecodeObject> getOutput() { return output; }
  }

  // --- ClassLoader that loads from in-memory bytecode ---
  private static class MemClassLoader extends ClassLoader {
    private final Map<String, byte[]> classes = new HashMap<String, byte[]>();

    MemClassLoader(List<BytecodeObject> compiled) {
      super(InMemoryCompiler.class.getClassLoader());
      for (BytecodeObject bc : compiled) {
        classes.put(bc.getClassName(), bc.getBytes());
      }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
      byte[] bytes = classes.get(name);
      if (bytes == null) throw new ClassNotFoundException(name);
      return defineClass(name, bytes, 0, bytes.length);
    }
  }
}
