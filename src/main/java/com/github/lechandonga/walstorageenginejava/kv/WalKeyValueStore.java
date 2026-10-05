package com.github.lechandonga.walstorageenginejava.kv;

import com.github.lechandonga.walstorageenginejava.kv.model.Entry;
import com.github.lechandonga.walstorageenginejava.kv.model.GetResult;
import com.github.lechandonga.walstorageenginejava.kv.model.KvOperation;
import com.github.lechandonga.walstorageenginejava.kv.model.OpType;
import com.github.lechandonga.walstorageenginejava.kv.model.RecoveryReport;
import com.github.lechandonga.walstorageenginejava.kv.model.WriteOutcome;
import com.github.lechandonga.walstorageenginejava.kv.wal.WalCodec;
import com.github.lechandonga.walstorageenginejava.kv.wal.WalCorruptionException;
import com.github.lechandonga.walstorageenginejava.kv.wal.WalFrame;
import com.github.lechandonga.walstorageenginejava.kv.wal.WalFormatException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 基于预写日志（WAL）的本地键值存储。
 *
 * <p>持久化顺序：帧追加到日志并 {@code force(fsync)} 成功后，才更新内存视图并对调用方确认。
 * 因此进程异常退出后，已确认的写入一定存在，未确认的写入一定不可见。</p>
 *
 * <p>并发：读操作无锁，始终看到某一个已提交版本的不可变快照；写操作串行化；
 * 备份在与写互斥的读锁内完成，保证是某个时刻的一致视图。</p>
 */
public class WalKeyValueStore implements KeyValueStore {

    private final Path logFile;
    private final long maxLogBytes;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile Map<String, Entry> snapshot = Map.of();
    private long nextBatchId = 1;
    private long logSize;
    private FileChannel channel;
    private RecoveryReport recoveryReport;

    public WalKeyValueStore(Path directory, long maxLogBytes) {
        if (maxLogBytes <= 0) {
            throw new IllegalArgumentException("maxLogBytes 必须为正数");
        }
        this.maxLogBytes = maxLogBytes;
        try {
            Files.createDirectories(directory);
            this.logFile = directory.resolve("kv.wal");
            openAndRecover();
        } catch (WalFormatException e) {
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException("无法打开存储目录: " + directory, e);
        }
    }

    public RecoveryReport getRecoveryReport() {
        return recoveryReport;
    }

    // ---------------------------------------------------------------- 读

    @Override
    public GetResult get(String key) {
        validateKey(key);
        Entry entry = snapshot.get(key);
        if (entry == null) {
            return GetResult.NOT_FOUND;
        }
        if (entry.tombstone()) {
            return GetResult.DELETED;
        }
        return GetResult.found(entry.value().clone());
    }

    // ---------------------------------------------------------------- 写

    @Override
    public WriteOutcome put(String key, byte[] value) {
        validateKey(key);
        Objects.requireNonNull(value, "value 不能为 null");
        return commit(List.of(KvOperation.put(key, value.clone())));
    }

    @Override
    public WriteOutcome delete(String key) {
        validateKey(key);
        return commit(List.of(KvOperation.delete(key)));
    }

    @Override
    public WriteOutcome batch(List<KvOperation> operations) {
        Objects.requireNonNull(operations, "operations 不能为 null");
        if (operations.isEmpty()) {
            throw new WriteRejectedException("批量操作不能为空");
        }
        List<KvOperation> validated = new ArrayList<>(operations.size());
        java.util.HashSet<String> seenKeys = new java.util.HashSet<>();
        for (KvOperation op : operations) {
            Objects.requireNonNull(op, "批量操作中的元素不能为 null");
            validateKey(op.key());
            if (!seenKeys.add(op.key())) {
                throw new WriteRejectedException("批量操作中键重复: " + op.key() + "，写入被拒绝");
            }
            if (op.type() == OpType.PUT) {
                Objects.requireNonNull(op.value(), "PUT 操作必须携带值");
                validated.add(KvOperation.put(op.key(), op.value().clone()));
            } else {
                validated.add(KvOperation.delete(op.key()));
            }
        }
        return commit(validated);
    }

