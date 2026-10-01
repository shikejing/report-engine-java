package com.github.shikejing.reportenginejava.api.dto;

import com.github.shikejing.reportenginejava.model.JobStatus;
import com.github.shikejing.reportenginejava.model.RenderFormat;

import java.time.Instant;

/**
 * 渲染作业响应。
 */
public record JobResponse(
        String jobId,
        String scope,
        String defName,
        int defVersion,
        int formatVersion,
        RenderFormat format,
        JobStatus status,
        boolean cancelRequested,
        String error,
        long totalRows,
        int chunkCount,
        int batchSize,
        long timeoutMs,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
