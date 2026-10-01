package com.github.shikejing.reportenginejava.engine;

/**
 * 渲染过程中发生的可定位字段错误（如数据行类型不符）。
 *
 * <p>抛出后作业置为 FAILED，已产出的中间分块整体丢弃，不会吐出半份结果。
 */
public class RenderException extends RuntimeException {

    private final String field;

    public RenderException(String field, String message) {
        super(field + ": " + message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
