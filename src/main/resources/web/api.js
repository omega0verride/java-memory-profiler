export async function fetchInitial() {
  const r = await fetch('/data');
  return r.json();
}

export async function fetchSince(index) {
  const r = await fetch('/data/since?index=' + index);
  return r.json();
}

export async function mark(label, gc = true) {
  const params = new URLSearchParams();
  params.set('gc', gc ? 'true' : 'false');
  params.set('label', label);
  await fetch('/mark?' + params.toString());
}

export async function forceGc() {
  await fetch('/gc');
}

export async function getAutoGc() {
  const r = await fetch('/autogc');
  return r.json();
}

export async function setAutoGc({ nearPeak, threshold, thresholdMB }) {
  const params = new URLSearchParams();
  params.set('nearPeak', nearPeak ? 'true' : 'false');
  params.set('threshold', threshold ? 'true' : 'false');
  if (thresholdMB > 0) params.set('thresholdMB', thresholdMB);
  await fetch('/autogc?' + params.toString());
}

export function openLocation(loc) {
  const path = String(loc || '');
  if (!path) return;
  fetch('/open?path=' + encodeURIComponent(path))
    .then(r => { if (!r.ok) r.text().then(t => console.error('open failed:', r.status, t)); })
    .catch(e => console.error('open error:', e));
}

export function openJsonlFile(path) {
  if (!path) return;
  fetch('/open?path=' + encodeURIComponent(path)).catch(() => {});
}

export function downloadJsonl() {
  window.location.href = '/data/file';
}

export async function takeHeapDump(name) {
  const params = new URLSearchParams();
  if (name) params.set('name', name);
  const r = await fetch('/heapdump?' + params.toString());
  return r.json();
}

export async function getHeapDumps() {
  const r = await fetch('/heapdumps');
  return r.json();
}

export async function openHeapDump(path) {
  await fetch('/heapdump/open?path=' + encodeURIComponent(path));
}

export async function listClasses(filter) {
  const params = filter ? '?filter=' + encodeURIComponent(filter) : '';
  const r = await fetch('/layout/classes' + params);
  return r.json();
}

export async function getClassSource(className) {
  const r = await fetch('/class/source?class=' + encodeURIComponent(className));
  return r.json();
}

export async function getObjectLayout(className) {
  const r = await fetch('/layout?class=' + encodeURIComponent(className));
  return r.json();
}

export async function compileAndAnalyze(source) {
  const r = await fetch('/layout/compile', {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain; charset=utf-8' },
    body: source,
  });
  return r.json();
}
