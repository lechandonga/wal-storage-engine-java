package com.github.lechandonga.walstorageenginejava.tests;

import com.github.lechandonga.walstorageenginejava.kv.KeyValueStore;
import com.github.lechandonga.walstorageenginejava.kv.WalKeyValueStore;
import com.github.lechandonga.walstorageenginejava.kv.model.KvOperation;
import com.github.lechandonga.walstorageenginejava.kv.model.ReadOutcome;
import com.github.lechandonga.walstorageenginejava.kv.model.WriteOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("业务3：并发读写与一致性备份（无半写视图、备份为时刻快照、可恢复）")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConcurrencyAndBackupTest {

    @TempDir
    Path dir;

    private static byte[] v(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("用例：多线程并发读写")
    class ConcurrentReadWrite {

        @Test
        @Order(1)
        @DisplayName("8 个写线程 + 8 个读线程并发：读取永远只看到已提交的完整版本，无异常/撕裂值")
        void readersNeverSeeHalfWrittenState() throws Exception {
            int writers = 8;
            int readers = 8;
            int iterations = 200;
            ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger errors = new AtomicInteger();

            try (WalKeyValueStore store = new WalKeyValueStore(dir, 1024 * 1024)) {
                List<Future<?>> futures = new ArrayList<>();
                for (int w = 0; w < writers; w++) {
                    final int writerId = w;
                    futures.add(pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < iterations; i++) {
                                String key = "key-" + (i % 20);
                                byte[] value = ("writer-" + writerId + "-iter-" + i)
                                        .getBytes(StandardCharsets.UTF_8);
                                store.batch(List.of(
                                        KvOperation.put(key, value),
                                        KvOperation.put("meta-" + key, value)));
                                if (i % 50 == 0) {
                                    store.delete("temp-" + writerId);
                                }
                            }
                        } catch (Throwable t) {
                            errors.incrementAndGet();
                        }
                    }));
                }
                for (int r = 0; r < readers; r++) {
                    futures.add(pool.submit(() -> {
                        try {
                            start.await();
                            long rounds = 0;
                            while (rounds < 5_000) {
                                rounds++;
                                for (int k = 0; k < 20; k++) {
                                    String key = "key-" + k;
                                    var got = store.get(key);
                                    var meta = store.get("meta-" + key);
                                    if (got.outcome() == ReadOutcome.FOUND) {
                                        // 同一批次写入的两个键必须同时可见（无半写状态）
                                        assertEquals(ReadOutcome.FOUND, meta.outcome(),
                                                "读到半写状态：主值存在但批次配套键缺失");
                                        // 值必须是完整写入的 "writer-*-iter-*"，不能是撕裂字节
                                        String text = new String(got.value(), StandardCharsets.UTF_8);
                                        assertTrue(text.matches("writer-\\d+-iter-\\d+"),
                                                "读到撕裂/半写数据，值格式不完整: " + text);
                                    } else {
                                        assertEquals(ReadOutcome.NOT_FOUND, meta.outcome(),
                                                "读到半写状态：主值不可见但批次配套键已可见");
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            errors.incrementAndGet();
                        }
                    }));
                }
                start.countDown();
                for (Future<?> f : futures) {
                    f.get(2, TimeUnit.MINUTES);
                }
            } finally {
                pool.shutdownNow();
                pool.awaitTermination(5, TimeUnit.SECONDS);
            }
            assertEquals(0, errors.get(), "并发过程中出现异常或一致性断言失败");

            try (WalKeyValueStore reopened = new WalKeyValueStore(dir, 1024 * 1024)) {
                assertTrue(reopened.getRecoveryReport().clean(), "并发写入后日志应完好");
                for (int k = 0; k < 20; k++) {
                    assertEquals(ReadOutcome.FOUND, reopened.get("key-" + k).outcome());
                }
            }
        }
    }

    @Nested
    @DisplayName("用例：一致性备份与恢复")
    class ConsistentBackup {

        @Test
        @Order(2)
        @DisplayName("备份与持续写入并发：备份期间的写入要么完整包含，要么完全不包含")
        void backupIsPointInTimeSnapshot() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Path> backups = new ArrayList<>();
            AtomicInteger errors = new AtomicInteger();

            try (WalKeyValueStore store = new WalKeyValueStore(dir, 1024 * 1024)) {
                store.put("base", v("0"));
                CountDownLatch backupDone = new CountDownLatch(1);

                Future<?> writer = pool.submit(() -> {
                    try {
                        for (int i = 1; i <= 400; i++) {
                            store.batch(List.of(
                                    KvOperation.put("pair-a-" + i, v("v" + i)),
                                    KvOperation.put("pair-b-" + i, v("v" + i)),
                                    KvOperation.put("counter", v(String.valueOf(i)))));
                        }
                    } catch (Throwable t) {
                        errors.incrementAndGet();
                    }
                });

                Future<?> backuper = pool.submit(() -> {
                    try {
                        for (int b = 0; b < 10; b++) {
                            Path file = dir.resolve("backup-" + b + ".wal");
                            store.backupTo(file);
                            backups.add(file);
                        }
                    } catch (Throwable t) {
                        errors.incrementAndGet();
                    } finally {
                        backupDone.countDown();
                    }
                });

                writer.get(2, TimeUnit.MINUTES);
                backupDone.await();
            } finally {
                pool.shutdownNow();
            }
            assertEquals(0, errors.get());

            for (Path backup : backups) {
                Path restoreDir = dir.resolve("restore-" + backup.getFileName());
                Files.createDirectories(restoreDir);
                Files.copy(backup, restoreDir.resolve("kv.wal"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                try (WalKeyValueStore restored = new WalKeyValueStore(restoreDir, 1024 * 1024)) {
                    assertEquals(ReadOutcome.FOUND, restored.get("base").outcome());
                    var counter = restored.get("counter");
                    if (counter.outcome() == ReadOutcome.FOUND) {
                        int n = Integer.parseInt(new String(counter.value(), StandardCharsets.UTF_8));
                        for (int i = 1; i <= 400; i++) {
                            var a = restored.get("pair-a-" + i);
                            var b = restored.get("pair-b-" + i);
                            if (i <= n) {
                                assertEquals(ReadOutcome.FOUND, a.outcome(),
                                        "备份不一致：批次 " + i + " 只包含了一部分（应完整包含）");
                                assertEquals(ReadOutcome.FOUND, b.outcome(),
                                        "备份不一致：批次 " + i + " 只包含了一部分（应完整包含）");
                            } else {
                                assertEquals(ReadOutcome.NOT_FOUND, a.outcome(),
                                        "备份不一致：批次 " + i + " 越界包含（应完整不包含）");
                                assertEquals(ReadOutcome.NOT_FOUND, b.outcome(),
                                        "备份不一致：批次 " + i + " 越界包含（应完整不包含）");
                            }
                        }
                    }
                }
            }
        }

        @Test
        @Order(3)
        @DisplayName("含删除的时刻快照：从备份恢复后的读取结论与导出时刻一致")
        void backupPreservesDeletedSemantics() throws Exception {
            Path backup;
            try (WalKeyValueStore store = new WalKeyValueStore(dir, 1024 * 1024)) {
                store.put("kept", v("yes"));
                store.put("gone", v("no"));
                store.delete("gone");
                backup = dir.resolve("snapshot.wal");
                store.backupTo(backup);
            }
            Path restoreDir = dir.resolve("restore-delete");
            Files.createDirectories(restoreDir);
            Files.copy(backup, restoreDir.resolve("kv.wal"));
            try (KeyValueStore restored = new WalKeyValueStore(restoreDir, 1024 * 1024)) {
                assertArrayEquals(v("yes"), restored.get("kept").value());
                // 备份压缩后删除键不再存在于快照中，读取为 NOT_FOUND，与“导出时刻该键无有效值”一致
                assertEquals(ReadOutcome.NOT_FOUND, restored.get("gone").outcome());
                assertEquals(ReadOutcome.NOT_FOUND, restored.get("never").outcome());
                // 恢复后的实例仍可正常写入并再次持久化
                assertEquals(WriteOutcome.APPLIED, restored.put("new", v("x")));
            }
            try (WalKeyValueStore again = new WalKeyValueStore(restoreDir, 1024 * 1024)) {
                assertArrayEquals(v("x"), again.get("new").value());
            }
        }
    }
}
