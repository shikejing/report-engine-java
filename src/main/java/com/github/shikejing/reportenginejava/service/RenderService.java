package com.github.shikejing.reportenginejava.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.reportenginejava.config.EngineProperties;
import com.github.shikejing.reportenginejava.engine.RenderCancelledException;
import com.github.shikejing.reportenginejava.engine.RenderControl;
import com.github.shikejing.reportenginejava.engine.RenderTimeoutException;
import com.github.shikejing.reportenginejava.engine.Renderer;
import com.github.shikejing.reportenginejava.engine.RendererRegistry;
import com.github.shikejing.reportenginejava.engine.ValidatorRegistry;
import com.github.shikejing.reportenginejava.engine.stream.IndexedRow;
import com.github.shikejing.reportenginejava.engine.stream.RowWriter;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
import java.util.stream.Stream;

/**
 * 渲染服务。
 *
 * <p>语义保证：
 * <ul>
 *   <li>每个作业精确绑定一个已提交的定义版本（{@code defVersion} 与
 *       {@code formatVersion} 持久化在作业记录中）；</li>
 *   <li><b>内存有界</b>：请求体流式解析，输入行立即落盘为 {@code input.jsonl}，
 *       渲染器顺序扫描 / 外部归并排序 / 分批落盘，常驻内存只与 batchSize
 *       （和归并路数）相关，不随输入总行数或输入体积增长；</li>
 *   <li><b>超限尽早拒绝</b>：解析输入时一旦行数超过 maxRows 立即返回 400，
 *       此前不创建作业记录，拒绝请求不留下任何中间结果；</li>
 *   <li>取消 / 超时在输入扫描、排序归并、分块产出等检查点即时生效，
 *       已产出分块整体丢弃；</li>
 *   <li>分块先写工作目录临时文件，再原子发布到 chunks 目录。</li>
 * </ul>
 */
@Service
public class RenderService {

    private static final Logger log = LoggerFactory.getLogger(RenderService.class);

    /** 作业输入行文件名（每行：{@code seq\t{json}}）。 */
    public static final String INPUT_FILE = "input.jsonl";

    private final DefinitionService definitionService;
    private final JobStore jobStore;
    private final RendererRegistry rendererRegistry;
    private final ValidatorRegistry validatorRegistry;
    private final ObjectMapper objectMapper;
    private final EngineProperties properties;
    private final JsonFactory jsonFactory;
    private final ExecutorService executor;

