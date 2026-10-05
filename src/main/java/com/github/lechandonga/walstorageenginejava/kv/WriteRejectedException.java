package com.github.lechandonga.walstorageenginejava.kv;

/**
 * 写入被明确拒绝（非法参数、不支持的格式版本、IO 故障等）。
 */
public class WriteRejectedException extends RuntimeException {

    public WriteRejectedException(String message) {
        super(message);
    }

    public WriteRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
