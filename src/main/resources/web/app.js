import { createApp, computed, watch } from 'vue';
import { state, selectPoint, clearSelection, fitAll, getTimeRange } from './state.js';
import * as api from './api.js';
import { mountChart, setContextMenuHandler, ensureVisible } from './chart.js';
import MarksTable from './components/MarksTable.js';
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
    'app-marks-table': MarksTable,
  },
  setup() {
    const pidLabel = computed(() => state.pid != null && state.pid >= 0 ? String(state.pid) : '-');
    const procTitle = computed(() => 'PID ' + (state.pid != null ? state.pid : '-')
      + (state.processName ? ' · ' + state.processName : ''));
    const onGc = () => api.forceGc(); // kept for potential reuse
    const onDownload = () => api.downloadJsonl();
    const onOpenJsonl = () => api.openJsonlFile(state.jsonlPath);

    // ---- snapshot header ----
    const metaTitle = computed(() => (state.meta && state.meta.title) || 'Java Memory Profiler');
    const metaChips = computed(() => snapshotChips(state.meta));

    // ---- theme ----
    const toggleTheme = () => setTheme(state.theme === 'dark' ? 'light' : 'dark', true);

    // ---- exports (live) ----
    const onExportHtml = () => {
      api.downloadHtmlExport();
      toast('Preparing HTML export…');
    };
    const togglePngMenu = () => { state.exportMenuOpen = !state.exportMenuOpen; };
    const pngOptions = (scope) => {
      const o = { theme: state.theme === 'dark' ? 'dark' : 'light', includeTable: state.pngIncludeTable };
      if (scope === 'view') {
        const r = getTimeRange();
        if (r) { o.from = Math.round(r.tMin); o.to = Math.round(r.tMax); }
      }
      return o;
    };
    const onPng = (scope) => {
      state.exportMenuOpen = false;
      api.downloadPngExport(pngOptions(scope));
      toast('Rendering PNG…');
    };
    const onCopyPng = async (scope) => {
      state.exportMenuOpen = false;
      toast('Rendering PNG…', 0);
      try {
        await api.copyPngExport(pngOptions(scope));
        toast('PNG copied to the clipboard');
      } catch (e) {
        toast('Copy failed: ' + (e && e.message ? e.message : e), 5000, true);
      }
    };
    const toggleAnnotations = () => { state.annotations = !state.annotations; };

    return {
      state, pidLabel, procTitle, onDownload, onOpenJsonl,
      metaTitle, metaChips, toggleTheme,
      onExportHtml, togglePngMenu, onPng, onCopyPng, toggleAnnotations,
    };
  }
});

function fmtDateTime(ts) {
  if (!ts) return '-';
  const d = new Date(ts);
  const pad = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
    + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
}

function fmtDuration(ms) {
  if (ms == null) return '-';
  const s = Math.round(ms / 1000);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  if (h) return h + 'h ' + String(m).padStart(2, '0') + 'm';
  if (m) return m + 'm ' + String(sec).padStart(2, '0') + 's';
  return (ms / 1000).toFixed(1) + ' s';
}

function fmtHeap(mb) {
  if (!(mb > 0)) return '-';
  return mb >= 1024 ? (mb / 1024).toFixed(1).replace(/\.0$/, '') + ' GB' : mb + ' MB';
}

function snapshotChips(m) {
  if (!m) return [];
  const chips = [];
  if (m.subtitle) chips.push({ k: '', v: m.subtitle, wide: true });
  if (m.source && m.source !== 'live') chips.push({ k: 'source', v: m.source });
  if (m.processName) chips.push({ k: 'process', v: m.processName });
  if (m.pid != null && m.pid >= 0) chips.push({ k: 'PID', v: String(m.pid) });
  chips.push({ k: 'JVM max heap', v: fmtHeap(m.jvmMaxHeapMB) });
  chips.push({ k: 'started', v: fmtDateTime(m.startTime) });
  chips.push({ k: 'duration', v: fmtDuration(m.durationMs) });
  if (m.sampleCount && m.exportedSampleCount && m.exportedSampleCount < m.sampleCount) {
    chips.push({ k: 'samples', v: m.exportedSampleCount + ' of ' + m.sampleCount + ' (min/max)' });
  }
  chips.push({ k: 'profiler', v: m.profilerVersion || '-' });
  chips.push({ k: 'exported', v: fmtDateTime(m.exportedAt) });
  return chips;
}

