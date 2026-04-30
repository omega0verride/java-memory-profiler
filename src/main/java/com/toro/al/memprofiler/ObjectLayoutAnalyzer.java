package com.toro.al.memprofiler;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Analyzes the memory layout of a Java class using {@link sun.misc.Unsafe}
 * to obtain actual field offsets assigned by the JVM. This gives a deterministic,
 * real view of the object layout including padding bytes.
 *
 * <p>The layout includes:
 * <ul>
 *   <li>Object header (typically 12 bytes on 64-bit JVM with compressed oops, 16 without)</li>
 *   <li>Fields from the entire class hierarchy, sorted by their actual JVM offset</li>
 *   <li>Padding gaps between fields</li>
 *   <li>Tail padding to align the total size to 8 bytes</li>
 * </ul>
 */
public class ObjectLayoutAnalyzer {

  private static final Unsafe UNSAFE;

  static {
    try {
      Field f = Unsafe.class.getDeclaredField("theUnsafe");
      f.setAccessible(true);
      UNSAFE = (Unsafe) f.get(null);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** A single region in the object layout (header, field, or padding). */
  public static class LayoutEntry {
    public final long offset;
    public final int size;
    public final String kind;       // "header", "field", "padding"
    public final String fieldName;  // null for header/padding
    public final String fieldType;  // null for header/padding
    public final String declaringClass; // null for header/padding

    LayoutEntry(long offset, int size, String kind, String fieldName, String fieldType, String declaringClass) {
      this.offset = offset;
      this.size = size;
      this.kind = kind;
      this.fieldName = fieldName;
      this.fieldType = fieldType;
      this.declaringClass = declaringClass;
    }

    public String toJson() {
      StringBuilder sb = new StringBuilder(128);
      sb.append("{\"offset\":").append(offset)
        .append(",\"size\":").append(size)
        .append(",\"kind\":\"").append(kind).append('"');
      if (fieldName != null) {
        sb.append(",\"fieldName\":\"").append(jsonEscape(fieldName)).append('"');
      }
      if (fieldType != null) {
        sb.append(",\"fieldType\":\"").append(jsonEscape(fieldType)).append('"');
      }
      if (declaringClass != null) {
        sb.append(",\"declaringClass\":\"").append(jsonEscape(declaringClass)).append('"');
      }
      sb.append('}');
      return sb.toString();
    }
  }

  /** Result of analyzing a class layout. */
  public static class LayoutResult {
    public final String className;
    public final List<LayoutEntry> entries;
    public final long shallowSize;
    public final long headerSize;
    public final long fieldsSize;
    public final long paddingSize;

    LayoutResult(String className, List<LayoutEntry> entries, long shallowSize,
                 long headerSize, long fieldsSize, long paddingSize) {
      this.className = className;
      this.entries = entries;
      this.shallowSize = shallowSize;
      this.headerSize = headerSize;
      this.fieldsSize = fieldsSize;
      this.paddingSize = paddingSize;
    }

    public String toJson() {
      StringBuilder sb = new StringBuilder(512);
      sb.append("{\"className\":\"").append(jsonEscape(className)).append('"')
        .append(",\"shallowSize\":").append(shallowSize)
        .append(",\"headerSize\":").append(headerSize)
        .append(",\"fieldsSize\":").append(fieldsSize)
        .append(",\"paddingSize\":").append(paddingSize)
        .append(",\"entries\":[");
      for (int i = 0; i < entries.size(); i++) {
        if (i > 0) sb.append(',');
        sb.append(entries.get(i).toJson());
      }
      sb.append("]}");
      return sb.toString();
    }
  }

  /**
   * Analyze the memory layout of the given class.
   *
   * @param className fully qualified class name (e.g. "java.lang.String")
   * @return layout result with all entries
   * @throws ClassNotFoundException if the class cannot be found
   */
  public static LayoutResult analyze(String className) throws ClassNotFoundException {
    Class<?> clazz = Class.forName(className);
    return analyze(clazz);
  }

  /**
   * Analyze the memory layout of the given class.
   */
  @SuppressWarnings("removal")
  public static LayoutResult analyze(Class<?> clazz) {
    // Collect all instance fields from the entire hierarchy
    List<FieldInfo> fields = new ArrayList<FieldInfo>();
    Class<?> c = clazz;
    while (c != null && c != Object.class) {
      for (Field f : c.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers())) continue;
        long offset = UNSAFE.objectFieldOffset(f);
        int size = fieldSize(f.getType());
        fields.add(new FieldInfo(offset, size, f.getName(), typeName(f.getType()), c.getName()));
      }
      c = c.getSuperclass();
    }

    // Sort by offset
    Collections.sort(fields, new Comparator<FieldInfo>() {
      public int compare(FieldInfo a, FieldInfo b) {
        return Long.compare(a.offset, b.offset);
      }
    });

    // Determine header size: offset of first field, or default 12/16
    long headerSize;
    if (!fields.isEmpty()) {
      headerSize = fields.get(0).offset;
    } else {
      // No fields -- estimate header. On 64-bit with compressed oops it's 12, without 16.
      // We use 12 as the common default; the object will be padded to 8-byte alignment anyway.
      headerSize = 12;
    }

    // Build layout entries
    List<LayoutEntry> entries = new ArrayList<LayoutEntry>();
    entries.add(new LayoutEntry(0, (int) headerSize, "header", null, null, null));

    long cursor = headerSize;
    long totalFieldSize = 0;
    long totalPadding = 0;

    for (FieldInfo fi : fields) {
      // Gap before this field = padding
      if (fi.offset > cursor) {
        int gap = (int) (fi.offset - cursor);
        entries.add(new LayoutEntry(cursor, gap, "padding", null, null, null));
        totalPadding += gap;
        cursor = fi.offset;
      }
      entries.add(new LayoutEntry(fi.offset, fi.size, "field", fi.name, fi.typeName, fi.declaringClass));
      totalFieldSize += fi.size;
      cursor = fi.offset + fi.size;
    }

    // Align total to 8 bytes
    long aligned = (cursor + 7) & ~7L;
    if (aligned > cursor) {
      int tail = (int) (aligned - cursor);
      entries.add(new LayoutEntry(cursor, tail, "padding", null, null, null));
      totalPadding += tail;
      cursor = aligned;
    }

    return new LayoutResult(clazz.getName(), entries, cursor, headerSize, totalFieldSize, totalPadding);
  }

