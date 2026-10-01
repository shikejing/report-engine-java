package com.github.shikejing.reportenginejava.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.reportenginejava.config.ReportEngineProperties;
import com.github.shikejing.reportenginejava.model.StoredDefinition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.springframework.stereotype.Repository;

@Repository
public class FileSystemDefinitionRepository {

    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final String DELETED_MARKER = ".deleted";

    private final Path root;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public FileSystemDefinitionRepository(ReportEngineProperties properties, ObjectMapper objectMapper) {
        this.root = properties.getDataDirectory().resolve("definitions").toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        createDirectories(root);
    }

    public StoredDefinition saveNext(String scope, String reportKey,
                                     BiFunction<Integer, Optional<StoredDefinition>, StoredDefinition> factory) {
        validateId(scope);
        validateId(reportKey);
        Lock lock = lockFor(scope, reportKey);
        lock.lock();
        try {
            Path directory = directory(scope, reportKey);
            if (Files.exists(directory.resolve(DELETED_MARKER))) {
                deleteRecursively(directory);
            }
            createDirectories(directory);
            cleanupTemporaryFiles(directory);
            Optional<Integer> latestNumber = latestVersion(directory);
            int nextVersion = latestNumber.orElse(0) + 1;
            Optional<StoredDefinition> latest = latestNumber
                    .map(version -> readJson(directory.resolve(versionFileName(version))));
            StoredDefinition definition = factory.apply(nextVersion, latest);
            if (definition.version() != nextVersion) {
                throw new IllegalArgumentException("version mismatch");
            }
            Path target = directory.resolve(versionFileName(nextVersion));
            Path temporary = directory.resolve(".tmp-" + System.nanoTime() + "-" + nextVersion + ".json");
            writeJson(temporary, definition);
            atomicMove(temporary, target);
            return definition;
        } finally {
            lock.unlock();
        }
    }

    public Optional<StoredDefinition> find(String scope, String reportKey, int version) {
        Path path = versionPath(scope, reportKey, version);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(readJson(path));
    }

    public Optional<StoredDefinition> findLatest(String scope, String reportKey) {
        Path directory = safeDirectory(scope, reportKey);
        if (!Files.isDirectory(directory) || Files.exists(directory.resolve(DELETED_MARKER))) {
            return Optional.empty();
        }
        return latestVersion(directory).map(version -> readJson(directory.resolve(versionFileName(version))));
    }

    public List<StoredDefinition> findAll(String scope, String reportKey) {
        Path directory = safeDirectory(scope, reportKey);
        if (!Files.isDirectory(directory) || Files.exists(directory.resolve(DELETED_MARKER))) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> parseVersion(path.getFileName().toString()))
                    .flatMap(Optional::stream)
                    .sorted()
                    .map(version -> readJson(directory.resolve(versionFileName(version))))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean delete(String scope, String reportKey) {
        validateId(scope);
        validateId(reportKey);
        Lock lock = lockFor(scope, reportKey);
        lock.lock();
        try {
            Path directory = directory(scope, reportKey);
            if (!Files.isDirectory(directory) || Files.exists(directory.resolve(DELETED_MARKER))) {
                return false;
            }
            Path marker = directory.resolve(DELETED_MARKER);
            try {
                Files.writeString(marker, "deleted\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            deleteRecursively(directory);
            return true;
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockFor(String scope, String reportKey) {
        return locks.computeIfAbsent(scope + '\u0000' + reportKey, ignored -> new ReentrantLock());
    }

    private Path safeDirectory(String scope, String reportKey) {
        validateId(scope);
        validateId(reportKey);
        return directory(scope, reportKey);
    }

    private Path directory(String scope, String reportKey) {
        Path directory = root.resolve(scope).resolve(reportKey).toAbsolutePath().normalize();
        if (!directory.startsWith(root)) {
            throw new IllegalArgumentException("invalid repository path");
        }
        return directory;
    }

    private Path versionPath(String scope, String reportKey, int version) {
        Path path = safeDirectory(scope, reportKey).resolve(versionFileName(version)).toAbsolutePath().normalize();
        if (!path.startsWith(root) || version < 1) {
            throw new IllegalArgumentException("invalid repository path");
        }
        return path;
    }

    private Optional<Integer> latestVersion(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .map(path -> path.getFileName().toString())
                    .map(this::parseVersion)
                    .flatMap(Optional::stream)
                    .max(Comparator.naturalOrder());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<Integer> parseVersion(String fileName) {
        if (!fileName.startsWith("v-") || !fileName.endsWith(".json")) {
            return Optional.empty();
        }
        try {
            int version = Integer.parseInt(fileName.substring(2, fileName.length() - 5));
            return version >= 1 ? Optional.of(version) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String versionFileName(int version) {
        return "v-" + version + ".json";
    }

    private void writeJson(Path path, StoredDefinition value) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private StoredDefinition readJson(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), StoredDefinition.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read stored definition: " + path, e);
        }
    }

    private void atomicMove(Path source, Path target) {
        try {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void cleanupTemporaryFiles(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(path -> path.getFileName().toString().startsWith(".tmp-"))
                    .forEach(this::deleteIfExists);
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

    private void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(this::deleteIfExists);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void validateId(String value) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid repository identifier");
        }
    }
}
