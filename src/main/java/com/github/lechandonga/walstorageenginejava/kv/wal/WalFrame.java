package com.github.lechandonga.walstorageenginejava.kv.wal;

import com.github.lechandonga.walstorageenginejava.kv.model.KvOperation;

import java.util.List;

/**
 * 一条 WAL 帧：一次原子写入（单键写/删，或一个批量写）。
 *
 * @param commit false 表示批量写尚未提交的片段，崩溃恢复时必须丢弃
 */
public record WalFrame(long batchId, boolean commit, List<KvOperation> operations) {
}
