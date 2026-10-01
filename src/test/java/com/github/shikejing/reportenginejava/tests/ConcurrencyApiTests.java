package com.github.shikejing.reportenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class ConcurrencyApiTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void concurrentRendersWithSameInputProduceIdenticalRows() throws Exception {
        String key = "concurrent-renders";
        putDefinition(key, 2, ApiTestSupport.salesReport(true));
        String body = rows(30);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit((Callable<List<String>>) () -> renderAll(key)));
            }
            Set<List<String>> outputs = new HashSet<>();
            for (Future<List<String>> future : futures) {
                outputs.add(future.get());
            }
            org.junit.jupiter.api.Assertions.assertEquals(1, outputs.size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentUpdatesAndReadsNeverMixVersions() throws Exception {
        String key = "version-isolation";
        putDefinition(key, 1, ApiTestSupport.salesReport(false));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> update = pool.submit(() -> {
                try {
                    putDefinition(key, 2, ApiTestSupport.salesReport(true));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            List<Future<JsonNode>> reads = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                reads.add(pool.submit(() -> readVersions(key)));
            }
            update.get();
            for (Future<JsonNode> future : reads) {
                JsonNode versions = future.get();
                for (JsonNode version : versions) {
                    int number = version.get("version").asInt();
                    int engine = version.get("engineVersion").asInt();
                    org.junit.jupiter.api.Assertions.assertEquals(number, engine);
                }
            }
            mockMvc.perform(ApiTestSupport.scope(get("/api/reports/" + key), "tenant-concurrency")
                            .param("version", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(1))
                    .andExpect(jsonPath("$.engineVersion").value(1));
            mockMvc.perform(ApiTestSupport.scope(get("/api/reports/" + key), "tenant-concurrency")
                            .param("version", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(2))
                    .andExpect(jsonPath("$.engineVersion").value(2));
        } finally {
            pool.shutdownNow();
        }
    }

    private JsonNode readVersions(String key) throws Exception {
        return objectMapper.readTree(mockMvc.perform(ApiTestSupport.scope(
                                get("/api/reports/" + key + "/versions"), "tenant-concurrency"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private List<String> renderAll(String key) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/reports/" + key + "/renders")
                        .header("X-Scope", "tenant-concurrency")
                        .header("X-Role", "READER")
                        .contentType(ApiTestSupport.NDJSON)
                        .content(rows(30)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andReturn();
        String jobId = objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .get("jobId").asText();
        List<String> rows = new ArrayList<>();
        long offset = 0;
        while (true) {
            JsonNode batch = objectMapper.readTree(mockMvc.perform(get("/api/reports/renders/{id}/rows", jobId)
                            .header("X-Scope", "tenant-concurrency")
                            .header("X-Role", "READER")
                            .param("offset", String.valueOf(offset))
                            .param("limit", "7"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            JsonNode nodes = batch.get("rows");
            for (JsonNode node : nodes) {
                rows.add(node.toString());
            }
            if (batch.get("end").asBoolean()) {
                return List.copyOf(rows);
            }
            offset += nodes.size();
        }
    }

    private String rows(int count) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(Map.of("customer", "c" + i, "amount", i));
        }
        return ApiTestSupport.ndjson(rows.toArray());
    }

    private void putDefinition(String key, int engineVersion, Object definition) throws Exception {
        String json = objectMapper.writeValueAsString(definition)
                .replace("\"reportKey\":\"sales\"", "\"reportKey\":\"" + key + "\"");
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder =
                ApiTestSupport.scope(put("/api/reports/" + key), "tenant-concurrency")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json);
        builder.header("X-Engine-Version", engineVersion);
        mockMvc.perform(builder).andExpect(status().isOk());
    }
}
