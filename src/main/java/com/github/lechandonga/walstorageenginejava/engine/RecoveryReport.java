package com.github.lechandonga.walstorageenginejava.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 打开存储时的恢复结论。
 */
public final class RecoveryReport {

    public enum Outcome {
        /** 文件不存在或为空，从零开始。 */
        EMPTY,
        /** 完整读完，没有发现损坏。 */
        CLEAN,
        /** 尾部存在截断/损坏记录，已安全截断到最后一个完好记录之后。 */
        TRUNCATED,
        /** 存储格式版本无法识别，拒绝打开。 */
        UNSUPPORTED_VERSION
    }

    private final Outcome outcome;
    private final long validBytes;
    private final long discardedBytes;
    private final long replayedBatches;
    private final List<String> messages = new ArrayList<>();

    public RecoveryReport(Outcome outcome, long validBytes, long discardedBytes, long replayedBatches) {
        this.outcome = outcome;
        this.validBytes = validBytes;
        this.discardedBytes = discardedBytes;
        this.replayedBatches = replayedBatches;
        this.messages.add(describe());
    }

    public Outcome outcome() {
        return outcome;
    }

    public long validBytes() {
        return validBytes;
    }

    public long discardedBytes() {
        return discardedBytes;
    }

    public long replayedBatches() {
        return replayedBatches;
    }

    public List<String> messages() {
        return messages;
    }

    public RecoveryReport addMessage(String message) {
        messages.add(message);
        return this;
    }

    public String describe() {
        return switch (outcome) {
            case EMPTY -> "数据文件不存在或为空，以空存储启动";
            case CLEAN -> "日志完整无损坏，顺序回放 " + replayedBatches + " 个已确认批次，有效字节 " + validBytes;
            case TRUNCATED -> "日志尾部存在截断或损坏（丢弃 " + discardedBytes + " 字节，保留 "
                    + validBytes + " 字节），已回放 " + replayedBatches + " 个完好批次并截断到最后一个完好位置";
            case UNSUPPORTED_VERSION -> "数据文件格式版本无法识别，拒绝猜测解析";
        };
    }

    @Override
    public String toString() {
        return describe();
    }
}
