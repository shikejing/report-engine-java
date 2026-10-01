package com.github.shikejing.reportenginejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationApiTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void enforcesScopeAuthenticationAndRoles() throws Exception {
        String json = objectMapper.writeValueAsString(ApiTestSupport.salesReport(false));
        mockMvc.perform(ApiTestSupport.scope(put("/api/reports/secret"), "scope-owner")
                        .header("X-Engine-Version", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.replace("\"reportKey\":\"sales\"", "\"reportKey\":\"secret\"")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/reports/secret")
                        .header("X-Scope", "scope-other")
                        .header("X-Role", "ADMIN"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/reports/secret")
                        .header("X-Scope", "scope-other")
                        .header("X-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.replace("\"reportKey\":\"sales\"", "\"reportKey\":\"secret\"")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.scope")
                        .value("scope-other"));
        mockMvc.perform(delete("/api/reports/secret")
                        .header("X-Scope", "scope-other")
                        .header("X-Role", "ADMIN"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/secret")
                        .header("X-Scope", "scope-other")
                        .header("X-Role", "ADMIN"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/reports/secret")
                        .header("X-Scope", "scope-owner")
                        .header("X-Role", "READER"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.scope")
                        .value("scope-owner"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.version")
                        .value(1));
        mockMvc.perform(put("/api/reports/secret")
                        .header("X-Scope", "scope-owner")
                        .header("X-Role", "READER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.replace("\"reportKey\":\"sales\"", "\"reportKey\":\"secret\"")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/reports/secret")
                        .header("X-Scope", "scope-owner")
                        .header("X-Role", "READER"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/reports/secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void renderJobsAreNotVisibleOutsideOwnerScope() throws Exception {
        String json = objectMapper.writeValueAsString(ApiTestSupport.salesReport(true))
                .replace("\"reportKey\":\"sales\"", "\"reportKey\":\"private-render\"");
        mockMvc.perform(ApiTestSupport.scope(put("/api/reports/private-render"), "job-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
        String response = mockMvc.perform(post("/api/reports/private-render/renders")
                        .header("X-Scope", "job-owner")
                        .header("X-Role", "READER")
                        .contentType(ApiTestSupport.NDJSON)
                        .content(ApiTestSupport.ndjson(java.util.Map.of("customer", "a", "amount", 1))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String jobId = objectMapper.readValue(response, JsonNode.class).get("jobId").asText();

        mockMvc.perform(get("/api/reports/renders/{id}", jobId)
                        .header("X-Scope", "job-intruder")
                        .header("X-Role", "READER"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/reports/renders/{id}/rows", jobId)
                        .header("X-Scope", "job-intruder")
                        .header("X-Role", "READER"))
                .andExpect(status().isNotFound());
    }
}
