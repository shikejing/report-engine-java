package com.github.shikejing.reportenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shikejing.reportenginejava.api.dto.JobResponse;
import com.github.shikejing.reportenginejava.model.JobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 渲染配置项生效测试：{@code report.engine.render.*} 必须真正绑定并影响行为。
 */
@TestPropertySource(properties = {
        "report.engine.render.batch-size=3",
        "report.engine.render.default-timeout-ms=12345",
        "report.engine.render.external-sort-fan-in=2"
})
class RenderConfigTest extends IntegrationTestBase {

    private List<Map<String, Object>> rows(int n) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            data.add(row("id", "id-" + i));
        }
        return data;
    }

    /** 验收：默认每批行数配成 3，10 行 → 4 块（3/3/3/1），逐块拉取完整还原。 */
    @Test
    void defaultBatchSizeProperty_drivesChunkSizes_whenRequestOmitsBatchSize() throws Exception {
        String scope = "s-cfg-batch";
        String def = definitionJson("r", columns(col("id", "STRING")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);

        String req = renderRequest("r", 1, rows(10), "JSON", null, null);
        JobResponse job = waitForTerminal(scope, submitRender(scope, req).jobId());
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.batchSize()).isEqualTo(3);
        assertThat(job.chunkCount()).isEqualTo(4);

        List<JsonNode> all = new ArrayList<>();
        for (int c = 0; c < 4; c++) {
            JsonNode chunk = fetchChunkJson(scope, job.jobId(), c);
            assertThat(chunk.isArray()).isTrue();
            int expected = (c < 3) ? 3 : 1;
            assertThat(chunk).hasSize(expected);
            for (JsonNode rowNode : chunk) {
                all.add(rowNode);
            }
        }
        assertThat(all).hasSize(10);
        for (int i = 0; i < 10; i++) {
            assertThat(all.get(i).path("id").asText()).isEqualTo("id-" + i);
        }
    }

    /** 其它渲染配置同样生效：请求未传 timeoutMs 时使用全局默认值。 */
    @Test
    void defaultTimeoutProperty_isBound() throws Exception {
        String scope = "s-cfg-other";
        String def = definitionJson("r", columns(col("id", "STRING")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);

        String req = renderRequest("r", 1, rows(1), "JSON", null, null);
        JobResponse job = waitForTerminal(scope, submitRender(scope, req).jobId());
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.timeoutMs()).isEqualTo(12345L);
    }
}
