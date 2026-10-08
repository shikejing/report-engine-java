package com.github.shikejing.reportenginejava.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.reportenginejava.config.EngineProperties;
import com.github.shikejing.reportenginejava.engine.Renderer;
import com.github.shikejing.reportenginejava.engine.RendererRegistry;
import com.github.shikejing.reportenginejava.engine.ValidatorRegistry;
import com.github.shikejing.reportenginejava.engine.stream.JsonlRowInput;
import com.github.shikejing.reportenginejava.engine.stream.Progress;
import com.github.shikejing.reportenginejava.engine.stream.RenderAbortedException;
import com.github.shikejing.reportenginejava.engine.stream.RowInput;
import com.github.shikejing.reportenginejava.model.Column;
import com.github.shikejing.reportenginejava.model.JobStatus;
import com.github.shikejing.reportenginejava.model.RenderFormat;
import com.github.shikejing.reportenginejava.model.RenderJob;
import com.github.shikejing.reportenginejava.model.StoredDefinition;
import com.github.shikejing.reportenginejava.model.error.FieldError;
import com.github.shikejing.reportenginejava.model.error.IllegalJobStateException;
import com.github.shikejing.reportenginejava.model.error.JobNotFoundException;
import com.github.shikejing.reportenginejava.model.error.ValidationException;
import com.github.shikejing.reportenginejava.storage.AtomicFiles;
import com.github.shikejing.reportenginejava.storage.JobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 *   <li><b>流式处理 / 有界内存</b>：提交请求时数据以 JSON Lines 流式落盘，
 *       服务端从不在内存中持有整个输入；渲染按批读取、按批落盘，带排序时
 *       使用有界内存的外部归并排序。无论输入多少行，渲染期常驻内存只与
 *       单批行数相关，不随输入总行数 / 体积增长；</li>
 *   <li><b>超限尽早拒绝</b>：边读取输入边计数，行数一旦超过
 *       {@code maxRows} 立即拒绝（400），不创建作业、不留任何记录或暂存文件；</li>
 *   <li>分块结果先写工作目录临时文件，再原子发布到 chunks 目录；
 *       取消 / 超时 / 失败时整体丢弃，绝不吐出半份结果；</li>
 *   <li>取消 / 超时检查点贯穿读取、校验、排序、输出全部阶段且粒度固定，
 *       大输入下也能及时生效；</li>
 *   <li>渲染器无状态、输出确定，并发渲染互不干扰。</li>
 * </ul>
 */
@Service
public class RenderService {

    private static final Logger log = LoggerFactory.getLogger(RenderService.class);

