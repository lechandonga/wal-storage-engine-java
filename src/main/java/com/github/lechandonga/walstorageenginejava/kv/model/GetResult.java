package com.github.lechandonga.walstorageenginejava.kv.model;

/**
 * 读取结果。value 仅在 outcome 为 {@link ReadOutcome#FOUND} 时非空。
 */
public record GetResult(ReadOutcome outcome, byte[] value) {

    public static GetResult found(byte[] value) {
        return new GetResult(ReadOutcome.FOUND, value);
    }

    public static final GetResult DELETED = new GetResult(ReadOutcome.DELETED, null);
    public static final GetResult NOT_FOUND = new GetResult(ReadOutcome.NOT_FOUND, null);
}
