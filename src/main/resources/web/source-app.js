import { createApp, ref, computed, nextTick, onMounted } from 'vue';
import * as api from './api.js';

const app = createApp({
  setup() {
    const className = ref('');
    const loading = ref(false);
    const error = ref(null);
    const result = ref(null);
    const highlightLine = ref(0);
    // the mark this page was opened for (?class=&file=&line=); a different class typed in the box has no line
    const target = { className: '', file: '', line: 0 };

    const lines = computed(() => {
      if (!result.value || !result.value.source) return [];
      return result.value.source.split('\n');
    });

    // "line 151", or where line 151 of the source ended up in decompiled code
    const lineNote = computed(() => {
      const r = result.value;
      if (!r || !r.sourceLine) return '';
      if (r.origin === 'source') return r.line ? 'line ' + r.line : 'line ' + r.sourceLine + ' is past the end of the file';
      return r.line
        ? 'source line ' + r.sourceLine + ' → line ' + r.line
        : 'source line ' + r.sourceLine + ' has no code in the decompiled class';
    });

    const scrollToHighlight = () => nextTick(() => {
      const el = document.querySelector('.source-code .line-highlight');
      if (el) el.scrollIntoView({ block: 'center' });
    });

    const loadSource = async () => {
      const name = className.value.trim();
      if (!name) return;
      const forTarget = name === target.className;
      loading.value = true;
      error.value = null;
      result.value = null;
      highlightLine.value = 0;
      try {
        const res = await api.getClassSource(name, forTarget ? target.file : '', forTarget ? target.line : 0);
        if (res.error) {
          error.value = res.error;
        } else {
          result.value = res;
          highlightLine.value = res.line || 0;
          if (highlightLine.value) scrollToHighlight();
        }
      } catch (e) {
        error.value = 'Request failed: ' + e.message;
      } finally {
        loading.value = false;
      }
    };

    onMounted(() => {
      const params = new URLSearchParams(window.location.search);
      const cls = params.get('class');
      if (!cls) return;
      target.className = cls;
      target.file = params.get('file') || '';
      target.line = parseInt(params.get('line'), 10) || 0;
      className.value = cls;
      loadSource();
    });

    return { className, loading, error, result, lines, highlightLine, lineNote, loadSource, scrollToHighlight };
  },
});

app.mount('#app');
