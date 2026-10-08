package com.github.shikejing.reportenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shikejing.reportenginejava.api.dto.JobResponse;
import com.github.shikejing.reportenginejava.config.EngineProperties;
import com.github.shikejing.reportenginejava.model.JobStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置项生效测试：application.properties / 环境变量 / 命令行参数中的
 * {@code report.engine.render.*} 必须真正绑定到服务，默认值与文档一致。
 *
 * <p>验收用例：默认每批行数配成 3，请求里不传 batchSize，渲染 10 行
 * 应得到 4 块，前三块各 3 行、最后一块 1 行，逐块拉取可完整还原。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RenderConfigBindingTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("report.engine.data-dir", () -> tempDir.toString());
        // 与 README「配置项」一致的键；若绑定失效，分块大小会退回内置默认值。
        registry.add("report.engine.render.batch-size", () -> "3");
        registry.add("report.engine.render.max-rows", () -> "100");
        registry.add("report.engine.render.default-timeout-ms", () -> "12345");
    }

    @LocalServerPort
    int port;

    @Autowired
    private EngineProperties properties;

    @Autowired
    private TestRestTemplate restTemplate;

    private HttpHeaders headers(String scope) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Scope", scope);
        h.set("Content-Type", "application/json");
        return h;
    }

    private JsonNode getChunk(String scope, String jobId, int chunk) {
        return restTemplate.exchange(
                "http://localhost:" + port + "/api/render-jobs/" + jobId + "/result?chunk=" + chunk,
                HttpMethod.GET, new HttpEntity<Void>(headers(scope)), JsonNode.class).getBody();
    }

    private JobResponse await(String scope, String jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            JobResponse j = restTemplate.exchange(
                    "http://localhost:" + port + "/api/render-jobs/" + jobId,
                    HttpMethod.GET, new HttpEntity<Void>(headers(scope)), JobResponse.class).getBody();
            if (j != null && j.status().isTerminal()) {
                return j;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("job did not finish: " + jobId);
    }

    @Test
    void renderProperties_areBoundFromConfiguration() {
        assertThat(properties.getRender().getBatchSize()).isEqualTo(3);
        assertThat(properties.getRender().getMaxRows()).isEqualTo(100L);
        assertThat(properties.getRender().getDefaultTimeoutMs()).isEqualTo(12345L);
    }

    @Test
    void defaultBatchSize3_tenRowsProduceFourChunks_3_3_3_1() throws Exception {
        String scope = "s-cfg";
        String def = "{\"name\":\"r\",\"columns\":[{\"field\":\"id\",\"type\":\"STRING\"}]}";
        var defResp = restTemplate.exchange("http://localhost:" + port + "/api/definitions",
                HttpMethod.POST, new HttpEntity<>(def, headers(scope)), Map.class);
        assertThat(defResp.getStatusCode().value()).isEqualTo(201);

        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            data.add(Map.of("id", "id-" + i));
        }
        // 不传 batchSize，必须采用服务端默认值 3
        String req = "{\"defName\":\"r\",\"defVersion\":1,\"format\":\"JSON\",\"data\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data) + "}";
        var submitResp = restTemplate.exchange("http://localhost:" + port + "/api/render-jobs",
                HttpMethod.POST, new HttpEntity<>(req, headers(scope)), JobResponse.class);
        JobResponse job = submitResp.getBody();
        assertThat(submitResp.getStatusCode().value()).isEqualTo(201);
        assertThat(job.batchSize()).isEqualTo(3);

        JobResponse done = await(scope, job.jobId());
        assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(done.chunkCount()).isEqualTo(4);

        // 前三块各 3 行，最后一块 1 行；逐块拉取完整还原
        int total = 0;
        int[] sizes = new int[4];
        for (int c = 0; c < 4; c++) {
            JsonNode chunk = getChunk(scope, job.jobId(), c);
            sizes[c] = chunk.size();
            total += chunk.size();
        }
        assertThat(sizes).containsExactly(3, 3, 3, 1);
        assertThat(total).isEqualTo(10);
        assertThat(getChunk(scope, job.jobId(), 0).get(0).get("id").asText()).isEqualTo("id-0");
        assertThat(getChunk(scope, job.jobId(), 3).get(0).get("id").asText()).isEqualTo("id-9");
    }
}
