package com.github.lechandonga.walstorageenginejava.engine;

/**
 * WAL 记录类型。
 */
public enum RecordType {
    PUT((byte) 1),
    DELETE((byte) 2);

    private final byte code;

    RecordType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static RecordType fromCode(byte code) {
        for (RecordType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知记录类型: " + code);
    }
}
