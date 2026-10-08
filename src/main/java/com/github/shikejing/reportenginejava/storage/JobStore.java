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

    /**
     * 请求输入的暂存目录：提交请求时数据以 JSON Lines 流式落盘到这里，
     * 作业创建后再原子移动为该作业的输入文件。该目录下的任何残留都不属于
     * 某个已创建作业，可被安全清理（被拒绝的请求不产生作业记录）。
     */
    Path stagingDir();

    /** 作业的流式输入数据文件（JSON Lines，每行一个数据对象）。 */
    Path requestDataFile(String jobId);

    /** 丢弃作业的全部中间产物（分块与临时文件），保留作业终态记录。 */
    void discardArtifacts(String jobId);
}
