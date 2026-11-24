import { computed } from 'vue';
import { state } from '../state.js';

function fmtSec(ms) {
  return ms >= 60000 ? Math.round(ms / 1000) + ' s' : (ms / 1000).toFixed(ms < 10000 ? 1 : 0) + ' s';
}

/** One-line key under the chart: how the line was sampled and what dashes and gaps mean. */
export default {
  template: `
    <div class="chart-key" v-if="state.points.length">
      <span class="ck-item"><span class="ck-swatch"></span>Line: {{ sampling }}, includes garbage</span>
      <span v-if="counts.bridged" class="ck-item" :title="dashTitle">
        <span class="ck-swatch dashed"></span>dashed: no sample for over {{ dashAfter }}, JVM busy in GC ({{ counts.bridged }})
      </span>
      <span v-if="counts.breaks" class="ck-item" title="No sample and no GC activity: the VM was not observed (debugger pause, suspended process)">
        <span class="ck-swatch gap"></span>gap: no observation ({{ counts.breaks }})
      </span>
      <span v-if="gcText" class="ck-item">{{ gcText }}</span>
    </div>
  `,
  setup() {
    const sampling = computed(() => state.samplingText || 'sampled heap');
    const counts = computed(() => {
      const list = state.gaps ? state.gaps.list : [];
      let bridged = 0, breaks = 0;
      for (const g of list) (g.bridged ? bridged++ : breaks++);
      return { bridged, breaks };
    });
    const dashAfter = computed(() => fmtSec(state.gaps ? state.gaps.dashAfterMs : 1000));
    const dashTitle = 'The sampler thread could not run (stop-the-world GC, forced GC of markGc); '
      + 'the heap was continuous, so the points are connected with a dashed segment.';
    const gcText = computed(() => {
      const m = state.meta;
      if (!m || !(m.gcTotalMs > 0)) return '';
      const share = m.durationMs > 0 ? ' (' + Math.round(100 * m.gcTotalMs / m.durationMs) + '% of the run)' : '';
      return 'GC ' + fmtSec(m.gcTotalMs) + share + (m.longestGcMs > 0 ? ', longest pause ' + fmtSec(m.longestGcMs) : '');
    });
    return { state, sampling, counts, dashAfter, dashTitle, gcText };
  },
};
