package com.github.lechandonga.walstorageenginejava.tests;

import com.github.lechandonga.walstorageenginejava.engine.KeyValueStore;
import com.github.lechandonga.walstorageenginejava.engine.ReadStatus;
import com.github.lechandonga.walstorageenginejava.engine.RecoveryReport;
import com.github.lechandonga.walstorageenginejava.engine.StoreConfig;
import com.github.lechandonga.walstorageenginejava.engine.WriteOperation;
import com.github.lechandonga.walstorageenginejava.tests.support.CaseLogger;
import com.github.lechandonga.walstorageenginejava.tests.support.StoreTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：异常退出恢复（崩溃回放、尾部截断、内容损坏、未知格式版本拒绝）。
 */
@ExtendWith(CaseLogger.class)
class CrashRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("模拟进程异常退出后重开，回到最近一次已确认写入的一致状态")
    void recoverAfterHardCrash() throws Exception {
        StoreConfig config = StoreTestSupport.config(tempDir);
        KeyValueStore store = KeyValueStore.open(config);
        store.put("k1", "v1");
        store.writeBatch(List.of(
                WriteOperation.put("k2", "v2"),
                WriteOperation.delete("k1"),
                WriteOperation.put("k3", "v3")));
        long sizeAfterConfirmed = Files_size();
        StoreTestSupport.hardCrash(store);

        KeyValueStore reopened = KeyValueStore.open(config);
        assertEquals(RecoveryReport.Outcome.CLEAN, reopened.lastRecoveryReport().outcome());
        assertEquals(2, reopened.size());
        assertEquals("键存在过但已被删除", reopened.get("k1").reason());
        assertEquals("v2", reopened.get("k2").value());
        assertEquals("v3", reopened.get("k3").value());

        reopened.put("k4", "v4");
        assertEquals("v4", reopened.get("k4").value());
        reopened.close();
        assertTrue(sizeAfterConfirmed > StoreConfig.HEADER_SIZE);
    }

    private long Files_size() throws Exception {
        return java.nio.file.Files.size(StoreTestSupport.walFile(tempDir));
    }

    @Test
    @DisplayName("WAL 尾部被截断：不读出错乱数据，截断到最后一个完好位置并说明结论")
    void recoverFromTruncatedTail() throws Exception {
        StoreConfig config = StoreTestSupport.config(tempDir);
        KeyValueStore store = KeyValueStore.open(config);
        store.put("k1", "v1");
        store.put("k2", "v2");
        long fullSize = java.nio.file.Files.size(StoreTestSupport.walFile(tempDir));
        store.close();

        StoreTestSupport.truncateFile(StoreTestSupport.walFile(tempDir), fullSize - 3);

        KeyValueStore reopened = KeyValueStore.open(config);
        RecoveryReport report = reopened.lastRecoveryReport();
        assertEquals(RecoveryReport.Outcome.TRUNCATED, report.outcome());
        assertEquals(3, report.discardedBytes());
        assertEquals("v1", reopened.get("k1").value());
        assertEquals("键从未写入", reopened.get("k2").reason());

        reopened.put("k3", "v3");
        KeyValueStore again = KeyValueStore.open(config);
        assertEquals(RecoveryReport.Outcome.CLEAN, again.lastRecoveryReport().outcome());
        assertEquals("v3", again.get("k3").value());
        again.close();
        reopened.close();
    }

    @Test
    @DisplayName("WAL 帧内容损坏（CRC 失败）：损坏帧及其后内容全部丢弃，状态回到完好位置")
    void recoverFromCorruptedContent() throws Exception {
        StoreConfig config = StoreTestSupport.config(tempDir);
        KeyValueStore store = KeyValueStore.open(config);
        store.put("k1", "v1");
        store.put("k2", "v2");
        store.put("k3", "v3");
        long fullSize = java.nio.file.Files.size(StoreTestSupport.walFile(tempDir));
        store.close();

        long corruptPosition = StoreConfig.HEADER_SIZE + 10;
        StoreTestSupport.corruptBytes(StoreTestSupport.walFile(tempDir), corruptPosition, 4);

        KeyValueStore reopened = KeyValueStore.open(config);
        RecoveryReport report = reopened.lastRecoveryReport();
        assertEquals(RecoveryReport.Outcome.TRUNCATED, report.outcome());
        assertTrue(report.discardedBytes() > 0);
        assertEquals("v1", reopened.get("k1").value());
        assertEquals("键从未写入", reopened.get("k2").reason());
        assertEquals("键从未写入", reopened.get("k3").reason());
        assertEquals(fullSize - report.discardedBytes(), report.validBytes());
        reopened.close();
    }

    @Test
    @DisplayName("无法识别的存储格式版本必须明确拒绝，而不是猜测解析")
    void unsupportedVersionRejected() throws Exception {
        StoreConfig config = StoreTestSupport.config(tempDir);
        KeyValueStore store = KeyValueStore.open(config);
        store.put("k1", "v1");
        store.close();

        StoreTestSupport.rewriteVersionByte(StoreTestSupport.walFile(tempDir), 99);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> KeyValueStore.open(config));
        assertTrue(ex.getMessage().contains("版本"), ex.getMessage());
        assertTrue(ex.getMessage().contains("拒绝"), ex.getMessage());
    }

    @Test
    @DisplayName("空目录首次打开以空存储启动，写入并再次重开数据仍在")
    void emptyStartThenRoundTrip() throws Exception {
        StoreConfig config = StoreTestSupport.config(tempDir.resolve("fresh"));
        KeyValueStore store = KeyValueStore.open(config);
        assertEquals(RecoveryReport.Outcome.EMPTY, store.lastRecoveryReport().outcome());
        store.put("hello", "world");
        store.close();

        KeyValueStore reopened = KeyValueStore.open(config);
        assertEquals("world", reopened.get("hello").value());
        reopened.close();
    }
}
