package com.github.shikejing.reportenginejava.engine;

/** 渲染在检查点发现取消请求时抛出。抛出后作业置 CANCELLED，中间产物整体丢弃。 */
public class RenderCancelledException extends RuntimeException {
}
