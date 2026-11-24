import { computed } from 'vue';
import { state } from '../state.js';
import { kindClass } from '../util.js';
import { markRows } from '../chart.js';
import { isAnnotated } from '../annotations.js';

export default {
  template: `
    <Transition name="hover-card">
      <div v-if="hover && (!state.selected || hover.index !== state.selected.index)"
           class="hover-card" :class="{ rich: !!row || !!snippet }" :style="cardStyle">
        <template v-if="row">
          <div class="hc-head">
            <span class="pill small" :class="cls">#{{ row.badge }}</span>
            <span class="hc-label">{{ row.text }}</span>
            <span class="hover-card-val">{{ hover.usedMB }} MB</span>
          </div>
          <div v-if="row.shortLocation" class="hc-loc">{{ row.shortLocation }}</div>
        </template>
        <span v-else class="hover-card-val">{{ hover.usedMB }} MB</span>
        <pre v-if="snippet" class="code-snippet compact"><code><span v-for="(l, i) in snippet.lines" :key="i"
          class="code-line" :class="{ hl: snippet.start + i === snippet.line }"><span class="ln">{{ snippet.start + i }}</span>{{ l }}
</span></code></pre>
      </div>
    </Transition>
  `,
  setup() {
    const hover = computed(() => state.hover);
    const cls = computed(() => hover.value ? kindClass(hover.value.kind) : '');

    const row = computed(() => {
      const h = hover.value;
      if (!h || !state.annotations || !isAnnotated(h.kind)) return null;
      return markRows().find(r => r.point.index === h.index) || null;
    });
    const snippet = computed(() => {
      const h = hover.value;
      if (!h || !h.sourceFile || !state.sources) return null;
      return state.sources[h.sourceFile] || null;
    });

    const cardStyle = computed(() => {
      if (!hover.value) return {};
      const pos = state.hoverPos;
      const rich = !!row.value || !!snippet.value;
      const cardWidth = snippet.value ? 460 : rich ? 300 : 160;
      const cardHeight = snippet.value ? 60 + snippet.value.lines.length * 15 : rich ? 52 : 30;
      
      let left = pos.x + 15;
      let top = pos.y - 20;
      
      const chartWrap = document.getElementById('chartWrap');
      if (chartWrap) {
        const rect = chartWrap.getBoundingClientRect();
        if (left + cardWidth > rect.width - 10) {
          left = pos.x - cardWidth - 15;
        }
        if (left < 4) left = 4;
        if (top + cardHeight > rect.height - 10) {
          top = rect.height - cardHeight - 10;
        }
        if (top < 10) {
          top = 10;
        }
      }
      
      return {
        left: left + 'px',
        top: top + 'px',
      };
    });

    return { state, hover, cls, row, snippet, cardStyle };
  }
};
