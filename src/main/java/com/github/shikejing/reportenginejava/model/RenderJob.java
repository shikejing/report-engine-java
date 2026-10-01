package com.github.shikejing.reportenginejava.model;

import java.time.Instant;

public record RenderJob(
        String jobId,
        String scope,
        String reportKey,
        int definitionVersion,
        JobStatus status,
        String errorMessage,
        long totalRows,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        long timeoutAtEpochMilli
) {
}
