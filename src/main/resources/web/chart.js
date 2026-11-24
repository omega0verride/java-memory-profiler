import { watchEffect } from 'vue';
import { state, VIEW, getTimeRange, freezeView, ensureVisible, selectPoint, selectRangePoint } from './state.js';
import { setAutoGc } from './api.js';
import { fmtTs } from './util.js';
import {
  BADGE_H, BADGE_PAD_X, DOT_GAP, DOT_R, TITLE_ROW_H, TITLE_PAD_X,
  isAnnotated, buildRows, buildBands, layoutBadges, layoutBandTitles, badgeTextColor,
} from './annotations.js';

let canvas, ctx, chartWrap;
let dragState = null;
let mouseY = null; // track mouse Y for horizontal crosshair

// Colors come from CSS variables so the chart follows the light/dark theme.
// The fallbacks are the original dark-theme values.
const KIND_VARS = {
  MARK: ['--mark', '#ffb020'],
  SCOPE_START: ['--start', '#37c26a'],
  SCOPE_END: ['--end', '#ff6e6e'],
  GC: ['--gc', '#4ea1ff'],
  HEAP_DUMP: ['--heapdump', '#e040fb'],
  AUTO_GC: ['--auto_gc', '#a3e635'],
  SAMPLE: ['--gc', '#4ea1ff'],
};
const CHART_VARS = {
  bg: ['--bg', '#0d1017'],
  grid: ['--chart-grid', '#1c222d'],
  axis: ['--chart-axis', '#6a7384'],
  line: ['--chart-line', '#4ea1ff'],
  time: ['--chart-time', '#8a93a3'],
  track: ['--chart-track', '#2a3242'],
  labelBg: ['--chart-label-bg', 'rgba(15,17,22,0.85)'],
  crosshair: ['--chart-crosshair', 'rgba(138,147,163,0.45)'],
  crosshairText: ['--chart-crosshair-text', '#c8cdd5'],
  shapeStroke: ['--chart-shape-stroke', 'rgba(255,255,255,0.3)'],
  selected: ['--chart-selected', '#ffffff'],
  hoverCursor: ['--chart-hover', '#8a93a3'],
  threshold: ['--chart-threshold', '#ff6e6e'],
  thresholdText: ['--chart-threshold-text', '#ff8a8a'],
  range: ['--chart-range', '#f0c040'],
  rangeFill: ['--chart-range-fill', 'rgba(240, 192, 64, 0.08)'],
  rangeEdge: ['--chart-range-edge', 'rgba(240, 192, 64, 0.3)'],
  bandFill: ['--band-fill', 'rgba(255,255,255,0.05)'],
  bandEdge: ['--band-edge', 'rgba(255,255,255,0.14)'],
  bandText: ['--band-text', '#8791a5'],
  refLine: ['--ref-line', '#ff6e6e'],
  refMax: ['--ref-max', '#8791a5'],
};
let colorCache = { key: null, c: null, k: null };

function colors() {
  const key = state.theme + '|' + (document.documentElement.getAttribute('data-theme') || '');
  if (colorCache.key === key) return colorCache.c;
  const cs = getComputedStyle(document.documentElement);
  const read = ([name, dflt]) => (cs.getPropertyValue(name) || '').trim() || dflt;
  const c = {}, k = {};
  for (const [n, v] of Object.entries(CHART_VARS)) c[n] = read(v);
  for (const [n, v] of Object.entries(KIND_VARS)) k[n] = read(v);
  colorCache = { key, c, k };
  return c;
}

function getKindColor(kind) {
  colors();
  return colorCache.k[kind] || colorCache.k.SAMPLE;
}

function getPointColor(p) {
  return getKindColor(p.kind);
}

