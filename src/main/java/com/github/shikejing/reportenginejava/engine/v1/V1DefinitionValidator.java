package com.github.shikejing.reportenginejava.engine.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shikejing.reportenginejava.engine.DefinitionValidator;
import com.github.shikejing.reportenginejava.model.Column;
import com.github.shikejing.reportenginejava.model.ColumnType;
import com.github.shikejing.reportenginejava.model.Definition;
import com.github.shikejing.reportenginejava.model.SortSpec;
import com.github.shikejing.reportenginejava.model.error.FieldError;
import com.github.shikejing.reportenginejava.model.error.ValidationException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 格式版本 1 的定义校验器。
 *
 * <p>校验规则（字段缺失 / 类型不符 / 引用不存在对象 / 字段组合不合法）
 * 全部在写入前执行，错误信息定位到具体字段路径。
 */
@Component
public class V1DefinitionValidator implements DefinitionValidator {

    public static final int FORMAT_VERSION = 1;

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,100}$");
    private static final Pattern FIELD_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{1,100}$");
    private static final Set<String> ROOT_FIELDS = Set.of("name", "description", "columns", "sort");
    private static final Set<String> COLUMN_FIELDS = Set.of("field", "header", "type", "format", "transform");
    private static final Set<String> SORT_FIELDS = Set.of("field", "direction");

    @Override
    public int formatVersion() {
        return FORMAT_VERSION;
    }

    @Override
    public Definition validate(JsonNode content) throws ValidationException {
        List<FieldError> errors = new ArrayList<>();
        if (content == null || !content.isObject()) {
            throw new ValidationException(List.of(new FieldError("$", "definition must be a JSON object")));
        }

        checkUnknownFields(content, ROOT_FIELDS, "$", errors);

        Definition def = new Definition();

        // name：必填，字符串，合法标识符
        JsonNode nameNode = content.get("name");
        if (nameNode == null || nameNode.isNull()) {
            errors.add(new FieldError("name", "name is required"));
        } else if (!nameNode.isTextual()) {
            errors.add(new FieldError("name", "name must be a string"));
        } else if (!NAME_PATTERN.matcher(nameNode.asText()).matches()) {
            errors.add(new FieldError("name", "name must match " + NAME_PATTERN.pattern()));
        } else {
            def.setName(nameNode.asText());
        }

        // description：可选字符串
        JsonNode descNode = content.get("description");
        if (descNode != null && !descNode.isNull()) {
            if (!descNode.isTextual()) {
                errors.add(new FieldError("description", "description must be a string"));
            } else {
                def.setDescription(descNode.asText());
            }
        }

        // columns：必填，非空数组
        JsonNode columnsNode = content.get("columns");
        List<Column> columns = new ArrayList<>();
        if (columnsNode == null || columnsNode.isNull()) {
            errors.add(new FieldError("columns", "columns is required"));
        } else if (!columnsNode.isArray()) {
            errors.add(new FieldError("columns", "columns must be an array"));
        } else if (columnsNode.isEmpty()) {
            errors.add(new FieldError("columns", "columns must contain at least one column"));
        } else {
            Set<String> seenFields = new HashSet<>();
            for (int i = 0; i < columnsNode.size(); i++) {
                JsonNode colNode = columnsNode.get(i);
                String path = "columns[" + i + "]";
                if (!colNode.isObject()) {
                    errors.add(new FieldError(path, "column must be an object"));
                    continue;
                }
                checkUnknownFields(colNode, COLUMN_FIELDS, path, errors);
                Column column = validateColumn(colNode, path, errors);
                if (column != null) {
                    if (column.getField() != null && !seenFields.add(column.getField())) {
                        errors.add(new FieldError(path + ".field",
                                "duplicate column field '" + column.getField() + "'"));
                    }
                    columns.add(column);
                }
            }
            def.setColumns(columns);
        }

        // sort：可选，field 必须引用已存在的列
        JsonNode sortNode = content.get("sort");
        if (sortNode != null && !sortNode.isNull()) {
            if (!sortNode.isObject()) {
                errors.add(new FieldError("sort", "sort must be an object"));
            } else {
                checkUnknownFields(sortNode, SORT_FIELDS, "sort", errors);
                SortSpec sort = validateSort(sortNode, def, errors);
                def.setSort(sort);
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return def;
    }

    protected Column validateColumn(JsonNode colNode, String path, List<FieldError> errors) {
        Column column = new Column();

        JsonNode fieldNode = colNode.get("field");
        if (fieldNode == null || fieldNode.isNull()) {
            errors.add(new FieldError(path + ".field", "field is required"));
        } else if (!fieldNode.isTextual()) {
            errors.add(new FieldError(path + ".field", "field must be a string"));
        } else if (!FIELD_PATTERN.matcher(fieldNode.asText()).matches()) {
            errors.add(new FieldError(path + ".field", "field must match " + FIELD_PATTERN.pattern()));
        } else {
            column.setField(fieldNode.asText());
        }

        JsonNode typeNode = colNode.get("type");
        ColumnType type = null;
        if (typeNode == null || typeNode.isNull()) {
            errors.add(new FieldError(path + ".type", "type is required"));
        } else if (!typeNode.isTextual()) {
            errors.add(new FieldError(path + ".type", "type must be a string"));
        } else {
            try {
                type = ColumnType.parse(typeNode.asText());
                column.setType(type);
            } catch (IllegalArgumentException e) {
                errors.add(new FieldError(path + ".type",
                        "type must be one of STRING, NUMBER, DATE, BOOLEAN"));
            }
        }

        JsonNode headerNode = colNode.get("header");
        if (headerNode != null && !headerNode.isNull()) {
            if (!headerNode.isTextual()) {
                errors.add(new FieldError(path + ".header", "header must be a string"));
            } else {
                column.setHeader(headerNode.asText());
            }
        }

        JsonNode formatNode = colNode.get("format");
        if (formatNode != null && !formatNode.isNull()) {
            if (!formatNode.isTextual()) {
                errors.add(new FieldError(path + ".format", "format must be a string"));
            } else {
                String format = formatNode.asText();
                column.setFormat(format);
                if (type == ColumnType.STRING || type == ColumnType.BOOLEAN) {
                    errors.add(new FieldError(path + ".format",
                            "format is not allowed for column type " + type));
                } else if (type == ColumnType.NUMBER && !isValidDecimalFormat(format)) {
                    errors.add(new FieldError(path + ".format", "invalid number format pattern: " + format));
                } else if (type == ColumnType.DATE && !isValidDateFormat(format)) {
                    errors.add(new FieldError(path + ".format", "invalid date format pattern: " + format));
                }
            }
        }

        // transform 是 v2 能力，v1 定义中出现即非法
        JsonNode transformNode = colNode.get("transform");
        if (transformNode != null && !transformNode.isNull()) {
            validateTransform(transformNode, type, path, errors);
            if (transformNode.isTextual()) {
                column.setTransform(transformNode.asText());
            }
        }

        return column;
    }

    /** v1 不支持 transform 字段；子类（v2）可覆盖。 */
    protected void validateTransform(JsonNode transformNode, ColumnType type, String path,
                                     List<FieldError> errors) {
        errors.add(new FieldError(path + ".transform",
                "transform is not supported in definition formatVersion " + FORMAT_VERSION));
    }

    protected SortSpec validateSort(JsonNode sortNode, Definition def, List<FieldError> errors) {
        SortSpec sort = new SortSpec();

        JsonNode fieldNode = sortNode.get("field");
        if (fieldNode == null || fieldNode.isNull()) {
            errors.add(new FieldError("sort.field", "sort.field is required when sort is specified"));
        } else if (!fieldNode.isTextual()) {
            errors.add(new FieldError("sort.field", "sort.field must be a string"));
        } else {
            String field = fieldNode.asText();
            sort.setField(field);
            boolean exists = def.getColumns().stream()
                    .anyMatch(c -> field.equals(c.getField()));
            if (!exists) {
                errors.add(new FieldError("sort.field",
                        "sort.field '" + field + "' does not reference any column"));
            }
        }

        JsonNode dirNode = sortNode.get("direction");
        if (dirNode != null && !dirNode.isNull()) {
            if (!dirNode.isTextual()) {
                errors.add(new FieldError("sort.direction", "sort.direction must be a string"));
            } else {
                String raw = dirNode.asText();
                try {
                    sort.setDirection(SortSpec.Direction.valueOf(raw.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    errors.add(new FieldError("sort.direction", "direction must be ASC or DESC"));
                }
            }
        }
        return sort;
    }

    protected void checkUnknownFields(JsonNode node, Set<String> allowed, String path,
                                      List<FieldError> errors) {
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                errors.add(new FieldError(path + "." + name, "unknown field '" + name + "'"));
            }
        });
    }

    private static boolean isValidDecimalFormat(String pattern) {
        try {
            new java.text.DecimalFormat(pattern);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isValidDateFormat(String pattern) {
        try {
            new java.text.SimpleDateFormat(pattern);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
