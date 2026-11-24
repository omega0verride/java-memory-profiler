// Run comparison page (export --compare --html). Plain modules, no Vue: the
// data is static. Shares the heap-line continuity rule with the viewer (gaps.js).

import { SOLID, DASHED, BREAK, normalizeGaps, segmentStyle } from './gaps.js';

const D = window.__COMPARE__ || { runs: [], references: [], table: { rows: [], summary: [] } };

// Categorical run colours, the same as Comparison.java (light and dark steps of the same hues).
const LIGHT = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300', '#6250d6', '#e34948'];
const DARK = ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#0ca30c', '#9085e9', '#e66767'];

const runs = D.runs.map((r, i) => ({
  ...r,
  i,
  gapsN: normalizeGaps(r.gaps),
  visible: true,
  marksByKey: Object.fromEntries((r.marks || []).map(m => [m.key, m])),
}));

const view = { xMin: 0, xMax: 1, highlight: null, hoverX: null };
const PAD = { l: 64, r: 150, t: 26, b: 36 };

const $ = (id) => document.getElementById(id);
const canvas = $('chart'), tip = $('tip'), box = $('chartBox');
const ctx = canvas.getContext('2d');

function isDark() { return document.documentElement.getAttribute('data-theme') !== 'light'; }
function color(i) { return (isDark() ? DARK : LIGHT)[i % LIGHT.length]; }
function css(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }

const fmtInt = (n) => Number(n).toLocaleString('en-US');
function fmtMB(mb) {
  if (mb == null) return '—';
  if (mb >= 1024) { let s = (mb / 1024).toFixed(1); if (s.endsWith('.0')) s = s.slice(0, -2); return s + ' GB'; }
  return mb + ' MB';
}
function fmtElapsed(ms, step = 1000) {
  const neg = ms < 0; ms = Math.abs(ms);
  const s = Math.floor(ms / 1000), h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, sec = s % 60;
  let out = (neg ? '-' : '') + (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(sec).padStart(2, '0');
  if (step < 1000) out += '.' + Math.floor((ms % 1000) / 100);
  return out;
}
function fmtDur(ms) {
  if (ms == null) return '—';
  if (ms < 1000) return ms + ' ms';
  if (ms < 60000) return (ms / 1000).toFixed(1) + ' s';
  return fmtElapsed(ms);
}
function fmtValue(v, unit) {
  if (v == null) return '—';
  if (unit === 'ms') return fmtDur(v);
  if (unit === '%') return v + '%';
  return fmtInt(v);
}
function fmtDelta(first, v, unit) {
  if (first == null || v == null) return null;
  const d = v - first;
  const sign = d > 0 ? '+' : d < 0 ? '−' : '±';
  if (unit === '%') return { text: sign + Math.abs(d) + ' pt', d };
  const abs = unit === 'ms' ? fmtDur(Math.abs(d)) : fmtInt(Math.abs(d));
  const pct = first !== 0 ? ' (' + sign + Math.round(Math.abs(100 * d / first)) + '%)' : '';
  return { text: sign + abs + pct, d };
}

// ---- header, legend ----

function el(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (k === 'class') e.className = v;
    else if (k === 'style') Object.assign(e.style, v);
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v);
  }
  for (const k of kids) if (k != null) e.append(k.nodeType ? k : document.createTextNode(String(k)));
  return e;
}

function renderHeader() {
  $('title').textContent = D.title || 'Heap comparison';
  $('subtitle').textContent = D.subtitle || '';
  $('warnings').textContent = (D.warnings || []).join(' · ');
  $('footer').textContent = 'Exported ' + new Date(D.exportedAt).toLocaleString() + ' · java-memory-profiler '
    + (D.profilerVersion || '');
}

function renderLegend() {
  const legend = $('legend');
  legend.replaceChildren();
  for (const r of runs) {
    const chip = el('span', { class: 'chip' + (r.visible ? '' : ' off'), title: (r.source || '') + ' · ' + (r.sampling || ''),
      onclick: () => { r.visible = !r.visible; renderLegend(); renderTable(); draw(); } },
      el('span', { class: 'sw', style: { background: color(r.i) } }),
      el('span', null, r.label),
      el('span', { class: 'meta' }, 'peak ' + fmtMB(r.peakMB) + (r.peakRetainedMB >= 0 ? ' · retained ' + fmtMB(r.peakRetainedMB) : '')
        + ' · ' + fmtDur(r.durationMs)));
    legend.append(chip);
  }
}

