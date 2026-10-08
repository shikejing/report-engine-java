package com.github.shikejing.reportenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shikejing.reportenginejava.api.dto.JobResponse;
import com.github.shikejing.reportenginejava.model.JobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 外部归并排序正确性测试（HTTP 端到端）：
 * 强制较小 fanIn 使输入跨多趟归并，输出必须与「一次性内存稳定排序」
 * 的期望顺序逐条一致 —— 包括重复键的稳定顺序与 null 永远末尾，
 * 从而保证排序改造前后输出完全一致。
 */
@TestPropertySource(properties = "report.engine.render.external-sort-fan-in=3")
class ExternalSortCorrectnessTest extends IntegrationTestBase {

    private String sortedDefinition(String name) {
        return "{\"name\":\"" + name + "\",\"columns\":["
                + "{\"field\":\"id\",\"type\":\"STRING\"},"
                + "{\"field\":\"k\",\"type\":\"NUMBER\"}],"
                + "\"sort\":{\"field\":\"k\",\"direction\":\"ASC\"}}";
    }

    @Test
    void externalSortedOutput_matchesInMemoryStableSort_nullsLast() throws Exception {
        String scope = "s-extsort";
        restTemplate.postForEntity("/api/definitions",
                entity(scope, sortedDefinition("r")), Map.class);

        // 5000 行，键在 0..49 之间循环且部分为 null；batchSize=37 → 必然多段多趟。
        List<Map<String, Object>> data = new ArrayList<>();
        List<Integer> expectedKeyOrder = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            Integer key = (i % 13 == 0) ? null : (i % 50);
            data.add(row("id", "id-" + i, "k", key));
            expectedKeyOrder.add(i);
        }
        // 期望：与历史实现相同的稳定排序（null 末尾，非 null 升序，同键保持输入顺序）
        expectedKeyOrder.sort(Comparator.comparing((Integer idx) -> {
            Integer k = (idx % 13 == 0) ? null : (idx % 50);
            return k == null ? Integer.MAX_VALUE : k;
        }));

        String req = renderRequest("r", 1, data, "JSON", 37, null);
        JobResponse job = waitForTerminal(scope, submitRender(scope, req).jobId());
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.chunkCount()).isEqualTo((5000 + 36) / 37);

        List<String> gotIds = new ArrayList<>();
        List<Integer> gotKeys = new ArrayList<>();
        for (int c = 0; c < job.chunkCount(); c++) {
            JsonNode chunk = fetchChunkJson(scope, job.jobId(), c);
            for (JsonNode row : chunk) {
                gotIds.add(row.path("id").asText());
                JsonNode k = row.path("k");
                gotKeys.add(k.isNull() || k.asText().isEmpty() ? null : k.asInt());
            }
        }
        assertThat(gotIds).hasSize(5000);

        for (int i = 0; i < 5000; i++) {
            int idx = expectedKeyOrder.get(i);
            assertThat(gotIds.get(i)).isEqualTo("id-" + idx);
            Integer expectedK = (idx % 13 == 0) ? null : (idx % 50);
            // null 在格式化输出里是空串
            if (expectedK == null) {
                assertThat(gotKeys.get(i)).as("row %d null-last", i).isNull();
            } else {
                assertThat(gotKeys.get(i)).isEqualTo(expectedK);
            }
        }
    }

    /** DESC + 重复键同样稳定，且 CSV 首块含表头、逐块可还原。 */
    @Test
    void externalSortedDesc_isStableAndReassemblableAsCsv() throws Exception {
        String scope = "s-extsort-desc";
        String def = "{\"name\":\"d\",\"columns\":["
                + "{\"field\":\"id\",\"type\":\"STRING\"},"
                + "{\"field\":\"k\",\"type\":\"NUMBER\"}],"
                + "\"sort\":{\"field\":\"k\",\"direction\":\"DESC\"}}";
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);

        List<Map<String, Object>> data = new ArrayList<>();
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            data.add(row("id", "id-" + i, "k", i % 10));
            expected.add(i);
        }
        expected.sort(Comparator.comparingInt((Integer idx) -> idx % 10).reversed());

        String req = renderRequest("d", 1, data, "CSV", 64, null);
        JobResponse job = waitForTerminal(scope, submitRender(scope, req).jobId());
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);

        StringBuilder csv = new StringBuilder();
        for (int c = 0; c < job.chunkCount(); c++) {
            csv.append(fetchChunkCsv(scope, job.jobId(), c));
        }
        String[] lines = csv.toString().split("\n");
        assertThat(lines[0]).isEqualTo("id,k");
        for (int i = 0; i < 1000; i++) {
            String[] cells = lines[i + 1].split(",");
            assertThat(cells[0]).isEqualTo("id-" + expected.get(i));
            assertThat(Integer.parseInt(cells[1])).isEqualTo(expected.get(i) % 10);
        }
    }
}
