import { computed, ref, watch, nextTick, onMounted, onUnmounted } from 'vue';
import { state, ensureVisible, selectPoint } from '../state.js';
import { fmtTs, kindClass } from '../util.js';

const KIND_OPTIONS = ['ALL', 'MARK', 'GC', 'AUTO_GC', 'SCOPE_START', 'SCOPE_END', 'HEAP_DUMP'];
const SORT_OPTIONS = [
  { value: 'time-desc', label: 'Newest first' },
  { value: 'time-asc', label: 'Oldest first' },
  { value: 'delta-desc', label: 'Delta (high to low)' },
  { value: 'delta-asc', label: 'Delta (low to high)' },
  { value: 'used-desc', label: 'Used MB (high to low)' },
  { value: 'used-asc', label: 'Used MB (low to high)' },
];

export default {
  template: `
    <div class="panel events">
      <div class="panel-title events-title-row">
        <span>Events</span>
        <span class="events-count">{{ countLabel }}</span>
      </div>
      
      <div class="events-controls">
        <div class="events-filters">
          <button 
            v-for="kind in kindOptions" 
            :key="kind"
            class="filter-btn"
            :class="{ active: filterKind === kind, [kindClass(kind)]: kind !== 'ALL' }"
            @click="filterKind = kind"
          >{{ kind === 'ALL' ? 'All' : kindLabel(kind) }}</button>
          <button 
            v-if="hasActiveFilters"
            class="filter-clear-btn"
            @click="clearFilters"
            title="Clear all filters"
          >× clear</button>
        </div>
        <div class="events-search-sort">
          <input 
            type="text" 
            class="events-search" 
            placeholder="Search..."
            v-model="searchText"
          />
          <select class="events-sort" v-model="sortBy">
            <option v-for="opt in sortOptions" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </option>
          </select>
        </div>
      </div>
      
      <div class="events-list" ref="eventsListEl" @scroll="onScroll">
        <button v-if="showScrollTop" class="events-scroll-top-btn" @click="scrollToTop" title="Scroll to top">↑ Top</button>
        <div v-if="!filteredEvents.length" class="events-empty">
          <template v-if="events.length">No events match the current filters.</template>
          <template v-else>No events yet. Use the mark button or profiler.scope() to add one.</template>
        </div>
        <div v-for="p in displayedEvents"
             :key="p.index"
             class="event"
             :class="[kindClass(p.kind), { selected: state.selected && state.selected.index === p.index }]"
             @click="onClick(p)">
          <div>
            <span class="pill" :class="kindClass(p.kind)">{{ p.kind }}</span>
            <span class="label">{{ p.label || '' }}</span>
            <span v-if="p.deltaMB != null" class="pill" :class="kindClass(p.kind)">
              {{ (p.deltaMB >= 0 ? '+' : '') + p.deltaMB }}MB
            </span>
            <span v-if="p.durationMs != null"> {{ p.durationMs }}ms</span>
          </div>
          <div class="loc">{{ p.location || '' }} · {{ p.usedMB }}MB · {{ fmtTs(p.timestamp) }}</div>
        </div>
        <div v-if="hasMore" class="events-more">
          <button class="show-more-btn" @click="showMore">
            Show more ({{ remainingCount }} remaining)
          </button>
          <button class="show-more-btn show-all-btn" @click="showAll">Show all</button>
        </div>
      </div>
    </div>
  `,
  setup() {
    const filterKind = ref('ALL');
    const searchText = ref('');
    const sortBy = ref('time-desc');
    
    const events = computed(() => state.points.filter(p => p.kind !== 'SAMPLE' && p.kind !== 'REFERENCE'));
    
    const filteredEvents = computed(() => {
      let result = events.value;
      
      // Filter by kind
      if (filterKind.value !== 'ALL') {
        result = result.filter(p => p.kind === filterKind.value);
      }
      
      // Filter by search text
      const search = searchText.value.trim().toLowerCase();
      if (search) {
        result = result.filter(p => {
          const label = (p.label || '').toLowerCase();
          const location = (p.location || '').toLowerCase();
          const thread = (p.thread || '').toLowerCase();
          return label.includes(search) || location.includes(search) || thread.includes(search);
        });
      }
      
      return result;
    });
    
    const sortedEvents = computed(() => {
      const arr = [...filteredEvents.value];
      const [field, dir] = sortBy.value.split('-');
      const asc = dir === 'asc';
      
      arr.sort((a, b) => {
        let cmp = 0;
        if (field === 'time') {
          cmp = a.timestamp - b.timestamp;
        } else if (field === 'delta') {
          const aDelta = a.deltaMB ?? 0;
          const bDelta = b.deltaMB ?? 0;
          cmp = aDelta - bDelta;
        } else if (field === 'used') {
          cmp = a.usedMB - b.usedMB;
        }
        return asc ? cmp : -cmp;
      });
      
      return arr;
    });
    
    const PAGE_SIZE = 200;
    const displayLimit = ref(PAGE_SIZE);
    watch([filterKind, searchText, sortBy], () => { displayLimit.value = PAGE_SIZE; });

    const displayedEvents = computed(() => sortedEvents.value.slice(0, displayLimit.value));
    const hasMore = computed(() => sortedEvents.value.length > displayLimit.value);
    const remainingCount = computed(() => Math.max(0, sortedEvents.value.length - displayLimit.value));
    const showMore = () => { displayLimit.value += PAGE_SIZE; };
    const showAll = () => { displayLimit.value = sortedEvents.value.length; };

    const countLabel = computed(() => {
      const total = events.value.length;
      const filtered = filteredEvents.value.length;
      if (!total) return '';
      const limit = displayLimit.value;
      if (filtered === total) {
        return total + (total > limit ? ' (showing ' + limit + ')' : '');
      }
      return filtered + ' / ' + total + (filtered > limit ? ' (showing ' + limit + ')' : '');
    });

    const hasActiveFilters = computed(() => filterKind.value !== 'ALL' || searchText.value !== '' || sortBy.value !== 'time-desc');
    const clearFilters = () => { filterKind.value = 'ALL'; searchText.value = ''; sortBy.value = 'time-desc'; };
    
    const kindLabel = (kind) => {
      if (kind === 'SCOPE_START') return 'Start';
      if (kind === 'SCOPE_END') return 'End';
      if (kind === 'AUTO_GC') return 'Auto GC';
      if (kind === 'HEAP_DUMP') return 'Dump';
      return kind.charAt(0) + kind.slice(1).toLowerCase();
    };
    
    const onClick = (p) => {
      selectPoint(p);
      ensureVisible(p);
    };

    const eventsListEl = ref(null);
    const showScrollTop = ref(false);

    const onScroll = () => {
      const el = eventsListEl.value;
      if (el) showScrollTop.value = el.scrollTop > 100;
    };

    const scrollToTop = () => {
      const el = eventsListEl.value;
      if (el) el.scrollTo({ top: 0, behavior: 'smooth' });
    };

    // When a point is selected (e.g. from chart click), scroll the events list to it
    watch(() => state.selected, (sel) => {
      if (!sel) return;
      nextTick(() => {
        const el = eventsListEl.value;
        if (!el) return;
        const target = el.querySelector('.event.selected');
        if (target) {
          target.scrollIntoView({ block: 'center', behavior: 'smooth' });
        }
      });
    });
    
    return {
      state, events, filteredEvents, displayedEvents, countLabel,
      kindClass, fmtTs, onClick,
      filterKind, searchText, sortBy,
      kindOptions: KIND_OPTIONS,
      sortOptions: SORT_OPTIONS,
      kindLabel, hasActiveFilters, clearFilters,
      hasMore, remainingCount, showMore, showAll,
      eventsListEl, showScrollTop, onScroll, scrollToTop,
    };
  }
};
