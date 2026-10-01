package com.github.shikejing.reportenginejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 报表引擎配置项。
 *
 * <p>对应配置前缀 {@code report.engine}，详见 README「配置项」章节。
 */
@ConfigurationProperties(prefix = "report.engine")
public class EngineProperties {

    /** 数据存储根目录（定义版本、渲染作业、分块结果都落在此目录下）。 */
    private Path dataDir = Path.of("data");

    /** 渲染线程池核心线程数。 */
    private int corePoolSize = 4;

    /** 渲染线程池最大线程数。 */
    private int maxPoolSize = 16;

    /** 渲染任务队列容量。 */
    private int queueCapacity = 200;

    /** 每批渲染的行数（输出分块大小）。 */
    private int batchSize = 500;

    /** 单个渲染任务的默认超时时间（毫秒）。 */
    private long defaultTimeoutMs = 300_000L;

    /** 单次渲染允许的最大行数，超出直接拒绝。 */
    private long maxRows = 1_000_000L;

    public Path getDataDir() {
        return dataDir;
    }

    public void setDataDir(Path dataDir) {
        this.dataDir = dataDir;
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        this.corePoolSize = corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(long defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public long getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(long maxRows) {
        this.maxRows = maxRows;
    }
}
