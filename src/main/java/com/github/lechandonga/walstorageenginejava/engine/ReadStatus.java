package com.github.lechandonga.walstorageenginejava.engine;

/**
 * 读取结论：OK（存在）、NOT_FOUND（从未写入或已删除）、REJECTED（写入被拒）。
 */
public enum ReadStatus {
    OK,
    NOT_FOUND,
    REJECTED
}
