package com.github.shikejing.reportenginejava.model.error;

/**
 * 作业当前状态不允许执行该操作（如读取未完成作业的结果）时抛出，返回 409。
 */
public class IllegalJobStateException extends RuntimeException {

    public IllegalJobStateException(String message) {
        super(message);
    }
}
