import { reactive } from 'vue';

export const state = reactive({
  points: [],
  maxObservedMB: 0,
  jsonlPath: null,
  pid: null,
  processName: null,
  lastIndex: -1,
  paused: false,
  selected: null,
  rangeStart: null,   // first point of range selection
  rangeEnd: null,     // second point of range selection
  rangeSelectMode: false, // when true, clicks act as range selection
  hover: null,
  hoverPos: { x: 0, y: 0 },
  viewWindowMs: 60 * 1000,
  customTMin: null,
  customTMax: null,
  autoscroll: true,
  yScaleAuto: true,
  yScaleMax: null,  // custom max MB for y-axis
  gcNearPeakEnabled: false,
  gcThresholdEnabled: false,
  gcThresholdMB: 0,
  status: { text: 'connecting...', cls: '' },
  menu: { open: false, x: 0, y: 0, items: [] },
  heapDumps: [],
  heapDumpOpener: 'system',
  heapDumpBusy: false,
});

export const VIEW = {
  MIN_SPAN_MS: 1000,
  MAX_SPAN_MS: 24 * 3600 * 1000,
  PAD_L: 50,
  PAD_R: 10,
  PAD_T: 12,
  PAD_B: 26,
};

export function getTimeRange() {
  const pts = state.points;
  if (!pts.length) return null;
  const now = pts[pts.length - 1].timestamp;
  if (state.autoscroll) return { tMin: now - state.viewWindowMs, tMax: now };
  if (state.customTMin != null && state.customTMax != null) {
    return { tMin: state.customTMin, tMax: state.customTMax };
  }
  return { tMin: pts[0].timestamp, tMax: now };
}

export function freezeView() {
  const range = getTimeRange();
  if (!range) return null;
  state.customTMin = range.tMin;
  state.customTMax = range.tMax;
  state.autoscroll = false;
  return range;
}

export function resetView() {
  state.customTMin = null;
  state.customTMax = null;
  state.viewWindowMs = 60 * 1000;
  state.autoscroll = true;
}

export function ensureVisible(p, opts) {
  if (!p) return;
  const range = getTimeRange();
  if (!range) return;
  const margin = (range.tMax - range.tMin) * 0.05;
  const inside = p.timestamp >= range.tMin + margin && p.timestamp <= range.tMax - margin;
  if (inside && !(opts && opts.force)) return;
  const span = range.tMax - range.tMin;
  state.autoscroll = false;
  state.customTMin = p.timestamp - span / 2;
  state.customTMax = p.timestamp + span / 2;
}

export function selectPoint(p) {
  state.selected = p;
  state.rangeStart = null;
  state.rangeEnd = null;
}

export function selectRangePoint(p) {
  if (!p) return;
  if (!state.rangeStart || state.rangeEnd) {
    // Start new range
    state.rangeStart = p;
    state.rangeEnd = null;
    state.selected = null;
  } else {
    // Complete range - ensure start < end by timestamp
    if (p.timestamp < state.rangeStart.timestamp) {
      state.rangeEnd = state.rangeStart;
      state.rangeStart = p;
    } else {
      state.rangeEnd = p;
    }
    state.selected = null;
    state.rangeSelectMode = false;
  }
}

export function clearSelection() {
  state.selected = null;
  state.rangeStart = null;
  state.rangeEnd = null;
}
