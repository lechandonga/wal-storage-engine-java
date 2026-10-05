package com.github.lechandonga.walstorageenginejava.tests;

import com.github.lechandonga.walstorageenginejava.kv.WalKeyValueStore;
import com.github.lechandonga.walstorageenginejava.kv.model.ReadOutcome;
import com.github.lechandonga.walstorageenginejava.kv.model.RecoveryReport;
import com.github.lechandonga.walstorageenginejava.kv.wal.WalCodec;
import com.github.lechandonga.walstorageenginejava.kv.wal.WalFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("业务2：异常退出恢复（已确认写入不丢、尾部截断/损坏安全恢复、版本拒绝）")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrashRecoveryTest {

    @TempDir
    Path dir;

    private Path walFile() {
        return dir.resolve("kv.wal");
    }

    @Nested
    @DisplayName("用例：崩溃后重启的一致性")
    class RestartConsistency {

        @Test
        @Order(1)
        @DisplayName("模拟进程退出后重开，回到最近一次已确认写入（含删除与批量）")
        void reopenReturnsToLastConfirmedState() {
            try (WalKeyValueStore store = new WalKeyValueStore(dir, 1024 * 1024)) {
                store.put("u1", v("alice"));
                store.put("u2", v("bob"));
                store.batch(java.util.List.of(
                        com.github.lechandonga.walstorageenginejava.kv.model.KvOperation.put("u3", v("carol")),
                        com.github.lechandonga.walstorageenginejava.kv.model.KvOperation.delete("u2")));
            }
            try (WalKeyValueStore reopened = new WalKeyValueStore(dir, 1024 * 1024)) {
                assertArrayEquals(v("alice"), reopened.get("u1").value());
                assertEquals(ReadOutcome.DELETED, reopened.get("u2").outcome());
                assertArrayEquals(v("carol"), reopened.get("u3").value());
                RecoveryReport report = reopened.getRecoveryReport();
                assertTrue(report.clean());
                assertEquals(0, report.truncatedBytes());
            }
        }

        @Test
        @Order(2)
        @DisplayName("重启后仍可继续写入，新写入在再次重启后依然存在")
        void writesAfterRestartSurvive() {
            try (WalKeyValueStore store = new WalKeyValueStore(dir, 1024 * 1024)) {
                store.put("x", v("1"));
            }
            try (WalKeyValueStore store = new WalKeyValueStore(dir, 1024 * 1024)) {
                store.put("y", v("2"));
            }
            try (WalKeyValueStore store = new WalKeyValueStore(dir, 1024 * 1024)) {
                assertArrayEquals(v("1"), store.get("x").value());
                assertArrayEquals(v("2"), store.get("y").value());
            }
        }
    }

    @Nested
    @DisplayName("用例：尾部截断与内容损坏")
    class CorruptionHandling {

        @Test
        @Order(3)
        @DisplayName("最后一帧被截断：恢复到最后一个完好位置，截断字节数有明确报告")
        void truncatedTailIsRecoveredSafely() throws Exception {
            try (WalKeyValueStore store = new WalKeyValueStore(dir, 1024 * 1024)) {
                store.put("good1", v("a"));
                store.put("good2", v("b"));
            }
            long originalSize = Files.size(walFile());
            // 模拟崩溃导致最后一帧只写了一半
            try (var out = Files.newByteChannel(walFile(),
                    java.nio.file.StandardOpenOption.WRITE)) {
                out.truncate(originalSize - 3);
            }
            try (WalKeyValueStore reopened = new WalKeyValueStore(dir, 1024 * 1024)) {
                assertArrayEquals(v("a"), reopened.get("good1").value());
                RecoveryReport report = reopened.getRecoveryReport();
                assertEquals(37, report.truncatedBytes());
                assertTrue(report.lastGoodOffset() < originalSize);
                assertTrue(report.reason().contains("截断"));
            }
            // 截断后文件被修复，再次打开应完好
            try (WalKeyValueStore store = new WalKeyValueStore(dir, 1024 * 1024)) {
                assertTrue(store.getRecoveryReport().clean());
                store.put("good3", v("c"));
                assertArrayEquals(v("c"), store.get("good3").value());
            }
        }

        @Test
        @Order(4)
        @DisplayName("帧内容被随机字节损坏：CRC 校验失败，损坏帧及之后内容被丢弃")
        void corruptedBytesAreDetectedByCrc() throws Exception {
            try (WalKeyValueStore store = new WalKeyValueStore(dir, 1024 * 1024)) {
                store.put("good1", v("a"));
                store.put("good2", v("b"));
            }
            byte[] data = Files.readAllBytes(walFile());
            int lastFrameStart = -1;
            // 定位最后一帧起始（从尾部找 FRAME_MAGIC 的小端字节 "FLAW"）
            byte[] magicBytes = {'F', 'L', 'A', 'W'};
            for (int i = data.length - 8; i >= WalCodec.HEADER_SIZE; i--) {
                if (data[i] == magicBytes[0] && data[i + 1] == magicBytes[1]
                        && data[i + 2] == magicBytes[2] && data[i + 3] == magicBytes[3]) {
                    lastFrameStart = i;
                    break;
                }
            }
            assertTrue(lastFrameStart >= 0, "测试前置条件：应能定位最后一帧");
            data[lastFrameStart + 12] ^= (byte) 0xFF;
            Files.write(walFile(), data);

            try (WalKeyValueStore reopened = new WalKeyValueStore(dir, 1024 * 1024)) {
                assertArrayEquals(v("a"), reopened.get("good1").value());
                assertEquals(ReadOutcome.NOT_FOUND, reopened.get("good2").outcome());
                RecoveryReport report = reopened.getRecoveryReport();
                assertTrue(report.truncatedBytes() > 0);
                assertTrue(report.reason().contains("CRC"));
            }
        }

        @Test
        @Order(5)
        @DisplayName("垃圾 magic 出现在中间：停在损坏点，之前的完好数据仍可用")
        void garbageMagicStopsReplay() throws Exception {
            try (WalKeyValueStore store = new WalKeyValueStore(dir, 1024 * 1024)) {
                store.put("good1", v("a"));
            }
            ByteArrayOutputStream extra = new ByteArrayOutputStream();
            extra.writeBytes(new byte[]{0x77, 0x77, 0x77, 0x77});
            extra.writeBytes("garbage-garbage".getBytes(StandardCharsets.UTF_8));
            Files.write(walFile(), extra.toByteArray(),
                    java.nio.file.StandardOpenOption.APPEND);

            try (WalKeyValueStore reopened = new WalKeyValueStore(dir, 1024 * 1024)) {
                assertArrayEquals(v("a"), reopened.get("good1").value());
                assertTrue(reopened.getRecoveryReport().truncatedBytes() > 0);
            }
        }
    }

    @Nested
    @DisplayName("用例：格式版本保护")
    class VersionGuard {

        @Test
        @Order(6)
        @DisplayName("无法识别的存储格式版本必须明确拒绝，不猜测解析")
        void unknownVersionIsRejected() throws Exception {
            Files.createDirectories(dir);
            byte[] header = WalCodec.fileHeader();
            header[4] = 99;
            Files.write(walFile(), header);
            WalFormatException error = assertThrows(WalFormatException.class,
                    () -> new WalKeyValueStore(dir, 1024 * 1024));
            assertTrue(error.getMessage().contains("版本"));
        }

        @Test
        @Order(7)
        @DisplayName("文件头被整体截断时安全报错，不读出错乱数据")
        void truncatedHeaderIsRejected() throws Exception {
            Files.createDirectories(dir);
            Files.write(walFile(), new byte[]{1, 2, 3});
            assertThrows(Exception.class, () -> new WalKeyValueStore(dir, 1024 * 1024));
        }
    }

    private static byte[] v(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
