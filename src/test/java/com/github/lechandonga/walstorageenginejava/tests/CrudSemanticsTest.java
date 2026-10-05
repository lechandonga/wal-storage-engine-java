package com.github.lechandonga.walstorageenginejava.tests;

import com.github.lechandonga.walstorageenginejava.kv.WalKeyValueStore;
import com.github.lechandonga.walstorageenginejava.kv.WriteRejectedException;
import com.github.lechandonga.walstorageenginejava.kv.model.GetResult;
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
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("业务1：基础读写语义（写入/读取/删除/批量/幂等/拒绝可区分）")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrudSemanticsTest {

    private static byte[] v(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @TempDir
    Path dir;

    @Nested
    @DisplayName("用例：单键读写删与三种读取结论")
    class SingleKey {

        @Test
        @Order(1)
        @DisplayName("未写入键返回 NOT_FOUND，写入后 FOUND，删除后 DELETED，三者可区分")
        void distinguishNotFoundFoundDeleted() {
            try (WalKeyValueStore store = new WalKeyValueStore(dir, 1024 * 1024)) {
                assertEquals(ReadOutcome.NOT_FOUND, store.get("k1").outcome());

                assertEquals(WriteOutcome.APPLIED, store.put("k1", v("hello")));
                GetResult got = store.get("k1");
                assertEquals(ReadOutcome.FOUND, got.outcome());
                assertArrayEquals(v("hello"), got.value());

                assertEquals(WriteOutcome.APPLIED, store.delete("k1"));
                assertEquals(ReadOutcome.DELETED, store.get("k1").outcome());
            }
        }

        @Test
        @Order(2)
        @DisplayName("重复写入相同值与重复删除均幂等，返回 IDEMPOTENT_NOOP")
        void idempotentWritesAndDeletes() {
            try (WalKeyValueStore store = new WalKeyValueStore(dir, 1024 * 1024)) {
                assertEquals(WriteOutcome.APPLIED, store.put("k", v("a")));
                assertEquals(WriteOutcome.IDEMPOTENT_NOOP, store.put("k", v("a")));
                assertEquals(WriteOutcome.APPLIED, store.put("k", v("b")));

                assertEquals(WriteOutcome.APPLIED, store.delete("k"));
                assertEquals(WriteOutcome.IDEMPOTENT_NOOP, store.delete("k"));
                // 从未存在过的键执行删除也是幂等无操作；删除后该键与 DELETED 不可区分
                assertEquals(WriteOutcome.IDEMPOTENT_NOOP, store.delete("never-existed"));
                assertEquals(ReadOutcome.DELETED, store.get("never-existed").outcome());
                assertEquals(ReadOutcome.DELETED, store.get("k").outcome());
            }
        }

        @Test
        @Order(3)
        @DisplayName("空键与关闭后的写入被明确拒绝，结论为 WriteRejectedException")
        void writesAreRejectedClearly() {
            WalKeyValueStore store = new WalKeyValueStore(dir, 1024 * 1024);
            assertThrows(WriteRejectedException.class, () -> store.put("", v("x")));
            assertThrows(WriteRejectedException.class, () -> store.put(null, v("x")));
            assertThrows(WriteRejectedException.class, () -> store.batch(List.of()));
            store.close();
            assertThrows(WriteRejectedException.class, () -> store.put("k", v("x")));
        }
    }

    @Nested
    @DisplayName("用例：批量写原子性")
    class BatchAtomicity {

        @Test
        @Order(4)
        @DisplayName("批量内多个 PUT/DELETE 整体生效")
        void batchAppliesAll() {
            try (WalKeyValueStore store = new WalKeyValueStore(dir, 1024 * 1024)) {
                store.put("a", v("1"));
                WriteOutcome outcome = store.batch(List.of(
                        KvOperation.put("b", v("2")),
                        KvOperation.put("c", v("3")),
                        KvOperation.delete("a")));
                assertEquals(WriteOutcome.APPLIED, outcome);
                assertArrayEquals(v("2"), store.get("b").value());
                assertArrayEquals(v("3"), store.get("c").value());
                assertEquals(ReadOutcome.DELETED, store.get("a").outcome());
            }
        }

        @Test
        @Order(5)
        @DisplayName("批量参数非法（空值/空键）时整体不生效，原有状态不变")
        void invalidBatchAppliesNothing() {
            try (WalKeyValueStore store = new WalKeyValueStore(dir, 1024 * 1024)) {
                store.put("keep", v("v"));
                List<KvOperation> bad = List.of(
                        KvOperation.put("new1", v("x")),
                        KvOperation.put("", v("bad-key")),
                        KvOperation.put("new2", v("y")));
                assertThrows(WriteRejectedException.class, () -> store.batch(bad));
                assertEquals(ReadOutcome.NOT_FOUND, store.get("new1").outcome());
                assertEquals(ReadOutcome.NOT_FOUND, store.get("new2").outcome());
                assertArrayEquals(v("v"), store.get("keep").value());
            }
        }

        @Test
        @Order(6)
        @DisplayName("批量中同一键出现多次时整体拒绝，不产生任何效果")
        void duplicateKeyInBatchIsRejected() {
            try (WalKeyValueStore store = new WalKeyValueStore(dir, 1024 * 1024)) {
                assertThrows(WriteRejectedException.class, () -> store.batch(List.of(
                        KvOperation.put("dup", v("1")),
                        KvOperation.put("other", v("2")),
                        KvOperation.delete("dup"))));
                assertEquals(ReadOutcome.NOT_FOUND, store.get("dup").outcome());
                assertEquals(ReadOutcome.NOT_FOUND, store.get("other").outcome());
            }
        }
    }
}
