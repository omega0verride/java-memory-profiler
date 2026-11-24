export function fmtTs(ts) {
  return new Date(ts).toISOString().substring(11, 23);
}

export function kindClass(kind) {
  return kind === 'MARK' ? 'mark'
       : kind === 'SCOPE_START' ? 'start'
       : kind === 'GC' ? 'gc'
       : kind === 'SCOPE_END' ? 'end'
       : kind === 'HEAP_DUMP' ? 'heap_dump'
       : kind === 'AUTO_GC' ? 'auto_gc'
       : 'neutral';
}

/** Tooltip for live-only controls inside an exported snapshot. */
export const SNAPSHOT_TIP = 'Not available in a snapshot: this page was exported from the profiler and has no live JVM behind it';
