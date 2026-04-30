import { watchEffect } from 'vue';
import { state, VIEW, getTimeRange, freezeView, ensureVisible, selectPoint, selectRangePoint } from './state.js';
import { setAutoGc } from './api.js';
import { fmtTs } from './util.js';

let canvas, ctx, chartWrap;
let dragState = null;
let mouseY = null; // track mouse Y for horizontal crosshair

// Color mapping for event kinds
const KIND_COLORS = {
  MARK: '#ffb020',
  SCOPE_START: '#37c26a',
  SCOPE_END: '#ff6e6e',
  GC: '#4ea1ff',
  HEAP_DUMP: '#e040fb',
  AUTO_GC: '#a3e635',
  SAMPLE: '#4ea1ff',
};

function getKindColor(kind) {
  return KIND_COLORS[kind] || '#4ea1ff';
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
  return yMax;
}

function draw() {
  if (!canvas) return;
  const w = canvas.clientWidth, h = canvas.clientHeight;
  ctx.clearRect(0, 0, w, h);
  if (!state.points.length) return;

  const padL = VIEW.PAD_L, padR = VIEW.PAD_R, padT = VIEW.PAD_T, padB = VIEW.PAD_B;
  const plotW = w - padL - padR, plotH = h - padT - padB;

  const range = getTimeRange();
  const tMin = range.tMin, tMax = range.tMax;
  const yMax = getYMax();
  const yMin = 0;

  ctx.strokeStyle = '#1c222d';
  ctx.fillStyle = '#6a7384';
  ctx.font = '11px Consolas, Menlo, monospace';
  ctx.lineWidth = 1;
  for (let i = 0; i <= 4; i++) {
    const y = padT + (plotH * i / 4);
    ctx.beginPath(); ctx.moveTo(padL, y); ctx.lineTo(w - padR, y); ctx.stroke();
    const v = Math.round(yMax - (yMax - yMin) * i / 4);
    ctx.fillText(v + ' MB', 4, y + 4);
  }

  const xAt = (ts) => padL + ((ts - tMin) / (tMax - tMin)) * plotW;
  const yAt = (mb) => padT + (1 - (mb - yMin) / (yMax - yMin)) * plotH;

  if (state.gcThresholdEnabled && state.gcThresholdMB > 0 && state.gcThresholdMB <= yMax) {
    const ty = yAt(state.gcThresholdMB);
    ctx.save();
    ctx.strokeStyle = '#ff6e6e';
    ctx.lineWidth = 1;
    ctx.setLineDash([5, 4]);
    ctx.beginPath(); ctx.moveTo(padL, ty); ctx.lineTo(w - padR, ty); ctx.stroke();
    ctx.setLineDash([]);
    const lbl = 'auto-GC ' + state.gcThresholdMB + ' MB';
    ctx.font = '11px Consolas, Menlo, monospace';
    const tw = ctx.measureText(lbl).width;
    ctx.fillStyle = 'rgba(15,17,22,0.85)';
    ctx.fillRect(w - padR - tw - 8, ty - 14, tw + 6, 14);
    ctx.fillStyle = '#ff8a8a';
    ctx.fillText(lbl, w - padR - tw - 5, ty - 3);
    ctx.restore();
  }

  ctx.save();
  ctx.beginPath();
  ctx.rect(padL, padT, plotW, plotH);
  ctx.clip();
  ctx.strokeStyle = '#4ea1ff';
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
    if (p.timestamp < tMin - 1000 || p.timestamp > tMax + 1000) continue;
    const x = xAt(p.timestamp);
    const y = yAt(p.usedMB);
    const color = getPointColor(p);
    
    ctx.fillStyle = color;
    ctx.strokeStyle = 'rgba(255,255,255,0.3)';
    ctx.lineWidth = 1;
    drawEventShape(ctx, x, y, p.kind);
  }
  
  ctx.restore();

  const trackY = h - padB + 4;
  ctx.strokeStyle = '#2a3242';
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
    drawCursor(state.hover, { stroke: '#8a93a3', dashed: true, r: 4, useKindColor: true });
  }
  if (state.selected) {
    drawCursor(state.selected, { stroke: '#ffffff', dashed: true, r: 5, ring: true, useKindColor: true });
  }

  // Draw range selection highlight
  if (state.rangeStart) {
    const rs = state.rangeStart;
    const re = state.rangeEnd;
    if (rs.timestamp >= tMin && rs.timestamp <= tMax) {
      drawCursor(rs, { stroke: '#f0c040', dashed: true, r: 5, ring: true, useKindColor: false });
    }
    if (re) {
      if (re.timestamp >= tMin && re.timestamp <= tMax) {
        drawCursor(re, { stroke: '#f0c040', dashed: true, r: 5, ring: true, useKindColor: false });
      }
      // Shade the range area
      const x1 = Math.max(padL, xAt(rs.timestamp));
      const x2 = Math.min(w - padR, xAt(re.timestamp));
      if (x2 > x1) {
        ctx.fillStyle = 'rgba(240, 192, 64, 0.08)';
        ctx.fillRect(x1, padT, x2 - x1, plotH);
        ctx.strokeStyle = 'rgba(240, 192, 64, 0.3)';
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
      ctx.strokeStyle = 'rgba(138,147,163,0.45)';
      ctx.lineWidth = 1;
      ctx.setLineDash([4, 3]);
      ctx.beginPath(); ctx.moveTo(padL, mouseY); ctx.lineTo(w - padR, mouseY); ctx.stroke();
      ctx.setLineDash([]);
      // Label on the left near the Y-axis
      const lbl = Math.round(crossMB) + ' MB';
      ctx.font = '11px Consolas, Menlo, monospace';
      const tw = ctx.measureText(lbl).width;
      ctx.fillStyle = 'rgba(15,17,22,0.85)';
      ctx.fillRect(2, mouseY - 8, tw + 6, 16);
      ctx.fillStyle = '#c8cdd5';
      ctx.fillText(lbl, 4, mouseY + 4);
      ctx.restore();
    }
  }

  ctx.fillStyle = '#8a93a3';
  ctx.fillText(fmtTs(tMin), padL, h - 6);
  ctx.fillText(fmtTs(tMax), w - padR - 60, h - 6);
}

function pointAt(clientX) {
  if (!state.points.length) return null;
  const range = getTimeRange();
  if (!range) return null;
  const r = canvas.getBoundingClientRect();
  const x = clientX - r.left;
  const w = canvas.clientWidth;
  const plotW = w - VIEW.PAD_L - VIEW.PAD_R;
  if (x < VIEW.PAD_L || x > w - VIEW.PAD_R) return null;
  const ts = range.tMin + (x - VIEW.PAD_L) / plotW * (range.tMax - range.tMin);
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
    const p = pointAt(e.clientX);
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
      if (e.ctrlKey || e.metaKey) {
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
      const p = pointAt(e.clientX);
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
