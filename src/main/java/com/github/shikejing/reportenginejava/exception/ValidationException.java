package com.github.shikejing.reportenginejava.exception;

import com.github.shikejing.reportenginejava.model.ValidationIssue;
import java.util.List;

public class ValidationException extends RuntimeException {
    private final List<ValidationIssue> issues;

    public ValidationException(List<ValidationIssue> issues) {
        super("Report definition validation failed");
        this.issues = List.copyOf(issues);
    }

    public List<ValidationIssue> issues() {
        return issues;
    }
}
