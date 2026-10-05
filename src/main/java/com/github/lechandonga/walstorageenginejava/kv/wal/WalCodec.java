package com.github.lechandonga.walstorageenginejava.kv.wal;

import com.github.lechandonga.walstorageenginejava.kv.model.KvOperation;
import com.github.lechandonga.walstorageenginejava.kv.model.OpType;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * WAL 文件与帧的二进制编解码。所有多字节整数采用小端序。
 *
 * <p>文件头（12 字节）：</p>
 * <pre>
 * 偏移 0  magic   int32 = 0x57414C4A ("WALJ")
 * 偏移 4  version int32，当前为 {@link #FORMAT_VERSION}
 * 偏移 8  reserved int32，必须为 0
 * </pre>
 *
 * <p>帧布局：</p>
 * <pre>
 * magic       int32 = 0x57414C46 ("WALF")
 * totalLength int32  从 batchId 到帧尾（含 CRC）的总字节数
 * batchId     int64  单调递增的批次编号
 * commit      byte   1=提交，0=批量写的未提交片段
 * opCount     int32
 * ops[]       每个操作：opType byte, keyLen int32, key UTF-8, valLen int32(DELETE 为 -1), value
 * crc32       int32  对 totalLength 之后、crc 之前的全部字节计算
 * </pre>
 */
public final class WalCodec {

    public static final int HEADER_SIZE = 12;
    public static final int FORMAT_VERSION = 1;

    public static final int FILE_MAGIC = 0x57414C4A;
    public static final int FRAME_MAGIC = 0x57414C46;

    private static final int MAX_KEY_BYTES = 1 << 20;       // 1 MiB
    private static final int MAX_VALUE_BYTES = 64 << 20;   // 64 MiB
    private static final int MAX_FRAME_BYTES = 256 << 20;  // 256 MiB

    private WalCodec() {
    }

    public static byte[] fileHeader() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(FILE_MAGIC);
        buffer.putInt(FORMAT_VERSION);
        buffer.putInt(0);
        return buffer.array();
    }

    /**
     * 校验并解析文件头，不支持的版本抛出 {@link WalFormatException}。
     */
    public static void parseFileHeader(byte[] header) {
        if (header.length < HEADER_SIZE) {
            throw new WalCorruptionException("文件头不完整（" + header.length + " 字节），无法形成任何已确认写入");
        }
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buffer.getInt();
        if (magic != FILE_MAGIC) {
            throw new WalCorruptionException("文件 magic 不匹配：0x" + Integer.toHexString(magic));
        }
        int version = buffer.getInt();
        if (version != FORMAT_VERSION) {
            throw new WalFormatException("不支持的存储格式版本: " + version
                    + "，当前软件仅支持版本 " + FORMAT_VERSION + "，拒绝猜测解析");
        }
    }

    public static byte[] encode(WalFrame frame) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        putLong(body, frame.batchId());
        body.write(frame.commit() ? 1 : 0);
        putInt(body, frame.operations().size());
        for (KvOperation op : frame.operations()) {
            byte[] keyBytes = op.key().getBytes(StandardCharsets.UTF_8);
            body.write(op.type().code());
            putInt(body, keyBytes.length);
            body.writeBytes(keyBytes);
            if (op.type() == OpType.DELETE) {
                putInt(body, -1);
            } else {
                putInt(body, op.value().length);
                body.writeBytes(op.value());
            }
        }
        byte[] bodyBytes = body.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(bodyBytes);

        ByteBuffer out = ByteBuffer
                .allocate(4 + 4 + bodyBytes.length + 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(FRAME_MAGIC);
        out.putInt(bodyBytes.length + 4);
        out.put(bodyBytes);
        out.putInt((int) crc.getValue());
        return out.array();
    }

    /**
     * 从给定偏移解析一帧。
     *
     * @return 解析结果（帧与下一帧偏移）；文件结束时返回 null
     * @throws WalCorruptionException magic/长度/CRC 校验失败或帧不完整
     */
    public static DecodedFrame decode(byte[] data, int offset) {
        if (offset >= data.length) {
            return null;
        }
        if (offset + 8 > data.length) {
            throw new WalCorruptionException("偏移 " + offset + " 处帧头被截断（仅剩 "
                    + (data.length - offset) + " 字节）");
        }
        ByteBuffer head = slice(data, offset, 8);
        int magic = head.getInt();
        if (magic != FRAME_MAGIC) {
            throw new WalCorruptionException("偏移 " + offset + " 处 magic 不匹配：0x"
                    + Integer.toHexString(magic));
        }
        int totalLength = head.getInt();
        if (totalLength < 8 + 4 || totalLength > MAX_FRAME_BYTES) {
            throw new WalCorruptionException("偏移 " + offset + " 处帧长度非法: " + totalLength);
        }
        int frameEnd = offset + 8 + totalLength;
        if (frameEnd > data.length) {
            throw new WalCorruptionException("偏移 " + offset + " 处帧体被截断，需要 "
                    + totalLength + " 字节，实际仅剩 " + (data.length - offset - 8) + " 字节");
        }

        int bodyLen = totalLength - 4;
        int bodyStart = offset + 8;
        CRC32 crc = new CRC32();
        crc.update(data, bodyStart, bodyLen);
        int expected = ByteBuffer.wrap(data, frameEnd - 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if ((int) crc.getValue() != expected) {
            throw new WalCorruptionException("偏移 " + offset + " 处帧 CRC32 校验失败");
        }

        ByteBuffer body = slice(data, bodyStart, bodyLen);
        long batchId = body.getLong();
        boolean commit = body.get() == 1;
        int opCount = body.getInt();
        if (opCount <= 0 || opCount > 1_000_000) {
            throw new WalCorruptionException("偏移 " + offset + " 处操作数非法: " + opCount);
        }
        List<KvOperation> operations = new ArrayList<>(opCount);
        for (int i = 0; i < opCount; i++) {
            byte typeCode = body.get();
            OpType type;
            try {
                type = OpType.fromCode(typeCode);
            } catch (IllegalArgumentException e) {
                throw new WalCorruptionException("偏移 " + offset + " 处操作类型非法: " + typeCode);
            }
            int keyLen = body.getInt();
            if (keyLen <= 0 || keyLen > MAX_KEY_BYTES || keyLen > body.remaining()) {
                throw new WalCorruptionException("偏移 " + offset + " 处键长度非法: " + keyLen);
            }
            byte[] keyBytes = new byte[keyLen];
            body.get(keyBytes);
            int valueLen = body.getInt();
            byte[] value = null;
            if (type == OpType.PUT) {
                if (valueLen < 0 || valueLen > MAX_VALUE_BYTES || valueLen > body.remaining()) {
                    throw new WalCorruptionException("偏移 " + offset + " 处值长度非法: " + valueLen);
                }
                value = new byte[valueLen];
                body.get(value);
            } else if (valueLen != -1) {
                throw new WalCorruptionException("偏移 " + offset + " 处 DELETE 携带了值长度: " + valueLen);
            }
            operations.add(new KvOperation(type, new String(keyBytes, StandardCharsets.UTF_8), value));
        }
        if (body.remaining() != 0) {
            throw new WalCorruptionException("偏移 " + offset + " 处帧体内存在 "
                    + body.remaining() + " 个多余字节");
        }
        return new DecodedFrame(new WalFrame(batchId, commit, List.copyOf(operations)), frameEnd);
    }

    public record DecodedFrame(WalFrame frame, int nextOffset) {
    }

    private static ByteBuffer slice(byte[] data, int offset, int length) {
        return ByteBuffer.wrap(data, offset, length).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void putInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void putLong(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) {
            out.write((int) (value >>> (i * 8)) & 0xFF);
        }
    }
}
