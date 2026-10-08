package com.github.shikejing.reportenginejava.engine;

import com.github.shikejing.reportenginejava.engine.stream.SortIO;

import java.nio.file.Path;

/**
 * 报表渲染器。每个定义格式版本对应一个实现。
 *
 * <p>渲染器必须是无状态、确定性的：同一份定义 + 同一份输入数据，
 * 无论执行多少次、是否并发、是否重启，输出必须逐字节一致。
 *
 * <p><b>内存有界</b>：输入是磁盘上的行文件（每行一个 JSON 对象，
 * {@code \t} 前为原始输入序号），渲染器顺序扫描、分批产出，
 * 带排序时使用外部归并排序；常驻内存只与 batchSize / fanIn 相关，
 * 不随输入总行数增长。
 */
public interface Renderer {

    /** 该渲染器支持的定义格式版本。 */
    int formatVersion();

    /**
     * 流式分批渲染。
     *
     * @param definition 已提交的定义（不可变）
     * @param inputFile  输入行文件（每行：{@code seq\t{json}}）
     * @param sortIO     外部排序归并段存储（仅在定义带排序时使用）
     * @param batchSize  每批行数
     * @param fanIn      外部排序单趟最大归并路数（&ge; 2）
     * @param control    取消 / 超时检查点
     * @param sink       批次回调，参数为（批次序号, 本批格式化行）
     */
    void render(com.github.shikejing.reportenginejava.model.StoredDefinition definition,
                Path inputFile, SortIO sortIO, int batchSize, int fanIn,
                RenderControl control, BatchSink sink);

    /** 批次回调。 */
    @FunctionalInterface
    interface BatchSink {
        void accept(int chunkIndex, java.util.List<java.util.Map<String, Object>> formattedRows);
    }
}
