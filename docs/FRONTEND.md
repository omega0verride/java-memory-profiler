# Frontend Architecture

The viewer is split into ES modules under `src/main/resources/web/`. Vue 3
(ESM browser build, loaded via CDN through an import map) provides reactivity
and templating. **No build step** — files are served verbatim from the
classpath by `ProfilerServer`.

## Layout

```
src/main/resources/
  viewer.html              # shell: import map, mount point, loads /web/app.js
  compare.html             # run comparison page template (export --compare --html), entry web/compare-app.js
  web/
    styles.css             # all CSS (one file for now, no scoped styles)
    state.js               # reactive store (points, selection, view, autogc, heapDumps, snapshot, annotations, theme)
    api.js                 # data source (live polling or embedded snapshot) + fetch wrappers + export helpers
    util.js                # tiny shared helpers (fmtTs, kindClass)
    annotations.js         # badge numbering, marks rows, scope bands, badge/title layout (mirrors Annotations.java)
    gaps.js                # heap line continuity lookup (solid / dashed / break); the rule itself is GapRule.java
    compare-app.js         # entry of the run comparison page (export --compare --html); plain DOM + canvas, no Vue
    chart.js               # canvas renderer + pointer/wheel handlers + annotation layers
    vendor/
      vue.esm-browser.prod.js  # Vue 3.4.38, inlined into HTML exports only (MIT, see LICENSE-vue.txt)
    components/
      Summary.js           # current-heap panel + heap dump controls
      Selected.js          # selected-point detail panel (+ source snippet in snapshots)
      Events.js            # event list panel
      Overlay.js           # in-chart floating controls (marks/follow/zoom/pause)
      ContextMenu.js       # right-click menu component
      HoverCard.js         # hover popover (+ mark label and snippet)
      HoverHeader.js       # detail bar above the chart
      MarksTable.js        # marks table under the chart (annotations on): Δ first, GC ms, hide repeats, copy as Markdown
      ChartKey.js          # one-line key under the chart: sampling interval, dashed/gap meaning, GC totals
    app.js                 # root component, wires modules + polling
```

`ProfilerServer` serves anything under `/web/...` as a static resource. Drop a
new file in and it's reachable. The import map in `viewer.html` resolves the
bare specifier `vue` so any module can `import { ref } from 'vue'`.

## Snapshot mode (exported HTML)

`HtmlExporter` inlines `styles.css`, turns every module reachable from
`app.js` into a `data:` URL in an import map (relative imports are rewritten
to `@memprofiler/web/...`), and embeds the profile as JSON. In the page,
`window.__PROFILE__` is set before the modules run; `api.js` then exports
`SNAPSHOT = true` and a `dataSource` that reads the embedded data and never
polls. Live-only calls in `api.js` resolve to `null` in a snapshot.

Rules for code that must also work in a snapshot:

- Get data through `dataSource`, never `fetch('/...')` directly.
- Gate live-only controls on `state.snapshot` (hide them, or disable them
  with `SNAPSHOT_TIP` from `util.js` as the tooltip).
- Import other modules with relative paths (`'./x.js'`, `'../x.js'`); the
  exporter only rewrites those, and a new module is picked up automatically.
- Colours come from CSS variables (`:root` and `:root[data-theme="light"]`),
  so the canvas follows the theme. Keep `annotations.js` and
  `Annotations.java` in step: the PNG export uses the same rules.

## Computed on the server, drawn in the browser

Rules that the PNG renderer also applies are computed once in Java and sent as
data, so the live chart, the HTML export and the PNG cannot disagree:

- `gaps` (`GapRule.java`): where the heap line is dashed or broken; `gaps.js`
  only looks segments up.
- `gcPressure` (`GcPressure.java`): GC-overhead regions to shade, totals.
- `segments` (`Segments.java`): segment brackets and the largest increase /
  release callouts.

`/data` and `/data/since` (live) and the embedded profile (snapshot) carry the
same sections. REFERENCE points never belong to `state.points`
(`splitReferences` moves any stray one to `state.references`).

## Adding a feature

A feature usually touches **three layers**, in this order:

1. **Backend handler** — add a route in `ProfilerServer`, wire to whatever
   subsystem owns the data (e.g. `AutoGcController`).
