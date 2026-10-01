package com.github.shikejing.reportenginejava.engine;

import com.github.shikejing.reportenginejava.model.StoredDefinition;

import java.util.List;
import java.util.Map;

/**
 * 报表渲染器。每个定义格式版本对应一个实现。
 *
 * <p>渲染器必须是无状态、确定性的：同一份定义 + 同一份输入数据，
 * 无论执行多少次、是否并发、是否重启，输出必须逐字节一致。
 */
public interface Renderer {

    /** 该渲染器支持的定义格式版本。 */
    int formatVersion();

    /**
     * 分批渲染。每完成一批行，通过 {@code sink} 回传格式化后的行对象
     * （键为列字段名，值为格式化后的字符串），由调用方落盘。
     *
     * @param definition 已提交的定义（不可变）
     * @param rows       输入数据行
     * @param batchSize  每批行数
     * @param sink       批次回调，参数为（批次序号, 本批格式化行）
     */
    void render(StoredDefinition definition, List<Map<String, Object>> rows,
                int batchSize, BatchSink sink);

    /** 批次回调。 */
    @FunctionalInterface
    interface BatchSink {
        void accept(int chunkIndex, List<Map<String, Object>> formattedRows);
    }
}
