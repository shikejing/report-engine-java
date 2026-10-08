package com.github.shikejing.reportenginejava.engine.stream;

import java.io.IOException;

/**
 * 顺序读取输入行的游标。
 *
 * <p>实现必须是流式的：游标自身只持有「当前行」，不缓冲整个输入，
 * 因此渲染期常驻内存只与单批行数相关，不随输入总行数增长。
 *
 * <p>{@link #next()} 返回 {@code Object}：正常数据行是 {@code Map<String,Object>}；
 * 若输入元素本身不是 JSON 对象（标量 / 数组），原样返回其反序列化值，
 * 由渲染器的「每行必须是对象」检查给出字段级错误。
 */
public interface RowCursor extends AutoCloseable {

    /**
     * 推进到下一行。
     *
     * @return 下一行；输入已读完时返回 {@code null}
     */
    Object next() throws IOException;

    @Override
    void close() throws IOException;
}
