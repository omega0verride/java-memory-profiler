import { createApp, ref, computed } from 'vue';
import * as api from './api.js';

const app = createApp({
  setup() {
    const className = ref('');
    const loading = ref(false);
    const error = ref(null);
    const result = ref(null);
    const highlightLine = ref(0);

    const lines = computed(() => {
      if (!result.value || !result.value.source) return [];
      return result.value.source.split('\n');
    });

    const loadSource = async () => {
      const name = className.value.trim();
      if (!name) return;
      loading.value = true;
      error.value = null;
      result.value = null;
      try {
        const res = await api.getClassSource(name);
        if (res.error) {
          error.value = res.error;
        } else {
          result.value = res;
        }
      } catch (e) {
        error.value = 'Request failed: ' + e.message;
      } finally {
        loading.value = false;
      }
    };

    return { className, loading, error, result, lines, highlightLine, loadSource };
  },
  mounted() {
    const params = new URLSearchParams(window.location.search);
    const cls = params.get('class');
    const line = params.get('line');
    if (cls) {
      this.className = cls;
      if (line) this.highlightLine = parseInt(line, 10) || 0;
      this.loadSource();
    }
  }
});

app.mount('#app');
