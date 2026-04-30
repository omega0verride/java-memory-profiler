import { createApp, ref, computed } from 'vue';
import * as api from './api.js';

const KIND_COLORS = {
  header: '#4ea1ff',
  field: '#37c26a',
  padding: '#5a6478',
};

const app = createApp({
  setup() {
    const sourceCode = ref('');
    const sourceEl = ref(null);
    const loading = ref(false);
    const error = ref(null);
    const result = ref(null);

    // Class browser state
    const activeTab = ref('source'); // 'source' or 'browse'
    const classSearch = ref('');
    const classList = ref([]);
    const classLoading = ref(false);
    const classError = ref(null);

    const searchClasses = async () => {
      const q = classSearch.value.trim();
      if (!q || q.length < 2) {
        classList.value = [];
        return;
      }
      classLoading.value = true;
      classError.value = null;
      try {
        const res = await api.listClasses(q);
        if (res.error) {
          classError.value = res.error;
          classList.value = [];
        } else {
          classList.value = res.slice(0, 200); // cap displayed results
        }
      } catch (e) {
        classError.value = 'Request failed: ' + e.message;
        classList.value = [];
      } finally {
        classLoading.value = false;
      }
    };

    let searchTimeout = null;
    const onSearchInput = () => {
      clearTimeout(searchTimeout);
      searchTimeout = setTimeout(searchClasses, 300);
    };

    const analyzeClass = async (className) => {
      loading.value = true;
      error.value = null;
      result.value = null;
      try {
        const res = await api.getObjectLayout(className);
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

    const samples = [
      { name: 'Minimal', code: 'private int x;\nprivate int y;' },
      { name: 'Mixed primitives', code: 'private long id;\nprivate int count;\nprivate short flags;\nprivate byte status;\nprivate boolean active;' },
      { name: 'With references', code: 'private long id;\nprivate String name;\nprivate Object payload;\nprivate int count;' },
      { name: 'Padding demo', code: 'private boolean a;\nprivate long b;\nprivate boolean c;\nprivate long d;' },
      { name: 'Large object', code: 'private long timestamp;\nprivate int userId;\nprivate String email;\nprivate String username;\nprivate double balance;\nprivate boolean verified;\nprivate Object metadata;' },
    ];

    const loadSample = (s) => {
      sourceCode.value = s.code;
      error.value = null;
      result.value = null;
    };

    const compileSource = async () => {
      const src = sourceCode.value.trim();
      if (!src) return;
      loading.value = true;
      error.value = null;
      result.value = null;
      try {
        const res = await api.compileAndAnalyze(src);
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

    const shortName = (fqn) => {
      if (!fqn) return '';
      const i = fqn.lastIndexOf('.');
      return i >= 0 ? fqn.substring(i + 1) : fqn;
    };

    const segStyle = (e) => {
      const total = result.value ? result.value.shallowSize : 1;
      const pct = Math.max(1, (e.size / total) * 100);
      return {
        width: pct + '%',
        background: KIND_COLORS[e.kind] || '#5a6478',
      };
    };

    const segTooltip = (e) => {
      let s = e.kind + ' @ offset ' + e.offset + ', ' + e.size + 'B';
      if (e.fieldName) s += ' \u2014 ' + e.fieldType + ' ' + e.fieldName;
      return s;
    };

    const copyMarkdown = async () => {
      const r = result.value;
      if (!r) return;
      const lines = [];
      lines.push('## Object Layout: ' + (r.className || 'Anonymous'));
      lines.push('');
      lines.push('| Metric | Value |');
      lines.push('|--------|-------|');
      lines.push('| Shallow size | ' + r.shallowSize + ' B |');
      lines.push('| Header | ' + r.headerSize + ' B |');
      lines.push('| Fields | ' + r.fieldsSize + ' B |');
      lines.push('| Padding | ' + r.paddingSize + ' B |');
      lines.push('');
      lines.push('| Offset | Size | Kind | Description |');
      lines.push('|-------:|-----:|------|-------------|');
      for (const e of r.entries) {
        let desc;
        if (e.kind === 'field') {
          desc = '`' + e.fieldType + '` ' + e.fieldName;
          if (e.declaringClass && e.declaringClass !== r.className) desc += ' (' + shortName(e.declaringClass) + ')';
        } else if (e.kind === 'header') {
          desc = 'object header (mark + klass)';
        } else {
          desc = 'alignment/padding gap';
        }
        lines.push('| ' + e.offset + ' | ' + e.size + ' | ' + e.kind + ' | ' + desc + ' |');
      }
      await navigator.clipboard.writeText(lines.join('\n'));
    };

    return {
      sourceCode, sourceEl,
      loading, error, result,
      samples, loadSample,
      compileSource, shortName, segStyle, segTooltip, copyMarkdown,
      activeTab, classSearch, classList, classLoading, classError,
      onSearchInput, analyzeClass,
    };
  },
  mounted() {
    // Auto-analyze if ?class= is in the URL
    const params = new URLSearchParams(window.location.search);
    const cls = params.get('class');
    if (cls) {
      this.activeTab = 'browse';
      this.classSearch = cls;
      this.analyzeClass(cls);
    }
  }
});

app.mount('#app');
