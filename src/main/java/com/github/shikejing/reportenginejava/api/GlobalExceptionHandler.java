package com.github.shikejing.reportenginejava.api;

import com.github.shikejing.reportenginejava.model.error.DefinitionNotFoundException;
import com.github.shikejing.reportenginejava.model.error.IllegalJobStateException;
import com.github.shikejing.reportenginejava.model.error.JobNotFoundException;
import com.github.shikejing.reportenginejava.model.error.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * 全局异常处理。校验错误返回 400 并携带全部字段级错误；
 * 不存在 / 越权统一 404；状态冲突 409；服务忙 503。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(ValidationException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "validation failed");
        body.put("errors", e.getErrors());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(DefinitionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleDefinitionNotFound(DefinitionNotFoundException e) {
        return notFound(e.getMessage());
    }

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleJobNotFound(JobNotFoundException e) {
        return notFound(e.getMessage());
    }

    @ExceptionHandler(IllegalJobStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalJobStateException e) {
        Map<String, Object> body = Map.of("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(RejectedExecutionException.class)
    public ResponseEntity<Map<String, Object>> handleRejected(RejectedExecutionException e) {
        Map<String, Object> body = Map.of("error", "render pool is busy, please retry later");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        Map<String, Object> body = Map.of("error", "malformed request body: " + e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("unexpected error", e);
        Map<String, Object> body = Map.of("error", "internal error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ResponseEntity<Map<String, Object>> notFound(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }
}
