package com.github.lechandonga.walstorageenginejava.kv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 本地键值存储配置项，前缀 {@code kv.store}。
 */
@ConfigurationProperties(prefix = "kv.store")
public class KvStoreProperties {

    /** WAL 数据目录，kv.wal 位于其中。 */
    private Path directory = Path.of("data", "kvstore");

    /** WAL 超过该字节数后在写入间隙自动压缩（重写为快照）。 */
    private long maxLogBytes = 64L * 1024 * 1024;

    /** 是否在启动时暴露本地 REST 接口。 */
    private boolean webEnabled = true;

    public Path getDirectory() {
        return directory;
    }

    public void setDirectory(Path directory) {
        this.directory = directory;
    }

    public long getMaxLogBytes() {
        return maxLogBytes;
    }

    public void setMaxLogBytes(long maxLogBytes) {
        this.maxLogBytes = maxLogBytes;
    }

    public boolean isWebEnabled() {
        return webEnabled;
    }

    public void setWebEnabled(boolean webEnabled) {
        this.webEnabled = webEnabled;
    }
}
