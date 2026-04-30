import { createApp, computed } from 'vue';
import { state, selectPoint, clearSelection } from './state.js';
import * as api from './api.js';
import { mountChart, setContextMenuHandler, ensureVisible } from './chart.js';
import Summary from './components/Summary.js';
import Selected from './components/Selected.js';
import Events from './components/Events.js';
import Overlay from './components/Overlay.js';
import ContextMenu from './components/ContextMenu.js';
import HoverCard from './components/HoverCard.js';
import HoverHeader from './components/HoverHeader.js';

const app = createApp({
  components: {
    'app-summary': Summary,
    'app-selected': Selected,
    'app-events': Events,
    'app-overlay': Overlay,
    'app-context-menu': ContextMenu,
    'app-hover-card': HoverCard,
    'app-hover-header': HoverHeader,
  },
  setup() {
    const pidLabel = computed(() => state.pid != null && state.pid >= 0 ? String(state.pid) : '-');
    const procTitle = computed(() => 'PID ' + (state.pid != null ? state.pid : '-')
      + (state.processName ? ' · ' + state.processName : ''));
    const onGc = () => api.forceGc(); // kept for potential reuse
    const onDownload = () => api.downloadJsonl();
    const onOpenJsonl = () => api.openJsonlFile(state.jsonlPath);
    return { state, pidLabel, procTitle, onDownload, onOpenJsonl };
  }
});

const vm = app.mount('#app');

const canvas = document.getElementById('chart');
const chartWrap = document.getElementById('chartWrap');
mountChart(canvas, chartWrap);

setContextMenuHandler((clientX, clientY, mbAtClick) => {
  const items = [
    { label: 'Add labeled mark…', action: addLabeledMark },
    { sep: true },
  ];
  if (mbAtClick != null) {
    items.push({
      label: state.gcThresholdEnabled ? 'Move auto-GC line here' : 'Set auto-GC line here',
      hint: mbAtClick + ' MB',
      action: () => setThresholdMB(mbAtClick),
    });
  } else {
    items.push({ label: 'Set auto-GC line here', hint: '—', disabled: true });
  }
  if (state.gcThresholdEnabled) {
    items.push({
      label: 'Remove auto-GC line',
      hint: state.gcThresholdMB + ' MB',
      action: removeThreshold,
    });
  }
  items.push({ sep: true });
  items.push({
    label: 'Auto-GC near peak',
    hint: state.gcNearPeakEnabled ? 'On' : 'Off',
    action: toggleNearPeak,
  });
  state.menu = { open: true, x: clientX, y: clientY, items };
});

async function addLabeledMark() {
  const label = window.prompt('Label for mark:', '');
  if (label == null) return;
  await api.mark(label.trim() || 'manual', true);
}

async function setThresholdMB(mb) {
  state.gcThresholdMB = mb;
  state.gcThresholdEnabled = true;
  await api.setAutoGc({
    nearPeak: state.gcNearPeakEnabled,
    threshold: true,
    thresholdMB: mb,
  });
}

async function removeThreshold() {
  state.gcThresholdEnabled = false;
  await api.setAutoGc({
    nearPeak: state.gcNearPeakEnabled,
    threshold: false,
    thresholdMB: state.gcThresholdMB,
  });
}

async function toggleNearPeak() {
  state.gcNearPeakEnabled = !state.gcNearPeakEnabled;
  await api.setAutoGc({
    nearPeak: state.gcNearPeakEnabled,
    threshold: state.gcThresholdEnabled,
    thresholdMB: state.gcThresholdMB,
  });
}

function isTypingTarget(t) {
  if (!t) return false;
  const tag = t.tagName;
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
  return !!t.isContentEditable;
}

function stepSelection(delta) {
  const pts = state.points;
  if (!pts.length) return;
  let idx;
  if (state.selected) {
    idx = pts.findIndex(p => p.index === state.selected.index);
    if (idx < 0) idx = pts.length - 1;
    idx = Math.max(0, Math.min(pts.length - 1, idx + delta));
  } else {
    idx = delta > 0 ? 0 : pts.length - 1;
  }
  const next = pts[idx];
  if (!next) return;
  selectPoint(next);
  ensureVisible(next);
}

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    if (state.menu.open) return;
    if (state.selected || state.rangeStart) clearSelection();
    return;
  }
  if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
    if (state.selected) return;          // handled by Selected component
    if (isTypingTarget(e.target)) return;
    if (state.menu.open) return;
    if (!state.points.length) return;
    e.preventDefault();
    stepSelection(e.key === 'ArrowRight' ? 1 : -1);
  }
});

async function loadInitial() {
  try {
    const j = await api.fetchInitial();
    state.points = j.points || [];
    state.maxObservedMB = j.maxObservedMB || 0;
    state.jsonlPath = j.jsonlPath;
    state.pid = (typeof j.pid === 'number') ? j.pid : null;
    state.processName = j.processName || null;
    if (state.points.length) state.lastIndex = state.points[state.points.length - 1].index;
  } catch (e) {
    state.status = { text: 'load failed', cls: 'disconnected' };
  }
}

async function loadAutoGc() {
  try {
    const j = await api.getAutoGc();
    state.gcNearPeakEnabled = !!j.nearPeak;
    state.gcThresholdEnabled = !!j.threshold;
    state.gcThresholdMB = j.thresholdMB || 0;
  } catch (e) {}
}

async function poll() {
  if (state.paused) return;
  try {
    const j = await api.fetchSince(state.lastIndex);
    if (j.points && j.points.length) {
      state.points = state.points.concat(j.points);
      for (const p of j.points) if (p.index > state.lastIndex) state.lastIndex = p.index;
    }
    state.maxObservedMB = j.maxObservedMB || state.maxObservedMB;
    state.status = { text: 'live · ' + state.points.length + ' pts', cls: 'live' };
  } catch (e) {
    state.status = { text: 'disconnected', cls: 'disconnected' };
  }
  try {
    const hd = await api.getHeapDumps();
    if ((hd.dumps || []).length !== state.heapDumps.length) {
      state.heapDumps = hd.dumps;
    }
  } catch (e) {}
}

loadAutoGc();
loadInitial();
loadHeapDumps();
setInterval(poll, 500);

async function loadHeapDumps() {
  try {
    const j = await api.getHeapDumps();
    state.heapDumps = j.dumps || [];
    state.heapDumpOpener = j.opener || 'system';
  } catch (e) {}
}