    private final JobStore jobStore;
    private final RendererRegistry rendererRegistry;
    private final ValidatorRegistry validatorRegistry;
    private final ObjectMapper objectMapper;
    private final DefinitionService definitionService;
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
        EngineProperties.Render renderProps = properties.getRender();
        this.executor = new ThreadPoolExecutor(
                renderProps.getCorePoolSize(), renderProps.getMaxPoolSize(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(renderProps.getQueueCapacity()),
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

    /** 流式解析出的请求标量字段与行数统计。 */
    private static final class ParsedRequest {
        String defName;
        Integer defVersion;
        RenderFormat format = RenderFormat.JSON;
        Integer batchSize;
        Long timeoutMs;
        long rowCount;
    }

    /**
     * 提交渲染请求。
     *
     * <p>直接消费原始请求字节流：边解析边把数据行写入暂存文件（JSON Lines），
     * 全程不把 {@code data} 读入内存；同时逐行计数，超过 {@code maxRows}
     * 立即抛出 {@link ValidationException}（HTTP 400），此时不创建作业目录、
     * 不产生作业记录，暂存文件立即删除。
     */
    public RenderJob submit(String scope, InputStream requestBody) {
        EngineProperties.Render renderProps = properties.getRender();
        List<FieldError> errors = new ArrayList<>();
        ParsedRequest parsed = new ParsedRequest();
        // 注意：spool 必须是本次调用私有的局部变量，不能共享到字段 / 外部变量，
        // 否则并发提交时一个请求的 finally 清理会误删另一个请求正在使用的暂存文件。
        Path spool = null;
        boolean claimed = false;
        try {
            spool = Files.createTempFile(jobStore.stagingDir(), "req-", ".jsonl");

            long maxRows = renderProps.getMaxRows();
            try (JsonParser parser = objectMapper.getFactory().createParser(requestBody);
                 BufferedWriter writer = Files.newBufferedWriter(spool, StandardCharsets.UTF_8)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    throw new ValidationException(List.of(
                            new FieldError("$", "request body must be a JSON object")));
                }
                boolean dataSeen = false;
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String field = parser.currentName();
                    JsonToken valueToken = parser.nextToken();
                    boolean handled = false;
                    if ("defName".equals(field)) {
                        if (valueToken == JsonToken.VALUE_STRING) {
                            parsed.defName = parser.getValueAsString();
                        } else {
                            errors.add(new FieldError("defName", "defName must be a string"));
                        }
                    } else if ("defVersion".equals(field)) {
                        if (valueToken == JsonToken.VALUE_NUMBER_INT) {
                            parsed.defVersion = parser.getIntValue();
                        } else {
                            errors.add(new FieldError("defVersion", "defVersion must be an integer"));
                        }
                    } else if ("format".equals(field)) {
                        if (valueToken == JsonToken.VALUE_STRING) {
                            try {
                                parsed.format = RenderFormat.parse(parser.getValueAsString());
                            } catch (IllegalArgumentException e) {
                                errors.add(new FieldError("format", "format must be JSON or CSV"));
                            }
                        } else {
                            errors.add(new FieldError("format", "format must be a string"));
                        }
                    } else if ("batchSize".equals(field)) {
                        if (valueToken == JsonToken.VALUE_NUMBER_INT) {
                            parsed.batchSize = parser.getIntValue();
                        } else {
                            errors.add(new FieldError("batchSize", "batchSize must be an integer"));
                        }
                    } else if ("timeoutMs".equals(field)) {
                        if (valueToken == JsonToken.VALUE_NUMBER_INT) {
                            parsed.timeoutMs = parser.getLongValue();
                        } else {
                            errors.add(new FieldError("timeoutMs", "timeoutMs must be an integer"));
                        }
                    } else if ("data".equals(field)) {
                        handled = true; // data 的数组在下面自行消费
                        dataSeen = true;
                        if (valueToken == JsonToken.VALUE_NULL) {
                            errors.add(new FieldError("data", "data is required"));
                        } else if (valueToken != JsonToken.START_ARRAY) {
                            errors.add(new FieldError("data", "data must be an array of row objects"));
                            parser.skipChildren();
                        } else {
                            long rowCount = 0;
                            while (parser.nextToken() != JsonToken.END_ARRAY) {
                                // 每次只把一个数据元素读成树（单行有界），立即落盘后释放。
                                JsonNode element = parser.readValueAsTree();
                                writer.write(objectMapper.writeValueAsString(element));
                                writer.write('\n');
                                rowCount++;
                                if (rowCount > maxRows) {
                                    // 超限即拒：不再读取后续输入，不产生任何作业记录或中间结果。
                                    throw new ValidationException(List.of(new FieldError("data",
                                            "too many rows: input exceeds maxRows " + maxRows
                                                    + " while reading row " + rowCount)));
                                }
                            }
                            writer.flush();
                            parsed.rowCount = rowCount;
                        }
                    }
                    // 未知字段（或类型错误的容器型已知字段）：跳过整个值，
                    // 否则对象 / 数组内部的 token 会被误当成顶层字段继续解析。
                    if (!handled && (valueToken == JsonToken.START_OBJECT
                            || valueToken == JsonToken.START_ARRAY)) {
                        parser.skipChildren();
                    }
                }
                if (!dataSeen) {
                    errors.add(new FieldError("data", "data is required"));
                }
            }

            // 标量语义校验（与历史行为一致）
            if (parsed.defName == null || parsed.defName.isBlank()) {
                errors.add(new FieldError("defName", "defName is required"));
            } else if (!parsed.defName.matches("^[a-zA-Z0-9_-]{1,100}$")) {
                errors.add(new FieldError("defName", "defName is invalid"));
            }
            if (parsed.defVersion != null && parsed.defVersion <= 0) {
                errors.add(new FieldError("defVersion", "defVersion must be a positive integer"));
            }
            int batchSize = renderProps.getBatchSize();
            if (parsed.batchSize != null) {
                if (parsed.batchSize <= 0) {
                    errors.add(new FieldError("batchSize", "batchSize must be positive"));
                } else {
                    batchSize = parsed.batchSize;
                }
            }
            long timeoutMs = renderProps.getDefaultTimeoutMs();
            if (parsed.timeoutMs != null) {
                if (parsed.timeoutMs <= 0) {
                    errors.add(new FieldError("timeoutMs", "timeoutMs must be positive"));
                } else {
                    timeoutMs = parsed.timeoutMs;
                }
            }
            if (!errors.isEmpty()) {
                throw new ValidationException(errors);
            }

            // 加载定义：精确版本或最新版本（读取与提交并发时，拿到的必然是某个已提交的完整版本）
            StoredDefinition stored = parsed.defVersion != null
                    ? definitionService.getVersion(scope, parsed.defName, parsed.defVersion)
                    : definitionService.getLatest(scope, parsed.defName);

            RenderJob job = new RenderJob();
            job.setJobId(UUID.randomUUID().toString());
            job.setScope(scope);
            job.setDefName(stored.getName());
            job.setDefVersion(stored.getVersion());
            job.setFormatVersion(stored.getFormatVersion());
            job.setFormat(parsed.format);
            job.setStatus(JobStatus.PENDING);
            job.setBatchSize(batchSize);
            job.setTimeoutMs(timeoutMs);
            job.setTotalRows(parsed.rowCount);
            job.setCreatedAt(Instant.now());
            jobStore.initJob(job);
            // 暂存文件认领为该作业的输入数据（同文件系统下原子 move）。
            Path target = jobStore.requestDataFile(job.getJobId());
            try {
                Files.move(spool, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailed) {
                Files.move(spool, target, StandardCopyOption.REPLACE_EXISTING);
            }
            claimed = true;

            try {
                executor.execute(() -> run(job.getJobId()));
            } catch (RejectedExecutionException e) {
                job.setStatus(JobStatus.FAILED);
                job.setError("render pool is busy, please retry later");
                job.setFinishedAt(Instant.now());
                jobStore.save(job);
                throw new IllegalStateException("render pool is busy", e);
            }
            log.info("render job submitted: {} -> {}/{} v{} ({} rows, {})",
                    job.getJobId(), scope, stored.getName(), stored.getVersion(),
                    parsed.rowCount, parsed.format);
            return job;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            // 未被作业认领的暂存文件（校验 / 超限 / 定义不存在等）立即删除：拒绝不留痕。
            if (!claimed && spool != null) {
                try {
                    Files.deleteIfExists(spool);
                } catch (IOException e) {
                    log.warn("cannot delete staging file {}", spool, e);
                }
            }
        }
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

    /** 请求取消。幂等：已终态作业直接返回；运行中的作业创建取消标记文件，在固定行数检查点上生效。 */
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
                    new FieldError("chunk", "chunk out of range: 0.." + (job.getChunkCount() - 1))));
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
        RenderControl control = new RenderControl(jobId, job.getTimeoutMs(), start);
        try {
            // 开始前先检查一次：提交后到调度前可能已被取消 / 超时。
            control.checkpoint(0);

            job.setStatus(JobStatus.RUNNING);
            job.setStartedAt(Instant.now());
            jobStore.save(job);

            StoredDefinition stored = definitionService.getVersion(
                    job.getScope(), job.getDefName(), job.getDefVersion());
            var definition = validatorRegistry.get(stored.getFormatVersion())
                    .validate(stored.getContent());

            RowInput input = new JsonlRowInput(
                    jobStore.requestDataFile(jobId), objectMapper);

            Renderer renderer = rendererRegistry.get(stored.getFormatVersion());
            renderer.render(stored, input, job.getTotalRows(), job.getBatchSize(),
                    jobStore.workDir(jobId), control, (chunkIndex, formattedRows) -> {
                        // 分块边界同样检查取消 / 超时。
                        control.checkpoint(job.getTotalRows());
                        writeChunk(job, definition, chunkIndex, formattedRows);
                        job.setChunkCount(chunkIndex + 1);
                        jobStore.save(job);
                    });

            job.setStatus(JobStatus.SUCCEEDED);
            job.setFinishedAt(Instant.now());
            jobStore.save(job);
            log.info("render job succeeded: {} ({} rows, {} chunks)",
                    jobId, job.getTotalRows(), job.getChunkCount());
        } catch (RenderAbortedException e) {
            jobStore.discardArtifacts(jobId);
            if (e.getReason() == RenderAbortedException.Reason.CANCELLED) {
                job.setStatus(JobStatus.CANCELLED);
                job.setCancelRequested(true);
                job.setError("cancelled by user");
                log.info("render job cancelled: {}", jobId);
            } else {
                job.setStatus(JobStatus.FAILED);
                job.setError(e.getMessage());
                log.info("render job timed out: {}", jobId);
            }
            job.setFinishedAt(Instant.now());
            jobStore.save(job);
        } catch (Exception e) {
            log.warn("render job failed: {}", jobId, e);
            jobStore.discardArtifacts(jobId);
            job.setStatus(JobStatus.FAILED);
            job.setError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            job.setFinishedAt(Instant.now());
            jobStore.save(job);
        }
    }

    private void writeChunk(RenderJob job, com.github.shikejing.reportenginejava.model.Definition definition,
                            int chunkIndex, List<Map<String, Object>> formattedRows) {
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

    private String toCsv(com.github.shikejing.reportenginejava.model.Definition definition,
                         int chunkIndex, List<Map<String, Object>> rows) {
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

    /**
     * 渲染控制钩子：在流水线的固定行数检查点（与每批行数无关）上检查
     * 取消标记文件与超时截止时间。取消 / 超时一旦发生，后续每个检查点
     * 都持续抛出，保证大输入下也能尽快跳出深层读取 / 排序 / 归并。
     */
    private final class RenderControl implements Progress {
        private final String jobId;
        private final long timeoutMs;
        private final long deadline;
        private boolean cancelled;

        RenderControl(String jobId, long timeoutMs, long start) {
            this.jobId = jobId;
            this.timeoutMs = timeoutMs;
            this.deadline = start + timeoutMs;
        }

        @Override
        public void checkpoint(long processedRows) {
            if (cancelled || Files.exists(jobStore.cancelFlag(jobId))) {
                cancelled = true;
                throw new RenderAbortedException(RenderAbortedException.Reason.CANCELLED,
                        "cancelled by user");
            }
            if (System.currentTimeMillis() > deadline) {
                throw new RenderAbortedException(RenderAbortedException.Reason.TIMEOUT,
                        "render timeout after " + timeoutMs + "ms");
            }
        }
    }
}
