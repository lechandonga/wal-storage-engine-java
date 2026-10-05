package com.github.lechandonga.walstorageenginejava.tests;

import com.github.lechandonga.walstorageenginejava.engine.KeyValueStore;
import com.github.lechandonga.walstorageenginejava.engine.ReadResult;
import com.github.lechandonga.walstorageenginejava.engine.ReadStatus;
import com.github.lechandonga.walstorageenginejava.engine.StoreConfig;
import com.github.lechandonga.walstorageenginejava.engine.WriteOperation;
import com.github.lechandonga.walstorageenginejava.tests.support.CaseLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：并发读写一致性与一致性备份/恢复。
 */
@ExtendWith(CaseLogger.class)
class ConcurrencyAndSnapshotTest {

    @TempDir
    Path tempDir;

    private KeyValueStore store;

    @BeforeEach
    void setUp() {
        store = KeyValueStore.open(new StoreConfig(tempDir.resolve("live").toString(), 4096, 4096, 1000));
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    @Test
    @DisplayName("多线程并发写入互不丢失，读取永远看不到写了一半的批次")
    void concurrentWritersAndReaders() throws Exception {
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads * 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int writerId = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    var result = store.writeBatch(List.of(
                            WriteOperation.put("w" + writerId + ":" + i, String.valueOf(i)),
                            WriteOperation.put("shared", writerId + "-" + i)));
                    if (!result.committed()) {
                        rejected.incrementAndGet();
                    }
                }
                return null;
            }));
        }

        List<Future<?>> readers = new ArrayList<>();
        for (int r = 0; r < threads; r++) {
            readers.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread * 2; i++) {
                    ReadResult shared = store.get("shared");
                    if (shared.status() == ReadStatus.OK) {
                        String[] parts = shared.value().split("-");
                        Integer.parseInt(parts[0]);
                        Integer.parseInt(parts[1]);
                    }
                    assertEquals(ReadStatus.NOT_FOUND, store.get("w" + (-1) + ":" + i).status());
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        for (Future<?> future : readers) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(0, rejected.get());
        assertEquals(threads * perThread + 1, store.size());
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < perThread; i++) {
                assertEquals(String.valueOf(i), store.get("w" + t + ":" + i).value());
            }
        }
    }

    @Test
    @DisplayName("备份期间持续写入：每个备份都是某时刻一致视图，恢复后读取结论与导出时刻一致")
    void snapshotIsConsistentUnderConcurrentWrites() throws Exception {
        store.put("base", "0");
        int totalWrites = 500;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        List<Path> backups = java.util.Collections.synchronizedList(new ArrayList<>());
        Future<?> writer = pool.submit(() -> {
            start.await();
            for (int i = 1; i <= totalWrites; i++) {
                store.writeBatch(List.of(
                        WriteOperation.put("counter", String.valueOf(i)),
                        WriteOperation.put("even" + i, i % 2 == 0 ? "y" : "n")));
            }
            return null;
        });

        Future<?> snapshotter = pool.submit(() -> {
            start.await();
            for (int i = 0; i < 20; i++) {
                try {
                    backups.add(store.snapshotTo(tempDir.resolve("backups").resolve("snap-" + i + ".log")));
                    Thread.sleep(2);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return null;
        });

        start.countDown();
        writer.get(30, TimeUnit.SECONDS);
        snapshotter.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(String.valueOf(totalWrites), store.get("counter").value());

        int verified = 0;
        for (Path backup : backups) {
            StoreConfig restoredConfig = new StoreConfig(
                    tempDir.resolve("restored-" + verified).toString(), 4096, 4096, 1000);
            try (KeyValueStore restored = KeyValueStore.restoreFrom(backup, restoredConfig)) {
                ReadResult counter = restored.get("counter");
                assertEquals(ReadStatus.OK, counter.status());
                int counterValue = Integer.parseInt(counter.value());

                for (int i = 1; i <= totalWrites; i++) {
                    ReadResult even = restored.get("even" + i);
                    if (i <= counterValue) {
                        assertEquals(ReadStatus.OK, even.status(),
                                "备份 " + backup + " 计数器=" + counterValue + " 但缺少 even" + i);
                    } else {
                        assertEquals(ReadStatus.NOT_FOUND, even.status(),
                                "备份 " + backup + " 计数器=" + counterValue + " 却包含未来的 even" + i);
                    }
                }
                assertEquals("0", restored.get("base").value());
            }
            verified++;
        }
        assertTrue(verified >= 2, "至少应生成 2 个备份，实际 " + verified);

        try (KeyValueStore finalView = KeyValueStore.open(
                new StoreConfig(tempDir.resolve("final-check").toString(), 4096, 4096, 1000))) {
            assertEquals(ReadStatus.NOT_FOUND, finalView.get("counter").status(),
                    "新目录应为空，证明恢复使用的是备份文件而非原目录");
        }
    }
}
