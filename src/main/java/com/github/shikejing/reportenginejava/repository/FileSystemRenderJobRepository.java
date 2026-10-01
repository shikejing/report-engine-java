package com.github.shikejing.reportenginejava.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.reportenginejava.config.ReportEngineProperties;
import com.github.shikejing.reportenginejava.model.JobStatus;
import com.github.shikejing.reportenginejava.model.RenderJob;
import com.github.shikejing.reportenginejava.model.StoredDefinition;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Repository;

@Repository
public class FileSystemRenderJobRepository {

    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern JOB_ID = Pattern.compile("[0-9a-fA-F-]{36}");
    private static final String META_FILE = "job.json";
    private static final String DEFINITION_FILE = "definition.json";
    private static final String OUTPUT_FILE = "rows.ndjson";
    private static final String INDEX_FILE = "rows.index";
    private static final int INDEX_LINE_SIZE = 40;

    private final Path root;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public FileSystemRenderJobRepository(ReportEngineProperties properties, ObjectMapper objectMapper) {
        this.root = properties.getDataDirectory().resolve("jobs").toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        createDirectories(root);
    }

    public RenderJob create(RenderJob job, StoredDefinition definition) {
        Path directory = directory(job.scope(), job.jobId());
        Lock lock = lock(job.scope(), job.jobId());
        lock.lock();
        try {
            if (Files.exists(directory)) {
                throw new IllegalArgumentException("job already exists");
            }
            createDirectories(directory);
            Path temporaryDefinition = directory.resolve(".tmp-definition-" + System.nanoTime());
            writeJson(temporaryDefinition, definition);
            atomicMove(temporaryDefinition, directory.resolve(DEFINITION_FILE));
            writeMeta(directory, job);
            return job;
        } finally {
            lock.unlock();
        }
    }

    public RenderJob updateStatus(RenderJob job) {
        Path directory = directory(job.scope(), job.jobId());
        Lock lock = lock(job.scope(), job.jobId());
        lock.lock();
        try {
            if (!Files.isDirectory(directory)) {
                throw new IllegalArgumentException("job does not exist");
            }
            writeMeta(directory, job);
            return job;
        } finally {
            lock.unlock();
        }
    }

