package com.github.lechandonga.walstorageenginejava.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.CRC32;

/**
 * 基于预写日志（WAL）的本地键值存储。
 *
 * <p>写入流程（写锁内）：校验整个批次 -&gt; 组装帧 -&gt; 追加到 wal.log -&gt;
 * {@code force(true)} 落盘 -&gt; 更新内存表 -&gt; 对调用方确认。进程在任何时刻崩溃，
 * 重启回放后只会处于“最近一次已确认写入”的一致状态。</p>
 */
public final class KeyValueStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KeyValueStore.class);

    private static final byte[] MAGIC = {'W', 'A', 'L', 'K'};

    private final StoreConfig config;
    private final Path dataFile;
    private FileChannel channel;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    /** 键 -&gt; 当前条目；DELETE 以墓碑条目标记，使“从未写入”和“已删除”可区分。 */
    private final Map<String, Entry> table = new HashMap<>();

    private long durableBytes;
    private long batchSequence;
    private RecoveryReport lastRecoveryReport;

    private KeyValueStore(StoreConfig config, Path dataFile) {
        this.config = config;
        this.dataFile = dataFile;
    }

    private record Entry(RecordType type, String value) {
        static final Entry TOMBSTONE = new Entry(RecordType.DELETE, null);
    }

    record DecodedFrame(long sequence, int frameLength, List<WriteOperation> operations) {
    }

    static final class CorruptFrameException extends RuntimeException {
        CorruptFrameException(String message) {
            super(message);
        }
    }

    /**
     * 打开（或创建）数据目录下的存储，回放 WAL 并修复/拒绝异常尾部。
     */
    public static KeyValueStore open(StoreConfig config) {
        try {
            Path dir = Path.of(config.dataDirectory());
            Files.createDirectories(dir);
            Path file = dir.resolve("wal.log");
            KeyValueStore store = new KeyValueStore(config, file);
            RecoveryReport report = store.recover();
            store.channel = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            store.channel.position(store.durableBytes);
            log.info("KV 存储已打开: {}，恢复结论: {}", file.toAbsolutePath(), report.describe());
            store.lastRecoveryReport = report;
            return store;
        } catch (IOException e) {
            throw new IllegalStateException("打开 KV 存储失败: " + config.dataDirectory(), e);
        }
    }

    public RecoveryReport lastRecoveryReport() {
        return lastRecoveryReport;
    }

    // ---------------------------------------------------------------
    // 公共 API
    // ---------------------------------------------------------------

    public WriteResult put(String key, String value) {
        return writeBatch(List.of(WriteOperation.put(key, value)));
    }

    public WriteResult delete(String key) {
        return writeBatch(List.of(WriteOperation.delete(key)));
    }

    public ReadResult get(String key) {
        if (key == null || key.isEmpty()) {
            return ReadResult.rejected(String.valueOf(key), "键不能为空");
        }
        lock.readLock().lock();
        try {
            Entry entry = table.get(key);
            if (entry == null) {
                return ReadResult.notWritten(key);
            }
            if (entry.type() == RecordType.DELETE) {
                return ReadResult.deleted(key);
            }
            return ReadResult.ok(key, entry.value());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 批量写入：先整体校验，任何一个操作非法则整批拒绝（不写任何字节）；
     * 全部合法才作为一个事务帧落盘，要么整体生效要么整体不生效。
     */
    public WriteResult writeBatch(List<WriteOperation> operations) {
        List<String> problems = validate(operations);
        if (!problems.isEmpty()) {
            log.warn("批次写入被拒: {}", problems);
            return WriteResult.rejected("批次中存在非法操作，整批拒绝", problems);
        }

        List<WriteOperation> snapshot = List.copyOf(operations);

        lock.writeLock().lock();
        try {
            long sequence = batchSequence + 1;
            byte[] frame = encodeFrame(sequence, snapshot);
            try {
                appendAndForce(frame);
            } catch (IOException e) {
                log.error("批次落盘失败，整批未生效", e);
                return WriteResult.rejected("落盘失败: " + e.getMessage(), keysOf(snapshot));
            }
            batchSequence = sequence;
            apply(snapshot);
            log.debug("批次已确认: seq={}, ops={}", sequence, snapshot.size());
            return WriteResult.committed(sequence, snapshot.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 导出一致性备份：读锁内复制当前已确认的完整字节区间，
     * 备份期间的写入要么完整包含、要么完整不包含，不会出现写了一半的帧。
     */
    public Path snapshotTo(Path backupFile) throws IOException {
        lock.readLock().lock();
        try {
            Path absolute = backupFile.toAbsolutePath();
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (FileChannel out = FileChannel.open(absolute,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer header = ByteBuffer.wrap(readHeader());
                while (header.hasRemaining()) {
                    out.write(header);
                }
                ByteBuffer copyBuffer = ByteBuffer.allocate(64 * 1024);
                long position = StoreConfig.HEADER_SIZE;
                long end = durableBytes;
                while (position < end) {
                    copyBuffer.clear();
                    int read = channel.read(copyBuffer, position);
                    if (read <= 0) {
                        throw new IOException("备份复制中断，剩余 " + (end - position) + " 字节");
                    }
                    copyBuffer.flip();
                    while (copyBuffer.hasRemaining()) {
                        out.write(copyBuffer);
                    }
                    position += read;
                }
                out.force(true);
            }
            log.info("一致性备份完成: {}，{} 字节", absolute, durableBytes);
            return absolute;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 从备份恢复到新实例：把备份文件原样复制为新目录的 wal.log 后打开。
     */
    public static KeyValueStore restoreFrom(Path backupFile, StoreConfig newConfig) throws IOException {
        Path dir = Path.of(newConfig.dataDirectory());
        Files.createDirectories(dir);
        Files.copy(backupFile, dir.resolve("wal.log"), StandardCopyOption.REPLACE_EXISTING);
        log.info("已从备份 {} 恢复到 {}", backupFile.toAbsolutePath(), dir.toAbsolutePath());
        return open(newConfig);
    }

    public int size() {
        lock.readLock().lock();
        try {
            int count = 0;
            for (Entry entry : table.values()) {
                if (entry.type() == RecordType.PUT) {
                    count++;
                }
            }
            return count;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() throws IOException {
        lock.writeLock().lock();
        try {
            if (channel != null) {
                channel.force(true);
                channel.close();
                channel = null;
            }
            log.info("KV 存储已关闭");
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---------------------------------------------------------------
    // 校验
    // ---------------------------------------------------------------

    private List<String> validate(List<WriteOperation> operations) {
        List<String> problems = new ArrayList<>();
        if (operations == null || operations.isEmpty()) {
            problems.add("批次不能为空");
            return problems;
        }
        if (operations.size() > config.maxBatchOps()) {
            problems.add("批次操作数 " + operations.size() + " 超过上限 " + config.maxBatchOps());
        }
        for (WriteOperation op : operations) {
            if (op == null) {
                problems.add("批次包含 null 操作");
                continue;
            }
            if (op.key() == null || op.key().isEmpty()) {
                problems.add("存在空键操作");
                continue;
            }
            int keyBytes = config.utf8Length(op.key());
            if (keyBytes > config.maxKeyBytes()) {
                problems.add("键 [" + op.key() + "] 字节数 " + keyBytes + " 超过上限 " + config.maxKeyBytes());
            }
            if (op.type() == RecordType.PUT) {
                if (op.value() == null) {
                    problems.add("键 [" + op.key() + "] 的值为 null");
                } else {
                    int valueBytes = config.utf8Length(op.value());
                    if (valueBytes > config.maxValueBytes()) {
                        problems.add("键 [" + op.key() + "] 的值字节数 " + valueBytes
                                + " 超过上限 " + config.maxValueBytes());
                    }
                }
            }
        }
        return problems;
    }

    private static List<String> keysOf(List<WriteOperation> operations) {
        return operations.stream().map(WriteOperation::key).toList();
    }

    // ---------------------------------------------------------------
    // 编码 / 落盘
    // ---------------------------------------------------------------

    private byte[] buildHeader() {
        byte[] header = new byte[StoreConfig.HEADER_SIZE];
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        header[4] = (byte) StoreConfig.FORMAT_VERSION;
        return header;
    }

    private byte[] readHeader() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(StoreConfig.HEADER_SIZE);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, StoreConfig.HEADER_SIZE - buffer.remaining());
            if (read == -1) {
                throw new IOException("数据文件不足 8 字节文件头");
            }
        }
        return buffer.array();
    }

    /**
     * 帧格式：[长度:4][载荷:长度][CRC32(载荷):4]，长度字段包含 CRC 的 4 字节。
     * 载荷：[批次序号:8][操作数:4] 若干
     * PUT：[类型:1][键长:4][键字节][值长:4][值字节]；DELETE：[类型:1][键长:4][键字节]。
     */
    private byte[] encodeFrame(long sequence, List<WriteOperation> operations) {
        int payloadLength = 8 + 4;
        List<byte[]> keys = new ArrayList<>(operations.size());
        List<byte[]> values = new ArrayList<>(operations.size());
        for (WriteOperation op : operations) {
            byte[] keyBytes = op.key().getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = op.type() == RecordType.PUT
                    ? op.value().getBytes(StandardCharsets.UTF_8) : new byte[0];
            keys.add(keyBytes);
            values.add(valueBytes);
            payloadLength += 1 + 4 + keyBytes.length;
            if (op.type() == RecordType.PUT) {
                payloadLength += 4 + valueBytes.length;
            }
        }

        ByteBuffer payload = ByteBuffer.allocate(payloadLength);
        payload.putLong(sequence);
        payload.putInt(operations.size());
        for (int i = 0; i < operations.size(); i++) {
            WriteOperation op = operations.get(i);
            payload.put(op.type().code());
            payload.putInt(keys.get(i).length);
            payload.put(keys.get(i));
            if (op.type() == RecordType.PUT) {
                payload.putInt(values.get(i).length);
                payload.put(values.get(i));
            }
        }
        CRC32 crc = new CRC32();
        crc.update(payload.array());

        ByteBuffer frame = ByteBuffer.allocate(4 + payloadLength + 4);
        frame.putInt(payloadLength + 4);
        frame.put(payload.array());
        frame.putInt((int) crc.getValue());
        return frame.array();
    }

    private void appendAndForce(byte[] frame) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(frame);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        channel.force(true);
        durableBytes += frame.length;
    }

    private void apply(List<WriteOperation> operations) {
        for (WriteOperation op : operations) {
            if (op.type() == RecordType.PUT) {
                table.put(op.key(), new Entry(RecordType.PUT, op.value()));
            } else if (table.containsKey(op.key())) {
                // 删除仅对存在过的键留下墓碑；删除从未写入的键保持“从未写入”，保证幂等且结论可区分
                table.put(op.key(), Entry.TOMBSTONE);
            }
        }
    }

    // ---------------------------------------------------------------
    // 恢复与解码
    // ---------------------------------------------------------------

    private RecoveryReport recover() throws IOException {
        long fileSize = Files.exists(dataFile) ? Files.size(dataFile) : 0;
        if (fileSize == 0) {
            Files.write(dataFile, buildHeader());
            durableBytes = StoreConfig.HEADER_SIZE;
            return new RecoveryReport(RecoveryReport.Outcome.EMPTY, durableBytes, 0, 0);
        }
        if (fileSize < StoreConfig.HEADER_SIZE) {
            throw new IllegalStateException("数据文件损坏：文件头不足 " + StoreConfig.HEADER_SIZE + " 字节");
        }

        byte[] fileBytes = Files.readAllBytes(dataFile);
        verifyHeader(fileBytes);

        long position = StoreConfig.HEADER_SIZE;
        long batches = 0;
        while (position < fileSize) {
            DecodedFrame frame;
            try {
                frame = decodeFrame(fileBytes, position, fileSize);
            } catch (CorruptFrameException e) {
                long discarded = fileSize - position;
                truncateAt(position);
                durableBytes = position;
                RecoveryReport report = new RecoveryReport(
                        RecoveryReport.Outcome.TRUNCATED, position, discarded, batches);
                report.addMessage("偏移 " + position + " 起 " + discarded
                        + " 字节无法通过完整性校验（" + e.getMessage()
                        + "），判定为尾部截断/损坏，已截断丢弃，恢复到最后一个完好位置");
                return report;
            }
            apply(frame.operations());
            batchSequence = frame.sequence();
            batches++;
            position += frame.frameLength();
        }

        durableBytes = fileSize;
        return new RecoveryReport(RecoveryReport.Outcome.CLEAN, durableBytes, 0, batches);
    }

    private void verifyHeader(byte[] fileBytes) {
        for (int i = 0; i < MAGIC.length; i++) {
            if (fileBytes[i] != MAGIC[i]) {
                throw new IllegalStateException("数据文件魔数不匹配，不是本引擎的 WAL 文件，拒绝打开");
            }
        }
        int version = fileBytes[4] & 0xFF;
        if (version != StoreConfig.FORMAT_VERSION) {
            RecoveryReport report = new RecoveryReport(RecoveryReport.Outcome.UNSUPPORTED_VERSION,
                    0, fileBytes.length, 0);
            report.addMessage("文件头声明格式版本 " + version + "，当前引擎仅支持版本 "
                    + StoreConfig.FORMAT_VERSION + "，明确拒绝解析");
            throw new IllegalStateException(report.describe());
        }
    }

    private void truncateAt(long position) throws IOException {
        try (FileChannel fileChannel = FileChannel.open(dataFile, StandardOpenOption.WRITE)) {
            fileChannel.truncate(position);
            fileChannel.force(true);
        }
    }

    /**
     * 按帧边界严格解码：长度越界、类型未知、键值长度越界、CRC 不匹配均判定为损坏。
     */
    static DecodedFrame decodeFrame(byte[] bytes, long start, long fileSize) {
        int offset = Math.toIntExact(start);
        if (fileSize - offset < 4) {
            throw new CorruptFrameException("帧长度字段不完整（尾部截断）");
        }
        int frameLength = readInt(bytes, offset);
        if (frameLength < 8 + 4 + 4) {
            throw new CorruptFrameException("帧长度 " + frameLength + " 小于最小合法帧长");
        }
        if ((long) offset + 4L + frameLength > fileSize) {
            throw new CorruptFrameException("帧声明长度 " + frameLength
                    + " 超出文件剩余字节（尾部截断）");
        }

        int payloadLength = frameLength - 4;
        int crcOffset = offset + 4 + payloadLength;
        int expectedCrc = readInt(bytes, crcOffset);
        CRC32 crc = new CRC32();
        crc.update(bytes, offset + 4, payloadLength);
        if (expectedCrc != (int) crc.getValue()) {
            throw new CorruptFrameException("CRC32 校验失败，帧内容已损坏");
        }

        int cursor = offset + 4;
        long sequence = readLong(bytes, cursor);
        cursor += 8;
        int opCount = readInt(bytes, cursor);
        cursor += 4;
        if (opCount <= 0) {
            throw new CorruptFrameException("批次操作数非法: " + opCount);
        }

        List<WriteOperation> operations = new ArrayList<>(opCount);
        for (int i = 0; i < opCount; i++) {
            if (cursor >= crcOffset) {
                throw new CorruptFrameException("操作区越界");
            }
            RecordType type;
            try {
                type = RecordType.fromCode(bytes[cursor]);
            } catch (IllegalArgumentException e) {
                throw new CorruptFrameException(e.getMessage());
            }
            cursor += 1;
            String key = readLengthPrefixed(bytes, cursor, crcOffset, "键");
            cursor += 4 + utf8LengthAt(bytes, cursor);
            String value = null;
            if (type == RecordType.PUT) {
                value = readLengthPrefixed(bytes, cursor, crcOffset, "值");
                cursor += 4 + utf8LengthAt(bytes, cursor);
            }
            operations.add(new WriteOperation(type, key, value));
        }
        if (cursor != crcOffset) {
            throw new CorruptFrameException("帧载荷存在无法解释的多余字节");
        }
        return new DecodedFrame(sequence, 4 + frameLength, operations);
    }

    private static int utf8LengthAt(byte[] bytes, int offset) {
        return readInt(bytes, offset);
    }

    private static String readLengthPrefixed(byte[] bytes, int offset, int limit, String field) {
        if (limit - offset < 4) {
            throw new CorruptFrameException(field + "长度字段缺失");
        }
        int length = readInt(bytes, offset);
        if (length < 0 || offset + 4L + length > limit) {
            throw new CorruptFrameException(field + "长度 " + length + " 越界");
        }
        return new String(bytes, offset + 4, length, StandardCharsets.UTF_8);
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    private static long readLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xFFL);
        }
        return value;
    }
}
