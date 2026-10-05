package com.github.lechandonga.walstorageenginejava.tests;

import com.github.lechandonga.walstorageenginejava.engine.KeyValueStore;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：基础读写语义（写入/读取/删除/批量/幂等/拒绝结论）。
 */
@ExtendWith(CaseLogger.class)
class BasicOperationTest {

    @TempDir
    Path tempDir;

    private KeyValueStore store;

    @BeforeEach
    void setUp() {
        store = KeyValueStore.open(new StoreConfig(tempDir.toString(), 128, 128, 100));
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    @Test
    @DisplayName("写入后可读取，未写入键与已删除键结论可区分（均为 NOT_FOUND 但 reason 不同）")
    void putGetDeleteDistinction() {
        assertTrue(store.put("name", "alice").committed());

        var present = store.get("name");
        assertEquals(ReadStatus.OK, present.status());
        assertEquals("alice", present.value());

        var neverWritten = store.get("ghost");
        assertEquals(ReadStatus.NOT_FOUND, neverWritten.status());
        assertEquals("键从未写入", neverWritten.reason());

        assertTrue(store.delete("name").committed());
        var deleted = store.get("name");
        assertEquals(ReadStatus.NOT_FOUND, deleted.status());
        assertEquals("键存在过但已被删除", deleted.reason());
    }

    @Test
    @DisplayName("重复写入与重复删除幂等：结果与单次操作一致")
    void idempotentPutAndDelete() {
        store.put("k", "v1");
        store.put("k", "v2");
        store.put("k", "v2");
        assertEquals("v2", store.get("k").value());

        store.delete("k");
        var secondDelete = store.delete("k");
        assertTrue(secondDelete.committed());
        assertEquals("键存在过但已被删除", store.get("k").reason());

        var deleteNeverWritten = store.delete("never");
        assertTrue(deleteNeverWritten.committed());
        assertEquals("键从未写入", store.get("never").reason(), "删除从未存在的键不留下墓碑");
    }

    @Test
    @DisplayName("删除后重新写入可以正常读到新值")
    void putAfterDelete() {
        store.put("k", "v1");
        store.delete("k");
        store.put("k", "v2");
        var result = store.get("k");
        assertEquals(ReadStatus.OK, result.status());
        assertEquals("v2", result.value());
    }

    @Test
    @DisplayName("超过键长/值长限制的写入被拒，状态为 REJECTED 且数据不变")
    void rejectedOversizedWrites() {
        var oversizedKey = store.put("k".repeat(200), "v");
        assertEquals(ReadStatus.REJECTED, oversizedKey.status());

        var oversizedValue = store.put("ok", "v".repeat(200));
        assertEquals(ReadStatus.REJECTED, oversizedValue.status());

        var invalidRead = store.get("");
        assertEquals(ReadStatus.REJECTED, invalidRead.status());
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("合法批次整体生效：批次内包含 PUT 与 DELETE")
    void batchCommitsAtomicallyWhenValid() {
        store.put("a", "old");
        var result = store.writeBatch(List.of(
                WriteOperation.put("a", "new"),
                WriteOperation.put("b", "bee"),
                WriteOperation.delete("a"),
                WriteOperation.put("c", "sea")));
        assertTrue(result.committed());
        assertEquals(4, result.appliedOps());
        assertEquals(2, store.size());
        assertEquals("键存在过但已被删除", store.get("a").reason());
        assertEquals("bee", store.get("b").value());
        assertEquals("sea", store.get("c").value());
    }

    @Test
    @DisplayName("非法批次整体不生效：一个操作超长，整批拒绝且其它键无变化")
    void batchRejectedAsAWhole() {
        store.put("safe", "1");
        var result = store.writeBatch(List.of(
                WriteOperation.put("safe", "2"),
                WriteOperation.put("bad", "v".repeat(200)),
                WriteOperation.put("other", "3")));
        assertFalse(result.committed());
        assertEquals(ReadStatus.REJECTED, result.status());
        assertEquals("1", store.get("safe").value());
        assertEquals("键从未写入", store.get("other").reason());
        assertEquals("键从未写入", store.get("bad").reason());
    }

    @Test
    @DisplayName("空批次与超过操作数上限的批次被拒")
    void emptyAndOversizedBatchRejected() {
        assertEquals(ReadStatus.REJECTED, store.writeBatch(List.of()).status());
        var tooMany = java.util.stream.IntStream.range(0, 101)
                .mapToObj(i -> WriteOperation.put("k" + i, "v"))
                .toList();
        assertEquals(ReadStatus.REJECTED, store.writeBatch(tooMany).status());
    }
}
