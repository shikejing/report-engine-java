package com.github.shikejing.reportenginejava.model;

import java.util.List;

public record ReportDefinition(
        String reportKey,
        String name,
        List<FieldDefinition> fields,
        List<ParameterDefinition> parameters,
        List<ColumnDefinition> columns
) {
}
