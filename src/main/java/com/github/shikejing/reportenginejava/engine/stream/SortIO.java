package com.github.shikejing.reportenginejava.engine.stream;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 外部排序的磁盘归并段（run）存储。
 *
 * <p>每个归并段是一个只追加的行文件；渲染失败 / 取消时由作业层整体丢弃
 * 工作目录，本接口不负责删除之外的生命周期管理。
 */
public interface SortIO {

    /** 打开作业输入行文件用于顺序读取（输入文件不是归并段）。 */
    RowReader openInput(Path inputFile) throws IOException;

    /** 创建并打开一个新归并段（编号由调用方分配）。 */
    RowWriter createRun(int runId) throws IOException;

    /** 重新打开一个已落盘的归并段用于归并读取。 */
    RowReader openRun(int runId) throws IOException;

    /** 删除归并段文件（归并产出的中间段在被下一趟消费后删除）。 */
    void deleteRun(int runId) throws IOException;
}
