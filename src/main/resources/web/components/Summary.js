import { computed, ref, onMounted, onUnmounted } from 'vue';
import { state, selectPoint, ensureVisible, clearSelection } from '../state.js';
import * as api from '../api.js';

const RADIUS = 40;
const C = 2 * Math.PI * RADIUS;

export default {
  template: `
    <div class="panel summary">
      <div class="summary-top">
        <div class="panel-title"><span>GC Controls</span></div>
        <div class="gc-controls">
          <button class="gc-trigger-btn" @click="forceGc" title="Force System.gc() now">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M3 12a9 9 0 019-9 9.75 9.75 0 016.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 01-9 9 9.75 9.75 0 01-6.74-2.74L3 16"/><path d="M3 21v-5h5"/>
            </svg>
            Trigger GC
          </button>

          <div class="gc-option">
            <label class="gc-toggle" :class="{ active: state.gcNearPeakEnabled }">
              <input type="checkbox" :checked="state.gcNearPeakEnabled" @change="toggleNearPeak">
              <span class="gc-toggle-track"><span class="gc-toggle-thumb"></span></span>
              <span>Auto-GC near peak</span>
            </label>
          </div>

          <div class="gc-option">
            <label class="gc-toggle" :class="{ active: state.gcThresholdEnabled }">
              <input type="checkbox" :checked="state.gcThresholdEnabled" @change="toggleThreshold">
              <span class="gc-toggle-track"><span class="gc-toggle-thumb"></span></span>
              <span>Auto-GC at threshold</span>
            </label>
            <div v-if="state.gcThresholdEnabled" class="gc-threshold-input">
              <div class="gc-presets">
                <button v-for="p in thresholdPresets" :key="p"
                  class="gc-preset-btn" :class="{ active: state.gcThresholdMB === p }"
                  @click="thresholdInput = p">{{ p >= 1024 ? (p / 1024) + 'G' : p + 'M' }}</button>
              </div>
              <input
                type="number"
                class="gc-mb-input"
                v-model.number.lazy="thresholdInput"
                min="1"
                step="10"
                title="Threshold in MB"
              />
              <span class="gc-mb-unit">MB</span>
            </div>
          </div>
        </div>
      </div>

      <div class="summary-tools">
        <div class="panel-title"><span>Tools</span></div>
        <div class="tools-row">
          <button class="tool-btn" :class="{ active: state.rangeSelectMode }" @click="toggleRangeMode"
                  title="Click two points on the chart to analyze a range">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M17 3l4 4-4 4"/><path d="M3 13V11a4 4 0 014-4h14"/>
              <path d="M7 21l-4-4 4-4"/><path d="M21 11v2a4 4 0 01-4 4H3"/>
            </svg>
            Range Select
          </button>
          <button class="tool-btn" @click="openLayoutTab"
                  title="Analyze the memory layout of a Java class (opens new tab)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><line x1="3" y1="9" x2="21" y2="9"/><line x1="3" y1="15" x2="21" y2="15"/><line x1="9" y1="3" x2="9" y2="21"/>
            </svg>
            Object Layout
          </button>
        </div>
      </div>

      <div class="summary-dumps">
        <div class="panel-title"><span>Heap Dumps</span></div>
        <div class="dump-row">
          <button class="gc-trigger-btn" @click="takeHeapDump" :disabled="state.heapDumpBusy"
                  title="Record an hprof heap dump">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
              <polyline points="7 10 12 15 17 10"/>
              <line x1="12" y1="15" x2="12" y2="3"/>
            </svg>
            {{ state.heapDumpBusy ? 'Recording...' : 'Record Heap Dump' }}
          </button>
          <div class="dump-menu-wrap" v-if="state.heapDumps.length">
            <button class="dump-toggle-btn" ref="dumpToggleEl" @click="toggleDumps">
              <span class="dump-badge">{{ state.heapDumps.length }}</span>
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline :points="dumpsOpen ? '18 15 12 9 6 15' : '6 9 12 15 18 9'"/>
              </svg>
            </button>
            <div class="dump-dropdown" v-if="dumpsOpen" :style="dropdownStyle">
              <div class="dump-dropdown-header">
                <span>Recorded Dumps</span>
                <span class="dump-opener-tag">{{ state.heapDumpOpener }}</span>
              </div>
              <div class="dump-dropdown-list">
                <div class="dump-item" v-for="d in reversedDumps" :key="d.path"
                     @click="openDump(d.path)" :title="d.path">
                  <div class="dump-name">{{ d.name }}</div>
                  <div class="dump-meta">
                    <span class="dump-size">{{ formatSize(d.sizeBytes) }}</span>
                    <span class="dump-time">{{ formatDumpTime(d.timestamp) }}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="summary-bottom">
        <div class="panel-title">
          <span>Current Heap</span>
          <span v-if="!state.paused" class="live-indicator"></span>
        </div>
      <div v-if="last" class="usage-donut-wrap">
        <svg class="usage-donut" viewBox="0 0 100 100" :title="usageTitle">
          <circle class="ring max" cx="50" cy="50" r="40"></circle>
          <circle class="ring committed" cx="50" cy="50" r="40"
            :stroke-dasharray="dashArray(committedLen)"
            stroke-dashoffset="0"></circle>
          <circle class="ring used" :class="usageClass" cx="50" cy="50" r="40"
            :stroke-dasharray="dashArray(usedLen)"
            stroke-dashoffset="0"></circle>
          <text x="50" y="50" text-anchor="middle" class="pct">{{ pctText }}</text>
          <text x="50" y="64" text-anchor="middle" class="sub">of max</text>
        </svg>
        <div class="usage-donut-legend">
          <div class="lg-item"><span class="sw used" :class="usageClass"></span>used</div>
          <div class="lg-item"><span class="sw committed"></span>committed</div>
          <div class="lg-item"><span class="sw max"></span>max</div>
        </div>
      </div>
      <div v-else class="summary-empty">No data yet.</div>
      <div class="summary-grid">
        <div class="metric" v-for="cell in cells" :key="cell.k"
             :class="{ clickable: cell.click }"
             @click="cell.click && cell.click()">
          <div class="k">{{ cell.k }}</div>
          <div class="v">
            <template v-if="cell.v == null">-</template>
            <template v-else>{{ cell.v }}<span v-if="cell.u" class="unit">{{ cell.u }}</span></template>
          </div>
        </div>
      </div>
      </div>
    </div>
  `,
  setup() {
    const thresholdPresets = [256, 512, 1024, 2048, 4096];
    const dumpsOpen = ref(false);
    const dumpToggleEl = ref(null);
    const dropdownStyle = ref({});

    const toggleDumps = () => {
      dumpsOpen.value = !dumpsOpen.value;
      if (dumpsOpen.value && dumpToggleEl.value) {
        const rect = dumpToggleEl.value.getBoundingClientRect();
        dropdownStyle.value = {
          top: (rect.bottom + 4) + 'px',
          left: rect.left + 'px',
        };
      }
    };

    const onClickOutside = (e) => {
      if (!dumpsOpen.value) return;
      if (dumpToggleEl.value && dumpToggleEl.value.contains(e.target)) return;
      const dd = document.querySelector('.dump-dropdown');
      if (dd && dd.contains(e.target)) return;
      dumpsOpen.value = false;
    };

    onMounted(() => document.addEventListener('mousedown', onClickOutside));
    onUnmounted(() => document.removeEventListener('mousedown', onClickOutside));

    const thresholdInput = computed({
      get() { return state.gcThresholdMB || ''; },
      set(v) {
        const mb = Math.max(1, parseInt(v) || 0);
        state.gcThresholdMB = mb;
        // Ensure the chart Y scale shows the threshold line
        if (!state.yScaleAuto && state.yScaleMax != null && state.yScaleMax < mb * 1.15) {
          state.yScaleAuto = true;
          state.yScaleMax = null;
        }
        api.setAutoGc({
          nearPeak: state.gcNearPeakEnabled,
          threshold: state.gcThresholdEnabled,
          thresholdMB: mb,
        });
      }
    });
    const last = computed(() => state.points.length ? state.points[state.points.length - 1] : null);

    const forceGc = () => api.forceGc();

    const openLayoutTab = () => {
      window.open('/layout.html', '_blank');
    };

    const toggleRangeMode = () => {
      state.rangeSelectMode = !state.rangeSelectMode;
      if (!state.rangeSelectMode) {
        // Exiting range mode: keep any completed range, clear partial
        if (state.rangeStart && !state.rangeEnd) {
          clearSelection();
        }
      }
    };

    const toggleNearPeak = () => {
      state.gcNearPeakEnabled = !state.gcNearPeakEnabled;
      api.setAutoGc({
        nearPeak: state.gcNearPeakEnabled,
        threshold: state.gcThresholdEnabled,
        thresholdMB: state.gcThresholdMB,
      });
    };

    const toggleThreshold = () => {
      state.gcThresholdEnabled = !state.gcThresholdEnabled;
      if (state.gcThresholdEnabled && state.gcThresholdMB > 0) {
        if (!state.yScaleAuto && state.yScaleMax != null && state.yScaleMax < state.gcThresholdMB * 1.15) {
          state.yScaleAuto = true;
          state.yScaleMax = null;
        }
      }
      api.setAutoGc({
        nearPeak: state.gcNearPeakEnabled,
        threshold: state.gcThresholdEnabled,
        thresholdMB: state.gcThresholdMB,
      });
    };

    const usagePct = computed(() => {
      const p = last.value;
      if (!p || !p.maxMB) return null;
      return Math.min(100, (p.usedMB / p.maxMB) * 100);
    });
    const usedLen = computed(() => {
      const p = last.value;
      if (!p || !p.maxMB) return 0;
      return Math.min(C, (p.usedMB / p.maxMB) * C);
    });
    const committedLen = computed(() => {
      const p = last.value;
      if (!p || !p.maxMB) return 0;
      return Math.min(C, (p.committedMB / p.maxMB) * C);
    });
    const dashArray = (len) => len + ' ' + (C - len);
    const pctText = computed(() => usagePct.value != null ? usagePct.value.toFixed(0) + '%' : '-');
    const usageClass = computed(() => {
      const p = usagePct.value;
      if (p == null) return '';
      if (p >= 85) return 'high';
      if (p >= 60) return 'mid';
      return 'low';
    });
    const usageTitle = computed(() => {
      const p = last.value;
      if (!p) return '';
      return 'used ' + p.usedMB + ' MB / committed ' + p.committedMB + ' MB / max ' + p.maxMB + ' MB';
    });

    const goToPeak = () => {
      let peak = null;
      for (const p of state.points) {
        if (!peak || p.usedMB > peak.usedMB) peak = p;
      }
      if (peak) {
        selectPoint(peak);
        ensureVisible(peak, { force: true });
      }
    };

    const takeHeapDump = async () => {
      state.heapDumpBusy = true;
      try {
        const name = 'dump-' + new Date().toLocaleTimeString().replace(/:/g, '');
        const info = await api.takeHeapDump(name);
        if (info && info.path) {
          state.heapDumps = [...state.heapDumps, info];
        }
      } catch (e) {
        console.error('heap dump failed:', e);
      } finally {
        state.heapDumpBusy = false;
      }
    };

    const openDump = (path) => { api.openHeapDump(path); };

    const reversedDumps = computed(() => [...state.heapDumps].reverse());

    const formatSize = (bytes) => {
      if (bytes == null) return '?';
      if (bytes < 1024) return bytes + ' B';
      if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
      return (bytes / 1024 / 1024).toFixed(1) + ' MB';
    };

    const formatDumpTime = (ts) => {
      if (!ts) return '';
      const d = new Date(ts);
      return d.toLocaleTimeString();
    };

    const cells = computed(() => {
      const p = last.value;
      return [
        { k: 'used',      v: p ? p.usedMB : null,      u: 'MB' },
        { k: 'committed', v: p ? p.committedMB : null, u: 'MB' },
        { k: 'max',       v: p ? p.maxMB : null,       u: 'MB' },
        { k: 'peak',      v: state.maxObservedMB,      u: 'MB', click: goToPeak },
      ];
    });

    return {
      state, last, thresholdInput, thresholdPresets, dumpsOpen, dumpToggleEl, dropdownStyle, toggleDumps,
      forceGc, toggleNearPeak, toggleThreshold, toggleRangeMode, openLayoutTab,
      usagePct, usedLen, committedLen, dashArray, pctText, usageClass, usageTitle,
      cells, takeHeapDump, openDump, reversedDumps, formatSize, formatDumpTime,
    };
  }
};
