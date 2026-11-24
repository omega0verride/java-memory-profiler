package com.toro.al.memprofiler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * {@code java -jar java-memory-profiler.jar} entry point.
 *
 * <pre>
 *   java -jar java-memory-profiler.jar                    # run the demo
 *   java -jar java-memory-profiler.jar export --help      # offline exports from a JSONL file
 *   java -jar java-memory-profiler.jar agents             # the guide for AI agents (AGENTS.md)
 *   java -jar java-memory-profiler.jar api                # the Java API at a glance (MemoryProfiler.printHelp())
 *   java -jar java-memory-profiler.jar --help             # the commands
 * </pre>
 */
public final class Cli {

  static final String USAGE =
      "Usage: java -jar java-memory-profiler.jar [command]\n"
      + "  (no command)      run the demo with the live UI (opens a browser)\n"
      + "  --export <dir>    run the demo headless, writing <dir>/demo-profile.html and .png\n"
      + "  export --help     HTML / PNG exports and summaries from a JSONL file\n"
      + "  agents            print the guide for AI agents (AGENTS.md) with this jar's path\n"
      + "  api               the Java API at a glance (the same as MemoryProfiler.printHelp())\n"
      + "  --help, help      this text\n"
      + "As a Java agent:   java -javaagent:java-memory-profiler.jar[=key=value,...] -cp app.jar Main\n";

  private Cli() {}

  /**
   * Runs the command in {@code args[0]} ({@code export}, {@code agents},
   * {@code api}, {@code --help}); anything else starts the demo with these arguments.
   *
   * @param args the command line
   * @throws Exception when the demo fails
   */
  public static void main(String[] args) throws Exception {
    String command = args.length > 0 ? args[0] : "";
    if ("export".equals(command)) {
      int code = ExportCli.run(Arrays.copyOfRange(args, 1, args.length), System.out, System.err);
      System.exit(code);
      return;
    }
    if ("agents".equals(command)) {
      System.out.print(agentsGuide());
      return;
    }
    if ("api".equals(command)) {
      MemoryProfiler.printHelp();
      return;
    }
    if ("--help".equals(command) || "-h".equals(command) || "help".equals(command)) {
      // "help api" reads naturally too
      if (args.length > 1 && "api".equals(args[1])) MemoryProfiler.printHelp();
      else System.out.print(USAGE);
      return;
    }
    Demo.main(args);
  }

  /** AGENTS.md from the classpath, after a line naming this jar, which the guide calls {@code <jar>}. */
  static String agentsGuide() throws IOException {
    String where;
    try {
      where = new java.io.File(Cli.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
    } catch (Exception e) {
      where = "java-memory-profiler.jar";
    }
    String header = "<jar> = " + where + " (version " + MemoryProfiler.version() + ")\n\n";
    InputStream in = Cli.class.getResourceAsStream("/AGENTS.md");
    if (in == null) return header + "AGENTS.md is missing from this build; see " + GithubLink.URL + "/blob/main/AGENTS.md\n";
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      for (int n; (n = in.read(buf)) > 0; ) bytes.write(buf, 0, n);
      return header + new String(bytes.toByteArray(), "UTF-8");
    } finally {
      in.close();
    }
  }
}
