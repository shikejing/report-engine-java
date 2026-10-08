package com.github.shikejing.reportenginejava.tests;

import com.github.shikejing.reportenginejava.api.dto.JobResponse;
import com.github.shikejing.reportenginejava.model.JobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 大输入下取消 / 超时及时生效测试：
 * 检查点（输入扫描、外部排序归并、分块产出之间）让标记不必等全部输入处理完；
 * 已产出分块整体丢弃，且不影响后续渲染。
 */
class LargeInputCancellationTest extends IntegrationTestBase {

    @Autowired
    private com.github.shikejing.reportenginejava.storage.JobStore jobStore;

    private List<Map<String, Object>> data(int n, boolean sorted) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // 带排序时使用大量重复键，验证取消检查点在归并阶段也能及时生效
            String key = sorted ? "k-" + (i % 97) : "id-" + i;
            data.add(row("id", "id-" + i, "key", key));
        }
        return data;
    }

    private String definition(String name, boolean withSort) {
        String cols = columns(col("id", "STRING"), col("key", "STRING"));
        if (withSort) {
            return "{\"name\":\"" + name + "\",\"columns\":" + cols
                    + ",\"sort\":{\"field\":\"key\"}}";
        }
        return "{\"name\":\"" + name + "\",\"columns\":" + cols + "}";
    }

    @Test
    void cancelLargeUnsortedJob_takesEffectPromptlyAndDiscardsChunks() throws Exception {
        assertCancelledPromptly(false, "s-lcancel-u");
    }

    @Test
    void cancelLargeSortedJob_takesEffectPromptlyAndDiscardsChunks() throws Exception {
        assertCancelledPromptly(true, "s-lcancel-s");
    }

    private void assertCancelledPromptly(boolean withSort, String scope) throws Exception {
        restTemplate.postForEntity("/api/definitions", entity(scope, definition("big", withSort)), Map.class);

        String req = renderRequest("big", 1, data(60_000, withSort), "JSON", 500, null);
        JobResponse submitted = submitRender(scope, req);
        String jobId = submitted.jobId();

        restTemplate.postForEntity("/api/render-jobs/" + jobId + "/cancel",
                entity(scope), JobResponse.class);

        long t0 = System.currentTimeMillis();
        JobResponse done = waitForTerminal(scope, jobId);
        long elapsed = System.currentTimeMillis() - t0;
        assertThat(done.status()).isEqualTo(JobStatus.CANCELLED);
        // 大输入下取消必须及时（处理完 6 万行远不止此）
        assertThat(elapsed).isLessThan(20_000);

        Path chunksDir = jobStore.jobDir(jobId).resolve("chunks");
        try (var chunks = Files.list(chunksDir)) {
            assertThat(chunks.count()).isZero();
        }
        try (var work = Files.list(jobStore.workDir(jobId))) {
            // input.jsonl 在作业目录而不是 work 下；work 内不应残留分块 / 归并段
            assertThat(work.filter(p -> !p.getFileName().toString().startsWith(".")).count()).isZero();
        }

        // 后续渲染不受影响
        String req2 = renderRequest("big", 1, List.of(row("id", "after", "key", "k-after")),
                "JSON", 100, null);
        JobResponse job2 = waitForTerminal(scope, submitRender(scope, req2).jobId());
        assertThat(job2.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(fetchChunkJson(scope, job2.jobId(), 0).toString()).contains("after");
    }

    @Test
    void timeoutLargeJob_failsPromptlyAndDiscardsChunks() throws Exception {
        String scope = "s-ltimeout";
        restTemplate.postForEntity("/api/definitions", entity(scope, definition("big", true)), Map.class);

        // 超时 50ms：输入扫描阶段的检查点即会触发，作业不可能跑完 12 万行
        String req = renderRequest("big", 1, data(60_000, true), "JSON", 500, 50L);
        long t0 = System.currentTimeMillis();
        JobResponse done = waitForTerminal(scope, submitRender(scope, req).jobId());
        long elapsed = System.currentTimeMillis() - t0;

        assertThat(done.status()).isEqualTo(JobStatus.FAILED);
        assertThat(done.error()).contains("timeout");
        assertThat(elapsed).isLessThan(20_000);
        try (var chunks = Files.list(jobStore.jobDir(done.jobId()).resolve("chunks"))) {
            assertThat(chunks.count()).isZero();
        }
    }
}
