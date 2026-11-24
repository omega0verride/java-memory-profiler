package com.toro.al.memprofiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bug 1: one JVM run must not be split in two when points arrive out of index order. */
class RunSplitTest {

  @TempDir
  File tmp;

  private static String line(long index, long ts, String kind) {
    return new DataPoint(index, ts, 100, 200, 4096, DataPoint.Kind.valueOf(kind), null, null, null,
        null, null, null, "t").toJsonLine();
  }

  @Test
  void smallInversionStaysInTheSameRun() throws Exception {
    File f = new File(tmp, "inverted.jsonl");
    Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
    long t = TestData.T0;
    for (int i = 0; i < 250; i++) w.write(line(i, t + i * 250L, "SAMPLE") + "\n");
    w.write(line(250, t + 250 * 250L + 1, "SAMPLE") + "\n");
    w.write(line(249, t + 250 * 250L, "GC") + "\n"); // a GC event written after a later SAMPLE (1.0.4 race)
    for (int i = 251; i < 400; i++) w.write(line(i, t + i * 250L, "SAMPLE") + "\n");
    // a genuinely new, appended run
    for (int i = 0; i < 5; i++) w.write(line(i, t + 1_000_000 + i * 250L, "SAMPLE") + "\n");
    w.close();

    JsonlSource src = JsonlSource.open(f, "all");
    assertEquals(2, src.runs().size());
    assertEquals(401, src.runs().get(0).points, "0..399 plus the out-of-order GC point");
    assertEquals(5, src.runs().get(1).points);
  }

  @Test
  void concurrentWritersNeverInvertTheIndexInTheFile() throws Exception {
    File f = new File(tmp, "concurrent.jsonl");
    final DataStore store = new DataStore(1_000_000);
    store.enableJsonl(f.getPath());
    final int threads = 4, perThread = 5000;
    final CountDownLatch go = new CountDownLatch(1);
    Thread[] ts = new Thread[threads];
    for (int k = 0; k < threads; k++) {
      final DataPoint.Kind kind = k % 2 == 0 ? DataPoint.Kind.SAMPLE : DataPoint.Kind.GC;
      ts[k] = new Thread(new Runnable() {
        public void run() {
          try { go.await(); } catch (InterruptedException e) { return; }
          for (int i = 0; i < perThread; i++) {
            // the index is taken before the lock, exactly as the sampler and GC listener do
            long provisional = store.nextIndex();
            store.add(new DataPoint(provisional, System.currentTimeMillis(), i, i, 4096, kind,
                null, null, null, null, null, null, Thread.currentThread().getName()));
          }
        }
      });
      ts[k].start();
    }
    go.countDown();
    for (Thread t : ts) t.join();
    store.close();

    List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
    assertTrue(lines.get(0).contains("\"kind\":\"META\""), "the run header comes first");
    lines = lines.subList(1, lines.size());
    assertEquals(threads * perThread, lines.size());
    for (int i = 0; i < lines.size(); i++) {
      assertEquals(i, JsonlSource.parseLine(lines.get(i)).index, "line " + i);
    }
    assertEquals(1, JsonlSource.open(f, "last").runs().size());
  }
}
