package com.github.lechandonga.walstorageenginejava.kv.model;

/**
 * 内存索引中某个键的当前状态。
 *
 * <p>tombstone 为 true 表示该键曾经写入但随后被删除，与“从未存在”严格区分。</p>
 */
public record Entry(byte[] value, long batchId, boolean tombstone) {

    public static Entry put(byte[] value, long batchId) {
        return new Entry(value, batchId, false);
    }

    public static Entry tombstone(long batchId) {
        return new Entry(null, batchId, true);
    }
}
