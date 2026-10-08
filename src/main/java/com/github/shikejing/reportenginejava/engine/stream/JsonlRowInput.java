package com.github.shikejing.reportenginejava.engine.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 基于 JSON Lines 文件的 {@link RowInput}：每行是一个 JSON 值（数据行）。
 *
 * <p>文件由提交请求时流式落盘产生，渲染期间每趟只通过 {@link BufferedReader}
 * 顺序读取、逐行反序列化，游标只持有当前行，不把整个文件读入内存，
 * 可重复 {@link #open()}（校验 / 排序 / 输出各一趟）。
 *
 * <p>非对象元素（数字、字符串、数组等）不在这里抛 Jackson 绑定异常，
 * 而是原样交给调用方，由渲染器第一趟检查给出与历史实现一致的错误
 * （{@code rows[i]: row must be an object}）。
 */
public class JsonlRowInput implements RowInput {

    private final Path file;
    private final ObjectMapper objectMapper;

    public JsonlRowInput(Path file, ObjectMapper objectMapper) {
        this.file = file;
        this.objectMapper = objectMapper;
    }

    @Override
    public RowCursor open() throws IOException {
        final BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
        return new RowCursor() {
            @Override
            public Object next() throws IOException {
                String line = reader.readLine();
                if (line == null) {
                    return null;
                }
                if (line.isEmpty()) {
                    return next();
                }
                // 先解析成通用 JsonNode：对象转 Map，非对象（标量 / 数组）原样返回，
                // 由渲染器的「每行必须是对象」检查统一报错。
                JsonNode node = objectMapper.readTree(line);
                if (node.isObject()) {
                    return objectMapper.convertValue(node, Map.class);
                }
                return objectMapper.treeToValue(node, Object.class);
            }

            @Override
            public void close() throws IOException {
                reader.close();
            }
        };
    }
}
