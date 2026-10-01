package com.github.shikejing.reportenginejava.engine.v1;

import com.github.shikejing.reportenginejava.engine.RenderException;
import com.github.shikejing.reportenginejava.engine.Renderer;
import com.github.shikejing.reportenginejava.engine.ValidatorRegistry;
import com.github.shikejing.reportenginejava.model.Column;
import com.github.shikejing.reportenginejava.model.ColumnType;
import com.github.shikejing.reportenginejava.model.Definition;
import com.github.shikejing.reportenginejava.model.SortSpec;
import com.github.shikejing.reportenginejava.model.StoredDefinition;
import org.springframework.stereotype.Component;

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
 *   <li>排序为稳定排序，null 永远排在末尾；</li>
 *   <li>渲染器无状态，同一份定义 + 同一份数据反复渲染结果逐字节一致。</li>
 * </ul>
 */
@Component
public class V1Renderer implements Renderer {

    public static final int FORMAT_VERSION = 1;

    protected static final String DEFAULT_NUMBER_PATTERN = "#.######";
    protected static final String DEFAULT_DATE_PATTERN = "yyyy-MM-dd";
    protected static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private final ValidatorRegistry validatorRegistry;

    public V1Renderer(ValidatorRegistry validatorRegistry) {
        this.validatorRegistry = validatorRegistry;
    }

    @Override
    public int formatVersion() {
        return FORMAT_VERSION;
    }

    @Override
    public void render(StoredDefinition stored, List<Map<String, Object>> rows,
                       int batchSize, BatchSink sink) {
        Definition def = validatorRegistry.get(stored.getFormatVersion()).validate(stored.getContent());

        // 逐行校验单元格类型（错误定位到 rows[i].field）
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            if (row == null) {
                throw new RenderException("rows[" + i + "]", "row must be an object");
            }
            for (Column col : def.getColumns()) {
                Object value = row.get(col.getField());
                validateCell(col, value, i);
            }
        }

        // 排序（稳定排序；null 排末尾）
        List<Map<String, Object>> ordered = new ArrayList<>(rows);
        if (def.getSort() != null) {
            ordered.sort(buildComparator(def));
        }

        // 分批格式化并回调
        int chunkIndex = 0;
        for (int from = 0; from < ordered.size(); from += batchSize) {
            int to = Math.min(from + batchSize, ordered.size());
            List<Map<String, Object>> chunk = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                Map<String, Object> row = ordered.get(i);
                Map<String, Object> out = new LinkedHashMap<>();
                for (Column col : def.getColumns()) {
                    out.put(col.getField(), formatCell(col, row.get(col.getField())));
                }
                chunk.add(out);
            }
            sink.accept(chunkIndex++, chunk);
        }
    }

    protected void validateCell(Column col, Object value, int rowIndex) {
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

    private Comparator<Map<String, Object>> buildComparator(Definition def) {
        SortSpec sort = def.getSort();
        Column sortColumn = def.getColumns().stream()
                .filter(c -> c.getField().equals(sort.getField()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "sort field references missing column: " + sort.getField()));
        boolean asc = sort.getDirection() == SortSpec.Direction.ASC;

        Comparator<Map<String, Object>> comparator = (r1, r2) -> {
            Object v1 = r1.get(sort.getField());
            Object v2 = r2.get(sort.getField());
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
        return comparator;
    }

    @SuppressWarnings("unchecked")
    private int compareDates(Object v1, Object v2, String pattern) {
        Date d1 = v1 instanceof Number ? new Date(((Number) v1).longValue()) : parseDate((String) v1, pattern);
        Date d2 = v2 instanceof Number ? new Date(((Number) v2).longValue()) : parseDate((String) v2, pattern);
        return d1.compareTo(d2);
    }
}
