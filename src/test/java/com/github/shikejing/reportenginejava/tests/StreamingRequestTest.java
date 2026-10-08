package com.github.shikejing.reportenginejava.tests;

import com.github.shikejing.reportenginejava.api.dto.JobResponse;
import com.github.shikejing.reportenginejava.model.JobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式提交管道的边界行为：
 * 非法 JSON 请求体 400；data 内非对象元素创建作业后在扫描阶段失败（rows[i]）。
 */
class StreamingRequestTest extends IntegrationTestBase {

    @Test
    void malformedJsonBody_returns400() {
        String scope = "s-malformed";
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                "/api/render-jobs", entity(scope, "{not-json"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(resp.getBody().get("error"))).contains("malformed");
    }

    @Test
    void nonObjectElement_failsJobAtRowPath() throws Exception {
        String scope = "s-nonobj";
        String def = definitionJson("r", columns(col("id", "STRING")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);

        // data 第二个元素不是对象
        String req = "{\"defName\":\"r\",\"defVersion\":1,\"data\":[{\"id\":\"a\"},42]}";
        HttpEntity<String> e = entity(scope, req);
        ResponseEntity<JobResponse> post = restTemplate.postForEntity(
                "/api/render-jobs", e, JobResponse.class);
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JobResponse job = waitForTerminal(scope, post.getBody().jobId());

        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.error()).contains("rows[1]");
    }

    @Test
    void emptyArray_succeedsWithZeroChunksAndZeroRows() throws Exception {
        String scope = "s-empty-stream";
        String def = definitionJson("r", columns(col("id", "STRING")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);

        String req = renderRequest("r", 1, List.of(), "JSON", 10, null);
        JobResponse job = waitForTerminal(scope, submitRender(scope, req).jobId());
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.totalRows()).isZero();
        assertThat(job.chunkCount()).isZero();
    }
}
