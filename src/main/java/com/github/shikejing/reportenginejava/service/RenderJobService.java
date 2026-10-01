package com.github.shikejing.reportenginejava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.reportenginejava.config.ReportEngineProperties;
import com.github.shikejing.reportenginejava.dto.RenderBatch;
import com.github.shikejing.reportenginejava.exception.ConflictException;
import com.github.shikejing.reportenginejava.exception.NotFoundException;
import com.github.shikejing.reportenginejava.model.CallerPrincipal;
import com.github.shikejing.reportenginejava.model.JobStatus;
import com.github.shikejing.reportenginejava.model.ParameterDefinition;
import com.github.shikejing.reportenginejava.model.RenderJob;
import com.github.shikejing.reportenginejava.model.StoredDefinition;
import com.github.shikejing.reportenginejava.repository.FileSystemDefinitionRepository;
import com.github.shikejing.reportenginejava.repository.FileSystemRenderJobRepository;
import com.github.shikejing.reportenginejava.service.engine.VersionedRenderEngine;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class RenderJobService {

    private final FileSystemDefinitionRepository definitions;
    private final FileSystemRenderJobRepository jobs;
    private final VersionedRenderEngine engine;
    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final ReportEngineProperties properties;
    private final Clock clock;
    private final Semaphore renderPermits;
    private final ConcurrentHashMap<String, AtomicBoolean> cancellationFlags = new ConcurrentHashMap<>();

    public RenderJobService(FileSystemDefinitionRepository definitions,
                            FileSystemRenderJobRepository jobs,
                            VersionedRenderEngine engine,
                            AuthService authService,
                            ObjectMapper objectMapper,
                            ReportEngineProperties properties,
                            Clock clock) {
        this.definitions = definitions;
        this.jobs = jobs;
        this.engine = engine;
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.renderPermits = new Semaphore(Math.max(1, properties.getWorkerThreads()), true);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedJobs() {
        for (FileSystemRenderJobRepository.RecoveredJob recovered : jobs.findRecoverableJobs()) {
            RenderJob job = recovered.job();
            if (job.status() == JobStatus.PENDING || job.status() == JobStatus.RUNNING) {
                String message = "interrupted by process restart; safe to retry";
                jobs.discardTemporaryOutputs(job.scope(), job.jobId());
                jobs.updateStatus(terminate(job, JobStatus.FAILED, message, 0));
            }
        }
    }

    public RenderJob start(CallerPrincipal caller,
                           String reportKey,
                           Integer requestedVersion,
                           InputStream input,
                           Map<String, Object> requestParameters) {
        authService.requireReader(caller, caller.scope());
        StoredDefinition stored = resolveDefinition(caller, reportKey, requestedVersion);
        Map<String, Object> parameters = resolveParameters(stored, requestParameters);

        String jobId = jobs.newJobId();
        Instant now = Instant.now(clock);
        RenderJob pending = new RenderJob(
                jobId,
                caller.scope(),
                reportKey,
                stored.version(),
                JobStatus.PENDING,
                null,
                0,
                now,
                now,
                null,
                now.plus(properties.getRenderTimeout()).toEpochMilli()
        );
        RenderJob created = jobs.create(pending, stored);
        cancellationFlags.put(jobId, new AtomicBoolean(false));
        renderPermits.acquireUninterruptibly();
        FileSystemRenderJobRepository.RenderOutputSession session = null;
        long produced = 0;
        try {
            RenderJob running = jobs.markRunningIfActive(caller.scope(), jobId, toRunning(created));
            if (running == null) {
                return jobs.find(caller.scope(), jobId).orElseThrow();
            }
            ensureActive(created);
            session = jobs.openOutputSession(caller.scope(), jobId, properties.getBatchSize());
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8), 8192)) {
                String line;
                while ((line = readNextLine(reader)) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    ensureActive(created);
                    Map<String, Object> row = readInputLine(line);
                    Map<String, Object> renderedRow = engine.renderRow(stored, row, parameters);
                    session.writeRow(renderedRow);
                    produced++;
                    ensureActive(created);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            ensureActive(created);
            RenderJob succeeded = terminate(created, JobStatus.SUCCEEDED, null, produced);
            if (!jobs.publishIfActive(caller.scope(), jobId, session, succeeded)) {
                return jobs.find(caller.scope(), jobId).orElseThrow();
            }
            session = null;
            return succeeded;
        } catch (RenderCancellationException e) {
            closeQuietly(session);
            jobs.discardTemporaryOutputs(caller.scope(), jobId);
            RenderJob current = jobs.find(caller.scope(), jobId).orElseThrow();
            if (current.status() == JobStatus.CANCELLED) {
                return current;
            }
            RenderJob cancelled = terminate(created, JobStatus.CANCELLED, e.getMessage(), 0);
            jobs.updateStatus(cancelled);
            return cancelled;
        } catch (RuntimeException e) {
            closeQuietly(session);
            jobs.discardTemporaryOutputs(caller.scope(), jobId);
            RenderJob currentJob = jobs.find(caller.scope(), jobId).orElse(null);
            if (currentJob != null && currentJob.status() == JobStatus.CANCELLED) {
                return currentJob;
            }
            RenderJob failed = terminate(created, JobStatus.FAILED, rootMessage(e), 0);
            jobs.updateStatus(failed);
            return failed;
        } finally {
            cancellationFlags.remove(jobId);
            renderPermits.release();
        }
    }

    public RenderJob status(CallerPrincipal caller, String jobId) {
        return requireJob(caller, jobId);
    }

    public RenderBatch batch(CallerPrincipal caller, String jobId, long offset, Integer requestedLimit) {
        RenderJob job = requireJob(caller, jobId);
        if (job.status() != JobStatus.SUCCEEDED) {
            throw new ConflictException("rendered output is available only after SUCCEEDED");
        }
        int maxBatchSize = properties.getBatchSize();
        int limit = Math.min(Math.max(1, requestedLimit == null ? maxBatchSize : requestedLimit),
                maxBatchSize);
        List<Map<String, Object>> rows = jobs.readBatch(caller.scope(), jobId, offset, limit + 1,
                job.totalRows(), maxBatchSize);
        boolean end = rows.size() <= limit;
        List<Map<String, Object>> page = end ? rows : List.copyOf(rows.subList(0, limit));
        return new RenderBatch(jobId, offset, page, end);
    }

    public RenderJob cancel(CallerPrincipal caller, String jobId) {
        RenderJob job = requireJob(caller, jobId);
        if (job.status() != JobStatus.PENDING && job.status() != JobStatus.RUNNING) {
            return job;
        }
        AtomicBoolean flag = cancellationFlags.get(jobId);
        if (flag != null) {
            flag.set(true);
        }
        RenderJob cancelled = terminate(job, JobStatus.CANCELLED, "render cancelled", 0);
        if (jobs.cancelIfActive(caller.scope(), jobId, cancelled)) {
            return cancelled;
        }
        return jobs.find(caller.scope(), jobId).orElse(cancelled);
    }

    private void ensureActive(RenderJob original) {
        AtomicBoolean cancelled = cancellationFlags.get(original.jobId());
        if (cancelled != null && cancelled.get()) {
            throw new RenderCancellationException("render cancelled");
        }
        RenderJob current = jobs.find(original.scope(), original.jobId())
                .orElseThrow(() -> new IllegalStateException("missing job metadata"));
        if (current.status() == JobStatus.CANCELLED) {
            throw new RenderCancellationException("render cancelled");
        }
        if (current.status() != JobStatus.PENDING && current.status() != JobStatus.RUNNING) {
            throw new RenderCancellationException("render is no longer active");
        }
        if (Instant.now(clock).toEpochMilli() >= original.timeoutAtEpochMilli()) {
            throw new RenderCancellationException("render timed out");
        }
    }

    private String readNextLine(BufferedReader reader) throws IOException {
        return reader.readLine();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readInputLine(String line) {
        try {
            Map<String, Object> row = objectMapper.readValue(line, LinkedHashMap.class);
            if (row == null) {
                throw new IllegalArgumentException("input row must be a JSON object");
            }
            return row;
        } catch (IOException e) {
            throw new UncheckedIOException("invalid JSON input row", e);
        }
    }

    private RenderJob toRunning(RenderJob job) {
        Instant now = Instant.now(clock);
        return new RenderJob(
                job.jobId(), job.scope(), job.reportKey(), job.definitionVersion(),
                JobStatus.RUNNING, null, 0, job.createdAt(), now, null,
                job.timeoutAtEpochMilli()
        );
    }

    private RenderJob terminate(RenderJob job, JobStatus status, String message, long rows) {
        Instant now = Instant.now(clock);
        return new RenderJob(
                job.jobId(), job.scope(), job.reportKey(), job.definitionVersion(),
                status, message, rows, job.createdAt(), now, now, job.timeoutAtEpochMilli()
        );
    }

    private StoredDefinition resolveDefinition(CallerPrincipal caller, String reportKey, Integer version) {
        Optional<StoredDefinition> optional = version == null
                ? definitions.findLatest(caller.scope(), reportKey)
                : definitions.find(caller.scope(), reportKey, version);
        StoredDefinition stored = optional.orElse(null);
        if (stored == null || !stored.scope().equals(caller.scope())) {
            throw new NotFoundException("report definition not found");
        }
        return stored;
    }

    private Map<String, Object> resolveParameters(StoredDefinition stored,
                                                  Map<String, Object> requestParameters) {
        Map<String, Object> supplied = requestParameters == null ? Map.of() : requestParameters;
        Map<String, Object> result = new HashMap<>();
        if (stored.definition().parameters() != null) {
            for (ParameterDefinition definition : stored.definition().parameters()) {
                if (supplied.containsKey(definition.name())) {
                    result.put(definition.name(), supplied.get(definition.name()));
                } else if (definition.defaultValue() != null) {
                    result.put(definition.name(), definition.defaultValue());
                } else if (definition.required()) {
                    throw new NotFoundException("missing required parameter " + definition.name());
                }
            }
        }
        for (String name : supplied.keySet()) {
            if (!result.containsKey(name)) {
                throw new NotFoundException("unknown parameter " + name);
            }
        }
        return Map.copyOf(result);
    }

    private RenderJob requireJob(CallerPrincipal caller, String jobId) {
        RenderJob job = jobs.find(caller.scope(), jobId).orElse(null);
        if (job == null || !job.scope().equals(caller.scope())) {
            throw new NotFoundException("render job not found");
        }
        return job;
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Temporary output is deleted by the caller.
            }
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? "render failed" : message;
    }

    private static class RenderCancellationException extends RuntimeException {
        private RenderCancellationException(String message) {
            super(message);
        }
    }
}
