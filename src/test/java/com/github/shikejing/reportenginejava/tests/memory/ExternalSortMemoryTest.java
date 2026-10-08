package com.github.shikejing.reportenginejava.tests.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.shikejing.reportenginejava.engine.Renderer;
import com.github.shikejing.reportenginejava.engine.ValidatorRegistry;
import com.github.shikejing.reportenginejava.engine.v1.V1DefinitionValidator;
import com.github.shikejing.reportenginejava.engine.v1.V1Renderer;
import com.github.shikejing.reportenginejava.engine.v2.V2DefinitionValidator;
import com.github.shikejing.reportenginejava.engine.v2.V2Renderer;
import com.github.shikejing.reportenginejava.model.StoredDefinition;
import com.github.shikejing.reportenginejava.service.TestRowFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 大输入内存有界测试。
 *
 * <p>在独立 forked JVM 中以受限堆（默认 {@code -Xmx96m}，可用
 * {@code -Dmemory.test.xmx=64m} 调得更小）渲染 40 万行：
 * <ul>
 *   <li>带排序：batchSize=1000、fanIn=4 → 400 个初始归并段，必须经过多趟
 *       外部归并；输出全局有序、重复键按原始输入顺序（稳定）、分块行数正确；</li>
 *   <li>无排序：输出顺序与输入完全一致。</li>
 * </ul>
 * 进程内常驻一块占用堆相当比例的 guard 字节数组并在渲染后引用，若实现退化为
 * 全量载入内存（40 万行远大于堆容量），forked JVM 会以 OOM 退出（退出码 1）。
 * 退出码 0 即证明常驻内存只与 batchSize / fanIn 相关。
 *
 * <p>本地复现：
 * <pre>
 * mvn -Dtest=ExternalSortMemoryTest test
 * mvn -Dtest=ExternalSortMemoryTest -Dmemory.test.xmx=64m test
 * </pre>
 */
class ExternalSortMemoryTest {

    private static final int ROWS = 400_000;

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void rendersHundredsOfThousandsRowsUnderTinyHeap_sortedAndUnsorted() throws Exception {
        String xmx = System.getProperty("memory.test.xmx", "64m");
        Path cpFile = Files.createTempFile("cp-", ".txt");
        Files.writeString(cpFile, buildClasspath());

        ProcessBuilder pb = new ProcessBuilder(
                javaBin(), "-Xmx" + xmx, "-Xss512k",
                "-cp", "@" + cpFile,
                ForkRender.class.getName(),
                String.valueOf(ROWS));
        pb.redirectErrorStream(false);
        Process p = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        p.getInputStream().transferTo(out);
        p.getErrorStream().transferTo(err);
        int exit = p.waitFor();
        if (exit != 0) {
            throw new AssertionError("forked render failed (exit=" + exit + ", -Xmx" + xmx + ")\n"
                    + "--- stdout ---\n" + out.toString(StandardCharsets.UTF_8)
                    + "\n--- stderr ---\n" + err.toString(StandardCharsets.UTF_8));
        }
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("SORTED_OK");
        assertThat(stdout).contains("UNSORTED_OK");
    }

    private static String javaBin() {
        String home = System.getProperty("java.home");
        Path java = Path.of(home, "bin", "java");
        return Files.isExecutable(java) ? java.toString() : "java";
    }

