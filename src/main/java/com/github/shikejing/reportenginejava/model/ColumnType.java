package com.github.shikejing.reportenginejava.model;

/**
 * 列数据类型。
 */
public enum ColumnType {

    STRING,
    NUMBER,
    DATE,
    BOOLEAN;

    /**
     * 解析类型名，大小写不敏感。
     *
     * @throws IllegalArgumentException 类型名不受支持时抛出
     */
    public static ColumnType parse(String raw) {
        for (ColumnType t : values()) {
            if (t.name().equalsIgnoreCase(raw)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unsupported column type: " + raw);
    }
}
