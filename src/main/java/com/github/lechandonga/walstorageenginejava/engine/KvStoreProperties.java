package com.github.lechandonga.walstorageenginejava.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * KV 存储配置项，前缀 kv.store。
 */
@ConfigurationProperties(prefix = "kv.store")
public class KvStoreProperties {

    /** 数据目录，WAL 文件 wal.log 位于其中。 */
    private String dataDir = "data/kv-store";

    /** 单个键最大 UTF-8 字节数。 */
    private int maxKeyBytes = 4096;

    /** 单个值最大 UTF-8 字节数。 */
    private int maxValueBytes = 16 * 1024 * 1024;

    /** 单个批次最大操作数。 */
    private int maxBatchOps = 10_000;

    public StoreConfig toConfig() {
        return new StoreConfig(dataDir, maxKeyBytes, maxValueBytes, maxBatchOps);
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public int getMaxKeyBytes() {
        return maxKeyBytes;
    }

    public void setMaxKeyBytes(int maxKeyBytes) {
        this.maxKeyBytes = maxKeyBytes;
    }

    public int getMaxValueBytes() {
        return maxValueBytes;
    }

    public void setMaxValueBytes(int maxValueBytes) {
        this.maxValueBytes = maxValueBytes;
    }

    public int getMaxBatchOps() {
        return maxBatchOps;
    }

    public void setMaxBatchOps(int maxBatchOps) {
        this.maxBatchOps = maxBatchOps;
    }
}
