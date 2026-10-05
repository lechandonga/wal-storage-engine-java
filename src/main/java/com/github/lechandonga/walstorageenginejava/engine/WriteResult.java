package com.github.lechandonga.walstorageenginejava.engine;

import java.util.List;

/**
 * 写入（含批量）结论。COMMITTED 已落盘并对调用方确认；REJECTED 整批未生效。
 */
public record WriteResult(ReadStatus status, long batchSequence, int appliedOps, String reason,
                          List<String> rejectedKeys) {

    static WriteResult committed(long batchSequence, int appliedOps) {
        return new WriteResult(ReadStatus.OK, batchSequence, appliedOps, null, List.of());
    }

    public static WriteResult rejected(String reason, List<String> rejectedKeys) {
        return new WriteResult(ReadStatus.REJECTED, 0, 0, reason, List.copyOf(rejectedKeys));
    }

    public boolean committed() {
        return status == ReadStatus.OK;
    }
}
