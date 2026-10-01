package com.github.shikejing.reportenginejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RenderApiTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void rendersStableBatchesForSameDefinitionAndInput() throws Exception {
        createSalesReport("render-stable", 2);
        String body = ApiTestSupport.ndjson(
                Map.of("customer", "alpha", "amount", 10),
                Map.of("customer", "beta", "amount", 20),
                Map.of("customer", "gamma", "amount", 30)
        );

        MvcResult result = mockMvc.perform(post("/api/reports/render-stable/renders")
                        .header("X-Scope", "tenant-render")
                        .header("X-Role", "READER")
                        .contentType(ApiTestSupport.NDJSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.definitionVersion").value(1))
                .andExpect(jsonPath("$.totalRows").value(3))
                .andReturn();
        String jobId = objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("jobId").asText();

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(get("/api/reports/renders/{id}/rows", jobId)
                            .header("X-Scope", "tenant-render")
                            .header("X-Role", "READER")
                            .param("offset", "0")
                            .param("limit", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows[0].tax").value(1.00))
                    .andExpect(jsonPath("$.rows[1].tax").value(2.00))
                    .andExpect(jsonPath("$.end").value(false));

            mockMvc.perform(get("/api/reports/renders/{id}/rows", jobId)
                            .header("X-Scope", "tenant-render")
                            .header("X-Role", "READER")
                            .param("offset", "2")
                            .param("limit", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows[0].tax").value(3.00))
                    .andExpect(jsonPath("$.end").value(true));
        }
    }

    @Test
    void failedRenderDiscardsOutputAndKeepsAnExplainedTerminalState() throws Exception {
        createSalesReport("render-fail", 2);
        String body = ApiTestSupport.ndjson(Map.of("customer", "alpha", "amount", "not-a-number"));

        MvcResult result = mockMvc.perform(post("/api/reports/render-fail/renders")
                        .header("X-Scope", "tenant-fail")
                        .header("X-Role", "READER")
                        .contentType(ApiTestSupport.NDJSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMessage").isNotEmpty())
                .andExpect(jsonPath("$.totalRows").value(0))
                .andReturn();
        String jobId = objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("jobId").asText();

        mockMvc.perform(get("/api/reports/renders/{id}/rows", jobId)
                        .header("X-Scope", "tenant-fail")
                        .header("X-Role", "READER"))
                .andExpect(status().isConflict());
    }

    private void createSalesReport(String key, int engineVersion) throws Exception {
        Object definition = ApiTestSupport.salesReport(true);
        Object rewritten = objectMapper.convertValue(definition, Map.class);
        ((Map<?, ?>) rewritten).getClass();
        String json = objectMapper.writeValueAsString(definition).replace("\"reportKey\":\"sales\"",
                "\"reportKey\":\"" + key + "\"");
        mockMvc.perform(ApiTestSupport.scope(put("/api/reports/" + key), "tenant-render")
                        .header("X-Engine-Version", engineVersion)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
        mockMvc.perform(ApiTestSupport.scope(put("/api/reports/" + key), "tenant-fail")
                        .header("X-Engine-Version", engineVersion)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.replace("\"reportKey\":\"" + key + "\"",
                                "\"reportKey\":\"" + key + "\"")))
                .andExpect(status().isOk());
    }
}
