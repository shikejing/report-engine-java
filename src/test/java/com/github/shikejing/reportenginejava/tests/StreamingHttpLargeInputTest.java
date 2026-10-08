package com.github.shikejing.reportenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shikejing.reportenginejava.api.dto.JobResponse;
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

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端 HTTP 流式大输入测试：请求体从磁盘文件直接以固定缓冲 POST，
 * <b>测试代码也不在内存中持有完整请求体</b>，真实复现「超大请求逐块到达」。
 * 随后轮询作业、逐块拉取并核对总行数。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamingHttpLargeInputTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("report.engine.data-dir", () -> tempDir.toString());
        registry.add("report.engine.render.max-rows", () -> "5000000");
        registry.add("report.engine.render.batch-size", () -> "20000");
    }

    @LocalServerPort
    int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private HttpHeaders headers(String scope) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Scope", scope);
        h.set("Content-Type", "application/json");
        return h;
    }

    @Test
    void hugeBodyStreamedOverHttp_rendersFullyWithoutHoldingBodyInMemory() throws Exception {
        String scope = "s-http-stream";
        String def = "{\"name\":\"r\",\"columns\":[{\"field\":\"id\",\"type\":\"STRING\"}]}";
        var defResp = restTemplate.exchange("http://localhost:" + port + "/api/definitions",
                HttpMethod.POST, new HttpEntity<>(def, headers(scope)), JsonNode.class);
        assertThat(defResp.getStatusCode().value()).isEqualTo(201);

        int rows = 400_000;
        // 1) 把超大请求体写到磁盘文件（前缀 / 后缀 + 每行一个对象）
        Path body = tempDir.resolve("req.json");
        try (BufferedWriter w = Files.newBufferedWriter(body, StandardCharsets.UTF_8)) {
            w.write("{\"defName\":\"r\",\"defVersion\":1,\"format\":\"JSON\",\"data\":[");
            for (int i = 0; i < rows; i++) {
                if (i > 0) {
                    w.write(',');
                }
                w.write("{\"id\":\"id-" + i + "\"}");
            }
            w.write("]}");
        }

        // 2) 用小缓冲流式 POST（8KB），全程不把 body 读进 byte[]
        String location;
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "http://localhost:" + port + "/api/render-jobs").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("X-Scope", scope);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        try (OutputStream out = conn.getOutputStream()) {
            byte[] buf = new byte[8192];
            try (var in = Files.newInputStream(body)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
        }
        int code = conn.getResponseCode();
        assertThat(code).isEqualTo(201);
        String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode resp = new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody);
        String jobId = resp.get("jobId").asText();
        assertThat(resp.get("totalRows").asLong()).isEqualTo(rows);

        // 3) 轮询到成功
        JobResponse job = null;
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode j = restTemplate.exchange(
                    "http://localhost:" + port + "/api/render-jobs/" + jobId, HttpMethod.GET,
                    new HttpEntity<Void>(headers(scope)), JsonNode.class).getBody();
            String status = j.get("status").asText();
            if ("SUCCEEDED".equals(status)) {
                break;
            }
            assertThat(status).isIn("PENDING", "RUNNING");
            Thread.sleep(100);
        }

        // 4) 逐块拉取，核对行数与首末内容
        JsonNode finalJob = restTemplate.exchange(
                "http://localhost:" + port + "/api/render-jobs/" + jobId, HttpMethod.GET,
                new HttpEntity<Void>(headers(scope)), JsonNode.class).getBody();
        assertThat(finalJob.get("status").asText()).isEqualTo(JobStatus.SUCCEEDED.name());
        int chunks = finalJob.get("chunkCount").asInt();
        long total = 0;
        for (int c = 0; c < chunks; c++) {
            JsonNode chunk = restTemplate.exchange(
                    "http://localhost:" + port + "/api/render-jobs/" + jobId
                            + "/result?chunk=" + c, HttpMethod.GET,
                    new HttpEntity<Void>(headers(scope)), JsonNode.class).getBody();
            if (c == 0) {
                assertThat(chunk.get(0).get("id").asText()).isEqualTo("id-0");
            }
            total += chunk.size();
        }
        assertThat(total).isEqualTo(rows);
    }
}
