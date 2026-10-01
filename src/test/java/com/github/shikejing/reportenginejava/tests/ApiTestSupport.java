package com.github.shikejing.reportenginejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.reportenginejava.model.ColumnDefinition;
import com.github.shikejing.reportenginejava.model.DataType;
import com.github.shikejing.reportenginejava.model.ExpressionNode;
import com.github.shikejing.reportenginejava.model.FieldDefinition;
import com.github.shikejing.reportenginejava.model.ReportDefinition;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

final class ApiTestSupport {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private ApiTestSupport() {
    }

    static ReportDefinition salesReport(boolean v2) {
        ColumnDefinition amount = new ColumnDefinition(
                "amountLabel",
                "Amount Label",
                DataType.STRING,
                new ExpressionNode("FUNCTION", null, null, null, "UPPER",
                        List.of(new ExpressionNode("FIELD", "customer", null, null, null, null)))
        );
        ColumnDefinition tax = new ColumnDefinition(
                "tax",
                "Tax",
                DataType.NUMBER,
                new ExpressionNode("FUNCTION", null, null, null, "ROUND",
                        List.of(
                                new ExpressionNode("FUNCTION", null, null, null, "MUL",
                                        List.of(
                                                new ExpressionNode("FIELD", "amount", null, null, null, null),
                                                new ExpressionNode("CONSTANT", null, null, 0.1, null, null)
                                        )),
                                new ExpressionNode("CONSTANT", null, null, 2, null, null)
                        ))
        );
        List<ColumnDefinition> columns = v2
                ? List.of(amount, tax)
                : List.of(new ColumnDefinition(
                "tax",
                "Tax",
                DataType.NUMBER,
                new ExpressionNode("FUNCTION", null, null, null, "MUL",
                        List.of(
                                new ExpressionNode("FIELD", "amount", null, null, null, null),
                                new ExpressionNode("CONSTANT", null, null, 0.1, null, null)
                        )))
        );
        return new ReportDefinition(
                "sales",
                "Sales Report",
                List.of(
                        new FieldDefinition("customer", DataType.STRING, true),
                        new FieldDefinition("amount", DataType.NUMBER, true)
                ),
                List.of(),
                columns
        );
    }

    static String ndjson(Object... rows) throws Exception {
        StringBuilder builder = new StringBuilder();
        for (Object row : rows) {
            builder.append(MAPPER.writeValueAsString(row)).append('\n');
        }
        return builder.toString();
    }

    static MockHttpServletRequestBuilder scope(MockHttpServletRequestBuilder builder, String scope) {
        return builder.header("X-Scope", scope).header("X-Role", "ADMIN");
    }
}
