package com.github.shikejing.reportenginejava.model;

/**
 * 渲染输出格式。
 */
public enum RenderFormat {

    /** JSON：分块返回，每块是一个行对象数组。 */
    JSON,

    /** CSV：首块包含表头，后续块为数据行。 */
    CSV;

    public static RenderFormat parse(String raw) {
        for (RenderFormat f : values()) {
            if (f.name().equalsIgnoreCase(raw)) {
                return f;
            }
        }
        throw new IllegalArgumentException("unsupported render format: " + raw);
    }
}
