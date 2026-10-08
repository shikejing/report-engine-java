package com.github.shikejing.reportenginejava.engine.stream;

import java.io.Closeable;
import java.io.IOException;

/** 行游标：顺序读取磁盘上的行文件。只持有当前行，不随总行数占内存。 */
public interface RowReader extends Closeable {

    boolean hasNext() throws IOException;

    IndexedRow next() throws IOException;

    @Override
    void close() throws IOException;
}
