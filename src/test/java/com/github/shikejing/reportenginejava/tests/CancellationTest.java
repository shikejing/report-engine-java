package com.github.shikejing.reportenginejava.tests;

import com.github.shikejing.reportenginejava.api.dto.JobResponse;
import com.github.shikejing.reportenginejava.model.JobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.github.shikejing.reportenginejava.storage.JobStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 取消测试：渲染中途取消后，作业置为 CANCELLED，
 * 已产生的中间分块整体丢弃，且不影响后续渲染。
 */
class CancellationTest extends IntegrationTestBase {

    @Autowired
    JobStore jobStore;

    @Test
    void cancelMidRender_discardsChunksAndDoesNotAffectLaterRenders() throws Exception {
        String scope = "s-cancel";
        String def = definitionJson("big", columns(col("id", "STRING")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);

        // 大批量 + 每批 1 行，制造慢速作业
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < 30000; i++) {
            data.add(row("id", "id-" + i));
        }
        String req = renderRequest("big", 1, data, "JSON", 1, null);
        JobResponse submitted = submitRender(scope, req);
        String jobId = submitted.jobId();

        // 立即取消
        restTemplate.postForEntity("/api/render-jobs/" + jobId + "/cancel",
                entity(scope), JobResponse.class);

        JobResponse done = waitForTerminal(scope, jobId);
        // 取消必须生效：作业不得成功
        assertThat(done.status()).isIn(JobStatus.CANCELLED, JobStatus.FAILED);

        // 中间分块整体丢弃
        Path chunksDir = jobStore.jobDir(jobId).resolve("chunks");
        Path workDir = jobStore.workDir(jobId);
        assertThat(Files.list(chunksDir).count()).isZero();
        assertThat(Files.exists(workDir) ? Files.list(workDir).count() : 0).isZero();

        // 后续渲染不受影响：新作业正常成功
        String req2 = renderRequest("big", 1, List.of(row("id", "after")), "JSON", 100, null);
        JobResponse job2 = waitForTerminal(scope, submitRender(scope, req2).jobId());
        assertThat(job2.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(fetchChunkJson(scope, job2.jobId(), 0).toString()).contains("after");
    }

    @Test
    void cancelAlreadyTerminal_isIdempotent() throws Exception {
        String scope = "s-cancel2";
        String def = definitionJson("d", columns(col("id", "STRING")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);
        String req = renderRequest("d", 1, List.of(row("id", "x")), "JSON", 100, null);
        JobResponse job = waitForTerminal(scope, submitRender(scope, req).jobId());
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);

        // 对已终态作业取消，幂等返回原状态
        JobResponse cancelResp = restTemplate.postForEntity(
                "/api/render-jobs/" + job.jobId() + "/cancel", entity(scope), JobResponse.class).getBody();
        assertThat(cancelResp.status()).isEqualTo(JobStatus.SUCCEEDED);
    }
}