// Draw distinct shapes per event kind for visual differentiation
function drawEventShape(ctx, x, y, kind) {
  ctx.beginPath();
  switch (kind) {
    case 'MARK':
      // Diamond
      ctx.moveTo(x, y - 5);
      ctx.lineTo(x + 5, y);
      ctx.lineTo(x, y + 5);
      ctx.lineTo(x - 5, y);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
      break;
    case 'SCOPE_START':
      // Triangle pointing right
      ctx.moveTo(x - 4, y - 5);
      ctx.lineTo(x + 5, y);
      ctx.lineTo(x - 4, y + 5);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
      break;
    case 'SCOPE_END':
      // Square
      ctx.rect(x - 4, y - 4, 8, 8);
      ctx.fill();
      ctx.stroke();
      break;
    case 'GC':
      // Small circle
      ctx.arc(x, y, 3, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      break;
    case 'HEAP_DUMP':
      // Star (4-pointed)
      for (let i = 0; i < 8; i++) {
        const a = (Math.PI / 4) * i - Math.PI / 2;
        const r = i % 2 === 0 ? 6 : 3;
        const px = x + Math.cos(a) * r;
        const py = y + Math.sin(a) * r;
        if (i === 0) ctx.moveTo(px, py);
        else ctx.lineTo(px, py);
      }
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
      break;
    case 'AUTO_GC':
      // Triangle pointing down
      ctx.moveTo(x, y + 5);
      ctx.lineTo(x - 5, y - 4);
      ctx.lineTo(x + 5, y - 4);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
      break;
    default:
      // Fallback circle
      ctx.arc(x, y, 4, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      break;
  }
}

export function mountChart(canvasEl, chartWrapEl) {
  canvas = canvasEl;
  ctx = canvas.getContext('2d');
  chartWrap = chartWrapEl;
  installEvents();
  resize();
  window.addEventListener('resize', resize);
  // The canvas also changes size without a window resize (marks table shown or
  // collapsed, header wrapping), so watch the element itself.
  if (typeof ResizeObserver !== 'undefined') {
    let last = '';
    new ResizeObserver(() => {
      const key = canvas.clientWidth + 'x' + canvas.clientHeight;
      if (key !== last) { last = key; resize(); }
    }).observe(canvas);
  }
  watchEffect(draw);
}

export { ensureVisible };

function resize() {
  const r = canvas.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 1;
  canvas.width = r.width * dpr;
  canvas.height = r.height * dpr;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  draw();
}

function computeGapBreakThreshold() {
  // Estimate typical sampling interval from the most recent points and
  // treat any larger gap as a pause (e.g. debugger breakpoint) so the
  // line is broken instead of drawing a diagonal "bridge".
  const pts = state.points;
  if (pts.length < 3) return 2000;
  const start = Math.max(1, pts.length - 100);
  const gaps = [];
  for (let i = start; i < pts.length; i++) {
    const dt = pts[i].timestamp - pts[i - 1].timestamp;
    if (dt > 0) gaps.push(dt);
  }
  if (!gaps.length) return 2000;
  gaps.sort((a, b) => a - b);
  const median = gaps[Math.floor(gaps.length / 2)];
  return Math.max(3 * median, 1500);
}

function getYMax() {
  if (!state.yScaleAuto && state.yScaleMax != null) {
    return state.yScaleMax;
  }
  let yMax = Math.max(state.maxObservedMB, 16) * 1.1;
  if (state.gcThresholdEnabled && state.gcThresholdMB > 0) {
    yMax = Math.max(yMax, state.gcThresholdMB * 1.15);
  }
  if (state.annotations) {
    // user reference lines are always in view; the JVM max only when it is close
    for (const r of state.references) yMax = Math.max(yMax, r.usedMB * 1.08);
    const jvmMax = jvmMaxMB();
    if (jvmMax > 0 && jvmMax <= state.maxObservedMB * 1.5) yMax = Math.max(yMax, jvmMax * 1.08);
    // room for one badge above the highest mark
    const h = canvas ? canvas.clientHeight - VIEW.PAD_T - VIEW.PAD_B : 0;
    const room = DOT_GAP + BADGE_H + 6;
    const peakDot = annotationModel().peakDot;
    if (peakDot > 0 && h > room * 2) yMax = Math.max(yMax, peakDot * h / (h - room));
  }
  return yMax;
}

// ---- annotation model (rebuilt only when the points array changes) ----

let annoCache = { points: null, rows: [], bands: [], peakDot: 0 };

function annotationModel() {
  if (annoCache.points !== state.points) {
    const rows = buildRows(state.points);
    let peakDot = 0;
    for (const r of rows) if (r.point.usedMB > peakDot) peakDot = r.point.usedMB;
    annoCache = { points: state.points, rows, bands: buildBands(state.points), peakDot };
  }
  return annoCache;
}

export function markRows() {
  return annotationModel().rows;
}

function jvmMaxMB() {
  const pts = state.points;
  for (let i = pts.length - 1; i >= 0; i--) {
    if (pts[i].kind === 'SAMPLE' && pts[i].maxMB > 0) return pts[i].maxMB;
  }
  return state.meta && state.meta.jvmMaxHeapMB > 0 ? state.meta.jvmMaxHeapMB : 0;
}

function fmtRefMB(mb) {
  if (mb >= 1024) {
    let s = (mb / 1024).toFixed(1);
    if (s.endsWith('.0')) s = s.slice(0, -2);
    return s + ' GB';
  }
  return mb + ' MB';
}

// Badge rectangles of the last draw, for click hit-testing: [{ rect, point }]
let badgeHits = [];

function draw() {
  if (!canvas) return;
  const w = canvas.clientWidth, h = canvas.clientHeight;
  ctx.clearRect(0, 0, w, h);
  badgeHits = [];
  if (!state.points.length) return;
  const C = colors();

  const padL = VIEW.PAD_L, padR = VIEW.PAD_R, padT = VIEW.PAD_T, padB = VIEW.PAD_B;
  const plotW = w - padL - padR, plotH = h - padT - padB;

  const range = getTimeRange();
  const tMin = range.tMin, tMax = range.tMax;
  const yMax = getYMax();
  const yMin = 0;

  const xAt = (ts) => padL + ((ts - tMin) / (tMax - tMin)) * plotW;
  const yAt = (mb) => padT + (1 - (mb - yMin) / (yMax - yMin)) * plotH;

  const anno = state.annotations ? annotationModel() : null;
  if (anno) drawBands(anno.bands, xAt, padL, padT, plotW, plotH, tMin, tMax, C);

  ctx.strokeStyle = C.grid;
  ctx.fillStyle = C.axis;
  ctx.font = '11px Consolas, Menlo, monospace';
  ctx.lineWidth = 1;
  for (let i = 0; i <= 4; i++) {
    const y = padT + (plotH * i / 4);
    ctx.beginPath(); ctx.moveTo(padL, y); ctx.lineTo(w - padR, y); ctx.stroke();
    const v = Math.round(yMax - (yMax - yMin) * i / 4);
    ctx.fillText(v + ' MB', 4, y + 4);
  }

  if (state.gcThresholdEnabled && state.gcThresholdMB > 0 && state.gcThresholdMB <= yMax) {
    const ty = yAt(state.gcThresholdMB);
    ctx.save();
    ctx.strokeStyle = C.threshold;
    ctx.lineWidth = 1;
    ctx.setLineDash([5, 4]);
    ctx.beginPath(); ctx.moveTo(padL, ty); ctx.lineTo(w - padR, ty); ctx.stroke();
    ctx.setLineDash([]);
    const lbl = 'auto-GC ' + state.gcThresholdMB + ' MB';
    ctx.font = '11px Consolas, Menlo, monospace';
    const tw = ctx.measureText(lbl).width;
    ctx.fillStyle = C.labelBg;
    ctx.fillRect(w - padR - tw - 8, ty - 14, tw + 6, 14);
    ctx.fillStyle = C.thresholdText;
    ctx.fillText(lbl, w - padR - tw - 5, ty - 3);
    ctx.restore();
  }

  ctx.save();
  ctx.beginPath();
  ctx.rect(padL, padT, plotW, plotH);
  ctx.clip();
  ctx.strokeStyle = C.line;
  ctx.lineWidth = 1.3;
  ctx.beginPath();
  const gapBreakMs = computeGapBreakThreshold();
  let started = false;
  let prevTs = 0;
  for (const p of state.points) {
    if (p.timestamp < tMin - 1000 || p.timestamp > tMax + 1000) { started = false; continue; }
    const x = xAt(p.timestamp);
    const y = yAt(p.usedMB);
    if (!started || (p.timestamp - prevTs) > gapBreakMs) {
      ctx.moveTo(x, y);
      started = true;
    } else {
      ctx.lineTo(x, y);
    }
    prevTs = p.timestamp;
  }
  ctx.stroke();
  
  // Draw color-coded shapes for non-SAMPLE events on the line
  for (const p of state.points) {
    if (p.kind === 'SAMPLE') continue;
    if (anno && isAnnotated(p.kind)) continue; // drawn as dots with badges below
    if (p.timestamp < tMin - 1000 || p.timestamp > tMax + 1000) continue;
    const x = xAt(p.timestamp);
    const y = yAt(p.usedMB);
    const color = getPointColor(p);
    
    ctx.fillStyle = color;
    ctx.strokeStyle = C.shapeStroke;
    ctx.lineWidth = 1;
    drawEventShape(ctx, x, y, p.kind);
  }
  
  ctx.restore();

  let obstacles = [];
  if (anno) obstacles = drawReferences(yAt, yMax, padL, padT, plotW, plotH, C);
  if (anno) drawBadges(anno.rows, xAt, yAt, { x: padL, y: padT, w: plotW, h: plotH }, tMin, tMax, obstacles, C);
  if (anno) drawBandTitles(anno.bands, xAt, padL, padT, plotW, tMin, tMax, C);

  const trackY = h - padB + 4;
  ctx.strokeStyle = C.track;
  ctx.lineWidth = 1;
  ctx.beginPath(); ctx.moveTo(padL, trackY); ctx.lineTo(w - padR, trackY); ctx.stroke();

  for (const p of state.points) {
    if (p.kind === 'SAMPLE') continue;
    if (p.timestamp < tMin || p.timestamp > tMax) continue;
    const x = xAt(p.timestamp);
    const color = getPointColor(p);
    ctx.strokeStyle = color;
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(x, trackY - 5);
    ctx.lineTo(x, trackY + 5);
    ctx.stroke();
  }

  const drawCursor = (p, style) => {
    if (p.timestamp < tMin || p.timestamp > tMax) return;
    const x = xAt(p.timestamp), y = yAt(p.usedMB);
    const kindColor = getPointColor(p);
    const cursorColor = style.useKindColor ? kindColor : style.stroke;
    
    ctx.strokeStyle = cursorColor;
    ctx.lineWidth = 1;
    if (style.dashed) ctx.setLineDash([2, 3]);
    ctx.beginPath(); ctx.moveTo(x, padT); ctx.lineTo(x, h - padB); ctx.stroke();
    ctx.setLineDash([]);
    ctx.fillStyle = cursorColor;
    ctx.beginPath(); ctx.arc(x, y, style.r || 3, 0, Math.PI * 2); ctx.fill();
    if (style.ring) {
      ctx.strokeStyle = style.stroke;
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(x, y, (style.r || 3) + 5, 0, Math.PI * 2);
      ctx.stroke();
    }
  };

  if (state.hover && (!state.selected || state.hover.index !== state.selected.index)) {
    drawCursor(state.hover, { stroke: C.hoverCursor, dashed: true, r: 4, useKindColor: true });
  }
  if (state.selected) {
    drawCursor(state.selected, { stroke: C.selected, dashed: true, r: 5, ring: true, useKindColor: true });
  }

  // Draw range selection highlight
  if (state.rangeStart) {
    const rs = state.rangeStart;
    const re = state.rangeEnd;
    if (rs.timestamp >= tMin && rs.timestamp <= tMax) {
      drawCursor(rs, { stroke: C.range, dashed: true, r: 5, ring: true, useKindColor: false });
    }
    if (re) {
      if (re.timestamp >= tMin && re.timestamp <= tMax) {
        drawCursor(re, { stroke: C.range, dashed: true, r: 5, ring: true, useKindColor: false });
      }
      // Shade the range area
      const x1 = Math.max(padL, xAt(rs.timestamp));
      const x2 = Math.min(w - padR, xAt(re.timestamp));
      if (x2 > x1) {
        ctx.fillStyle = C.rangeFill;
        ctx.fillRect(x1, padT, x2 - x1, plotH);
        ctx.strokeStyle = C.rangeEdge;
        ctx.lineWidth = 1;
        ctx.strokeRect(x1, padT, x2 - x1, plotH);
      }
    }
  }

  // Horizontal crosshair tracking mouse Y
  if (mouseY != null && mouseY >= padT && mouseY <= h - padB) {
    const crossMB = yMax * (1 - (mouseY - padT) / plotH);
    if (crossMB >= 0) {
      ctx.save();
      ctx.strokeStyle = C.crosshair;
      ctx.lineWidth = 1;
      ctx.setLineDash([4, 3]);
      ctx.beginPath(); ctx.moveTo(padL, mouseY); ctx.lineTo(w - padR, mouseY); ctx.stroke();
      ctx.setLineDash([]);
      // Label on the left near the Y-axis
      const lbl = Math.round(crossMB) + ' MB';
      ctx.font = '11px Consolas, Menlo, monospace';
      const tw = ctx.measureText(lbl).width;
      ctx.fillStyle = C.labelBg;
      ctx.fillRect(2, mouseY - 8, tw + 6, 16);
      ctx.fillStyle = C.crosshairText;
      ctx.fillText(lbl, 4, mouseY + 4);
      ctx.restore();
    }
  }

  ctx.fillStyle = C.time;
  ctx.fillText(fmtTs(tMin), padL, h - 6);
  ctx.fillText(fmtTs(tMax), w - padR - 60, h - 6);
}

// ---- annotation layers ----

const BADGE_FONT = 'bold 11px -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif';
const TITLE_FONT = '11px -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif';

function drawBands(bands, xAt, padL, padT, plotW, plotH, tMin, tMax, C) {
  ctx.save();
  for (const b of bands) {
    if (b.endTs < tMin || b.startTs > tMax) continue;
    let x1 = Math.max(padL, xAt(b.startTs));
    let x2 = Math.min(padL + plotW, xAt(b.endTs));
    if (x2 < x1) continue;
    if (x2 - x1 < 1) { const c = (x1 + x2) / 2; x1 = c - 0.5; x2 = c + 0.5; }
    ctx.fillStyle = C.bandFill;
    ctx.fillRect(x1, padT, x2 - x1, plotH);
    ctx.strokeStyle = C.bandEdge;
    ctx.lineWidth = 1;
    ctx.beginPath();
    if (b.startTs >= tMin) { ctx.moveTo(x1 + 0.5, padT); ctx.lineTo(x1 + 0.5, padT + plotH); }
    if (b.endTs <= tMax && !b.open) { ctx.moveTo(x2 - 0.5, padT); ctx.lineTo(x2 - 0.5, padT + plotH); }
    ctx.stroke();
  }
  ctx.restore();
}

function drawBandTitles(bands, xAt, padL, padT, plotW, tMin, tMax, C) {
  const visible = bands.filter(b => !(b.endTs < tMin || b.startTs > tMax));
  if (!visible.length) return;
  ctx.save();
  ctx.font = TITLE_FONT;
  const measure = (t) => ctx.measureText(t).width;
  // keep titles clear of the zoom control bar that floats over the top-right corner
  let right = padL + plotW;
  const bar = chartWrap && chartWrap.querySelector('.ctrl-top-bar');
  if (bar) {
    const br = bar.getBoundingClientRect(), cr = canvas.getBoundingClientRect();
    if (br.width && br.bottom > cr.top && br.top - cr.top < padT) right = Math.min(right, br.left - cr.left - 8);
  }
  const placed = layoutBandTitles(
    visible.map(b => ({ x1: xAt(b.startTs), x2: xAt(b.endTs), text: b.label })),
    padL, right, measure);
  const top = padT - (2 * TITLE_ROW_H + 8) + 2;
  ctx.fillStyle = C.bandText;
  ctx.textBaseline = 'top';
  for (const t of placed) {
    if (t.hidden) continue;
    ctx.fillText(t.text, t.x + TITLE_PAD_X, top + t.row * TITLE_ROW_H + 1);
  }
  ctx.restore();
}

function drawReferences(yAt, yMax, padL, padT, plotW, plotH, C) {
  const lines = [];
  for (const r of state.references) {
    const label = r.label && String(r.label).trim() ? String(r.label) : fmtRefMB(r.usedMB);
    lines.push({ mb: r.usedMB, label, color: C.refLine });
  }
  const jvmMax = jvmMaxMB();
  const taken = [];
  ctx.save();
  ctx.font = TITLE_FONT;
  if (jvmMax > 0) {
    if (jvmMax <= yMax) {
      lines.push({ mb: jvmMax, label: 'JVM max heap · ' + fmtRefMB(jvmMax), color: C.refMax });
    } else {
      const note = 'JVM max heap ' + fmtRefMB(jvmMax) + ' (off scale)';
      const w = ctx.measureText(note).width + 8;
      const r = { x: padL + plotW - w - 4, y: padT + 3, w, h: 16 };
      taken.push(r);
      ctx.fillStyle = C.labelBg;
      ctx.fillRect(r.x, r.y, r.w, r.h);
      ctx.fillStyle = C.refMax;
      ctx.textBaseline = 'middle';
      ctx.fillText(note, r.x + 4, r.y + r.h / 2);
    }
  }
  const free = (r) => taken.every(t => !(r.x < t.x + t.w + 2 && t.x < r.x + r.w + 2 && r.y < t.y + t.h + 2 && t.y < r.y + r.h + 2));
  for (const l of lines) {
    if (l.mb > yMax) continue;
    const y = yAt(l.mb);
    ctx.strokeStyle = l.color;
    ctx.lineWidth = 1.2;
    ctx.setLineDash([6, 4]);
    ctx.beginPath(); ctx.moveTo(padL, y); ctx.lineTo(padL + plotW, y); ctx.stroke();
    ctx.setLineDash([]);
    const w = ctx.measureText(l.label).width + 8;
    const x = padL + plotW - w - 4;
    const above = { x, y: y - 17, w, h: 15 }, below = { x, y: y + 2, w, h: 15 };
    let r = null;
    if (above.y >= padT && free(above)) r = above;
    else if (below.y + below.h <= padT + plotH && free(below)) r = below;
    if (!r) continue;
    taken.push(r);
    ctx.fillStyle = C.labelBg;
    ctx.fillRect(r.x, r.y, r.w, r.h);
    ctx.fillStyle = l.color;
    ctx.textBaseline = 'middle';
    ctx.fillText(l.label, r.x + 4, r.y + r.h / 2);
  }
  ctx.restore();
  return taken;
}

function drawBadges(rows, xAt, yAt, plot, tMin, tMax, obstacles, C) {
  const vis = rows.filter(r => r.point.timestamp >= tMin && r.point.timestamp <= tMax);
  if (!vis.length) return;
  ctx.save();
  ctx.font = BADGE_FONT;
  const items = vis.map(r => ({
    ax: xAt(r.point.timestamp), ay: yAt(r.point.usedMB),
    w: ctx.measureText(r.badge).width + 2 * BADGE_PAD_X,
  }));
  const placed = layoutBadges(items, plot, obstacles);
  // dots
  for (let i = 0; i < vis.length; i++) {
    const it = items[i];
    ctx.beginPath();
    ctx.arc(it.ax, it.ay, DOT_R, 0, Math.PI * 2);
    ctx.fillStyle = getPointColor(vis[i].point);
    ctx.fill();
    ctx.strokeStyle = C.bg;
    ctx.lineWidth = 1;
    ctx.stroke();
  }
  // leaders, then badges
  for (let i = 0; i < vis.length; i++) {
    const b = placed[i];
    if (b.hidden || !b.leader) continue;
    const it = items[i];
    const cx = b.rect.x + b.rect.w / 2;
    ctx.strokeStyle = getPointColor(vis[i].point);
    ctx.globalAlpha = 0.75;
    ctx.lineWidth = 1;
    ctx.beginPath();
    if (b.below) { ctx.moveTo(cx, b.rect.y); ctx.lineTo(it.ax, it.ay + DOT_R); }
    else { ctx.moveTo(cx, b.rect.y + b.rect.h); ctx.lineTo(it.ax, it.ay - DOT_R); }
    ctx.stroke();
    ctx.globalAlpha = 1;
  }
  ctx.textBaseline = 'middle';
  ctx.textAlign = 'center';
  for (let i = 0; i < vis.length; i++) {
    const b = placed[i];
    if (b.hidden) continue;
    const r = b.rect, fill = getPointColor(vis[i].point);
    const rad = BADGE_H / 2;
    ctx.beginPath();
    ctx.moveTo(r.x + rad, r.y);
    ctx.arcTo(r.x + r.w, r.y, r.x + r.w, r.y + r.h, rad);
    ctx.arcTo(r.x + r.w, r.y + r.h, r.x, r.y + r.h, rad);
    ctx.arcTo(r.x, r.y + r.h, r.x, r.y, rad);
    ctx.arcTo(r.x, r.y, r.x + r.w, r.y, rad);
    ctx.closePath();
    ctx.fillStyle = fill;
    ctx.fill();
    ctx.strokeStyle = C.bg;
    ctx.lineWidth = 1;
    ctx.stroke();
    const sel = state.selected && state.selected.index === vis[i].point.index;
    if (sel) {
      ctx.strokeStyle = C.selected;
      ctx.lineWidth = 1.5;
      ctx.stroke();
    }
    ctx.fillStyle = badgeTextColor(fill);
    ctx.fillText(vis[i].badge, r.x + r.w / 2, r.y + r.h / 2 + 0.5);
    badgeHits.push({ rect: r, point: vis[i].point });
  }
  ctx.restore();
}

function badgeAt(clientX, clientY) {
  if (!state.annotations || !badgeHits.length) return null;
  const r = canvas.getBoundingClientRect();
  const x = clientX - r.left, y = clientY - r.top;
  for (let i = badgeHits.length - 1; i >= 0; i--) {
    const b = badgeHits[i].rect;
    if (x >= b.x - 1 && x <= b.x + b.w + 1 && y >= b.y - 1 && y <= b.y + b.h + 1) return badgeHits[i].point;
  }
  return null;
}

function pointAt(clientX, clientY) {
  if (!state.points.length) return null;
  const range = getTimeRange();
  if (!range) return null;
  if (clientY != null) {
    const onBadge = badgeAt(clientX, clientY);
    if (onBadge) return onBadge;
  }
  const r = canvas.getBoundingClientRect();
  const x = clientX - r.left;
  const w = canvas.clientWidth;
  const plotW = w - VIEW.PAD_L - VIEW.PAD_R;
  if (x < VIEW.PAD_L || x > w - VIEW.PAD_R) return null;
  const ts = range.tMin + (x - VIEW.PAD_L) / plotW * (range.tMax - range.tMin);
  if (state.annotations) {
    // marks are easier to hit than the samples around them: snap within 6 px
    const snapMs = 6 / plotW * (range.tMax - range.tMin);
    let mark = null, markDist = Infinity;
    for (const row of annotationModel().rows) {
      const d = Math.abs(row.point.timestamp - ts);
      if (d <= snapMs && d < markDist) { markDist = d; mark = row.point; }
    }
    if (mark) return mark;
  }
  let best = null, bestDist = Infinity;
  for (const p of state.points) {
    const d = Math.abs(p.timestamp - ts);
    if (d < bestDist) { bestDist = d; best = p; }
  }
  return best;
}

function tsAtClientX(clientX) {
  const range = getTimeRange();
  if (!range) return null;
  const r = canvas.getBoundingClientRect();
  const w = canvas.clientWidth;
  const plotW = w - VIEW.PAD_L - VIEW.PAD_R;
  const px = Math.max(0, Math.min(1, (clientX - r.left - VIEW.PAD_L) / plotW));
  return range.tMin + px * (range.tMax - range.tMin);
}

export function mbAtClientY(clientY) {
  const r = canvas.getBoundingClientRect();
  const h = canvas.clientHeight;
  const plotH = h - VIEW.PAD_T - VIEW.PAD_B;
  const y = clientY - r.top;
  if (y < VIEW.PAD_T - 2 || y > h - VIEW.PAD_B + 2) return null;
  const yMax = getYMax();
  const mb = Math.round(yMax * (1 - (y - VIEW.PAD_T) / plotH));
  return mb > 0 ? mb : null;
}

function mbAtClientYClamped(clientY) {
  const r = canvas.getBoundingClientRect();
  const h = canvas.clientHeight;
  const plotH = h - VIEW.PAD_T - VIEW.PAD_B;
  let y = clientY - r.top;
  y = Math.max(VIEW.PAD_T, Math.min(h - VIEW.PAD_B, y));
  const yMax = getYMax();
  return Math.max(1, Math.round(yMax * (1 - (y - VIEW.PAD_T) / plotH)));
}

function thresholdY() {
  if (!state.gcThresholdEnabled || state.gcThresholdMB <= 0) return null;
  const h = canvas.clientHeight;
  const plotH = h - VIEW.PAD_T - VIEW.PAD_B;
  const yMax = getYMax();
  if (state.gcThresholdMB > yMax) return null;
  return VIEW.PAD_T + (1 - state.gcThresholdMB / yMax) * plotH;
}

function isNearThresholdLine(clientX, clientY) {
  if (state.snapshot) return false;
  const ty = thresholdY();
  if (ty == null) return false;
  const r = canvas.getBoundingClientRect();
  const w = canvas.clientWidth;
  const x = clientX - r.left;
  const y = clientY - r.top;
  return x >= VIEW.PAD_L && x <= w - VIEW.PAD_R && Math.abs(y - ty) <= 12;
}

function zoomBy(factor, anchorTs) {
  const range = getTimeRange();
  if (!range) return;
  const span = range.tMax - range.tMin;
  const newSpan = Math.max(VIEW.MIN_SPAN_MS, Math.min(VIEW.MAX_SPAN_MS, span * factor));
  if (newSpan === span) return;
  if (anchorTs == null) anchorTs = range.tMax;
  if (state.autoscroll) {
    state.viewWindowMs = newSpan;
  } else {
    const frac = (anchorTs - range.tMin) / span;
    state.customTMin = anchorTs - frac * newSpan;
    state.customTMax = anchorTs + (1 - frac) * newSpan;
  }
}

export function zoomIn() { zoomBy(0.5, null); }
export function zoomOut() { zoomBy(2.0, null); }

function zoomYBy(factor) {
  const currentYMax = getYMax();
  const minYMax = 32; // Minimum 32 MB
  // Get the max heap from points to determine maximum zoom out
  let maxHeapMB = state.maxObservedMB;
  for (const p of state.points) {
    if (p.maxMB > maxHeapMB) maxHeapMB = p.maxMB;
  }
  const maxYMax = Math.max(maxHeapMB * 1.2, currentYMax * 2); // Allow zooming out to see full heap
  
  const newYMax = Math.max(minYMax, Math.min(maxYMax, currentYMax * factor));
  if (newYMax !== currentYMax) {
    state.yScaleAuto = false;
    state.yScaleMax = Math.round(newYMax);
  }
}

export function resetYScale() {
  state.yScaleAuto = true;
  state.yScaleMax = null;
}

let onContextMenu = null;
export function setContextMenuHandler(fn) { onContextMenu = fn; }

function installEvents() {
  canvas.addEventListener('pointerdown', (e) => {
    if (e.button !== 0) return;
    if (isNearThresholdLine(e.clientX, e.clientY)) {
      canvas.setPointerCapture(e.pointerId);
      dragState = { mode: 'threshold' };
      canvas.style.cursor = 'ns-resize';
      state.hover = null;
      state.menu.open = false;
      return;
    }
    const range = getTimeRange();
    if (!range) return;
    canvas.setPointerCapture(e.pointerId);
    dragState = {
      mode: 'pan',
      startClientX: e.clientX,
      startTMin: range.tMin,
      startTMax: range.tMax,
      isDrag: false,
    };
  });

  canvas.addEventListener('pointermove', (e) => {
    const rect = canvas.getBoundingClientRect();
    mouseY = e.clientY - rect.top;
    if (dragState && dragState.mode === 'threshold') {
      state.gcThresholdMB = mbAtClientYClamped(e.clientY);
      return;
    }
    if (dragState && dragState.mode === 'pan') {
      const dx = e.clientX - dragState.startClientX;
      if (!dragState.isDrag && Math.abs(dx) > 3) {
        dragState.isDrag = true;
        state.autoscroll = false;
        canvas.classList.add('panning');
        state.hover = null;
      }
      if (dragState.isDrag) {
        const plotW = canvas.clientWidth - VIEW.PAD_L - VIEW.PAD_R;
        const span = dragState.startTMax - dragState.startTMin;
        const dt = -dx / plotW * span;
        state.customTMin = dragState.startTMin + dt;
        state.customTMax = dragState.startTMax + dt;
        return;
      }
    }
    canvas.style.cursor = isNearThresholdLine(e.clientX, e.clientY) ? 'ns-resize'
      : state.rangeSelectMode ? 'crosshair' : '';
    const p = pointAt(e.clientX, e.clientY);
    if (p !== state.hover) {
      state.hover = p;
    } else {
      draw(); // redraw for horizontal crosshair even if hover point unchanged
    }
    // Update hover position for hover card
    if (p) {
      const rect = chartWrap.getBoundingClientRect();
      state.hoverPos = { x: e.clientX - rect.left, y: e.clientY - rect.top };
    }
  });

  canvas.addEventListener('pointerup', (e) => {
    if (!dragState) return;
    const mode = dragState.mode;
    const wasDrag = dragState.isDrag;
    canvas.classList.remove('panning');
    canvas.style.cursor = '';
    try { canvas.releasePointerCapture(e.pointerId); } catch (_) {}
    dragState = null;
    if (mode === 'threshold') {
      setAutoGc({
        nearPeak: state.gcNearPeakEnabled,
        threshold: state.gcThresholdEnabled,
        thresholdMB: state.gcThresholdMB,
      });
      return;
    }
    if (!wasDrag) {
      if ((e.ctrlKey || e.metaKey) && !state.snapshot) {
        const mb = mbAtClientYClamped(e.clientY);
        if (mb != null) {
          state.gcThresholdMB = mb;
          state.gcThresholdEnabled = true;
          setAutoGc({
            nearPeak: state.gcNearPeakEnabled,
            threshold: true,
            thresholdMB: mb,
          });
        }
        return;
      }
      const p = pointAt(e.clientX, e.clientY);
      if (p) {
        if (e.shiftKey || state.rangeSelectMode) {
          selectRangePoint(p);
        } else {
          selectPoint(p);
        }
      }
    }
  });

  canvas.addEventListener('pointercancel', () => {
    canvas.classList.remove('panning');
    canvas.style.cursor = '';
    dragState = null;
  });

  canvas.addEventListener('pointerleave', () => {
    mouseY = null;
    if (!dragState) {
      canvas.style.cursor = '';
      state.hover = null;
    } else {
      draw();
    }
  });

  canvas.addEventListener('wheel', (e) => {
    if (!state.points.length) return;
    e.preventDefault();
    state.menu.open = false;
    
    const factor = Math.exp(e.deltaY * 0.0015);
    
    // Shift+wheel for vertical zoom
    if (e.shiftKey) {
      zoomYBy(factor);
      return;
    }
    
    // Regular wheel for horizontal zoom
    const anchor = tsAtClientX(e.clientX);
    if (!state.autoscroll && anchor != null) freezeView();
    zoomBy(factor, anchor);
  }, { passive: false });

  canvas.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    if (!state.points.length) { state.menu.open = false; return; }
    if (onContextMenu) onContextMenu(e.clientX, e.clientY, mbAtClientY(e.clientY));
  });
}
