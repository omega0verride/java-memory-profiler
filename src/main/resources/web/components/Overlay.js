import { state, freezeView, resetView, getTimeRange, VIEW } from '../state.js';
import { zoomIn, zoomOut, resetYScale } from '../chart.js';

// Map a value from [0, 1] to an exponential range [min, max]
function logLerp(t, min, max) {
  return min * Math.pow(max / min, t);
}
// Inverse: map a value in [min, max] to [0, 1]
function logInvLerp(v, min, max) {
  return Math.log(v / min) / Math.log(max / min);
}

// Y-axis bounds (MB)
const Y_MIN = 32;
const Y_MAX = 16384; // 16 GB

export default {
  template: `
    <div class="ctrl-top-bar">
      <div class="seg-bar">
        <button class="seg" :class="{ active: state.annotations }" @click="toggleAnnotations"
                title="Mark badges, scope bands, reference lines and the marks table">
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6">
            <rect x="2" y="2" width="9" height="7" rx="3.5"/><path d="M6.5 9v3"/><circle cx="6.5" cy="13.5" r="1.3" fill="currentColor"/>
          </svg>
          <span>marks</span>
        </button>
        <button v-if="!state.snapshot" class="seg" :class="{ active: autoscroll }" @click="toggleFollow" title="Follow latest data">
          <svg width="10" height="10" viewBox="0 0 10 10"><circle cx="5" cy="5" r="5" fill="currentColor"/></svg>
          <span>live</span>
        </button>
        <button v-if="!state.snapshot" class="seg" :class="{ active: state.paused }" @click="togglePause" title="Pause data collection">
          <svg v-if="!state.paused" width="12" height="12" viewBox="0 0 16 16" fill="currentColor">
            <rect x="3" y="2" width="3.5" height="12" rx="1"/><rect x="9.5" y="2" width="3.5" height="12" rx="1"/>
          </svg>
          <svg v-else width="12" height="12" viewBox="0 0 16 16" fill="currentColor">
            <path d="M4 2l10 6-10 6z"/>
          </svg>
          <span>{{ pauseLabel }}</span>
        </button>
        <button class="seg" @click="reset" :title="state.snapshot ? 'Reset zoom to the whole run' : 'Reset view and resume following'">
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M2 8a6 6 0 0111.47-2.47"/><path d="M14 8a6 6 0 01-11.47 2.47"/><path d="M13.5 2v3.5H10"/><path d="M2.5 14v-3.5H6"/>
          </svg>
          <span>{{ state.snapshot ? 'reset zoom' : 'reset' }}</span>
        </button>
      </div>
    </div>

    <div class="ctrl-y-slider" @wheel.prevent="onYWheel">
      <button class="ctrl-btn ctrl-btn-sm" @click="zoomYIn" title="Zoom in (memory)">+</button>
      <input type="range" class="ctrl-slider ctrl-slider-v" min="0" max="200" :value="ySliderVal"
             orient="vertical"
             @input="onYSlider"
             title="Vertical zoom (memory axis)" />
      <button class="ctrl-btn ctrl-btn-sm" @click="zoomYOut" title="Zoom out (memory)">−</button>
      <button v-if="!yScaleAuto" class="ctrl-btn ctrl-btn-sm ctrl-auto" @click="resetY" title="Reset to auto scale">A</button>
    </div>

    <div class="ctrl-x-slider" @wheel.prevent="onXWheel">
      <button class="ctrl-btn ctrl-btn-sm" @click="zoomOut" title="Zoom out (time)">−</button>
      <input type="range" class="ctrl-slider ctrl-slider-h" min="0" max="200" :value="xSliderVal"
             @input="onXSlider"
             title="Horizontal zoom (time axis)" />
      <button class="ctrl-btn ctrl-btn-sm" @click="zoomIn" title="Zoom in (time)">+</button>
    </div>
  `,
  data() { return { state }; },
  computed: {
    autoscroll() { return state.autoscroll; },
    yScaleAuto() { return state.yScaleAuto; },
    pauseLabel() { return state.paused ? 'resume' : 'pause'; },

    // Slider position derived from current zoom state
    xSliderVal() {
      const range = getTimeRange();
      if (!range) return 100;
      const span = range.tMax - range.tMin;
      // Inverted: small span = zoomed in = slider right (200)
      const t = 1 - logInvLerp(
        Math.max(VIEW.MIN_SPAN_MS, Math.min(VIEW.MAX_SPAN_MS, span)),
        VIEW.MIN_SPAN_MS, VIEW.MAX_SPAN_MS
      );
      return Math.round(t * 200);
    },
    ySliderVal() {
      const yMax = state.yScaleMax || Math.max(state.maxObservedMB, 16) * 1.1;
      // Inverted: small yMax = zoomed in = slider up (200)
      const t = 1 - logInvLerp(Math.max(Y_MIN, Math.min(Y_MAX, yMax)), Y_MIN, Y_MAX);
      return Math.round(t * 200);
    }
  },
  methods: {
    toggleFollow() {
      state.autoscroll = !state.autoscroll;
      if (state.autoscroll) {
        state.customTMin = null;
        state.customTMax = null;
      } else {
        freezeView();
      }
    },
    zoomIn, zoomOut,

    onXSlider(e) {
      const t = 1 - Number(e.target.value) / 200; // invert: right = zoomed in = small span
      const newSpan = logLerp(t, VIEW.MIN_SPAN_MS, VIEW.MAX_SPAN_MS);
      if (state.autoscroll) {
        state.viewWindowMs = newSpan;
      } else {
        const range = getTimeRange();
        if (!range) return;
        const mid = (range.tMin + range.tMax) / 2;
        state.customTMin = mid - newSpan / 2;
        state.customTMax = mid + newSpan / 2;
      }
    },

    onYSlider(e) {
      const t = 1 - Number(e.target.value) / 200; // invert: up = zoomed in = small yMax
      const newYMax = Math.round(logLerp(t, Y_MIN, Y_MAX));
      state.yScaleAuto = false;
      state.yScaleMax = newYMax;
    },

    onXWheel(e) {
      const step = e.deltaY > 0 ? -5 : 5; // scroll down = zoom out (slider left)
      const newVal = Math.max(0, Math.min(200, this.xSliderVal + step));
      this.onXSlider({ target: { value: newVal } });
    },

    onYWheel(e) {
      const step = e.deltaY > 0 ? -5 : 5; // scroll down = zoom out (slider down)
      const newVal = Math.max(0, Math.min(200, this.ySliderVal + step));
      this.onYSlider({ target: { value: newVal } });
    },

    zoomYIn() {
      state.yScaleAuto = false;
      const currentMax = state.yScaleMax || Math.max(state.maxObservedMB, 16) * 1.1;
      state.yScaleMax = Math.max(Y_MIN, Math.round(currentMax * 0.5));
    },
    zoomYOut() {
      state.yScaleAuto = false;
      const currentMax = state.yScaleMax || Math.max(state.maxObservedMB, 16) * 1.1;
      state.yScaleMax = Math.min(Y_MAX, Math.round(currentMax * 2));
    },
    resetY() { resetYScale(); },
    reset() { 
      resetView(); 
      resetYScale();
    },
    togglePause() { state.paused = !state.paused; },
    toggleAnnotations() { state.annotations = !state.annotations; }
  }
};
