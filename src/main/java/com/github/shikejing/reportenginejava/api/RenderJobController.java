package com.github.shikejing.reportenginejava.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.reportenginejava.api.dto.Responses;
import com.github.shikejing.reportenginejava.api.dto.JobResponse;
import com.github.shikejing.reportenginejava.model.RenderFormat;
import com.github.shikejing.reportenginejava.model.RenderJob;
import com.github.shikejing.reportenginejava.service.RenderService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * 渲染作业接口。所有接口按 X-Scope 头鉴权，越权返回 404。
 */
@RestController
@RequestMapping("/api/render-jobs")
public class RenderJobController {

    private final RenderService renderService;
    private final ObjectMapper objectMapper;

    public RenderJobController(RenderService renderService, ObjectMapper objectMapper) {
        this.renderService = renderService;
        this.objectMapper = objectMapper;
    }

    /** 提交渲染请求。请求体流式读取：边解析边落盘，超限尽早拒绝，不整体读入内存。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobResponse submit(@RequestAttribute(ScopeFilter.SCOPE_ATTRIBUTE) String scope,
                              HttpServletRequest servletRequest) {
        try (InputStream body = servletRequest.getInputStream()) {
            RenderJob job = renderService.submit(scope, body);
            return Responses.of(job);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 查询作业状态。 */
    @GetMapping("/{jobId}")
    public JobResponse get(@RequestAttribute(ScopeFilter.SCOPE_ATTRIBUTE) String scope,
                           @PathVariable String jobId) {
        return Responses.of(renderService.getJob(scope, jobId));
    }

    /** 请求取消。已终态作业幂等返回。 */
    @PostMapping("/{jobId}/cancel")
    public JobResponse cancel(@RequestAttribute(ScopeFilter.SCOPE_ATTRIBUTE) String scope,
                              @PathVariable String jobId) {
        return Responses.of(renderService.cancel(scope, jobId));
    }

    /**
     * 按批读取渲染结果。数据按块返回，服务端不一次性加载全部结果。
     * JSON 输出返回行数组；CSV 输出返回文本（首块含表头）。
     */
    @GetMapping("/{jobId}/result")
    public ResponseEntity<byte[]> result(@RequestAttribute(ScopeFilter.SCOPE_ATTRIBUTE) String scope,
                                         @PathVariable String jobId,
                                         @RequestParam(defaultValue = "0") int chunk) {
        RenderJob job = renderService.getJob(scope, jobId);
        byte[] bytes = renderService.fetchChunk(scope, jobId, chunk);
        if (job.getFormat() == RenderFormat.CSV) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                    .body(bytes);
        }
        try {
            JsonNode node = objectMapper.readTree(bytes);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsBytes(node));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
