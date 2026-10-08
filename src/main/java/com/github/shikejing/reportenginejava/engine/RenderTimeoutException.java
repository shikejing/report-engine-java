package com.github.shikejing.reportenginejava.engine;

/** 渲染在检查点发现超过作业超时时抛出。抛出后作业置 FAILED，中间产物整体丢弃。 */
public class RenderTimeoutException extends RuntimeException {

    public RenderTimeoutException(String message) {
        super(message);
    }
}
