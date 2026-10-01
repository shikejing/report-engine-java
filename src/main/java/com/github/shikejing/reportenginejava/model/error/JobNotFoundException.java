package com.github.shikejing.reportenginejava.model.error;

/**
 * 渲染作业不存在（或当前作用域不可见）时抛出，统一返回 404。
 */
public class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(String jobId) {
        super("render job not found: " + jobId);
    }
}
