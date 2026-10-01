package com.github.shikejing.reportenginejava.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 已提交保存的报表定义。
 *
 * <p>定义按版本不可变保存：每次提交产生一个新版本文件，历史版本永远保留，
 * 渲染时可以精确定位到「某作用域 / 某名称 / 某版本」。
 */
public class StoredDefinition {

    /** 所属作用域（租户）。 */
    private String scope;

    /** 报表名称。 */
    private String name;

    /** 提交版本号，从 1 递增。 */
    private int version;

    /** 定义格式版本（定义语言的版本），决定使用哪个渲染器，永不因升级而改变。 */
    private int formatVersion;

    /** 提交时间。 */
    private Instant createdAt;

    /** 提交时的原始定义内容。 */
    private JsonNode content;

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(int formatVersion) {
        this.formatVersion = formatVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public JsonNode getContent() {
        return content;
    }

    public void setContent(JsonNode content) {
        this.content = content;
    }
}
