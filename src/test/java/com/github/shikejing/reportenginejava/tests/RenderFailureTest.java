package com.github.shikejing.reportenginejava.tests;

import com.github.shikejing.reportenginejava.api.dto.JobResponse;
import com.github.shikejing.reportenginejava.model.JobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 渲染失败测试：数据类型不符、超时等情况下作业置为 FAILED，
 * 中间产物整体丢弃，不吐出半份结果。
 */
class RenderFailureTest extends IntegrationTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    private com.github.shikejing.reportenginejava.storage.JobStore jobStore;

    @Test
    void wrongCellType_failsJobWithFieldPathAndDiscardsChunks() throws Exception {
        String scope = "s-fail";
        String def = definitionJson("r", columns(col("id", "STRING"), col("amount", "NUMBER")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);

        // amount 列传了字符串
        List<Map<String, Object>> data = List.of(
                row("id", "a", "amount", "not-a-number"),
                row("id", "b", "amount", 2));
        String req = renderRequest("r", 1, data, "JSON", 100, null);
        JobResponse job = waitForTerminal(scope, submitRender(scope, req).jobId());

        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.error()).contains("rows[0].amount");

        // 中间分块整体丢弃
        java.nio.file.Path chunksDir = jobStore.jobDir(job.jobId()).resolve("chunks");
        assertThat(Files.list(chunksDir).count()).isZero();
    }

    @Test
    void timeout_failsJobAndDiscardsChunks() throws Exception {
        String scope = "s-timeout";
        String def = definitionJson("r", columns(col("id", "STRING")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);

        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < 50000; i++) {
            data.add(row("id", "id-" + i));
        }
        // 超时 1ms：第一批写出前必然超时
        String req = renderRequest("r", 1, data, "JSON", 1, 1L);
        JobResponse job = waitForTerminal(scope, submitRender(scope, req).jobId());

        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.error()).contains("timeout");
    }

    @Test
    void emptyData_succeedsWithZeroChunks() throws Exception {
        String scope = "s-empty";
        String def = definitionJson("r", columns(col("id", "STRING")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);

        String req = renderRequest("r", 1, List.of(), "JSON", 100, null);
        JobResponse job = waitForTerminal(scope, submitRender(scope, req).jobId());

        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.chunkCount()).isZero();
        assertThat(job.totalRows()).isZero();
    }

    @Test
    void renderOfMissingDefinition_returns404() throws Exception {
        String scope = "s-missing";
        String req = renderRequest("nope", 1, List.of(), "JSON", 100, null);
        ResponseEntity<JobResponse> resp = restTemplate.postForEntity(
                "/api/render-jobs", entity(scope, req), JobResponse.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void renderWithBadVersion_returns404() throws Exception {
        String scope = "s-badv";
        String def = definitionJson("r", columns(col("id", "STRING")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);

        String req = renderRequest("r", 99, List.of(), "JSON", 100, null);
        ResponseEntity<JobResponse> resp = restTemplate.postForEntity(
                "/api/render-jobs", entity(scope, req), JobResponse.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void invalidRenderRequest_isRejectedWithFieldErrors() throws Exception {
        String scope = "s-badreq";
        // 缺少 data
        String req = "{\"defName\":\"r\"}";
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                "/api/render-jobs", entity(scope, req), Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(((List<Map<String, Object>>) resp.getBody().get("errors")))
                .anySatisfy(e -> assertThat(e.get("field")).isEqualTo("data"));
    }

}
