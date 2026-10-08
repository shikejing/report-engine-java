package com.github.shikejing.reportenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shikejing.reportenginejava.api.dto.JobResponse;
import com.github.shikejing.reportenginejava.model.JobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 带排序定义的流式渲染端到端测试：外部排序产出的分块与
 * 「在内存中按历史规则稳定排序」的参考结果逐条一致，逐块可完整还原。
 */
class SortedStreamingRenderTest extends IntegrationTestBase {

    @Test
    void sortedLargeRender_chunksMatchReferenceStableSort() throws Exception {
        String scope = "s-sort-stream";
        // id STRING 升序，amount NUMBER；重复 id 时验证稳定性（原始输入顺序）
        String def = "{\"name\":\"r\",\"columns\":[{\"field\":\"id\",\"type\":\"STRING\"},"
                + "{\"field\":\"amount\",\"type\":\"NUMBER\"}],"
                + "\"sort\":{\"field\":\"id\",\"direction\":\"ASC\"}}";
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);

        int n = 10_000;
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // id 逆序且有重复（%700 个不同值），制造稳定性决胜；重复 id 必须保持输入相对顺序
            data.add(row("id", "id-" + String.format("%05d", (n - 1 - i) % 700),
                    "amount", i));
        }

        // 每批 333 → 多块（覆盖跨块排序结果）
        String req = renderRequest("r", 1, data, "JSON", 333, null);
        JobResponse done = waitForTerminal(scope, submitRender(scope, req).jobId());
        assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);

        List<JsonNode> reassembled = new ArrayList<>();
        for (int c = 0; c < done.chunkCount(); c++) {
            JsonNode chunk = fetchChunkJson(scope, done.jobId(), c);
            for (JsonNode r : chunk) {
                reassembled.add(r);
            }
        }
        assertThat(reassembled).hasSize(n);

        // 参考：历史实现语义 = 稳定排序（null 末尾），key 为 id 字符串
        List<Map<String, Object>> expected = new ArrayList<>(data);
        expected.sort(Comparator.comparing((Map<String, Object> r) -> (String) r.get("id")));

        for (int i = 0; i < n; i++) {
            JsonNode got = reassembled.get(i);
            Map<String, Object> want = expected.get(i);
            assertThat(got.get("id").asText()).isEqualTo(want.get("id"));
            // amount 随原始行一起排序：重复 id 时必须保持输入顺序（稳定）
            assertThat(got.get("amount").asInt()).isEqualTo(((Number) want.get("amount")).intValue());
        }

        // 首末元素
        assertThat(reassembled.get(0).get("id").asText())
                .isEqualTo(expected.get(0).get("id"));
        assertThat(reassembled.get(n - 1).get("id").asText())
                .isEqualTo(expected.get(n - 1).get("id"));
    }

    @Test
    void sortedRender_descendingNullsLast_isConsistent() throws Exception {
        String scope = "s-sort-desc";
        String def = "{\"name\":\"r\",\"columns\":[{\"field\":\"v\",\"type\":\"NUMBER\"}],"
                + "\"sort\":{\"field\":\"v\",\"direction\":\"DESC\"}}";
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);

        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            data.add(row("v", i));
        }
        data.add(row());        // null（唯一的空键，必须排在最末尾）
        data.add(row("v", 500));

        String req = renderRequest("r", 1, data, "JSON", 100, null);
        JobResponse done = waitForTerminal(scope, submitRender(scope, req).jobId());
        assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);

        List<String> ids = new ArrayList<>();
        for (int c = 0; c < done.chunkCount(); c++) {
            JsonNode chunk = fetchChunkJson(scope, done.jobId(), c);
            for (JsonNode r : chunk) {
                ids.add(r.get("v").asText());
            }
        }
        assertThat(ids).hasSize(1002);
        // DESC：第一个是 999；唯一的空键（null → 空串）永远在最末尾
        assertThat(ids.get(0)).isEqualTo("999");
        assertThat(ids.get(1001)).isEmpty();
        // 非空部分单调不增（1001 个非空值：0..999 外加一个 500，故 500 出现两次）
        for (int i = 1; i < 1001; i++) {
            assertThat(Integer.parseInt(ids.get(i - 1)))
                    .as("position %d", i)
                    .isGreaterThanOrEqualTo(Integer.parseInt(ids.get(i)));
        }
    }
}
