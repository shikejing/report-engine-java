package com.github.shikejing.reportenginejava.tests;

import com.github.shikejing.reportenginejava.model.error.FieldError;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定义校验测试：字段缺失、类型不符、引用不存在对象、字段组合不合法
 * 一律在写入前拒绝，错误定位到具体字段，且不留下任何记录。
 */
class DefinitionValidationTest extends IntegrationTestBase {

    private ResponseEntity<Map> postDefinition(String scope, String body) {
        return restTemplate.postForEntity("/api/definitions", entity(scope, body), Map.class);
    }

    private List<Map<String, Object>> errors(ResponseEntity<Map> resp) {
        return (List<Map<String, Object>>) resp.getBody().get("errors");
    }

    @Test
    void missingName_isRejectedWithFieldPath() {
        String body = "{\"columns\":[{\"field\":\"id\",\"type\":\"STRING\"}]}";
        ResponseEntity<Map> resp = postDefinition("s1", body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errors(resp)).anySatisfy(e -> assertThat(e.get("field")).isEqualTo("name"));
    }

    @Test
    void missingColumns_isRejected() {
        String body = "{\"name\":\"d1\"}";
        ResponseEntity<Map> resp = postDefinition("s1", body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errors(resp)).anySatisfy(e -> assertThat(e.get("field")).isEqualTo("columns"));
    }

    @Test
    void columnsNotArray_isRejected() {
        String body = "{\"name\":\"d1\",\"columns\":\"oops\"}";
        ResponseEntity<Map> resp = postDefinition("s1", body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errors(resp)).anySatisfy(e -> assertThat(e.get("field")).isEqualTo("columns"));
    }

    @Test
    void columnMissingField_isRejectedWithIndex() {
        String body = "{\"name\":\"d1\",\"columns\":[{\"type\":\"STRING\"}]}";
        ResponseEntity<Map> resp = postDefinition("s1", body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errors(resp)).anySatisfy(e -> assertThat(e.get("field")).isEqualTo("columns[0].field"));
    }

    @Test
    void columnBadType_isRejected() {
        String body = "{\"name\":\"d1\",\"columns\":[{\"field\":\"id\",\"type\":\"NUMBERX\"}]}";
        ResponseEntity<Map> resp = postDefinition("s1", body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errors(resp)).anySatisfy(e -> assertThat(e.get("field")).isEqualTo("columns[0].type"));
    }

    @Test
    void formatOnStringColumn_isRejected() {
        String body = "{\"name\":\"d1\",\"columns\":[{\"field\":\"id\",\"type\":\"STRING\",\"format\":\"0.00\"}]}";
        ResponseEntity<Map> resp = postDefinition("s1", body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errors(resp)).anySatisfy(e -> assertThat(e.get("field")).isEqualTo("columns[0].format"));
    }

    @Test
    void sortReferencingMissingColumn_isRejected() {
        String body = "{\"name\":\"d1\",\"columns\":[{\"field\":\"id\",\"type\":\"STRING\"}],"
                + "\"sort\":{\"field\":\"notExist\",\"direction\":\"ASC\"}}";
        ResponseEntity<Map> resp = postDefinition("s1", body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errors(resp)).anySatisfy(e -> assertThat(e.get("field")).isEqualTo("sort.field"));
    }

    @Test
    void duplicateColumnFields_isRejected() {
        String body = "{\"name\":\"d1\",\"columns\":["
                + "{\"field\":\"id\",\"type\":\"STRING\"},"
                + "{\"field\":\"id\",\"type\":\"NUMBER\"}]}";
        ResponseEntity<Map> resp = postDefinition("s1", body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errors(resp)).anySatisfy(e -> assertThat(e.get("field")).isEqualTo("columns[1].field"));
    }

    @Test
    void unknownRootField_isRejected() {
        String body = "{\"name\":\"d1\",\"columns\":[{\"field\":\"id\",\"type\":\"STRING\"}],\"extra\":1}";
        ResponseEntity<Map> resp = postDefinition("s1", body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errors(resp)).anySatisfy(e -> assertThat(e.get("field")).isEqualTo("$.extra"));
    }

    @Test
    void multipleErrors_areAllReported() {
        String body = "{\"columns\":[{\"type\":\"BAD\"}]}";
        ResponseEntity<Map> resp = postDefinition("s1", body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        List<Map<String, Object>> errs = errors(resp);
        assertThat(errs).extracting(e -> e.get("field"))
                .contains("name", "columns[0].field", "columns[0].type");
    }

    @Test
    void invalidDefinition_leavesNoRecord() {
        String scope = "s-noleak";
        String body = "{\"name\":\"bad\",\"columns\":[{\"field\":\"id\",\"type\":\"BAD\"}]}";
        ResponseEntity<Map> resp = postDefinition(scope, body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 读取不存在
        ResponseEntity<Map> get = restTemplate.exchange(
                "/api/definitions/bad", HttpMethod.GET, entity(scope), Map.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 作用域内列表为空
        ResponseEntity<List> list = restTemplate.exchange(
                "/api/definitions", HttpMethod.GET, entity(scope), List.class);
        assertThat(list.getBody()).isEmpty();
    }

    @Test
    void validDefinition_isCreatedAndVersioned() {
        String body = "{\"name\":\"good\",\"columns\":[{\"field\":\"id\",\"type\":\"STRING\"}]}";
        ResponseEntity<Map> resp = postDefinition("s1", body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("version")).isEqualTo(1);
        assertThat(resp.getBody().get("formatVersion")).isEqualTo(2);
    }
}
