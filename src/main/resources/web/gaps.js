// Heap line continuity: solid, dashed (the sampler stalled while the JVM was in
// GC) or broken (no observation, e.g. a paused VM).
//
// The rule itself lives in GapRule.java and is computed by the server (live
// /data) or the exporter (HTML snapshot), so the live chart, the HTML export and
// the PNG draw the same line. This module only looks segments up.

export const SOLID = 0;
export const DASHED = 1;
export const BREAK = 2;

/** { intervalMs, dashAfterMs, breakAfterMs, list: [[from, to, bridged, gcMs], ...] } -> normalized */
export function normalizeGaps(g) {
  if (!g || !Array.isArray(g.list)) return { dashAfterMs: Infinity, list: [] };
  const list = g.list.map(a => ({ from: a[0], to: a[1], bridged: !!a[2], gcMs: a[3] || 0 }));
  list.sort((x, y) => x.from - y.from);
  return {
    intervalMs: g.intervalMs || 0,
    dashAfterMs: g.dashAfterMs > 0 ? g.dashAfterMs : 1000,
    breakAfterMs: g.breakAfterMs || 0,
    list,
  };
}

/** Style of the line segment between timestamps a < b. Mirrors GapRule.Result.style. */
export function segmentStyle(gaps, a, b) {
  if (!gaps || !gaps.list.length || b - a <= gaps.dashAfterMs) return SOLID;
  const list = gaps.list;
  let lo = 0, hi = list.length;
  while (lo < hi) { // first gap with to > a
    const mid = (lo + hi) >>> 1;
    if (list[mid].to > a) hi = mid; else lo = mid + 1;
  }
  let style = SOLID;
  for (let i = lo; i < list.length && list[i].from < b; i++) {
    if (!list[i].bridged) return BREAK;
    style = DASHED;
  }
  return style;
}

/** Every point except REFERENCE is a heap reading (a REFERENCE's usedMB is a line value). */
export function isHeapReading(p) {
  return !!p && p.kind !== 'REFERENCE';
}

/** Splits REFERENCE points out of a point list: { points, references }. */
export function splitReferences(points) {
  const pts = [], refs = [];
  for (const p of points || []) (p && p.kind === 'REFERENCE' ? refs : pts).push(p);
  return { points: pts, references: refs };
}
