package com.github.shikejing.reportenginejava.model;

public record FieldDefinition(
        String name,
        DataType type,
        boolean required
) {
}
