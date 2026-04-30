import { computed } from 'vue';
import { state } from '../state.js';
import { fmtTs, kindClass } from '../util.js';

export default {
  template: `
    <div class="hover-header">
      <span class="hh-cell hh-ts mono" title="Timestamp">{{ hover ? fmtTs(hover.timestamp) : '--:--:--.---' }}</span>
      <span class="hh-cell hh-kind" title="Event type"><span class="pill small" :class="cls">{{ hover ? hover.kind : '--' }}</span></span>
      <span class="hh-cell hh-delta" :class="deltaClass" title="Memory delta since previous event">{{ deltaText }}</span>
      <span class="hh-cell hh-label" :title="hover && hover.label ? 'Label: ' + hover.label : 'Event label'">{{ hover && hover.label ? hover.label : '--' }}</span>
      <span class="hh-cell mono" title="Heap memory used">{{ hover ? hover.usedMB : '--' }} <span class="hh-unit">MB used</span></span>
      <span class="hh-cell mono" title="Heap memory committed">{{ hover ? hover.committedMB : '--' }} <span class="hh-unit">MB commit</span></span>
      <span class="hh-cell mono" title="GC pause duration">{{ durationText }} <span class="hh-unit">ms</span></span>
    </div>
  `,
  setup() {
    const hover = computed(() => state.hover);
    const cls = computed(() => hover.value ? kindClass(hover.value.kind) : '');
    const deltaClass = computed(() => {
      if (!hover.value || hover.value.deltaMB == null) return '';
      return hover.value.deltaMB >= 0 ? 'positive' : 'negative';
    });
    const deltaText = computed(() => {
      if (!hover.value || hover.value.deltaMB == null) return '-- MB';
      return (hover.value.deltaMB >= 0 ? '+' : '') + hover.value.deltaMB + ' MB';
    });
    const durationText = computed(() => {
      if (!hover.value || hover.value.durationMs == null) return '--';
      return hover.value.durationMs;
    });
    return { state, hover, cls, deltaClass, deltaText, durationText, fmtTs };
  }
};
