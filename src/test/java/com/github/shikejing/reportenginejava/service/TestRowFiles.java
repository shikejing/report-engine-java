package com.github.shikejing.reportenginejava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.reportenginejava.engine.stream.RowReader;
import com.github.shikejing.reportenginejava.engine.stream.RowWriter;
import com.github.shikejing.reportenginejava.engine.stream.SortIO;

import java.nio.file.Path;

/**
 * 测试支撑：把包私有的 {@link JobRowFiles} 行文件 IO 暴露给其它测试包
 * （生产代码的可见性保持不变）。
 */
public final class TestRowFiles {

    private TestRowFiles() {
    }

    public static RowWriter writer(ObjectMapper mapper, Path file) throws Exception {
        return JobRowFiles.writer(mapper, file);
    }

    public static RowReader reader(ObjectMapper mapper, Path file) throws Exception {
        return JobRowFiles.reader(mapper, file);
    }

    public static SortIO sortIO(ObjectMapper mapper, Path workDir) {
        return new JobRowFiles.FileSortIO(mapper, workDir);
    }
}