2. **API wrapper** — add a function to `web/api.js`. One function per endpoint,
   thin: parse JSON, return the value or throw.
3. **State + UI** — add reactive fields to `web/state.js`, then either extend
   an existing component or create a new one.

Match the existing JSON shape conventions (camelCase keys, MB units as
integers, timestamps as epoch millis). Don't rename client-side.

## Adding a component

Components are plain ES modules that export a Vue 3 component object.
**One component per file.**

1. Create `web/components/<Name>.js`, default-export the component
   (`{ template, setup }` or `{ template, computed, methods }`).
2. Import from `app.js` and register under a multi-word kebab-case tag
   (e.g. `'app-foo': Foo`) — single-word tags collide with native HTML.
3. Mount with `<app-foo>` in `viewer.html` or another component's template.
4. Communicate **down** via props, **up** via emits — never reach into
   another component's internals.

Shared state belongs in `state.js`, not in components. Components import
state directly; don't prop-drill.

### Template style

Keep templates inline as backtick strings. Don't introduce `.vue` SFCs —
those need a build step.

```js
import { computed } from 'vue';
import { state } from '../state.js';
import { mark } from '../api.js';

export default {
  template: `
    <button @click="onMark">{{ label }}</button>
  `,
  setup() {
    const label = computed(() => 'mark (' + state.points.length + ')');
    const onMark = () => mark('manual');
    return { label, onMark };
  }
};
```

## State

`state.js` owns one `reactive(...)` object. Add new fields there, alongside
existing ones. Mutations happen anywhere; reactivity propagates automatically.

Do **not** create per-component reactive stores — single source of truth.

Persisted UI state (e.g. `gcThresholdMB`) is loaded from the server on init
and pushed back on change. Follow that pattern when adding new persisted
settings: server is the source of truth, client mirrors it.

## Chart (canvas)

The chart is imperative — Vue does not render the canvas. `chart.js` exports
`mountChart(canvas, chartWrap)` which installs pointer/wheel handlers and
sets up a `watchEffect(draw)` so the canvas redraws whenever any reactive
state read by `draw` changes.

To add a new visual element:
1. Add a draw step inside `draw()` in the right layer order:
   grid → threshold line → data line → cursors → axis labels.
2. If interactive, add a hit-test helper and wire it into the existing
   pointer handlers. Don't attach new DOM listeners ad-hoc.
3. Trigger redraws by mutating state. The chart's `watchEffect` handles
   the rest — don't call `draw()` from outside `chart.js`.

## Context menu

The menu is rendered by `components/ContextMenu.js`. Items are built in
`app.js`'s context-menu handler (`setContextMenuHandler` callback) and
written to `state.menu.items`.

To add a right-click action:
1. Append to the `items` array in that handler.
2. Item shape: `{ label, hint?, disabled?, action }` (or `{ sep: true }`
   for a separator). Delegate the work to API wrappers or state mutations
   — keep `action` short.
3. If the action needs input, use `window.prompt` or an inline input.
   Don't add a top-bar control just to feed the menu.

## Heap dump dropdown

The heap dump UI in `Summary.js` uses a `position: fixed` dropdown to
escape the parent panel's `overflow` clipping. The pattern:

1. A toggle button stores a template `ref` (`dumpToggleEl`).
2. On click, `getBoundingClientRect()` calculates the dropdown position
   and writes it to a reactive `dropdownStyle` object.
3. The dropdown binds `:style="dropdownStyle"` with `position: fixed`.
4. A `mousedown` listener on `document` closes the dropdown when clicking
   outside.

Use this same pattern for any popup that needs to escape a scrollable /
clipped parent. Don't use `position: absolute` inside overflow containers.

## Conventions

- **No build step.** If you think you need one, raise it before adding it.
- **No new globals.** Everything goes through `import`. The only external
  dependency is Vue, declared once in the import map in `viewer.html`.
- **One concern per module.** If a file grows past ~300 lines, split it
  before adding more.
- **Server is the source of truth** for persisted settings. The UI mirrors
  server state, not the other way around.
- **Match server JSON shape.** Don't transform fields client-side to rename
  them — fix the server response if it's wrong.
- **CSS is currently global** (`web/styles.css`). When adding component
  styles, namespace classes with the component's tag name to keep them
  searchable.
