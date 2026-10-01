package com.github.shikejing.reportenginejava.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.shikejing.reportenginejava.config.EngineProperties;
import com.github.shikejing.reportenginejava.engine.RenderException;
import com.github.shikejing.reportenginejava.engine.Renderer;
import com.github.shikejing.reportenginejava.engine.RendererRegistry;
import com.github.shikejing.reportenginejava.engine.ValidatorRegistry;
import com.github.shikejing.reportenginejava.model.Column;
import com.github.shikejing.reportenginejava.model.Definition;
import com.github.shikejing.reportenginejava.model.JobStatus;
import com.github.shikejing.reportenginejava.model.RenderFormat;
import com.github.shikejing.reportenginejava.model.RenderJob;
import com.github.shikejing.reportenginejava.model.StoredDefinition;
import com.github.shikejing.reportenginejava.model.error.IllegalJobStateException;
import com.github.shikejing.reportenginejava.model.error.JobNotFoundException;
import com.github.shikejing.reportenginejava.model.error.ValidationException;
import com.github.shikejing.reportenginejava.storage.AtomicFiles;
import com.github.shikejing.reportenginejava.storage.JobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 渲染服务。
 *
 * <p>语义保证：
 * <ul>
 *   <li>每个作业精确绑定一个已提交的定义版本（{@code defVersion} 与
 *       {@code formatVersion} 持久化在作业记录中），读取方拿到的结果
 *       必然对应某个已提交版本，不存在跨版本拼接；</li>
 *   <li>分块结果先写工作目录临时文件，再原子发布到 chunks 目录；
 *       取消 / 超时 / 失败时整体丢弃，绝不吐出半份结果；</li>
 *   <li>取消标记在批次之间检查，取消立即生效且不影响后续渲染；</li>
 *   <li>渲染器无状态、输出确定，并发渲染互不干扰。</li>
 * </ul>
 */
@Service
public class RenderService {

    private static final Logger log = LoggerFactory.getLogger(RenderService.class);

    private final DefinitionService definitionService;
    private final JobStore jobStore;
    private final RendererRegistry rendererRegistry;
    private final ValidatorRegistry validatorRegistry;
    private final ObjectMapper objectMapper;
    private final EngineProperties properties;
    private final ExecutorService executor;