    public RenderService(DefinitionService definitionService, JobStore jobStore,
                         RendererRegistry rendererRegistry, ValidatorRegistry validatorRegistry,
                         ObjectMapper objectMapper, EngineProperties properties) {
        this.definitionService = definitionService;
        this.jobStore = jobStore;
        this.rendererRegistry = rendererRegistry;
        this.validatorRegistry = validatorRegistry;
        this.objectMapper = objectMapper;
        this.jsonFactory = objectMapper.getFactory();
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
     * 流式提交渲染请求。
     *
     * <p>请求体边解析边落盘：字段级校验失败、JSON 不合法、行数超过 maxRows
     * 都在创建作业记录之前以 {@link ValidationException}（400）拒绝，且不留
     * 任何文件。校验通过、定义解析成功后才初始化作业目录并调度异步渲染。
     */
    public RenderJob submit(String scope, InputStream body) {
        ParsedRequest parsed;
        try {
            parsed = parseRequest(body);
        } catch (ValidationException e) {
            throw e;
        }

        // 加载定义：精确版本或最新版本（读取与提交并发时，拿到的必然是某个已提交的完整版本）
        StoredDefinition stored;
        try {
            stored = parsed.defVersion != null
                    ? definitionService.getVersion(scope, parsed.defName, parsed.defVersion)
                    : definitionService.getLatest(scope, parsed.defName);
        } catch (RuntimeException e) {
            deleteIfExists(parsed.spool);
            throw e;
        }

        String jobId = UUID.randomUUID().toString();
        RenderJob job = new RenderJob();
        job.setJobId(jobId);
        job.setScope(scope);
        job.setDefName(stored.getName());
        job.setDefVersion(stored.getVersion());
        job.setFormatVersion(stored.getFormatVersion());
        job.setFormat(parsed.format);
        job.setStatus(JobStatus.PENDING);
        job.setBatchSize(parsed.batchSize);
        job.setTimeoutMs(parsed.timeoutMs);
        job.setTotalRows(parsed.totalRows);
        job.setCreatedAt(Instant.now());
        jobStore.initJob(job);

        Path inputFile = jobStore.jobDir(jobId).resolve(INPUT_FILE);
        try {
            Files.move(parsed.spool, inputFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveFailed) {
            try {
                Files.move(parsed.spool, inputFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                deleteRecursively(jobStore.jobDir(jobId));
                throw new UncheckedIOException(e);
            }
        }

        try {
            executor.execute(() -> run(jobId));
        } catch (RejectedExecutionException e) {
            deleteRecursively(jobStore.jobDir(jobId));
            throw e; // 由全局异常处理映射为 503
        }
        log.info("render job submitted: {} -> {}/{} v{} ({}, {} rows)",
                jobId, scope, stored.getName(), stored.getVersion(), parsed.format, parsed.totalRows);
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

    /** 请求取消。幂等：已终态作业直接返回；运行中的作业创建取消标记文件，检查点处生效。 */
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
        long start = job.getCreatedAt() != null ? job.getCreatedAt().toEpochMilli()
                : System.currentTimeMillis();
        try {
            // 作业可能在线程池队列中排队等待；开始执行前先检查取消 / 超时，
            // 使排队期间收到的取消也能立即生效，排队超时也不会再跑一遍渲染。
            if (Files.exists(jobStore.cancelFlag(jobId))) {
                throw new RenderCancelledException();
            }
            if (System.currentTimeMillis() - start > job.getTimeoutMs()) {
                throw new RenderTimeoutException(
                        "render timeout after " + job.getTimeoutMs() + "ms");
            }
            job.setStatus(JobStatus.RUNNING);
            job.setStartedAt(Instant.now());
            jobStore.save(job);

            StoredDefinition stored = definitionService.getVersion(
                    job.getScope(), job.getDefName(), job.getDefVersion());
            // 解析定义内容（渲染器内部也会解析；这里解析一次供 CSV 序列化表头使用，
            // 定义非法会在产出任何分块前令作业失败）
            com.github.shikejing.reportenginejava.model.Definition definition =
                    validatorRegistry.get(stored.getFormatVersion()).validate(stored.getContent());

            Path inputFile = jobStore.jobDir(jobId).resolve(INPUT_FILE);
            JobRowFiles.FileSortIO sortIO =
                    new JobRowFiles.FileSortIO(objectMapper, jobStore.workDir(jobId));
            RenderControl control = new JobControl(jobId, start, job.getTimeoutMs());

            Renderer renderer = rendererRegistry.get(stored.getFormatVersion());
            renderer.render(stored, inputFile, sortIO, job.getBatchSize(),
                    Math.max(2, properties.getRender().getExternalSortFanIn()),
                    control, (chunkIndex, formattedRows) -> {
                        writeChunk(job, definition, chunkIndex, formattedRows);
                        job.setChunkCount(chunkIndex + 1);
                        jobStore.save(job);
                    });

            job.setStatus(JobStatus.SUCCEEDED);
            job.setFinishedAt(Instant.now());
            jobStore.clearWorkDir(jobId);
            jobStore.save(job);
            log.info("render job succeeded: {} ({} rows, {} chunks)",
                    jobId, job.getTotalRows(), job.getChunkCount());
        } catch (RenderCancelledException e) {
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

    /** 取消 / 超时检查点：取消标记是独立文件，不会被作业状态保存覆盖。 */
    private final class JobControl implements RenderControl {
        private final String jobId;
        private final long start;
        private final long timeoutMs;

        JobControl(String jobId, long start, long timeoutMs) {
            this.jobId = jobId;
            this.start = start;
            this.timeoutMs = timeoutMs;
        }

        @Override
        public void checkpoint() {
            if (Files.exists(jobStore.cancelFlag(jobId))) {
                throw new RenderCancelledException();
            }
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw new RenderTimeoutException("render timeout after " + timeoutMs + "ms");
            }
        }
    }

    // ------------------------------------------------------------------
    // 请求体流式解析
    // ------------------------------------------------------------------

    /** 解析后的提交参数与落盘输入。 */
    private static final class ParsedRequest {
        String defName;
        Integer defVersion;
        RenderFormat format = RenderFormat.JSON;
        int batchSize;
        long timeoutMs;
        long totalRows;
        Path spool;
    }

    private ParsedRequest parseRequest(InputStream body) {
        List<FieldError> errors = new ArrayList<>();
        Path spool = null;
        ParsedRequest parsed = new ParsedRequest();
        parsed.batchSize = properties.getBatchSize();
        parsed.timeoutMs = properties.getDefaultTimeoutMs();
        long maxRows = properties.getMaxRows();

        try (JsonParser p = jsonFactory.createParser(body)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new ValidationException(List.of(new FieldError("$", "request body must be a JSON object")));
            }
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                JsonToken token = p.nextToken();
                switch (field) {
                    case "defName" -> {
                        if (token != JsonToken.VALUE_STRING) {
                            errors.add(new FieldError("defName", "defName must be a string"));
                        } else {
                            parsed.defName = p.getValueAsString();
                            if (parsed.defName == null || parsed.defName.isBlank()) {
                                errors.add(new FieldError("defName", "defName is required"));
                            } else if (!parsed.defName.matches("^[a-zA-Z0-9_-]{1,100}$")) {
                                errors.add(new FieldError("defName", "defName is invalid"));
                            }
                        }
                    }
                    case "defVersion" -> {
                        if (token == JsonToken.VALUE_NUMBER_INT && p.getIntValue() > 0) {
                            parsed.defVersion = p.getIntValue();
                        } else {
                            errors.add(new FieldError("defVersion", "defVersion must be a positive integer"));
                        }
                    }
                    case "format" -> {
                        if (token != JsonToken.VALUE_STRING) {
                            errors.add(new FieldError("format", "format must be JSON or CSV"));
                        } else {
                            try {
                                parsed.format = RenderFormat.parse(p.getValueAsString());
                            } catch (IllegalArgumentException e) {
                                errors.add(new FieldError("format", "format must be JSON or CSV"));
                            }
                        }
                    }
                    case "batchSize" -> {
                        if (token == JsonToken.VALUE_NUMBER_INT && p.getIntValue() > 0) {
                            parsed.batchSize = p.getIntValue();
                        } else {
                            errors.add(new FieldError("batchSize", "batchSize must be positive"));
                        }
                    }
                    case "timeoutMs" -> {
                        if (token == JsonToken.VALUE_NUMBER_INT && p.getLongValue() > 0) {
                            parsed.timeoutMs = p.getLongValue();
                        } else {
                            errors.add(new FieldError("timeoutMs", "timeoutMs must be positive"));
                        }
                    }
                    case "data" -> {
                        if (spool != null) {
                            // 重复的 data 字段：丢弃前一份后按最后一次出现解析（与 JSON 绑定默认一致）。
                            deleteIfExists(spool);
                            spool = null;
                        }
                        spool = parseDataArray(p, parsed, maxRows, errors);
                    }
                    default -> p.skipChildren();
                }
            }
        } catch (ValidationException e) {
            deleteIfExists(spool);
            throw e;
        } catch (IOException e) {
            deleteIfExists(spool);
            // 请求体不是合法 JSON（截断 / 语法错误等）：归类为 400，而非 500。
            throw new org.springframework.http.converter.HttpMessageNotReadableException(
                    "malformed request body: " + e.getMessage(), e);
        }

        if (spool == null) {
            errors.add(new FieldError("data", "data is required and must be an array of row objects"));
        }
        if (!errors.isEmpty()) {
            deleteIfExists(spool);
            throw new ValidationException(errors);
        }
        parsed.spool = spool;
        return parsed;
    }

    /**
     * 流式解析 data 数组：逐行写入临时 spool 文件，内存中只持有当前行。
     * 行数一旦超过 maxRows 立即抛 {@link ValidationException}（作业尚未创建）。
     */
    private Path parseDataArray(JsonParser p, ParsedRequest parsed, long maxRows,
                                List<FieldError> errors) throws IOException {
        if (p.currentToken() != JsonToken.START_ARRAY) {
            errors.add(new FieldError("data", "data must be an array of row objects"));
            p.skipChildren();
            return null;
        }
        Path spool = createSpool();
        long seq = 0;
        try (RowWriter writer = JobRowFiles.writer(objectMapper, spool)) {
            while (p.nextToken() != JsonToken.END_ARRAY) {
                JsonToken token = p.currentToken();
                long count = seq + 1;
                if (count > maxRows) {
                    throw new ValidationException(List.of(new FieldError("data",
                            "too many rows: more than maxRows " + maxRows)));
                }
                if (token == JsonToken.START_OBJECT) {
                    JsonNode node = objectMapper.readTree(p);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = objectMapper.convertValue(node, Map.class);
                    writer.write(new IndexedRow(seq, row));
                } else {
                    // 非对象元素：原样占位（空负载），渲染器扫描时报 rows[i] row must be an object。
                    // skipChildren 对数组 / 对象整体跳过，对标量是空操作。
                    p.skipChildren();
                    writer.write(new IndexedRow(seq, null));
                }
                seq = count;
            }
        } catch (RuntimeException | IOException e) {
            deleteIfExists(spool);
            throw e;
        }
        parsed.totalRows = seq;
        return spool;
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

    private static void deleteIfExists(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("cannot delete temp file {}", path, e);
        }
    }

    /**
     * 在数据目录下创建临时输入文件：与作业目录处于同一文件系统，
     * 之后可原子 move 到 {@code jobs/{jobId}/input.jsonl}，且不依赖系统临时目录容量。
     */
    private Path createSpool() {
        try {
            Path tmpDir = properties.getDataDir().resolve("tmp");
            Files.createDirectories(tmpDir);
            return Files.createTempFile(tmpDir, "render-input-", ".jsonl");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteRecursively(Path root) {        if (!Files.exists(root)) {
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
}
