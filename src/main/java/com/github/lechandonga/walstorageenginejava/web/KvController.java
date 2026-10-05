package com.github.lechandonga.walstorageenginejava.web;

import com.github.lechandonga.walstorageenginejava.engine.KeyValueStore;
import com.github.lechandonga.walstorageenginejava.engine.ReadResult;
import com.github.lechandonga.walstorageenginejava.engine.ReadStatus;
import com.github.lechandonga.walstorageenginejava.engine.RecoveryReport;
import com.github.lechandonga.walstorageenginejava.engine.WriteOperation;
import com.github.lechandonga.walstorageenginejava.engine.WriteResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/kv")
public class KvController {

    private final KeyValueStore store;

    public KvController(KeyValueStore store) {
        this.store = store;
    }

    public record ValueBody(String value) {
    }

    public record BatchBody(List<Map<String, String>> operations) {
    }

    @GetMapping("/{key}")
    public ResponseEntity<ReadResult> get(@PathVariable String key) {
        ReadResult result = store.get(key);
        if (result.status() == ReadStatus.REJECTED) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{key}")
    public ResponseEntity<WriteResult> put(@PathVariable String key, @RequestBody ValueBody body) {
        WriteResult result = store.put(key, body == null ? null : body.value());
        return result.committed() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @DeleteMapping("/{key}")
    public WriteResult delete(@PathVariable String key) {
        return store.delete(key);
    }

    /**
     * 批量写入，操作形如 {"operations":[{"op":"PUT","key":"k1","value":"v1"},
     * {"op":"DELETE","key":"k2"}]}，整体成功或整体拒绝。
     */
    @PostMapping("/batch")
    public ResponseEntity<WriteResult> batch(@RequestBody BatchBody body) {
        List<WriteOperation> operations;
        try {
            operations = body.operations().stream().map(item -> {
                String op = item.getOrDefault("op", "PUT").toUpperCase();
                return "DELETE".equals(op)
                        ? WriteOperation.delete(item.get("key"))
                        : WriteOperation.put(item.get("key"), item.get("value"));
            }).toList();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                    .body(WriteResult.rejected("请求体非法: " + e.getMessage(), List.of()));
        }
        WriteResult result = store.writeBatch(operations);
        return result.committed() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PostMapping("/snapshot")
    public Map<String, String> snapshot() throws IOException {
        Path backup = store.snapshotTo(Path.of("data", "backups",
                "snapshot-" + System.currentTimeMillis() + ".log"));
        return Map.of("backupFile", backup.toString());
    }

    @GetMapping("/recovery-report")
    public RecoveryReport recoveryReport() {
        return store.lastRecoveryReport();
    }
}
