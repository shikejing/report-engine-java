package com.github.shikejing.reportenginejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.reportenginejava.model.ColumnDefinition;
import com.github.shikejing.reportenginejava.model.DataType;
import com.github.shikejing.reportenginejava.model.ExpressionNode;
import com.github.shikejing.reportenginejava.model.FieldDefinition;
import com.github.shikejing.reportenginejava.model.ReportDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DefinitionApiTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void rejectsMissingFieldWithPathAndDoesNotStorePartialVersion() throws Exception {
        ReportDefinition invalid = new ReportDefinition(
                "bad-missing",
                "Bad",
                List.of(new FieldDefinition("name", DataType.STRING, true)),
                List.of(),
                List.of()
        );

        mockMvc.perform(ApiTestSupport.scope(put("/api/reports/bad-missing"), "tenant-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.issues[?(@.field == '$.columns')]").exists());

        mockMvc.perform(ApiTestSupport.scope(get("/api/reports/bad-missing"), "tenant-a"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsTypeMismatchAndUnknownReference() throws Exception {
        ReportDefinition invalid = new ReportDefinition(
                "bad-ref",
                "Bad",
                List.of(new FieldDefinition("name", DataType.STRING, true)),
                List.of(),
                List.of(
                        new ColumnDefinition(
                                "amount",
                                "Amount",
                                DataType.NUMBER,
                                new ExpressionNode("FIELD", "name", null, null, null, null)
                        ),
                        new ColumnDefinition(
                                "missingColumn",
                                "Missing",
                                DataType.NUMBER,
                                new ExpressionNode("FIELD", "missing", null, null, null, null)
                        )
                )
        );

        mockMvc.perform(ApiTestSupport.scope(put("/api/reports/bad-ref"), "tenant-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.issues[?(@.field == '$.columns[1].expression.field')]").exists())
                .andExpect(jsonPath("$.issues[?(@.field == '$.columns[0].dataType')]").exists());
    }

    @Test
    void rejectsIllegalFunctionCombination() throws Exception {
        ReportDefinition invalid = new ReportDefinition(
                "bad-func",
                "Bad",
                List.of(new FieldDefinition("amount", DataType.NUMBER, true)),
                List.of(),
                List.of(new ColumnDefinition(
                        "rounded",
                        "Rounded",
                        DataType.NUMBER,
                        new ExpressionNode("FUNCTION", null, null, null, "ROUND",
                                List.of(
                                        new ExpressionNode("FIELD", "amount", null, null, null, null),
                                        new ExpressionNode("CONSTANT", null, null, 99, null, null)
                                ))
                ))
        );

        mockMvc.perform(ApiTestSupport.scope(put("/api/reports/bad-func"), "tenant-a")
                        .header("X-Engine-Version", 2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.issues[?(@.field == '$.columns[0].expression.args[1]')]").exists());
    }

    @Test
    void oldVersionKeepsOldSemanticsWhenServerAddsCapability() throws Exception {
        mockMvc.perform(ApiTestSupport.scope(put("/api/reports/sales"), "tenant-a")
                        .header("X-Engine-Version", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ApiTestSupport.salesReport(false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engineVersion").value(1))
                .andExpect(jsonPath("$.version").value(1));

        ReportDefinition v2Definition = ApiTestSupport.salesReport(true);
        mockMvc.perform(ApiTestSupport.scope(put("/api/reports/sales"), "tenant-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(v2Definition)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.issues[?(@.field == '$.columns[1].expression.op')]").exists());

        mockMvc.perform(ApiTestSupport.scope(get("/api/reports/sales"), "tenant-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.engineVersion").value(1));

        mockMvc.perform(ApiTestSupport.scope(get("/api/reports/sales/versions"), "tenant-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }
}
