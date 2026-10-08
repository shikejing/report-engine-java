package com.github.shikejing.reportenginejava.engine.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.reportenginejava.engine.RenderException;
import com.github.shikejing.reportenginejava.engine.Renderer;
import com.github.shikejing.reportenginejava.engine.ValidatorRegistry;
import com.github.shikejing.reportenginejava.engine.stream.ExternalSorter;
import com.github.shikejing.reportenginejava.engine.stream.Progress;
import com.github.shikejing.reportenginejava.engine.stream.RowCursor;
import com.github.shikejing.reportenginejava.engine.stream.RowInput;
import com.github.shikejing.reportenginejava.model.Column;
import com.github.shikejing.reportenginejava.model.ColumnType;
import com.github.shikejing.reportenginejava.model.Definition;
import com.github.shikejing.reportenginejava.model.SortSpec;
import com.github.shikejing.reportenginejava.model.StoredDefinition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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
 *   <li>排序为稳定排序，null 永远排在末尾；</li>
 *   <li>渲染器无状态，同一份定义 + 同一份数据反复渲染结果逐字节一致。</li>
 * </ul>
 *
 * <p>流式与有界内存：输入经 {@link RowInput} 逐行读取，校验与输出都按批进行，
 * 内存中至多持有一批行；带排序时使用 {@link ExternalSorter} 外部归并排序
 * （建段内存为一批，归并时每路只持队首行），常驻内存不随输入总行数增长。
 * 排序结果与历史实现（整体读入后稳定排序）逐条一致。
 */
@Component
public class V1Renderer implements Renderer {

    public static final int FORMAT_VERSION = 1;

    protected static final String DEFAULT_NUMBER_PATTERN = "#.######";
    protected static final String DEFAULT_DATE_PATTERN = "yyyy-MM-dd";
    protected static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    /** 与 {@link ExternalSorter} 一致的检查点粒度。 */
    private static final int CHECKPOINT_EVERY = ExternalSorter.CHECKPOINT_EVERY;

    private final ValidatorRegistry validatorRegistry;
    private final ObjectMapper objectMapper;

    public V1Renderer(ValidatorRegistry validatorRegistry, ObjectMapper objectMapper) {
        this.validatorRegistry = validatorRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public int formatVersion() {
        return FORMAT_VERSION;
    }

    @Override
    public void render(StoredDefinition stored, RowInput input, long totalRows,
                       int batchSize, Path workDir, Progress progress, BatchSink sink) throws IOException {
        Definition def = validatorRegistry.get(stored.getFormatVersion()).validate(stored.getContent());

        // 第 1 趟：逐行确认每行都是对象（保持历史实现「先检查所有行是否为对象」的错误先后顺序）。
        try (RowCursor cursor = input.open()) {
            long i = 0;
            Object row;
            while ((row = cursor.next()) != null) {
                if (!(row instanceof Map)) {
                    throw new RenderException("rows[" + i + "]", "row must be an object");
                }
                if (i % CHECKPOINT_EVERY == 0) {
                    progress.checkpoint(i);
                }
                i++;
            }
        }

        // 第 2 趟：逐行校验单元格类型（错误定位到 rows[i].field），任何输出之前完成。
        try (RowCursor cursor = input.open()) {
            long i = 0;
            Map<?, ?> row;
            while ((row = asMap(cursor.next())) != null) {
                for (Column col : def.getColumns()) {
                    validateCell(col, row.get(col.getField()), i);
                }
                if (i % CHECKPOINT_EVERY == 0) {
                    progress.checkpoint(i);
                }
                i++;
            }
        }

        if (def.getSort() != null) {
            renderSorted(def, input, batchSize, workDir, progress, sink);
        } else {
            renderPassThrough(def, input, batchSize, progress, sink);
        }
    }

    /** 不带排序：按批读取原始顺序的行并格式化输出。 */
    private void renderPassThrough(Definition def, RowInput input, int batchSize,
                                   Progress progress, BatchSink sink) throws IOException {
        int chunkIndex = 0;
        long processed = 0;
        try (RowCursor cursor = input.open()) {
            while (true) {
                List<Map<String, Object>> chunk = new ArrayList<>(batchSize);
                Map<String, Object> row;
                while (chunk.size() < batchSize && (row = asMap(cursor.next())) != null) {
                    chunk.add(formatRow(def, row));
                    processed++;
                    if (processed % CHECKPOINT_EVERY == 0) {
                        progress.checkpoint(processed);
                    }
                }
                if (chunk.isEmpty()) {
                    return;
                }
                sink.accept(chunkIndex++, chunk);
            }
        }
    }

    /** 带排序：外部排序得到最终有序段，再按批读取并格式化输出。 */
    private void renderSorted(Definition def, RowInput input, int batchSize, Path workDir,
                              Progress progress, BatchSink sink) throws IOException {
        SortSpec sort = def.getSort();
        Column sortColumn = def.getColumns().stream()
                .filter(c -> c.getField().equals(sort.getField()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "sort field references missing column: " + sort.getField()));
        boolean asc = sort.getDirection() == SortSpec.Direction.ASC;

        ExternalSorter sorter = new ExternalSorter(objectMapper);
        ExternalSorter.KeyExtractor extractor = (index, row) -> extractSortKey(sortColumn, row);

        Path sortedRun = sorter.sort(input, extractor, asc, batchSize, workDir, "sort-", progress);
        if (sortedRun == null) {
            return;
        }
        try {
            int chunkIndex = 0;
            long processed = 0;
            try (RowCursor cursor = sorter.openSorted(sortedRun)) {
                while (true) {
                    List<Map<String, Object>> chunk = new ArrayList<>(batchSize);
                    Map<String, Object> row;
                    while (chunk.size() < batchSize && (row = asMap(cursor.next())) != null) {
                        chunk.add(formatRow(def, row));
                        processed++;
                        if (processed % CHECKPOINT_EVERY == 0) {
                            progress.checkpoint(processed);
                        }
                    }
                    if (chunk.isEmpty()) {
                        return;
                    }
                    sink.accept(chunkIndex++, chunk);
                }
            }
        } finally {
            Files.deleteIfExists(sortedRun);
        }
    }

    private ExternalSorter.Key extractSortKey(Column sortColumn, Map<String, Object> row) {
        Object v = row.get(sortColumn.getField());
        if (v == null) {
            return null;
        }
        if (sortColumn.getType() == ColumnType.NUMBER && v instanceof Number) {
            return ExternalSorter.Key.number(((Number) v).doubleValue());
        }
        if (sortColumn.getType() == ColumnType.DATE) {
            if (v instanceof Number) {
                return ExternalSorter.Key.number(((Number) v).longValue());
            }
            // 校验趟已保证此处只可能是可解析的日期字符串。
            return ExternalSorter.Key.number(parseDate((String) v, sortColumn.getFormat()).getTime());
        }
        return ExternalSorter.Key.text(v.toString());
    }

    private Map<String, Object> formatRow(Definition def, Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Column col : def.getColumns()) {
            out.put(col.getField(), formatCell(col, row.get(col.getField())));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object row) {
        return (Map<String, Object>) row;
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
}
