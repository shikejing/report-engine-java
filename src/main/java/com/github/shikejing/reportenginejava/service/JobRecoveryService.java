package com.github.shikejing.reportenginejava.service;

import com.github.shikejing.reportenginejava.model.JobStatus;
import com.github.shikejing.reportenginejava.model.RenderJob;
import com.github.shikejing.reportenginejava.storage.JobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 重启恢复服务。
 *
 * <p>应用启动后扫描全部作业：
 * <ul>
 *   <li>仍处于 PENDING / RUNNING 的作业（重启导致的中间状态）一律置为 FAILED，
 *       错误原因标记为服务重启，并整体丢弃中间产物 —— 重启后不存在停在中间
 *       状态且无法解释的渲染任务；</li>
 *   <li>已 SUCCEEDED 的作业及其分块结果保留，重启后仍可读取；</li>
 *   <li>已 FAILED / CANCELLED 的作业保持终态。</li>
 * </ul>
 * 渲染是确定性的，客户端收到失败后用同一份定义版本与数据重新提交即可。
 */
@Service
public class JobRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(JobRecoveryService.class);

    private final JobStore jobStore;

    public JobRecoveryService(JobStore jobStore) {
        this.jobStore = jobStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        List<RenderJob> jobs = jobStore.listAll();
        int recovered = 0;
        for (RenderJob job : jobs) {
            if (job.getStatus() == JobStatus.PENDING || job.getStatus() == JobStatus.RUNNING) {
                jobStore.discardArtifacts(job.getJobId());
                job.setStatus(JobStatus.FAILED);
                job.setError("interrupted by server restart; please resubmit (rendering is deterministic)");
                job.setFinishedAt(Instant.now());
                jobStore.save(job);
                recovered++;
                log.info("recovered interrupted render job {} -> FAILED", job.getJobId());
            }
        }
        if (recovered > 0) {
            log.info("job recovery finished: {} interrupted job(s) marked FAILED", recovered);
        }
    }
}
