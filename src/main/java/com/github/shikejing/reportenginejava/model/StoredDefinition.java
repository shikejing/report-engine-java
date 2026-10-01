package com.github.shikejing.reportenginejava.model;

import java.time.Instant;

public record StoredDefinition(
        String scope,
        String reportKey,
        int version,
        int engineVersion,
        Instant createdAt,
        ReportDefinition definition
) {
}
