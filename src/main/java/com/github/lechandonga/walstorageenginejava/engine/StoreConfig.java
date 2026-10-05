package com.github.lechandonga.walstorageenginejava.engine;

import java.nio.charset.StandardCharsets;

/**
 * 存储引擎配置项。
 *
 * @param dataDirectory 数据目录，WAL 文件 wal.log 位于其中
 * @param maxKeyBytes   单个键的最大 UTF-8 字节数，超出则写入被拒
 * @param maxValueBytes 单个值的最大 UTF-8 字节数，超出则写入被拒
 * @param maxBatchOps   单个批次最大操作数，超出则整批被拒
 */
public record StoreConfig(String dataDirectory,
                          int maxKeyBytes,
                          int maxValueBytes,
                          int maxBatchOps) {

    public static final int FORMAT_VERSION = 1;
    public static final int HEADER_SIZE = 8;

    public static StoreConfig defaults() {
        return new StoreConfig("data/kv-store", 4096, 16 * 1024 * 1024, 10_000);
    }

    public int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