// ---- table ----

function renderTable() {
  const t = $('table');
  t.replaceChildren();
  const head = el('tr', null, el('th', null, '#'), el('th', null, 'Mark'));
  for (const r of runs) {
    head.append(el('th', { class: 'num' + (r.visible ? '' : ' off') },
      el('span', { class: 'sw', style: { background: color(r.i) } }), r.label));
  }
  t.append(el('thead', null, head));
  const body = el('tbody', { class: 'marks' });
  for (const row of D.table.rows) {
    const tr = el('tr', { class: view.highlight === row.key ? 'hl' : '', title: 'Highlight this mark in every run',
      onclick: () => highlight(row.key) },
      el('td', { class: 'badge' }, row.badge + (row.occurrence > 1 ? ' (' + row.occurrence + ')' : '')),
      el('td', { title: (row.labels || []).filter(Boolean).join('\n') }, row.text || ''));
    row.cells.forEach((v, i) => tr.append(valueCell(v, i === 0 ? null : fmtDelta(row.cells[0], v, 'MB'), runs[i])));
    body.append(tr);
  }
  t.append(body);
  const sum = el('tbody', { class: 'summary' });
  for (const s of D.table.summary) {
    const tr = el('tr', null, el('td', { colspan: '2' }, s.name));
    s.values.forEach((v, i) => tr.append(valueCell(v, i === 0 ? null : fmtDelta(s.values[0], v, s.unit), runs[i], s.unit)));
    sum.append(tr);
  }
  t.append(sum);
}

function valueCell(v, delta, run, unit = 'MB') {
  const td = el('td', { class: 'num' + (run.visible ? '' : ' off') }, fmtValue(v, unit));
  if (delta) td.append(el('span', { class: 'd ' + (delta.d > 0 ? 'up' : delta.d < 0 ? 'down' : 'same') }, delta.text));
  return td;
}

function highlight(key) {
  view.highlight = view.highlight === key ? null : key;
  if (view.highlight) {
    // make sure every run's mark is in view
    let lo = Infinity, hi = -Infinity;
    for (const r of runs) {
      const m = r.marksByKey[view.highlight];
      if (m && r.visible) { lo = Math.min(lo, m.t); hi = Math.max(hi, m.t); }
    }
    if (lo !== Infinity && (lo < view.xMin || hi > view.xMax)) {
      const span = Math.max(view.xMax - view.xMin, (hi - lo) * 1.2);
      const mid = (lo + hi) / 2;
      view.xMin = mid - span / 2;
      view.xMax = mid + span / 2;
    }
  }
  renderTable();
  draw();
}

// ---- chart ----

function fullRange() {
  let lo = Infinity, hi = -Infinity;
  for (const r of runs) { lo = Math.min(lo, r.startMs); hi = Math.max(hi, r.endMs); }
  if (lo === Infinity) { lo = 0; hi = 1000; }
  const pad = Math.max(500, (hi - lo) * 0.01);
  return [lo - pad, hi + pad];
}

function resetZoom() {
  [view.xMin, view.xMax] = fullRange();
  draw();
}

function niceStep(raw) {
  if (raw <= 0) return 1;
  const exp = Math.floor(Math.log10(raw)), base = Math.pow(10, exp), f = raw / base;
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * base;
}
const TIME_STEPS = [100, 200, 500, 1000, 2000, 5000, 10000, 15000, 30000, 60000, 120000, 300000, 600000, 900000,
  1800000, 3600000];
function timeStep(span, w) {
  const max = Math.max(2, w / 90);
  for (const s of TIME_STEPS) if (span / s <= max) return s;
  return 3600000 * Math.ceil(span / (3600000 * max));
}

function yScale() {
  let top = 16;
  for (const r of runs) if (r.visible) top = Math.max(top, r.peakMB);
  for (const ref of D.references || []) top = Math.max(top, ref.mb);
  const raw = top * 1.08;
  const gb = raw >= 2048, unit = gb ? 1024 : 1;
  const step = Math.max(gb ? 1 : 1, niceStep(raw / unit / 5) * unit);
  return { yMax: Math.ceil(raw / step - 1e-9) * step, step, gb };
}

