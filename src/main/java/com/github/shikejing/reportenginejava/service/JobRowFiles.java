package com.github.shikejing.reportenginejava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.reportenginejava.engine.stream.IndexedRow;
import com.github.shikejing.reportenginejava.engine.stream.RowReader;
import com.github.shikejing.reportenginejava.engine.stream.RowWriter;
import com.github.shikejing.reportenginejava.engine.stream.SortIO;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 作业行文件 IO：输入行文件与外部排序归并段统一使用 JSON-lines 格式，
 * 每行形如 {@code <原始序号>\t<紧凑 JSON 对象>}（非对象行的负载为空串）。
 *
 * <p>读游标只持有当前行字符串，写游标使用小块缓冲，内存占用不随行数增长。
 */
final class JobRowFiles {

    private JobRowFiles() {
    }

    /** 打开一个行文件用于顺序读取。 */
    static RowReader reader(ObjectMapper mapper, Path file) throws IOException {
        return new LineRowReader(mapper, Files.newBufferedReader(file, StandardCharsets.UTF_8));
    }

    /** 创建 / 覆盖一个行文件用于顺序写出。 */
    static RowWriter writer(ObjectMapper mapper, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return new LineRowWriter(mapper, Files.newBufferedWriter(file, StandardCharsets.UTF_8), file);
    }

    /**
     * 基于工作目录的外部排序归并段存储。归并段命名为 {@code run-00000001.jsonl}，
     * 编号由渲染器在趟次间全局分配，删除随中间段消费进行。
     */
    static final class FileSortIO implements SortIO {

        private final ObjectMapper mapper;
        private final Path workDir;

        FileSortIO(ObjectMapper mapper, Path workDir) {
            this.mapper = mapper;
            this.workDir = workDir;
        }

        @Override
        public RowReader openInput(Path inputFile) throws IOException {
            return JobRowFiles.reader(mapper, inputFile);
        }

        @Override
        public RowWriter createRun(int runId) throws IOException {
            return JobRowFiles.writer(mapper, runFile(runId));
        }

        @Override
        public RowReader openRun(int runId) throws IOException {
            return JobRowFiles.reader(mapper, runFile(runId));
        }

        @Override
        public void deleteRun(int runId) throws IOException {
            Files.deleteIfExists(runFile(runId));
        }

        /** 打开任意行文件（用于读取作业输入行文件）。 */
        RowReader openFile(Path file) throws IOException {
            return JobRowFiles.reader(mapper, file);
        }

        private Path runFile(int runId) {
            return workDir.resolve(String.format("run-%08d.jsonl", runId));
        }
    }

    private static final class LineRowWriter implements RowWriter {

        private final ObjectMapper mapper;
        private final BufferedWriter out;
        private final Path file;

        LineRowWriter(ObjectMapper mapper, BufferedWriter out, Path file) {
            this.mapper = mapper;
            this.out = out;
            this.file = file;
        }

        @Override
        public void write(IndexedRow row) throws IOException {
            out.write(Long.toString(row.getSeq()));
            out.write('\t');
            Map<String, Object> data = row.getRow();
            if (data != null) {
                out.write(mapper.writeValueAsString(data));
            }
            out.write('\n');
        }

        @Override
        public void close() throws IOException {
            out.close();
        }

        @Override
        public String toString() {
            return file.toString();
        }
    }

    private static final class LineRowReader implements RowReader {

        private final ObjectMapper mapper;
        private final BufferedReader in;
        private String pending;

        LineRowReader(ObjectMapper mapper, BufferedReader in) throws IOException {
            this.mapper = mapper;
            this.in = in;
            this.pending = in.readLine();
        }

        @Override
        public boolean hasNext() {
            return pending != null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public IndexedRow next() throws IOException {
            String line = pending;
            if (line == null) {
                throw new java.util.NoSuchElementException();
            }
            int tab = line.indexOf('\t');
            long seq = Long.parseLong(line.substring(0, tab));
            String payload = line.substring(tab + 1);
            Map<String, Object> row;
            if (payload.isEmpty()) {
                row = null;
            } else {
                row = mapper.readValue(payload, Map.class);
            }
            pending = in.readLine();
            return new IndexedRow(seq, row);
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
