package com.github.shikejing.reportenginejava.engine.stream;

import java.util.Map;

/**
 * 携带原始输入序号的数据行。
 *
 * <p>{@code seq} 为该行在输入数组中的下标（从 0 开始，全局唯一、单调）。
 * 外部归并排序在排序键相等时按 {@code seq} 决胜，严格复刻内存稳定排序的顺序。
 */
public final class IndexedRow {

    private final long seq;
    private final Map<String, Object> row;

    public IndexedRow(long seq, Map<String, Object> row) {
        this.seq = seq;
        this.row = row;
    }

    public long getSeq() {
        return seq;
    }

    public Map<String, Object> getRow() {
        return row;
    }
}
