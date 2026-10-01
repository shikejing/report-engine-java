package com.github.shikejing.reportenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定义更新与读取并发测试：
 * <ul>
 *   <li>写入期间并发读取不会看到半写状态（无 5xx、无残缺内容）；</li>
 *   <li>读取方拿到的总是某一个已提交的完整版本；</li>
 *   <li>历史版本（v1）的读取结果在更新期间保持不变。</li>
 * </ul>
 */
class DefinitionReadWriteConcurrencyTest extends IntegrationTestBase {

    @Test
    void concurrentUpdatesAndReads_neverObservePartialVersion() throws Exception {
        String scope = "s-rw";
        // 初始版本
        String v1 = definitionJson("rw", columns(col("c0", "STRING")));
        restTemplate.postForEntity("/api/definitions", entity(scope, v1), Map.class);

        int writerIterations = 30;
        int readerIterations = 120;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            AtomicInteger errors = new AtomicInteger();

            // 写者：不断提交新版本（每版多一列，vN 即 N 列）
            Callable<Void> writer = () -> {
                for (int v = 2; v <= writerIterations + 1; v++) {
                    List<String> cols = new ArrayList<>();
                    for (int c = 0; c < v; c++) {
                        cols.add(col("c" + c, "STRING"));
                    }
                    String body = definitionJson("rw", "[" + String.join(",", cols) + "]");
                    ResponseEntity<Map> resp = restTemplate.postForEntity(
                            "/api/definitions", entity(scope, body), Map.class);
                    if (!resp.getStatusCode().is2xxSuccessful()) {
                        errors.incrementAndGet();
                    }
                }
                return null;
            };

            // 读者：不断读 latest 与 pinned v1
            Callable<Void> reader = () -> {
                for (int i = 0; i < readerIterations; i++) {
                    // latest：必须完整，且列数 == 版本号
                    ResponseEntity<JsonNode> latest = restTemplate.exchange(
                            "/api/definitions/rw", HttpMethod.GET, entity(scope), JsonNode.class);
                    if (!latest.getStatusCode().is2xxSuccessful() || latest.getBody() == null) {
                        errors.incrementAndGet();
                        continue;
                    }
                    JsonNode body = latest.getBody();
                    int version = body.path("version").asInt();
                    int colCount = body.path("content").path("columns").size();
                    if (version < 1 || colCount != version
                            || !"rw".equals(body.path("name").asText())) {
                        errors.incrementAndGet();
                    }

                    // pinned v1：永远是 1 列
                    ResponseEntity<JsonNode> v1resp = restTemplate.exchange(
                            "/api/definitions/rw/versions/1", HttpMethod.GET, entity(scope), JsonNode.class);
                    if (!v1resp.getStatusCode().is2xxSuccessful() || v1resp.getBody() == null) {
                        errors.incrementAndGet();
                        continue;
                    }
                    int v1cols = v1resp.getBody().path("content").path("columns").size();
                    int v1version = v1resp.getBody().path("version").asInt();
                    if (v1cols != 1 || v1version != 1) {
                        errors.incrementAndGet();
                    }
                }
                return null;
            };

            List<Callable<Void>> tasks = new ArrayList<>();
            tasks.add(writer);
            for (int i = 0; i < 4; i++) {
                tasks.add(reader);
            }
            List<Future<Void>> futures = pool.invokeAll(tasks);
            for (Future<Void> f : futures) {
                f.get();
            }

            assertThat(errors.get()).isZero();

            // 最终版本号正确
            ResponseEntity<JsonNode> latest = restTemplate.exchange(
                    "/api/definitions/rw", HttpMethod.GET, entity(scope), JsonNode.class);
            assertThat(latest.getBody().path("version").asInt()).isEqualTo(writerIterations + 1);
        } finally {
            pool.shutdownNow();
        }
    }
}
