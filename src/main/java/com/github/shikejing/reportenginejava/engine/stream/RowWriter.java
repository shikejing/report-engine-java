package com.github.shikejing.reportenginejava.engine.stream;

import java.io.Closeable;
import java.io.IOException;

/** 行写出器：将行顺序追加到磁盘上的行文件。 */
public interface RowWriter extends Closeable {

    void write(IndexedRow row) throws IOException;

    @Override
    void close() throws IOException;
}
