package com.github.lechandonga.walstorageenginejava.kv.wal;

/**
 * WAL 帧无法通过完整性校验（magic 不匹配、长度越界、CRC 错误、半截帧）。
 */
public class WalCorruptionException extends RuntimeException {

    public WalCorruptionException(String message) {
        super(message);
    }
}
