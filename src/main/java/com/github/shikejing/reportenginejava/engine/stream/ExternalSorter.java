package com.github.shikejing.reportenginejava.engine.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 有界内存的外部排序。
 *
 * <p>内存约束：无论输入多少行，排序期间常驻内存只与单段行数（{@code runSize}，
 * 取每批行数）和固定归并路数 {@link #FAN_IN} 相关，不随输入总行数增长：
 * <ol>
 *   <li><b>建段</b>：流式读入，每攒满 {@code runSize} 行就在内存中排序并
 *       顺序写入一个有序段文件（JSON Lines），随后清空缓冲；</li>
 *   <li><b>归并</b>：段数超过 1 时按固定路数做多趟 k 路归并，每路只在内存中
 *       持有「当前队首行」（用最小堆选择全局最小），每趟把段数缩减到
 *       1/{@link #FAN_IN}，直到只剩一个有序段。</li>
 * </ol>
 *
 * <p>确定性 / 稳定性：非空排序键相等时按全局输入序号（第 0 行为 0）决胜，
 * 与内存中的稳定排序结果完全一致；{@code null} 键永远排在末尾，且不受
 * 升降序方向影响。
 *
 * <p>段文件写在调用方提供的工作目录下，中间段在被下一趟消费后立即删除；
 * 返回的最终有序段由调用方在输出结束后删除。渲染失败 / 取消时工作目录会被
 * 整体丢弃。
 */
public final class ExternalSorter {

    /** 每趟归并的固定路数（与输入总行数无关的常量，决定堆中常驻的队首行数）。 */
    public static final int FAN_IN = 16;

    /** 取消 / 超时检查点的行数粒度（与每批行数无关，保证大批次下也能及时中止）。 */
    public static final int CHECKPOINT_EVERY = 256;

    private final ObjectMapper objectMapper;

    public ExternalSorter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 排序键：要么是数值（数字列 / 已折算为毫秒的日期列），要么是文本。 */
    public static final class Key implements Comparable<Key> {
        private final boolean numeric;
        private final double number;
        private final String text;

        private Key(boolean numeric, double number, String text) {
            this.numeric = numeric;
            this.number = number;
            this.text = text;
        }

        public static Key number(double value) {
            return new Key(true, value, null);
        }

        public static Key text(String value) {
            return new Key(false, 0.0, value);
        }

        @Override
        public int compareTo(Key o) {
            // 正常数据下列类型一致，两类不会相遇；相遇时给出确定顺序即可。
            if (numeric != o.numeric) {
                return numeric ? -1 : 1;
            }
            if (numeric) {
                return Double.compare(number, o.number);
            }
            return text.compareTo(o.text);
        }
    }

    /** 从一行提取非空排序键；返回 {@code null} 表示该行为空键（排末尾）。 */
    @FunctionalInterface
    public interface KeyExtractor {
        Key extract(long rowIndex, Map<String, Object> row);
    }

    /** 一条待排序记录：全局输入序号 + 原始行 + 排序键。 */
    private static final class Entry {
        final long index;
        final Map<String, Object> row;
        Key key;

        Entry(long index, Map<String, Object> row, Key key) {
            this.index = index;
            this.row = row;
            this.key = key;
        }
    }

    /** 一次排序会话（持有键提取器与方向，供建段与归并共用同一比较规则）。 */
    private final class Session {
        private final KeyExtractor extractor;
        private final boolean ascending;

        private Session(KeyExtractor extractor, boolean ascending) {
            this.extractor = extractor;
            this.ascending = ascending;
        }

        private Comparator<Entry> comparator() {
            return (a, b) -> {
                int c;
                if (a.key == null && b.key == null) {
                    c = 0;
                } else if (a.key == null) {
                    return 1; // null 永远末尾，不受方向影响
                } else if (b.key == null) {
                    return -1;
                } else {
                    c = a.key.compareTo(b.key);
                }
                if (c != 0) {
                    return ascending ? c : -c;
                }
                return Long.compare(a.index, b.index); // 稳定：相等键保持输入顺序
            };
        }

        private List<Path> buildRuns(RowInput input, int runSize, Path workDir, String prefix,
                                     Progress progress) throws IOException {
            List<Path> runs = new ArrayList<>();
            List<Entry> buffer = new ArrayList<>(runSize);
            long index = 0;
            try (RowCursor cursor = input.open()) {
                Object element;
                while ((element = cursor.next()) != null) {
                    if (index % CHECKPOINT_EVERY == 0) {
                        progress.checkpoint(index);
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) element;
                    buffer.add(new Entry(index, row, extractor.extract(index, row)));
                    index++;
                    if (buffer.size() == runSize) {
                        runs.add(flushRun(buffer, workDir, prefix, 0, runs.size()));
                        buffer.clear();
                    }
                }
                if (!buffer.isEmpty()) {
                    runs.add(flushRun(buffer, workDir, prefix, 0, runs.size()));
                    buffer.clear();
                }
            }
            return runs;
        }

        private Path flushRun(List<Entry> buffer, Path workDir, String prefix,
                              int level, int ordinal) throws IOException {
            buffer.sort(comparator());
            Path file = runFile(workDir, prefix, level, ordinal);
            writeEntries(file, buffer);
            return file;
        }

        private List<Path> mergeRound(List<Path> inputs, Path workDir, String prefix,
                                      int level, Progress progress) throws IOException {
            List<Path> outputs = new ArrayList<>();
            int ordinal = 0;
            for (int from = 0; from < inputs.size(); from += FAN_IN) {
                List<Path> group = inputs.subList(from, Math.min(from + FAN_IN, inputs.size()));
                Path out = runFile(workDir, prefix, level + 1, ordinal);
                mergeGroup(group, out, progress);
                // 本组输入段已全部消费，立即删除，磁盘占用不随趟数累积。
                for (Path consumed : group) {
                    Files.deleteIfExists(consumed);
                }
                outputs.add(out);
                ordinal++;
            }
            return outputs;
        }

        private void mergeGroup(List<Path> runs, Path output, Progress progress) throws IOException {
            List<RunReader> readers = new ArrayList<>(runs.size());
            long processed = 0;
            try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                PriorityQueue<RunReader> heap = new PriorityQueue<>(
                        Comparator.comparing((RunReader r) -> r.head, comparator()));
                for (Path run : runs) {
                    RunReader reader = new RunReader(run, this);
                    // 先登记再读取：即便首个段读取就失败，finally 也能关闭已打开的文件句柄。
                    readers.add(reader);
                    if (!reader.advance()) {
                        reader.close();
                    } else {
                        heap.add(reader);
                    }
                }
                while (!heap.isEmpty()) {
                    if (processed % CHECKPOINT_EVERY == 0) {
                        progress.checkpoint(processed);
                    }
                    RunReader smallest = heap.poll();
                    writer.write(smallest.headLine);
                    writer.write('\n');
                    processed++;
                    if (smallest.advance()) {
                        heap.add(smallest);
                    }
                }
            } finally {
                IOException failure = null;
                for (RunReader reader : readers) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        if (failure == null) {
                            failure = e;
                        }
                    }
                }
                if (failure != null) {
                    throw failure;
                }
            }
        }
    }

    /** 单个有序段的流式读取器：内存中只持有当前队首记录。 */
    private final class RunReader implements AutoCloseable {
        private final BufferedReader reader;
        private final Session session;
        private Entry head;
        private String headLine;

        RunReader(Path file, Session session) throws IOException {
            this.reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
            this.session = session;
        }

        boolean advance() throws IOException {
            String line;
            do {
                line = reader.readLine();
            } while (line != null && line.isEmpty());
            if (line == null) {
                head = null;
                headLine = null;
                return false;
            }
            headLine = line;
            JsonNode node = objectMapper.readTree(line);
            long index = node.get("i").asLong();
            @SuppressWarnings("unchecked")
            Map<String, Object> row = objectMapper.convertValue(node.get("r"), Map.class);
            // 段文件只持久化行本身；归并时按同一规则重新提取键。
            head = new Entry(index, row, session.extractor.extract(index, row));
            return true;
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    /**
     * 执行外部排序。
     *
     * @return 最终有序段文件（JSON Lines，每行一个 Entry）；输入为空时返回 {@code null}。
     *         调用方输出结束后应删除该文件。
     */
    public Path sort(RowInput input, KeyExtractor extractor, boolean ascending,
                     int runSize, Path workDir, String filePrefix, Progress progress) throws IOException {
        if (runSize <= 0) {
            throw new IllegalArgumentException("runSize must be positive");
        }
        Session session = new Session(extractor, ascending);
        List<Path> runs = session.buildRuns(input, runSize, workDir, filePrefix, progress);
        if (runs.isEmpty()) {
            return null;
        }
        int level = 0;
        while (runs.size() > 1) {
            runs = session.mergeRound(runs, workDir, filePrefix, level, progress);
            level++;
        }
        return runs.get(0);
    }

    /** 打开最终有序段，按排序后顺序逐行产出原始数据行。 */
    public RowCursor openSorted(Path sortedRun) throws IOException {
        final BufferedReader reader = Files.newBufferedReader(sortedRun, StandardCharsets.UTF_8);
        return new RowCursor() {
            @Override
            @SuppressWarnings("unchecked")
            public Map<String, Object> next() throws IOException {
                String line;
                do {
                    line = reader.readLine();
                } while (line != null && line.isEmpty());
                if (line == null) {
                    return null;
                }
                JsonNode node = objectMapper.readTree(line);
                return objectMapper.convertValue(node.get("r"), Map.class);
            }

            @Override
            public void close() throws IOException {
                reader.close();
            }
        };
    }

    private static Path runFile(Path workDir, String prefix, int level, int ordinal) {
        return workDir.resolve(String.format("%srun-L%d-%06d.jsonl", prefix, level, ordinal));
    }

    private void writeEntries(Path file, List<Entry> entries) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (Entry e : entries) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("i", e.index);
                node.set("r", objectMapper.valueToTree(e.row));
                writer.write(objectMapper.writeValueAsString(node));
                writer.write('\n');
            }
        }
    }
}
