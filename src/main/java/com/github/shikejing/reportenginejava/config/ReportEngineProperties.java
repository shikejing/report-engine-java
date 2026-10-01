package com.github.shikejing.reportenginejava.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "reportengine")
public class ReportEngineProperties {
    private Path dataDirectory = Path.of("target", "report-engine-data");
    private Duration renderTimeout = Duration.ofMinutes(2);
    private int batchSize = 100;
    private int workerThreads = 2;

    public Path getDataDirectory() { return dataDirectory; }
    public void setDataDirectory(Path dataDirectory) { this.dataDirectory = dataDirectory; }
    public Duration getRenderTimeout() { return renderTimeout; }
    public void setRenderTimeout(Duration renderTimeout) { this.renderTimeout = renderTimeout; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
}
