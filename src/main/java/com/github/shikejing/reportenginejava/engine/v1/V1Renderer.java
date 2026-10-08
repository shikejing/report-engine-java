package com.github.shikejing.reportenginejava.engine.v1;

import com.github.shikejing.reportenginejava.engine.RenderControl;
import com.github.shikejing.reportenginejava.engine.RenderException;
import com.github.shikejing.reportenginejava.engine.Renderer;
import com.github.shikejing.reportenginejava.engine.ValidatorRegistry;
import com.github.shikejing.reportenginejava.engine.stream.IndexedRow;
import com.github.shikejing.reportenginejava.engine.stream.SortIO;
import com.github.shikejing.reportenginejava.model.Column;
import com.github.shikejing.reportenginejava.model.ColumnType;
import com.github.shikejing.reportenginejava.model.Definition;
import com.github.shikejing.reportenginejava.model.SortSpec;
import com.github.shikejing.reportenginejava.model.StoredDefinition;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 格式版本 1 渲染器。
 *
 * <p>确定性保证：
 * <ul>
 *   <li>输出列顺序严格按定义中的 columns 顺序（{@link LinkedHashMap}）；</li>
 *   <li>数字 / 日期格式化使用固定模式与 UTC 时区，不依赖默认区域设置；</li>
 *   <li>排序为稳定排序，null 永远排在末尾（外部归并以原始序号决胜）；</li>
 *   <li>渲染器无状态，同一份定义 + 同一份数据反复渲染结果逐字节一致。</li>
 * </ul>
 *
 * <p>内存有界：输入以行文件形式落盘，渲染器顺序扫描、分批产出；带排序时
 * 使用外部归并排序。常驻内存只与 batchSize 和归并路数相关，不随输入总行数增长。
 */
@Component
public class V1Renderer implements Renderer {

    public static final int FORMAT_VERSION = 1;

    protected static final String DEFAULT_NUMBER_PATTERN = "#.######";
    protected static final String DEFAULT_DATE_PATTERN = "yyyy-MM-dd";
    protected static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    /** 取消 / 超时检查点间隔的行数（扫描输入时用）。 */
    private static final int CHECKPOINT_ROWS = 256;

    private final ValidatorRegistry validatorRegistry;

    public V1Renderer(ValidatorRegistry validatorRegistry) {
        this.validatorRegistry = validatorRegistry;
    }

    @Override
    public int formatVersion() {
        return FORMAT_VERSION;
    }

    @Override
    public void render(StoredDefinition stored, Path inputFile, SortIO sortIO,
                       int batchSize, int fanIn, RenderControl control, BatchSink sink) {
        if (fanIn < 2) {
            throw new IllegalArgumentException("fanIn must be >= 2");
        }
        Definition def = validatorRegistry.get(stored.getFormatVersion()).validate(stored.getContent());
        if (def.getSort() == null) {
            renderUnsorted(def, inputFile, sortIO, batchSize, control, sink);
        } else {
            renderSorted(def, inputFile, sortIO, batchSize, fanIn, control, sink);
        }
    }

