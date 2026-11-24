// Mark annotations: badge numbering, marks table rows, scope bands and the
// collision-avoiding layouts for badges and band titles.
//
// Keep in step with Annotations.java (the PNG renderer): same constants, same
// placement order, same tie-breaking. Only text widths differ (browser fonts
// vs Java2D fonts).

export const BADGE_H = 16;
export const BADGE_PAD_X = 5;
export const BADGE_GAP = 3;
export const DOT_GAP = 7;
export const DOT_R = 4;
export const TITLE_ROW_H = 15;
export const TITLE_PAD_X = 3;
export const TITLE_GAP = 8;
export const TITLE_MAX_W = 140;
export const BAND_AREA = 2 * TITLE_ROW_H + 8;

const NUMBERED = /^\s*(\d+)\.(?:\s+|$)/;

/** Thousands separators for integers of five or more digits in a label (Annotations.formatNumbers). */
export function formatNumbers(text) {
  if (text == null) return text;
  return String(text).replace(/(?<![\d.,])\d{5,}(?![.,]\d)/g, (d) => Number(d).toLocaleString('en-US'));
}

export function isAnnotated(kind) {
  return kind === 'MARK' || kind === 'SCOPE_START' || kind === 'SCOPE_END';
}

/** "com.acme.Loader.load:42" -> "Loader.load:42" */
export function shortLocation(location) {
  if (!location) return '';
  let loc = location, line = '';
  const colon = loc.lastIndexOf(':');
  if (colon > 0 && /^\d+$/.test(loc.substring(colon + 1))) {
    line = loc.substring(colon);
    loc = loc.substring(0, colon);
  }
  const methodDot = loc.lastIndexOf('.');
  if (methodDot <= 0) return location;
  const cls = loc.substring(0, methodDot);
  const method = loc.substring(methodDot + 1);
  const simple = cls.substring(cls.lastIndexOf('.') + 1);
  return simple + '.' + method + line;
}

/**
 * One row per annotated point (MARK, SCOPE_START, SCOPE_END), numbered over the
 * whole run: badge = the label's own "N." prefix (numbers may repeat); other
 * points are numbered sequentially after the highest explicit number, so the
 * two kinds never share a badge.
 */
export function buildRows(points) {
  let maxExplicit = 0;
  for (const p of points) {
    if (!isAnnotated(p.kind)) continue;
    const m = NUMBERED.exec(p.label == null ? '' : String(p.label));
    if (m) {
      const digits = m[1].replace(/^0+(?=\d)/, '');
      if (digits.length <= 9) maxExplicit = Math.max(maxExplicit, Number(digits));
    }
  }
  const rows = [];
  let prev = null;
  let seq = 0;
  let nextImplicit = maxExplicit;
  const runStart = points.length ? points[0].timestamp : 0;
  for (const p of points) {
    if (!isAnnotated(p.kind)) continue;
    seq++;
    const label = p.label == null ? '' : String(p.label);
    const m = NUMBERED.exec(label);
    let badge, text;
    if (m) {
      badge = m[1].replace(/^0+(?=\d)/, '');
      text = label.substring(m[0].length).trim();
    } else {
      badge = String(++nextImplicit);
      text = label.trim();
    }
    if (!text) text = p.kind === 'MARK' ? '(mark)' : '(scope)';
    rows.push({
      point: p,
      seq,
      badge,
      text,
      deltaPrevMB: prev == null ? null : p.usedMB - prev.point.usedMB,
      elapsedMs: p.timestamp - runStart,
      shortLocation: shortLocation(p.location),
    });
    prev = rows[rows.length - 1];
  }
  return rows;
}

/** Pairs SCOPE_START / SCOPE_END by scopeId; ordered by start time, then index. */
export function buildBands(points) {
  const byId = new Map();
  if (!points.length) return [];
  const firstTs = points[0].timestamp, lastTs = points[points.length - 1].timestamp;
  for (const p of points) {
    if (!p.scopeId) continue;
    if (p.kind === 'SCOPE_START') {
      byId.set(p.scopeId, { scopeId: p.scopeId, label: p.label || p.scopeId, startTs: p.timestamp,
        endTs: lastTs, open: true, order: p.index });
    } else if (p.kind === 'SCOPE_END') {
      let b = byId.get(p.scopeId);
      if (!b) {
        b = { scopeId: p.scopeId, label: p.label || p.scopeId, startTs: firstTs, endTs: p.timestamp,
          open: true, order: -Number.MAX_SAFE_INTEGER + p.index };
        byId.set(p.scopeId, b);
      } else {
        b.open = false;
      }
      b.endTs = p.timestamp;
    }
  }
  return Array.from(byId.values()).sort((a, b) =>
    a.startTs !== b.startTs ? a.startTs - b.startTs : a.order - b.order);
}

function near(a, b, gap) {
  return a.x < b.x + b.w + gap && b.x < a.x + a.w + gap && a.y < b.y + b.h + gap && b.y < a.y + a.h + gap;
}

export function clamp(v, lo, hi) {
  if (hi < lo) return lo;
  return v < lo ? lo : v > hi ? hi : v;
}

