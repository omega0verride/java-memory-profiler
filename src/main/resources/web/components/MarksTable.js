import { computed, ref } from 'vue';
import { state, selectPoint, ensureVisible } from '../state.js';
import { markRows } from '../chart.js';
import { badgeTextColor, formatNumbers } from '../annotations.js';

const KIND_VAR = { MARK: '--mark', SCOPE_START: '--start', SCOPE_END: '--end' };

/**
 * Rows of repeated numbers (several "6." progress marks) reduced to the first
 * and the last of each run of the same number.
 */
export function withoutRepeats(rows) {
  const out = [];
  for (let i = 0; i < rows.length; i++) {
    const r = rows[i];
    const prevSame = i > 0 && rows[i - 1].badge === r.badge;
    const nextSame = i + 1 < rows.length && rows[i + 1].badge === r.badge;
    if (prevSame && nextSame) continue;
    out.push(r);
  }
  return out;
}

export default {
  template: `
    <div v-if="state.annotations && allRows.length" class="marks-panel" :class="{ collapsed: state.marksCollapsed }">
      <div class="marks-head" @click="state.marksCollapsed = !state.marksCollapsed">
        <span class="panel-title marks-title">Marks <span class="events-count">{{ rows.length }}<template v-if="rows.length !== allRows.length"> of {{ allRows.length }}</template></span></span>
        <span class="marks-hint">Click a row to centre the chart on it</span>
        <label v-if="hasRepeats" class="marks-opt" @click.stop title="Keep only the first and the last of repeated numbers (for example several 6. progress marks)">
          <input type="checkbox" v-model="state.hideRepeatedMarks"> hide repeats
        </label>
        <button class="marks-copy" @click.stop="copyMarkdown" :title="'Copy the table as Markdown'">{{ copied ? 'Copied' : 'Copy as Markdown' }}</button>
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
              <th class="num" title="Change since the first mark">Δ first</th>
              <th class="num">Elapsed (s)</th>
              <th v-if="hasGc" class="num" title="Time spent in the forced GC of markGc / scopeGc">GC ms</th>
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
                {{ text(r) }}<span v-if="r.point.kind !== 'MARK'" class="scope-note">{{ scopeNote(r) }}</span>
              </td>
              <td class="num">{{ fmtNum(r.point.usedMB) }}</td>
              <td class="num" :class="deltaClass(r.deltaPrevMB)">{{ fmtDelta(r.deltaPrevMB) }}</td>
              <td class="num" :class="deltaClass(deltaFirst(r))">{{ fmtDelta(deltaFirst(r)) }}</td>
              <td class="num">{{ (r.elapsedMs / 1000).toFixed(1) }}</td>
              <td v-if="hasGc" class="num muted">{{ r.point.gcMs != null ? fmtNum(r.point.gcMs) : '' }}</td>
              <td class="c-loc mono" :title="r.point.location || ''">{{ r.shortLocation }}</td>
              <td class="c-thread" :title="r.point.thread || ''">{{ r.point.thread || '' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  `,
  setup() {
    const allRows = computed(() => (state.annotations ? markRows() : []));
    const hasRepeats = computed(() => withoutRepeats(allRows.value).length !== allRows.value.length);
    const rows = computed(() => (state.hideRepeatedMarks ? withoutRepeats(allRows.value) : allRows.value));
    const hasGc = computed(() => allRows.value.some(r => r.point.gcMs != null));
    const first = computed(() => (allRows.value.length ? allRows.value[0].point.usedMB : null));
    const copied = ref(false);

    const colorOf = (kind) => {
      void state.theme; // re-read on theme change
      const v = getComputedStyle(document.documentElement).getPropertyValue(KIND_VAR[kind] || '--mark');
      return (v || '').trim() || '#ffb020';
    };
    const badgeStyle = (r) => {
      const bg = colorOf(r.point.kind);
      return { background: bg, color: badgeTextColor(bg) };
    };
    const text = (r) => (state.meta && state.meta.formatLabelNumbers ? formatNumbers(r.text) : r.text);
    const fmtNum = (n) => Number(n).toLocaleString('en-US');
    const fmtDelta = (d) => (d == null ? '' : (d > 0 ? '+' : '') + fmtNum(d));
    const deltaClass = (d) => (d == null || d === 0 ? '' : d > 0 ? 'positive' : 'negative');
    const deltaFirst = (r) => (r === allRows.value[0] || first.value == null ? null : r.point.usedMB - first.value);
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
    const md = (s) => String(s == null ? '' : s).replace(/\|/g, '\\|');
    const copyMarkdown = async () => {
      const head = ['#', 'Mark', 'Retained MB', 'Δ prev', 'Δ first', 'Elapsed (s)'];
      if (hasGc.value) head.push('GC ms');
      head.push('Location');
      const lines = ['| ' + head.join(' | ') + ' |',
        '|' + head.map((h, i) => (i >= 2 && i <= 6 && h !== 'Location' ? ' ---: ' : ' --- ')).join('|') + '|'];
      for (const r of rows.value) {
        const cells = [r.badge, md(text(r) + (r.point.kind !== 'MARK' ? scopeNote(r) : '')), fmtNum(r.point.usedMB),
          fmtDelta(r.deltaPrevMB), fmtDelta(deltaFirst(r)), (r.elapsedMs / 1000).toFixed(1)];
        if (hasGc.value) cells.push(r.point.gcMs != null ? fmtNum(r.point.gcMs) : '');
        cells.push('`' + md(r.shortLocation) + '`');
        lines.push('| ' + cells.join(' | ') + ' |');
      }
      const out = lines.join('\n') + '\n';
      try {
        await navigator.clipboard.writeText(out);
      } catch (e) {
        const ta = document.createElement('textarea');
        ta.value = out;
        document.body.appendChild(ta);
        ta.select();
        try { document.execCommand('copy'); } catch (e2) { /* nothing else to try */ }
        ta.remove();
      }
      copied.value = true;
      setTimeout(() => { copied.value = false; }, 1500);
    };

    return {
      state, rows, allRows, hasRepeats, hasGc, copied, badgeStyle, text, fmtNum, fmtDelta, deltaClass, deltaFirst,
      scopeNote, onClick, copyMarkdown,
    };
  },
};
