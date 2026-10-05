package com.github.lechandonga.walstorageenginejava.kv.model;

/**
 * 启动恢复结论。
 *
 * @param lastGoodOffset 最后一个完好记录之后的文件偏移（尾部损坏会被截断到该位置）
 * @param validRecords   回放并生效的完整提交记录数
 * @param appliedBatches 生效的批次数
 * @param truncatedBytes 因损坏或半截记录被丢弃的尾部字节数；0 表示日志完好
 * @param reason         人类可读的恢复说明
 */
public record RecoveryReport(boolean clean,
                             long lastGoodOffset,
                             int validRecords,
                             int appliedBatches,
                             long truncatedBytes,
                             String reason) {
}
