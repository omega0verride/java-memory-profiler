package com.toro.al.memprofiler;

import java.util.*;
import java.util.concurrent.*;

/**
 * Comprehensive demo exercising all profiler features:
 * - Configuration (interval, maxPoints, port, jsonl, gcListener, autoGc, sourceRoots, heapDump settings)
 * - mark / markGc
 * - scope / scopeGc
 * - @Profile annotation + wrap proxy
 * - Heap dumps
 * - Multi-threaded allocation patterns
 * - Memory leak simulation
 * - Bursty allocation / release cycles
 */
public class Demo {

  // ─── Annotated interface for @Profile + wrap ───────────────────────────────

  public interface DataPipeline {
    @Profile("ingest-batch")
    List<byte[]> ingest(int records, int recordSizeKB);

    @Profile("transform")
    List<byte[]> transform(List<byte[]> data);

    @Profile(value = "persist", gc = true)
    void persist(List<byte[]> data);

    @Profile(value = "cleanup", gc = true)
    void cleanup();
  }

  public static class DataPipelineImpl implements DataPipeline {
    private final List<byte[]> retained = new ArrayList<>();

    @Override
    public List<byte[]> ingest(int records, int recordSizeKB) {
      List<byte[]> batch = new ArrayList<>();
      for (int i = 0; i < records; i++) {
        byte[] record = new byte[recordSizeKB * 1024];
        Arrays.fill(record, (byte) (i & 0xFF));
        batch.add(record);
      }
      return batch;
    }

    @Override
    public List<byte[]> transform(List<byte[]> data) {
      // Simulate transformation: double each record (temporary spike)
      List<byte[]> transformed = new ArrayList<>();
      for (byte[] record : data) {
        byte[] bigger = new byte[record.length * 2];
        System.arraycopy(record, 0, bigger, 0, record.length);
        transformed.add(bigger);
      }
      return transformed;
    }

    @Override
    public void persist(List<byte[]> data) {
      // Simulate a leak: retain some records
      for (int i = 0; i < data.size() && i < 5; i++) {
        retained.add(data.get(i));
      }
      data.clear();
    }

    @Override
    public void cleanup() {
      retained.clear();
    }
  }

  // ─── Simulated cache with growing memory footprint ─────────────────────────

  static class LeakyCache {
    private final Map<String, byte[]> cache = new LinkedHashMap<>();

    void put(String key, int sizeKB) {
      cache.put(key, new byte[sizeKB * 1024]);
    }

    void evict(int count) {
      Iterator<String> it = cache.keySet().iterator();
      for (int i = 0; i < count && it.hasNext(); i++) {
        it.next();
        it.remove();
      }
    }

    int size() { return cache.size(); }
  }

  // ─── Main ──────────────────────────────────────────────────────────────────