    /**
     * 原子提交：整组操作编码成一帧写入，fsync 成功后一次性应用到内存视图。
     * 前置校验失败或 IO 失败都不会改变现有状态（整体不生效）。
     */
    private WriteOutcome commit(List<KvOperation> operations) {
        lock.writeLock().lock();
        try {
            ensureOpen();
            Map<String, Entry> current = snapshot;
            boolean changed = false;
            for (KvOperation op : operations) {
                Entry existing = current.get(op.key());
                if (op.type() == OpType.PUT) {
                    if (existing == null || existing.tombstone()
                            || !Arrays.equals(existing.value(), op.value())) {
                        changed = true;
                    }
                } else if (existing != null && !existing.tombstone()) {
                    changed = true;
                }
            }

            long batchId = nextBatchId++;
            appendAndForce(new WalFrame(batchId, true, operations));

            Map<String, Entry> next = new HashMap<>(current);
            for (KvOperation op : operations) {
                if (op.type() == OpType.PUT) {
                    next.put(op.key(), Entry.put(op.value(), batchId));
                } else {
                    next.put(op.key(), Entry.tombstone(batchId));
                }
            }
            snapshot = Map.copyOf(next);

            if (logSize > maxLogBytes) {
                rewriteLog();
            }
            return changed ? WriteOutcome.APPLIED : WriteOutcome.IDEMPOTENT_NOOP;
        } catch (IOException e) {
            throw new WriteRejectedException("写入已被拒绝：日志持久化失败（状态未改变）", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---------------------------------------------------------------- 备份

    /**
     * 一致性备份：输出一个自包含的 WAL（标准文件头 + 一帧快照），
     * 可直接作为新实例的数据文件打开。空库写入一个内部标记帧以保持格式非空。
     */
    @Override
    public void backupTo(Path backupFile) {
        Objects.requireNonNull(backupFile, "backupFile 不能为 null");
        lock.readLock().lock();
        try {
            ensureOpen();
            Map<String, Entry> view = snapshot;
            Path parent = backupFile.toAbsolutePath().normalize().getParent();
            Files.createDirectories(parent);
            Path temp = backupFile.resolveSibling(backupFile.getFileName() + ".tmp");
            try (FileChannel out = FileChannel.open(temp,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write(ByteBuffer.wrap(WalCodec.fileHeader()));
                List<KvOperation> ops = new ArrayList<>(view.size());
                for (Map.Entry<String, Entry> e : new TreeMap<>(view).entrySet()) {
                    if (!e.getValue().tombstone()) {
                        ops.add(KvOperation.put(e.getKey(), e.getValue().value()));
                    }
                }
                if (ops.isEmpty()) {
                    ops.add(KvOperation.put("__backup_empty__", new byte[0]));
                }
                out.write(ByteBuffer.wrap(WalCodec.encode(new WalFrame(nextBatchId, true, ops))));
                out.force(true);
            }
            Files.move(temp, backupFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("一致性备份失败: " + backupFile, e);
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---------------------------------------------------------------- 恢复

    private void openAndRecover() throws IOException {
        if (!Files.exists(logFile)) {
            channel = FileChannel.open(logFile,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            channel.write(ByteBuffer.wrap(WalCodec.fileHeader()));
            channel.force(true);
            logSize = WalCodec.HEADER_SIZE;
            recoveryReport = new RecoveryReport(true, logSize, 0, 0, 0,
                    "数据文件不存在，已创建全新的空日志");
            return;
        }

        byte[] data = Files.readAllBytes(logFile);
        if (data.length == 0) {
            throw new WalCorruptionException("数据文件存在但为空，缺少文件头");
        }
        WalCodec.parseFileHeader(data);

        Map<String, Entry> recovered = new HashMap<>();
        int validRecords = 0;
        int appliedBatches = 0;
        int droppedUncommitted = 0;
        long maxBatchId = 0;
        int offset = WalCodec.HEADER_SIZE;
        int lastGoodOffset = offset;
        String corruptionReason = null;

        while (offset < data.length) {
            WalCodec.DecodedFrame decoded;
            try {
                decoded = WalCodec.decode(data, offset);
            } catch (WalCorruptionException e) {
                corruptionReason = e.getMessage();
                break;
            }
            if (decoded == null) {
                break;
            }
            WalFrame frame = decoded.frame();
            validRecords++;
            if (!frame.commit()) {
                droppedUncommitted++;
            } else {
                for (KvOperation op : frame.operations()) {
                    if (op.type() == OpType.PUT) {
                        recovered.put(op.key(), Entry.put(op.value(), frame.batchId()));
                    } else {
                        recovered.put(op.key(), Entry.tombstone(frame.batchId()));
                    }
                }
                appliedBatches++;
                maxBatchId = Math.max(maxBatchId, frame.batchId());
            }
            offset = decoded.nextOffset();
            lastGoodOffset = offset;
        }

        long truncated = data.length - lastGoodOffset;
        if (truncated > 0) {
            try (FileChannel ch = FileChannel.open(logFile, StandardOpenOption.WRITE)) {
                ch.truncate(lastGoodOffset);
                ch.force(true);
            }
        }

        snapshot = Map.copyOf(recovered);
        nextBatchId = maxBatchId + 1;
        logSize = lastGoodOffset;
        channel = FileChannel.open(logFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND);

        boolean clean = truncated == 0 && droppedUncommitted == 0;
        String reason;
        if (clean) {
            reason = "日志完好，已恢复到最后一次已确认写入，共 " + appliedBatches + " 个批次生效";
        } else {
            StringBuilder sb = new StringBuilder();
            if (truncated > 0) {
                sb.append("检测到尾部损坏或半截记录（").append(corruptionReason)
                        .append("），已安全截断 ").append(truncated).append(" 字节并回到最后一个完好位置；");
            }
            if (droppedUncommitted > 0) {
                sb.append("丢弃 ").append(droppedUncommitted).append(" 条未提交帧；");
            }
            sb.append("共 ").append(appliedBatches).append(" 个已确认批次生效");
            reason = sb.toString();
        }
        recoveryReport = new RecoveryReport(clean, lastGoodOffset, validRecords,
                appliedBatches, truncated, reason);
    }

    // ---------------------------------------------------------------- 内部工具

    private void appendAndForce(WalFrame frame) throws IOException {
        byte[] bytes = WalCodec.encode(frame);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        channel.force(true);
        logSize += bytes.length;
    }

    /**
     * 日志超过阈值后压缩：用当前存活快照重写一个全新的 WAL 再原子替换。
     */
    private void rewriteLog() throws IOException {
        Path compacted = logFile.resolveSibling("kv.wal.compacted");
        try (FileChannel out = FileChannel.open(compacted,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            out.write(ByteBuffer.wrap(WalCodec.fileHeader()));
            List<KvOperation> live = new ArrayList<>();
            for (Map.Entry<String, Entry> e : new TreeMap<>(snapshot).entrySet()) {
                if (!e.getValue().tombstone()) {
                    live.add(KvOperation.put(e.getKey(), e.getValue().value()));
                }
            }
            if (!live.isEmpty()) {
                out.write(ByteBuffer.wrap(WalCodec.encode(new WalFrame(nextBatchId - 1, true, live))));
            }
            out.force(true);
        }
        channel.close();
        Files.move(compacted, logFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        channel = FileChannel.open(logFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        logSize = Files.size(logFile);
    }

    private void ensureOpen() {
        if (!channel.isOpen()) {
            throw new WriteRejectedException("存储实例已关闭，写入被拒绝");
        }
    }

    private static void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new WriteRejectedException("键不能为空，写入被拒绝");
        }
        int utf8Length = key.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Length > (1 << 20)) {
            throw new WriteRejectedException("键长度超过 1 MiB 限制，写入被拒绝");
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (channel != null && channel.isOpen()) {
                channel.force(true);
                channel.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("关闭存储失败", e);
        } finally {
            lock.writeLock().unlock();
        }
    }
}
