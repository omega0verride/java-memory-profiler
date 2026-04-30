import { onMounted, onBeforeUnmount, ref, watch, nextTick } from 'vue';
import { state } from '../state.js';

export default {
  template: `
    <div class="chart-menu" v-show="state.menu.open"
         :style="{ left: pos.x + 'px', top: pos.y + 'px' }"
         ref="root">
      <template v-for="(it, i) in state.menu.items" :key="i">
        <div v-if="it.sep" class="sep"></div>
        <div v-else class="item" :class="{ disabled: it.disabled }" @click="run(it)">
          <span>{{ it.label }}</span>
          <span v-if="it.hint" class="hint">{{ it.hint }}</span>
        </div>
      </template>
    </div>
  `,
  setup() {
    const root = ref(null);
    const pos = ref({ x: 0, y: 0 });

    const place = async () => {
      await nextTick();
      const wrap = root.value && root.value.parentElement;
      if (!wrap) return;
      const wrect = wrap.getBoundingClientRect();
      const mw = root.value.offsetWidth, mh = root.value.offsetHeight;
      let x = state.menu.x - wrect.left;
      let y = state.menu.y - wrect.top;
      if (x + mw > wrect.width - 4) x = wrect.width - mw - 4;
      if (y + mh > wrect.height - 4) y = wrect.height - mh - 4;
      if (x < 4) x = 4;
      if (y < 4) y = 4;
      pos.value = { x, y };
    };

    watch(() => state.menu.open, (open) => { if (open) place(); });
    watch(() => state.menu.items, () => { if (state.menu.open) place(); });

    const run = (it) => {
      if (it.disabled) return;
      state.menu.open = false;
      it.action && it.action();
    };

    const closeOnOutside = (e) => {
      if (!state.menu.open) return;
      if (root.value && !root.value.contains(e.target)) state.menu.open = false;
    };
    const closeOnBlur = () => { state.menu.open = false; };
    const onKey = (e) => {
      if (e.key === 'Escape' && state.menu.open) state.menu.open = false;
    };

    onMounted(() => {
      document.addEventListener('mousedown', closeOnOutside);
      window.addEventListener('blur', closeOnBlur);
      window.addEventListener('resize', closeOnBlur);
      document.addEventListener('keydown', onKey);
    });
    onBeforeUnmount(() => {
      document.removeEventListener('mousedown', closeOnOutside);
      window.removeEventListener('blur', closeOnBlur);
      window.removeEventListener('resize', closeOnBlur);
      document.removeEventListener('keydown', onKey);
    });

    return { state, root, pos, run };
  }
};
