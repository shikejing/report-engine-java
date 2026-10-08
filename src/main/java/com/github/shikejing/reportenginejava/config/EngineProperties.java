package com.github.shikejing.reportenginejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 报表引擎配置项。
 *
 * <p>对应配置前缀 {@code report.engine}，渲染相关配置统一嵌套在
 * {@code report.engine.render.*} 下（见 {@link Render}）。
 */
@ConfigurationProperties(prefix = "report.engine")
public class EngineProperties {

    /** 数据存储根目录（定义版本、渲染作业、分块结果都落在此目录下）。 */
    private Path dataDir = Path.of("data");

    /** 渲染相关配置（{@code report.engine.render.*}）。 */
    private Render render = new Render();

    public Path getDataDir() {
        return dataDir;
    }

    public void setDataDir(Path dataDir) {
        this.dataDir = dataDir;
    }

    public Render getRender() {
        return render;
    }

    public void setRender(Render render) {
        this.render = render;
    }

    // 以下为便捷委托方法，保持既有调用方不变。

    public int getCorePoolSize() {
        return render.getCorePoolSize();
    }

    public int getMaxPoolSize() {
        return render.getMaxPoolSize();
    }

    public int getQueueCapacity() {
        return render.getQueueCapacity();
    }

    public int getBatchSize() {
        return render.getBatchSize();
    }

    public long getDefaultTimeoutMs() {
        return render.getDefaultTimeoutMs();
    }

    public long getMaxRows() {
        return render.getMaxRows();
    }

    /**
     * 渲染配置（{@code report.engine.render.*}）。
     *
     * <p>内存语义：渲染期间常驻内存只与 {@link #batchSize}（以及
     * {@link #externalSortFanIn} 个归并缓冲）相关，不随输入总行数增长。
     */
    public static class Render {

        /** 渲染线程池核心线程数。 */
        private int corePoolSize = 4;

        /** 渲染线程池最大线程数。 */
        private int maxPoolSize = 16;

        /** 渲染任务队列容量。 */
        private int queueCapacity = 200;

        /** 默认每批渲染的行数（输出分块大小）。请求未传 batchSize 时使用。 */
        private int batchSize = 500;

        /** 单个渲染任务的默认超时时间（毫秒）。 */
        private long defaultTimeoutMs = 300_000L;

        /** 单次渲染允许的最大行数，解析输入时超出立即拒绝（不创建作业）。 */
        private long maxRows = 1_000_000L;

        /**
         * 带排序渲染时外部归并排序的单次归并路数。
         *
         * <p>每个归并缓冲只持有当前行，常驻内存约为 batchSize + fanIn 行；
         * 路数越多需要的归并趟数越少。取值必须 &ge; 2。
         */
        private int externalSortFanIn = 64;

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

        public int getExternalSortFanIn() {
            return externalSortFanIn;
        }

        public void setExternalSortFanIn(int externalSortFanIn) {
            this.externalSortFanIn = externalSortFanIn;
        }
    }
}
