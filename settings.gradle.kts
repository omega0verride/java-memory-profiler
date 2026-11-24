rootProject.name = "java-memory-profiler"

// Tiny compile-time API (no dependencies): instrument a library without a hard
// dependency on the profiler. See README "Instrument a library without a hard dependency".
include("memprofiler-api")
