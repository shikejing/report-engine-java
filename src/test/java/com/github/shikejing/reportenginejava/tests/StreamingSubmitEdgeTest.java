package com.github.shikejing.reportenginejava.tests;

import com.github.shikejing.reportenginejava.api.dto.JobResponse;
import com.github.shikejing.reportenginejava.model.JobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式提交解析的边界行为（保持与历史一致）：非数组 data、缺 data、
 * 畸形 JSON 都在作业创建之前以 400 拒绝，且不留作业痕迹。
 */
class StreamingSubmitEdgeTest extends IntegrationTestBase {

    @Test
    void dataNotArray_isRejected400() {
        String scope = "s-edge";
        ResponseEntity<Map> resp = restTemplate.exchange("/api/render-jobs", HttpMethod.POST,
                entity(scope, "{\"defName\":\"r\",\"defVersion\":1,\"data\":{\"a\":1}}"),
                Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void missingData_isRejected400() {
        String scope = "s-edge2";
        ResponseEntity<Map> resp = restTemplate.exchange("/api/render-jobs", HttpMethod.POST,
                entity(scope, "{\"defName\":\"r\",\"defVersion\":1}"), Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void malformedJson_isRejected400() {
        String scope = "s-edge3";
        ResponseEntity<Map> resp = restTemplate.exchange("/api/render-jobs", HttpMethod.POST,
                entity(scope, "{not-json"), Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void unknownNestedFields_areSkippedAndRequestStillRenders() throws Exception {
        String scope = "s-edge5";
        String def = "{\"name\":\"r\",\"columns\":[{\"field\":\"id\",\"type\":\"STRING\"}]}";
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);
        // 顶层未知对象 / 数组字段必须被整体跳过，不能污染后续字段解析
        String req = "{\"extra\":{\"a\":[1,2,{\"b\":3}]},\"defName\":\"r\",\"defVersion\":1,"
                + "\"tail\":[9,8],\"data\":[{\"id\":\"z\"}]}";
        JobResponse done = waitForTerminal(scope, submitRender(scope, req).jobId());
        assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void scalarRowElement_jobFailsLikeLegacy_andNoLaterEffect() throws Exception {
        // 旧实现：data 元素不是对象 → 作业运行期失败 rows[i]: row must be an object
        String scope = "s-edge4";
        String def = "{\"name\":\"r\",\"columns\":[{\"field\":\"id\",\"type\":\"STRING\"}]}";
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);
        String req = "{\"defName\":\"r\",\"defVersion\":1,\"data\":[{\"id\":\"ok\"},42]}";
        JobResponse done = waitForTerminal(scope, submitRender(scope, req).jobId());
        assertThat(done.status()).isEqualTo(JobStatus.FAILED);
        assertThat(done.error()).contains("rows[1]", "row must be an object");

        JobResponse ok = waitForTerminal(scope,
                submitRender(scope, "{\"defName\":\"r\",\"defVersion\":1,\"data\":[{\"id\":\"fine\"}]}")
                        .jobId());
        assertThat(ok.status()).isEqualTo(JobStatus.SUCCEEDED);
    }
}
