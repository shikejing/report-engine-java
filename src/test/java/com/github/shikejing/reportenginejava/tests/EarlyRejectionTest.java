package com.github.shikejing.reportenginejava.tests;

import com.github.shikejing.reportenginejava.api.dto.JobResponse;
import com.github.shikejing.reportenginejava.model.JobStatus;
import com.github.shikejing.reportenginejava.storage.JobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超限尽早拒绝测试：
 * <ul>
 *   <li>输入行数明显超过 maxRows 时，在读取输入的过程中即返回 400，
 *       而不是把整个输入处理完才报错；</li>
 *   <li>被拒绝的请求不产生任何作业记录、作业目录或暂存残留；</li>
 *   <li>正常（恰好等于上限）的请求仍可成功。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EarlyRejectionTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("report.engine.data-dir", () -> tempDir.toString());
        // 很小的上限，方便用轻量请求体验证
        registry.add("report.engine.render.max-rows", () -> "50");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JobStore jobStore;

    private HttpHeaders headers(String scope) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Scope", scope);
        h.set("Content-Type", "application/json");
        return h;
    }

    private String manyRowsRequest(int n) {
        StringBuilder sb = new StringBuilder("{\"defName\":\"r\",\"defVersion\":1,\"data\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"id-").append(i).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    @Test
    void inputOverMaxRows_isRejectedWith400BeforeReadingWholeInput_andLeavesNoTrace() throws Exception {
        String scope = "s-reject";
        String def = "{\"name\":\"r\",\"columns\":[{\"field\":\"id\",\"type\":\"STRING\"}]}";
        ResponseEntity<Map> defResp = restTemplate.exchange("/api/definitions", HttpMethod.POST,
                new HttpEntity<>(def, headers(scope)), Map.class);
        assertThat(defResp.getStatusCode().value()).isEqualTo(201);

        long jobsBefore = countJobDirs();
        // 上限 50，提交 5000 行：应在第 51 行附近立即拒绝
        ResponseEntity<Map> resp = restTemplate.exchange("/api/render-jobs", HttpMethod.POST,
                new HttpEntity<>(manyRowsRequest(5000), headers(scope)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat((String) resp.getBody().get("error")).contains("validation failed");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fieldErrors = (List<Map<String, Object>>) resp.getBody().get("errors");
        assertThat(fieldErrors).anySatisfy(e -> {
            assertThat(e.get("field")).isEqualTo("data");
            assertThat((String) e.get("message")).contains("maxRows 50");
        });

        // 不产生任何作业目录 / 作业记录
        assertThat(countJobDirs()).isEqualTo(jobsBefore);
        // 暂存目录下不残留临时输入文件
        try (Stream<Path> leftover = Files.list(jobStore.stagingDir())) {
            assertThat(leftover).isEmpty();
        }
    }

    @Test
    void inputExactlyAtMaxRows_succeeds() throws Exception {
        String scope = "s-reject-ok";
        String def = "{\"name\":\"r\",\"columns\":[{\"field\":\"id\",\"type\":\"STRING\"}]}";
        restTemplate.exchange("/api/definitions", HttpMethod.POST,
                new HttpEntity<>(def, headers(scope)), Map.class);

        ResponseEntity<JobResponse> resp = restTemplate.exchange("/api/render-jobs", HttpMethod.POST,
                new HttpEntity<>(manyRowsRequest(50), headers(scope)), JobResponse.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        JobResponse submitted = resp.getBody();

        JobResponse done = waitForTerminal(scope, submitted.jobId());
        assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(done.totalRows()).isEqualTo(50);
    }

    private long countJobDirs() throws Exception {
        Path jobsRoot = tempDir.resolve("jobs");
        if (!Files.isDirectory(jobsRoot)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(jobsRoot)) {
            // .staging 不是作业目录，排除掉
            return s.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith(".")).count();
        }
    }

    private JobResponse waitForTerminal(String scope, String jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            JobResponse j = restTemplate.exchange("/api/render-jobs/" + jobId, HttpMethod.GET,
                    new HttpEntity<Void>(headers(scope)), JobResponse.class).getBody();
            if (j != null && j.status().isTerminal()) {
                return j;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("job did not finish: " + jobId);
    }
}
