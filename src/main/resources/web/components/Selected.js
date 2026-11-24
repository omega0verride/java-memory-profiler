import { computed, ref, watchEffect, onMounted, onUnmounted } from 'vue';
import { state, clearSelection, selectPoint, ensureVisible } from '../state.js';
import { openLocation, downloadPngExport } from '../api.js';
import { fmtTs, kindClass, SNAPSHOT_TIP } from '../util.js';

const MINI_CHART_WINDOW = 30; // number of points to show on each side

export default {
  template: `
    <div class="panel selected">
      <div class="panel-title">
        <span>{{ panelTitle }}</span>
        <div class="selected-actions">
          <template v-if="canNavigate">
            <button class="nav-btn" :disabled="!prevSibling" @click="goPrev" title="Previous (←)">‹</button>
            <button class="nav-btn" :disabled="!nextSibling" @click="goNext" title="Next (→)">›</button>
          </template>
          <button v-if="hasSelection" class="deselect-btn" @click="deselect" title="Deselect (Esc)">×</button>
        </div>
      </div>

      <!-- Empty state -->
      <div v-if="!hasSelection" class="selected-card empty">
        <div class="empty-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <circle cx="12" cy="12" r="10"/>
            <path d="M12 8v4M12 16h.01"/>
          </svg>
        </div>
        <div class="empty-text">Click a point to see details</div>
        <div class="empty-hint">Shift+Click two points to analyze a range</div>
      </div>

      <!-- Range waiting for second point -->
      <div v-else-if="rangePartial" class="selected-card has-data range-partial">
        <div class="range-status">
          <span class="pill large range-pill">RANGE</span>
          <span class="range-hint">Shift+Click a second point to complete range</span>
        </div>
        <div class="selected-header">
          <div class="selected-kind">
            <span class="pill large" :class="startCls">{{ rangeStart.kind }}</span>
          </div>
          <div class="selected-label">Start: {{ rangeStart.label || '#' + rangeStart.index }}</div>
          <div class="selected-time">{{ fmtTs(rangeStart.timestamp) }}</div>
        </div>
        <div class="selected-metrics">
          <div class="metric-item">
            <div class="metric-label">Used</div>
            <div class="metric-value highlight">{{ rangeStart.usedMB }}<span class="unit">MB</span></div>
          </div>
        </div>
      </div>

      <!-- Range analysis -->
      <div v-else-if="range" class="selected-card has-data range-analysis">
        <div class="range-status">
          <span class="pill large range-pill">RANGE</span>
          <span class="range-count">{{ rangeStats.pointCount }} points</span>
        </div>

        <div class="range-endpoints">
          <div class="range-endpoint">
            <div class="range-ep-label">Start</div>
            <div class="range-ep-detail">
              <span class="pill small" :class="startCls">{{ rangeStart.kind }}</span>
              <span>{{ rangeStart.label || '#' + rangeStart.index }}</span>
            </div>
            <div class="range-ep-time">{{ fmtTs(rangeStart.timestamp) }}</div>
            <div class="range-ep-used">{{ rangeStart.usedMB }} MB</div>
          </div>
          <div class="range-arrow">→</div>
          <div class="range-endpoint">
            <div class="range-ep-label">End</div>
            <div class="range-ep-detail">
              <span class="pill small" :class="endCls">{{ rangeEnd.kind }}</span>
              <span>{{ rangeEnd.label || '#' + rangeEnd.index }}</span>
            </div>
            <div class="range-ep-time">{{ fmtTs(rangeEnd.timestamp) }}</div>
            <div class="range-ep-used">{{ rangeEnd.usedMB }} MB</div>
          </div>
        </div>

        <div class="selected-mini-chart-wrap">
          <canvas ref="miniCanvas" class="selected-mini-chart"></canvas>
        </div>

        <div class="range-section-title">Memory (Used MB)</div>
        <div class="selected-metrics">
          <div class="metric-item">
            <div class="metric-label">Delta</div>
            <div class="metric-value" :class="rangeStats.deltaClass">
              {{ rangeStats.deltaSign }}{{ Math.abs(rangeStats.deltaMB) }}<span class="unit">MB</span>
            </div>
          </div>
          <div class="metric-item">
            <div class="metric-label">Min</div>
            <div class="metric-value">{{ rangeStats.minUsed }}<span class="unit">MB</span></div>
          </div>
          <div class="metric-item">
            <div class="metric-label">Max</div>
            <div class="metric-value highlight">{{ rangeStats.maxUsed }}<span class="unit">MB</span></div>
          </div>
          <div class="metric-item">
            <div class="metric-label">Avg</div>
            <div class="metric-value">{{ rangeStats.avgUsed }}<span class="unit">MB</span></div>
          </div>
        </div>

        <div class="range-section-title">Time</div>
        <div class="selected-metrics">
          <div class="metric-item wide">
            <div class="metric-label">Duration</div>
            <div class="metric-value">{{ rangeStats.durationStr }}</div>
          </div>
        </div>

        <div v-if="rangeStats.gcCount > 0" class="range-section-title">Events in Range</div>
        <div v-if="rangeStats.gcCount > 0" class="selected-metrics">
          <div v-if="rangeStats.gcCount" class="metric-item">
            <div class="metric-label">GC</div>
            <div class="metric-value">{{ rangeStats.gcCount }}</div>
          </div>
          <div v-if="rangeStats.markCount" class="metric-item">
            <div class="metric-label">Marks</div>
            <div class="metric-value">{{ rangeStats.markCount }}</div>
          </div>
          <div v-if="rangeStats.scopeCount" class="metric-item">
            <div class="metric-label">Scopes</div>
            <div class="metric-value">{{ rangeStats.scopeCount }}</div>
          </div>
        </div>

        <div class="range-export">
          <button class="tool-btn" @click="copyRange" title="Copy start, end, retained delta and duration as a Markdown table row">
            {{ rangeCopied ? 'Copied' : 'Copy as Markdown' }}
          </button>
          <button class="tool-btn" :disabled="state.snapshot" @click="exportRangePng"
                  :title="state.snapshot ? snapshotTip : 'Render this range (with the marks table) as a PNG'">Export PNG</button>
        </div>
      </div>

      <!-- Single point (existing behavior) -->
      <div v-else-if="sel" class="selected-card has-data">
        <div class="selected-header">
          <div class="selected-kind">
            <span class="pill large" :class="cls">{{ sel.kind }}</span>
            <span v-if="sel.deltaMB != null" class="delta-badge" :class="deltaClass">
              {{ (sel.deltaMB >= 0 ? '+' : '') + sel.deltaMB }} MB
            </span>
          </div>
          <div class="selected-label" :title="sel.label || ''">{{ sel.label || '—' }}</div>
          <div class="selected-time">{{ fmtTs(sel.timestamp) }}</div>
        </div>

        <div class="selected-mini-chart-wrap">
          <canvas ref="miniCanvas" class="selected-mini-chart"></canvas>
        </div>

        <div class="selected-metrics">
          <div class="metric-item">
            <div class="metric-label">Used</div>
            <div class="metric-value highlight">{{ sel.usedMB }}<span class="unit">MB</span> <span class="pct">({{ usedPct }}%)</span></div>
          </div>
          <div class="metric-item">
            <div class="metric-label">Committed</div>
            <div class="metric-value">{{ sel.committedMB }}<span class="unit">MB</span> <span class="pct">({{ committedPct }}%)</span></div>
          </div>
          <div class="metric-item">
            <div class="metric-label">Max</div>
            <div class="metric-value">{{ sel.maxMB }}<span class="unit">MB</span></div>
          </div>
          <div v-if="sel.durationMs != null" class="metric-item">
            <div class="metric-label">Duration</div>
            <div class="metric-value">{{ sel.durationMs }}<span class="unit">ms</span></div>
          </div>
        </div>

        <div class="selected-details">
          <div v-if="sel.thread" class="detail-row">
            <span class="detail-key">Thread</span>
            <span class="detail-val">{{ sel.thread }}</span>
          </div>
          <div v-if="sel.scopeId" class="detail-row">
            <span class="detail-key">Scope</span>
            <span class="detail-val">{{ sel.scopeId }}</span>
          </div>
          <div class="detail-row">
            <span class="detail-key">Index</span>
            <span class="detail-val">#{{ sel.index }}</span>
          </div>
        </div>

        <div v-if="sel.location" class="selected-location">
          <div class="location-path">{{ sel.location }}</div>
          <template v-if="!state.snapshot">
            <a href="#" class="location-link" @click.prevent="openIde">Open in IDE</a>
            <a href="#" class="location-link" @click.prevent="viewSource" title="View decompiled source of this class">View Source</a>
          </template>
          <template v-else>
            <span class="location-link disabled" :title="snapshotTip">Open in IDE</span>
            <span class="location-link disabled" :title="snapshotTip">View Source</span>
          </template>
        </div>

        <div v-if="snippet" class="selected-snippet">
          <div class="snippet-file">{{ snippet.file }}:{{ snippet.line }}</div>
          <pre class="code-snippet"><code><span v-for="(l, i) in snippet.lines" :key="i"
            class="code-line" :class="{ hl: snippet.start + i === snippet.line }"><span class="ln">{{ snippet.start + i }}</span>{{ l }}
</span></code></pre>
        </div>
      </div>
    </div>
  `,
  setup() {
    const sel = computed(() => state.selected);
    const snippet = computed(() => {
      const s = state.selected;
      if (!s || !s.sourceFile || !state.sources) return null;
      return state.sources[s.sourceFile] || null;
    });
    const rangeStart = computed(() => state.rangeStart);
    const rangeEnd = computed(() => state.rangeEnd);
    const range = computed(() => state.rangeStart && state.rangeEnd);
    const rangePartial = computed(() => state.rangeStart && !state.rangeEnd);
    const hasSelection = computed(() => !!state.selected || !!state.rangeStart);

    const panelTitle = computed(() => {
      if (range.value) return 'Range Analysis';
      if (rangePartial.value) return 'Range Selection';
      return 'Selected Datapoint';
    });

    const cls = computed(() => sel.value ? kindClass(sel.value.kind) : '');
    const usedPct = computed(() => {
      const s = sel.value;
      if (!s || !s.maxMB) return '0';
      return ((s.usedMB / s.maxMB) * 100).toFixed(1);
    });
    const committedPct = computed(() => {
      const s = sel.value;
      if (!s || !s.maxMB) return '0';
      return ((s.committedMB / s.maxMB) * 100).toFixed(1);
    });
    const startCls = computed(() => rangeStart.value ? kindClass(rangeStart.value.kind) : '');
    const endCls = computed(() => rangeEnd.value ? kindClass(rangeEnd.value.kind) : '');
    const deltaClass = computed(() => {
      if (!sel.value || sel.value.deltaMB == null) return '';
      return sel.value.deltaMB >= 0 ? 'positive' : 'negative';
    });

    const rangeStats = computed(() => {
      if (!rangeStart.value || !rangeEnd.value) return {};
      const s = rangeStart.value;
      const e = rangeEnd.value;
      const pts = state.points.filter(p => p.timestamp >= s.timestamp && p.timestamp <= e.timestamp);

      let minUsed = Infinity, maxUsed = -Infinity, sumUsed = 0;
      let gcCount = 0, markCount = 0, scopeCount = 0;
      for (const p of pts) {
        if (p.usedMB < minUsed) minUsed = p.usedMB;
        if (p.usedMB > maxUsed) maxUsed = p.usedMB;
        sumUsed += p.usedMB;
        if (p.kind === 'GC') gcCount++;
        if (p.kind === 'MARK') markCount++;
        if (p.kind === 'SCOPE_START' || p.kind === 'SCOPE_END') scopeCount++;
      }

      const deltaMB = e.usedMB - s.usedMB;
      const durationMs = e.timestamp - s.timestamp;

      let durationStr;
      if (durationMs < 1000) durationStr = durationMs + ' ms';
      else if (durationMs < 60000) durationStr = (durationMs / 1000).toFixed(1) + ' s';
      else if (durationMs < 3600000) durationStr = (durationMs / 60000).toFixed(1) + ' min';
      else durationStr = (durationMs / 3600000).toFixed(2) + ' h';

      return {
        pointCount: pts.length,
        deltaMB,
        deltaSign: deltaMB >= 0 ? '+' : '',
        deltaClass: deltaMB >= 0 ? 'positive' : 'negative',
        minUsed: pts.length ? minUsed : 0,
        maxUsed: pts.length ? maxUsed : 0,
        avgUsed: pts.length ? Math.round(sumUsed / pts.length) : 0,
        durationMs,
        durationStr,
        gcCount,
        markCount,
        scopeCount,
      };
    });

    const miniCanvas = ref(null);

    const deselect = () => { clearSelection(); };
    const openIde = () => {
      if (!sel.value) return;
      openLocation(sel.value.sourceFile || sel.value.location);
    };
    const viewSource = () => {
      if (!sel.value || !sel.value.location) return;
      const loc = sel.value.location;
      const noLine = loc.replace(/:\d+$/, '');
      const lineMatch = loc.match(/:(\d+)$/);
      const line = lineMatch ? lineMatch[1] : '';
      const lastDot = noLine.lastIndexOf('.');
      const className = lastDot > 0 ? noLine.substring(0, lastDot) : noLine;
      let url = '/source.html?class=' + encodeURIComponent(className);
      if (line) url += '&line=' + line;
      window.open(url, '_blank');
    };

    const canNavigate = computed(() => !!sel.value);
    const selIndexInPoints = computed(() => {
      if (!sel.value) return -1;
      const target = sel.value.index;
      const pts = state.points;
      for (let i = 0; i < pts.length; i++) {
        if (pts[i].index === target) return i;
      }
      return -1;
    });
    const prevSibling = computed(() => {
      const i = selIndexInPoints.value;
      return i > 0 ? state.points[i - 1] : null;
    });
    const nextSibling = computed(() => {
      const i = selIndexInPoints.value;
      return i >= 0 && i < state.points.length - 1 ? state.points[i + 1] : null;
    });
    const goPrev = () => {
      if (prevSibling.value) { selectPoint(prevSibling.value); ensureVisible(prevSibling.value); }
    };
    const goNext = () => {
      if (nextSibling.value) { selectPoint(nextSibling.value); ensureVisible(nextSibling.value); }
    };

    const onKeyNav = (e) => {
      if (!sel.value) return;
      const t = e.target;
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
      if (e.key === 'ArrowLeft') { e.preventDefault(); goPrev(); }
      else if (e.key === 'ArrowRight') { e.preventDefault(); goNext(); }
    };
    onMounted(() => { document.addEventListener('keydown', onKeyNav); });
    onUnmounted(() => { document.removeEventListener('keydown', onKeyNav); });

    const drawMiniChart = () => {
      const canvas = miniCanvas.value;
      if (!canvas) return;

      const ctx = canvas.getContext('2d');
      const rect = canvas.parentElement.getBoundingClientRect();
      const dpr = window.devicePixelRatio || 1;

      canvas.width = rect.width * dpr;
      canvas.height = 80 * dpr;
      canvas.style.width = rect.width + 'px';
      canvas.style.height = '80px';
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

      const w = rect.width;
      const h = 80;
      const pad = { l: 4, r: 4, t: 8, b: 16 };
      const plotW = w - pad.l - pad.r;
      const plotH = h - pad.t - pad.b;

      ctx.clearRect(0, 0, w, h);

      let windowPoints, highlightStart, highlightEnd;

      if (range.value) {
        // Range mode: show points in and around the range
        const rs = rangeStart.value;
        const re = rangeEnd.value;
        let si = 0, ei = state.points.length - 1;
        for (let i = 0; i < state.points.length; i++) {
          if (state.points[i].index === rs.index) { si = i; break; }
        }
        for (let i = state.points.length - 1; i >= 0; i--) {
          if (state.points[i].index === re.index) { ei = i; break; }
        }
        const margin = Math.max(5, Math.round((ei - si) * 0.15));
        const from = Math.max(0, si - margin);
        const to = Math.min(state.points.length - 1, ei + margin);
        windowPoints = state.points.slice(from, to + 1);
        highlightStart = rs;
        highlightEnd = re;
      } else if (sel.value) {
        // Single point mode
        const selIdx = sel.value.index;
        let startIdx = 0, endIdx = state.points.length - 1;
        for (let i = 0; i < state.points.length; i++) {
          if (state.points[i].index === selIdx) {
            startIdx = Math.max(0, i - MINI_CHART_WINDOW);
            endIdx = Math.min(state.points.length - 1, i + MINI_CHART_WINDOW);
            break;
          }
        }
        windowPoints = state.points.slice(startIdx, endIdx + 1);
        highlightStart = sel.value;
        highlightEnd = null;
      } else {
        return;
      }

      if (windowPoints.length < 2) return;

      const tMin = windowPoints[0].timestamp;
      const tMax = windowPoints[windowPoints.length - 1].timestamp;
      const tSpan = tMax - tMin || 1;

      let yMax = 0;
      for (const p of windowPoints) {
        if (p.usedMB > yMax) yMax = p.usedMB;
      }
      yMax = Math.max(yMax * 1.15, 16);

      const xAt = (ts) => pad.l + ((ts - tMin) / tSpan) * plotW;
      const yAt = (mb) => pad.t + (1 - mb / yMax) * plotH;

      // Draw grid lines
      ctx.strokeStyle = '#1c222d';
      ctx.lineWidth = 1;
      for (let i = 0; i <= 2; i++) {
        const y = pad.t + (plotH * i / 2);
        ctx.beginPath();
        ctx.moveTo(pad.l, y);
        ctx.lineTo(w - pad.r, y);
        ctx.stroke();
      }

      // Draw range shading if in range mode
      if (highlightStart && highlightEnd) {
        const x1 = xAt(highlightStart.timestamp);
        const x2 = xAt(highlightEnd.timestamp);
        ctx.fillStyle = 'rgba(240, 192, 64, 0.1)';
        ctx.fillRect(x1, pad.t, x2 - x1, plotH);
      }

      // Draw area fill
      ctx.beginPath();
      ctx.moveTo(xAt(windowPoints[0].timestamp), yAt(0));
      for (const p of windowPoints) {
        ctx.lineTo(xAt(p.timestamp), yAt(p.usedMB));
      }
      ctx.lineTo(xAt(windowPoints[windowPoints.length - 1].timestamp), yAt(0));
      ctx.closePath();
      const gradient = ctx.createLinearGradient(0, pad.t, 0, h - pad.b);
      gradient.addColorStop(0, 'rgba(78, 161, 255, 0.25)');
      gradient.addColorStop(1, 'rgba(78, 161, 255, 0.02)');
      ctx.fillStyle = gradient;
      ctx.fill();

      // Draw line
      ctx.strokeStyle = '#4ea1ff';
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      let started = false;
      for (const p of windowPoints) {
        const x = xAt(p.timestamp);
        const y = yAt(p.usedMB);
        if (!started) { ctx.moveTo(x, y); started = true; }
        else ctx.lineTo(x, y);
      }
      ctx.stroke();

      // Draw markers
      const drawMarker = (p, color) => {
        const mx = xAt(p.timestamp);
        const my = yAt(p.usedMB);
        ctx.strokeStyle = 'rgba(255, 255, 255, 0.3)';
        ctx.lineWidth = 1;
        ctx.setLineDash([2, 2]);
        ctx.beginPath();
        ctx.moveTo(mx, pad.t);
        ctx.lineTo(mx, h - pad.b);
        ctx.stroke();
        ctx.setLineDash([]);
        ctx.fillStyle = '#ffffff';
        ctx.beginPath();
        ctx.arc(mx, my, 4, 0, Math.PI * 2);
        ctx.fill();
        ctx.strokeStyle = color;
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.arc(mx, my, 6, 0, Math.PI * 2);
        ctx.stroke();
      };

      if (highlightEnd) {
        drawMarker(highlightStart, '#f0c040');
        drawMarker(highlightEnd, '#f0c040');
      } else {
        drawMarker(highlightStart, '#4ea1ff');
      }

      // Time labels
      ctx.fillStyle = '#6a7384';
      ctx.font = '10px Consolas, Menlo, monospace';
      ctx.fillText(fmtTs(tMin), pad.l, h - 3);
      const endLabel = fmtTs(tMax);
      const endLabelW = ctx.measureText(endLabel).width;
      ctx.fillText(endLabel, w - pad.r - endLabelW, h - 3);
    };

    let resizeObserver = null;

    onMounted(() => {
      if (miniCanvas.value && miniCanvas.value.parentElement) {
        resizeObserver = new ResizeObserver(() => {
          if (sel.value || range.value) drawMiniChart();
        });
        resizeObserver.observe(miniCanvas.value.parentElement);
      }
    });

    onUnmounted(() => {
      if (resizeObserver) resizeObserver.disconnect();
    });

    watchEffect(() => {
      if ((sel.value || range.value) && miniCanvas.value) {
        requestAnimationFrame(drawMiniChart);
      }
    });

    const rangeCopied = ref(false);
    const exportRangePng = () => {
      if (!rangeStart.value || !rangeEnd.value) return;
      downloadPngExport({ from: rangeStart.value.timestamp, to: rangeEnd.value.timestamp, includeTable: true,
        theme: state.theme === 'dark' ? 'dark' : 'light' });
    };
    const copyRange = async () => {
      const a = rangeStart.value, b = rangeEnd.value;
      if (!a || !b) return;
      const name = (p) => String(p.label || p.kind + ' #' + p.index).replace(/\|/g, '\\|');
      const d = b.usedMB - a.usedMB;
      const text = '| From | To | Δ MB | Duration | Min MB | Max MB |\n| --- | --- | ---: | ---: | ---: | ---: |\n'
        + '| ' + name(a) + ' | ' + name(b) + ' | ' + (d > 0 ? '+' : '') + d.toLocaleString('en-US') + ' | '
        + rangeStats.value.durationStr + ' | ' + rangeStats.value.minUsed + ' | ' + rangeStats.value.maxUsed + ' |\n';
      try { await navigator.clipboard.writeText(text); } catch (e) { /* clipboard blocked */ }
      rangeCopied.value = true;
      setTimeout(() => { rangeCopied.value = false; }, 1500);
    };

    return {
      rangeCopied, exportRangePng, copyRange,
      state, snippet, snapshotTip: SNAPSHOT_TIP,
      sel, cls, deltaClass, usedPct, committedPct, miniCanvas, deselect, openIde, viewSource, fmtTs,
      rangeStart, rangeEnd, range, rangePartial, hasSelection, panelTitle,
      startCls, endCls, rangeStats,
      canNavigate, prevSibling, nextSibling, goPrev, goNext,
    };
  }
};
