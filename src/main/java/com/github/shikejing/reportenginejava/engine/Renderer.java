package com.github.shikejing.reportenginejava.engine;

import com.github.shikejing.reportenginejava.engine.stream.Progress;
import com.github.shikejing.reportenginejava.engine.stream.RowInput;
import com.github.shikejing.reportenginejava.model.StoredDefinition;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 报表渲染器。每个定义格式版本对应一个实现。
 *
 * <p>渲染器必须是无状态、确定性的：同一份定义 + 同一份输入数据，
 * 无论执行多少次、是否并发、是否重启，输出必须逐字节一致。
 *
 * <p>渲染器必须是流式的：输入经 {@link RowInput} 逐行 / 逐批读取，
 * 带排序时使用有界内存的外部排序，渲染期常驻内存只与单批行数相关，
 * 不随输入总行数增长。
 */
public interface Renderer {

    /** 该渲染器支持的定义格式版本。 */
    int formatVersion();

    /**
     * 流式分批渲染。每完成一批行，通过 {@code sink} 回传格式化后的行对象
     * （键为列字段名，值为格式化后的字符串），由调用方落盘。
     *
     * @param definition 已提交的定义（不可变）
     * @param input      可重复打开的流式行输入（由磁盘支撑，不整体驻留内存）
     * @param totalRows  输入总行数（读取输入时已统计，渲染器无需再计数）
     * @param batchSize  每批行数
     * @param workDir    作业的临时工作目录（外部排序段文件写在这里，失败时整体丢弃）
     * @param progress   取消 / 超时检查点，渲染流水线按固定粒度调用
     * @param sink       批次回调，参数为（批次序号, 本批格式化行）
     */
    void render(StoredDefinition definition, RowInput input, long totalRows,
                int batchSize, Path workDir, Progress progress, BatchSink sink) throws IOException;

    /** 批次回调。 */
    @FunctionalInterface
    interface BatchSink {
        void accept(int chunkIndex, List<Map<String, Object>> formattedRows);
    }
}
