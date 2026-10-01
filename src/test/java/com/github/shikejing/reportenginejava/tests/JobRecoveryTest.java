package com.github.shikejing.reportenginejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.reportenginejava.config.EngineProperties;
import com.github.shikejing.reportenginejava.model.JobStatus;
import com.github.shikejing.reportenginejava.model.RenderFormat;
import com.github.shikejing.reportenginejava.model.RenderJob;
import com.github.shikejing.reportenginejava.service.JobRecoveryService;
import com.github.shikejing.reportenginejava.storage.FileBasedJobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重启恢复测试：启动时仍处于 PENDING / RUNNING 的作业一律置为 FAILED，
 * 中间产物整体丢弃；已成功的作业及其结果保留。
 */
class JobRecoveryTest {

    @TempDir
    Path tempDir;

    private FileBasedJobStore jobStore() {
        EngineProperties props = new EngineProperties();
        props.setDataDir(tempDir);
        return new FileBasedJobStore(new ObjectMapper(), props);
    }

    private RenderJob job(String id, JobStatus status) {
        RenderJob j = new RenderJob();
        j.setJobId(id);
        j.setScope("s1");
        j.setDefName("d");
        j.setDefVersion(1);
        j.setFormatVersion(2);
        j.setFormat(RenderFormat.JSON);
        j.setStatus(status);
        j.setBatchSize(100);
        j.setTimeoutMs(60000);
        j.setCreatedAt(Instant.now());
        return j;
    }

    @Test
    void interruptedJobs_areMarkedFailedAndArtifactsDiscarded() throws Exception {
        FileBasedJobStore store = jobStore();
        JobRecoveryService recovery = new JobRecoveryService(store);

        // 一个 RUNNING 作业，带有已发布分块与临时文件
        RenderJob running = job("job-running", JobStatus.RUNNING);
        store.initJob(running);
        Files.writeString(store.chunkFile("job-running", 0), "chunk-data");
        Files.writeString(store.workDir("job-running").resolve("000001.json.tmp"), "tmp-data");

        // 一个 PENDING 作业
        RenderJob pending = job("job-pending", JobStatus.PENDING);
        store.initJob(pending);

        // 一个已成功作业（结果应保留）
        RenderJob succeeded = job("job-ok", JobStatus.SUCCEEDED);
        store.initJob(succeeded);
        Files.writeString(store.chunkFile("job-ok", 0), "ok-data");

        recovery.recoverOnStartup();

        RenderJob r1 = store.get("job-running").orElseThrow();
        assertThat(r1.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(r1.getError()).contains("restart");
        assertThat(Files.list(store.jobDir("job-running").resolve("chunks"))).isEmpty();
        assertThat(Files.exists(store.workDir("job-running"))
                ? Files.list(store.workDir("job-running")).count() : 0).isZero();

        RenderJob r2 = store.get("job-pending").orElseThrow();
        assertThat(r2.getStatus()).isEqualTo(JobStatus.FAILED);

        // 成功作业保留
        RenderJob r3 = store.get("job-ok").orElseThrow();
        assertThat(r3.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(Files.readString(store.chunkFile("job-ok", 0))).isEqualTo("ok-data");
    }
}
