package com.github.shikejing.reportenginejava.engine;

/**
 * 渲染控制钩子。渲染器在输入扫描、归并排序与分块产出等检查点调用，
 * 使取消 / 超时在大输入下也能及时生效，而不必等全部输入处理完。
 */
public interface RenderControl {

    /**
     * 检查点：若用户已请求取消则抛 {@link RenderCancelledException}；
     * 若超过作业超时则抛 {@link RenderTimeoutException}。
     */
    void checkpoint();
}
