package com.github.shikejing.reportenginejava.controller;

import com.github.shikejing.reportenginejava.exception.AuthorizationException;
import com.github.shikejing.reportenginejava.exception.ConflictException;
import com.github.shikejing.reportenginejava.exception.ForbiddenException;
import com.github.shikejing.reportenginejava.exception.NotFoundException;
import com.github.shikejing.reportenginejava.exception.ValidationException;
import com.github.shikejing.reportenginejava.model.ValidationIssue;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> validation(ValidationException exception) {
        return body("validation_failed", exception.getMessage(),
                Map.of("issues", exception.issues()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(Exception exception) {
        List<ValidationIssue> issues = List.of(new ValidationIssue("$", exception.getMessage()));
        return body("bad_request", "request was rejected", Map.of("issues", issues));
    }

    @ExceptionHandler(AuthorizationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, Object> unauthorized(AuthorizationException exception) {
        return body("unauthorized", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, Object> forbidden(ForbiddenException exception) {
        return body("forbidden", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> notFound(NotFoundException exception) {
        return body("not_found", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> conflict(ConflictException exception) {
        return body("conflict", exception.getMessage(), Map.of());
    }

    private Map<String, Object> body(String code, String message, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("timestamp", Instant.now());
        body.putAll(details);
        return Map.copyOf(body);
    }
}