  public static void main(String[] args) throws Exception {

    // Full configuration demonstrating all options
    MemoryProfiler.start(MemoryProfiler.Config.defaults()
        .intervalMs(200)                           // fast sampling
        .maxPoints(50_000)                         // large ring buffer
        .port(9090)                                // fixed port
        .jsonl("C:/temp/memprofiler-demo.jsonl")   // persistent log
        .openBrowser(true)                         // launch browser
        .startServer(true)                         // web UI
        .gcListener(true)                          // capture GC events
        .autoGcNearPeak(true)                      // auto-GC near peak
        .autoGcThreshold(true, 100)                // auto-GC above 400 MB
        .sourceRoots("src/main/java")              // for "Open in IDE"
        .heapDumpDir("C:/temp/dumps")              // heap dump output
        .heapDumpOpener("system")                  // open with OS default
        .forceGc(3, 80)                            // gc loop for markGc/scopeGc
    );

    System.out.println("=== Java Memory Profiler Demo ===");
    System.out.println("Web UI: http://localhost:9090/");
    System.out.println();

    // Wrap the pipeline interface for annotation-driven profiling
    DataPipeline pipeline = MemoryProfiler.wrap(DataPipeline.class, new DataPipelineImpl());
    LeakyCache cache = new LeakyCache();

    // ── Phase 1: Baseline ────────────────────────────────────────────────────
    MemoryProfiler.markGc("baseline");
    Thread.sleep(1000);

    // ── Phase 2: Pipeline processing with @Profile scopes ────────────────────
    try (MemoryProfiler.Scope phase = MemoryProfiler.scopeGc("phase-2-pipeline")) {
      for (int batch = 0; batch < 4; batch++) {
        List<byte[]> raw = pipeline.ingest(50, 64);       // 50 records × 64 KB = ~3.2 MB
        List<byte[]> transformed = pipeline.transform(raw); // doubles → ~6.4 MB spike
        pipeline.persist(transformed);                     // retains 5 records (leak)
        MemoryProfiler.mark("batch-" + batch + "-done");
        Thread.sleep(500);
      }
    }

    MemoryProfiler.markGc("after-pipeline");
    Thread.sleep(500);

    // ── Phase 3: Cache growth (simulated memory leak) ────────────────────────
    try (MemoryProfiler.Scope phase = MemoryProfiler.scope("phase-3-cache-growth")) {
      for (int i = 0; i < 200; i++) {
        cache.put("entry-" + i, 32);  // 32 KB each → ~6.4 MB total
        if (i % 50 == 0) {
          MemoryProfiler.mark("cache-size-" + cache.size());
        }
        Thread.sleep(20);
      }
    }

    MemoryProfiler.markGc("cache-full");

    // ── Phase 4: Partial eviction ────────────────────────────────────────────
    try (MemoryProfiler.Scope phase = MemoryProfiler.scopeGc("phase-4-eviction")) {
      cache.evict(100);  // evict half
      Thread.sleep(300);
    }

    MemoryProfiler.markGc("after-eviction");

    // ── Phase 5: Multi-threaded burst allocation ─────────────────────────────
    MemoryProfiler.mark("mt-burst-start");
    ExecutorService pool = Executors.newFixedThreadPool(4);
    List<Future<List<byte[]>>> futures = new ArrayList<>();

    for (int t = 0; t < 4; t++) {
      final int threadId = t;
      futures.add(pool.submit(() -> {
        try (MemoryProfiler.Scope s = MemoryProfiler.scope("worker-" + threadId)) {
          List<byte[]> local = new ArrayList<>();
          for (int i = 0; i < 20; i++) {
            local.add(new byte[256 * 1024]); // 256 KB per alloc
            Thread.sleep(50);
          }
          return local;
        }
      }));
    }

    // Wait for all threads, then release
    List<List<byte[]>> allResults = new ArrayList<>();
    for (Future<List<byte[]>> f : futures) {
      allResults.add(f.get());
    }
    pool.shutdown();

    MemoryProfiler.markGc("mt-burst-allocated");
    Thread.sleep(500);

    // Release multi-threaded allocations
    allResults.clear();
    MemoryProfiler.markGc("mt-burst-released");
    Thread.sleep(500);

    // ── Phase 6: Large object allocation stress ──────────────────────────────
    try (MemoryProfiler.Scope phase = MemoryProfiler.scopeGc("phase-6-large-objects")) {
      List<byte[]> large = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        large.add(new byte[20 * 1024 * 1024]); // 20 MB each
        MemoryProfiler.mark("large-" + (i + 1) + "-allocated");
        Thread.sleep(300);
      }
      // Release one at a time
      for (int i = large.size() - 1; i >= 0; i--) {
        large.remove(i);
        MemoryProfiler.markGc("large-" + (i + 1) + "-freed");
        Thread.sleep(200);
      }
    }

    // ── Phase 7: Cleanup and heap dump ───────────────────────────────────────
    pipeline.cleanup();
    cache.evict(cache.size());
    MemoryProfiler.markGc("all-cleaned");
    Thread.sleep(500);

    // Record heap dump for offline analysis
    MemoryProfiler.heapDump("demo-final");

    // ── Phase 8: Object layout analysis (print to console) ───────────────────
    System.out.println();
    System.out.println("=== Object Layout: LeakyCache ===");
    ObjectLayoutAnalyzer.LayoutResult layout = ObjectLayoutAnalyzer.analyze(LeakyCache.class);
    System.out.printf("  Shallow size: %d bytes (header: %d, fields: %d, padding: %d)%n",
        layout.shallowSize, layout.headerSize, layout.fieldsSize, layout.paddingSize);
    for (ObjectLayoutAnalyzer.LayoutEntry e : layout.entries) {
      if ("field".equals(e.kind)) {
        System.out.printf("  [%2d +%d] %s %s%n", e.offset, e.size, e.fieldType, e.fieldName);
      }
    }
    System.out.println();

    System.out.println("Demo complete. Press Enter to stop profiling (UI stays up)...");
    System.in.read();
    MemoryProfiler.stop();
    MemoryProfiler.await();
  }
}