    private String buildClasspath() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("mvn", "-q", "dependency:build-classpath",
                "-Dmdep.outputFile=/dev/stdout");
        pb.redirectErrorStream(false);
        Process p = pb.start();
        String cp = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor();
        return System.getProperty("java.class.path") + System.getProperty("path.separator") + cp;
    }

    /** Forked JVM 入口：不依赖 Spring，直接驱动 v1/v2 渲染器。 */
    public static final class ForkRender {

        public static void main(String[] args) throws Exception {
            int n = Integer.parseInt(args[0]);
            ObjectMapper mapper = new ObjectMapper();
            ValidatorRegistry registry = new ValidatorRegistry(
                    List.of(new V1DefinitionValidator(), new V2DefinitionValidator()));

            Path dir = Files.createTempDirectory("external-sort-mem-");
            Path work = dir.resolve("work");
            Files.createDirectories(work);
            Path input = dir.resolve("input.jsonl");

            // 输入：score 倒序且大量重复（重复组 0..99 循环），id 记录原始顺序。
            try (var w = TestRowFiles.writer(mapper, input)) {
                for (int i = 0; i < n; i++) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", "id-" + i);
                    row.put("score", (long) (n - 1 - (i % 100) * 1000L + i / 100));
                    w.write(new com.github.shikejing.reportenginejava.engine.stream.IndexedRow(i, row));
                }
            }

            // 占用约 24MB 的 guard：再叠加渲染缓冲，96MB 堆内仍有足够活动数据压力，
            // 而全量载入 40 万行的旧实现必然 OOM。
            byte[] guard = new byte[24 * 1024 * 1024];

            runSorted(mapper, registry, input, work, n);
            // 让 guard 在整个渲染期间保持存活
            if (guard[0] == 42 && guard[guard.length - 1] == 42) {
                System.out.println("GUARD_MARKER");
            }
            runUnsorted(mapper, registry, input, work, n);
        }

        private static StoredDefinition definition(ObjectMapper mapper, boolean sorted) {
            ObjectNode content = mapper.createObjectNode();
            content.put("name", "big");
            var cols = content.putArray("columns");
            cols.addObject().put("field", "id").put("type", "STRING");
            cols.addObject().put("field", "score").put("type", "NUMBER");
            if (sorted) {
                content.putObject("sort").put("field", "score").put("direction", "ASC");
            }
            StoredDefinition stored = new StoredDefinition();
            stored.setScope("t");
            stored.setName("big");
            stored.setVersion(1);
            stored.setFormatVersion(V1Renderer.FORMAT_VERSION);
            stored.setCreatedAt(java.time.Instant.now());
            stored.setContent(content);
            return stored;
        }

        private static void runSorted(ObjectMapper mapper, ValidatorRegistry registry,
                                      Path input, Path work, int n) throws Exception {
            // 清空 work
            if (Files.isDirectory(work)) {
                try (Stream<Path> s = Files.list(work)) {
                    s.forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
            Renderer renderer = new V1Renderer(registry);
            var sortIO = TestRowFiles.sortIO(mapper, work);
            // 全部校验都在 sink 内流式完成，harness 自身保持 O(1) 状态。
            long[] count = {0};
            long[] prevScore = {Long.MIN_VALUE};
            long[] prevId = {-1};
            int[] firstChunkSize = {-1};
            int[] chunkCount = {0};
            renderer.render(definition(mapper, true), input, sortIO, 1000, 4,
                    () -> {
                    }, (chunkIndex, rows) -> {
                        if (firstChunkSize[0] < 0) {
                            firstChunkSize[0] = rows.size();
                        }
                        chunkCount[0]++;
                        for (Map<String, Object> r : rows) {
                            long score = Long.parseLong(r.get("score").toString());
                            long id = Long.parseLong(r.get("id").toString().substring(3));
                            if (score < prevScore[0]) {
                                throw new AssertionError("not sorted at row " + count[0]);
                            }
                            if (score == prevScore[0] && id <= prevId[0]) {
                                throw new AssertionError("tie order not stable at row " + count[0]
                                        + ": id-" + prevId[0] + " then id-" + id);
                            }
                            prevScore[0] = score;
                            prevId[0] = id;
                            count[0]++;
                        }
                    });

            if (count[0] != n) {
                throw new AssertionError("expected " + n + " rows but got " + count[0]);
            }
            if (firstChunkSize[0] != 1000) {
                throw new AssertionError("first chunk size " + firstChunkSize[0]);
            }
            if (chunkCount[0] != (n + 999) / 1000) {
                throw new AssertionError("chunk count " + chunkCount[0]);
            }
            System.out.println("SORTED_OK rows=" + count[0] + " chunks=" + chunkCount[0]);
        }

        private static void runUnsorted(ObjectMapper mapper, ValidatorRegistry registry,
                                        Path input, Path work, int n) throws Exception {
            if (Files.isDirectory(work)) {
                try (Stream<Path> s = Files.list(work)) {
                    s.forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
            Renderer renderer = new V2Renderer(registry);
            var sortIO = TestRowFiles.sortIO(mapper, work);
            long[] expectedId = {0};
            boolean[] ok = {true};
            renderer.render(definition(mapper, false), input, sortIO, 137, 8,
                    () -> {
                    }, (chunkIndex, rows) -> {
                        for (Map<String, Object> r : rows) {
                            String want = "id-" + expectedId[0]++;
                            if (!want.equals(r.get("id"))) {
                                ok[0] = false;
                            }
                        }
                    });
            if (expectedId[0] != n || !ok[0]) {
                throw new AssertionError("unsorted order broken, rows=" + expectedId[0]);
            }
            System.out.println("UNSORTED_OK rows=" + expectedId[0]);
        }

    }
}
