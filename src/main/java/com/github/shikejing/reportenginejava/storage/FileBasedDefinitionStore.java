package com.github.shikejing.reportenginejava.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.shikejing.reportenginejava.config.EngineProperties;
import com.github.shikejing.reportenginejava.model.StoredDefinition;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 基于文件的定义存储。
 *
 * <p>目录结构：
 * <pre>
 * {dataDir}/definitions/{scope}/{name}/
 *   latest                 # 内容为最新版本号
 *   versions/1.json        # 不可变的历史版本
 *   versions/2.json
 * </pre>
 *
 * <p>每个版本文件只通过原子 move 发布一次，因此历史版本一旦写入就不可变；
 * 并发读取要么看到旧版本、要么看到新版本，绝不会看到半写文件。
 */
@Component
public class FileBasedDefinitionStore implements DefinitionStore {

    private static final Logger log = LoggerFactory.getLogger(FileBasedDefinitionStore.class);

    private final ObjectMapper objectMapper;
    private final Path definitionsRoot;

    /** 不可变版本文件的内存缓存：key = scope/name/version。 */
    private final Map<String, StoredDefinition> cache = new ConcurrentHashMap<>();

    public FileBasedDefinitionStore(ObjectMapper objectMapper, EngineProperties properties) {
        this.objectMapper = objectMapper;
        this.definitionsRoot = properties.getDataDir().resolve("definitions");
        try {
            Files.createDirectories(definitionsRoot);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public StoredDefinition save(StoredDefinition definition) {
        Path nameDir = nameDir(definition.getScope(), definition.getName());
        Path versionsDir = nameDir.resolve("versions");
        Path versionFile = versionsDir.resolve(definition.getVersion() + ".json");
        Path latestFile = nameDir.resolve("latest");

        try {
            Files.createDirectories(versionsDir);
            // 版本文件只追加：已存在则说明版本号分配冲突，直接失败，绝不覆盖历史。
            if (Files.exists(versionFile)) {
                throw new IllegalStateException("definition version already exists: "
                        + definition.getScope() + "/" + definition.getName() + " v" + definition.getVersion());
            }
            byte[] versionBytes = objectMapper.writeValueAsBytes(serialize(definition));
            AtomicFiles.writeAtomically(versionFile, versionBytes);

            // 发布最新版本指针（原子切换）。
            byte[] latestBytes = Integer.toString(definition.getVersion()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            AtomicFiles.writeAtomically(latestFile, latestBytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        cache.put(cacheKey(definition), definition);
        log.info("definition saved: {}/{} v{} (formatVersion {})",
                definition.getScope(), definition.getName(), definition.getVersion(), definition.getFormatVersion());
        return definition;
    }

    @Override
    public Optional<StoredDefinition> get(String scope, String name, int version) {
        String key = cacheKey(scope, name, version);
        StoredDefinition cached = cache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Path versionFile = nameDir(scope, name).resolve("versions").resolve(version + ".json");
        if (!Files.isRegularFile(versionFile)) {
            return Optional.empty();
        }
        try {
            StoredDefinition loaded = deserialize(Files.readAllBytes(versionFile));
            cache.put(key, loaded);
            return Optional.of(loaded);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<StoredDefinition> getLatest(String scope, String name) {
        Path latestFile = nameDir(scope, name).resolve("latest");
        if (!Files.isRegularFile(latestFile)) {
            return Optional.empty();
        }
        try {
            String raw = Files.readString(latestFile).trim();
            int version = Integer.parseInt(raw);
            return get(scope, name, version);
        } catch (IOException | NumberFormatException e) {
            throw new UncheckedIOException("invalid latest pointer for " + scope + "/" + name,
                    e instanceof IOException ? (IOException) e : new IOException(e));
        }
    }

    @Override
    public List<StoredDefinition> listLatest(String scope) {
        Path scopeDir = scopeDir(scope);
        if (!Files.isDirectory(scopeDir)) {
            return List.of();
        }
        List<StoredDefinition> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(scopeDir)) {
            List<Path> nameDirs = stream.filter(Files::isDirectory).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            for (Path nameDir : nameDirs) {
                getLatest(scope, nameDir.getFileName().toString()).ifPresent(result::add);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    @Override
    public boolean delete(String scope, String name) {
        Path nameDir = nameDir(scope, name);
        if (!Files.exists(nameDir)) {
            return false;
        }
        // 原子移动到临时删除目录，避免删除过程中读者看到半删除状态。
        Path trash = definitionsRoot.resolve(".trash").resolve(scope + "-" + name + "-" + System.nanoTime());
        try {
            Files.createDirectories(trash.getParent());
            Files.move(nameDir, trash, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            deleteRecursively(trash);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // 清理缓存
        cache.keySet().removeIf(k -> k.startsWith(scope + "/" + name + "/"));
        log.info("definition deleted: {}/{}", scope, name);
        return true;
    }

    /** 读取某名称当前最新版本号；不存在返回 0。 */
    public int currentVersion(String scope, String name) {
        Path latestFile = nameDir(scope, name).resolve("latest");
        if (!Files.isRegularFile(latestFile)) {
            return 0;
        }
        try {
            return Integer.parseInt(Files.readString(latestFile).trim());
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    private ObjectNode serialize(StoredDefinition d) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("scope", d.getScope());
        node.put("name", d.getName());
        node.put("version", d.getVersion());
        node.put("formatVersion", d.getFormatVersion());
        node.put("createdAt", d.getCreatedAt().toEpochMilli());
        node.set("content", d.getContent());
        return node;
    }

    private StoredDefinition deserialize(byte[] bytes) throws IOException {
        JsonNode node = objectMapper.readTree(bytes);
        StoredDefinition d = new StoredDefinition();
        d.setScope(node.path("scope").asText(null));
        d.setName(node.path("name").asText(null));
        d.setVersion(node.path("version").asInt());
        d.setFormatVersion(node.path("formatVersion").asInt());
        d.setCreatedAt(Instant.ofEpochMilli(node.path("createdAt").asLong()));
        d.setContent(node.path("content"));
        return d;
    }

    private Path scopeDir(String scope) {
        return definitionsRoot.resolve(scope);
    }

    private Path nameDir(String scope, String name) {
        return scopeDir(scope).resolve(name);
    }

    private static String cacheKey(StoredDefinition d) {
        return cacheKey(d.getScope(), d.getName(), d.getVersion());
    }

    private static String cacheKey(String scope, String name, int version) {
        return scope + "/" + name + "/" + version;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
