package com.github.shikejing.reportenginejava.model;

import java.util.Objects;

public record ParameterDefinition(
        String name,
        DataType type,
        boolean required,
        Object defaultValue
) {
    public ParameterDefinition {
        Objects.requireNonNull(name, "name");
    }
}
