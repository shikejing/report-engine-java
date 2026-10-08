package com.github.shikejing.reportenginejava.storage;

import com.github.shikejing.reportenginejava.model.RenderJob;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 渲染作业存储。
 *
 * <p>作业状态与分块结果都持久化，每次状态变更均为临时文件 + 原子 move，
 * 重启后不留中间状态。
 */
public interface JobStore {

    /** 初始化作业目录并写入初始状态。 */
    void initJob(RenderJob job);

    /** 原子更新作业状态。 */
    void save(RenderJob job);

    /** 读取作业。 */
    Optional<RenderJob> get(String jobId);

    /** 列出全部作业（重启恢复时使用）。 */
    List<RenderJob> listAll();

    /** 作业目录。 */
    Path jobDir(String jobId);

    /** 某分块的最终路径。 */
    Path chunkFile(String jobId, int chunkIndex);

    /** 临时工作目录（渲染完成前的分块先写这里）。 */
    Path workDir(String jobId);

    /** 取消标记文件：存在即表示用户请求取消，渲染线程在批次之间检查。 */
    Path cancelFlag(String jobId);

    /** 丢弃作业的全部中间产物（分块与临时文件），保留作业终态记录。 */
    void discardArtifacts(String jobId);

    /** 清空工作目录中的临时文件（如外部排序归并段），保留空目录本身。 */
    void clearWorkDir(String jobId);
}
