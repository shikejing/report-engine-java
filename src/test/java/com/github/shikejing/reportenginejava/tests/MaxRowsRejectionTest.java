package com.github.shikejing.reportenginejava.tests;

import com.github.shikejing.reportenginejava.api.dto.JobResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超限输入尽早拒绝测试：行数超过 {@code report.engine.render.max-rows} 时，
 * 服务端在解析输入的过程中即返回 400 —— 不创建作业记录、不留下临时文件，
 * 客户端也不必上传完整请求体后才得到结论。
 */
@TestPropertySource(properties = "report.engine.render.max-rows=7")
class MaxRowsRejectionTest extends IntegrationTestBase {

    @Test
    void oversizedInput_isRejectedEarlyWithoutJobRecordOrTempFiles() throws Exception {
        String scope = "s-maxrows";
        String def = definitionJson("r", columns(col("id", "STRING")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);

        // 拒绝不产生作业记录（与提交前相比，作业目录数不变）
        java.nio.file.Path jobsDir = tempDir.resolve("jobs");
        long before = java.nio.file.Files.list(jobsDir).count();
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            data.add(row("id", "id-" + i));
        }
        String req = renderRequest("r", 1, data, "JSON", null, null);
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                "/api/render-jobs", entity(scope, req), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) resp.getBody().get("errors");
        assertThat(errors).anySatisfy(e -> {
            assertThat(e.get("field")).isEqualTo("data");
            assertThat((String) e.get("message")).contains("maxRows");
        });
        assertThat(java.nio.file.Files.list(jobsDir).count()).isEqualTo(before);
        // 不残留临时输入文件
        if (java.nio.file.Files.isDirectory(tempDir.resolve("tmp"))) {
            try (var s = java.nio.file.Files.list(tempDir.resolve("tmp"))) {
                assertThat(s.count()).isZero();
            }
        }
    }

    /** 恰好等于上限的输入正常渲染。 */
    @Test
    void inputAtLimit_succeeds() throws Exception {
        String scope = "s-maxrows-ok";
        String def = definitionJson("r", columns(col("id", "STRING")));
        restTemplate.postForEntity("/api/definitions", entity(scope, def), Map.class);

        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            data.add(row("id", "id-" + i));
        }
        String req = renderRequest("r", 1, data, "JSON", null, null);
        JobResponse job = waitForTerminal(scope, submitRender(scope, req).jobId());
        org.assertj.core.api.Assertions.assertThat(job.status())
                .isEqualTo(com.github.shikejing.reportenginejava.model.JobStatus.SUCCEEDED);
        assertThat(job.totalRows()).isEqualTo(7);
    }
}
