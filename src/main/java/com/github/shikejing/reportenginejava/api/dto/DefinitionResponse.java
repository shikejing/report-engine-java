package com.github.shikejing.reportenginejava.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 定义响应：包含服务端元数据与原始定义内容。
 */
public record DefinitionResponse(
        String scope,
        String name,
        int version,
        int formatVersion,
        Instant createdAt,
        JsonNode content
) {
}
