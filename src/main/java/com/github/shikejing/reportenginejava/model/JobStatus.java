package com.github.shikejing.reportenginejava.model;

/**
 * 渲染作业状态。
 *
 * <p>状态只能单向流动：
 * <pre>
 * PENDING → RUNNING → SUCCEEDED
 *                  ↘ FAILED
 *                  ↘ CANCELLED
 * </pre>
 * 作业中断（重启）后不会停留在中间状态，恢复时 RUNNING / PENDING 一律置为 FAILED。
 */
public enum JobStatus {

    /** 已提交，等待调度。 */
    PENDING,

    /** 正在渲染。 */
    RUNNING,

    /** 渲染成功，结果可读取。 */
    SUCCEEDED,

    /** 渲染失败（含校验失败、超时、重启中断），中间产物已丢弃。 */
    FAILED,

    /** 被用户取消，中间产物已丢弃。 */
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
