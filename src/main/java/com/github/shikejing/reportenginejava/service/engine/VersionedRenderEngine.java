package com.github.shikejing.reportenginejava.service.engine;

import com.github.shikejing.reportenginejava.model.ColumnDefinition;
import com.github.shikejing.reportenginejava.model.DataType;
import com.github.shikejing.reportenginejava.model.ExpressionNode;
import com.github.shikejing.reportenginejava.model.ExpressionOp;
import com.github.shikejing.reportenginejava.model.FieldDefinition;
import com.github.shikejing.reportenginejava.model.ParameterDefinition;
import com.github.shikejing.reportenginejava.model.ReportDefinition;
import com.github.shikejing.reportenginejava.model.StoredDefinition;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class VersionedRenderEngine {

    public Map<String, Object> renderRow(StoredDefinition stored,
                                         Map<String, Object> input,
                                         Map<String, Object> parameters) {
        ReportDefinition definition = stored.definition();
        Map<String, FieldDefinition> fields = new LinkedHashMap<>();
        for (FieldDefinition field : definition.fields()) {
            fields.put(field.name(), field);
        }
        Map<String, ParameterDefinition> parameterDefinitions = new LinkedHashMap<>();
        if (definition.parameters() != null) {
            for (ParameterDefinition parameter : definition.parameters()) {
                parameterDefinitions.put(parameter.name(), parameter);
            }
        }
        validateInput(definition, fields, parameterDefinitions, input, parameters);

        Map<String, Object> output = new LinkedHashMap<>();
        for (ColumnDefinition column : definition.columns()) {
            Object value = evaluate(stored.engineVersion(), column.expression(),
                    input, parameters, parameterDefinitions);
            output.put(column.name(), value);
        }
        return output;
    }

    private void validateInput(ReportDefinition definition,
                               Map<String, FieldDefinition> fields,
                               Map<String, ParameterDefinition> parameterDefinitions,
                               Map<String, Object> input,
                               Map<String, Object> parameters) {
        if (input == null) {
            throw new RenderException("input row must not be null");
        }
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            FieldDefinition field = fields.get(entry.getKey());
            if (field == null) {
                throw new RenderException("unknown input field '" + entry.getKey() + "'");
            }
            checkValue(entry.getValue(), field.type(), "fields." + field.name());
        }
        for (FieldDefinition field : definition.fields()) {
            if (field.required() && !input.containsKey(field.name())) {
                throw new RenderException("missing required field '" + field.name() + "'");
            }
        }
        if (parameters == null) {
            throw new RenderException("parameters must not be null");
        }
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            ParameterDefinition parameter = parameterDefinitions.get(entry.getKey());
            if (parameter == null) {
                throw new RenderException("unknown parameter '" + entry.getKey() + "'");
            }
            checkValue(entry.getValue(), parameter.type(), "parameters." + parameter.name());
        }
        for (ParameterDefinition parameter : parameterDefinitions.values()) {
            if (!parameters.containsKey(parameter.name()) && parameter.required()
                    && parameter.defaultValue() == null) {
                throw new RenderException("missing required parameter '" + parameter.name() + "'");
            }
        }
    }

    private void checkValue(Object value, DataType type, String path) {
        if (value == null) {
            return;
        }
        DataType actual;
        if (value instanceof String) {
            actual = DataType.STRING;
        } else if (value instanceof Boolean) {
            actual = DataType.BOOLEAN;
        } else if (value instanceof Number) {
            actual = DataType.NUMBER;
        } else {
            throw new RenderException(path + " has unsupported JSON type");
        }
        if (actual != type) {
            throw new RenderException(path + " must be " + type + " but was " + actual);
        }
    }

    private Object evaluate(int formatVersion,
                            ExpressionNode node,
                            Map<String, Object> input,
                            Map<String, Object> parameters,
                            Map<String, ParameterDefinition> parameterDefinitions) {
        switch (node.type()) {
            case "CONSTANT":
                return node.value();
            case "FIELD":
                return input.get(node.field());
            case "PARAMETER":
                if (parameters.containsKey(node.parameter())) {
                    return parameters.get(node.parameter());
                }
                ParameterDefinition definition = parameterDefinitions.get(node.parameter());
                return definition == null ? null : definition.defaultValue();
            case "FUNCTION":
                return evaluateFunction(formatVersion, node, input, parameters, parameterDefinitions);
            default:
                throw new RenderException("unknown expression type " + node.type());
        }
    }

    private Object evaluateFunction(int formatVersion,
                                    ExpressionNode node,
                                    Map<String, Object> input,
                                    Map<String, Object> parameters,
                                    Map<String, ParameterDefinition> parameterDefinitions) {
        ExpressionOp op = ExpressionOp.valueOf(node.op());
        if (formatVersion == 1 && (op == ExpressionOp.LOWER || op == ExpressionOp.ROUND)) {
            throw new RenderException(op + " is not available in formatVersion 1");
        }
        Object[] args = node.args().stream()
                .map(arg -> evaluate(formatVersion, arg, input, parameters, parameterDefinitions))
                .toArray();

        return switch (op) {
            case ADD -> numbers(args).reduce(BigDecimal.ZERO, BigDecimal::add);
            case SUB -> numbers(args).reduce(BigDecimal::subtract)
                    .orElseThrow(() -> new RenderException("SUB requires arguments"));
            case MUL -> numbers(args).reduce(BigDecimal.ONE, BigDecimal::multiply);
            case DIV -> {
                BigDecimal divisor = number(args[1]);
                if (divisor.signum() == 0) {
                    throw new RenderException("division by zero");
                }
                yield number(args[0]).divide(divisor, 18, RoundingMode.HALF_UP);
            }
            case UPPER -> text(args[0]).toUpperCase(Locale.ROOT);
            case LOWER -> text(args[0]).toLowerCase(Locale.ROOT);
            case CONCAT -> {
                StringBuilder builder = new StringBuilder();
                for (Object arg : args) {
                    builder.append(text(arg));
                }
                yield builder.toString();
            }
            case ROUND -> number(args[0]).setScale(((Number) node.args().get(1).value()).intValue(),
                    RoundingMode.HALF_UP);
        };
    }

    private java.util.stream.Stream<BigDecimal> numbers(Object[] values) {
        return java.util.Arrays.stream(values).map(this::number);
    }

    private BigDecimal number(Object value) {
        if (value == null) {
            throw new RenderException("numeric expression evaluated to null");
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        throw new RenderException("expected NUMBER but got " + value.getClass().getSimpleName());
    }

    private String text(Object value) {
        if (value == null) {
            throw new RenderException("string expression evaluated to null");
        }
        return value.toString();
    }

    public static class RenderException extends RuntimeException {
        public RenderException(String message) {
            super(message);
        }
    }
}
