package com.github.shikejing.reportenginejava.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.shikejing.reportenginejava.config.EngineProperties;
import com.github.shikejing.reportenginejava.model.JobStatus;
import com.github.shikejing.reportenginejava.model.RenderFormat;
import com.github.shikejing.reportenginejava.model.RenderJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 基于文件的渲染作业存储。
 *
 * <p>目录结构：
 * <pre>
 * {dataDir}/jobs/{jobId}/
 *   job.json          # 作业状态（原子更新）
 *   chunks/000001.json  # 已发布的分块结果（原子发布）
 *   work/              # 渲染中的临时分块，失败/取消时整体丢弃
 * </pre>
 */
@Component
public class FileBasedJobStore implements JobStore {

    private static final Logger log = LoggerFactory.getLogger(FileBasedJobStore.class);

    private final ObjectMapper objectMapper;
    private final Path jobsRoot;

    public FileBasedJobStore(ObjectMapper objectMapper, EngineProperties properties) {
        this.objectMapper = objectMapper;
        this.jobsRoot = properties.getDataDir().resolve("jobs");
        try {
            Files.createDirectories(jobsRoot);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void initJob(RenderJob job) {
        Path jobDir = jobDir(job.getJobId());
        try {
            Files.createDirectories(jobDir.resolve("chunks"));
            Files.createDirectories(workDir(job.getJobId()));
            save(job);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void save(RenderJob job) {
        try {
            AtomicFiles.writeAtomically(jobDir(job.getJobId()).resolve("job.json"),
                    objectMapper.writeValueAsBytes(serialize(job)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<RenderJob> get(String jobId) {
        Path jobFile = jobDir(jobId).resolve("job.json");
        if (!Files.isRegularFile(jobFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(deserialize(Files.readAllBytes(jobFile)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<RenderJob> listAll() {
        if (!Files.isDirectory(jobsRoot)) {
            return List.of();
        }
        List<RenderJob> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(jobsRoot)) {
            List<Path> dirs = stream.filter(Files::isDirectory).toList();
            for (Path dir : dirs) {
                Path jobFile = dir.resolve("job.json");
                if (Files.isRegularFile(jobFile)) {
                    try {
                        result.add(deserialize(Files.readAllBytes(jobFile)));
                    } catch (IOException e) {
                        log.warn("cannot read job file {}, skipping", jobFile, e);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    @Override
    public Path jobDir(String jobId) {
        return jobsRoot.resolve(jobId);
    }

    @Override
    public Path chunkFile(String jobId, int chunkIndex) {
        return jobDir(jobId).resolve("chunks").resolve(String.format("%06d.json", chunkIndex));
    }

    @Override
    public Path workDir(String jobId) {
        return jobDir(jobId).resolve("work");
    }

    @Override
    public Path cancelFlag(String jobId) {
        return jobDir(jobId).resolve("cancel.flag");
    }

    @Override
    public void discardArtifacts(String jobId) {
        Path dir = jobDir(jobId);
        deleteQuietly(dir.resolve("chunks"));
        deleteQuietly(workDir(jobId));
        deleteQuietly(cancelFlag(jobId));
        try {
            Files.createDirectories(dir.resolve("chunks"));
            Files.createDirectories(workDir(jobId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void clearWorkDir(String jobId) {
        Path work = workDir(jobId);
        if (!Files.isDirectory(work)) {
            return;
        }
        try (Stream<Path> entries = Files.list(work)) {
            entries.forEach(this::deleteQuietly);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteQuietly(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("cannot delete {}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("cannot walk {}", root, e);
        }
    }

    private ObjectNode serialize(RenderJob job) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("jobId", job.getJobId());
        node.put("scope", job.getScope());
        node.put("defName", job.getDefName());
        node.put("defVersion", job.getDefVersion());
        node.put("formatVersion", job.getFormatVersion());
        node.put("format", job.getFormat().name());
        node.put("status", job.getStatus().name());
        node.put("cancelRequested", job.isCancelRequested());
        node.put("error", job.getError());
        node.put("totalRows", job.getTotalRows());
        node.put("chunkCount", job.getChunkCount());
        node.put("batchSize", job.getBatchSize());
        node.put("timeoutMs", job.getTimeoutMs());
        node.put("createdAt", job.getCreatedAt() != null ? job.getCreatedAt().toEpochMilli() : null);
        node.put("startedAt", job.getStartedAt() != null ? job.getStartedAt().toEpochMilli() : null);
        node.put("finishedAt", job.getFinishedAt() != null ? job.getFinishedAt().toEpochMilli() : null);
        return node;
    }

    private RenderJob deserialize(byte[] bytes) throws IOException {
        JsonNode node = objectMapper.readTree(bytes);
        RenderJob job = new RenderJob();
        job.setJobId(node.path("jobId").asText(null));
        job.setScope(node.path("scope").asText(null));
        job.setDefName(node.path("defName").asText(null));
        job.setDefVersion(node.path("defVersion").asInt());
        job.setFormatVersion(node.path("formatVersion").asInt());
        job.setFormat(RenderFormat.parse(node.path("format").asText()));
        job.setStatus(JobStatus.valueOf(node.path("status").asText()));
        job.setCancelRequested(node.path("cancelRequested").asBoolean(false));
        job.setError(node.path("error").isNull() ? null : node.path("error").asText(null));
        job.setTotalRows(node.path("totalRows").asLong(0));
        job.setChunkCount(node.path("chunkCount").asInt(0));
        job.setBatchSize(node.path("batchSize").asInt());
        job.setTimeoutMs(node.path("timeoutMs").asLong());
        job.setCreatedAt(instant(node, "createdAt"));
        job.setStartedAt(instant(node, "startedAt"));
        job.setFinishedAt(instant(node, "finishedAt"));
        return job;
    }

    private static Instant instant(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return Instant.ofEpochMilli(v.asLong());
    }
}
