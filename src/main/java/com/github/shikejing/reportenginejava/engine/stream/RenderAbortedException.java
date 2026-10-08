package com.github.shikejing.reportenginejava.engine.stream;

/**
 * 渲染被外部条件中止（用户取消或等待超时）。
 *
 * <p>由 {@link Progress} 检查点抛出、贯穿整个流式渲染流水线，
 * 与数据错误（{@link com.github.shikejing.reportenginejava.engine.RenderException}）
 * 区分开：中止时作业进入 CANCELLED / FAILED，已产出分块整体丢弃。
 */
public class RenderAbortedException extends RuntimeException {

    /** 中止类型。 */
    public enum Reason {
        /** 用户请求取消。 */
        CANCELLED,
        /** 超过任务允许的等待时间。 */
        TIMEOUT
    }

    private final Reason reason;

    public RenderAbortedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
