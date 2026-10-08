package com.github.shikejing.reportenginejava.tests;

import com.github.shikejing.reportenginejava.api.dto.JobResponse;
import com.github.shikejing.reportenginejava.model.JobStatus;
import com.github.shikejing.reportenginejava.storage.JobStore;
import org.junit.jupiter.api.Test;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 大输入下取消 / 超时及时生效测试。
 *
 * <p>故意把 batchSize 配得很大，使「分块边界」上的检查点很少 —— 取消 / 超时
 * 必须由贯穿读取、校验、外部排序、归并、输出全阶段的固定行数检查点来保证。
 * 断言作业在发出取消（或超时）后很快进入终态，而不是长时间停在 RUNNING，
 * 且已产出的分块被整体丢弃，后续渲染不受影响。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LargeInputAbortTest {

    @TempDir
    static Path tempDir;

    /** 上限放开，允许大输入。 */
    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("report.engine.data-dir", () -> tempDir.toString());
        registry.add("report.engine.render.max-rows", () -> "2000000");
        registry.add("report.engine.render.default-timeout-ms", () -> "600000");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JobStore jobStore;

    private static final int ROWS = 300_000;
    /** 每批很大：整个作业只有数个分块边界，取消必须靠固定行数检查点生效。 */
    private static final int BIG_BATCH = 100_000;

    private HttpHeaders headers(String scope) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Scope", scope);
        h.set("Content-Type", "application/json");
        return h;
    }

    /** 构造带排序定义的大输入请求（排序会触发外部排序，覆盖建段 / 归并阶段的中止）。 */
    private String sortedRequest(String name) {
        StringBuilder sb = new StringBuilder(
                "{\"defName\":\"").append(name).append("\",\"defVersion\":1,\"batchSize\":")
                .append(BIG_BATCH).append(",\"data\":[");
        for (int i = 0; i < ROWS; i++) {
            if (i > 0) {
                sb.append(',');
            }
            // 逆序字符串，制造真实排序工作量
            sb.append("{\"id\":\"k").append(ROWS - i).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String unsortedRequest(String name, Integer timeoutMs) {
        StringBuilder sb = new StringBuilder(
                "{\"defName\":\"").append(name).append("\",\"defVersion\":1,\"batchSize\":")
                .append(BIG_BATCH);
        if (timeoutMs != null) {
            sb.append(",\"timeoutMs\":").append(timeoutMs);
        }
        sb.append(",\"data\":[");
        for (int i = 0; i < ROWS; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"id-").append(i).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private void createDef(String scope, String name, boolean sorted) {
        String cols = "[{\"field\":\"id\",\"type\":\"STRING\"}]";
        String sort = sorted ? ",\"sort\":{\"field\":\"id\",\"direction\":\"ASC\"}" : "";
        String def = "{\"name\":\"" + name + "\",\"columns\":" + cols + sort + "}";
        ResponseEntity<Map> r = restTemplate.exchange("/api/definitions", HttpMethod.POST,
                new HttpEntity<>(def, headers(scope)), Map.class);
        assertThat(r.getStatusCode().value()).isEqualTo(201);
    }

    private JobResponse submit(String scope, String body) {
        ResponseEntity<JobResponse> r = restTemplate.exchange("/api/render-jobs", HttpMethod.POST,
                new HttpEntity<>(body, headers(scope)), JobResponse.class);
        assertThat(r.getStatusCode().value()).isEqualTo(201);
        return r.getBody();
    }

    private JobResponse pollUntilTerminal(String scope, String jobId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JobResponse last = null;
        while (System.currentTimeMillis() < deadline) {
            last = restTemplate.exchange("/api/render-jobs/" + jobId, HttpMethod.GET,
                    new HttpEntity<Void>(headers(scope)), JobResponse.class).getBody();
            if (last != null && last.status().isTerminal()) {
                return last;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("job " + jobId + " stuck at " + (last == null ? "null" : last.status())
                + " for " + timeoutMs + "ms");
    }

    private void assertNoArtifacts(String jobId) throws Exception {
        Path chunks = jobStore.jobDir(jobId).resolve("chunks");
        if (Files.isDirectory(chunks)) {
            try (Stream<Path> s = Files.list(chunks)) {
                assertThat(s).isEmpty();
            }
        }
        Path work = jobStore.workDir(jobId);
        if (Files.isDirectory(work)) {
            // 允许目录里留下空的层级结构，但不允许残留排序段 / 临时分块
            try (Stream<Path> s = Files.walk(work)) {
                assertThat(s.filter(Files::isRegularFile)).isEmpty();
            }
        }
    }

    @Test
    void cancelDuringLargeSortedRender_takesEffectPromptlyAndDiscardsChunks() throws Exception {
        String scope = "s-big-cancel";
        createDef(scope, "big", true);
        JobResponse job = submit(scope, sortedRequest("big"));

        // 让渲染真正开始后立即取消（此时大概率正在校验 / 外部排序建段）
        Thread.sleep(300);
        restTemplate.exchange("/api/render-jobs/" + job.jobId() + "/cancel", HttpMethod.POST,
                new HttpEntity<Void>(headers(scope)), JobResponse.class);

        long start = System.currentTimeMillis();
        JobResponse done = pollUntilTerminal(scope, job.jobId(), 20_000);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(done.status()).isEqualTo(JobStatus.CANCELLED);
        // 必须很快生效，而不是等整份 30 万行处理完
        assertThat(elapsed).isLessThan(10_000);
        assertNoArtifacts(job.jobId());

        // 后续渲染不受影响
        String req = "{\"defName\":\"big\",\"defVersion\":1,\"data\":[{\"id\":\"after\"}]}";
        JobResponse job2 = pollUntilTerminal(scope, submit(scope, req).jobId(), 20_000);
        assertThat(job2.status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void timeoutDuringLargeRender_failsPromptlyAndDiscardsChunks() throws Exception {
        String scope = "s-big-timeout";
        createDef(scope, "big2", false);
        // 超时 300ms：作业必须在数百毫秒级的检查点粒度上中止，而非跑完全部 30 万行
        JobResponse job = submit(scope, unsortedRequest("big2", 300));

        long start = System.currentTimeMillis();
        JobResponse done = pollUntilTerminal(scope, job.jobId(), 20_000);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(done.status()).isEqualTo(JobStatus.FAILED);
        assertThat(done.error()).contains("timeout");
        assertThat(elapsed).isLessThan(10_000);
        assertNoArtifacts(job.jobId());
    }
}
