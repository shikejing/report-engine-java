package com.github.shikejing.reportenginejava.engine.stream;

/**
 * 渲染进度钩子。渲染流水线在校验、外部排序（建段 / 归并）、格式化输出等
 * 所有阶段都按固定粒度调用，使取消与超时在大输入下也能及时生效，
 * 而不需要等整批、整趟甚至整个输入处理完。
 *
 * <p>检查点粒度与单批行数无关（固定的行数间隔 + 每个分块边界），
 * 因此即便每批行数很大，取消 / 超时仍按行数及时生效。
 */
@FunctionalInterface
public interface Progress {

    /** 已处理 {@code processedRows} 行时的检查点；实现可在此中止渲染。 */
    void checkpoint(long processedRows);

    /** 不做任何检查的进度钩子（单元测试等场景使用）。 */
    Progress NOOP = processedRows -> {
    };
}
