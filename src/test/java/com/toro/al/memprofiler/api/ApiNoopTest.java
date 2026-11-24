package com.toro.al.memprofiler.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Improvement B: without the profiler on the classpath every call is a silent no-op. */
class ApiNoopTest {

  @AfterEach
  void restore() {
    MemoryProfiler.implName = MemoryProfiler.IMPL;
    MemoryProfiler.reset();
  }

  @Test
  void everyCallIsANoOpWithoutTheProfiler() {
    MemoryProfiler.implName = "com.example.not.There";
    MemoryProfiler.reset();
    assertFalse(MemoryProfiler.isAvailable());
    assertFalse(MemoryProfiler.isRunning());
    MemoryProfiler.mark("a");
    MemoryProfiler.markGc("b");
    MemoryProfiler.referenceLine(1, "c");
    MemoryProfiler.title("d", "e");
    try (MemoryProfiler.Scope s = MemoryProfiler.scopeGc("f")) {
      // nothing
    }
    assertNull(MemoryProfiler.heapDump("g"));
  }
}
