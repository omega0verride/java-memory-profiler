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
