package com.github.shikejing.reportenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shikejing.reportenginejava.api.dto.JobResponse;
import com.github.shikejing.reportenginejava.model.JobStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发渲染测试：大量并发渲染请求互不干扰，
 * 同一份定义版本 + 同一份输入数据，结果逐字节一致。
 */
class ConcurrentRenderTest extends IntegrationTestBase {

    @Test
    void manyConcurrentRenders_produceByteIdenticalResults() throws Exception {
        String scope = "s-conc";
        String def = definitionJson("report",
                columns(col("id", "STRING"), col("amount", "NUMBER", "#.00"), col("when", "DATE")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);

        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            data.add(row("id", "id-" + i, "amount", i * 1.5, "when", "2024-01-01"));
        }

        int concurrency = 20;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                tasks.add(() -> {
                    String req = renderRequest("report", 1, data, "JSON", 137, null);
                    JobResponse job = submitRender(scope, req);
                    return job.jobId();
                });
            }
            List<Future<String>> futures = pool.invokeAll(tasks);
            List<String> jobIds = new ArrayList<>();
            for (Future<String> f : futures) {
                jobIds.add(f.get());
            }

            // 等待全部完成
            List<byte[]> results = new ArrayList<>();
            for (String jobId : jobIds) {
                JobResponse done = waitForTerminal(scope, jobId);
                assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);
                JsonNode chunk = fetchChunkJson(scope, jobId, 0);
                results.add(objectMapper.writeValueAsBytes(chunk));
            }

            // 所有结果逐字节一致
            byte[] first = results.get(0);
            for (byte[] r : results) {
                assertThat(r).isEqualTo(first);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRenders_withDifferentData_areNotMixed() throws Exception {
        String scope = "s-conc2";
        String def = definitionJson("r", columns(col("id", "STRING")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);

        int concurrency = 8;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                final int idx = i;
                tasks.add(() -> {
                    List<Map<String, Object>> data = List.of(row("id", "row-" + idx));
                    String req = renderRequest("r", 1, data, "JSON", 50, null);
                    return submitRender(scope, req).jobId();
                });
            }
            List<Future<String>> futures = pool.invokeAll(tasks);
            for (Future<String> f : futures) {
                String jobId = f.get();
                waitForTerminal(scope, jobId);
                JsonNode chunk = fetchChunkJson(scope, jobId, 0);
                // 每个作业只包含自己的数据，不与其他作业拼接
                String text = objectMapper.writeValueAsString(chunk);
                assertThat(text).contains("row-").doesNotContain("row-" + (idxOf(futures, f) + 1));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static int idxOf(List<Future<String>> futures, Future<String> f) {
        return futures.indexOf(f);
    }
}
