package com.github.shikejing.reportenginejava.engine.stream;

/**
 * 可重复打开的行输入源。
 *
 * <p>同一份渲染输入可能需要被多趟读取（例如先整体校验、再排序 / 输出），
 * 每趟调用 {@link #open()} 都从第 0 行开始返回一个新的 {@link RowCursor}。
 *
 * <p>输入源本身由磁盘（JSON Lines 落盘文件）支撑，不在内存中持有全部行，
 * 因此重复打开不会增加与输入总行数相关的常驻内存。
 */
@FunctionalInterface
public interface RowInput {

    /** 从第 0 行开始打开一个新的读取游标。 */
    RowCursor open() throws java.io.IOException;
}
