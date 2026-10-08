package com.github.shikejing.reportenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shikejing.reportenginejava.api.dto.JobResponse;
import com.github.shikejing.reportenginejava.model.JobStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DATE 列排序：字符串日期与 epoch 毫秒混排时必须按时间先后（与旧内存排序一致）。
 */
class DateSortRenderTest extends IntegrationTestBase {

    @Test
    void dateColumn_mixedStringAndEpoch_sortsChronologically() throws Exception {
        String scope = "s-date-sort";
        String def = "{\"name\":\"r\",\"columns\":[{\"field\":\"d\",\"type\":\"DATE\"},"
                + "{\"field\":\"tag\",\"type\":\"STRING\"}],"
                + "\"sort\":{\"field\":\"d\",\"direction\":\"ASC\"}}";
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);

        List<Map<String, Object>> data = new ArrayList<>();
        data.add(row("d", "2024-03-01", "tag", "mar"));
        data.add(row("d", 1704067200000L, "tag", "jan-epoch"));   // 2024-01-01 UTC
        data.add(row("d", "2024-02-01", "tag", "feb"));
        data.add(row("d", 1711929600000L, "tag", "apr-epoch"));   // 2024-04-01 UTC

        // 每批 2，强制走外部排序（2 个段）
        String req = renderRequest("r", 1, data, "JSON", 2, null);
        JobResponse done = waitForTerminal(scope, submitRender(scope, req).jobId());
        assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);

        List<String> tags = new ArrayList<>();
        List<String> dates = new ArrayList<>();
        for (int c = 0; c < done.chunkCount(); c++) {
            JsonNode chunk = fetchChunkJson(scope, done.jobId(), c);
            for (JsonNode r : chunk) {
                tags.add(r.get("tag").asText());
                dates.add(r.get("d").asText());
            }
        }
        assertThat(tags).containsExactly("jan-epoch", "feb", "mar", "apr-epoch");
        assertThat(dates).containsExactly("2024-01-01", "2024-02-01", "2024-03-01", "2024-04-01");
    }
}