    /**
     * 无排序路径：第一遍顺序扫描做逐行单元格校验（与历史版本一致：全部行校验
     * 通过后才产出任何分块），第二遍顺序扫描、分批格式化并回调。
     */
    private void renderUnsorted(Definition def, Path inputFile, SortIO sortIO, int batchSize,
                                RenderControl control, BatchSink sink) {
        validateAll(def, inputFile, sortIO, control);

        int chunkIndex = 0;
        List<Map<String, Object>> chunk = new ArrayList<>(batchSize);
        long scanned = 0;
        try (com.github.shikejing.reportenginejava.engine.stream.RowReader reader =
                     sortIO.openInput(inputFile)) {
            while (reader.hasNext()) {
                IndexedRow ir = reader.next();
                chunk.add(formatRow(def, ir.getRow()));
                if (chunk.size() == batchSize) {
                    control.checkpoint();
                    sink.accept(chunkIndex++, chunk);
                    chunk = new ArrayList<>(batchSize);
                }
                if (++scanned % CHECKPOINT_ROWS == 0) {
                    control.checkpoint();
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        if (!chunk.isEmpty()) {
            control.checkpoint();
            sink.accept(chunkIndex, chunk);
        }
    }

    /**
     * 带排序路径（外部归并排序，内存只与 batchSize 和 fanIn 相关）：
     * <ol>
     *   <li>一遍校验，并按 batchSize 大小切分；每批在内存中用与历史版本
     *       完全相同的比较器排序后落盘为一个归并段；</li>
     *   <li>若归并段数 &le; fanIn，一趟 k 路归并并按 batchSize 产出；</li>
     *   <li>否则反复按 fanIn 个段做一趟归并（每 &le; fanIn 段产出一个新段，
     *       中间段消费后删除），直到只剩一段再产出。</li>
     * </ol>
     * 归并的优先级队列以（键比较, 原始序号）排序，严格复刻稳定排序语义。
     */
    private void renderSorted(Definition def, Path inputFile, SortIO sortIO, int batchSize,
                              int fanIn, RenderControl control, BatchSink sink) {
        Comparator<IndexedRow> keyCmp = buildComparator(def);

        List<Integer> runs = new ArrayList<>();
        List<IndexedRow> batch = new ArrayList<>(batchSize);
        int nextRunId = 0;
        long scanned = 0;
        try (com.github.shikejing.reportenginejava.engine.stream.RowReader reader =
                     sortIO.openInput(inputFile)) {
            while (reader.hasNext()) {
                IndexedRow ir = reader.next();
                validateRow(def, ir);
                batch.add(ir);
                if (batch.size() == batchSize) {
                    runs.add(flushRun(sortIO, nextRunId++, batch, keyCmp));
                    batch = new ArrayList<>(batchSize);
                }
                if (++scanned % CHECKPOINT_ROWS == 0) {
                    control.checkpoint();
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        if (!batch.isEmpty()) {
            runs.add(flushRun(sortIO, nextRunId++, batch, keyCmp));
        }

        // 中间趟：每 fanIn 个段归并成一个新段，直到段数不超过 fanIn。
        while (runs.size() > fanIn) {
            control.checkpoint();
            List<Integer> merged = new ArrayList<>((runs.size() + fanIn - 1) / fanIn);
            for (int from = 0; from < runs.size(); from += fanIn) {
                int to = Math.min(from + fanIn, runs.size());
                int outId = nextRunId++;
                mergeRuns(def, sortIO, runs.subList(from, to), outId, keyCmp, control, -1, null);
                for (int i = from; i < to; i++) {
                    deleteRunQuietly(sortIO, runs.get(i));
                }
                merged.add(outId);
            }
            runs = merged;
        }

        // 最终趟：k 路归并并按 batchSize 产出格式化分块。
        mergeRuns(def, sortIO, runs, -1, keyCmp, control, batchSize, sink);
    }

    private int flushRun(SortIO sortIO, int runId, List<IndexedRow> rows,
                         Comparator<IndexedRow> keyCmp) {
        rows.sort(keyCmp); // List.sort 是稳定排序；批次内同键保持原始顺序
        try (com.github.shikejing.reportenginejava.engine.stream.RowWriter w = sortIO.createRun(runId)) {
            for (IndexedRow row : rows) {
                w.write(row);
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return runId;
    }

    /**
     * k 路归并。{@code outRunId &gt;= 0} 时把归并结果写为新归并段；
     * {@code batchSize &gt; 0} 时把归并结果按批格式化并回调 sink（最终趟）。
     */
    private void mergeRuns(Definition def, SortIO sortIO, List<Integer> runIds, int outRunId,
                           Comparator<IndexedRow> keyCmp, RenderControl control,
                           int batchSize, BatchSink sink) {
        Comparator<MergeEntry> heapCmp = (a, b) -> {
            int c = keyCmp.compare(a.row, b.row);
            if (c != 0) {
                return c;
            }
            return Long.compare(a.row.getSeq(), b.row.getSeq()); // 同键按原始序号 → 稳定
        };
        java.util.PriorityQueue<MergeEntry> heap = new java.util.PriorityQueue<>(
                Math.max(2, runIds.size()), heapCmp);
        List<com.github.shikejing.reportenginejava.engine.stream.RowReader> readers = new ArrayList<>(runIds.size());
        try {
            for (int runId : runIds) {
                com.github.shikejing.reportenginejava.engine.stream.RowReader r = sortIO.openRun(runId);
                readers.add(r);
                if (r.hasNext()) {
                    heap.add(new MergeEntry(r.next(), r));
                }
            }

            com.github.shikejing.reportenginejava.engine.stream.RowWriter out = null;
            List<Map<String, Object>> chunk =
                    batchSize > 0 ? new ArrayList<>(batchSize) : null;
            int chunkIndex = 0;
            long emitted = 0;
            try {
                if (outRunId >= 0) {
                    out = sortIO.createRun(outRunId);
                }
                while (!heap.isEmpty()) {
                    MergeEntry e = heap.poll();
                    if (out != null) {
                        out.write(e.row);
                    } else {
                        chunk.add(formatRow(def, e.row.getRow()));
                        if (chunk.size() == batchSize) {
                            control.checkpoint();
                            sink.accept(chunkIndex++, chunk);
                            chunk = new ArrayList<>(batchSize);
                        }
                    }
                    if (e.reader.hasNext()) {
                        heap.add(new MergeEntry(e.reader.next(), e.reader));
                    }
                    if (++emitted % CHECKPOINT_ROWS == 0) {
                        control.checkpoint();
                    }
                }
                if (chunk != null && !chunk.isEmpty()) {
                    control.checkpoint();
                    sink.accept(chunkIndex, chunk);
                }
            } finally {
                if (out != null) {
                    out.close();
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } finally {
            for (com.github.shikejing.reportenginejava.engine.stream.RowReader r : readers) {
                try {
                    r.close();
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        }
    }

    private void deleteRunQuietly(SortIO sortIO, int runId) {
        try {
            sortIO.deleteRun(runId);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void validateAll(Definition def, Path inputFile, SortIO sortIO, RenderControl control) {
        long scanned = 0;
        try (com.github.shikejing.reportenginejava.engine.stream.RowReader reader =
                     sortIO.openInput(inputFile)) {
            while (reader.hasNext()) {
                validateRow(def, reader.next());
                if (++scanned % CHECKPOINT_ROWS == 0) {
                    control.checkpoint();
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void validateRow(Definition def, IndexedRow ir) {
        Map<String, Object> row = ir.getRow();
        if (row == null) {
            throw new RenderException("rows[" + ir.getSeq() + "]", "row must be an object");
        }
        for (Column col : def.getColumns()) {
            validateCell(col, row.get(col.getField()), ir.getSeq());
        }
    }

    private Map<String, Object> formatRow(Definition def, Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Column col : def.getColumns()) {
            out.put(col.getField(), formatCell(col, row.get(col.getField())));
        }
        return out;
    }

    /** 归并堆元素：当前行与提供该行的游标（游标推进后回填堆）。 */
    private static final class MergeEntry {
        final IndexedRow row;
        final com.github.shikejing.reportenginejava.engine.stream.RowReader reader;

        MergeEntry(IndexedRow row, com.github.shikejing.reportenginejava.engine.stream.RowReader reader) {
            this.row = row;
            this.reader = reader;
        }
    }

    protected void validateCell(Column col, Object value, long rowIndex) {
        if (value == null) {
            return;
        }
        String path = "rows[" + rowIndex + "]." + col.getField();
        ColumnType type = col.getType();
        if (type == ColumnType.STRING) {
            if (!(value instanceof String)) {
                throw new RenderException(path, "expected STRING but got " + value.getClass().getSimpleName());
            }
        } else if (type == ColumnType.NUMBER) {
            if (!(value instanceof Number)) {
                throw new RenderException(path, "expected NUMBER but got " + value.getClass().getSimpleName());
            }
        } else if (type == ColumnType.BOOLEAN) {
            if (!(value instanceof Boolean)) {
                throw new RenderException(path, "expected BOOLEAN but got " + value.getClass().getSimpleName());
            }
        } else if (type == ColumnType.DATE) {
            if (value instanceof Number) {
                return;
            }
            if (value instanceof String) {
                parseDate((String) value, col.getFormat());
                return;
            }
            throw new RenderException(path, "expected DATE (string or epoch millis) but got "
                    + value.getClass().getSimpleName());
        }
    }

    protected String formatCell(Column col, Object value) {
        if (value == null) {
            return "";
        }
        switch (col.getType()) {
            case STRING:
                return (String) value;
            case BOOLEAN:
                return Boolean.toString((Boolean) value);
            case NUMBER:
                return formatNumber((Number) value, col.getFormat());
            case DATE:
                return formatDate(value, col.getFormat());
            default:
                throw new IllegalStateException("unsupported column type: " + col.getType());
        }
    }

    protected String formatNumber(Number value, String pattern) {
        DecimalFormat df = new DecimalFormat(pattern != null ? pattern : DEFAULT_NUMBER_PATTERN);
        return df.format(value.doubleValue());
    }

    protected String formatDate(Object value, String pattern) {
        Date date;
        if (value instanceof Number) {
            date = new Date(((Number) value).longValue());
        } else {
            date = parseDate((String) value, pattern);
        }
        SimpleDateFormat sdf = new SimpleDateFormat(pattern != null ? pattern : DEFAULT_DATE_PATTERN);
        sdf.setTimeZone(UTC);
        sdf.setLenient(false);
        return sdf.format(date);
    }

    protected Date parseDate(String raw, String pattern) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat(pattern != null ? pattern : DEFAULT_DATE_PATTERN);
            sdf.setTimeZone(UTC);
            sdf.setLenient(false);
            return sdf.parse(raw);
        } catch (java.text.ParseException e) {
            throw new RenderException("date", "cannot parse date '" + raw + "' with pattern "
                    + (pattern != null ? pattern : DEFAULT_DATE_PATTERN));
        }
    }

    /**
     * 排序键比较器：比较两行在排序列上的值。语义与历史版本完全一致：
     * null 永远排在末尾（与方向无关），其余按 ASC / DESC 取反。
     * 不负责决胜（稳定顺序由调用方用原始序号保证）。
     */
    protected Comparator<IndexedRow> buildComparator(Definition def) {
        SortSpec sort = def.getSort();
        Column sortColumn = def.getColumns().stream()
                .filter(c -> c.getField().equals(sort.getField()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "sort field references missing column: " + sort.getField()));
        boolean asc = sort.getDirection() == SortSpec.Direction.ASC;

        return (r1, r2) -> {
            Object v1 = r1.getRow().get(sort.getField());
            Object v2 = r2.getRow().get(sort.getField());
            if (v1 == null && v2 == null) {
                return 0;
            }
            if (v1 == null) {
                return 1; // null 排末尾
            }
            if (v2 == null) {
                return -1;
            }
            int cmp;
            if (sortColumn.getType() == ColumnType.NUMBER && v1 instanceof Number && v2 instanceof Number) {
                cmp = Double.compare(((Number) v1).doubleValue(), ((Number) v2).doubleValue());
            } else if (sortColumn.getType() == ColumnType.DATE) {
                cmp = compareDates(v1, v2, sortColumn.getFormat());
            } else {
                cmp = v1.toString().compareTo(v2.toString());
            }
            return asc ? cmp : -cmp;
        };
    }

    @SuppressWarnings("unchecked")
    protected int compareDates(Object v1, Object v2, String pattern) {
        Date d1 = v1 instanceof Number ? new Date(((Number) v1).longValue()) : parseDate((String) v1, pattern);
        Date d2 = v2 instanceof Number ? new Date(((Number) v2).longValue()) : parseDate((String) v2, pattern);
        return d1.compareTo(d2);
    }
}