let geom = null;

function draw() {
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth, h = canvas.clientHeight;
  if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
  }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  const plot = { x: PAD.l, y: PAD.t, w: w - PAD.l - PAD.r, h: h - PAD.t - PAD.b };
  const ys = yScale();
  const xAt = (t) => plot.x + (t - view.xMin) / (view.xMax - view.xMin) * plot.w;
  const yAt = (mb) => plot.y + (1 - mb / ys.yMax) * plot.h;
  geom = { plot, xAt, yAt, ys };

  const grid = css('--grid'), axis = css('--axis'), mute = css('--mute'), fg = css('--fg');
  ctx.font = '11px -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif';
  ctx.lineWidth = 1;
  // y grid
  for (let v = 0; v <= ys.yMax + 1e-6; v += ys.step) {
    const y = Math.round(yAt(v)) + 0.5;
    ctx.strokeStyle = v === 0 ? axis : grid;
    ctx.beginPath(); ctx.moveTo(plot.x, y); ctx.lineTo(plot.x + plot.w, y); ctx.stroke();
    ctx.fillStyle = mute;
    ctx.textAlign = 'right'; ctx.textBaseline = 'middle';
    const label = ys.gb ? (v / 1024).toFixed(ys.step >= 1024 ? 0 : 1) : String(Math.round(v));
    ctx.fillText(label, plot.x - 8, y);
  }
  ctx.textAlign = 'right'; ctx.textBaseline = 'bottom';
  ctx.fillText(ys.gb ? 'GB' : 'MB', plot.x - 8, plot.y - 2 > 10 ? plot.y - 2 : 12);
  // x grid and labels
  const step = timeStep(view.xMax - view.xMin, plot.w);
  ctx.textAlign = 'center'; ctx.textBaseline = 'top';
  let lastRight = -Infinity;
  for (let t = Math.ceil(view.xMin / step) * step; t <= view.xMax; t += step) {
    const x = Math.round(xAt(t)) + 0.5;
    ctx.strokeStyle = grid;
    ctx.beginPath(); ctx.moveTo(x, plot.y); ctx.lineTo(x, plot.y + plot.h); ctx.stroke();
    const label = fmtElapsed(t, step), lw = ctx.measureText(label).width;
    if (x - lw / 2 > lastRight + 6) {
      ctx.fillStyle = mute;
      ctx.fillText(label, x, plot.y + plot.h + 6);
      lastRight = x + lw / 2;
    }
  }
  ctx.textAlign = 'right';
  ctx.fillStyle = css('--faint');
  ctx.fillText(D.alignMark ? 'elapsed since mark ' + D.alignMark + ' (m:ss)' : 'elapsed since start (m:ss)',
    plot.x + plot.w, plot.y + plot.h + 20);

  ctx.save();
  ctx.beginPath(); ctx.rect(plot.x, plot.y - 2, plot.w, plot.h + 4); ctx.clip();
  // reference lines
  const ref = css('--ref');
  for (const r of D.references || []) {
    if (r.mb > ys.yMax) continue;
    const y = yAt(r.mb);
    ctx.strokeStyle = ref; ctx.lineWidth = 1.2; ctx.setLineDash([6, 4]);
    ctx.beginPath(); ctx.moveTo(plot.x, y); ctx.lineTo(plot.x + plot.w, y); ctx.stroke();
    ctx.setLineDash([]);
    const label = r.label || fmtMB(r.mb);
    const lw = ctx.measureText(label).width + 8;
    ctx.fillStyle = css('--label-bg');
    ctx.fillRect(plot.x + 4, y - 17, lw, 15);
    ctx.fillStyle = ref; ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
    ctx.fillText(label, plot.x + 8, y - 9.5);
  }
  // highlighted mark: a vertical line per run
  if (view.highlight) {
    for (const r of runs) {
      const m = r.visible && r.marksByKey[view.highlight];
      if (!m) continue;
      const x = xAt(m.t);
      ctx.strokeStyle = color(r.i); ctx.globalAlpha = 0.5; ctx.lineWidth = 1; ctx.setLineDash([3, 3]);
      ctx.beginPath(); ctx.moveTo(x, plot.y); ctx.lineTo(x, plot.y + plot.h); ctx.stroke();
      ctx.setLineDash([]); ctx.globalAlpha = 1;
    }
  }
  for (const r of runs) if (r.visible) drawRun(r, xAt, yAt);
  ctx.restore();
  drawEndLabels(xAt, yAt, plot, fg);
  drawHover();
  renderKey();
}

