package com.github.shikejing.reportenginejava.tests.membound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.shikejing.reportenginejava.engine.ValidatorRegistry;
import com.github.shikejing.reportenginejava.engine.stream.JsonlRowInput;
import com.github.shikejing.reportenginejava.engine.stream.Progress;
import com.github.shikejing.reportenginejava.engine.v1.V1DefinitionValidator;
import com.github.shikejing.reportenginejava.engine.v1.V1Renderer;
import com.github.shikejing.reportenginejava.engine.v2.V2DefinitionValidator;
import com.github.shikejing.reportenginejava.model.Column;
import com.github.shikejing.reportenginejava.model.ColumnType;
import com.github.shikejing.reportenginejava.model.StoredDefinition;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 受限堆内存下的有界渲染复现入口（独立 JVM 运行）。
 *
 * <p>参数：{@code <dataDir> <rows> <batchSize> <sorted:true|false>}。
 * 运行方式见 {@code BoundedMemoryRenderTest} 与 README「本地验证」。
 *
 * <p>成功时进程退出码为 0 并打印校验信息；OOM 或输出不符时退出码非 0。
 * 本进程<b>不在内存中保留输入</b>：先把输入流式写成 JSON Lines，再让渲染器
 * 逐批读取，模拟真实的「请求流式落盘 → 流式渲染」链路。
 */
public final class BoundedMemoryRenderMain {

    private BoundedMemoryRenderMain() {
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args[0]);
        int rows = Integer.parseInt(args[1]);
        int batchSize = Integer.parseInt(args[2]);
        boolean sorted = Boolean.parseBoolean(args[3]);

        Path input = dataDir.resolve("input.jsonl");
        Path work = dataDir.resolve("work");
        Files.createDirectories(work);

        // 1) 流式生成输入：逆序写入，每行只短暂存在于内存，随后落盘释放。
        try (BufferedWriter w = Files.newBufferedWriter(input, StandardCharsets.UTF_8)) {
            ObjectMapper mapper = new ObjectMapper();
            for (int i = 0; i < rows; i++) {
                ObjectNode row = mapper.createObjectNode();
                // 排序键故意逆序，确保排序确实发生；字符串填充使单行有一定体积。
                row.put("id", "k" + String.format("%09d", rows - 1 - i));
                row.put("amount", i);
                w.write(mapper.writeValueAsString(row));
                w.write('\n');
            }
        }

        // 2) 构造 v2 定义（id STRING 升序 + amount NUMBER），走真实渲染器。
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode content = mapper.createObjectNode();
        content.put("name", "r");
        var cols = content.putArray("columns");
        ObjectNode c1 = cols.addObject();
        c1.put("field", "id");
        c1.put("type", "STRING");
        ObjectNode c2 = cols.addObject();
        c2.put("field", "amount");
        c2.put("type", "NUMBER");
        if (sorted) {
            ObjectNode sort = content.putObject("sort");
            sort.put("field", "id");
            sort.put("direction", "ASC");
        }

        StoredDefinition stored = new StoredDefinition();
        stored.setScope("s");
        stored.setName("r");
        stored.setVersion(1);
        stored.setFormatVersion(2);
        stored.setContent(content);

        ValidatorRegistry validators = new ValidatorRegistry(
                List.of(new V1DefinitionValidator(), new V2DefinitionValidator()));
        V1Renderer renderer = new V1Renderer(validators, mapper);

        // 3) 逐批渲染并落盘（这里简单写到 per-chunk 文件），只持有当前一批。
        Path outDir = dataDir.resolve("out");
        Files.createDirectories(outDir);
        long[] receivedRows = {0};
        List<Path> chunkFiles = new ArrayList<>();

        JsonlRowInput rowInput = new JsonlRowInput(input, mapper);
        renderer.render(stored, rowInput, rows, batchSize, work, Progress.NOOP, (chunkIndex, formatted) -> {
            try {
                Path f = outDir.resolve("chunk-" + chunkIndex + ".json");
                Files.write(f, mapper.writeValueAsBytes(formatted));
                chunkFiles.add(f);
                receivedRows[0] += formatted.size();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // 4) 校验输出：行数完整；排序时严格升序；批次大小符合预期（最后一块可较小）。
        if (receivedRows[0] != rows) {
            fail("expected " + rows + " rows but got " + receivedRows[0]);
        }
        int expectedChunks = (rows + batchSize - 1) / batchSize;
        if (chunkFiles.size() != expectedChunks) {
            fail("expected " + expectedChunks + " chunks but got " + chunkFiles.size());
        }
        String prev = null;
        long seen = 0;
        for (int i = 0; i < chunkFiles.size(); i++) {
            JsonNode arr = mapper.readTree(Files.readAllBytes(chunkFiles.get(i)));
            if (i < chunkFiles.size() - 1 && arr.size() != batchSize) {
                fail("chunk " + i + " has " + arr.size() + " rows, expected " + batchSize);
            }
            for (JsonNode rowNode : arr) {
                String id = rowNode.get("id").asText();
                if (sorted && prev != null && id.compareTo(prev) < 0) {
                    fail("not sorted at row " + seen + ": " + prev + " > " + id);
                }
                prev = id;
                seen++;
            }
        }
        if (seen != rows) {
            fail("reassembled rows " + seen + " != " + rows);
        }
        if (sorted) {
            // 排序输出：首行应为 k000000000，末行应为 k{rows-1}（前面已校验严格升序）。
            JsonNode firstChunk = mapper.readTree(Files.readAllBytes(chunkFiles.get(0)));
            String firstId = firstChunk.get(0).get("id").asText();
            String expectedFirst = String.format("k%09d", 0);
            String expectedLast = String.format("k%09d", rows - 1);
            if (!expectedFirst.equals(firstId)) {
                fail("first id expected " + expectedFirst + " but was " + firstId);
            }
            if (!expectedLast.equals(prev)) {
                fail("last id expected " + expectedLast + " but was " + prev);
            }
        }

        long usedMb = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        System.out.println("RENDER_OK rows=" + rows + " batchSize=" + batchSize
                + " sorted=" + sorted + " chunks=" + chunkFiles.size()
                + " committedHeapMB=" + usedMb);
    }

    private static void fail(String msg) {
        System.err.println("RENDER_MISMATCH: " + msg);
        System.exit(2);
    }
}
