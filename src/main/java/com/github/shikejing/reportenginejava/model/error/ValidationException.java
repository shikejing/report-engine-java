package com.github.shikejing.reportenginejava.model.error;

import java.util.List;

/**
 * 定义 / 请求校验失败时抛出，携带全部字段级错误。
 *
 * <p>校验在任何写入之前完成：抛出此异常时不会产生任何定义或作业记录。
 */
public class ValidationException extends RuntimeException {

    private final transient List<FieldError> errors;

    public ValidationException(List<FieldError> errors) {
        super("validation failed: " + errors.size() + " error(s)");
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> getErrors() {
        return errors;
    }
}
