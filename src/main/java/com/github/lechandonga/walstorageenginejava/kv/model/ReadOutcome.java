package com.github.lechandonga.walstorageenginejava.kv.model;

/**
 * 读取结论，三种状态必须可区分。
 */
public enum ReadOutcome {
    /** 键存在，携带值。 */
    FOUND,
    /** 键曾经写入过但已被删除。 */
    DELETED,
    /** 键从未写入过。 */
    NOT_FOUND
}
