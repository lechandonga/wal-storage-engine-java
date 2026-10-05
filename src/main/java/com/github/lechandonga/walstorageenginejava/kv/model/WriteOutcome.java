package com.github.lechandonga.walstorageenginejava.kv.model;

/**
 * 写入/删除结论。
 */
public enum WriteOutcome {
    /** 状态发生变化（新写入、覆盖、删除）。 */
    APPLIED,
    /** 幂等重复操作：重复写入相同值，或删除一个已删除/不存在的键。 */
    IDEMPOTENT_NOOP
}
