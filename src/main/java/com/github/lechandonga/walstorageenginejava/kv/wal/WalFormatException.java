package com.github.lechandonga.walstorageenginejava.kv.wal;

/**
 * 存储格式本身不受支持（例如版本号无法识别），必须明确拒绝而不是猜测解析。
 */
public class WalFormatException extends RuntimeException {

    public WalFormatException(String message) {
        super(message);
    }
}
