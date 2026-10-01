package com.github.shikejing.reportenginejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.reportenginejava.model.JobStatus;
import com.github.shikejing.reportenginejava.model.RenderJob;
import com.github.shikejing.reportenginejava.model.StoredDefinition;
import com.github.shikejing.reportenginejava.repository.FileSystemDefinitionRepository;
import com.github.shikejing.reportenginejava.repository.FileSystemRenderJobRepository;
import com.github.shikejing.reportenginejava.repository.FileSystemRenderJobRepository;
import com.github.shikejing.reportenginejava.service.RenderJobService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RecoveryAndCancellationTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    RenderJobService renderJobService;
    @Autowired
    FileSystemDefinitionRepository definitions;
    @Autowired
    FileSystemRenderJobRepository jobs;
    @Autowired
    Clock clock;

    @Test
    void cancelledRenderHasNoPartialOutputAndFutureRendersAreUnaffected() throws Exception {
        createDefinition("cancellable", "tenant-cancel");
        InputStream infiniteRows = new InputStream() {
            private int count = 0;
            private byte[] current = new byte[0];
            private int index;

            @Override
            public int read() {
                if (index >= current.length) {
                    if (count++ >= 10_000) {
                        return -1;
                    }
                    String row = "{\"customer\":\"c" + count + "\",\"amount\":1}\n";
                    current = row.getBytes(StandardCharsets.UTF_8);
                    index = 0;
                }
                return current[index++] & 0xff;
            }
        };
        Thread render = new Thread(() -> {
            try {
                renderJobService.start(caller("tenant-cancel"), "cancellable", null,
                        infiniteRows, Map.of());
            } catch (Exception ignored) {
                // The test observes the persisted terminal state.
            }
        });
        render.start();
        String jobId = waitForRunningJob("tenant-cancel");

        mockMvc.perform(post("/api/reports/renders/{id}/cancel", jobId)
                        .header("X-Scope", "tenant-cancel")
                        .header("X-Role", "READER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        render.join(10_000);
        assertTrue(!render.isAlive());

        mockMvc.perform(get("/api/reports/renders/{id}/rows", jobId)
                        .header("X-Scope", "tenant-cancel")
                        .header("X-Role", "READER"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/reports/cancellable/renders")
                        .header("X-Scope", "tenant-cancel")
                        .header("X-Role", "READER")
                        .contentType(ApiTestSupport.NDJSON)
                        .content(ApiTestSupport.ndjson(Map.of("customer", "next", "amount", 12))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.totalRows").value(1));
    }

    @Test
    void runningJobLeftByRestartIsRecoveredToExplainedFailure() throws Exception {
        createDefinition("recoverable", "tenant-restart");
        StoredDefinition definition = definitions.findLatest("tenant-restart", "recoverable").orElseThrow();
        Instant now = Instant.now(clock);
        RenderJob running = new RenderJob(
                "11111111-1111-1111-1111-111111111111",
                "tenant-restart",
                "recoverable",
                definition.version(),
                JobStatus.RUNNING,
                null,
                5,
                now,
                now,
                null,
                now.plusSeconds(60).toEpochMilli()
        );
        jobs.create(running, definition);
        try (FileSystemRenderJobRepository.RenderOutputSession orphan =
                     jobs.openOutputSession("tenant-restart", running.jobId(), 2)) {
            orphan.writeRow(java.util.Map.of("customer", "partial", "tax", 0));
        }

        renderJobService.recoverInterruptedJobs();
        RenderJob recovered = jobs.find("tenant-restart", running.jobId()).orElseThrow();
        assertEquals(JobStatus.FAILED, recovered.status());
        assertEquals(0, recovered.totalRows());
        assertTrue(recovered.errorMessage().contains("restart"));
        assertTrue(jobs.readBatch("tenant-restart", running.jobId(), 0, 10,
                recovered.totalRows(), 2).isEmpty());
    }


    @Test
    void timedOutRenderDoesNotPublishPartialOutput() throws Exception {
        createDefinition("timeout-report", "tenant-timeout");
        String firstRow = """
                {"customer":"a","amount":1}
                """;
        InputStream delayed = new InputStream() {
            private byte[] first = firstRow.getBytes(StandardCharsets.UTF_8);
            private int index;
            private boolean waited;

            @Override
            public int read() throws java.io.IOException {
                if (index < first.length) {
                    return first[index++] & 0xff;
                }
                if (!waited) {
                    waited = true;
                    try {
                        Thread.sleep(1_500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException(e);
                    }
                    return -1;
                }
                return -1;
            }
        };

        RenderJob job = renderJobService.start(caller("tenant-timeout"), "timeout-report", null,
                delayed, Map.of());
        org.junit.jupiter.api.Assertions.assertEquals(JobStatus.CANCELLED, job.status());
        org.junit.jupiter.api.Assertions.assertTrue(job.errorMessage().contains("timed out"));
        org.junit.jupiter.api.Assertions.assertEquals(0, job.totalRows());
        org.junit.jupiter.api.Assertions.assertTrue(jobs.readBatch("tenant-timeout", job.jobId(), 0, 10,
                job.totalRows(), 2).isEmpty());
    }


    @Test
    void queuedPendingRenderCancelledBeforePermitIsAvailableStaysCancelled() throws Exception {
        createDefinition("queued-cancel", "tenant-queue");
        int workers = 8;
        var everBlocked = new java.util.concurrent.CountDownLatch(workers);
        var releaseWorkers = new java.util.concurrent.CountDownLatch(1);
        List<Thread> blocking = new java.util.ArrayList<>();
        for (int i = 0; i < workers; i++) {
            InputStream blockingInput = new InputStream() {
                @Override
                public int read() throws java.io.IOException {
                    everBlocked.countDown();
                    try {
                        releaseWorkers.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException(e);
                    }
                    return -1;
                }
            };
            Thread worker = new Thread(() -> renderJobService.start(caller("tenant-queue"),
                    "queued-cancel", null, blockingInput, Map.of()));
            worker.start();
            blocking.add(worker);
        }
        org.junit.jupiter.api.Assertions.assertTrue(everBlocked.await(5, java.util.concurrent.TimeUnit.SECONDS));

        var secondStarted = new java.util.concurrent.CountDownLatch(1);
        InputStream secondInput = new InputStream() {
            @Override
            public int read() {
                secondStarted.countDown();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
                return -1;
            }
        };
        java.util.concurrent.Future<RenderJob> queued;
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            queued = executor.submit(() -> renderJobService.start(caller("tenant-queue"),
                    "queued-cancel", null, secondInput, Map.of()));
            String queuedJobId = waitForJobStatus("tenant-queue", JobStatus.PENDING);
            RenderJob cancelled = renderJobService.cancel(caller("tenant-queue"), queuedJobId);
            assertEquals(JobStatus.CANCELLED, cancelled.status());

            secondStarted.await(5, java.util.concurrent.TimeUnit.SECONDS);
            releaseWorkers.countDown();
            RenderJob result = queued.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(JobStatus.CANCELLED, result.status());
            org.junit.jupiter.api.Assertions.assertTrue(jobs.readBatch("tenant-queue", queuedJobId,
                    0, 10, result.totalRows(), 2).isEmpty());
        } finally {
            executor.shutdownNow();
        }
        for (Thread worker : blocking) {
            worker.join(5_000);
        }
    }

    private String waitForRunningJob(String scope) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            for (FileSystemRenderJobRepository.RecoveredJob recovered : jobs.findRecoverableJobs()) {
                if (scope.equals(recovered.job().scope())
                        && (recovered.job().status() == JobStatus.RUNNING
                        || recovered.job().status() == JobStatus.PENDING)) {
                    return recovered.job().jobId();
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("running job was not observed");
    }

    private String waitForJobStatus(String scope, JobStatus expected) throws InterruptedException {
        for (int i = 0; i < 250; i++) {
            for (FileSystemRenderJobRepository.RecoveredJob recovered : jobs.findRecoverableJobs()) {
                if (scope.equals(recovered.job().scope())
                        && recovered.job().status() == expected) {
                    return recovered.job().jobId();
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("job did not reach " + expected);
    }

    private com.github.shikejing.reportenginejava.model.CallerPrincipal caller(String scope) {
        return new com.github.shikejing.reportenginejava.model.CallerPrincipal(scope, "READER");
    }

    private void createDefinition(String key, String scope) throws Exception {
        String json = objectMapper.writeValueAsString(ApiTestSupport.salesReport(true))
                .replace("\"reportKey\":\"sales\"", "\"reportKey\":\"" + key + "\"");
        mockMvc.perform(ApiTestSupport.scope(put("/api/reports/" + key), scope)
                        .header("X-Engine-Version", 2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }
}
