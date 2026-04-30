package com.toro.al.memprofiler;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Attempts to self-attach the profiler agent at runtime so that
 * {@link java.lang.instrument.Instrumentation} becomes available without
 * requiring the user to specify {@code -javaagent} on the command line.
 *
 * <p>This works on JDK 9+ (with {@code -Djdk.attach.allowAttachSelf=true},
 * which is the default on many distributions) and on JDK 8 if {@code tools.jar}
 * is on the classpath or discoverable in the JDK directory.
 */
final class SelfAttach {

  private SelfAttach() {}

  /**
   * Try to attach the agent to the current JVM. On success,
   * {@link ProfilerAgent#instrumentation()} will be non-null afterwards.
   *
   * @return true if instrumentation is now available
   */
  static boolean tryAttach() {
    if (ProfilerAgent.instrumentation() != null) return true;

    try {
      // Find the path to our own jar (the profiler jar)
      String agentJar = findAgentJar();
      if (agentJar == null) return false;

      String pid = getPid();
      if (pid == null) return false;

      // Try to load VirtualMachine class
      Class<?> vmClass = loadVirtualMachineClass();
      if (vmClass == null) return false;

      // Attach
      Method attach = vmClass.getMethod("attach", String.class);
      Object vm = attach.invoke(null, pid);
      try {
        Method loadAgent = vmClass.getMethod("loadAgent", String.class);
        loadAgent.invoke(vm, agentJar);
      } finally {
        Method detach = vmClass.getMethod("detach");
        detach.invoke(vm);
      }

      return ProfilerAgent.instrumentation() != null;
    } catch (Throwable t) {
      // Self-attach not supported in this environment
      return false;
    }
  }

  private static String getPid() {
    try {
      String name = ManagementFactory.getRuntimeMXBean().getName();
      int at = name.indexOf('@');
      return at > 0 ? name.substring(0, at) : name;
    } catch (Throwable t) {
      return null;
    }
  }

  private static String findAgentJar() {
    try {
      // Get the location of this class's jar
      URL url = SelfAttach.class.getProtectionDomain().getCodeSource().getLocation();
      if (url == null) return null;
      File f = new File(url.toURI());
      if (f.isFile() && f.getName().endsWith(".jar")) {
        return f.getAbsolutePath();
      }
      // Running from class directory (IDE) - not a jar, can't self-attach
      return null;
    } catch (Throwable t) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static Class<?> loadVirtualMachineClass() {
    // Try direct load first (JDK 9+ where it's in java.base or auto-module)
    try {
      return Class.forName("com.sun.tools.attach.VirtualMachine");
    } catch (ClassNotFoundException ignored) {}

    // JDK 8: try to find tools.jar in JAVA_HOME
    try {
      String javaHome = System.getProperty("java.home");
      File toolsJar = new File(javaHome, "lib/tools.jar");
      if (!toolsJar.exists()) {
        // java.home might point to jre/ inside jdk
        toolsJar = new File(javaHome, "../lib/tools.jar");
      }
      if (toolsJar.exists()) {
        URLClassLoader cl = new URLClassLoader(
          new URL[]{toolsJar.toURI().toURL()},
          SelfAttach.class.getClassLoader()
        );
        return cl.loadClass("com.sun.tools.attach.VirtualMachine");
      }
    } catch (Throwable ignored) {}

    return null;
  }
}
