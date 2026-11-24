package com.toro.al.memprofiler;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Opens a source file at a line for the live UI's "Open in IDE" link.
 * {@link Desktop#open} cannot take a line, so the IDE is launched directly:
 * <ul>
 *   <li>{@code intellij}: {@code idea --line N file}</li>
 *   <li>{@code vscode}: {@code code --goto file:N}</li>
 *   <li>a command with {@code {file}} and {@code {line}} placeholders, for any
 *       other editor (for example {@code subl {file}:{line}})</li>
 *   <li>{@code system}: the OS default application, without the line</li>
 *   <li>{@code auto} (default): IntelliJ if a launcher is found, else VS Code,
 *       else {@code system}</li>
 * </ul>
 * The IDE's own launcher hands the file to a running instance when there is one.
 */
final class SourceOpener {

  /** System property naming the opener when the config does not ({@code -Dmemprofiler.sourceOpener=vscode}). */
  static final String PROPERTY = "memprofiler.sourceOpener";
  /** System property with the opener binary for {@code intellij} / {@code vscode}. */
  static final String PATH_PROPERTY = "memprofiler.sourceOpenerPath";

  enum Kind { AUTO, INTELLIJ, VSCODE, SYSTEM, COMMAND }

  private final Kind kind;
  private final String binary;   // explicit binary for INTELLIJ / VSCODE, or null to detect
  private final String template; // COMMAND only
  private volatile Resolved resolved;

  /**
   * @param spec       {@code auto}, {@code intellij}, {@code vscode}, {@code system}, a command
   *                   with {@code {file}} / {@code {line}}, or null for the system property, else auto
   * @param binaryPath launcher for {@code intellij} / {@code vscode} (null = system property, else detect)
   */
  SourceOpener(String spec, String binaryPath) {
    if (spec == null || spec.trim().isEmpty()) spec = System.getProperty(PROPERTY);
    if (binaryPath == null || binaryPath.trim().isEmpty()) binaryPath = System.getProperty(PATH_PROPERTY);
    String s = spec == null ? "" : spec.trim();
    this.binary = binaryPath == null || binaryPath.trim().isEmpty() ? null : binaryPath.trim();
    if (s.contains("{file}")) {
      this.kind = Kind.COMMAND;
      this.template = s;
    } else {
      Kind k;
      try {
        k = parseKind(s);
      } catch (IllegalArgumentException e) {
        System.err.println("[memprofiler] " + e.getMessage() + "; using auto");
        k = Kind.AUTO;
      }
      this.kind = k;
      this.template = null;
    }
  }

  static Kind parseKind(String s) {
    String k = s.toLowerCase(Locale.ROOT);
    if (k.isEmpty() || "auto".equals(k)) return Kind.AUTO;
    if ("intellij".equals(k) || "idea".equals(k)) return Kind.INTELLIJ;
    if ("vscode".equals(k) || "code".equals(k)) return Kind.VSCODE;
    if ("system".equals(k)) return Kind.SYSTEM;
    throw new IllegalArgumentException("unknown source opener '" + s
        + "': use auto, intellij, vscode, system, or a command with {file} and {line}");
  }

  Kind kind() { return kind; }

  /**
   * Opens {@code file} at {@code line} (0 or less: just the file).
   *
   * @return what was done, for the UI (for example "Opened Demo.java:151 in VS Code")
   */
  String open(File file, int line) throws IOException {
    Resolved r = resolve();
    String where = file.getName() + (line > 0 ? ":" + line : "");
    if (r.kind == Kind.SYSTEM) {
      Headless.markAwtUsed();
      if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        throw new IOException("no desktop to open files with; set the source opener to intellij, vscode or a command");
      }
      Desktop.getDesktop().open(file);
      return "Opened " + file.getName() + " with the system default app"
          + (line > 0 ? " (no IntelliJ or VS Code launcher found, so not at line " + line + ")" : "");
    }
    List<String> cmd = command(r.kind, r.binary, template, file.getAbsolutePath(), line);
    ProcessBuilder pb = new ProcessBuilder(cmd);
    File log = new File(System.getProperty("java.io.tmpdir"), "memprofiler-opener.log");
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
    try {
      pb.start();
    } catch (IOException e) {
      throw new IOException("could not run " + cmd.get(0) + ": " + e.getMessage(), e);
    }
    return "Opened " + where + " in " + label(r.kind, cmd.get(0));
  }

  /** The command line for {@code kind}; pure, so it is unit-tested. */
  static List<String> command(Kind kind, String binary, String template, String file, int line) {
    if (kind == Kind.INTELLIJ) {
      return line > 0 ? Arrays.asList(binary, "--line", String.valueOf(line), file) : Arrays.asList(binary, file);
    }
    if (kind == Kind.VSCODE) {
      return line > 0 ? Arrays.asList(binary, "--goto", file + ":" + line) : Arrays.asList(binary, file);
    }
    if (kind == Kind.COMMAND) {
      List<String> out = new ArrayList<String>();
      String lineText = String.valueOf(Math.max(1, line));
      // placeholders are replaced per token, so a path with spaces stays one argument
      for (String token : tokenize(template)) out.add(token.replace("{file}", file).replace("{line}", lineText));
      return out;
    }
    throw new IllegalArgumentException("no command for " + kind);
  }

  /** Splits on whitespace; double quotes group a token (for a binary path with spaces). */
  static List<String> tokenize(String s) {
    List<String> out = new ArrayList<String>();
    StringBuilder cur = new StringBuilder();
    boolean quoted = false, any = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"') {
        quoted = !quoted;
        any = true;
      } else if (Character.isWhitespace(c) && !quoted) {
        if (any) out.add(cur.toString());
        cur.setLength(0);
        any = false;
      } else {
        cur.append(c);
        any = true;
      }
    }
    if (any) out.add(cur.toString());
    return out;
  }

  private static String label(Kind kind, String binary) {
    if (kind == Kind.INTELLIJ) return "IntelliJ IDEA";
    if (kind == Kind.VSCODE) return "VS Code";
    return new File(binary).getName();
  }

  // ---- resolving auto and launcher paths ----

  private static final class Resolved {
    final Kind kind;
    final String binary;
    Resolved(Kind kind, String binary) {
      this.kind = kind;
      this.binary = binary;
    }
  }

  private Resolved resolve() throws IOException {
    Resolved r = resolved;
    if (r != null) return r;
    if (kind == Kind.SYSTEM || kind == Kind.COMMAND) {
      r = new Resolved(kind, null);
    } else if (kind == Kind.INTELLIJ || kind == Kind.VSCODE) {
      String b = binary != null ? binary : kind == Kind.INTELLIJ ? findIntellij() : findVscode();
      if (b == null) {
        throw new IOException((kind == Kind.INTELLIJ ? "IntelliJ" : "VS Code")
            + " launcher not found; set its path with sourceOpener(\"" + kind.name().toLowerCase(Locale.ROOT)
            + "\", \"/path/to/launcher\") or -D" + PATH_PROPERTY);
      }
      r = new Resolved(kind, b);
    } else {
      String idea = findIntellij();
      if (idea != null) r = new Resolved(Kind.INTELLIJ, idea);
      else {
        String code = findVscode();
        r = code != null ? new Resolved(Kind.VSCODE, code) : new Resolved(Kind.SYSTEM, null);
      }
    }
    resolved = r;
    return r;
  }

  static String findIntellij() {
    String home = System.getProperty("user.home", "");
    String local = System.getenv("LOCALAPPDATA");
    return firstExisting(onPath("idea", "idea.sh", "idea64.exe", "idea.cmd", "idea.bat",
        "intellij-idea", "intellij-idea-ultimate", "intellij-idea-community"),
        // JetBrains Toolbox shell scripts (Linux, macOS, Windows)
        home + "/.local/share/JetBrains/Toolbox/scripts/idea",
        home + "/Library/Application Support/JetBrains/Toolbox/scripts/idea",
        local == null ? null : local + "\\JetBrains\\Toolbox\\scripts\\idea.cmd",
        "/snap/bin/intellij-idea",
        "/snap/bin/intellij-idea-ultimate",
        "/snap/bin/intellij-idea-community",
        "/Applications/IntelliJ IDEA.app/Contents/MacOS/idea",
        "/Applications/IntelliJ IDEA CE.app/Contents/MacOS/idea",
        home + "/Applications/IntelliJ IDEA.app/Contents/MacOS/idea",
        home + "/Applications/IntelliJ IDEA CE.app/Contents/MacOS/idea");
  }

  static String findVscode() {
    String home = System.getProperty("user.home", "");
    return firstExisting(onPath("code", "code.cmd", "codium", "codium.cmd"),
        "/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code",
        home + "/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code");
  }

  private static String onPath(String... names) {
    String path = System.getenv("PATH");
    if (path == null) return null;
    for (String dir : path.split(File.pathSeparator)) {
      if (dir.isEmpty()) continue;
      for (String n : names) {
        File f = new File(dir, n);
        if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
      }
    }
    return null;
  }

  private static String firstExisting(String first, String... candidates) {
    if (first != null) return first;
    for (String c : candidates) {
      if (c == null) continue;
      File f = new File(c);
      if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
    }
    return null;
  }
}
