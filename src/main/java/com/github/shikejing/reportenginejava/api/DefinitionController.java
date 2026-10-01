package com.github.shikejing.reportenginejava.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shikejing.reportenginejava.api.dto.DefinitionResponse;
import com.github.shikejing.reportenginejava.api.dto.Responses;
import com.github.shikejing.reportenginejava.service.DefinitionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 定义管理接口。所有接口按 X-Scope 头鉴权，越权返回 404。
 */
@RestController
@RequestMapping("/api/definitions")
public class DefinitionController {

    private final DefinitionService definitionService;

    public DefinitionController(DefinitionService definitionService) {
        this.definitionService = definitionService;
    }

    /** 提交定义（创建新版本）。写入前完整校验，非法定义不留任何记录。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DefinitionResponse create(@RequestAttribute(ScopeFilter.SCOPE_ATTRIBUTE) String scope,
                                     @RequestBody JsonNode content) {
        return Responses.of(definitionService.create(scope, content));
    }

    /** 列出本作用域下的全部定义（最新版本摘要）。 */
    @GetMapping
    public List<DefinitionResponse> list(@RequestAttribute(ScopeFilter.SCOPE_ATTRIBUTE) String scope) {
        return definitionService.list(scope).stream().map(Responses::of).toList();
    }

    /** 读取最新版本。 */
    @GetMapping("/{name}")
    public DefinitionResponse getLatest(@RequestAttribute(ScopeFilter.SCOPE_ATTRIBUTE) String scope,
                                        @PathVariable String name) {
        return Responses.of(definitionService.getLatest(scope, name));
    }

    /** 读取指定历史版本。 */
    @GetMapping("/{name}/versions/{version}")
    public DefinitionResponse getVersion(@RequestAttribute(ScopeFilter.SCOPE_ATTRIBUTE) String scope,
                                          @PathVariable String name,
                                          @PathVariable int version) {
        return Responses.of(definitionService.getVersion(scope, name, version));
    }

    /** 删除某定义的全部历史版本。 */
    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestAttribute(ScopeFilter.SCOPE_ATTRIBUTE) String scope,
                       @PathVariable String name) {
        definitionService.delete(scope, name);
    }
}
