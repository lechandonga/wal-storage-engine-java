package com.github.lechandonga.walstorageenginejava.kv.model;

/**
 * 批量写中的单个操作：PUT 或 DELETE。
 */
public record KvOperation(OpType type, String key, byte[] value) {

    public static KvOperation put(String key, byte[] value) {
        return new KvOperation(OpType.PUT, key, value);
    }

    public static KvOperation delete(String key) {
        return new KvOperation(OpType.DELETE, key, null);
    }
}