    public Optional<RenderJob> find(String scope, String jobId) {
        Path file = safeDirectory(scope, jobId).resolve(META_FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(readJson(file, RenderJob.class));
    }

    public Optional<StoredDefinition> findDefinition(String scope, String jobId) {
        Path file = safeDirectory(scope, jobId).resolve(DEFINITION_FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(readJson(file, StoredDefinition.class));
    }

    public RenderJob markRunningIfActive(String scope, String jobId, RenderJob running) {
        Path directory = safeDirectory(scope, jobId);
        Lock lock = lock(scope, jobId);
        lock.lock();
        try {
            RenderJob current = readJson(directory.resolve(META_FILE), RenderJob.class);
            if (current.status() != JobStatus.PENDING && current.status() != JobStatus.RUNNING) {
                return null;
            }
            writeMeta(directory, running);
            return running;
        } finally {
            lock.unlock();
        }
    }

    public boolean cancelIfActive(String scope, String jobId, RenderJob cancelled) {
        Path directory = safeDirectory(scope, jobId);
        Lock lock = lock(scope, jobId);
        lock.lock();
        try {
            RenderJob current = readJson(directory.resolve(META_FILE), RenderJob.class);
            if (current.status() != JobStatus.PENDING && current.status() != JobStatus.RUNNING) {
                return false;
            }
            deleteOutputArtifacts(directory);
            writeMeta(directory, cancelled);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean publishIfActive(String scope, String jobId, RenderOutputSession session, RenderJob succeeded) {
        Path directory = safeDirectory(scope, jobId);
        Lock lock = lock(scope, jobId);
        lock.lock();
        try {
            RenderJob current = readJson(directory.resolve(META_FILE), RenderJob.class);
            if (current.status() != JobStatus.PENDING && current.status() != JobStatus.RUNNING) {
                closeQuietly(session);
                deleteOutputArtifacts(directory);
                return false;
            }
            session.close();
            moveTemporaryOutputs(directory);
            writeMeta(directory, succeeded);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    public RenderOutputSession openOutputSession(String scope, String jobId, int batchSize) {
        Path directory = safeDirectory(scope, jobId);
        long token = System.nanoTime();
        Path rowsTemporary = directory.resolve(".tmp-rows-" + token + ".ndjson");
        Path indexTemporary = directory.resolve(".tmp-index-" + token + ".idx");
        try {
            OutputSessionStreams streams = new OutputSessionStreams(
                    Files.newOutputStream(rowsTemporary),
                    Files.newOutputStream(indexTemporary));
            RenderOutputSession session = new RenderOutputSession(
                    rowsTemporary, indexTemporary, streams, Math.max(1, batchSize));
            session.writeBatchIndex();
            return session;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void discardTemporaryOutputs(String scope, String jobId) {
        Path directory = safeDirectory(scope, jobId);
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(".tmp-rows-") || name.startsWith(".tmp-index-")
                                || OUTPUT_FILE.equals(name) || INDEX_FILE.equals(name);
                    })
                    .forEach(this::deleteIfExists);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void moveTemporaryOutputs(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> files = stream
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(".tmp-rows-") || name.startsWith(".tmp-index-");
                    })
                    .toList();
            if (files.size() != 2) {
                throw new IllegalStateException("expected temporary rows and index files");
            }
            for (Path file : files) {
                String targetName = file.getFileName().toString().contains(".tmp-rows-")
                        ? OUTPUT_FILE : INDEX_FILE;
                atomicMove(file, directory.resolve(targetName));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteOutputArtifacts(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(".tmp-rows-") || name.startsWith(".tmp-index-")
                                || OUTPUT_FILE.equals(name) || INDEX_FILE.equals(name);
                    })
                    .forEach(this::deleteIfExists);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // Caller deletes the files.
            }
        }
    }

    public List<Map<String, Object>> readBatch(String scope, String jobId, long offset, int limit,
                                               long totalRows, int batchSize) {
        if (offset < 0 || limit < 1 || batchSize < 1) {
            throw new IllegalArgumentException("invalid batch range");
        }
        if (offset >= totalRows) {
            return List.of();
        }
        Path directory = safeDirectory(scope, jobId);
        Path file = directory.resolve(OUTPUT_FILE);
        Path indexFile = directory.resolve(INDEX_FILE);
        if (!Files.isRegularFile(file) || !Files.isRegularFile(indexFile)) {
            return List.of();
        }
        BatchLocation location = readBatchLocation(indexFile, offset, batchSize);
        List<Map<String, Object>> rows = new ArrayList<>(limit);
        try (java.io.InputStream input = Files.newInputStream(file, StandardOpenOption.READ)) {
            long skipped = 0;
            while (skipped < location.byteOffset()) {
                skipped += input.skip(location.byteOffset() - skipped);
            }
            try (BufferedReader reader = new BufferedReader(
                    new java.io.InputStreamReader(input, StandardCharsets.UTF_8), 8192)) {
                long index = location.firstRow();
                String line;
                while (index < offset + limit && (line = reader.readLine()) != null) {
                    if (index >= offset) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> row = objectMapper.readValue(line, LinkedHashMap.class);
                        rows.add(row);
                    }
                    index++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(rows);
    }

    private BatchLocation readBatchLocation(Path indexFile, long offset, int batchSize) {
        long batchIndex = offset / batchSize;
        try (SeekableByteChannel channel = Files.newByteChannel(indexFile, StandardOpenOption.READ)) {
            channel.position(batchIndex * INDEX_LINE_SIZE);
            ByteBuffer buffer = ByteBuffer.allocate(INDEX_LINE_SIZE);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) == -1) {
                    throw new IllegalStateException("missing output batch index");
                }
            }
            String line = new String(buffer.array(), StandardCharsets.UTF_8);
            String[] parts = line.trim().split(" ");
            return new BatchLocation(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<RecoveredJob> findRecoverableJobs() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<RecoveredJob> result = new ArrayList<>();
        try (Stream<Path> scopes = Files.list(root)) {
            for (Path scopePath : scopes.toList()) {
                if (!Files.isDirectory(scopePath)) {
                    continue;
                }
                try (Stream<Path> jobs = Files.list(scopePath)) {
                    for (Path jobPath : jobs.toList()) {
                        Path meta = jobPath.resolve(META_FILE);
                        if (Files.isRegularFile(meta)) {
                            RenderJob job = readJson(meta, RenderJob.class);
                            result.add(new RecoveredJob(job,
                                    Files.isRegularFile(jobPath.resolve(DEFINITION_FILE))));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(result);
    }

    public String newJobId() {
        return UUID.randomUUID().toString();
    }

    private void writeMeta(Path directory, RenderJob job) {
        Path temporary = directory.resolve(".tmp-job-" + System.nanoTime() + ".json");
        writeJson(temporary, job);
        atomicMove(temporary, directory.resolve(META_FILE));
    }

    private void writeJson(Path path, Object value) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> T readJson(Path path, Class<T> type) {
        try {
            return objectMapper.readValue(path.toFile(), type);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read file: " + path, e);
        }
    }

    private void atomicMove(Path source, Path target) {
        try {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicNotSupported) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path safeDirectory(String scope, String jobId) {
        validateScope(scope);
        validateJobId(jobId);
        return directory(scope, jobId);
    }

    private Path directory(String scope, String jobId) {
        Path path = root.resolve(scope).resolve(jobId).toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("invalid repository path");
        }
        return path;
    }

    private Lock lock(String scope, String jobId) {
        return locks.computeIfAbsent(scope + '\u0000' + jobId, ignored -> new ReentrantLock());
    }

    private void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void validateScope(String value) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid scope");
        }
    }

    private void validateJobId(String value) {
        if (value == null || !JOB_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid job id");
        }
    }

    public record RecoveredJob(RenderJob job, boolean definitionPresent) {
    }

    private record BatchLocation(long firstRow, long byteOffset) {
    }

    private record OutputSessionStreams(OutputStream rows, OutputStream index) implements Closeable {
        @Override
        public void close() throws IOException {
            try {
                rows.flush();
            } finally {
                rows.close();
                index.close();
            }
        }
    }

    public final class RenderOutputSession implements Closeable {
        private final Path rowsPath;
        private final Path indexPath;
        private final OutputSessionStreams streams;
        private final int batchSize;
        private long rowsWritten;
        private long bytesWritten;

        private RenderOutputSession(Path rowsPath, Path indexPath, OutputSessionStreams streams,
                                    int batchSize) {
            this.rowsPath = rowsPath;
            this.indexPath = indexPath;
            this.streams = streams;
            this.batchSize = batchSize;
        }

        public synchronized void writeRow(Map<String, Object> row) {
            try {
                if (rowsWritten % batchSize == 0) {
                    writeBatchIndex();
                }
                byte[] json = objectMapper.writeValueAsBytes(row);
                streams.rows().write(json);
                streams.rows().write('\n');
                bytesWritten += json.length + 1L;
                rowsWritten++;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void writeBatchIndex() throws IOException {
            String line = String.format(Locale.ROOT, "%019d %019d\n", rowsWritten, bytesWritten);
            streams.index().write(line.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public synchronized void close() throws IOException {
            streams.close();
        }

        public Path rowsPath() {
            return rowsPath;
        }

        public Path indexPath() {
            return indexPath;
        }
    }

}
