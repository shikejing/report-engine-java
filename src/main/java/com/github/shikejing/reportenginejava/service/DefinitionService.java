package com.github.shikejing.reportenginejava.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shikejing.reportenginejava.engine.DefinitionValidator;
import com.github.shikejing.reportenginejava.engine.ValidatorRegistry;
import com.github.shikejing.reportenginejava.model.StoredDefinition;
import com.github.shikejing.reportenginejava.model.error.DefinitionNotFoundException;
import com.github.shikejing.reportenginejava.storage.FileBasedDefinitionStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定义服务：定义的提交、读取、列表、删除。
 *
 * <p>并发控制：
 * <ul>
 *   <li>同一作用域下同一名称的提交串行化（按 key 加锁），版本号单调递增，
 *       不会出现版本覆盖或跳号；</li>
 *   <li>历史版本文件只追加、不可变，读取与提交并发时互不干扰，
 *       读者要么看到旧版本、要么看到新版本，绝不会看到半写状态。</li>
 * </ul>
 */
@Service
public class DefinitionService {

    /** 当前支持的定义格式版本（新提交一律采用最新格式版本）。 */
    public static final int CURRENT_FORMAT_VERSION = 2;

    private final FileBasedDefinitionStore store;
    private final ValidatorRegistry validatorRegistry;

    /** 按 作用域/名称 加锁，串行化版本分配。 */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public DefinitionService(FileBasedDefinitionStore store, ValidatorRegistry validatorRegistry) {
        this.store = store;
        this.validatorRegistry = validatorRegistry;
    }

    /**
     * 提交（创建 / 更新）定义。
     *
     * <p>写入前执行完整校验：字段缺失、类型不符、引用不存在对象、字段组合不合法
     * 一律拒绝，不产生任何记录。校验通过后才分配版本号并原子落盘。
     */
    public StoredDefinition create(String scope, JsonNode content) {
        DefinitionValidator validator = validatorRegistry.get(CURRENT_FORMAT_VERSION);
        // 校验在加锁前完成，且不产生任何写入
        validator.validate(content);

        String name = content.path("name").asText();
        Object lock = locks.computeIfAbsent(scope + "/" + name, k -> new Object());
        synchronized (lock) {
            int nextVersion = store.currentVersion(scope, name) + 1;
            StoredDefinition stored = new StoredDefinition();
            stored.setScope(scope);
            stored.setName(name);
            stored.setVersion(nextVersion);
            stored.setFormatVersion(CURRENT_FORMAT_VERSION);
            stored.setCreatedAt(Instant.now());
            stored.setContent(content);
            return store.save(stored);
        }
    }

    /** 读取最新版本；不存在或不属于该作用域统一抛 404。 */
    public StoredDefinition getLatest(String scope, String name) {
        return store.getLatest(scope, name)
                .orElseThrow(() -> new DefinitionNotFoundException(scope, name));
    }

    /** 读取指定历史版本；不存在或不属于该作用域统一抛 404。 */
    public StoredDefinition getVersion(String scope, String name, int version) {
        return store.get(scope, name, version)
                .orElseThrow(() -> new DefinitionNotFoundException(scope, name, version));
    }

    /** 列出本作用域下的全部定义（不跨作用域，不泄漏其他作用域）。 */
    public List<StoredDefinition> list(String scope) {
        return store.listLatest(scope);
    }

    /** 删除本作用域下某定义的全部版本。 */
    public void delete(String scope, String name) {
        if (!store.delete(scope, name)) {
            throw new DefinitionNotFoundException(scope, name);
        }
    }
}
