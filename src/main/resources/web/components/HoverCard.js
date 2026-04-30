import { computed } from 'vue';
import { state } from '../state.js';
import { kindClass } from '../util.js';

export default {
  template: `
    <Transition name="hover-card">
      <div v-if="hover && (!state.selected || hover.index !== state.selected.index)" class="hover-card" :style="cardStyle">
        <span class="hover-card-val">{{ hover.usedMB }} MB</span>
      </div>
    </Transition>
  `,
  setup() {
    const hover = computed(() => state.hover);

    const cardStyle = computed(() => {
      if (!hover.value) return {};
      const pos = state.hoverPos;
      const cardWidth = 160;
      const cardHeight = 30;
      
      let left = pos.x + 15;
      let top = pos.y - 20;
      
      const chartWrap = document.getElementById('chartWrap');
      if (chartWrap) {
        const rect = chartWrap.getBoundingClientRect();
        if (left + cardWidth > rect.width - 10) {
          left = pos.x - cardWidth - 15;
        }
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

    return { state, hover, cardStyle };
  }
};
