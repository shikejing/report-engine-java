package com.github.shikejing.reportenginejava.model;

public record ValidationIssue(
        String field,
        String message
) {
}
