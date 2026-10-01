package com.github.shikejing.reportenginejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 鉴权测试：读取、修改、删除定义以及渲染都按作用域隔离，
 * 越权请求一律 404，不泄漏其他作用域是否存在该定义。
 */
class AuthorizationTest extends IntegrationTestBase {

    @Test
    void crossScopeAccess_returns404WithoutLeakingExistence() {
        String owner = "s-owner";
        String other = "s-other";
        String body = "{\"name\":\"secret\",\"columns\":[{\"field\":\"id\",\"type\":\"STRING\"}]}";
        restTemplate.postForEntity("/api/definitions", entity(owner, body), Map.class);

        // 其他作用域读取最新版本 → 404（与不存在相同）
        ResponseEntity<Map> get = restTemplate.exchange(
                "/api/definitions/secret", HttpMethod.GET, entity(other), Map.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 其他作用域读取历史版本 → 404
        ResponseEntity<Map> getV = restTemplate.exchange(
                "/api/definitions/secret/versions/1", HttpMethod.GET, entity(other), Map.class);
        assertThat(getV.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 其他作用域列表为空
        ResponseEntity<List> list = restTemplate.exchange(
                "/api/definitions", HttpMethod.GET, entity(other), List.class);
        assertThat(list.getBody()).isEmpty();

        // 其他作用域删除 → 404
        ResponseEntity<Void> del = restTemplate.exchange(
                "/api/definitions/secret", HttpMethod.DELETE, entity(other), Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 其他作用域渲染 → 404
        Map<String, Object> data = Map.of("data", List.of(Map.of("id", "x")));
        ResponseEntity<Map> render = restTemplate.postForEntity(
                "/api/render-jobs", entity(other, "{\"defName\":\"secret\",\"data\":[{\"id\":\"x\"}]}"), Map.class);
        assertThat(render.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 属主一切正常
        ResponseEntity<Map> ownerGet = restTemplate.exchange(
                "/api/definitions/secret", HttpMethod.GET, entity(owner), Map.class);
        assertThat(ownerGet.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void missingScopeHeader_isRejected() {
        ResponseEntity<Map> resp = restTemplate.exchange(
                "/api/definitions", HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(new org.springframework.http.HttpHeaders()),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void crossScopeJobAccess_returns404() throws Exception {
        String owner = "s-job-owner";
        String other = "s-job-other";
        String body = "{\"name\":\"r\",\"columns\":[{\"field\":\"id\",\"type\":\"STRING\"}]}";
        restTemplate.postForEntity("/api/definitions", entity(owner, body), Map.class);
        String req = renderRequest("r", 1, List.of(Map.of("id", "x")), "JSON", 100, null);
        String jobId = submitRender(owner, req).jobId();
        waitForTerminal(owner, jobId);

        // 其他作用域读取作业 → 404
        ResponseEntity<Map> get = restTemplate.exchange(
                "/api/render-jobs/" + jobId, HttpMethod.GET, entity(other), Map.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 其他作用域读取结果 → 404
        ResponseEntity<Map> result = restTemplate.exchange(
                "/api/render-jobs/" + jobId + "/result?chunk=0", HttpMethod.GET, entity(other), Map.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 其他作用域取消 → 404
        ResponseEntity<Map> cancel = restTemplate.postForEntity(
                "/api/render-jobs/" + jobId + "/cancel", entity(other), Map.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