  private static int fieldSize(Class<?> type) {
    if (type == boolean.class || type == byte.class) return 1;
    if (type == short.class || type == char.class) return 2;
    if (type == int.class || type == float.class) return 4;
    if (type == long.class || type == double.class) return 8;
    // Object references: 4 bytes with compressed oops (default), 8 without.
    // We detect by checking the minimum offset gap for a reference field.
    return isCompressedOops() ? 4 : 8;
  }

  private static volatile int compressedOops = -1; // -1 = unknown, 0 = no, 1 = yes

  @SuppressWarnings("removal")
  private static boolean isCompressedOops() {
    if (compressedOops >= 0) return compressedOops == 1;
    // Probe: create a class with two reference fields and check the offset gap
    try {
      Class<?> probe = ProbeClass.class;
      Field f1 = probe.getDeclaredField("ref1");
      Field f2 = probe.getDeclaredField("ref2");
      long o1 = UNSAFE.objectFieldOffset(f1);
      long o2 = UNSAFE.objectFieldOffset(f2);
      long gap = Math.abs(o2 - o1);
      compressedOops = (gap == 4) ? 1 : 0;
    } catch (Exception e) {
      compressedOops = 1; // assume compressed oops as default
    }
    return compressedOops == 1;
  }

  /** Probe class used only to detect compressed oops. */
  @SuppressWarnings("unused")
  private static class ProbeClass {
    Object ref1;
    Object ref2;
  }

  private static String typeName(Class<?> type) {
    if (type.isArray()) {
      return type.getComponentType().getName() + "[]";
    }
    return type.getName();
  }

  private static String jsonEscape(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static class FieldInfo {
    final long offset;
    final int size;
    final String name;
    final String typeName;
    final String declaringClass;

    FieldInfo(long offset, int size, String name, String typeName, String declaringClass) {
      this.offset = offset;
      this.size = size;
      this.name = name;
      this.typeName = typeName;
      this.declaringClass = declaringClass;
    }
  }
}
