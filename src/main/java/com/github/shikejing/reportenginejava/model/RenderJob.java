package com.github.shikejing.reportenginejava.model;

import java.time.Instant;

/**
 * 渲染作业。
 *
 * <p>作业状态持久化在 {@code jobs/{jobId}/job.json}，每次状态变更都通过
 * 临时文件 + 原子 move 落盘，重启后可据其恢复。
 */
public class RenderJob {

    /** 作业 ID。 */
    private String jobId;

    /** 所属作用域。 */
    private String scope;

    /** 报表名称。 */
    private String defName;

    /** 渲染所依据的定义提交版本（精确对应某一个已提交版本）。 */
    private int defVersion;

    /** 定义格式版本（冗余记录，便于排查）。 */
    private int formatVersion;

    /** 输出格式。 */
    private RenderFormat format;

    /** 作业状态。 */
    private JobStatus status;

    /** 取消标记，渲染线程在批次之间检查。 */
    private boolean cancelRequested;

    /** 失败 / 取消原因。 */
    private String error;

    /** 输入数据行数。 */
    private long totalRows;

    /** 已产出的分块数。 */
    private int chunkCount;

    /** 每块行数。 */
    private int batchSize;

    /** 超时时间（毫秒）。 */
    private long timeoutMs;

    private Instant createdAt;

    private Instant startedAt;

    private Instant finishedAt;

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getDefName() {
        return defName;
    }

    public void setDefName(String defName) {
        this.defName = defName;
    }

    public int getDefVersion() {
        return defVersion;
    }

    public void setDefVersion(int defVersion) {
        this.defVersion = defVersion;
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(int formatVersion) {
        this.formatVersion = formatVersion;
    }

    public RenderFormat getFormat() {
        return format;
    }

    public void setFormat(RenderFormat format) {
        this.format = format;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public void setCancelRequested(boolean cancelRequested) {
        this.cancelRequested = cancelRequested;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public long getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(long totalRows) {
        this.totalRows = totalRows;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }
}