function drawRun(r, xAt, yAt) {
  const pts = r.points;
  const solid = new Path2D(), dashed = new Path2D();
  // only the points in view (plus one on each side)
  let lo = lowerBound(pts, view.xMin) - 1, hi = lowerBound(pts, view.xMax) + 1;
  lo = Math.max(1, lo); hi = Math.min(pts.length, hi);
  for (let k = lo; k < hi; k++) {
    const a = pts[k - 1], b = pts[k];
    const style = b[0] >= a[0] ? segmentStyle(r.gapsN, a[0], b[0]) : SOLID;
    if (style === BREAK) continue;
    const p = style === DASHED ? dashed : solid;
    p.moveTo(xAt(a[0]), yAt(a[1]));
    p.lineTo(xAt(b[0]), yAt(b[1]));
  }
  ctx.strokeStyle = color(r.i);
  ctx.lineWidth = 1.6; ctx.lineJoin = 'round'; ctx.lineCap = 'round';
  ctx.stroke(solid);
  ctx.setLineDash([5, 4]); ctx.lineCap = 'butt';
  ctx.stroke(dashed);
  ctx.setLineDash([]);
  const bg = css('--card');
  for (const m of r.marks || []) {
    if (m.t < view.xMin || m.t > view.xMax) continue;
    const hl = view.highlight === m.key;
    ctx.beginPath(); ctx.arc(xAt(m.t), yAt(m.mb), hl ? 5.5 : 3.5, 0, Math.PI * 2);
    ctx.fillStyle = color(r.i); ctx.fill();
    ctx.strokeStyle = hl ? css('--fg') : bg; ctx.lineWidth = hl ? 2 : 1.2; ctx.stroke();
  }
}

function drawEndLabels(xAt, yAt, plot, fg) {
  ctx.font = '12px -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif';
  const items = [];
  for (const r of runs) {
    if (!r.visible || !r.points.length) continue;
    // last point in view
    const k = Math.min(r.points.length - 1, lowerBound(r.points, view.xMax + 1e-9) - 1);
    if (k < 0 || r.points[k][0] < view.xMin) continue;
    const p = r.points[k];
    items.push({ r, x: xAt(p[0]), y: yAt(p[1]) });
  }
  items.sort((a, b) => a.y - b.y);
  const h = 15;
  let prev = plot.y - h;
  for (const it of items) { it.ly = Math.max(it.y - h / 2, prev + h + 1); prev = it.ly; }
  const over = prev + h - (plot.y + plot.h);
  if (over > 0) for (const it of items) it.ly -= over;
  ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
  for (const it of items) {
    const lx = Math.min(it.x + 8, plot.x + plot.w + 8);
    ctx.strokeStyle = color(it.r.i); ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(it.x + 2, it.y); ctx.lineTo(lx - 2, it.ly + h / 2); ctx.stroke();
    ctx.fillStyle = fg;
    const maxW = canvas.clientWidth - lx - 4;
    let text = it.r.label;
    while (text.length > 1 && ctx.measureText(text).width > maxW) text = text.slice(0, -2) + '…';
    ctx.fillText(text, lx, it.ly + h / 2);
  }
}

function lowerBound(pts, t) {
  let lo = 0, hi = pts.length;
  while (lo < hi) { const mid = (lo + hi) >>> 1; if (pts[mid][0] < t) lo = mid + 1; else hi = mid; }
  return lo;
}

/** The value of a run at elapsed time t: the reading at or before t, unless t falls in a break or outside the run. */
function valueAt(r, t) {
  const pts = r.points;
  if (!pts.length || t < pts[0][0] || t > pts[pts.length - 1][0]) return null;
  const k = Math.max(0, lowerBound(pts, t + 1e-9) - 1);
  const a = pts[k], b = pts[Math.min(pts.length - 1, k + 1)];
  if (b !== a && segmentStyle(r.gapsN, a[0], b[0]) === BREAK) return null;
  return a[1];
}

