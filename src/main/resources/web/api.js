// ---- data source -----------------------------------------------------------
//
// Live mode: the viewer polls the profiler's HTTP server.
// Snapshot mode (an exported HTML file): the profile is embedded in the page as
// window.__PROFILE__ = { meta, points, references, sources }. Nothing is
// fetched and nothing polls.

import { splitReferences } from './gaps.js';

const embedded = (typeof window !== 'undefined' && window.__PROFILE__) || null;

/** True when running inside an exported HTML snapshot. */
export const SNAPSHOT = !!embedded;

function liveSource() {
  return {
    live: true,
    async load() {
      const r = await fetch('/data');
      return r.json();
    },
    async since(index) {
      const r = await fetch('/data/since?index=' + index);
      return r.json();
    },
  };
}

function snapshotSource(profile) {
  // REFERENCE points never belong to the points (their usedMB is a line value)
  const split = splitReferences(Array.isArray(profile.points) ? profile.points : []);
  const points = split.points;
  const meta = profile.meta || {};
  let peak = 0;
  for (const p of points) if (p.usedMB > peak) peak = p.usedMB;
  return {
    live: false,
    async load() {
      return {
        points,
        references: (Array.isArray(profile.references) ? profile.references : []).concat(split.references),
        gaps: profile.gaps || null,
        gcPressure: profile.gcPressure || null,
        segments: profile.segments || [],
        samplingText: meta.samplingText || '',
        sources: profile.sources || {},
        meta,
        maxObservedMB: peak || meta.peakMB || 0,
        jsonlPath: null,
        pid: typeof meta.pid === 'number' ? meta.pid : null,
        processName: meta.processName || null,
        intervalMs: meta.intervalMs || 0,
        version: meta.profilerVersion || null,
      };
    },
    async since() {
      return { points: [] };
    },
  };
}

export const dataSource = SNAPSHOT ? snapshotSource(embedded) : liveSource();

/** Kept for compatibility; prefer dataSource.load(). */
export function fetchInitial() {
  return dataSource.load();
}

/** Kept for compatibility; prefer dataSource.since(index). */
export function fetchSince(index) {
  return dataSource.since(index);
}

// Live-only calls resolve to null in a snapshot, so a stray click can never
// reach the network.
function liveOnly(fn) {
  return SNAPSHOT ? () => Promise.resolve(null) : fn;
}

async function mark_(label, gc = true) {
  const params = new URLSearchParams();
  params.set('gc', gc ? 'true' : 'false');
  params.set('label', label);
  await fetch('/mark?' + params.toString());
}

async function forceGc_() {
  await fetch('/gc');
}

async function getAutoGc_() {
  const r = await fetch('/autogc');
  return r.json();
}

async function setAutoGc_({ nearPeak, threshold, thresholdMB }) {
  const params = new URLSearchParams();
  params.set('nearPeak', nearPeak ? 'true' : 'false');
  params.set('threshold', threshold ? 'true' : 'false');
  if (thresholdMB > 0) params.set('thresholdMB', thresholdMB);
  await fetch('/autogc?' + params.toString());
}

// Opens a mark's source location ("com/acme/Foo.java:42") in the IDE, at the line.
// Resolves to { ok, text }: what the server did, or why it could not.
async function openLocation_(loc) {
  const path = String(loc || '');
  if (!path) return { ok: false, text: 'no location recorded' };
  try {
    const r = await fetch('/open?path=' + encodeURIComponent(path));
    const text = await r.text();
    return { ok: r.ok, text };
  } catch (e) {
    return { ok: false, text: 'request failed: ' + (e && e.message ? e.message : e) };
  }
}

function openJsonlFile_(path) {
  if (!path) return;
  fetch('/open?path=' + encodeURIComponent(path)).catch(() => {});
}

function downloadJsonl_() {
  window.location.href = '/data/file';
}

async function takeHeapDump_(name) {
  const params = new URLSearchParams();
  if (name) params.set('name', name);
  const r = await fetch('/heapdump?' + params.toString());
  return r.json();
}

async function getHeapDumps_() {
  const r = await fetch('/heapdumps');
  return r.json();
}

async function openHeapDump_(path) {
  await fetch('/heapdump/open?path=' + encodeURIComponent(path));
}

async function listClasses_(filter) {
  const params = filter ? '?filter=' + encodeURIComponent(filter) : '';
  const r = await fetch('/layout/classes' + params);
  return r.json();
}

// file ("com/acme/Foo.java") and line are the mark's; the server answers with
// the line of the returned text to highlight (mapped when the code is decompiled).
async function getClassSource_(className, file, line) {
  const params = new URLSearchParams();
  params.set('class', className);
  if (file) params.set('file', file);
  if (line) params.set('line', line);
  const r = await fetch('/class/source?' + params.toString());
  return r.json();
}

async function getObjectLayout_(className) {
  const r = await fetch('/layout?class=' + encodeURIComponent(className));
  return r.json();
}

async function compileAndAnalyze_(source) {
  const r = await fetch('/layout/compile', {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain; charset=utf-8' },
    body: source,
  });
  return r.json();
}

export const mark = liveOnly(mark_);
export const forceGc = liveOnly(forceGc_);
export const getAutoGc = liveOnly(getAutoGc_);
export const setAutoGc = liveOnly(setAutoGc_);
export const takeHeapDump = liveOnly(takeHeapDump_);
export const getHeapDumps = liveOnly(getHeapDumps_);
export const openHeapDump = liveOnly(openHeapDump_);
export const listClasses = liveOnly(listClasses_);
export const getClassSource = liveOnly(getClassSource_);
export const getObjectLayout = liveOnly(getObjectLayout_);
export const compileAndAnalyze = liveOnly(compileAndAnalyze_);
export const openLocation = liveOnly(openLocation_);
export const openJsonlFile = liveOnly(openJsonlFile_);
export const downloadJsonl = liveOnly(downloadJsonl_);

// ---- exports (live mode) ----------------------------------------------------

export function downloadHtmlExport() {
  if (SNAPSHOT) return;
  window.location.href = '/export/html';
}

/** URL of /export/png for the given options ({ from, to, theme, includeTable, ... }). */
export function pngUrl(opts) {
  const params = new URLSearchParams();
  for (const [k, v] of Object.entries(opts || {})) {
    if (v == null || v === '' || v === false) continue;
    params.set(k, v === true ? 'true' : String(v));
  }
  return '/export/png?' + params.toString();
}

export function downloadPngExport(opts) {
  if (SNAPSHOT) return;
  window.location.href = pngUrl(Object.assign({}, opts, { download: true }));
}

/** Copies the PNG to the clipboard. Resolves when done; rejects with a readable message. */
export async function copyPngExport(opts) {
  if (SNAPSHOT) return;
  if (!navigator.clipboard || typeof window.ClipboardItem === 'undefined') {
    throw new Error('this browser cannot copy images (no ClipboardItem)');
  }
  const url = pngUrl(opts);
  const blob = fetch(url).then(async (r) => {
    if (!r.ok) throw new Error((await r.text()) || ('HTTP ' + r.status));
    return r.blob();
  });
  try {
    // Promise form keeps the user gesture alive in Safari and Chrome.
    await navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]);
  } catch (e) {
    const b = await blob;
    await navigator.clipboard.write([new ClipboardItem({ 'image/png': b })]);
  }
}