    public RenderService(DefinitionService definitionService, JobStore jobStore,
                         RendererRegistry rendererRegistry, ValidatorRegistry validatorRegistry,
                         ObjectMapper objectMapper, EngineProperties properties) {
        this.definitionService = definitionService;
        this.jobStore = jobStore;
        this.rendererRegistry = rendererRegistry;
        this.validatorRegistry = validatorRegistry;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.executor = new ThreadPoolExecutor(
                properties.getCorePoolSize(), properties.getMaxPoolSize(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(properties.getQueueCapacity()),
                new ThreadFactory() {
                    private final AtomicInteger n = new AtomicInteger();
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "render-pool-" + n.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                });
    }

    /**
     * 提交渲染请求。请求校验通过后创建作业并异步执行。
     */
    public RenderJob submit(String scope, RenderRequest request) {
        List<com.github.shikejing.reportenginejava.model.error.FieldError> errors = new ArrayList<>();

        // defName：必填，合法标识符
        String defName = request.getDefName();
        if (defName == null || defName.isBlank()) {
            errors.add(new com.github.shikejing.reportenginejava.model.error.FieldError(
                    "defName", "defName is required"));
        } else if (!defName.matches("^[a-zA-Z0-9_-]{1,100}$")) {
            errors.add(new com.github.shikejing.reportenginejava.model.error.FieldError(
                    "defName", "defName is invalid"));
        }

        // defVersion：可选，正整数
        Integer defVersion = request.getDefVersion();
        if (defVersion != null && defVersion <= 0) {
            errors.add(new com.github.shikejing.reportenginejava.model.error.FieldError(
                    "defVersion", "defVersion must be a positive integer"));
        }

        // data：必填，数组
        JsonNode data = request.getData();
        if (data == null || data.isNull()) {
            errors.add(new com.github.shikejing.reportenginejava.model.error.FieldError(
                    "data", "data is required"));
        } else if (!data.isArray()) {
            errors.add(new com.github.shikejing.reportenginejava.model.error.FieldError(
                    "data", "data must be an array of row objects"));
        }

        // format：可选，枚举
        RenderFormat format = RenderFormat.JSON;
        if (request.getFormat() != null) {
            try {
                format = RenderFormat.parse(request.getFormat());
            } catch (IllegalArgumentException e) {
                errors.add(new com.github.shikejing.reportenginejava.model.error.FieldError(
                        "format", "format must be JSON or CSV"));
            }
        }

        // batchSize：可选，1..配置上限
        int batchSize = properties.getBatchSize();
        if (request.getBatchSize() != null) {
            if (request.getBatchSize() <= 0) {
                errors.add(new com.github.shikejing.reportenginejava.model.error.FieldError(
                        "batchSize", "batchSize must be positive"));
            } else {
                batchSize = request.getBatchSize();
            }
        }

        // timeoutMs：可选，正数
        long timeoutMs = properties.getDefaultTimeoutMs();
        if (request.getTimeoutMs() != null) {
            if (request.getTimeoutMs() <= 0) {
                errors.add(new com.github.shikejing.reportenginejava.model.error.FieldError(
                        "timeoutMs", "timeoutMs must be positive"));
            } else {
                timeoutMs = request.getTimeoutMs();
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        // 加载定义：精确版本或最新版本（读取与提交并发时，拿到的必然是某个已提交的完整版本）
        StoredDefinition stored = defVersion != null
                ? definitionService.getVersion(scope, defName, defVersion)
                : definitionService.getLatest(scope, defName);

        RenderJob job = new RenderJob();
        job.setJobId(UUID.randomUUID().toString());
        job.setScope(scope);
        job.setDefName(stored.getName());
        job.setDefVersion(stored.getVersion());
        job.setFormatVersion(stored.getFormatVersion());
        job.setFormat(format);
        job.setStatus(JobStatus.PENDING);
        job.setBatchSize(batchSize);
        job.setTimeoutMs(timeoutMs);
        job.setCreatedAt(Instant.now());
        jobStore.initJob(job);
        try {
            AtomicFiles.writeAtomically(jobStore.jobDir(job.getJobId()).resolve("request-data.json"),
                    objectMapper.writeValueAsBytes(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        try {
            executor.execute(() -> run(job.getJobId()));
        } catch (RejectedExecutionException e) {
            job.setStatus(JobStatus.FAILED);
            job.setError("render pool is busy, please retry later");
            job.setFinishedAt(Instant.now());
            jobStore.save(job);
            throw new IllegalStateException("render pool is busy", e);
        }
        log.info("render job submitted: {} -> {}/{} v{} ({})",
                job.getJobId(), scope, stored.getName(), stored.getVersion(), format);
        return job;
    }

    /** 读取作业；不属于当前作用域一律按不存在处理（404，不泄漏存在性）。 */
    public RenderJob getJob(String scope, String jobId) {
        RenderJob job = jobStore.get(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
        if (!scope.equals(job.getScope())) {
            throw new JobNotFoundException(jobId);
        }
        return job;
    }

    /** 请求取消。幂等：已终态作业直接返回；运行中的作业创建取消标记文件，批次间生效。 */
    public RenderJob cancel(String scope, String jobId) {
        RenderJob job = getJob(scope, jobId);
        if (job.getStatus().isTerminal()) {
            return job;
        }
        try {
            AtomicFiles.writeAtomically(jobStore.cancelFlag(jobId), new byte[0]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        job.setCancelRequested(true);
        jobStore.save(job);
        return job;
    }

    /**
     * 读取某一分块结果。只有 SUCCEEDED 作业可读；返回原始字节与格式，
     * 由控制器按格式序列化（JSON 数组 / CSV 文本）。
     */
    public byte[] fetchChunk(String scope, String jobId, int chunk) {
        RenderJob job = getJob(scope, jobId);
        if (job.getStatus() != JobStatus.SUCCEEDED) {
            throw new IllegalJobStateException("job " + jobId + " is not succeeded (status="
                    + job.getStatus() + ")");
        }
        if (chunk < 0 || chunk >= job.getChunkCount()) {
            throw new ValidationException(List.of(
                    new com.github.shikejing.reportenginejava.model.error.FieldError(
                            "chunk", "chunk out of range: 0.." + (job.getChunkCount() - 1))));
        }
        Path chunkFile = jobStore.chunkFile(jobId, chunk);
        try {
            return Files.readAllBytes(chunkFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void run(String jobId) {
        RenderJob job = jobStore.get(jobId).orElse(null);
        if (job == null) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            job.setStatus(JobStatus.RUNNING);
            job.setStartedAt(Instant.now());
            jobStore.save(job);

            StoredDefinition stored = definitionService.getVersion(
                    job.getScope(), job.getDefName(), job.getDefVersion());
            Definition definition = validatorRegistry.get(stored.getFormatVersion())
                    .validate(stored.getContent());

            List<Map<String, Object>> rows = toRows(job);
            if (rows.size() > properties.getMaxRows()) {
                throw new RenderException("data", "too many rows: " + rows.size()
                        + " > maxRows " + properties.getMaxRows());
            }
            job.setTotalRows(rows.size());
            jobStore.save(job);

            Renderer renderer = rendererRegistry.get(stored.getFormatVersion());
            renderer.render(stored, rows, job.getBatchSize(), (chunkIndex, formattedRows) -> {
                // 批次之间检查取消 / 超时。取消标记是独立文件，不会被作业状态保存覆盖。
                if (Files.exists(jobStore.cancelFlag(jobId))) {
                    throw new CancelledException();
                }
                if (System.currentTimeMillis() - start > job.getTimeoutMs()) {
                    throw new TimeoutException("render timeout after " + job.getTimeoutMs() + "ms");
                }
                writeChunk(job, definition, chunkIndex, formattedRows);
                job.setChunkCount(chunkIndex + 1);
                jobStore.save(job);
            });

            job.setStatus(JobStatus.SUCCEEDED);
            job.setFinishedAt(Instant.now());
            jobStore.save(job);
            log.info("render job succeeded: {} ({} rows, {} chunks)",
                    jobId, job.getTotalRows(), job.getChunkCount());
        } catch (CancelledException e) {
            jobStore.discardArtifacts(jobId);
            job.setStatus(JobStatus.CANCELLED);
            job.setCancelRequested(true);
            job.setError("cancelled by user");
            job.setFinishedAt(Instant.now());
            jobStore.save(job);
            log.info("render job cancelled: {}", jobId);
        } catch (Exception e) {
            log.warn("render job failed: {}", jobId, e);
            jobStore.discardArtifacts(jobId);
            job.setStatus(JobStatus.FAILED);
            job.setError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            job.setFinishedAt(Instant.now());
            jobStore.save(job);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toRows(RenderJob job) throws IOException {
        Path dataFile = jobStore.jobDir(job.getJobId()).resolve("request-data.json");
        JsonNode data = objectMapper.readTree(Files.readAllBytes(dataFile));
        List<Map<String, Object>> rows = new ArrayList<>();
        int i = 0;
        for (JsonNode element : data) {
            if (!element.isObject()) {
                throw new RenderException("rows[" + i + "]", "row must be an object");
            }
            rows.add(objectMapper.convertValue(element, Map.class));
            i++;
        }
        return rows;
    }

    private void writeChunk(RenderJob job, Definition definition, int chunkIndex,
                            List<Map<String, Object>> formattedRows) {
        try {
            byte[] bytes;
            if (job.getFormat() == RenderFormat.JSON) {
                bytes = objectMapper.writeValueAsBytes(formattedRows);
            } else {
                bytes = toCsv(definition, chunkIndex, formattedRows).getBytes(StandardCharsets.UTF_8);
            }
            // 先写工作目录临时文件，再原子发布到 chunks
            Path tmp = jobStore.workDir(job.getJobId())
                    .resolve(String.format("%06d.json.tmp", chunkIndex));
            Files.write(tmp, bytes);
            AtomicFiles.writeAtomically(jobStore.chunkFile(job.getJobId(), chunkIndex), bytes);
            Files.deleteIfExists(tmp);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String toCsv(Definition definition, int chunkIndex, List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        if (chunkIndex == 0) {
            boolean first = true;
            for (Column col : definition.getColumns()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(csvEscape(col.getHeader() != null ? col.getHeader() : col.getField()));
                first = false;
            }
            sb.append('\n');
        }
        for (Map<String, Object> row : rows) {
            boolean first = true;
            for (Column col : definition.getColumns()) {
                if (!first) {
                    sb.append(',');
                }
                Object v = row.get(col.getField());
                sb.append(csvEscape(v == null ? "" : v.toString()));
                first = false;
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String csvEscape(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /** 取消标记异常，用于在批次回调中中断渲染。 */
    private static class CancelledException extends RuntimeException {
    }

    /** 超时异常。 */
    private static class TimeoutException extends RuntimeException {
        TimeoutException(String message) {
            super(message);
        }
    }

    /** 渲染请求 DTO。 */
    public static class RenderRequest {
        private String defName;
        private Integer defVersion;
        private JsonNode data;
        private String format;
        private Integer batchSize;
        private Long timeoutMs;

        public String getDefName() {
            return defName;
        }

        public void setDefName(String defName) {
            this.defName = defName;
        }

        public Integer getDefVersion() {
            return defVersion;
        }

        public void setDefVersion(Integer defVersion) {
            this.defVersion = defVersion;
        }

        public JsonNode getData() {
            return data;
        }

        public void setData(JsonNode data) {
            this.data = data;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public Integer getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(Integer batchSize) {
            this.batchSize = batchSize;
        }

        public Long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}
