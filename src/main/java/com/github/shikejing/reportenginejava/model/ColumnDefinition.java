package com.github.shikejing.reportenginejava.model;

public record ColumnDefinition(
        String name,
        String label,
        DataType dataType,
        ExpressionNode expression
) {
}
