package com.toro.al.memprofiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny JSON reader and string writer, just enough for JSONL lines and the
 * export payloads (the jar bundles no JSON library).
 *
 * <p>Objects parse to {@link LinkedHashMap}, arrays to {@link ArrayList},
 * integers to {@link Long}, other numbers to {@link Double}.
 */
final class MiniJson {

  private final String s;
  private int i;

  private MiniJson(String s) {
    this.s = s;
  }

  static Object parse(String text) {
    MiniJson p = new MiniJson(text);
    p.ws();
    Object v = p.value();
    p.ws();
    if (p.i != p.s.length()) throw p.error("trailing characters");
    return v;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> parseObject(String text) {
    Object v = parse(text);
    if (!(v instanceof Map)) throw new IllegalArgumentException("not a JSON object");
    return (Map<String, Object>) v;
  }

  private Object value() {
    if (i >= s.length()) throw error("unexpected end");
    char c = s.charAt(i);
    switch (c) {
      case '{': return object();
      case '[': return array();
      case '"': return string();
      case 't': return literal("true", Boolean.TRUE);
      case 'f': return literal("false", Boolean.FALSE);
      case 'n': return literal("null", null);
      default:
        if (c == '-' || (c >= '0' && c <= '9')) return number();
        throw error("unexpected '" + c + "'");
    }
  }

  private Map<String, Object> object() {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    i++;
    ws();
    if (peek() == '}') { i++; return m; }
    while (true) {
      ws();
      if (peek() != '"') throw error("expected key");
      String k = string();
      ws();
      expect(':');
      ws();
      m.put(k, value());
      ws();
      char c = next();
      if (c == '}') return m;
      if (c != ',') throw error("expected ',' or '}'");
    }
  }

  private List<Object> array() {
    List<Object> a = new ArrayList<Object>();
    i++;
    ws();
    if (peek() == ']') { i++; return a; }
    while (true) {
      ws();
      a.add(value());
      ws();
      char c = next();
      if (c == ']') return a;
      if (c != ',') throw error("expected ',' or ']'");
    }
  }

  private String string() {
    expect('"');
    StringBuilder sb = null;
    int start = i;
    while (true) {
      if (i >= s.length()) throw error("unterminated string");
      char c = s.charAt(i);
      if (c == '"') {
        String out = sb == null ? s.substring(start, i) : sb.append(s, start, i).toString();
        i++;
        return out;
      }
      if (c == '\\') {
        if (sb == null) sb = new StringBuilder();
        sb.append(s, start, i);
        i++;
        if (i >= s.length()) throw error("bad escape");
        char e = s.charAt(i++);
        switch (e) {
          case '"': sb.append('"'); break;
          case '\\': sb.append('\\'); break;
          case '/': sb.append('/'); break;
          case 'b': sb.append('\b'); break;
          case 'f': sb.append('\f'); break;
          case 'n': sb.append('\n'); break;
          case 'r': sb.append('\r'); break;
          case 't': sb.append('\t'); break;
          case 'u':
            if (i + 4 > s.length()) throw error("bad unicode escape");
            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
            i += 4;
            break;
          default: throw error("bad escape");
        }
        start = i;
        continue;
      }
      i++; // raw control characters are tolerated (older JSONL lines may contain them)
    }
  }

  private Object number() {
    int start = i;
    if (peek() == '-') i++;
    boolean integral = true;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c >= '0' && c <= '9') { i++; continue; }
      if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { integral = false; i++; continue; }
      break;
    }
    String t = s.substring(start, i);
    try {
      if (integral) return Long.valueOf(Long.parseLong(t));
      return Double.valueOf(Double.parseDouble(t));
    } catch (NumberFormatException e) {
      throw error("bad number " + t);
    }
  }

  private Object literal(String word, Object v) {
    if (!s.startsWith(word, i)) throw error("unexpected token");
    i += word.length();
    return v;
  }

  private void ws() {
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
      else break;
    }
  }

  private char peek() {
    return i < s.length() ? s.charAt(i) : '\0';
  }

  private char next() {
    if (i >= s.length()) throw error("unexpected end");
    return s.charAt(i++);
  }

  private void expect(char c) {
    if (next() != c) throw error("expected '" + c + "'");
  }

  private IllegalArgumentException error(String msg) {
    return new IllegalArgumentException("JSON: " + msg + " at offset " + i);
  }

  // ---- writing ----

  /**
   * Appends {@code v} as a JSON string literal that is also safe to embed in an
   * HTML {@code <script>} element: {@code <}, {@code >}, {@code &} and the JS line
   * separators are written as {@code \\uXXXX}, so a label such as
   * {@code "</script>"} can never close the element.
   */
  static StringBuilder quote(StringBuilder sb, String v) {
    if (v == null) return sb.append("null");
    sb.append('"');
    for (int k = 0, n = v.length(); k < n; k++) {
      char c = v.charAt(k);
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        case '<': sb.append("\\u003c"); break;
        case '>': sb.append("\\u003e"); break;
        case '&': sb.append("\\u0026"); break;
        case ' ': sb.append("\\u2028"); break;
        case ' ': sb.append("\\u2029"); break;
        default:
          if (c < 0x20) {
            sb.append("\\u00");
            sb.append(Character.forDigit((c >> 4) & 0xF, 16));
            sb.append(Character.forDigit(c & 0xF, 16));
          } else {
            sb.append(c);
          }
      }
    }
    return sb.append('"');
  }

  static String quote(String v) {
    return quote(new StringBuilder(v == null ? 4 : v.length() + 8), v).toString();
  }

  static long asLong(Object v, long dflt) {
    if (v instanceof Number) return ((Number) v).longValue();
    return dflt;
  }

  static Long asLongOrNull(Object v) {
    if (v instanceof Number) return Long.valueOf(((Number) v).longValue());
    return null;
  }

  static String asString(Object v) {
    return v instanceof String ? (String) v : null;
  }
}
