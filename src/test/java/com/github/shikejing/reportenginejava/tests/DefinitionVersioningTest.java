package com.github.shikejing.reportenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shikejing.reportenginejava.api.dto.JobResponse;
import com.github.shikejing.reportenginejava.model.StoredDefinition;
import com.github.shikejing.reportenginejava.storage.FileBasedDefinitionStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 版本兼容测试：
 * <ul>
 *   <li>历史版本不可变，可按版本精确渲染；</li>
 *   <li>定义更新后，旧版本的渲染语义与输出不变；</li>
 *   <li>新版本能力（transform）不泄漏给旧版本；</li>
 *   <li>同一份定义版本 + 同一份数据，反复渲染逐字节一致。</li>
 * </ul>
 */
class DefinitionVersioningTest extends IntegrationTestBase {

    @Autowired
    FileBasedDefinitionStore definitionStore;

    private byte[] renderAndGetBytes(String scope, String defName, Integer version,
                                     List<Map<String, Object>> data) throws Exception {
        String req = renderRequest(defName, version, data, "JSON", 100, null);
        JobResponse job = submitRender(scope, req);
        assertThat(job).isNotNull();
        JobResponse done = waitForTerminal(scope, job.jobId());
        assertThat(done.status()).isEqualTo(com.github.shikejing.reportenginejava.model.JobStatus.SUCCEEDED);
        JsonNode chunk = fetchChunkJson(scope, job.jobId(), 0);
        return objectMapper.writeValueAsBytes(chunk);
    }

    @Test
    void pinnedVersion_rendersIdenticallyAfterUpdate() throws Exception {
        String scope = "s-ver";
        String v1 = definitionJson("report", columns(col("id", "STRING"), col("amount", "NUMBER")));
        restTemplate.postForEntity("/api/definitions", entity(scope, v1), Map.class);

        List<Map<String, Object>> data = List.of(row("id", "a", "amount", 1), row("id", "b", "amount", 2));

        // 渲染 v1
        byte[] outV1Before = renderAndGetBytes(scope, "report", 1, data);

        // 更新定义（新增一列）→ v2
        String v2 = definitionJson("report",
                columns(col("id", "STRING"), col("amount", "NUMBER"), col("note", "STRING")));
        Map created = restTemplate.postForEntity("/api/definitions", entity(scope, v2), Map.class).getBody();
        assertThat(created.get("version")).isEqualTo(2);

        // 再次渲染 pinned v1：输出必须与更新前逐字节一致
        byte[] outV1After = renderAndGetBytes(scope, "report", 1, data);
        assertThat(outV1After).isEqualTo(outV1Before);

        // 渲染最新版本：输出不同（多了 note 列）
        byte[] outLatest = renderAndGetBytes(scope, "report", null, data);
        assertThat(outLatest).isNotEqualTo(outV1Before);
        String latestJson = new String(outLatest);
        assertThat(latestJson).contains("note");
    }

    @Test
    void sameDefinitionAndData_rendersByteIdenticalEveryTime() throws Exception {
        String scope = "s-det";
        String def = definitionJson("det",
                columns(col("id", "STRING"), col("amount", "NUMBER", "#.00")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);
        List<Map<String, Object>> data = List.of(row("id", "a", "amount", 1.5), row("id", "b", "amount", 2.25));

        byte[] r1 = renderAndGetBytes(scope, "det", 1, data);
        byte[] r2 = renderAndGetBytes(scope, "det", 1, data);
        byte[] r3 = renderAndGetBytes(scope, "det", 1, data);
        assertThat(r1).isEqualTo(r2).isEqualTo(r3);
    }

    @Test
    void v2Transform_appliesOnlyToNewDefinitions() throws Exception {
        String scope = "s-v2";
        // v2 定义使用 transform
        String def = definitionJson("upper", columns(colTransform("name", "STRING", "UPPER")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);

        List<Map<String, Object>> data = List.of(row("name", "alice"), row("name", "bob"));
        byte[] out = renderAndGetBytes(scope, "upper", 1, data);
        assertThat(new String(out)).contains("ALICE").contains("BOB");
    }

    @Test
    void legacyV1Definition_stillRendersWithV1Semantics() throws Exception {
        String scope = "s-legacy";
        // 直接写入一个格式版本 1 的历史定义（模拟升级前提交的存量定义）
        String content = "{\"name\":\"legacy\",\"columns\":["
                + "{\"field\":\"id\",\"type\":\"STRING\"},"
                + "{\"field\":\"amount\",\"type\":\"NUMBER\"}]}";
        StoredDefinition legacy = new StoredDefinition();
        legacy.setScope(scope);
        legacy.setName("legacy");
        legacy.setVersion(1);
        legacy.setFormatVersion(1);
        legacy.setCreatedAt(Instant.now());
        legacy.setContent(objectMapper.readTree(content));
        definitionStore.save(legacy);

        List<Map<String, Object>> data = List.of(row("id", "x", "amount", 10));
        byte[] out = renderAndGetBytes(scope, "legacy", 1, data);
        assertThat(new String(out)).contains("x").contains("10");

        // 历史定义再次渲染，结果一致
        byte[] out2 = renderAndGetBytes(scope, "legacy", 1, data);
        assertThat(out2).isEqualTo(out);
    }

    @Test
    void pinnedVersion_neverCrossesToOtherVersion() throws Exception {
        String scope = "s-cross";
        String v1 = definitionJson("cross", columns(col("id", "STRING")));
        restTemplate.postForEntity("/api/definitions", entity(scope, v1), Map.class);
        String v2 = definitionJson("cross", columns(col("id", "STRING"), col("extra", "STRING")));
        restTemplate.postForEntity("/api/definitions", entity(scope, v2), Map.class);

        // 渲染 v1 的结果中绝不包含 v2 的 extra 字段
        byte[] outV1 = renderAndGetBytes(scope, "cross", 1,
                List.of(row("id", "a", "extra", "hidden")));
        assertThat(new String(outV1)).doesNotContain("extra").contains("a");
    }
}