/**
 * Places badges in order. Preferred slot: centred, bottom DOT_GAP above the
 * dot. If taken, move up one slot (BADGE_H + BADGE_GAP) at a time to the top of
 * the plot, then try the slots below the preferred one; else hidden.
 * items: [{ ax, ay, w }], plot: { x, y, w, h }, obstacles: [{ x, y, w, h }]
 * returns [{ rect, hidden, leader, below }]
 */
export function layoutBadges(items, plot, obstacles) {
  const placed = obstacles ? obstacles.slice() : [];
  const out = [];
  const step = BADGE_H + BADGE_GAP;
  const top = plot.y, bottom = plot.y + plot.h, left = plot.x, right = plot.x + plot.w;
  const free = (r) => {
    for (let i = 0; i < placed.length; i++) if (near(r, placed[i], BADGE_GAP)) return false;
    return true;
  };
  for (const it of items) {
    const w = Math.min(Math.max(BADGE_H, it.w), plot.w);
    const x = clamp(it.ax - w / 2, left, right - w);
    const pref = it.ay - DOT_GAP - BADGE_H;
    const y0 = clamp(pref, top, bottom - BADGE_H);
    let hit = null;
    for (let y = y0; y >= top - 1e-6; y -= step) {
      const r = { x, y, w, h: BADGE_H };
      if (free(r)) { hit = r; break; }
    }
    if (!hit) {
      for (let y = y0 + step; y + BADGE_H <= bottom + 1e-6; y += step) {
        const r = { x, y, w, h: BADGE_H };
        if (free(r)) { hit = r; break; }
      }
    }
    if (!hit) {
      out.push({ rect: null, hidden: true, leader: false, below: false });
    } else {
      placed.push(hit);
      out.push({ rect: hit, hidden: false, leader: Math.abs(hit.y - pref) > 0.5, below: hit.y > it.ay });
    }
  }
  return out;
}

/** text, or a prefix + "…", that fits maxW (with padding); null if nothing fits. */
export function fitText(text, maxW, measure) {
  if (measure(text) + 2 * TITLE_PAD_X <= maxW) return text;
  for (let n = text.length - 1; n >= 1; n--) {
    const c = text.substring(0, n).replace(/\s+$/, '') + '…';
    if (measure(c) + 2 * TITLE_PAD_X <= maxW) return c;
  }
  return null;
}

/**
 * Band titles on two rows above the plot, in band order. Centred over the
 * visible part of the band, cut to max(band width, TITLE_MAX_W); row 0 first,
 * then row 1; may slide right past the previous title but must start inside
 * its band; else hidden.
 * items: [{ x1, x2, text }] -> [{ hidden, row, x, w, text }]
 */
export function layoutBandTitles(items, left, right, measure) {
  const rowRight = [-Infinity, -Infinity];
  const out = [];
  for (const it of items) {
    const tp = { hidden: true, row: 0, x: 0, w: 0, text: '' };
    const vx1 = Math.max(it.x1, left), vx2 = Math.min(it.x2, right);
    const full = it.text || '';
    if (vx2 < vx1 || !full) { out.push(tp); continue; }
    const cap = Math.min(right - left, Math.max(vx2 - vx1, TITLE_MAX_W));
    const text = fitText(full, cap, measure);
    if (text == null) { out.push(tp); continue; }
    const w = measure(text) + 2 * TITLE_PAD_X;
    const cx = (vx1 + vx2) / 2;
    for (let row = 0; row < 2; row++) {
      let x = clamp(cx - w / 2, left, right - w);
      x = Math.max(x, rowRight[row] + TITLE_GAP);
      if (x > Math.max(vx2 - 2, vx1)) continue;
      let txt = text, tw = w;
      if (x + tw > right) {
        txt = fitText(text, right - x, measure);
        if (txt == null) continue;
        tw = measure(txt) + 2 * TITLE_PAD_X;
      }
      tp.hidden = false;
      tp.row = row;
      tp.x = x;
      tp.w = tw;
      tp.text = txt;
      rowRight[row] = x + tw;
      break;
    }
    out.push(tp);
  }
  return out;
}

function channel(c) {
  const v = c / 255;
  return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
}

/** Text colour for a badge fill (WCAG crossover luminance, as in PngRenderer.badgeText). */
export function badgeTextColor(fill) {
  const rgb = parseColor(fill);
  if (!rgb) return '#111827';
  const l = 0.2126 * channel(rgb[0]) + 0.7152 * channel(rgb[1]) + 0.0722 * channel(rgb[2]);
  return l > 0.179 ? '#111827' : '#ffffff';
}

function parseColor(c) {
  if (!c) return null;
  c = String(c).trim();
  let m = /^#([0-9a-f]{6})$/i.exec(c);
  if (m) {
    const v = parseInt(m[1], 16);
    return [(v >> 16) & 255, (v >> 8) & 255, v & 255];
  }
  m = /^#([0-9a-f]{3})$/i.exec(c);
  if (m) return m[1].split('').map(h => parseInt(h + h, 16));
  m = /^rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)/i.exec(c);
  if (m) return [Number(m[1]), Number(m[2]), Number(m[3])];
  return null;
}
