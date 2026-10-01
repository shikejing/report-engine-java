package com.github.shikejing.reportenginejava.dto;

import java.util.List;
import java.util.Map;

public record RenderBatch(
        String jobId,
        long offset,
        List<Map<String, Object>> rows,
        boolean end
) {
}
