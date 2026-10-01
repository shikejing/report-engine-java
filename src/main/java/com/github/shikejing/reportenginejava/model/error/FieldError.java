package com.github.shikejing.reportenginejava.model.error;

/**
 * 字段级校验错误，{@code field} 为可定位的字段路径，例如
 * {@code columns[1].type}、{@code sort.field}。
 */
public record FieldError(String field, String message) {
}
