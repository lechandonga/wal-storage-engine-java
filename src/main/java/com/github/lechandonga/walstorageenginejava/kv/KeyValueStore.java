package com.github.lechandonga.walstorageenginejava.kv;

import com.github.lechandonga.walstorageenginejava.kv.model.GetResult;
import com.github.lechandonga.walstorageenginejava.kv.model.KvOperation;
import com.github.lechandonga.walstorageenginejava.kv.model.WriteOutcome;

import java.nio.file.Path;
import java.util.List;

/**
 * 本地键值存储：所有写操作先持久化并 fsync，再对调用方确认成功。
 */
public interface KeyValueStore extends AutoCloseable {

    GetResult get(String key);

    WriteOutcome put(String key, byte[] value);

    WriteOutcome delete(String key);

    /**
     * 原子批量写：所有操作要么整体生效，要么整体不生效。
     */
    WriteOutcome batch(List<KvOperation> operations);

    /**
     * 一致性备份：生成的文件是某一时刻的完整一致视图。
     * 备份期间发生的写入要么完整包含，要么完全不包含。
     */
    void backupTo(Path backupFile);

    @Override
    void close();
}
