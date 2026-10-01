package com.github.shikejing.reportenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.reportenginejava.api.dto.JobResponse;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;

/**
 * 集成测试基类：使用临时数据目录，随机端口启动，提供作用域头与常用请求方法。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("report.engine.data-dir", () -> tempDir.toString());
        registry.add("report.engine.render.core-pool-size", () -> "2");
        registry.add("report.engine.render.max-pool-size", () -> "4");
        registry.add("report.engine.render.queue-capacity", () -> "50");
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    protected HttpHeaders headers(String scope) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Scope", scope);
        h.set("Content-Type", "application/json");
        return h;
    }

    protected HttpEntity<String> entity(String scope, String body) {
        return new HttpEntity<>(body, headers(scope));
    }

    protected HttpEntity<Void> entity(String scope) {
        return new HttpEntity<>(headers(scope));
    }

    protected String definitionJson(String name, String columnsJson) throws Exception {
        return "{\"name\":\"" + name + "\",\"columns\":" + columnsJson + "}";
    }

    protected String columns(String... cols) {
        return "[" + String.join(",", cols) + "]";
    }

    protected String col(String field, String type) {
        return "{\"field\":\"" + field + "\",\"type\":\"" + type + "\"}";
    }

    protected String col(String field, String type, String format) {
        return "{\"field\":\"" + field + "\",\"type\":\"" + type + "\",\"format\":\"" + format + "\"}";
    }

    protected String colTransform(String field, String type, String transform) {
        return "{\"field\":\"" + field + "\",\"type\":\"" + type + "\",\"transform\":\"" + transform + "\"}";
    }

    /** 构造一行数据：row("id", 1, "name", "a")。 */
    protected Map<String, Object> row(Object... kv) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    protected String renderRequest(String defName, Object data) throws Exception {
        return renderRequest(defName, null, data, "JSON", null, null);
    }

    protected String renderRequest(String defName, Integer defVersion, Object data,
                                   String format, Integer batchSize, Long timeoutMs) throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("defName", defName);
        if (defVersion != null) {
            node.put("defVersion", defVersion);
        }
        node.set("data", objectMapper.valueToTree(data));
        if (format != null) {
            node.put("format", format);
        }
        if (batchSize != null) {
            node.put("batchSize", batchSize);
        }
        if (timeoutMs != null) {
            node.put("timeoutMs", timeoutMs);
        }
        return objectMapper.writeValueAsString(node);
    }

    protected JobResponse submitRender(String scope, String reqBody) {
        ResponseEntity<JobResponse> resp = restTemplate.postForEntity(
                "/api/render-jobs", entity(scope, reqBody), JobResponse.class);
        return resp.getBody();
    }

    protected JobResponse waitForTerminal(String scope, String jobId) {
        return await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    ResponseEntity<JobResponse> resp = restTemplate.exchange(
                            "/api/render-jobs/" + jobId, HttpMethod.GET,
                            entity(scope), JobResponse.class);
                    JobResponse j = resp.getBody();
                    if (j == null) {
                        return null;
                    }
                    return switch (j.status()) {
                        case SUCCEEDED, FAILED, CANCELLED -> j;
                        default -> null;
                    };
                }, java.util.Objects::nonNull);
    }

    protected JsonNode fetchChunkJson(String scope, String jobId, int chunk) {
        ResponseEntity<JsonNode> resp = restTemplate.exchange(
                "/api/render-jobs/" + jobId + "/result?chunk=" + chunk, HttpMethod.GET,
                entity(scope), JsonNode.class);
        return resp.getBody();
    }

    protected String fetchChunkCsv(String scope, String jobId, int chunk) {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/render-jobs/" + jobId + "/result?chunk=" + chunk, HttpMethod.GET,
                entity(scope), String.class);
        return resp.getBody();
    }
}