let toastTimer = null;
function toast(text, ms = 2500, error = false) {
  state.toast = { text, error };
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = ms ? setTimeout(() => { state.toast = null; }, ms) : null;
}

// ---- theme: dark by default live; a snapshot follows the OS unless the viewer picked one ----
function readStoredTheme() {
  try { return localStorage.getItem('memprofiler.theme'); } catch (e) { return null; }
}
function setTheme(t, persist) {
  document.documentElement.setAttribute('data-theme', t);
  state.theme = t;
  if (persist) { try { localStorage.setItem('memprofiler.theme', t); } catch (e) {} }
}
(function initTheme() {
  const attr = document.documentElement.getAttribute('data-theme');
  const stored = readStoredTheme();
  const t = stored === 'light' || stored === 'dark' ? stored : (attr || 'dark');
  setTheme(t, false);
  if (api.SNAPSHOT && !(stored === 'light' || stored === 'dark') && window.matchMedia) {
    const mq = window.matchMedia('(prefers-color-scheme: light)');
    const onChange = () => { if (!readStoredTheme()) setTheme(mq.matches ? 'light' : 'dark', false); };
    if (mq.addEventListener) mq.addEventListener('change', onChange);
  }
})();

state.snapshot = api.SNAPSHOT;
if (api.SNAPSHOT) document.documentElement.classList.add('is-snapshot');
(function initAnnotations() {
  let stored = null;
  try { stored = localStorage.getItem('memprofiler.annotations'); } catch (e) {}
  state.annotations = api.SNAPSHOT ? stored !== 'off' : stored === 'on';
})();
watch(() => state.annotations, (v) => {
  try { localStorage.setItem('memprofiler.annotations', v ? 'on' : 'off'); } catch (e) {}
});

const vm = app.mount('#app');

const canvas = document.getElementById('chart');
const chartWrap = document.getElementById('chartWrap');
mountChart(canvas, chartWrap);

setContextMenuHandler((clientX, clientY, mbAtClick) => {
  if (state.snapshot) {
    state.menu = { open: true, x: clientX, y: clientY, items: [
      { label: 'Reset zoom (whole run)', action: fitAll },
      { label: state.annotations ? 'Hide mark annotations' : 'Show mark annotations',
        action: () => { state.annotations = !state.annotations; } },
      { sep: true },
      { label: 'Add mark, auto-GC, heap dump', hint: 'snapshot', disabled: true },
    ] };
    return;
  }
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
    if (state.exportMenuOpen) { state.exportMenuOpen = false; return; }
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
    const j = await api.dataSource.load();
    state.points = j.points || [];
    state.maxObservedMB = j.maxObservedMB || 0;
    state.jsonlPath = j.jsonlPath;
    state.pid = (typeof j.pid === 'number') ? j.pid : null;
    state.processName = j.processName || null;
    state.references = j.references || [];
    state.intervalMs = j.intervalMs || 0;
    state.version = j.version || null;
    if (j.meta) state.meta = j.meta;
    if (j.sources) state.sources = j.sources;
    if (state.points.length) state.lastIndex = state.points[state.points.length - 1].index;
    if (state.snapshot) {
      fitAll();
      state.status = { text: 'snapshot · ' + state.points.length + ' pts', cls: 'snapshot' };
    }
  } catch (e) {
    state.status = { text: 'load failed', cls: 'disconnected' };
    console.error('profile load failed:', e);
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
    const j = await api.dataSource.since(state.lastIndex);
    if (j.references) state.references = j.references;
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

loadInitial();
if (api.dataSource.live) {
  loadAutoGc();
  loadHeapDumps();
  setInterval(poll, 500);
}

// close the export menu on outside clicks
document.addEventListener('mousedown', (e) => {
  if (!state.exportMenuOpen) return;
  const wrap = document.querySelector('.hdr-menu-wrap');
  if (wrap && wrap.contains(e.target)) return;
  state.exportMenuOpen = false;
});

async function loadHeapDumps() {
  try {
    const j = await api.getHeapDumps();
    state.heapDumps = j.dumps || [];
    state.heapDumpOpener = j.opener || 'system';
  } catch (e) {}
}
