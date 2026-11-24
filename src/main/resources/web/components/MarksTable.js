import { computed } from 'vue';
import { state, selectPoint, ensureVisible } from '../state.js';
import { markRows } from '../chart.js';
import { badgeTextColor } from '../annotations.js';

const KIND_VAR = { MARK: '--mark', SCOPE_START: '--start', SCOPE_END: '--end' };

export default {
  template: `
    <div v-if="state.annotations && rows.length" class="marks-panel" :class="{ collapsed: state.marksCollapsed }">
      <div class="marks-head" @click="state.marksCollapsed = !state.marksCollapsed">
        <span class="panel-title marks-title">Marks <span class="events-count">{{ rows.length }}</span></span>
        <span class="marks-hint">Click a row to centre the chart on it</span>
        <button class="marks-collapse" :title="state.marksCollapsed ? 'Show the marks table' : 'Hide the marks table'">
          {{ state.marksCollapsed ? '▴' : '▾' }}
        </button>
      </div>
      <div v-show="!state.marksCollapsed" class="marks-table-wrap">
        <table class="marks-table">
          <thead>
            <tr>
              <th class="c-badge">#</th>
              <th>Mark</th>
              <th class="num">Retained MB</th>
              <th class="num">Δ prev</th>
              <th class="num">Elapsed (s)</th>
              <th>Location</th>
              <th>Thread</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.point.index"
                :class="{ selected: state.selected && state.selected.index === r.point.index }"
                @click="onClick(r)">
              <td class="c-badge"><span class="mark-badge" :style="badgeStyle(r)">{{ r.badge }}</span></td>
              <td class="c-label" :title="r.point.label || ''">
                {{ r.text }}<span v-if="r.point.kind !== 'MARK'" class="scope-note">{{ scopeNote(r) }}</span>
              </td>
              <td class="num">{{ fmtNum(r.point.usedMB) }}</td>
              <td class="num" :class="deltaClass(r)">{{ fmtDelta(r.deltaPrevMB) }}</td>
              <td class="num">{{ (r.elapsedMs / 1000).toFixed(1) }}</td>
              <td class="c-loc mono" :title="r.point.location || ''">{{ r.shortLocation }}</td>
              <td class="c-thread" :title="r.point.thread || ''">{{ r.point.thread || '' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  `,
  setup() {
    const rows = computed(() => (state.annotations ? markRows() : []));

    const colorOf = (kind) => {
      void state.theme; // re-read on theme change
      const v = getComputedStyle(document.documentElement).getPropertyValue(KIND_VAR[kind] || '--mark');
      return (v || '').trim() || '#ffb020';
    };
    const badgeStyle = (r) => {
      const bg = colorOf(r.point.kind);
      return { background: bg, color: badgeTextColor(bg) };
    };
    const fmtNum = (n) => Number(n).toLocaleString('en-US');
    const fmtDelta = (d) => (d == null ? '' : (d > 0 ? '+' : '') + fmtNum(d));
    const deltaClass = (r) => (r.deltaPrevMB == null || r.deltaPrevMB === 0 ? '' : r.deltaPrevMB > 0 ? 'positive' : 'negative');
    const scopeNote = (r) => {
      const p = r.point;
      if (p.kind === 'SCOPE_START') return ' (scope start)';
      let s = ' (scope end';
      if (p.deltaMB != null) s += ', ' + (p.deltaMB >= 0 ? '+' : '') + p.deltaMB + ' MB';
      if (p.durationMs != null) s += ' in ' + (p.durationMs < 1000 ? p.durationMs + ' ms' : (p.durationMs / 1000).toFixed(1) + ' s');
      return s + ')';
    };
    const onClick = (r) => {
      selectPoint(r.point);
      ensureVisible(r.point, { force: true });
    };

    return { state, rows, badgeStyle, fmtNum, fmtDelta, deltaClass, scopeNote, onClick };
  },
};
