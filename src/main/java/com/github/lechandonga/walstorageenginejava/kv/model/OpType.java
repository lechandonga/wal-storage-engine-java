package com.github.lechandonga.walstorageenginejava.kv.model;

/**
 * WAL 记录中单个键操作的类型。
 */
public enum OpType {
    PUT((byte) 1),
    DELETE((byte) 2);

    private final byte code;

    OpType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static OpType fromCode(byte code) {
        return switch (code) {
            case 1 -> PUT;
            case 2 -> DELETE;
            default -> throw new IllegalArgumentException("未知操作类型码: " + code);
        };
    }
}
