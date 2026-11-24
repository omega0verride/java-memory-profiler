package com.toro.al.memprofiler;

import java.util.Arrays;

/**
 * {@code java -jar java-memory-profiler.jar} entry point.
 *
 * <pre>
 *   java -jar java-memory-profiler.jar                    # run the demo
 *   java -jar java-memory-profiler.jar export --help      # offline exports from a JSONL file
 * </pre>
 */
public final class Cli {

  private Cli() {}

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && "export".equals(args[0])) {
      int code = ExportCli.run(Arrays.copyOfRange(args, 1, args.length), System.out, System.err);
      System.exit(code);
      return;
    }
    Demo.main(args);
  }
}
