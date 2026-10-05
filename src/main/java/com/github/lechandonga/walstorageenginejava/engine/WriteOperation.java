package com.github.lechandonga.walstorageenginejava.engine;

/**
 * 批量写入中的单个操作：PUT 或 DELETE。
 */
public record WriteOperation(RecordType type, String key, String value) {

    public static WriteOperation put(String key, String value) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("键不能为空");
        }
        if (value == null) {
            throw new IllegalArgumentException("值不能为 null");
        }
        return new WriteOperation(RecordType.PUT, key, value);
    }

    public static WriteOperation delete(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("键不能为空");
        }
        return new WriteOperation(RecordType.DELETE, key, null);
    }
}
