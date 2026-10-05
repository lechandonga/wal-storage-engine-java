package com.github.lechandonga.walstorageenginejava.engine;

/**
 * 读取结果。三种 {@link ReadStatus} 对调用方可明确区分。
 */
public record ReadResult(ReadStatus status, String key, String value, String reason) {

    static ReadResult ok(String key, String value) {
        return new ReadResult(ReadStatus.OK, key, value, null);
    }

    static ReadResult notFound(String key) {
        return new ReadResult(ReadStatus.NOT_FOUND, key, null, "键不存在或已删除");
    }

    static ReadResult notWritten(String key) {
        return new ReadResult(ReadStatus.NOT_FOUND, key, null, "键从未写入");
    }

    static ReadResult deleted(String key) {
        return new ReadResult(ReadStatus.NOT_FOUND, key, null, "键存在过但已被删除");
    }

    static ReadResult rejected(String key, String reason) {
        return new ReadResult(ReadStatus.REJECTED, key, null, reason);
    }

    public boolean present() {
        return status == ReadStatus.OK;
    }
}
