package com.github.shikejing.reportenginejava.storage;

import com.github.shikejing.reportenginejava.model.StoredDefinition;

import java.util.List;
import java.util.Optional;

/**
 * 定义存储。
 *
 * <p>定义按版本不可变保存：{@link #save(StoredDefinition)} 只追加新版本文件，
 * 绝不覆盖历史版本；读取时可以精确拿到任意历史版本。
 */
public interface DefinitionStore {

    /**
     * 保存一个新版本定义（调用方负责分配版本号）。
     * 实现必须保证：历史版本文件不被覆盖；写入对并发读取不可见中间状态。
     */
    StoredDefinition save(StoredDefinition definition);

    /** 精确读取某作用域下某名称的某一版本；不存在返回 empty。 */
    Optional<StoredDefinition> get(String scope, String name, int version);

    /** 读取某作用域下某名称的最新版本；不存在返回 empty。 */
    Optional<StoredDefinition> getLatest(String scope, String name);

    /** 列出某作用域下的全部定义（每个名称返回其最新版本摘要）。 */
    List<StoredDefinition> listLatest(String scope);

    /** 删除某作用域下某名称的全部版本；返回是否删除了内容。 */
    boolean delete(String scope, String name);
}
