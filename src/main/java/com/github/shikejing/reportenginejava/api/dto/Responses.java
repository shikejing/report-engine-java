package com.github.shikejing.reportenginejava.api.dto;

import com.github.shikejing.reportenginejava.model.RenderJob;
import com.github.shikejing.reportenginejava.model.StoredDefinition;

/**
 * 响应对象转换工具。
 */
public final class Responses {

    private Responses() {
    }

    public static DefinitionResponse of(StoredDefinition d) {
        return new DefinitionResponse(d.getScope(), d.getName(), d.getVersion(),
                d.getFormatVersion(), d.getCreatedAt(), d.getContent());
    }

    public static JobResponse of(RenderJob j) {
        return new JobResponse(j.getJobId(), j.getScope(), j.getDefName(), j.getDefVersion(),
                j.getFormatVersion(), j.getFormat(), j.getStatus(), j.isCancelRequested(),
                j.getError(), j.getTotalRows(), j.getChunkCount(), j.getBatchSize(),
                j.getTimeoutMs(), j.getCreatedAt(), j.getStartedAt(), j.getFinishedAt());
    }
}
