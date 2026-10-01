package com.github.shikejing.reportenginejava.model;

/**
 * 报表列定义。
 */
public class Column {

    /** 数据行中的字段名（必填）。 */
    private String field;

    /** 列标题，缺省时使用 {@link #field}。 */
    private String header;

    /** 列类型（必填）。 */
    private ColumnType type;

    /** 格式化模式，仅 NUMBER / DATE 允许配置。 */
    private String format;

    /** 字符串转换方式（v2 能力）：UPPER / LOWER，仅 STRING 列允许配置。 */
    private String transform;

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getHeader() {
        return header;
    }

    public void setHeader(String header) {
        this.header = header;
    }

    public ColumnType getType() {
        return type;
    }

    public void setType(ColumnType type) {
        this.type = type;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getTransform() {
        return transform;
    }

    public void setTransform(String transform) {
        this.transform = transform;
    }
}
