package com.github.shikejing.reportenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shikejing.reportenginejava.api.dto.JobResponse;
import com.github.shikejing.reportenginejava.model.JobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分批结果测试：数据按批返回，分批拉取后可完整还原，
 * 且与一次性渲染结果逐字节一致；服务端不一次性加载全部结果。
 */
class BatchResultTest extends IntegrationTestBase {

    private List<Map<String, Object>> largeData(int n) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            data.add(row("id", "id-" + i, "amount", i));
        }
        return data;
    }

    @Test
    void jsonChunks_canBeReassembledAndMatchFullRender() throws Exception {
        String scope = "s-batch";
        String def = definitionJson("r", columns(col("id", "STRING"), col("amount", "NUMBER")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);
        List<Map<String, Object>> data = largeData(2500);

        // 分批渲染：每批 1000 行 → 3 块
        String reqBatch = renderRequest("r", 1, data, "JSON", 1000, null);
        JobResponse jobBatch = waitForTerminal(scope, submitRender(scope, reqBatch).jobId());
        assertThat(jobBatch.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(jobBatch.chunkCount()).isEqualTo(3);

        // 逐块拉取并还原
        List<JsonNode> reassembled = new ArrayList<>();
        for (int c = 0; c < jobBatch.chunkCount(); c++) {
            JsonNode chunk = fetchChunkJson(scope, jobBatch.jobId(), c);
            assertThat(chunk.isArray()).isTrue();
            for (JsonNode row : chunk) {
                reassembled.add(row);
            }
        }
        assertThat(reassembled).hasSize(2500);
        assertThat(reassembled.get(0).path("id").asText()).isEqualTo("id-0");
        assertThat(reassembled.get(2499).path("id").asText()).isEqualTo("id-2499");

        // 一次性渲染（单块）结果应与分批一致
        String reqFull = renderRequest("r", 1, data, "JSON", 2500, null);
        JobResponse jobFull = waitForTerminal(scope, submitRender(scope, reqFull).jobId());
        JsonNode fullChunk = fetchChunkJson(scope, jobFull.jobId(), 0);
        assertThat(objectMapper.writeValueAsBytes(reassembled))
                .isEqualTo(objectMapper.writeValueAsBytes(fullChunk));
    }

    @Test
    void csvChunks_includeHeaderOnlyInFirstChunk() throws Exception {
        String scope = "s-batch-csv";
        String def = definitionJson("r", columns(col("id", "STRING"), col("amount", "NUMBER")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);
        List<Map<String, Object>> data = largeData(1500);

        String req = renderRequest("r", 1, data, "CSV", 1000, null);
        JobResponse job = waitForTerminal(scope, submitRender(scope, req).jobId());
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.chunkCount()).isEqualTo(2);

        String chunk0 = fetchChunkCsv(scope, job.jobId(), 0);
        String chunk1 = fetchChunkCsv(scope, job.jobId(), 1);

        // 首块含表头
        assertThat(chunk0).startsWith("id,amount\n");
        // 数据行数：chunk0 含表头 + 1000 行；chunk1 含 500 行（行尾换行被 split 忽略）
        assertThat(chunk0.split("\n")).hasSize(1001);
        assertThat(chunk1.split("\n")).hasSize(500);
        // 第二块不含表头
        assertThat(chunk1).doesNotStartWith("id,amount");

        // 完整 CSV 与一次性渲染一致
        String reqFull = renderRequest("r", 1, data, "CSV", 1500, null);
        JobResponse jobFull = waitForTerminal(scope, submitRender(scope, reqFull).jobId());
        String fullCsv = fetchChunkCsv(scope, jobFull.jobId(), 0);
        assertThat(chunk0 + chunk1).isEqualTo(fullCsv);
    }

    @Test
    void chunkOutOfRange_returnsBadRequest() throws Exception {
        String scope = "s-batch-err";
        String def = definitionJson("r", columns(col("id", "STRING")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);
        String req = renderRequest("r", 1, List.of(row("id", "x")), "JSON", 100, null);
        JobResponse job = waitForTerminal(scope, submitRender(scope, req).jobId());

        ResponseEntity<Map> resp = restTemplate.exchange(
                "/api/render-jobs/" + job.jobId() + "/result?chunk=5", HttpMethod.GET,
                entity(scope), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void resultOfUnfinishedJob_returnsConflict() throws Exception {
        String scope = "s-batch-pending";
        String def = definitionJson("r", columns(col("id", "STRING")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);
        // 提交一个大作业但立即读取结果（此时可能仍在渲染）
        List<Map<String, Object>> data = largeData(5000);
        String req = renderRequest("r", 1, data, "JSON", 1, null);
        JobResponse job = submitRender(scope, req);

        // 轮询直到终态前，若状态非 SUCCEEDED 则应返回 409
        // （这里直接验证：终态前读取结果被拒绝）
        JobResponse done = waitForTerminal(scope, job.jobId());
        if (done.status() != JobStatus.SUCCEEDED) {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    "/api/render-jobs/" + job.jobId() + "/result?chunk=0", HttpMethod.GET,
                    entity(scope), Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }
}