function drawHover() {
  if (view.hoverX == null || !geom) { tip.style.display = 'none'; return; }
  const { plot, xAt } = geom;
  const x = view.hoverX;
  if (x < plot.x || x > plot.x + plot.w) { tip.style.display = 'none'; return; }
  const t = view.xMin + (x - plot.x) / plot.w * (view.xMax - view.xMin);
  ctx.strokeStyle = css('--mute'); ctx.globalAlpha = 0.6; ctx.lineWidth = 1; ctx.setLineDash([4, 3]);
  ctx.beginPath(); ctx.moveTo(x + 0.5, plot.y); ctx.lineTo(x + 0.5, plot.y + plot.h); ctx.stroke();
  ctx.setLineDash([]); ctx.globalAlpha = 1;
  const snap = 6 / plot.w * (view.xMax - view.xMin);
  const rows = [];
  for (const r of runs) {
    if (!r.visible) continue;
    const v = valueAt(r, t);
    let mark = null;
    for (const m of r.marks || []) if (Math.abs(m.t - t) <= snap) mark = m;
    rows.push({ r, v, mark });
    if (v != null) {
      ctx.beginPath(); ctx.arc(x, geom.yAt(v), 3, 0, Math.PI * 2);
      ctx.fillStyle = color(r.i); ctx.fill();
    }
  }
  tip.replaceChildren(el('div', { class: 't' }, fmtElapsed(t, 100)));
  for (const { r, v, mark } of rows) {
    tip.append(el('div', { class: 'r' },
      el('span', { class: 'sw', style: { background: color(r.i) } }),
      el('span', null, r.label),
      mark ? el('span', { class: 'm' }, '#' + mark.badge) : null,
      el('span', { class: 'v' }, v == null ? '—' : fmtInt(v) + ' MB')));
  }
  tip.style.display = 'block';
  const bw = box.clientWidth, tw = tip.offsetWidth;
  tip.style.left = (x + 14 + tw > bw ? x - 14 - tw : x + 14) + 'px';
  tip.style.top = (plot.y + 8) + 'px';
}

function renderKey() {
  const parts = runs.filter(r => r.visible).map(r => r.label + ': ' + (r.sampling || ''));
  $('key').textContent = 'Lines: heap readings (include garbage); dots: marks; dashed: the sampler was stalled by GC '
    + '(no sample for over ' + fmtDur(runs[0] ? runs[0].gapsN.dashAfterMs : 1000) + '); a gap: no observation. '
    + parts.join(' · ');
}

// ---- interaction ----

let drag = null;
canvas.addEventListener('pointerdown', (e) => {
  canvas.setPointerCapture(e.pointerId);
  drag = { x: e.clientX, xMin: view.xMin, xMax: view.xMax };
});
canvas.addEventListener('pointermove', (e) => {
  const rect = canvas.getBoundingClientRect();
  view.hoverX = e.clientX - rect.left;
  if (drag && geom) {
    const dt = -(e.clientX - drag.x) / geom.plot.w * (drag.xMax - drag.xMin);
    view.xMin = drag.xMin + dt;
    view.xMax = drag.xMax + dt;
  }
  draw();
});
canvas.addEventListener('pointerup', () => { drag = null; });
canvas.addEventListener('pointerleave', () => { view.hoverX = null; draw(); });
canvas.addEventListener('wheel', (e) => {
  if (!geom) return;
  e.preventDefault();
  const rect = canvas.getBoundingClientRect();
  const frac = Math.max(0, Math.min(1, (e.clientX - rect.left - geom.plot.x) / geom.plot.w));
  const anchor = view.xMin + frac * (view.xMax - view.xMin);
  const span = Math.max(1000, (view.xMax - view.xMin) * Math.exp(e.deltaY * 0.0015));
  view.xMin = anchor - frac * span;
  view.xMax = anchor + (1 - frac) * span;
  draw();
}, { passive: false });
canvas.addEventListener('dblclick', resetZoom);
$('reset').addEventListener('click', resetZoom);
$('theme').addEventListener('click', () => {
  const t = isDark() ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', t);
  try { localStorage.setItem('memprofiler.theme', t); } catch (e) { /* private mode */ }
  renderLegend(); renderTable(); draw();
});
window.addEventListener('resize', draw);

renderHeader();
renderLegend();
renderTable();
resetZoom();
