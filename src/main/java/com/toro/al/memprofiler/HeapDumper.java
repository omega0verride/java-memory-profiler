package com.toro.al.memprofiler;

import javax.management.MBeanServer;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Utility to capture heap dumps (hprof files) using the HotSpot Diagnostic MXBean.
 * Works on any HotSpot-based JVM (Oracle, OpenJDK, etc.) without external dependencies.
 */
final class HeapDumper {

  /** Opener strategy for hprof files. */
  enum Opener {
    /** Use Desktop.open() — OS default handler. */
    SYSTEM,
    /** Launch VisualVM with the file path. */
    VISUALVM,
    /** Launch IntelliJ IDEA's built-in profiler. */
    INTELLIJ
  }

  /** Metadata for a recorded heap dump. */
  static final class DumpInfo {
    final String name;
    final String path;
    final long timestamp;
    final long sizeBytes;

    DumpInfo(String name, String path, long timestamp, long sizeBytes) {
      this.name = name;
      this.path = path;
      this.timestamp = timestamp;
      this.sizeBytes = sizeBytes;
    }

    String toJson() {
      StringBuilder sb = new StringBuilder(128);
      sb.append('{');
      sb.append("\"name\":\"").append(escape(name)).append('"');
      sb.append(",\"path\":\"").append(escape(path)).append('"');
      sb.append(",\"timestamp\":").append(timestamp);
      sb.append(",\"sizeBytes\":").append(sizeBytes);
      sb.append('}');
      return sb.toString();
    }

    private static String escape(String s) {
      return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
  }

  private final File dumpDir;
  private Opener opener = Opener.SYSTEM;
  private String openerPath = null; // custom binary path for VISUALVM / INTELLIJ
  private final List<DumpInfo> dumps = new ArrayList<DumpInfo>();
  private Object diagnosticBean;
  private Method dumpMethod;

  HeapDumper(File dumpDir) {
    this.dumpDir = dumpDir;
    if (!dumpDir.exists()) dumpDir.mkdirs();
    initBean();
  }

  private void initBean() {
    try {
      MBeanServer server = ManagementFactory.getPlatformMBeanServer();
      Class<?> clazz = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
      diagnosticBean = ManagementFactory.newPlatformMXBeanProxy(
          server, "com.sun.management:type=HotSpotDiagnostic", clazz);
      dumpMethod = clazz.getMethod("dumpHeap", String.class, boolean.class);
    } catch (Throwable t) {
      System.err.println("[memprofiler] heap dump not available: " + t.getMessage());
    }
  }

  void setOpener(Opener opener) { this.opener = opener; }
  void setOpenerPath(String path) { this.openerPath = path; }
  Opener opener() { return opener; }
  String openerPath() { return openerPath; }

  /**
   * Take a heap dump with the given label. Only live objects are included.
   * @return the DumpInfo, or null if heap dumping is not available
   */
  synchronized DumpInfo dump(String name) {
    if (diagnosticBean == null || dumpMethod == null) return null;
    if (name == null || name.isEmpty()) name = "dump";

    String safeName = name.replaceAll("[^a-zA-Z0-9._-]", "_");
    String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
    String filename = safeName + "_" + ts + ".hprof";
    File file = new File(dumpDir, filename);

    try {
      dumpMethod.invoke(diagnosticBean, file.getAbsolutePath(), true);
    } catch (Throwable t) {
      System.err.println("[memprofiler] heap dump failed: " + t.getMessage());
      return null;
    }

    long size = file.exists() ? file.length() : 0;
    DumpInfo info = new DumpInfo(name, file.getAbsolutePath(), System.currentTimeMillis(), size);
    dumps.add(info);
    System.out.println("[memprofiler] heap dump: " + file.getAbsolutePath() + " (" + (size / 1024 / 1024) + " MB)");
    return info;
  }

  synchronized List<DumpInfo> list() {
    return new ArrayList<DumpInfo>(dumps);
  }

  /**
   * Open a heap dump file using the configured opener.
   */
  void open(String path) {
    if (path == null || path.isEmpty()) return;
    File f = new File(path);
    if (!f.exists()) {
      System.err.println("[memprofiler] heap dump file not found: " + path);
      return;
    }
    try {
      switch (opener) {
        case VISUALVM:
          launchProcess(resolveVisualVM(), f.getAbsolutePath());
          break;
        case INTELLIJ:
          launchProcess(resolveIntelliJ(), f.getAbsolutePath());
          break;
        default:
          Headless.markAwtUsed();
          if (java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop.getDesktop().open(f);
          }
          break;
      }
    } catch (Throwable t) {
      System.err.println("[memprofiler] failed to open heap dump: " + t.getMessage());
    }
  }

  private String resolveVisualVM() {
    if (openerPath != null && !openerPath.isEmpty()) return openerPath;
    // Try common locations
    String javaHome = System.getProperty("java.home");
    if (javaHome != null) {
      File candidate = new File(javaHome, "../bin/jvisualvm");
      if (candidate.exists()) return candidate.getAbsolutePath();
      candidate = new File(javaHome, "../bin/jvisualvm.exe");
      if (candidate.exists()) return candidate.getAbsolutePath();
    }
    return "visualvm"; // hope it's on PATH
  }

  private String resolveIntelliJ() {
    if (openerPath != null && !openerPath.isEmpty()) return openerPath;
    return "idea"; // hope it's on PATH
  }

  private void launchProcess(String binary, String filePath) throws Exception {
    ProcessBuilder pb;
    if (binary.toLowerCase().contains("visualvm")) {
      pb = new ProcessBuilder(binary, "--openfile", filePath);
    } else if (binary.toLowerCase().contains("idea")) {
      pb = new ProcessBuilder(binary, filePath);
    } else {
      pb = new ProcessBuilder(binary, filePath);
    }
    pb.redirectErrorStream(true);
    // Detach streams so the child process doesn't die with the parent's pipe
    pb.redirectOutput(ProcessBuilder.Redirect.to(new File(
        System.getProperty("java.io.tmpdir"), "memprofiler-opener.log")));
    pb.start();
  }
}
