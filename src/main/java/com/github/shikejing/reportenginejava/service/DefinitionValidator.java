package com.github.shikejing.reportenginejava.service;

import com.github.shikejing.reportenginejava.model.ColumnDefinition;
import com.github.shikejing.reportenginejava.model.DataType;
import com.github.shikejing.reportenginejava.model.ExpressionNode;
import com.github.shikejing.reportenginejava.model.ExpressionOp;
import com.github.shikejing.reportenginejava.model.FieldDefinition;
import com.github.shikejing.reportenginejava.model.ParameterDefinition;
import com.github.shikejing.reportenginejava.model.ReportDefinition;
import com.github.shikejing.reportenginejava.model.ValidationIssue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class DefinitionValidator {

    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Set<Integer> SUPPORTED_VERSIONS = Set.of(1, 2);

    public List<ValidationIssue> validate(ReportDefinition definition, int engineVersion) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (definition == null) {
            issues.add(new ValidationIssue("$", "definition must not be null"));
            return issues;
        }

        requireKey(definition.reportKey(), "$.reportKey", issues);
        if (isBlank(definition.name())) {
            issues.add(new ValidationIssue("$.name", "must not be blank"));
        }
        if (engineVersion < 1 || engineVersion > 2) {
            issues.add(new ValidationIssue("$.engineVersion", "supported versions are 1 and 2"));
        }

        Map<String, FieldDefinition> fields = validateFields(definition.fields(), issues);
        Map<String, ParameterDefinition> parameters = validateParameters(definition.parameters(), issues);
        validateColumns(definition, engineVersion, fields, parameters, issues);
        return issues;
    }

    private Map<String, FieldDefinition> validateFields(List<FieldDefinition> fields,
                                                        List<ValidationIssue> issues) {
        if (fields == null || fields.isEmpty()) {
            issues.add(new ValidationIssue("$.fields", "must contain at least one field"));
            return Map.of();
        }
        Map<String, FieldDefinition> result = new java.util.HashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            String path = "$.fields[" + i + "]";
            FieldDefinition field = fields.get(i);
            if (field == null) {
                issues.add(new ValidationIssue(path, "must not be null"));
                continue;
            }
            if (!requireName(field.name(), path + ".name", issues) || !seen.add(field.name())) {
                issues.add(new ValidationIssue(path + ".name", "duplicate field name"));
            }
            if (field.type() == null) {
                issues.add(new ValidationIssue(path + ".type", "must be STRING, NUMBER, or BOOLEAN"));
            } else if (field.name() != null) {
                result.put(field.name(), field);
            }
        }
        return Map.copyOf(result);
    }

    private Map<String, ParameterDefinition> validateParameters(List<ParameterDefinition> parameters,
                                                                List<ValidationIssue> issues) {
        if (parameters == null) {
            return Map.of();
        }
        Map<String, ParameterDefinition> result = new java.util.HashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < parameters.size(); i++) {
            String path = "$.parameters[" + i + "]";
            ParameterDefinition parameter = parameters.get(i);
            if (parameter == null) {
                issues.add(new ValidationIssue(path, "must not be null"));
                continue;
            }
            if (!requireName(parameter.name(), path + ".name", issues) || !seen.add(parameter.name())) {
                issues.add(new ValidationIssue(path + ".name", "duplicate parameter name"));
            }
            if (parameter.type() == null) {
                issues.add(new ValidationIssue(path + ".type", "must be STRING, NUMBER, or BOOLEAN"));
            } else {
                DataType defaultType = literalType(parameter.defaultValue()).orElse(null);
                if (defaultType != null && defaultType != parameter.type()) {
                    issues.add(new ValidationIssue(path + ".defaultValue",
                            "default value must be " + parameter.type()));
                }
                if (parameter.name() != null) {
                    result.put(parameter.name(), parameter);
                }
            }
        }
        return Map.copyOf(result);
    }

    private void validateColumns(ReportDefinition definition,
                                 int engineVersion,
                                 Map<String, FieldDefinition> fields,
                                 Map<String, ParameterDefinition> parameters,
                                 List<ValidationIssue> issues) {
        List<ColumnDefinition> columns = definition.columns();
        if (columns == null || columns.isEmpty()) {
            issues.add(new ValidationIssue("$.columns", "must contain at least one column"));
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < columns.size(); i++) {
            String path = "$.columns[" + i + "]";
            ColumnDefinition column = columns.get(i);
            if (column == null) {
                issues.add(new ValidationIssue(path, "must not be null"));
                continue;
            }
            if (!requireName(column.name(), path + ".name", issues) || !seen.add(column.name())) {
                issues.add(new ValidationIssue(path + ".name", "duplicate column name"));
            }
            if (isBlank(column.label())) {
                issues.add(new ValidationIssue(path + ".label", "must not be blank"));
            }
            if (column.dataType() == null) {
                issues.add(new ValidationIssue(path + ".dataType", "must be STRING, NUMBER, or BOOLEAN"));
            }
            if (column.expression() == null) {
                issues.add(new ValidationIssue(path + ".expression", "must not be null"));
            } else {
                DataType inferred = inferExpression(column.expression(), engineVersion,
                        fields, parameters, path + ".expression", issues);
                if (inferred != null && column.dataType() != null && inferred != column.dataType()) {
                    issues.add(new ValidationIssue(path + ".dataType",
                            "expression produces " + inferred + ", not " + column.dataType()));
                }
            }
        }
    }

    private DataType inferExpression(ExpressionNode node,
                                     int formatVersion,
                                     Map<String, FieldDefinition> fields,
                                     Map<String, ParameterDefinition> parameters,
                                     String path,
                                     List<ValidationIssue> issues) {
        if (node == null) {
            issues.add(new ValidationIssue(path, "expression must not be null"));
            return null;
        }
        String kind = node.type();
        if ("FIELD".equals(kind)) {
            if (isBlank(node.field())) {
                issues.add(new ValidationIssue(path + ".field", "must not be blank"));
                return null;
            }
            FieldDefinition field = fields.get(node.field());
            if (field == null) {
                issues.add(new ValidationIssue(path + ".field", "references undefined field '"
                        + node.field() + "'"));
                return null;
            }
            return field.type();
        }
        if ("PARAMETER".equals(kind)) {
            if (isBlank(node.parameter())) {
                issues.add(new ValidationIssue(path + ".parameter", "must not be blank"));
                return null;
            }
            ParameterDefinition parameter = parameters.get(node.parameter());
            if (parameter == null) {
                issues.add(new ValidationIssue(path + ".parameter", "references undefined parameter '"
                        + node.parameter() + "'"));
                return null;
            }
            return parameter.type();
        }
        if ("CONSTANT".equals(kind)) {
            Optional<DataType> type = literalType(node.value());
            if (type.isEmpty()) {
                issues.add(new ValidationIssue(path + ".value",
                        "must be a JSON string, number, or boolean"));
                return null;
            }
            return type.get();
        }
        if ("FUNCTION".equals(kind)) {
            return inferFunction(node, formatVersion, fields, parameters, path, issues);
        }
        issues.add(new ValidationIssue(path + ".type",
                "must be FIELD, PARAMETER, CONSTANT, or FUNCTION"));
        return null;
    }

    private DataType inferFunction(ExpressionNode node,
                                   int formatVersion,
                                   Map<String, FieldDefinition> fields,
                                   Map<String, ParameterDefinition> parameters,
                                   String path,
                                   List<ValidationIssue> issues) {
        ExpressionOp op;
        try {
            op = ExpressionOp.valueOf(node.op());
        } catch (RuntimeException ex) {
            issues.add(new ValidationIssue(path + ".op", "unknown function '" + node.op() + "'"));
            return null;
        }
        if (formatVersion == 1 && (op == ExpressionOp.LOWER || op == ExpressionOp.ROUND)) {
            issues.add(new ValidationIssue(path + ".op",
                    op + " requires formatVersion 2"));
        }

        List<ExpressionNode> args = node.args();
        Integer expectedArity = switch (op) {
            case ADD, CONCAT -> null;
            case SUB, MUL, DIV -> op == ExpressionOp.MUL ? null : 2;
            case UPPER, LOWER -> 1;
            case ROUND -> 2;
        };
        if (args == null || args.isEmpty()) {
            issues.add(new ValidationIssue(path + ".args", "must contain function arguments"));
            return null;
        }
        if (expectedArity != null && args.size() != expectedArity) {
            issues.add(new ValidationIssue(path + ".args",
                    op + " requires exactly " + expectedArity + " arguments"));
            return null;
        }
        if (expectedArity == null && args.size() < 2) {
            issues.add(new ValidationIssue(path + ".args", op + " requires at least 2 arguments"));
            return null;
        }

        List<DataType> argTypes = new ArrayList<>(args.size());
        for (int i = 0; i < args.size(); i++) {
            argTypes.add(inferExpression(args.get(i), formatVersion, fields, parameters,
                    path + ".args[" + i + "]", issues));
        }
        if (argTypes.stream().anyMatch(type -> type == null)) {
            return null;
        }

        return switch (op) {
            case ADD, SUB, MUL, DIV -> {
                if (argTypes.stream().anyMatch(type -> type != DataType.NUMBER)) {
                    issues.add(new ValidationIssue(path + ".args", op + " requires NUMBER arguments"));
                    yield null;
                }
                if (op == ExpressionOp.DIV && isZeroLiteral(args.get(1))) {
                    issues.add(new ValidationIssue(path + ".args[1]", "constant divisor must not be zero"));
                    yield null;
                }
                yield DataType.NUMBER;
            }
            case UPPER, LOWER -> {
                if (argTypes.get(0) != DataType.STRING) {
                    issues.add(new ValidationIssue(path + ".args", op + " requires a STRING argument"));
                    yield null;
                }
                yield DataType.STRING;
            }
            case CONCAT -> {
                if (argTypes.stream().anyMatch(type -> type != DataType.STRING)) {
                    issues.add(new ValidationIssue(path + ".args", "CONCAT requires STRING arguments"));
                    yield null;
                }
                yield DataType.STRING;
            }
            case ROUND -> {
                if (argTypes.get(0) != DataType.NUMBER) {
                    issues.add(new ValidationIssue(path + ".args[0]", "ROUND requires a NUMBER value"));
                    yield null;
                }
                Object digits = args.get(1).value();
                if (!(digits instanceof Number number) || !isIntegerValue(number)) {
                    issues.add(new ValidationIssue(path + ".args[1]",
                            "ROUND digits must be an integer CONSTANT"));
                    yield null;
                }
                long digitCount = number.longValue();
                if (digitCount < 0 || digitCount > 18) {
                    issues.add(new ValidationIssue(path + ".args[1]",
                            "ROUND digits must be between 0 and 18"));
                    yield null;
                }
                yield DataType.NUMBER;
            }
        };
    }

    private boolean isIntegerValue(Number number) {
        if (number instanceof Integer || number instanceof Long) {
            return true;
        }
        BigDecimal decimal = number instanceof BigDecimal value
                ? value
                : new BigDecimal(number.toString());
        return decimal.stripTrailingZeros().scale() <= 0;
    }

    private boolean isZeroLiteral(ExpressionNode node) {
        return "CONSTANT".equals(node.type()) && node.value() instanceof Number number
                && number.doubleValue() == 0.0d;
    }

    private Optional<DataType> literalType(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof String) {
            return Optional.of(DataType.STRING);
        }
        if (value instanceof Boolean) {
            return Optional.of(DataType.BOOLEAN);
        }
        if (value instanceof Number) {
            return Optional.of(DataType.NUMBER);
        }
        return Optional.empty();
    }

    private boolean requireKey(String value, String path, List<ValidationIssue> issues) {
        if (isBlank(value)) {
            issues.add(new ValidationIssue(path, "must not be blank"));
            return false;
        }
        if (!KEY.matcher(value).matches()) {
            issues.add(new ValidationIssue(path, "must use lowercase letters, numbers, '_' or '-'"));
            return false;
        }
        return true;
    }

    private boolean requireName(String value, String path, List<ValidationIssue> issues) {
        if (isBlank(value)) {
            issues.add(new ValidationIssue(path, "must not be blank"));
            return false;
        }
        if (!NAME.matcher(value).matches()) {
            issues.add(new ValidationIssue(path, "must start with a letter or underscore"));
            return false;
        }
        return true;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
