package com.github.lechandonga.walstorageenginejava.kv.web;

import com.github.lechandonga.walstorageenginejava.kv.KeyValueStore;
import com.github.lechandonga.walstorageenginejava.kv.model.GetResult;
import com.github.lechandonga.walstorageenginejava.kv.model.KvOperation;
import com.github.lechandonga.walstorageenginejava.kv.model.OpType;
import com.github.lechandonga.walstorageenginejava.kv.model.ReadOutcome;
import com.github.lechandonga.walstorageenginejava.kv.model.WriteOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 本地演示/验证用 REST 接口（仅监听本机配置端口，不依赖任何外部服务）。
 * 值采用 Base64 传输以支持任意字节。
 */
@RestController
@RequestMapping("/api/kv")
@ConditionalOnProperty(prefix = "kv.store", name = "web-enabled", havingValue = "true", matchIfMissing = true)
public class KvController {

    private final KeyValueStore store;

    public KvController(KeyValueStore store) {
        this.store = store;
    }

    @GetMapping("/{key}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String key) {
        GetResult result = store.get(key);
        if (result.outcome() == ReadOutcome.FOUND) {
            return ResponseEntity.ok(Map.of(
                    "outcome", "FOUND",
                    "key", key,
                    "valueBase64", Base64.getEncoder().encodeToString(result.value())));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("outcome", result.outcome().name(), "key", key));
    }

    @PutMapping("/{key}")
    public Map<String, Object> put(@PathVariable String key, @RequestBody PutRequest request) {
        byte[] value = Base64.getDecoder().decode(request.valueBase64() == null ? "" : request.valueBase64());
        WriteOutcome outcome = store.put(key, value);
        return Map.of("outcome", outcome.name(), "key", key);
    }

    @DeleteMapping("/{key}")
    public Map<String, Object> delete(@PathVariable String key) {
        WriteOutcome outcome = store.delete(key);
        return Map.of("outcome", outcome.name(), "key", key);
    }

    @PostMapping("/batch")
    public Map<String, Object> batch(@RequestBody BatchRequest request) {
        List<KvOperation> operations = new ArrayList<>();
        for (BatchOpDto op : request.operations()) {
            OpType type = "DELETE".equalsIgnoreCase(op.op()) ? OpType.DELETE : OpType.PUT;
            if (type == OpType.PUT) {
                operations.add(KvOperation.put(op.key(),
                        Base64.getDecoder().decode(op.valueBase64() == null ? "" : op.valueBase64())));
            } else {
                operations.add(KvOperation.delete(op.key()));
            }
        }
        WriteOutcome outcome = store.batch(operations);
        return Map.of("outcome", outcome.name(), "applied", operations.size());
    }

    @PostMapping("/backup")
    public Map<String, Object> backup(@RequestBody BackupRequest request) {
        store.backupTo(java.nio.file.Path.of(request.file()));
        return Map.of("outcome", "BACKUP_CREATED", "file", request.file());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("outcome", "REJECTED", "reason", e.getMessage()));
    }

    @ExceptionHandler(com.github.lechandonga.walstorageenginejava.kv.WriteRejectedException.class)
    public ResponseEntity<Map<String, String>> handleRejected(
            com.github.lechandonga.walstorageenginejava.kv.WriteRejectedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("outcome", "REJECTED", "reason", e.getMessage()));
    }

    public record PutRequest(String valueBase64) {
    }

    public record BatchOpDto(String op, String key, String valueBase64) {
    }

    public record BatchRequest(List<BatchOpDto> operations) {
    }

    public record BackupRequest(String file) {
    }

}
